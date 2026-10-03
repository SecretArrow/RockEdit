package com.secretarrow.rockedit.core

/**
 * Application settings, backed by [KeyValueStore] for testability.
 * Values are stored as plain strings so the Settings screen can map
 * list preferences directly onto them.
 */
class SettingsRepository(private val kv: KeyValueStore) {

    // ---- Theme ----------------------------------------------------------
    var theme: String
        get() = kv.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = kv.putString(KEY_THEME, value)

    /** Applies the stored theme to AppCompat. Safe to call from any Activity. */
    fun applyTheme() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK, THEME_BLACK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** True when the pure-black AMOLED overlay should be applied to activities. */
    fun isBlackTheme(): Boolean = theme == THEME_BLACK

    // ---- Editor defaults --------------------------------------------------
    var lineNumbers: Boolean
        get() = kv.getBoolean(KEY_LINE_NUMBERS, true)
        set(value) = kv.putBoolean(KEY_LINE_NUMBERS, value)

    /** Hides the system bars to maximize editing space (roadmap: full screen). */
    var fullScreen: Boolean
        get() = kv.getBoolean(KEY_FULL_SCREEN, false)
        set(value) = kv.putBoolean(KEY_FULL_SCREEN, value)

    var wordWrap: Boolean
        get() = kv.getBoolean(KEY_WORD_WRAP, false)
        set(value) = kv.putBoolean(KEY_WORD_WRAP, value)

    /** One of [LINE_BREAK_AUTO], [LINE_BREAK_LF], [LINE_BREAK_CRLF]. */
    var lineBreakDefault: String
        get() = kv.getString(KEY_LINE_BREAK, LINE_BREAK_AUTO) ?: LINE_BREAK_AUTO
        set(value) = kv.putString(KEY_LINE_BREAK, value)

    /** Resolves the setting into a concrete [LineBreak]. */
    fun resolveLineBreak(): LineBreak = when (lineBreakDefault) {
        LINE_BREAK_LF -> LineBreak.LF
        LINE_BREAK_CRLF -> LineBreak.CRLF
        else -> LineBreak.LF // placeholder: activity decides "auto" from file content
    }

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_LINE_NUMBERS = "line_numbers"
        const val KEY_WORD_WRAP = "word_wrap"
        const val KEY_LINE_BREAK = "line_break"
        const val KEY_FULL_SCREEN = "full_screen"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_BLACK = "black"

        const val LINE_BREAK_AUTO = "auto"
        const val LINE_BREAK_LF = "lf"
        const val LINE_BREAK_CRLF = "crlf"
    }
}
