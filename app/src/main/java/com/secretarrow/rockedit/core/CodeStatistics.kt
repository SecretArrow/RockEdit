package com.secretarrow.rockedit.core

/**
 * v0.16.0 extended code statistics (backlog: "statistik kode"). Pure Kotlin,
 * string-aware and comment-aware; extends the basic [TextStats] numbers with
 * code/blank/comment classification, indentation profile, line-ending mix,
 * trailing-whitespace and TODO/FIXME counting. Fully unit-testable on the JVM.
 *
 * Defensive-programming case table (scenario -> handling -> test):
 *  - empty text -> all-zero stats, lineCount 0 (matches [TextStats.lineCount])
 *  - whitespace-only text -> 1 blank line
 *  - mixed CRLF/LF/CR -> endings counted per unit, crlf never double-counted
 *  - comment starter inside a string literal -> not a comment (string-aware scan)
 *  - comment starter inside a triple-quoted string (Python) -> not a comment
 *  - unclosed single/double quote -> string state is confined to its own line
 *    (deliberate improvement over "rest of file is string": a stray apostrophe
 *    in prose can no longer disable comment detection for the whole document;
 *    triple-quoted strings DO span lines because their syntax is multi-line)
 *  - unclosed block comment -> rest of document is comment content (fail-safe)
 *  - block comment opener/closer chars -> counted as comment characters, so a
 *    one-line C block comment (opener, content, closer) classifies as
 *    comment-only
 *  - TODO tokens -> counted only inside comment regions, uppercase, on word
 *    boundaries (TODOS does not count, TODO: does; strings/code never count)
 *  - line ending semantics -> [CodeStats.lineCount] equals
 *    [TextStats.lineCount] for every input (asserted by tests)
 *  - indentation -> only non-blank lines count; first-char tab => tab indented,
 *    leading spaces => space indented; common width picked among
 *    {1, 2, 3, 4, 6, 8}, ties resolved to the smaller width, none -> -1
 *  - trailing whitespace -> non-blank line whose last char is space/tab
 *  - .m extension -> MATLAB profile (conflict with Objective-C documented;
 *    MATLAB chosen because Obj-C files usually also carry .h/.m pairs in
 *    C-family projects and MATLAB users rely on the dedicated profile)
 *  - unknown/null file name -> [CommentProfiles.default]
 *
 * Assumptions (business decisions):
 *  - strings and triple strings are code content, never comments
 *  - blank lines inside block comments classify as blank (not comment-only)
 *  - word count delegates to [TextStats.wordCount] so both dialogs agree
 *  - no input size cap: the editor already caps buffers at 1M characters and
 *    the scan is a single O(n) pass
 */
object CodeStatistics {

    /** Leading-space widths considered when picking the common indent width. */
    private val COMMON_WIDTHS = intArrayOf(1, 2, 3, 4, 6, 8)

    /** Analyzes [text] with the comment profile of [profile]. Never throws. */
    fun analyze(
        text: String,
        profile: CommentProfile = CommentProfiles.default(),
    ): CodeStats {
        val endings = countLineEndings(text)
        val lines = splitLogicalLines(text)
        if (lines.isEmpty()) {
            return CodeStats(
                charCount = text.length,
                wordCount = TextStats.wordCount(text),
                lineCount = 0,
                blankLines = 0,
                commentOnlyLines = 0,
                codeLines = 0,
                longestLineLength = 0,
                averageLineLength = 0.0,
                lineEndings = endings,
                indentation = IndentationProfile(0, 0, -1, false),
                trailingWhitespaceLines = 0,
                todos = TodoBreakdown(0, 0, 0, 0),
            )
        }
        val lineComments =
            profile.lineComments
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.length }
        val blockComments =
            profile.blockComments
                .filter { (open, close) -> open.isNotEmpty() && close.isNotEmpty() }
                .sortedByDescending { (open, _) -> open.length }
        val triples =
            profile.tripleStrings
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.length }

        var blankLines = 0
        var commentOnlyLines = 0
        var longest = 0
        var totalLength = 0L
        var trailingWhitespaceLines = 0
        var tabIndented = 0
        var spaceIndented = 0
        val spaceWidthCounts = HashMap<Int, Int>()
        val commentText = StringBuilder()

        // Scanner state that persists across lines. Quotes reset per line
        // (single-line strings only); block comments and triple strings span.
        var inBlockCloser: String? = null
        var inTriple: String? = null
        var inQuote: Char? = null

        for (line in lines) {
            val n = line.length
            var i = 0
            var commentNonWs = 0
            while (i < n) {
                val closer = inBlockCloser
                if (closer != null) {
                    val end = line.indexOf(closer, i)
                    if (end < 0) {
                        commentNonWs += countNonWhitespace(line, i, n)
                        commentText.append(line, i, n).append('\n')
                        i = n
                    } else {
                        val stop = end + closer.length
                        commentNonWs += countNonWhitespace(line, i, stop)
                        commentText.append(line, i, stop).append('\n')
                        i = stop
                        inBlockCloser = null
                    }
                    continue
                }
                val triple = inTriple
                if (triple != null) {
                    val end = line.indexOf(triple, i)
                    if (end < 0) {
                        i = n
                    } else {
                        i = end + triple.length
                        inTriple = null
                    }
                    continue
                }
                val quote = inQuote
                if (quote != null) {
                    val c = line[i]
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    i++
                    if (c == quote) inQuote = null
                    continue
                }
                // Free state: block openers first (Lua `--[[` starts with `--`),
                // then line comments, then triple strings (`"""` starts with `"`),
                // then quotes, then plain characters.
                var matched = false
                for ((open, close) in blockComments) {
                    if (line.regionMatches(i, open, 0, open.length)) {
                        commentNonWs += open.length
                        inBlockCloser = close
                        i += open.length
                        matched = true
                        break
                    }
                }
                if (matched) continue
                for (starter in lineComments) {
                    if (line.regionMatches(i, starter, 0, starter.length)) {
                        commentNonWs += countNonWhitespace(line, i, n)
                        commentText.append(line, i, n).append('\n')
                        i = n
                        matched = true
                        break
                    }
                }
                if (matched) continue
                for (delim in triples) {
                    if (line.regionMatches(i, delim, 0, delim.length)) {
                        inTriple = delim
                        i += delim.length
                        matched = true
                        break
                    }
                }
                if (matched) continue
                val c = line[i]
                if (c == '"' || c == '\'') {
                    inQuote = c
                }
                i++
            }
            // End of line: an unclosed quote is confined to this line.
            inQuote = null
            val totalNonWs = countNonWhitespace(line, 0, n)
            totalLength += n
            if (n > longest) longest = n
            when {
                totalNonWs == 0 -> blankLines++
                commentNonWs >= totalNonWs -> commentOnlyLines++
                else -> {
                    if (line[0] == '\t') {
                        tabIndented++
                    } else if (line[0] == ' ') {
                        spaceIndented++
                        val width = leadingSpaceWidth(line)
                        spaceWidthCounts[width] = (spaceWidthCounts[width] ?: 0) + 1
                    }
                    val last = line[n - 1]
                    if (last == ' ' || last == '\t') trailingWhitespaceLines++
                }
            }
        }

        val lineCount = lines.size
        val commentOnly = commentOnlyLines
        val codeLines = (lineCount - blankLines - commentOnly).coerceAtLeast(0)
        val todos =
            TodoBreakdown(
                todo = countWordToken(commentText, "TODO"),
                fixme = countWordToken(commentText, "FIXME"),
                hack = countWordToken(commentText, "HACK"),
                xxx = countWordToken(commentText, "XXX"),
            )
        val indentation =
            IndentationProfile(
                tabIndentedLines = tabIndented,
                spaceIndentedLines = spaceIndented,
                commonSpaceWidth = commonSpaceWidth(spaceWidthCounts),
                mixed = tabIndented > 0 && spaceIndented > 0,
            )
        return CodeStats(
            charCount = text.length,
            wordCount = TextStats.wordCount(text),
            lineCount = lineCount,
            blankLines = blankLines,
            commentOnlyLines = commentOnly,
            codeLines = codeLines,
            longestLineLength = longest,
            averageLineLength = totalLength / lineCount.toDouble(),
            lineEndings = endings,
            indentation = indentation,
            trailingWhitespaceLines = trailingWhitespaceLines,
            todos = todos,
        )
    }

    /** Counts crlf/lf/cr terminators on the raw text; crlf is one unit. */
    private fun countLineEndings(text: String): LineEndings {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\r' -> {
                    if (i + 1 < n && text[i + 1] == '\n') {
                        crlf++
                        i++
                    } else {
                        cr++
                    }
                }
                '\n' -> lf++
            }
            i++
        }
        return LineEndings(crlf, lf, cr)
    }

    /**
     * Splits into logical lines with EXACTLY [TextStats.lineCount] semantics:
     * empty text -> no lines; a trailing terminator does not add a final
     * empty line ("a\n" -> 1 line, "a\n\n" -> 2 lines).
     */
    private fun splitLogicalLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\r') {
                if (i + 1 < n && text[i + 1] == '\n') i++
                lines.add(current.toString())
                current.setLength(0)
            } else if (c == '\n') {
                lines.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        if (text.endsWith("\n") || text.endsWith("\r")) {
            // The final add would duplicate the trailing empty logical line.
            if (current.isEmpty()) return lines
        }
        lines.add(current.toString())
        return lines
    }

    private fun countNonWhitespace(
        line: String,
        from: Int,
        to: Int,
    ): Int {
        var count = 0
        for (j in from until to) {
            if (!Character.isWhitespace(line[j])) count++
        }
        return count
    }

    private fun leadingSpaceWidth(line: String): Int {
        var width = 0
        while (width < line.length && line[width] == ' ') width++
        return width
    }

    private fun commonSpaceWidth(counts: Map<Int, Int>): Int {
        var bestWidth = -1
        var bestCount = 0
        for (width in COMMON_WIDTHS) {
            val count = counts[width] ?: 0
            if (count > bestCount) {
                bestCount = count
                bestWidth = width
            }
        }
        return bestWidth
    }

    /**
     * Counts uppercase [token] occurrences on word boundaries (letters,
     * digits and underscore count as word characters). Overlapping matches
     * are impossible by construction (token advances past a match).
     */
    private fun countWordToken(
        text: CharSequence,
        token: String,
    ): Int {
        var count = 0
        var i = 0
        val n = text.length
        while (i <= n - token.length) {
            if (text.regionMatches(i, token, 0, token.length)) {
                val before = if (i == 0) ' ' else text[i - 1]
                val afterIndex = i + token.length
                val after = if (afterIndex == n) ' ' else text[afterIndex]
                if (!isWordChar(before) && !isWordChar(after)) {
                    count++
                    i = afterIndex
                    continue
                }
            }
            i++
        }
        return count
    }

    private fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_'
}

/** Comment syntax profile for one language family. */
data class CommentProfile(
    val lineComments: List<String>,
    val blockComments: List<Pair<String, String>>,
    val tripleStrings: List<String> = emptyList(),
)

object CommentProfiles {
    private val C_LIKE = CommentProfile(listOf("//"), listOf("/*" to "*/"))
    private val HASH = CommentProfile(listOf("#"), emptyList())
    private val PYTHON = CommentProfile(listOf("#"), emptyList(), listOf("\"\"\"", "'''"))
    private val LUA = CommentProfile(listOf("--"), listOf("--[[" to "]]"))
    private val HTML = CommentProfile(emptyList(), listOf("<!--" to "-->"))
    private val SQL = CommentProfile(listOf("--"), listOf("/*" to "*/"))
    private val HASKELL = CommentProfile(listOf("--"), listOf("{-" to "-}"))
    private val MATLAB = CommentProfile(listOf("%"), emptyList())
    private val INI = CommentProfile(listOf(";", "#"), emptyList())
    private val LATEX = CommentProfile(listOf("%"), emptyList())
    private val CSS = CommentProfile(emptyList(), listOf("/*" to "*/"))

    /** C-family profile used when nothing better matches. */
    fun default(): CommentProfile = C_LIKE

    /**
     * Picks a profile by file name extension; null/blank/no-extension/
     * unknown extensions fall back to [default]. Decision: `.m` maps to
     * MATLAB (see class KDoc) and `.css` has block comments only.
     */
    fun forFileName(name: String?): CommentProfile {
        if (name.isNullOrBlank()) return default()
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return default()
        return when (name.substring(dot + 1).lowercase()) {
            "py", "pyi", "pyw" -> PYTHON
            "sh", "bash", "zsh", "rb", "ruby", "yml", "yaml", "toml", "r", "mk", "nix" -> HASH
            "lua" -> LUA
            "html", "htm", "xml", "svg", "vue", "xhtml" -> HTML
            "sql" -> SQL
            "hs", "lhs" -> HASKELL
            "m" -> MATLAB
            "ini", "properties", "cfg", "conf" -> INI
            "tex", "latex" -> LATEX
            "css", "scss", "less" -> CSS
            else -> default()
        }
    }
}

data class LineEndings(
    val crlf: Int,
    val lf: Int,
    val cr: Int,
)

data class IndentationProfile(
    val tabIndentedLines: Int,
    val spaceIndentedLines: Int,
    val commonSpaceWidth: Int,
    val mixed: Boolean,
)

data class TodoBreakdown(
    val todo: Int,
    val fixme: Int,
    val hack: Int,
    val xxx: Int,
) {
    fun total(): Int = todo + fixme + hack + xxx
}

data class CodeStats(
    val charCount: Int,
    val wordCount: Int,
    val lineCount: Int,
    val blankLines: Int,
    val commentOnlyLines: Int,
    val codeLines: Int,
    val longestLineLength: Int,
    val averageLineLength: Double,
    val lineEndings: LineEndings,
    val indentation: IndentationProfile,
    val trailingWhitespaceLines: Int,
    val todos: TodoBreakdown,
)
