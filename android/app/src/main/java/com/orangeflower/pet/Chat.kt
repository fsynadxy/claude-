package com.orangeflower.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** A model provider preset: request format, endpoint and suggested models. */
data class Provider(
    val id: String,
    val label: String,
    val format: String,
    val base: String,
    val models: List<String>,
    val keyHint: String,
)

/** API settings, kept in the app's private preferences on the phone. Each provider keeps its own key and model. */
object ChatConfig {
    const val FORMAT_ANTHROPIC = "anthropic"
    const val FORMAT_OPENAI = "openai"
    const val CUSTOM = "custom"

    val providers = listOf(
        Provider("opencode", "OpenCode", FORMAT_ANTHROPIC, "https://opencode.ai/inference/anthropic/v1",
            listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"), "Key 在 opencode.ai 的控制台创建"),
        Provider("anthropic", "Anthropic", FORMAT_ANTHROPIC, "https://api.anthropic.com/v1",
            listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"), "Key 在 console.anthropic.com 创建"),
        Provider("openai", "OpenAI", FORMAT_OPENAI, "https://api.openai.com/v1",
            listOf("gpt-5.4-mini", "gpt-5.5", "gpt-5.4-nano"), "Key 在 platform.openai.com 创建"),
        Provider("google", "Google Gemini", FORMAT_OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"), "Key 在 aistudio.google.com 创建"),
        Provider("deepseek", "DeepSeek", FORMAT_OPENAI, "https://api.deepseek.com",
            listOf("deepseek-chat", "deepseek-reasoner"), "Key 在 platform.deepseek.com 创建"),
        Provider(CUSTOM, "自定义", FORMAT_OPENAI, "", emptyList(), "填写任意兼容 OpenAI 或 Anthropic 格式的接口"),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("chat", Context.MODE_PRIVATE)

    fun provider(c: Context): Provider {
        migrate(c)
        val id = prefs(c).getString("provider", "opencode")
        return providers.firstOrNull { it.id == id } ?: providers[0]
    }

    fun format(c: Context): String {
        val p = provider(c)
        return if (p.id == CUSTOM) prefs(c).getString("format_custom", FORMAT_OPENAI) ?: FORMAT_OPENAI else p.format
    }

    fun base(c: Context): String {
        val p = provider(c)
        return if (p.id == CUSTOM) prefs(c).getString("base_custom", "") ?: "" else p.base
    }

    fun model(c: Context): String = modelFor(c, provider(c))
    fun key(c: Context): String = keyFor(c, provider(c).id)

    fun modelFor(c: Context, p: Provider) = (prefs(c).getString("model_${p.id}", "") ?: "").ifBlank { p.models.firstOrNull() ?: "" }
    fun keyFor(c: Context, id: String) = prefs(c).getString("key_$id", "") ?: ""
    fun customBase(c: Context) = prefs(c).getString("base_custom", "") ?: ""
    fun customFormat(c: Context) = prefs(c).getString("format_custom", FORMAT_OPENAI) ?: FORMAT_OPENAI

    fun userName(c: Context) = (prefs(c).getString("user", "") ?: "").ifBlank { "你" }
    fun rawUserName(c: Context) = prefs(c).getString("user", "") ?: ""
    fun isReady(c: Context) = key(c).isNotBlank() && base(c).isNotBlank() && model(c).isNotBlank()

    fun save(c: Context, providerId: String, model: String, key: String, user: String, customBase: String, customFormat: String) {
        val e = prefs(c).edit()
            .putString("provider", providerId)
            .putString("model_$providerId", model.trim())
            .putString("key_$providerId", key.trim())
            .putString("user", user.trim())
        if (providerId == CUSTOM) e.putString("base_custom", customBase.trim()).putString("format_custom", customFormat)
        e.apply()
    }

    /** Settings saved by 1.0.3 (single format/base/model/key) become the matching provider entry. */
    private fun migrate(c: Context) {
        val p = prefs(c)
        if (p.contains("provider") || !p.contains("key")) return
        val base = p.getString("base", "") ?: ""
        val known = providers.firstOrNull { it.id != CUSTOM && it.base == base }
        val id = known?.id ?: CUSTOM
        val e = p.edit().putString("provider", id)
            .putString("key_$id", p.getString("key", ""))
            .putString("model_$id", p.getString("model", ""))
        if (known == null) e.putString("base_custom", base).putString("format_custom", p.getString("format", FORMAT_OPENAI))
        e.remove("key").remove("base").remove("model").remove("format").apply()
    }
}

data class Msg(val role: String, val content: String)

/** Expression tag the model puts at the start of a reply, e.g. 【开心】. */
object Emotion {
    private val tag = Regex("[【\\[](开心|害羞|生气|委屈|难过|惊讶|思考|平静)[】\\]]")

    /** Returns (emotion or null, text without tags). */
    fun parse(text: String): Pair<String?, String> = tag.find(text)?.groupValues?.get(1) to strip(text)

    fun strip(text: String) = tag.replace(text, "").trim()
}

/** User-side messages that describe what was done to the pet rather than said. */
const val ACTION_PREFIX = "【互动】"


/** Per-card chat history, newest last. */
object ChatStore {
    private const val KEEP = 40
    private fun prefs(c: Context) = c.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(c: Context, cardId: String): MutableList<Msg> {
        val raw = prefs(c).getString(cardId, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Msg(o.getString("role"), o.getString("content"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(c: Context, cardId: String, list: List<Msg>) {
        val arr = JSONArray()
        list.takeLast(KEEP).forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        prefs(c).edit().putString(cardId, arr.toString()).apply()
    }

    fun clear(c: Context, cardId: String) = prefs(c).edit().remove(cardId).apply()
}

/** Talks to an Anthropic Messages or OpenAI Chat Completions compatible endpoint. */
object ChatClient {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private const val PET_NOTE =
        "【桌宠说明】你现在住在{{user}}的手机屏幕上，是一个Q版桌宠。你的回复会先显示在头顶的小气泡里，" +
            "完整内容显示在聊天窗口。平时用一到三句自然口语回答；除非对方要求，不要长篇大论；" +
            "不要使用 Markdown、标题或列表符号。" +
            "\n每次回复的最开头，用一个标签标出你此刻的表情，只能从【开心】【害羞】【生气】【委屈】【惊讶】【思考】【平静】里选一个，" +
            "例如：【开心】嘿嘿，谢谢。标签只在开头出现一次。" +
            "\n当{{user}}的消息以【互动】开头时，表示{{user}}在屏幕上对你做了这个动作，而不是在说话。" +
            "用一两句简短、符合你性格的反应回应，不要复述动作本身。"

    fun systemPrompt(c: Context, card: Card): String {
        val user = ChatConfig.userName(c)
        return (card.persona.trim() + "\n\n" + PET_NOTE)
            .replace("{{char}}", card.name)
            .replace("{{user}}", user)
    }

    /** Sends [history] (ending with the user's message) and calls [done] on the main thread. */
    fun send(c: Context, card: Card, history: List<Msg>, done: (Result<String>) -> Unit) {
        val app = c.applicationContext
        val format = ChatConfig.format(app)
        val base = ChatConfig.base(app).trimEnd('/')
        val model = ChatConfig.model(app)
        val key = ChatConfig.key(app)
        val system = systemPrompt(app, card)
        val turns = normalize(history.takeLast(16))
        pool.execute {
            val result = runCatching {
                if (format == ChatConfig.FORMAT_OPENAI) openAi(base, model, key, system, turns)
                else anthropic(base, model, key, system, turns)
            }
            main.post { done(result) }
        }
    }

    /** Merges consecutive same-role turns and drops leading assistant turns (APIs expect a user turn first). */
    private fun normalize(list: List<Msg>): List<Msg> {
        val out = mutableListOf<Msg>()
        for (m in list) {
            if (out.isEmpty() && m.role != "user") continue
            if (out.isNotEmpty() && out.last().role == m.role) {
                out[out.size - 1] = Msg(m.role, out.last().content + "\n" + m.content)
            } else {
                out.add(m)
            }
        }
        return out
    }

    private fun anthropic(base: String, model: String, key: String, system: String, turns: List<Msg>): String {
        val url = if (base.endsWith("/messages")) base else "$base/messages"
        val msgs = JSONArray()
        turns.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 600)
            .put("system", system)
            .put("messages", msgs)
        // the official API wants only x-api-key; gateways such as OpenCode also accept a bearer token
        val bearer = if (base.contains("api.anthropic.com")) null else key
        val json = post(url, body, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), bearer)
        val content = json.optJSONArray("content") ?: throw IllegalStateException(errorText(json))
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.optString("type") == "text") sb.append(part.optString("text"))
        }
        return sb.toString().trim().ifEmpty { throw IllegalStateException("她没有说话（空回复）") }
    }

    private fun openAi(base: String, model: String, key: String, system: String, turns: List<Msg>): String {
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        // OpenAI's newer models only accept max_completion_tokens; most compatible APIs use max_tokens
        val official = base.contains("api.openai.com")
        fun body(newParam: Boolean) = JSONObject().put("model", model).put("messages", msgs)
            .put(if (newParam) "max_completion_tokens" else "max_tokens", if (newParam) 2000 else 800)
        val json = try {
            post(url, body(official), emptyMap(), key)
        } catch (e: IllegalStateException) {
            val m = e.message ?: ""
            if (m.startsWith("HTTP 400") && (m.contains("max_tokens") || m.contains("max_completion_tokens"))) {
                post(url, body(!official), emptyMap(), key)
            } else throw e
        }
        val choices = json.optJSONArray("choices") ?: throw IllegalStateException(errorText(json))
        return choices.getJSONObject(0).getJSONObject("message").optString("content").trim()
            .ifEmpty { throw IllegalStateException("她没有说话（空回复）") }
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>, bearer: String?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "ClauNiang/1.0")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                val t = text.trim()
                if (t.startsWith("[")) org.json.JSONArray(t).getJSONObject(0) else JSONObject(t)
            } catch (e: Exception) {
                throw IllegalStateException("HTTP $code：${text.take(160)}")
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code：${errorText(json)}")
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun errorText(json: JSONObject): String {
        val err = json.opt("error")
        return when (err) {
            is JSONObject -> err.optString("message", err.toString())
            is String -> err
            else -> json.toString().take(160)
        }
    }
}
