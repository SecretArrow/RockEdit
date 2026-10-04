package com.secretarrow.rockedit.core

/**
 * Minimal key-value abstraction so repositories can be unit tested on the JVM
 * without Android framework dependencies.
 */
interface KeyValueStore {
    fun getString(
        key: String,
        defValue: String?,
    ): String?

    fun putString(
        key: String,
        value: String,
    )

    fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean

    fun putBoolean(
        key: String,
        value: Boolean,
    )

    fun remove(key: String)

    fun clear()

    fun contains(key: String): Boolean
}

/** Simple in-memory implementation used by unit tests. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, Any>()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = map[key] as? String ?: defValue

    override fun putString(
        key: String,
        value: String,
    ) {
        map[key] = value
    }

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = map[key] as? Boolean ?: defValue

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun clear() {
        map.clear()
    }

    override fun contains(key: String): Boolean = map.containsKey(key)
}
