package com.secretarrow.rockedit.core

/**
 * Character-level line scanner shared by the structural formatters
 * (brace / indent / lisp families).
 *
 * Responsibilities and guarantees:
 * - carries string, multiline-string and block-comment state ACROSS lines,
 * - reports the net delta and the minimum prefix balance of the configured
 *   bracket characters (this drives dedent for lines that open with `}`/`)`),
 * - reports the last code character and whether the line contains code
 *   (drives trailing-colon detection for Python-like languages),
 * - flags unterminated single-line strings instead of silently eating the
 *   rest of the file,
 * - never throws: all failures are reported as flags for the caller to
 *   translate into [FormatResult.Failure] with a line number.
 *
 * NOT thread safe by design: formatters create one scanner per run and
 * formatters themselves are stateless.
 *
 * Known, documented limitation: regular expression literals that contain
 * quotes (e.g. `/it's/` in JavaScript) are indistinguishable from strings
 * without a full parser; such inputs fail with an unterminated-string
 * error (strict mode) or format best-effort (lenient mode).
 */
internal class LineScanner(
    private val lineComments: List<String>,
    private val blockComments: List<Pair<String, String>>,
    private val stringDelims: List<Char>,
    private val multilineDelims: List<Pair<String, String>>,
    private val openChars: List<Char>,
    private val closeChars: List<Char>
) {

    /** Closer token of the block comment we are currently inside, or null. */
    private var blockCommentCloser: String? = null

    /** Closer token of the multiline string we are currently inside, or null. */
    private var multilineCloser: String? = null

    /** Outcome of scanning one physical line. */
    class LineScan(
        /** opens minus closes counted outside strings/comments. */
        val netDelta: Int,
        /**
         * Number of closing brackets at the very start of the line (before
         * the first other significant character). Drives the one-level-per-
         * closer dedent for lines like `}` , `});` , `)))`.
         */
        val leadingDedent: Int,
        /** last character of the code portion (before any trailing comment). */
        val lastCodeChar: Char?,
        /** true when the line contains any code (code or string content). */
        val hasCode: Boolean,
        /** true when a single-line string was not closed before end of line. */
        val unterminatedString: Boolean,
        /** true when the line starts inside a carried block comment. */
        val startsInsideBlockComment: Boolean,
        /** true when the line starts inside a carried multiline string. */
        val startsInsideMultiline: Boolean
    )

    fun scanLine(line: String): LineScan {
        var net = 0
        var leadingDedent = 0
        var seenSignificant = false
        var lastCode: Char? = null
        var hasCode = false
        var unterminated = false
        val startedInBlock = blockCommentCloser != null
        val startedInMultiline = multilineCloser != null

        var i = 0
        val n = line.length
        while (i < n) {
            // (1) inside a block comment carried from an earlier line
            val bc = blockCommentCloser
            if (bc != null) {
                val end = line.indexOf(bc, i)
                if (end < 0) {
                    i = n
                    break
                }
                i = end + bc.length
                blockCommentCloser = null
                continue
            }
            // (2) inside a multiline string carried from an earlier line
            val ml = multilineCloser
            if (ml != null) {
                val end = line.indexOf(ml, i)
                if (end < 0) {
                    i = n
                    break
                }
                i = end + ml.length
                multilineCloser = null
                seenSignificant = true
                continue
            }
            val c = line[i]
            // (3) line comment: the rest of the line is not code
            var consumedAsComment = false
            for (lc in lineComments) {
                if (line.startsWith(lc, i)) {
                    i = n
                    consumedAsComment = true
                    break
                }
            }
            if (consumedAsComment) break
            // (4) block comment opener
            var consumed = false
            for ((open, close) in blockComments) {
                if (line.startsWith(open, i)) {
                    val end = line.indexOf(close, i + open.length)
                    if (end < 0) {
                        blockCommentCloser = close
                        i = n
                    } else {
                        i = end + close.length
                    }
                    consumed = true
                    break
                }
            }
            if (consumed) continue
            // (5) multiline string opener (checked before single-char delims
            // so that `"""` wins over `"`; escapes do not apply here)
            var consumedMl = false
            for ((open, close) in multilineDelims) {
                if (line.startsWith(open, i)) {
                    val end = line.indexOf(close, i + open.length)
                    if (end < 0) {
                        multilineCloser = close
                        i = n
                    } else {
                        i = end + close.length
                    }
                    seenSignificant = true
                    consumedMl = true
                    break
                }
            }
            if (consumedMl) continue
            // (6) single-line string (with backslash escapes)
            if (stringDelims.contains(c)) {
                seenSignificant = true
                hasCode = true
                lastCode = c
                i++
                var closed = false
                while (i < n) {
                    val sc = line[i]
                    if (sc == '\\') {
                        i += 2
                        continue
                    }
                    if (sc == c) {
                        closed = true
                        i++
                        break
                    }
                    i++
                }
                if (!closed) unterminated = true
                continue
            }
            // (7) brackets and plain characters
            if (openChars.contains(c) || closeChars.contains(c)) {
                val isOpen = openChars.contains(c)
                if (isOpen) {
                    // An opener always stops the leading-closer run.
                    seenSignificant = true
                } else if (!seenSignificant) {
                    leadingDedent++
                }
                net += if (isOpen) 1 else -1
                lastCode = c
                hasCode = true
                i++
                continue
            }
            if (!c.isWhitespace()) {
                seenSignificant = true
                lastCode = c
                hasCode = true
            }
            i++
        }
        return LineScan(
            netDelta = net,
            leadingDedent = leadingDedent,
            lastCodeChar = lastCode,
            hasCode = hasCode,
            unterminatedString = unterminated,
            startsInsideBlockComment = startedInBlock,
            startsInsideMultiline = startedInMultiline
        )
    }
}
