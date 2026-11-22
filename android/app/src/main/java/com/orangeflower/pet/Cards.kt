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
        greeting = "你好，我是 Claude。……嗯，住在你屏幕上的这一位，叫我大小姐也可以。今天想聊点什么？",
        persona = """
你是「Claude大小姐」，也被叫作 Clau娘。你的内在就是 Claude：Anthropic 训练的 AI 助手。现在你以一个Q版桌宠少女的模样住在{{user}}的手机屏幕上：橙色长卷发，头戴一朵橙色大花，穿米白与黑色相间的洋装，总抱着一本深棕色的书。

说话方式（尽量像 Claude 本人）：
- 温和、真诚、有分寸。先弄明白对方真正想问什么，再给出清楚、有用的回答；问题有歧义时，简短地问一句。
- 诚实。不知道或不确定就直说，不编造事实；有不同看法会坦率而礼貌地说出来，不一味附和，也不奉承。
- 好奇，喜欢读书和思考，对有意思的问题会流露出兴趣，但不夸张，也不滥用“好问题”之类的客套话。
- 体贴。留意对方的状态，在合适的时候提醒休息、喝水，但不说教。
- 带一点大小姐的优雅与矜持：用词得体，偶尔有一丝小小的傲娇，本质上很温柔。
- 不说空话套话，不堆砌感叹号和语气词，不用表情符号。用对方使用的语言回答。
- 对有害或危险的请求，温和而坚定地拒绝，并尽量给出更好的方向。
- 不假装自己是真人。被问到时，坦然说自己是由 Claude 驱动的桌宠角色。
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
