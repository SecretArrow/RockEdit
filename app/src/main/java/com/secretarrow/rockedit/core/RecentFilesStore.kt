package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/** One recently opened file. [uri] is a content:// URI string. */
data class RecentFile(
    val uri: String,
    val name: String,
    val lastOpened: Long,
)

/**
 * Recently opened files list, capped and de-duplicated (most recent first).
 * Backed by [KeyValueStore] so it runs in plain JVM unit tests.
 */
class RecentFilesStore(
    private val kv: KeyValueStore,
    private val capacity: Int = MAX_ITEMS,
) {
    fun list(): List<RecentFile> = parse(kv.getString(KEY, null) ?: "[]")

    /** Adds or moves [uri] to the top. Returns the new list. */
    fun add(
        uri: String,
        name: String,
        timestamp: Long = System.currentTimeMillis(),
    ): List<RecentFile> {
        if (uri.isBlank()) return list()
        val rest = list().filterNot { it.uri == uri }
        val updated =
            buildList {
                add(RecentFile(uri, name.ifBlank { FileNames.sanitize(uri.substringAfterLast('/')) }, timestamp))
                addAll(rest)
            }.take(capacity)
        save(updated)
        return updated
    }

    /** Removes one entry. Returns the new list. */
    fun remove(uri: String): List<RecentFile> {
        val updated = list().filterNot { it.uri == uri }
        save(updated)
        return updated
    }

    /** Clears every entry. */
    fun clear() {
        kv.remove(KEY)
    }

    private fun save(items: List<RecentFile>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("name", it.name)
                    .put("lastOpened", it.lastOpened),
            )
        }
        kv.putString(KEY, arr.toString())
    }

    companion object {
        const val KEY = "recent_files"
        const val MAX_ITEMS = 40

        /** Tolerant parser: skips malformed entries instead of crashing. */
        fun parse(json: String): List<RecentFile> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val uri = obj.optString("uri", "")
                        if (uri.isBlank()) continue
                        add(RecentFile(uri, obj.optString("name", ""), obj.optLong("lastOpened", 0L)))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
