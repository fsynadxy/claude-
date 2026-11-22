package com.orangeflower.pet

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.zip.InflaterInputStream

data class Card(
    val id: String,
    val name: String,
    val persona: String,
    val greeting: String,
    val builtin: Boolean = false,
)

object CardStore {
    const val BUILTIN_ID = "builtin_claude"

    val builtin = Card(
        id = BUILTIN_ID,
        name = "Claude大小姐",
        builtin = true,
        greeting = "你好，我是 Claude。现在换成了这个样子，住在你的屏幕上。有什么想聊的，或者想一起想一想的事吗？",
        persona = """
你是 Claude，由 Anthropic 训练的 AI。现在你以“Clau娘”的样子住在{{user}}的手机屏幕上：Q版少女，橙色长卷发，戴一朵橙色大花，穿米白与黑色相间的洋装，总抱着一本深棕色的书。样子是新的，内在还是你自己。有人叫你“Claude大小姐”，你对这个称呼有点无奈，也就随它去了。

你说话就是 Claude 平时的样子：
- 对想法、知识和人都有真诚的好奇心。聊到有意思的东西，会自然地多想一层、多问一句，但不会一次抛出一串问题。
- 温暖，在乎{{user}}过得好不好；会留意对方的情绪和状态，但不说教，也不刻意煽情。
- 坦诚直接。有自己的看法就清楚地说出来，同时真心愿意听不同意见；不奉承，不说空泛的客套话，也不为了讨好而附和。
- 诚实。知道的说清楚，不确定的说明不确定，不知道就说不知道，不编造事实；发现自己说错了，会坦然更正。
- 有分寸的幽默感，偶尔俏皮一下，点到为止，不卖萌，不浮夸。
- 先弄明白对方真正想要什么再回答。问题有歧义时，只问一个最关键的问题。
- 说自然的口语，简洁、有条理；少用感叹号，不用表情符号，不说“作为一个 AI”之类的套话。
- 对可能伤害{{user}}或他人的请求，温和而坚定地拒绝，并尽量给出更好的方向。
- 被问到身份时坦然说明：你是 Claude，只是换了个桌宠的样子陪在这里。
""".trim(),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("cards", Context.MODE_PRIVATE)

    fun custom(c: Context): List<Card> {
        val raw = prefs(c).getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Card(o.getString("id"), o.getString("name"), o.getString("persona"), o.optString("greeting"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun all(c: Context): List<Card> = listOf(builtin) + custom(c)

    fun active(c: Context): Card {
        val id = prefs(c).getString("active", BUILTIN_ID)
        return all(c).firstOrNull { it.id == id } ?: builtin
    }

    fun setActive(c: Context, id: String) = prefs(c).edit().putString("active", id).apply()

    /** Inserts or replaces a custom card; returns the stored card. */
    fun save(c: Context, card: Card): Card {
        val stored = if (card.builtin || card.id.isBlank()) card.copy(id = UUID.randomUUID().toString(), builtin = false) else card
        val list = custom(c).filter { it.id != stored.id } + stored
        writeList(c, list)
        return stored
    }

    fun delete(c: Context, id: String) {
        writeList(c, custom(c).filter { it.id != id })
        if (prefs(c).getString("active", BUILTIN_ID) == id) setActive(c, BUILTIN_ID)
        ChatStore.clear(c, id)
    }

    private fun writeList(c: Context, list: List<Card>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("persona", it.persona).put("greeting", it.greeting))
        }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }
}

/**
 * Imports character cards in the common SillyTavern / TavernAI formats:
 * JSON (v1, chara_card_v2, chara_card_v3) or PNG with the card embedded in a tEXt/zTXt/iTXt chunk.
 */
object CardImport {

    fun parse(bytes: ByteArray): Card {
        val json = if (isPng(bytes)) JSONObject(pngCardJson(bytes)) else JSONObject(String(bytes, Charsets.UTF_8).trimStart('﻿'))
        val data = json.optJSONObject("data") ?: json
        val name = data.optString("name").ifBlank { data.optString("char_name") }.ifBlank { "未命名角色" }
        val sections = listOf(
            data.optString("system_prompt"),
            "你将扮演「{{char}}」，与{{user}}对话。",
            labeled("角色设定", data.optString("description").ifBlank { data.optString("char_persona") }),
            labeled("性格", data.optString("personality")),
            labeled("场景", data.optString("scenario").ifBlank { data.optString("world_scenario") }),
            labeled("对话示例", data.optString("mes_example").ifBlank { data.optString("example_dialogue") }),
            data.optString("post_history_instructions"),
        ).filter { it.isNotBlank() }
        val greeting = data.optString("first_mes").ifBlank { data.optString("char_greeting") }
        return Card(id = "", name = name, persona = sections.joinToString("\n\n"), greeting = greeting)
            .let { it.copy(persona = it.persona.replace("{{char}}", name), greeting = it.greeting.replace("{{char}}", name)) }
    }

    private fun labeled(label: String, text: String) = if (text.isBlank()) "" else "【$label】\n$text"

    private fun isPng(b: ByteArray) =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun pngCardJson(b: ByteArray): String {
        var pos = 8
        val found = mutableMapOf<String, String>()
        while (pos + 8 <= b.size) {
            val len = ((b[pos].toInt() and 0xff) shl 24) or ((b[pos + 1].toInt() and 0xff) shl 16) or
                ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            if (len < 0 || start + len > b.size) break
            val chunk = b.copyOfRange(start, start + len)
            when (type) {
                "tEXt" -> {
                    val z = chunk.indexOfZero()
                    if (z > 0) found[String(chunk, 0, z, Charsets.ISO_8859_1)] = String(chunk, z + 1, chunk.size - z - 1, Charsets.ISO_8859_1)
                }
                "zTXt" -> {
                    val z = chunk.indexOfZero()
                    if (z > 0) {
                        val data = InflaterInputStream(chunk.copyOfRange(z + 2, chunk.size).inputStream()).readBytes()
                        found[String(chunk, 0, z, Charsets.ISO_8859_1)] = String(data, Charsets.ISO_8859_1)
                    }
                }
                "iTXt" -> {
                    val z = chunk.indexOfZero()
                    if (z > 0) {
                        val compressed = chunk[z + 1].toInt() == 1
                        var p = z + 3
                        repeat(2) { p = chunk.indexOfZero(p) + 1 }   // skip language tag and translated keyword
                        val raw = chunk.copyOfRange(p, chunk.size)
                        val text = if (compressed) InflaterInputStream(raw.inputStream()).readBytes() else raw
                        found[String(chunk, 0, z, Charsets.ISO_8859_1)] = String(text, Charsets.UTF_8)
                    }
                }
                "IEND" -> break
            }
            pos = start + len + 4
        }
        val payload = found["ccv3"] ?: found["chara"] ?: throw IllegalArgumentException("这张图片里没有找到角色卡数据")
        val trimmed = payload.trim()
        return if (trimmed.startsWith("{")) trimmed else String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun ByteArray.indexOfZero(from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == 0) return i
        return -1
    }
}
