package com.secretarrow.rockedit.core

import org.json.JSONObject

/** Saved cursor state for one file. */
data class SessionCursor(
    val uri: String,
    val selStart: Int,
    val selEnd: Int,
    val scrollY: Int,
    val updatedAt: Long
)

/**
 * Remembers the caret position (and scroll offset) of recently opened files so
 * reopening a file restores the exact editing position (roadmap: session
 * resume). Backed by [KeyValueStore]; entries are capped LRU-style.
 */
class SessionStore(private val kv: KeyValueStore, private val capacity: Int = MAX_FILES) {

    /** Saves (or overwrites) the cursor state of [uri]. */
    fun saveCursor(
        uri: String,
        selStart: Int,
        selEnd: Int,
        scrollY: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (uri.isBlank()) return
        val map = parse(kv.getString(KEY, null) ?: "{}").toMutableMap()
        map.remove(uri)
        map[uri] = SessionCursor(uri, selStart.coerceAtLeast(0), selEnd.coerceAtLeast(0), scrollY, timestamp)
        // Evict oldest beyond capacity.
        val trimmed = map.entries
            .sortedByDescending { it.value.updatedAt }
            .take(capacity)
            .associate { it.toPair() }
        save(trimmed)
    }

    /** Returns the stored cursor for [uri], or null. */
    fun loadCursor(uri: String): SessionCursor? = parse(kv.getString(KEY, null) ?: "{}")[uri]

    /** Removes the stored cursor of [uri] (e.g. after the file was deleted). */
    fun clear(uri: String) {
        val map = parse(kv.getString(KEY, null) ?: "{}").toMutableMap()
        if (map.remove(uri) != null) save(map)
    }

    private fun save(map: Map<String, SessionCursor>) {
        val obj = JSONObject()
        map.values.forEach {
            obj.put(
                it.uri,
                JSONObject()
                    .put("selStart", it.selStart)
                    .put("selEnd", it.selEnd)
                    .put("scrollY", it.scrollY)
                    .put("updatedAt", it.updatedAt)
            )
        }
        kv.putString(KEY, obj.toString())
    }

    companion object {
        const val KEY = "session_cursors"
        const val MAX_FILES = 30

        /** Tolerant parser: malformed JSON or entries are skipped, never thrown. */
        fun parse(json: String): Map<String, SessionCursor> {
            if (json.isBlank()) return emptyMap()
            return try {
                val obj = JSONObject(json)
                val out = HashMap<String, SessionCursor>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val uri = keys.next()
                    val entry = obj.optJSONObject(uri) ?: continue
                    out[uri] = SessionCursor(
                        uri = uri,
                        selStart = entry.optInt("selStart", 0),
                        selEnd = entry.optInt("selEnd", 0),
                        scrollY = entry.optInt("scrollY", 0),
                        updatedAt = entry.optLong("updatedAt", 0L)
                    )
                }
                out
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
