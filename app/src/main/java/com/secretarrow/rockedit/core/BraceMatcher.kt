package com.secretarrow.rockedit.core

/**
 * Bracket matching engine (v0.13.0): given a cursor on a bracket, finds its
 * partner while correctly skipping string literals, char literals and
 * comments. Pure JVM (kotlin stdlib only), fully iterative (no recursion).
 *
 * Case map (defensive rule 1):
 * - Empty text -> [BraceMatcher.MatchResult.NoBracketAtCursor] (checked first:
 *   an empty text cannot contain a bracket, so a bounds error is meaningless
 *   even for an out-of-range index).
 * - Index outside 0..length-1 -> [BraceMatcher.MatchResult.Unmatched] with an
 *   "index out of bounds" reason. Out-of-range NEVER throws (documented
 *   decision; the editor calls this on every cursor move).
 * - Character at the index is not a configured bracket -> NoBracketAtCursor.
 * - Cursor on an opener -> forward scan: nested same-type openers are counted,
 *   other pair types are tracked on a stack so interleaving is respected
 *   ("{ [(] }": `(` does not match `]`). The partner is accepted only when the
 *   other-stack is empty; our own closer arriving at depth 0 with a non-empty
 *   other-stack means unbalanced source -> Unmatched("unbalanced ..."). A
 *   closer of ANOTHER type with an empty stack is a stray and is tolerated
 *   (ignored) so messy sources still match their obvious pairs. EOF ->
 *   Unmatched("no closing <char> found").
 * - Cursor on a closer -> backward scan, symmetric rules (other closers push,
 *   other openers pop). Backward simplifications, documented deliberately:
 *   - String/char literals are tracked with backslash-run escape counting.
 *   - Block comments toggle on `*/` (enter) and `/*` (exit) exactly.
 *   - Line comments are only approximated: on seeing `//` the scanner drops
 *     the rest of that line segment (resumes on the previous line). Characters
 *     to the RIGHT of the `//` were already visited and are not retroactively
 *     masked, so a backward match can report Unmatched (nested-comment
 *     brackets surface as "no opening ..." or "unbalanced ...") where the
 *     forward scan succeeds. The forward scan is authoritative.
 * - Both scans start in code state at the cursor; a string/comment that is
 *   already open AT the cursor cannot be detected (context before the cursor
 *   is unknown). Kotlin raw strings (`"""`) are NOT specially handled: each
 *   `"` toggles the string state. An unterminated string recovers at the next
 *   newline so one broken literal cannot swallow the rest of the file.
 * - Scan distance above [DEFAULT_MAX_SCAN] (or the explicit [matchAt] limit)
 *   -> Unmatched("scan limit reached ..."). The limit counts examined
 *   positions; characters skipped inside escapes/comment delimiters are free.
 * - [BraceMatcher.BracePairs] with no pairs, a duplicated bracket character or
 *   opener == closer throws IllegalArgumentException at construction (a
 *   programming error, not runtime input).
 */
object BraceMatcher {

    /** Default scan budget: 1 MiB of characters per match attempt. */
    const val DEFAULT_MAX_SCAN = 1_048_576

    private const val MIN_REASONABLE_PAIRS = 1

    /**
     * The bracket vocabulary for matching. Characters must be unique across
     * every opener and closer, and an opener may never equal its closer.
     */
    data class BracePairs(val pairs: List<Pair<Char, Char>>) {

        init {
            if (pairs.size < MIN_REASONABLE_PAIRS) {
                throw IllegalArgumentException(
                    "BracePairs needs at least one (opener, closer) pair, got ${pairs.size}"
                )
            }
            val seen = HashSet<Char>(pairs.size * 2)
            for ((open, close) in pairs) {
                if (open == close) {
                    throw IllegalArgumentException(
                        "invalid brace pair '$open$close': opener and closer must differ"
                    )
                }
                if (!seen.add(open)) {
                    throw IllegalArgumentException(
                        "duplicate bracket character '$open': each bracket may appear only once"
                    )
                }
                if (!seen.add(close)) {
                    throw IllegalArgumentException(
                        "duplicate bracket character '$close': each bracket may appear only once"
                    )
                }
            }
        }

        companion object {
            /** Classic programming brackets: ( ) [ ] { }. */
            val DEFAULT = BracePairs(listOf('(' to ')', '[' to ']', '{' to '}'))
        }
    }

    sealed interface MatchResult {
        /**
         * A partner was found: [partnerIndex] is its position, [isOpener] is
         * `true` when the cursor bracket was the opener, [bracket] is the
         * character at the cursor.
         */
        data class Matched(
            val partnerIndex: Int,
            val isOpener: Boolean,
            val bracket: Char
        ) : MatchResult

        /** The cursor is not on any configured bracket. */
        object NoBracketAtCursor : MatchResult

        /** No partner (or none reachable): [reason] says what failed and why. */
        data class Unmatched(val reason: String) : MatchResult
    }

    /**
     * Matches the bracket at [index]. Never throws for an out-of-range index
     * or a non-bracket character; only a negative [maxScan] is rejected with
     * IllegalArgumentException (a programming error).
     */
    fun matchAt(
        text: String,
        index: Int,
        pairs: BracePairs = BracePairs.DEFAULT,
        maxScan: Int = DEFAULT_MAX_SCAN
    ): MatchResult = matchAtWithLimit(text, index, pairs, maxScan)

    /** Test-visible worker behind [matchAt] so a tiny scan limit is unit-testable. */
    internal fun matchAtWithLimit(
        text: String,
        index: Int,
        pairs: BracePairs,
        maxScan: Int
    ): MatchResult {
        if (maxScan < 0) {
            throw IllegalArgumentException("maxScan is $maxScan, must be >= 0")
        }
        if (text.isEmpty()) {
            return MatchResult.NoBracketAtCursor
        }
        if (index < 0 || index >= text.length) {
            return MatchResult.Unmatched(
                "index out of bounds: index is $index, valid range is 0..${text.length - 1} " +
                    "for text of length ${text.length}"
            )
        }
        val openerToCloser = HashMap<Char, Char>(pairs.pairs.size * 2)
        val closerToOpener = HashMap<Char, Char>(pairs.pairs.size * 2)
        for ((open, close) in pairs.pairs) {
            openerToCloser[open] = close
            closerToOpener[close] = open
        }
        val cursor = text[index]
        val ourClose = openerToCloser[cursor]
        if (ourClose != null) {
            return scanForward(
                text, index, cursor, ourClose, openerToCloser, closerToOpener, maxScan
            )
        }
        val ourOpen = closerToOpener[cursor]
        if (ourOpen != null) {
            return scanBackward(
                text, index, ourOpen, cursor, openerToCloser, closerToOpener, maxScan
            )
        }
        return MatchResult.NoBracketAtCursor
    }

    // ------------------------------------------------------------ forward

    private fun scanForward(
        text: String,
        start: Int,
        ourOpen: Char,
        ourClose: Char,
        openerToCloser: Map<Char, Char>,
        closerToOpener: Map<Char, Char>,
        maxScan: Int
    ): MatchResult {
        var state = State.CODE
        var depth = 0
        val others = ArrayList<Char>()
        var i = start + 1
        var scanned = 0
        while (i < text.length) {
            if (scanned >= maxScan) {
                return MatchResult.Unmatched(
                    "scan limit reached: no closing '$ourClose' found within $maxScan " +
                        "characters of index $start"
                )
            }
            val c = text[i]
            scanned++
            when (state) {
                State.CODE -> when {
                    c == '"' && !isEscaped(text, i) -> state = State.STRING
                    c == '\'' && !isEscaped(text, i) -> state = State.CHAR
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                        state = State.LINE_COMMENT
                        i++
                    }
                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                        state = State.BLOCK_COMMENT
                        i++
                    }
                    c == ourOpen -> depth++
                    c == ourClose -> when {
                        depth > 0 -> depth--
                        others.isEmpty() -> return MatchResult.Matched(i, true, ourOpen)
                        else -> return MatchResult.Unmatched(
                            "unbalanced text: '$ourClose' at index $i would close " +
                                "'$ourOpen' at index $start, but ${others.size} inner " +
                                "bracket(s) opened after it are still open"
                        )
                    }
                    openerToCloser.containsKey(c) -> others.add(c)
                    closerToOpener.containsKey(c) -> {
                        val expectedOpen = closerToOpener.getValue(c)
                        if (others.isNotEmpty() && others.last() == expectedOpen) {
                            others.removeAt(others.lastIndex)
                        }
                        // else: stray closer of another type, tolerated and ignored.
                    }
                    else -> {
                        // Plain text character.
                    }
                }
                State.STRING -> when {
                    c == '\\' -> i++
                    c == '"' -> state = State.CODE
                    c == '\n' -> state = State.CODE
                    else -> {
                        // String content.
                    }
                }
                State.CHAR -> when {
                    c == '\\' -> i++
                    c == '\'' -> state = State.CODE
                    c == '\n' -> state = State.CODE
                    else -> {
                        // Char literal content.
                    }
                }
                State.LINE_COMMENT -> if (c == '\n') {
                    state = State.CODE
                } else {
                    // Comment content: quotes and brackets are literal here.
                }
                State.BLOCK_COMMENT -> if (c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                    state = State.CODE
                    i++
                } else {
                    // Comment content, spans newlines.
                }
            }
            i++
        }
        return MatchResult.Unmatched(
            "no closing '$ourClose' found for '$ourOpen' at index $start"
        )
    }

    // ------------------------------------------------------------ backward

    private fun scanBackward(
        text: String,
        start: Int,
        ourOpen: Char,
        ourClose: Char,
        openerToCloser: Map<Char, Char>,
        closerToOpener: Map<Char, Char>,
        maxScan: Int
    ): MatchResult {
        var state = State.CODE
        var depth = 0
        val others = ArrayList<Char>()
        var i = start - 1
        var scanned = 0
        while (i >= 0) {
            if (scanned >= maxScan) {
                return MatchResult.Unmatched(
                    "scan limit reached: no opening '$ourOpen' found within $maxScan " +
                        "characters of index $start"
                )
            }
            val c = text[i]
            scanned++
            when (state) {
                State.CODE -> when {
                    c == '"' && !isEscaped(text, i) -> state = State.STRING
                    c == '\'' && !isEscaped(text, i) -> state = State.CHAR
                    c == '/' && i > 0 && text[i - 1] == '*' -> {
                        state = State.BLOCK_COMMENT
                        i--
                    }
                    c == '/' && i > 0 && text[i - 1] == '/' -> {
                        // Documented simplification: `//` makes the whole line
                        // segment opaque; the scan resumes on the previous line.
                        i = text.lastIndexOf('\n', i) + 1
                    }
                    c == ourClose -> depth++
                    c == ourOpen -> when {
                        depth > 0 -> depth--
                        others.isEmpty() -> return MatchResult.Matched(i, false, ourClose)
                        else -> return MatchResult.Unmatched(
                            "unbalanced text: '$ourOpen' at index $i would match " +
                                "'$ourClose' at index $start, but ${others.size} inner " +
                                "bracket(s) between them remain unclosed"
                        )
                    }
                    closerToOpener.containsKey(c) -> others.add(c)
                    openerToCloser.containsKey(c) -> {
                        val expectedClose = openerToCloser.getValue(c)
                        if (others.isNotEmpty() && others.last() == expectedClose) {
                            others.removeAt(others.lastIndex)
                        }
                        // else: stray opener of another type, tolerated and ignored.
                    }
                    else -> {
                        // Plain text character.
                    }
                }
                State.STRING -> if (c == '"' && !isEscaped(text, i)) {
                    state = State.CODE
                } else {
                    // String content; escapes are resolved by [isEscaped] at quotes.
                }
                State.CHAR -> if (c == '\'' && !isEscaped(text, i)) {
                    state = State.CODE
                } else {
                    // Char literal content.
                }
                State.BLOCK_COMMENT -> if (c == '*' && i > 0 && text[i - 1] == '/') {
                    state = State.CODE
                    i--
                } else {
                    // Comment content.
                }
                State.LINE_COMMENT -> {
                    // Unreachable in the backward scan (line comments are
                    // skipped as whole segments); kept for exhaustiveness.
                }
            }
            i--
        }
        return MatchResult.Unmatched(
            "no opening '$ourOpen' found for '$ourClose' at index $start"
        )
    }

    /**
     * `true` when the character at [index] is preceded by an odd number of
     * backslashes (i.e. it is escaped by them).
     */
    private fun isEscaped(text: String, index: Int): Boolean {
        var run = 0
        var j = index - 1
        while (j >= 0 && text[j] == '\\') {
            run++
            j--
        }
        return run % 2 == 1
    }

    private enum class State { CODE, STRING, CHAR, LINE_COMMENT, BLOCK_COMMENT }
}
