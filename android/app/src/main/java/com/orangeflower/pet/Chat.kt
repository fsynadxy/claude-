package com.orangeflower.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** API settings, kept in the app's private preferences on the phone. */
object ChatConfig {
    const val FORMAT_ANTHROPIC = "anthropic"
    const val FORMAT_OPENAI = "openai"
    const val DEFAULT_ANTHROPIC_BASE = "https://opencode.ai/inference/anthropic/v1"
    const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5"
    const val DEFAULT_OPENAI_BASE = "https://opencode.ai/inference/openai/v1"
    const val DEFAULT_OPENAI_MODEL = "deepseek-v4.1-flash"

    private fun prefs(c: Context) = c.getSharedPreferences("chat", Context.MODE_PRIVATE)

    fun format(c: Context) = prefs(c).getString("format", FORMAT_ANTHROPIC) ?: FORMAT_ANTHROPIC
    fun base(c: Context) = prefs(c).getString("base", DEFAULT_ANTHROPIC_BASE) ?: DEFAULT_ANTHROPIC_BASE
    fun model(c: Context) = prefs(c).getString("model", DEFAULT_ANTHROPIC_MODEL) ?: DEFAULT_ANTHROPIC_MODEL
    fun key(c: Context) = prefs(c).getString("key", "") ?: ""
    fun userName(c: Context) = (prefs(c).getString("user", "") ?: "").ifBlank { "你" }
    fun rawUserName(c: Context) = prefs(c).getString("user", "") ?: ""
    fun isReady(c: Context) = key(c).isNotBlank() && base(c).isNotBlank() && model(c).isNotBlank()

    fun save(c: Context, format: String, base: String, model: String, key: String, user: String) {
        prefs(c).edit()
            .putString("format", format)
            .putString("base", base.trim())
            .putString("model", model.trim())
            .putString("key", key.trim())
            .putString("user", user.trim())
            .apply()
    }
}

data class Msg(val role: String, val content: String)

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
            "不要使用 Markdown、标题或列表符号。"

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
        val json = post(url, body, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), key)
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
        val body = JSONObject().put("model", model).put("max_tokens", 600).put("messages", msgs)
        val json = post(url, body, emptyMap(), key)
        val choices = json.optJSONArray("choices") ?: throw IllegalStateException(errorText(json))
        return choices.getJSONObject(0).getJSONObject("message").optString("content").trim()
            .ifEmpty { throw IllegalStateException("她没有说话（空回复）") }
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>, key: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "ClauNiang/1.0")
            conn.setRequestProperty("Authorization", "Bearer $key")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
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
