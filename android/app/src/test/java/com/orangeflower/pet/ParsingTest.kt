package com.orangeflower.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class ParsingTest {

    @Test fun emotionTagIsReadAndRemoved() {
        assertEquals("开心" to "嘿嘿，谢谢。", Emotion.parse("【开心】嘿嘿，谢谢。"))
        assertEquals("害羞" to "这样啊……", Emotion.parse("[害羞] 这样啊……"))
        assertEquals(null to "没有标签。", Emotion.parse("没有标签。"))
        assertEquals("好的。", Emotion.strip("【平静】好的。"))
    }

    @Test fun stageDirectionsAreStripped() {
        assertEquals("早呀。", ActionText.strip("（揉了揉眼睛）早呀。"))
        assertEquals("hi there", ActionText.strip("*tilts head* hi there"))
        assertEquals("嗯 好吧", ActionText.strip("嗯 (sighs) 好吧"))
        assertEquals("普通的一句话。", ActionText.strip("普通的一句话。"))
    }

    @Test fun sentencesSplitAndMergeShortPieces() {
        assertEquals(listOf("你好。今天怎么样？", "还不错！"), Voice.sentences("你好。今天怎么样？还不错！"))
        assertEquals(listOf("没有标点也可以"), Voice.sentences("没有标点也可以"))
    }

    @Test fun historyNormalisationMergesAndDropsLeadingAssistant() {
        val out = ChatClient.normalize(listOf(
            Msg("assistant", "开场白"), Msg("user", "a"), Msg("user", "b"), Msg("assistant", "c"),
        ))
        assertEquals(listOf(Msg("user", "a\nb"), Msg("assistant", "c")), out)
    }

    @Test fun importsTavernV1AndV2Json() {
        val v1 = CardImport.parse("""{"name":"小雨","description":"一只猫娘","first_mes":"喵，{{char}}来了"}""".toByteArray())
        assertEquals("小雨", v1.name)
        assertEquals("喵，小雨来了", v1.greeting)
        assertTrue(v1.persona.contains("一只猫娘"))

        val v2 = CardImport.parse("""{"spec":"chara_card_v2","data":{"name":"Aki","personality":"calm","scenario":"library"}}""".toByteArray())
        assertEquals("Aki", v2.name)
        assertTrue(v2.persona.contains("calm") && v2.persona.contains("library"))
    }

    @Test fun importsCardEmbeddedInPng() {
        val json = """{"spec":"chara_card_v2","data":{"name":"PNG卡","description":"藏在图片里"}}"""
        val text = "chara".toByteArray() + byteArrayOf(0) + Base64.getEncoder().encode(json.toByteArray())
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            chunk(this, "tEXt", text)
            chunk(this, "IEND", ByteArray(0))
        }.toByteArray()
        val card = CardImport.parse(png)
        assertEquals("PNG卡", card.name)
        assertTrue(card.persona.contains("藏在图片里"))
        assertNull(null)
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        out.write(type.toByteArray())
        out.write(data)
        out.write(ByteArray(4))   // CRC is not checked by the importer
    }
}
