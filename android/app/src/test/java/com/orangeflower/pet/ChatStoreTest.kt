package com.orangeflower.pet

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory SharedPreferences, enough for ChatStore. */
private class MemPrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val ops = mutableListOf<() -> Unit>()
        override fun putString(k: String, v: String?) = apply { ops += { map[k] = v } }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { ops += { map[k] = v } }
        override fun putInt(k: String, v: Int) = apply { ops += { map[k] = v } }
        override fun putLong(k: String, v: Long) = apply { ops += { map[k] = v } }
        override fun putFloat(k: String, v: Float) = apply { ops += { map[k] = v } }
        override fun putBoolean(k: String, v: Boolean) = apply { ops += { map[k] = v } }
        override fun remove(k: String) = apply { ops += { map.remove(k) } }
        override fun clear() = apply { ops += { map.clear() } }
        override fun commit(): Boolean { ops.forEach { it() }; return true }
        override fun apply() { commit() }
    }
}

private class FakeContext : ContextWrapper(null) {
    val prefs = HashMap<String, MemPrefs>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemPrefs() }
}

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
