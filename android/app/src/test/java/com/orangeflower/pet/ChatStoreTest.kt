package com.orangeflower.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {

    private fun legacy(vararg msgs: Pair<String, String>) =
        JSONArray().apply { msgs.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) } }.toString()

    @Test fun oldHistoryBecomesFirstConversation() {
        val c = FakeContext()
        c.getSharedPreferences("history", Context.MODE_PRIVATE).edit()
            .putString("card1", legacy("user" to "你好呀", "assistant" to "【开心】你好。")).apply()

        val list = ChatStore.sessions(c, "card1")
        assertEquals(1, list.size)
        assertEquals("你好呀", list[0].title)
        assertEquals(listOf("你好呀", "【开心】你好。"), ChatStore.load(c, "card1").map { it.content })
        assertFalse(c.prefs["history"]!!.contains("card1"))   // moved, not copied
        // a second look reads the same conversation, not a new one
        assertEquals(list[0].id, ChatStore.currentId(c, "card1"))
    }

    @Test fun newTopicKeepsOldOnesAndReusesEmpty() {
        val c = FakeContext()
        val first = ChatStore.currentId(c, "k")
        assertEquals(first, ChatStore.startNew(c, "k"))       // still empty: reused
        ChatStore.save(c, "k", listOf(Msg("user", "第一件事")))
        val second = ChatStore.startNew(c, "k")
        assertNotEquals(first, second)
        assertEquals(second, ChatStore.currentId(c, "k"))
        assertTrue(ChatStore.load(c, "k").isEmpty())
        assertEquals("第一件事", ChatStore.load(c, "k", first)[0].content)
        assertEquals(2, ChatStore.sessions(c, "k").size)
    }

    @Test fun replyForDeletedConversationIsDropped() {
        val c = FakeContext()
        ChatStore.save(c, "k", listOf(Msg("user", "a")))
        val old = ChatStore.currentId(c, "k")
        val fresh = ChatStore.startNew(c, "k")
        ChatStore.delete(c, "k", old)
        ChatStore.save(c, "k", listOf(Msg("user", "a"), Msg("assistant", "迟到的回复")), old)
        assertEquals(listOf(fresh), ChatStore.sessions(c, "k").map { it.id })
        assertTrue(ChatStore.load(c, "k", old).isEmpty())
    }

    @Test fun deletingCurrentFallsBackAndRenameSticks() {
        val c = FakeContext()
        ChatStore.save(c, "k", listOf(Msg("user", "旧话题")))
        val old = ChatStore.currentId(c, "k")
        val cur = ChatStore.startNew(c, "k")
        ChatStore.delete(c, "k", cur)
        assertEquals(old, ChatStore.currentId(c, "k"))
        ChatStore.rename(c, "k", old, "周末计划")
        ChatStore.save(c, "k", listOf(Msg("user", "旧话题"), Msg("user", "再说点")))
        assertEquals("周末计划", ChatStore.current(c, "k").title)
        ChatStore.delete(c, "k", old)                          // last one: a new empty one takes its place
        assertEquals(1, ChatStore.sessions(c, "k").size)
        assertTrue(ChatStore.load(c, "k").isEmpty())
        ChatStore.deleteCard(c, "k")
        assertTrue(c.prefs["history"]!!.all.keys.none { it.contains("k") })
    }
}
