package com.secretarrow.rockedit.core

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Pure regex testing engine backing the interactive Regex Tester dialog
 * (v0.11.0). Complements Find/Replace: the user can validate a pattern and
 * inspect matches + capture groups before touching the document.
 *
 * Defensive contract:
 * - Blank pattern maps to [RegexResult.Failure] with EMPTY_PATTERN.
 * - Invalid patterns surface [PatternSyntaxException] details as PARSE_ERROR
 *   instead of crashing the dialog.
 * - Zero-length matches are recorded, then scanning advances by exactly one
 *   character so "a*" can never loop forever.
 * - [maxMatches] caps the result; further hits set [RegexResult.matchesTruncated].
 * - Text above [MAX_TEXT_CHARS] is rejected up front (INPUT_TOO_LARGE).
 * - Nothing in this class mutates input; it is read-only by construction.
 *
 * Documented assumptions:
 * - Java regex semantics (closest to what Find/Replace uses in-app).
 * - Java regex is not interruptible: the dialog runs this on a background
 *   dispatcher and accepts that a pathological pattern keeps burning one
 *   core until the engine returns; input is capped so worst case stays
 *   bounded (documented trade-off, no silent MitM on correctness).
 */
object RegexTester {

    const val MAX_TEXT_CHARS = 1_000_000
    const val DEFAULT_MAX_MATCHES = 500
    const val MAX_MAX_MATCHES = 10_000

    enum class ErrorCode { EMPTY_PATTERN, INPUT_TOO_LARGE, PARSE_ERROR, INTERNAL_ERROR }

    data class RegexError(val code: ErrorCode, val message: String)

    data class RegexGroup(val index: Int, val text: String?, val start: Int, val end: Int)

    data class RegexMatch(val start: Int, val end: Int, val groups: List<RegexGroup>)

    data class RegexResult(
        val matches: List<RegexMatch>,
        val matchesTruncated: Boolean,
        val groupCount: Int
    ) {
        companion object {
            val EMPTY = RegexResult(emptyList(), false, 0)
        }
    }

    sealed class RegexOutcome {
        data class Found(val result: RegexResult) : RegexOutcome()
        data class Failure(val error: RegexError) : RegexOutcome()
    }

    data class Flags(
        val ignoreCase: Boolean = false,
        val multiline: Boolean = false,
        val dotAll: Boolean = false
    ) {
        internal fun toPatternBits(): Int {
            var bits = 0
            if (ignoreCase) bits = bits or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if (multiline) bits = bits or Pattern.MULTILINE
            if (dotAll) bits = bits or Pattern.DOTALL
            return bits
        }
    }

    fun run(
        pattern: String,
        text: String,
        flags: Flags = Flags(),
        maxMatches: Int = DEFAULT_MAX_MATCHES
    ): RegexOutcome {
        if (pattern.isBlank()) {
            return RegexOutcome.Failure(RegexError(ErrorCode.EMPTY_PATTERN, "pattern is empty"))
        }
        if (text.length > MAX_TEXT_CHARS) {
            return RegexOutcome.Failure(
                RegexError(ErrorCode.INPUT_TOO_LARGE, "text exceeds $MAX_TEXT_CHARS characters")
            )
        }
        val cappedMax = maxMatches.coerceIn(1, MAX_MAX_MATCHES)
        val compiled = try {
            Pattern.compile(pattern, flags.toPatternBits())
        } catch (e: PatternSyntaxException) {
            return RegexOutcome.Failure(
                RegexError(ErrorCode.PARSE_ERROR, e.description ?: e.message ?: "invalid pattern")
            )
        }
        return try {
            RegexOutcome.Found(scan(compiled.matcher(text), text.length, cappedMax))
        } catch (e: OutOfMemoryError) {
            RegexOutcome.Failure(RegexError(ErrorCode.INTERNAL_ERROR, "not enough memory"))
        } catch (e: Exception) {
            RegexOutcome.Failure(RegexError(ErrorCode.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun scan(matcher: Matcher, textLength: Int, cap: Int): RegexResult {
        val matches = ArrayList<RegexMatch>()
        var truncated = false
        var index = 0
        while (index <= textLength) {
            if (!matcher.find(index)) break
            if (matches.size == cap) {
                // One MORE match beyond the cap exists: report truncation.
                truncated = true
                break
            }
            val start = matcher.start()
            val end = matcher.end()
            val groups = ArrayList<RegexGroup>(matcher.groupCount() + 1)
            for (g in 0..matcher.groupCount()) {
                // Optional groups that did not participate report null text
                // and the (-1, -1) sentinel the Matcher provides.
                groups.appendOrSkip(g, matcher)
            }
            matches.add(RegexMatch(start, end, groups))
            index = if (end == start) {
                // Zero-length match: force progress, one char at a time,
                // so patterns like "a*" can never loop forever.
                start + 1
            } else {
                end
            }
        }
        return RegexResult(matches, truncated, matcher.groupCount())
    }

    private fun MutableList<RegexGroup>.appendOrSkip(g: Int, m: Matcher) {
        val start = m.start(g)
        val end = m.end(g)
        if (start < 0 || end < 0) {
            add(RegexGroup(g, null, -1, -1))
        } else {
            add(RegexGroup(g, m.group(g), start, end))
        }
    }
}
