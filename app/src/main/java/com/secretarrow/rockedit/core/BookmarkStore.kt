package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/** One bookmark: a marked line inside one file, with an optional label. */
data class Bookmark(val uri: String, val line: Int, val label: String, val createdAt: Long)

/**
 * Per-file line bookmarks, persisted across sessions.
 * Backed by [KeyValueStore] so it runs in plain JVM unit tests.
 */
class BookmarkStore(private val kv: KeyValueStore, private val capacity: Int = MAX_ITEMS) {

    /** All bookmarks of [uri], sorted ascending by line number. */
    fun list(uri: String): List<Bookmark> =
        parse(kv.getString(KEY, null) ?: "[]")
            .filter { it.uri == uri }
            .sortedBy { it.line }

    /** True when [line] in [uri] is bookmarked. */
    fun has(uri: String, line: Int): Boolean =
        parse(kv.getString(KEY, null) ?: "[]").any { it.uri == uri && it.line == line }

    /**
     * Toggles the bookmark on [line] of [uri]. Returns true when the bookmark
     * was added, false when it was removed.
     */
    fun toggle(uri: String, line: Int, label: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        if (uri.isBlank() || line < 1) return has(uri, line)
        val all = parse(kv.getString(KEY, null) ?: "[]").toMutableList()
        val existing = all.indexOfFirst { it.uri == uri && it.line == line }
        if (existing >= 0) {
            all.removeAt(existing)
            save(all)
            return false
        }
        all.add(Bookmark(uri, line, label, timestamp))
        save(all)
        return true
    }

    /** Removes every bookmark of one file. */
    fun clear(uri: String) {
        val all = parse(kv.getString(KEY, null) ?: "[]")
        save(all.filterNot { it.uri == uri })
    }

    private fun save(items: List<Bookmark>) {
        val capped = items.sortedByDescending { it.createdAt }.take(capacity)
        val arr = JSONArray()
        capped.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("line", it.line)
                    .put("label", it.label)
                    .put("createdAt", it.createdAt)
            )
        }
        kv.putString(KEY, arr.toString())
    }

    companion object {
        const val KEY = "bookmarks"
        const val MAX_ITEMS = 200

        /** Tolerant parser: skips malformed entries instead of crashing. */
        fun parse(json: String): List<Bookmark> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val uri = obj.optString("uri", "")
                        val line = obj.optInt("line", -1)
                        if (uri.isBlank() || line < 1) continue
                        add(Bookmark(uri, line, obj.optString("label", ""), obj.optLong("createdAt", 0L)))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
