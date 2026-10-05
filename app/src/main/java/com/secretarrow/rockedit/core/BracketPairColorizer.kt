package com.secretarrow.rockedit.core

/**
 * v0.15.0 bracket pair colorization (backlog item 4, second half).
 *
 * Assigns a 1-based nesting depth to every bracket character `( ) [ ] { }`
 * found **outside** string literals, char literals and comments, so the UI
 * can colorize matching pairs. Read-only and purely visual: it never mutates
 * the document and never throws — on any internal problem it returns what it
 * has computed so far (fail-safe, because it runs on every re-highlight).
 *
 * Assumptions (documented per the defensive-programming template):
 *  - Angle brackets `< >` are intentionally NOT colored: in most languages
 *    they are ambiguous (comparison vs generics vs HTML tags).
 *  - A string literal that is not terminated on its line is treated as
 *    closed at the newline, so one stray quote cannot poison the rest of a
 *    document (common for plain-text/notes files).
 *  - All four languages families sharing the C quote/comment conventions are
 *    covered by one scanner; YAML/Python comments starting with `#` do not
 *    affect brackets and are simply treated as code — brackets inside them
 *    still get colors, which is harmless visually.
 */
object BracketPairColorizer {
    /** One bracket character occurrence at [offset] with 1-based [depth]. */
    data class ColoredBracket(
        val offset: Int,
        val depth: Int,
    )

    /**
     * Scans [text] and returns bracket positions with depths, in document
     * order. Guards: empty input -> empty list; oversized input (>
     * [maxChars]) -> empty list (the highlighter has the same cap); any
     * unexpected exception -> the partial result collected so far.
     */
    fun colorize(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<ColoredBracket> {
        if (text.isEmpty()) return emptyList()
        if (text.length > maxChars) return emptyList()
        if (maxChars <= 0) return emptyList()
        val out = ArrayList<ColoredBracket>()
        var depth = 0
        try {
            var state = State.CODE
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                when (state) {
                    State.CODE ->
                        when (c) {
                            '"' -> state = State.STRING
                            '\'' -> state = State.CHAR
                            '/' -> {
                                if (i + 1 < n && text[i + 1] == '/') {
                                    state = State.LINE_COMMENT
                                    i += 2
                                    continue
                                }
                                if (i + 1 < n && text[i + 1] == '*') {
                                    state = State.BLOCK_COMMENT
                                    i += 2
                                    continue
                                }
                            }
                            '(', '[', '{' -> {
                                depth++
                                out.add(ColoredBracket(i, depth))
                            }
                            ')', ']', '}' -> {
                                // Unbalanced closers clamp to depth 1 so they
                                // still get a visible color and never make
                                // depth negative for the rest of the document.
                                val shown = if (depth > 0) depth else 1
                                out.add(ColoredBracket(i, shown))
                                if (depth > 0) depth--
                            }
                        }
                    State.STRING ->
                        when {
                            c == '\\' -> i++
                            c == '"' -> state = State.CODE
                            c == '\n' -> state = State.CODE
                        }
                    State.CHAR ->
                        when {
                            c == '\\' -> i++
                            c == '\'' -> state = State.CODE
                            c == '\n' -> state = State.CODE
                        }
                    State.LINE_COMMENT -> if (c == '\n') state = State.CODE
                    State.BLOCK_COMMENT ->
                        if (c == '*' && i + 1 < n && text[i + 1] == '/') {
                            state = State.CODE
                            i++
                        }
                }
                i++
            }
        } catch (_: Exception) {
            // Fail-safe: a colorizer must never break editing. Return the
            // brackets computed before the unexpected state.
            return out
        }
        return out
    }

    /**
     * Maps a 1-based [depth] onto a palette index (0-based, cycling). Invalid
     * inputs are clamped: depth < 1 -> first color; paletteSize < 1 -> the
     * default palette size. There is no branch that can throw here.
     */
    fun colorIndexFor(
        depth: Int,
        paletteSize: Int = DEFAULT_PALETTE,
    ): Int {
        val safeDepth = if (depth < 1) 1 else depth
        val safePalette = if (paletteSize < 1) DEFAULT_PALETTE else paletteSize
        return (safeDepth - 1) % safePalette
    }

    private enum class State { CODE, STRING, CHAR, LINE_COMMENT, BLOCK_COMMENT }

    const val DEFAULT_MAX_CHARS = 150_000
    const val DEFAULT_PALETTE = 4
}
