package com.hangge.rootpilot.testing

import com.hangge.rootpilot.data.prefs.KeyValueStore

/** In-memory KeyValueStore so SharedPreferences-backed stores can be unit tested on the JVM. */
class InMemoryKeyValueStore : KeyValueStore {
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun getString(key: String, defaultValue: String?): String? = strings[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        strings[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue

    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleans[key] ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun remove(key: String) {
        strings.remove(key)
        ints.remove(key)
        booleans.remove(key)
    }

    fun raw(key: String): String? = strings[key]
}
