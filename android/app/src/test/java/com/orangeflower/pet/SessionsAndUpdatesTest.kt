package com.orangeflower.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsAndUpdatesTest {

    @Test fun conversationTitleComesFromFirstTypedMessage() {
        val msgs = listOf(
            Msg("user", ACTION_PREFIX + "摸了摸你的头"),
            Msg("assistant", "【害羞】嗯……"),
            Msg("user", "今天想聊聊周末去哪儿玩\n第二行不要"),
        )
        assertEquals("今天想聊聊周末去哪儿玩", ChatStore.autoTitle(msgs))
        assertEquals("一二三四五六七八九十一二三四五六…", ChatStore.autoTitle(listOf(Msg("user", "一二三四五六七八九十一二三四五六七八"))))
        assertEquals("", ChatStore.autoTitle(listOf(Msg("user", "【识图】（给你看了一眼现在的屏幕）"))))
        assertEquals("新对话", Session("a", "", 0).shownTitle())
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(Updates.isNewer("1.3.0", "1.2.0"))
        assertTrue(Updates.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Updates.isNewer("1.3.1", "1.3"))
        assertFalse(Updates.isNewer("1.3.0", "1.3.0"))
        assertFalse(Updates.isNewer("1.2.9", "1.3.0"))
    }

    @Test fun releaseJsonKeepsOnlyThisProjectsLinks() {
        val good = """{"tag_name":"v1.3.0","html_url":"https://github.com/fsynadxy/claude-/releases/tag/v1.3.0","body":"新功能",
            "assets":[{"browser_download_url":"https://github.com/fsynadxy/claude-/releases/download/v1.3.0/ClauNiang-1.3.0.apk"}]}"""
        val r = Updates.parse(good)!!
        assertEquals("1.3.0", r.version)
        assertEquals("https://github.com/fsynadxy/claude-/releases/download/v1.3.0/ClauNiang-1.3.0.apk", r.apkUrl)

        val odd = """{"tag_name":"v9.9.9","html_url":"https://evil.example/x","assets":[{"browser_download_url":"https://evil.example/a.apk"}]}"""
        val o = Updates.parse(odd)!!
        assertEquals("", o.apkUrl)
        assertEquals(Updates.RELEASES_PAGE, o.pageUrl)

        assertNull(Updates.parse("""{"tag_name":"v2.0.0","prerelease":true}"""))
    }
}
