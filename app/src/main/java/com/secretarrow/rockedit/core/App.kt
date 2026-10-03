package com.secretarrow.rockedit.core

import android.content.Context

/**
 * Lightweight service locator. Rock Edit is intentionally DI-framework free
 * to keep the build fast and the code approachable.
 */
object App {

    private const val PREFS_NAME = "rockedit_prefs"

    fun keyValueStore(context: Context): KeyValueStore =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).toKeyValueStore()

    fun settings(context: Context): SettingsRepository =
        SettingsRepository(keyValueStore(context))

    fun recents(context: Context): RecentFilesStore =
        RecentFilesStore(keyValueStore(context))

    fun sessions(context: Context): SessionStore =
        SessionStore(keyValueStore(context))

    fun bookmarks(context: Context): BookmarkStore =
        BookmarkStore(keyValueStore(context))

    private fun android.content.SharedPreferences.toKeyValueStore(): KeyValueStore =
        object : KeyValueStore {
            override fun getString(key: String, defValue: String?): String? =
                this@toKeyValueStore.getString(key, defValue)

            override fun putString(key: String, value: String) {
                edit().putString(key, value).apply()
            }

            override fun getBoolean(key: String, defValue: Boolean): Boolean =
                this@toKeyValueStore.getBoolean(key, defValue)

            override fun putBoolean(key: String, value: Boolean) {
                edit().putBoolean(key, value).apply()
            }

            override fun remove(key: String) {
                edit().remove(key).apply()
            }

            override fun clear() {
                edit().clear().apply()
            }
        }
}
