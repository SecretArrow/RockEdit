package com.secretarrow.rockedit.core

import java.util.regex.PatternSyntaxException

/**
 * Minimal .editorconfig reader (v0.11.0) feeding Format-on-save and the
 * formatter defaults. Supported subset:
 * - `root = true|false` header (parsed, not used for lookup walk: see below)
 * - sections `[name]`, `[*]`, `*.ext`, single `?` wildcard
 * - keys: indent_style, indent_size, tab_width, end_of_line,
 *   trim_trailing_whitespace, insert_final_newline, max_line_length
 *
 * Defensive contract:
 * - Empty/missing/blank file maps to an empty [EditorConfig] — never an error.
 * - Malformed lines (no '=', unclosed brackets) are counted in
 *   [EditorConfig.malformedLines] and skipped; parsing always completes.
 * - Unknown keys and out-of-range values are ignored key-by-key (fail-safe);
 *   one bad value never poisons the whole file.
 * - Parsing never throws: any unexpected condition yields an empty config.
 *
 * Documented assumptions:
 * - Later sections override earlier ones for the same key (last-wins),
 *   matching the common reading of the editorconfig spec for our subset.
 * - Glob support is intentionally minimal (exact name, *, ?, *.ext via
 *   wildcard translation); brace expansion and ** are out of scope v1.
 * - Lookup walks only the .editorconfig next to the file (single level):
 *   reliable parent discovery across arbitrary SAF providers is not
 *   guaranteed, so higher-level configs are out of scope v1.
 */
object EditorConfigParser {
    enum class IndentStyle { SPACE, TAB }

    data class Section(
        val pattern: String,
        val indentStyle: IndentStyle? = null,
        val indentSize: Int? = null,
        val tabWidth: Int? = null,
        val endOfLine: LineBreak? = null,
        val trimTrailing: Boolean? = null,
        val insertFinalNewline: Boolean? = null,
    )

    data class EditorConfig(
        val isRoot: Boolean = false,
        val sections: List<Section> = emptyList(),
        val malformedLines: Int = 0,
    ) {
        fun isEmpty(): Boolean = sections.isEmpty()
    }

    /** Result of matching a file name against a config. */
    data class Resolved(
        val indentStyle: IndentStyle? = null,
        val indentSize: Int? = null,
        val endOfLine: LineBreak? = null,
        val trimTrailing: Boolean? = null,
        val insertFinalNewline: Boolean? = null,
    )

    // ------------------------------------------------------------- parse

    fun parse(content: String): EditorConfig {
        if (content.isBlank()) return EditorConfig()
        return try {
            parseInner(content)
        } catch (e: OutOfMemoryError) {
            EditorConfig()
        } catch (e: Exception) {
            EditorConfig() // fail-safe: a broken config must never crash the editor
        }
    }

    private fun parseInner(content: String): EditorConfig {
        val text = content.removePrefix("\uFEFF")
        val lines = text.split(Regex("\r\n|\n|\r"))
        var isRoot = false
        var malformed = 0
        val rawSections = ArrayList<Pair<String, MutableMap<String, String>>>()
        var current: MutableMap<String, String>? = null

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
                line.startsWith("[") -> {
                    if (!line.endsWith("]")) {
                        malformed++
                        current = null
                    } else {
                        val name = line.substring(1, line.length - 1).trim()
                        if (name.isEmpty()) {
                            malformed++
                            current = null
                        } else {
                            val map = HashMap<String, String>()
                            rawSections.add(name to map)
                            current = map
                        }
                    }
                }
                else -> {
                    val eq = line.indexOf('=')
                    if (eq <= 0) {
                        if (line.isNotEmpty()) malformed++
                        continue
                    }
                    val key = line.substring(0, eq).trim().lowercase()
                    val value = stripQuotes(line.substring(eq + 1).trim())
                    if (key.isEmpty()) {
                        malformed++
                        continue
                    }
                    if (current == null) {
                        // Header property before any section: only "root".
                        if (key == "root") {
                            isRoot = value.equals("true", ignoreCase = true)
                        } else {
                            malformed++
                        }
                        continue
                    }
                    current[key] = value
                }
            }
        }

        val sections = ArrayList<Section>(rawSections.size)
        for ((pattern, map) in rawSections) {
            sections.add(buildSection(pattern, map))
        }
        return EditorConfig(isRoot, sections, malformed)
    }

    private fun stripQuotes(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }

    private fun buildSection(
        pattern: String,
        map: Map<String, String>,
    ): Section =
        Section(
            pattern = pattern,
            indentStyle =
                when (map["indent_style"]?.lowercase()) {
                    "space" -> IndentStyle.SPACE
                    "tab" -> IndentStyle.TAB
                    else -> null // missing or invalid: key ignored
                },
            indentSize = positiveInt(map["indent_size"], max = 8),
            tabWidth = positiveInt(map["tab_width"], max = 16),
            endOfLine =
                when (map["end_of_line"]?.lowercase()) {
                    "lf" -> LineBreak.LF
                    "crlf" -> LineBreak.CRLF
                    "cr" -> LineBreak.CR
                    else -> null
                },
            trimTrailing = boolean(map["trim_trailing_whitespace"]),
            insertFinalNewline = boolean(map["insert_final_newline"]),
        )

    private fun positiveInt(
        raw: String?,
        max: Int,
    ): Int? {
        if (raw == null) return null
        val v = raw.toIntOrNull() ?: return null
        return if (v in 1..max) v else null
    }

    private fun boolean(raw: String?): Boolean? =
        when (raw?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    // ------------------------------------------------------------- resolve

    /** Merges every section matching [fileName], in file order (last wins). */
    fun resolve(
        config: EditorConfig,
        fileName: String,
    ): Resolved {
        var resolved = Resolved()
        for (section in config.sections) {
            if (!matches(section.pattern, fileName)) continue
            resolved =
                Resolved(
                    indentStyle = section.indentStyle ?: resolved.indentStyle,
                    indentSize = section.indentSize ?: resolved.indentSize,
                    endOfLine = section.endOfLine ?: resolved.endOfLine,
                    trimTrailing = section.trimTrailing ?: resolved.trimTrailing,
                    insertFinalNewline = section.insertFinalNewline ?: resolved.insertFinalNewline,
                )
        }
        return resolved
    }

    /** Maps a resolved config onto [base] options, keeping unspecified defaults. */
    fun toFormatOptions(
        resolved: Resolved,
        base: FormatOptions,
    ): FormatOptions {
        // Per spec: indent_size = "tab" falls back to tab_width; our parser
        // rejects the literal "tab" value, so tab_width alone drives nothing
        // beyond clamping — documented subset behavior.
        val indentSize =
            (resolved.indentSize ?: base.indentSize)
                .coerceIn(FormatOptions.MIN_INDENT_SIZE, FormatOptions.MAX_INDENT_SIZE)
        val indentStyle =
            when (resolved.indentStyle) {
                // Fully qualified: this object declares its own IndentStyle enum
                // (SPACE/TAB); the formatter's enum (SPACES/TABS) is the target.
                IndentStyle.TAB -> com.secretarrow.rockedit.core.IndentStyle.TABS
                IndentStyle.SPACE -> com.secretarrow.rockedit.core.IndentStyle.SPACES
                null -> base.indentStyle
            }
        return base.copy(
            indentStyle = indentStyle,
            indentSize = indentSize,
            lineBreak = resolved.endOfLine ?: base.lineBreak,
            trimTrailingWhitespace = resolved.trimTrailing ?: base.trimTrailingWhitespace,
            insertFinalNewline = resolved.insertFinalNewline ?: base.insertFinalNewline,
        )
    }

    // ------------------------------------------------------------- globs

    /** Exact name, `*` (any run) and `?` (single char), case-insensitive. */
    internal fun matches(
        pattern: String,
        fileName: String,
    ): Boolean {
        if (pattern == "*" || pattern.isEmpty()) return true
        val regex = buildGlobRegex(pattern) ?: return false
        return regex.matcher(fileName).matches()
    }

    private fun buildGlobRegex(pattern: String): java.util.regex.Pattern? {
        val sb = StringBuilder(pattern.length * 2 + 2)
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                in regexSpecials -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return try {
            java.util.regex.Pattern
                .compile(sb.toString(), java.util.regex.Pattern.CASE_INSENSITIVE)
        } catch (e: PatternSyntaxException) {
            null // invalid pattern matches nothing (fail-safe)
        }
    }

    private val regexSpecials = "()[]{}.^$+\\|".toSet()
}
