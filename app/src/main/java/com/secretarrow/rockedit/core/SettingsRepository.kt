package com.secretarrow.rockedit.core

/**
 * Application settings, backed by [KeyValueStore] for testability.
 * Values are stored as plain strings so the Settings screen can map
 * list preferences directly onto them.
 */
class SettingsRepository(
    private val kv: KeyValueStore,
) {
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
            },
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

    /** Syntax highlighting on by default; per-file language comes from the file name. */
    var syntaxHighlight: Boolean
        get() = kv.getBoolean(KEY_SYNTAX_HIGHLIGHT, true)
        set(value) = kv.putBoolean(KEY_SYNTAX_HIGHLIGHT, value)

    /** Editor font size in sp; falls back to the default on malformed values. */
    var fontSizeSp: Int
        get() = kv.getString(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)?.toIntOrNull() ?: 14
        set(value) = kv.putString(KEY_FONT_SIZE, value.toString())

    /** Saves the open file automatically when the activity goes to background. */
    var autoSave: Boolean
        get() = kv.getBoolean(KEY_AUTO_SAVE, false)
        set(value) = kv.putBoolean(KEY_AUTO_SAVE, value)

    /** One of [LINE_BREAK_AUTO], [LINE_BREAK_LF], [LINE_BREAK_CRLF]. */
    var lineBreakDefault: String
        get() = kv.getString(KEY_LINE_BREAK, LINE_BREAK_AUTO) ?: LINE_BREAK_AUTO
        set(value) = kv.putString(KEY_LINE_BREAK, value)

    /** Resolves the setting into a concrete [LineBreak]. */
    fun resolveLineBreak(): LineBreak =
        when (lineBreakDefault) {
            LINE_BREAK_LF -> LineBreak.LF
            LINE_BREAK_CRLF -> LineBreak.CRLF
            else -> LineBreak.LF // placeholder: activity decides "auto" from file content
        }

    // ---- Files & tabs ------------------------------------------------------

    /** Restores the set of open tabs when the editor starts without a file. */
    var rememberTabs: Boolean
        get() = kv.getBoolean(KEY_REMEMBER_TABS, true)
        set(value) = kv.putBoolean(KEY_REMEMBER_TABS, value)

    /** Folder browser: lists folders before files. */
    var sortFoldersFirst: Boolean
        get() = kv.getBoolean(KEY_SORT_FOLDERS_FIRST, true)
        set(value) = kv.putBoolean(KEY_SORT_FOLDERS_FIRST, value)

    /** Folder browser: shows dot-files. */
    var showHiddenFiles: Boolean
        get() = kv.getBoolean(KEY_SHOW_HIDDEN_FILES, false)
        set(value) = kv.putBoolean(KEY_SHOW_HIDDEN_FILES, value)

    /** Last folder tree the user browsed (content:// tree URI string). */
    var lastFolderUri: String
        get() = kv.getString(KEY_LAST_FOLDER_URI, "").orEmpty()
        set(value) = kv.putString(KEY_LAST_FOLDER_URI, value)

    /** Opt-in consent for sending code to the Piston online runner. */
    var onlineExecution: Boolean
        get() = kv.getBoolean(KEY_ONLINE_EXECUTION, false)
        set(value) = kv.putBoolean(KEY_ONLINE_EXECUTION, value)

    /** v0.15.0: colorize matching bracket pairs by nesting depth. */
    var bracketPairColors: Boolean
        get() = kv.getBoolean(KEY_BRACKET_PAIR_COLORS, true)
        set(value) = kv.putBoolean(KEY_BRACKET_PAIR_COLORS, value)

    /** v0.11.0: run the Code Formatter automatically before every save. */
    var formatOnSave: Boolean
        get() = kv.getBoolean(KEY_FORMAT_ON_SAVE, false)
        set(value) = kv.putBoolean(KEY_FORMAT_ON_SAVE, value)

    // ---- Indentation & font family (v0.23.0) -------------------------------
    //
    // The stored strings map onto ListPreference entry values. Read paths are
    // defensive: a value written by a future/older build (or corrupted data)
    // must never crash or produce an out-of-range formatter request, so every
    // getter falls back to the shipped default documented in the KDoc.

    /**
     * Base indent style for the Code Formatter: one of [INDENT_SPACES] /
     * [INDENT_TABS]. Unknown stored values read back as [INDENT_SPACES] so a
     * malformed entry can never be mistaken for tabs. Per-file .editorconfig
     * still wins: the activity merges it on top of this base.
     */
    var indentStyle: String
        get() {
            val raw = kv.getString(KEY_INDENT_STYLE, INDENT_SPACES) ?: INDENT_SPACES
            return if (raw == INDENT_TABS) INDENT_TABS else INDENT_SPACES
        }
        set(value) = kv.putString(KEY_INDENT_STYLE, value)

    /**
     * Spaces per indent level for the Code Formatter, stored as a decimal
     * string. Reads clamp into the formatter's accepted range
     * (FormatOptions MIN/MAX) and fall back to [FormatOptions.DEFAULT_INDENT_SIZE]
     * when unparseable; writes are clamped so an out-of-range value can never
     * reach the store.
     */
    var indentSize: Int
        get() {
            val raw =
                kv.getString(KEY_INDENT_SIZE, DEFAULT_INDENT_SIZE_SETTING)
                    ?: DEFAULT_INDENT_SIZE_SETTING
            val parsed = raw.toIntOrNull() ?: return FormatOptions.DEFAULT_INDENT_SIZE
            return parsed.coerceIn(FormatOptions.MIN_INDENT_SIZE, FormatOptions.MAX_INDENT_SIZE)
        }
        set(value) {
            val safe = value.coerceIn(FormatOptions.MIN_INDENT_SIZE, FormatOptions.MAX_INDENT_SIZE)
            kv.putString(KEY_INDENT_SIZE, safe.toString())
        }

    /**
     * Editor text font family: one of [FONT_MONOSPACE] / [FONT_SANS] /
     * [FONT_SERIF]. Unknown stored values read back as [FONT_MONOSPACE]. The
     * gutter is always monospace regardless of this setting (alignment).
     */
    var fontFamily: String
        get() {
            val raw = kv.getString(KEY_FONT_FAMILY, FONT_MONOSPACE) ?: FONT_MONOSPACE
            return if (raw == FONT_SANS || raw == FONT_SERIF) raw else FONT_MONOSPACE
        }
        set(value) = kv.putString(KEY_FONT_FAMILY, value)

    /** Resolves the stored indent style into the formatter enum. */
    fun resolveIndentStyle(): IndentStyle = if (indentStyle == INDENT_TABS) IndentStyle.TABS else IndentStyle.SPACES

    /**
     * v0.24.0: gutter numbering mode — one of [NUMBERING_ABSOLUTE] /
     * [NUMBERING_RELATIVE] / [NUMBERING_HYBRID]. Unknown stored values read
     * back as absolute (the historical behavior), so a corrupted entry can
     * only ever fall back to what older builds rendered.
     */
    var lineNumbering: String
        get() = kv.getString(KEY_LINE_NUMBERING, NUMBERING_ABSOLUTE) ?: NUMBERING_ABSOLUTE
        set(value) = kv.putString(KEY_LINE_NUMBERING, value)

    /** Resolves the stored numbering preference into the core enum. */
    fun resolveLineNumbering(): LineNumbering.Mode = LineNumbering.modeFrom(lineNumbering)

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_LINE_NUMBERS = "line_numbers"
        const val KEY_WORD_WRAP = "word_wrap"
        const val KEY_LINE_BREAK = "line_break"
        const val KEY_FULL_SCREEN = "full_screen"
        const val KEY_SYNTAX_HIGHLIGHT = "syntax_highlight"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_AUTO_SAVE = "auto_save"
        const val KEY_REMEMBER_TABS = "remember_tabs"
        const val KEY_SORT_FOLDERS_FIRST = "sort_folders_first"
        const val KEY_SHOW_HIDDEN_FILES = "show_hidden_files"
        const val KEY_LAST_FOLDER_URI = "last_folder_uri"
        const val KEY_ONLINE_EXECUTION = "online_execution"
        const val KEY_FORMAT_ON_SAVE = "format_on_save"
        const val KEY_BRACKET_PAIR_COLORS = "bracket_pair_colors"
        const val KEY_INDENT_STYLE = "indent_style"
        const val KEY_INDENT_SIZE = "indent_size"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_LINE_NUMBERING = "line_numbering"

        const val DEFAULT_FONT_SIZE = "14"
        const val DEFAULT_INDENT_SIZE_SETTING = "4"

        const val INDENT_SPACES = "spaces"
        const val INDENT_TABS = "tabs"

        const val FONT_MONOSPACE = "monospace"
        const val FONT_SANS = "sans"
        const val FONT_SERIF = "serif"

        const val NUMBERING_ABSOLUTE = "absolute"
        const val NUMBERING_RELATIVE = "relative"
        const val NUMBERING_HYBRID = "hybrid"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_BLACK = "black"

        const val LINE_BREAK_AUTO = "auto"
        const val LINE_BREAK_LF = "lf"
        const val LINE_BREAK_CRLF = "crlf"
    }
}
