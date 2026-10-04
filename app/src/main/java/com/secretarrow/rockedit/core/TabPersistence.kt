package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the open-tab session (URIs + active index) so closing the app and
 * reopening it restores the same tab set. Content is NOT stored here — tabs
 * are restored as [EditorTab.pending] placeholders and loaded lazily on first
 * activation. Pure JVM, backed by [KeyValueStore].
 */
class TabPersistence(private val kv: KeyValueStore) {

    /** Metadata of one persisted tab. */
    data class SavedTab(
        val uri: String?,
        val name: String,
        val charset: String,
        val lineBreak: String,
        val readOnly: Boolean
    )

    data class SavedTabs(val tabs: List<SavedTab>, val activeIndex: Int)

    fun save(tabs: List<EditorTab>, activeIndex: Int) {
        val arr = JSONArray()
        for (t in tabs) {
            arr.put(
                JSONObject()
                    .put(F_URI, t.uri ?: "")
                    .put(F_NAME, t.name)
                    .put(F_CHARSET, t.charsetName)
                    .put(F_LINE_BREAK, t.lineBreak.name)
                    .put(F_READ_ONLY, t.readOnly)
            )
        }
        kv.putString(
            KEY,
            JSONObject().put(F_TABS, arr).put(F_ACTIVE, activeIndex).toString()
        )
    }

    fun load(): SavedTabs? {
        val raw = kv.getString(KEY, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray(F_TABS) ?: return null
            val tabs = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        SavedTab(
                            uri = o.optString(F_URI).ifEmpty { null },
                            name = o.optString(F_NAME),
                            charset = o.optString(F_CHARSET, EncodingDetector.DEFAULT_CHARSET),
                            lineBreak = o.optString(F_LINE_BREAK, LineBreak.LF.name),
                            readOnly = o.optBoolean(F_READ_ONLY, false)
                        )
                    )
                }
            }
            SavedTabs(tabs, obj.optInt(F_ACTIVE, 0))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        kv.remove(KEY)
    }

    companion object {
        const val KEY = "open_tabs"
        private const val F_TABS = "tabs"
        private const val F_ACTIVE = "active"
        private const val F_URI = "uri"
        private const val F_NAME = "name"
        private const val F_CHARSET = "charset"
        private const val F_LINE_BREAK = "line_break"
        private const val F_READ_ONLY = "read_only"
    }
}
