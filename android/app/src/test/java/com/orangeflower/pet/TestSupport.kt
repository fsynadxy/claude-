package com.orangeflower.pet

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/** In-memory SharedPreferences, enough for the stores under test. */
class MemPrefs : SharedPreferences {
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

class FakeContext(private val files: java.io.File? = null) : ContextWrapper(null) {
    val prefs = HashMap<String, MemPrefs>()
    override fun getFilesDir(): java.io.File = files ?: error("no files dir in this test")
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemPrefs() }
}

