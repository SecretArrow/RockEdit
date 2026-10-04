package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports and restores app data (settings + store payloads) as a single JSON
 * document. Pure JVM logic over [KeyValueStore]; the UI layer only supplies
 * the key list and reads/writes the resulting string via SAF.
 *
 * Document shape:
 * ```
 * {
 *   "app": "rockedit",
 *   "version": 1,
 *   "created": 1730000000000,
 *   "entries": [
 *     {"key": "theme", "type": "string",  "value": "dark"},
 *     {"key": "line_numbers", "type": "boolean", "value": true},
 *     {"key": "recent_files", "type": "json", "value": "[...]"}
 *   ]
 * }
 * ```
 */
class BackupRestore(private val kv: KeyValueStore) {

    /** One backed-up key with its storage type. */
    data class BackupKey(val key: String, val type: EntryType) {
        enum class EntryType { STRING, BOOLEAN, JSON }
    }

    data class RestoreResult(val applied: Int, val skipped: Int)

    fun export(keys: List<BackupKey>, timestamp: Long = System.currentTimeMillis()): String {
        val entries = JSONArray()
        for (bk in keys) {
            if (!kv.contains(bk.key)) continue
            val value: Any = when (bk.type) {
                BackupKey.EntryType.BOOLEAN -> kv.getBoolean(bk.key, false)
                else -> kv.getString(bk.key, "") ?: continue
            }
            entries.put(
                JSONObject()
                    .put(F_KEY, bk.key)
                    .put(F_TYPE, bk.type.name.lowercase())
                    .put(F_VALUE, value)
            )
        }
        return JSONObject()
            .put(F_APP, "rockedit")
            .put(F_VERSION, VERSION)
            .put(F_CREATED, timestamp)
            .put(F_ENTRIES, entries)
            .toString()
    }

    /**
     * Restores entries from [json]. Keys not in [allowedKeys] are skipped
     * (safety against importing garbage); malformed values count as skipped.
     */
    fun restore(json: String, allowedKeys: Set<String>): RestoreResult {
        var applied = 0
        var skipped = 0
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return RestoreResult(0, 0)
        }
        val entries = obj.optJSONArray(F_ENTRIES) ?: return RestoreResult(0, 0)
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i)
            if (entry == null) {
                skipped++
                continue
            }
            val key = entry.optString(F_KEY)
            val type = entry.optString(F_TYPE)
            if (key !in allowedKeys) {
                skipped++
                continue
            }
            when (type) {
                "boolean" -> {
                    val value = entry.optBoolean(F_VALUE)
                    kv.putBoolean(key, value)
                    applied++
                }
                "string", "json" -> {
                    val value = entry.optString(F_VALUE)
                    if (value.isEmpty() && entry.isNull(F_VALUE)) {
                        skipped++
                    } else {
                        kv.putString(key, value)
                        applied++
                    }
                }
                else -> skipped++
            }
        }
        return RestoreResult(applied, skipped)
    }

    companion object {
        const val VERSION = 1
        private const val F_APP = "app"
        private const val F_VERSION = "version"
        private const val F_CREATED = "created"
        private const val F_ENTRIES = "entries"
        private const val F_KEY = "key"
        private const val F_TYPE = "type"
        private const val F_VALUE = "value"

        /**
         * The full default backup plan: every settings key plus the payload
         * keys of the recents / sessions / bookmarks / open-tabs stores.
         */
        fun defaultKeys(): List<BackupKey> = buildList {
            for (key in listOf(
                SettingsRepository.KEY_LINE_NUMBERS,
                SettingsRepository.KEY_WORD_WRAP,
                SettingsRepository.KEY_FULL_SCREEN,
                SettingsRepository.KEY_SYNTAX_HIGHLIGHT,
                SettingsRepository.KEY_AUTO_SAVE,
                SettingsRepository.KEY_REMEMBER_TABS,
                SettingsRepository.KEY_SORT_FOLDERS_FIRST,
                SettingsRepository.KEY_SHOW_HIDDEN_FILES
            )) {
                add(BackupKey(key, BackupKey.EntryType.BOOLEAN))
            }
            for (key in listOf(
                SettingsRepository.KEY_THEME,
                SettingsRepository.KEY_LINE_BREAK,
                SettingsRepository.KEY_FONT_SIZE,
                SettingsRepository.KEY_LAST_FOLDER_URI
            )) {
                add(BackupKey(key, BackupKey.EntryType.STRING))
            }
            add(BackupKey(RecentFilesStore.KEY, BackupKey.EntryType.JSON))
            add(BackupKey(SessionStore.KEY, BackupKey.EntryType.JSON))
            add(BackupKey(BookmarkStore.KEY, BackupKey.EntryType.JSON))
            add(BackupKey(TabPersistence.KEY, BackupKey.EntryType.JSON))
        }
    }
}
