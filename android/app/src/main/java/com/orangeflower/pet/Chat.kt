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

/**
 * Stage directions such as （笑了笑）, (sighs) or *tilts head*. They are dimmed in the bubble and
 * skipped by the voice: the pet's animation already shows what she does.
 */
object ActionText {
    val pattern = Regex("（[^（）\\n]{0,40}）|\\([^()\\n]{0,40}\\)|\\*[^*\\n]{1,40}\\*")
    fun strip(text: String) = pattern.replace(text, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    /** Greys out stage directions in [text] (for bubbles and the chat history). */
    fun dim(text: CharSequence, color: Int): CharSequence {
        val sb = android.text.SpannableStringBuilder(text)
        for (m in pattern.findAll(text)) {
            sb.setSpan(android.text.style.ForegroundColorSpan(color), m.range.first, m.range.last + 1, 0)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), m.range.first, m.range.last + 1, 0)
        }
        return sb
    }
}

/** User-side messages that describe what was done to the pet rather than said. */
const val ACTION_PREFIX = "【互动】"


/** One conversation's entry in the list (messages are stored separately). */
data class Session(val id: String, val title: String, val updated: Long, val named: Boolean = false) {
    fun shownTitle() = title.ifBlank { "新对话" }
}

/**
 * Chat history, kept as several conversations per character card. One of them is "current":
 * the pet, the bubble input and the chat panel all talk in it. Starting a new topic keeps the
 * old conversations. The single history of 1.2 and earlier becomes the card's first conversation.
 */
object ChatStore {
    private const val KEEP = 200
    private fun prefs(c: Context) = c.getSharedPreferences("history", Context.MODE_PRIVATE)
    private fun idxKey(cardId: String) = "idx:$cardId"
    private fun msgKey(cardId: String, sid: String) = "msg:$cardId:$sid"

    private class Index(var current: String, val list: MutableList<Session>)

    /** Title made from the first thing the user typed (actions and screen looks don't count). */
    fun autoTitle(list: List<Msg>): String {
        val first = list.firstOrNull { it.role == "user" && !it.content.startsWith(ACTION_PREFIX) && !it.content.startsWith("【识图】") }
            ?: return ""
        val line = first.content.trim().lineSequence().first().trim()
        return if (line.length > 16) line.take(16) + "…" else line
    }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()

    private fun readIndex(raw: String): Index? = try {
        val o = JSONObject(raw)
        val arr = o.getJSONArray("list")
        val list = MutableList(arr.length()) { i ->
            val s = arr.getJSONObject(i)
            Session(s.getString("id"), s.optString("title"), s.optLong("updated"), s.optBoolean("named"))
        }
        if (list.isEmpty()) null else Index(o.optString("cur", list[0].id), list)
    } catch (e: Exception) {
        null
    }

    private fun writeIndex(c: Context, cardId: String, idx: Index) {
        val arr = JSONArray()
        idx.list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated).put("named", it.named))
        }
        prefs(c).edit().putString(idxKey(cardId), JSONObject().put("cur", idx.current).put("list", arr).toString()).apply()
    }

    private fun index(c: Context, cardId: String): Index {
        val p = prefs(c)
        p.getString(idxKey(cardId), null)?.let { readIndex(it) }?.let { idx ->
            if (idx.list.none { it.id == idx.current }) idx.current = idx.list.maxBy { it.updated }.id
            return idx
        }
        // first use: a fresh conversation, holding the old single history if there is one
        val sid = newId()
        val legacy = p.getString(cardId, null)
        val msgs = legacy?.let { parse(it) } ?: emptyList()
        val idx = Index(sid, mutableListOf(Session(sid, autoTitle(msgs), System.currentTimeMillis())))
        val e = p.edit()
        if (legacy != null) e.putString(msgKey(cardId, sid), legacy).remove(cardId)
        e.apply()
        writeIndex(c, cardId, idx)
        return idx
    }

    private fun parse(raw: String): MutableList<Msg> = try {
        val arr = JSONArray(raw)
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Msg(o.getString("role"), o.getString("content"))
        }
    } catch (e: Exception) {
        mutableListOf()
    }

    /** Conversations of [cardId], most recently used first. */
    fun sessions(c: Context, cardId: String): List<Session> = index(c, cardId).list.sortedByDescending { it.updated }

    fun currentId(c: Context, cardId: String): String = index(c, cardId).current

    fun current(c: Context, cardId: String): Session = index(c, cardId).let { idx -> idx.list.first { it.id == idx.current } }

    fun exists(c: Context, cardId: String, sid: String) = index(c, cardId).list.any { it.id == sid }

    fun load(c: Context, cardId: String, sid: String = currentId(c, cardId)): MutableList<Msg> =
        prefs(c).getString(msgKey(cardId, sid), null)?.let { parse(it) } ?: mutableListOf()

    /** Saves a conversation; does nothing if it was deleted meanwhile (e.g. while a reply was on its way). */
    fun save(c: Context, cardId: String, list: List<Msg>, sid: String = currentId(c, cardId)) {
        val idx = index(c, cardId)
        val i = idx.list.indexOfFirst { it.id == sid }
        if (i < 0) return
        val arr = JSONArray()
        list.takeLast(KEEP).forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        prefs(c).edit().putString(msgKey(cardId, sid), arr.toString()).apply()
        val s = idx.list[i]
        idx.list[i] = s.copy(title = if (s.named) s.title else autoTitle(list), updated = System.currentTimeMillis())
        writeIndex(c, cardId, idx)
    }

    /** Starts a new topic and makes it current. An empty current conversation is reused instead. */
    fun startNew(c: Context, cardId: String): String {
        val idx = index(c, cardId)
        if (load(c, cardId, idx.current).isEmpty()) return idx.current
        val sid = newId()
        idx.list.add(Session(sid, "", System.currentTimeMillis()))
        idx.current = sid
        writeIndex(c, cardId, idx)
        return sid
    }

    fun select(c: Context, cardId: String, sid: String) {
        val idx = index(c, cardId)
        if (idx.list.none { it.id == sid }) return
        idx.current = sid
        writeIndex(c, cardId, idx)
    }

    fun rename(c: Context, cardId: String, sid: String, title: String) {
        val idx = index(c, cardId)
        val i = idx.list.indexOfFirst { it.id == sid }
        if (i < 0) return
        val t = title.trim()
        idx.list[i] = if (t.isEmpty()) idx.list[i].copy(title = autoTitle(load(c, cardId, sid)), named = false)
        else idx.list[i].copy(title = t.take(30), named = true)
        writeIndex(c, cardId, idx)
    }

    /** Deletes a conversation. If it was the current one, the most recent other becomes current (or a new empty one). */
    fun delete(c: Context, cardId: String, sid: String) {
        val idx = index(c, cardId)
        if (!idx.list.removeAll { it.id == sid }) return
        prefs(c).edit().remove(msgKey(cardId, sid)).apply()
        if (idx.list.isEmpty()) {
            val fresh = newId()
            idx.list.add(Session(fresh, "", System.currentTimeMillis()))
        }
        if (idx.current == sid) idx.current = idx.list.maxBy { it.updated }.id
        writeIndex(c, cardId, idx)
    }

    /** Count of things the user said in a conversation (for the list). */
    fun userCount(c: Context, cardId: String, sid: String) = load(c, cardId, sid).count { it.role == "user" }

    /** Removes every conversation of a deleted card. */
    fun deleteCard(c: Context, cardId: String) {
        val p = prefs(c)
        val e = p.edit()
        p.all.keys.filter { it == cardId || it == idxKey(cardId) || it.startsWith("msg:$cardId:") }.forEach { e.remove(it) }
        e.apply()
    }
}

/** Talks to an Anthropic Messages or OpenAI Chat Completions compatible endpoint. */
object ChatClient {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private const val PET_NOTE =
        "【桌宠说明】你现在住在{{user}}的手机屏幕上，是一个Q版桌宠。你的回复会先显示在头顶的小气泡里，" +
            "完整内容显示在聊天窗口。平时用一到三句自然口语回答；除非对方要求，不要长篇大论；" +
            "不要使用 Markdown、标题或列表符号。" +
            "\n只写你说出口的话：不要写动作、神态或心理描写，也不要用括号或星号描述动作，你的动作和表情会由桌宠动画表现。" +
            "\n每次回复的最开头，用一个标签标出你此刻的表情，只能从【开心】【害羞】【生气】【委屈】【惊讶】【思考】【平静】里选一个，" +
            "例如：【开心】嘿嘿，谢谢。标签只在开头出现一次。" +
            "\n当{{user}}的消息以【互动】开头时，表示{{user}}在屏幕上对你做了这个动作，而不是在说话。" +
            "用一两句简短、符合你性格的反应回应，不要复述动作本身。" +
            "\n当{{user}}的消息以【识图】开头时，附带的图片是{{user}}手机当前屏幕的截图（截图里没有你自己）。" +
            "像陪在旁边的朋友一样，简短说说你看到了什么、{{user}}可能在做什么，有用的话顺便给一点建议；看不清或不确定就直说。"

    fun systemPrompt(c: Context, card: Card): String {
        val user = ChatConfig.userName(c)
        return (card.persona.trim() + "\n\n" + PET_NOTE)
            .replace("{{char}}", card.name)
            .replace("{{user}}", user)
    }

    /**
     * Adds [userText] to the card's current conversation, asks the model and stores the reply in that
     * same conversation, even if another one is opened meanwhile. Returns that conversation's id.
     */
    fun ask(c: Context, card: Card, userText: String, image: String? = null, done: (Result<String>) -> Unit): String {
        val app = c.applicationContext
        val sid = ChatStore.currentId(app, card.id)
        val history = ChatStore.load(app, card.id, sid)
        history.add(Msg("user", userText))
        ChatStore.save(app, card.id, history, sid)
        send(app, card, history, image) { result ->
            result.onSuccess { reply ->
                val h = ChatStore.load(app, card.id, sid)
                h.add(Msg("assistant", reply))
                ChatStore.save(app, card.id, h, sid)
            }
            done(result)
        }
        return sid
    }

    /** Sends [history] (ending with the user's message) and calls [done] on the main thread. */
    fun send(c: Context, card: Card, history: List<Msg>, image: String? = null, done: (Result<String>) -> Unit) {
        val app = c.applicationContext
        val format = ChatConfig.format(app)
        val base = ChatConfig.base(app).trimEnd('/')
        val model = ChatConfig.model(app)
        val key = ChatConfig.key(app)
        val system = systemPrompt(app, card)
        val turns = normalize(history.takeLast(16))
        pool.execute {
            val result = runCatching {
                if (format == ChatConfig.FORMAT_OPENAI) openAi(base, model, key, system, turns, image)
                else anthropic(base, model, key, system, turns, image)
            }
            main.post { done(result) }
        }
    }

    /** Merges consecutive same-role turns and drops leading assistant turns (APIs expect a user turn first). */
    internal fun normalize(list: List<Msg>): List<Msg> {
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

    private fun anthropic(base: String, model: String, key: String, system: String, turns: List<Msg>, image: String?): String {
        val url = if (base.endsWith("/messages")) base else "$base/messages"
        val msgs = JSONArray()
        turns.forEachIndexed { i, m ->
            if (image != null && i == turns.lastIndex && m.role == "user") {
                val content = JSONArray()
                    .put(JSONObject().put("type", "image").put("source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", image)))
                    .put(JSONObject().put("type", "text").put("text", m.content))
                msgs.put(JSONObject().put("role", "user").put("content", content))
            } else {
                msgs.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
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

    private fun openAi(base: String, model: String, key: String, system: String, turns: List<Msg>, image: String?): String {
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEachIndexed { i, m ->
            if (image != null && i == turns.lastIndex && m.role == "user") {
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.content))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")))
                msgs.put(JSONObject().put("role", "user").put("content", content))
            } else {
                msgs.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
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
