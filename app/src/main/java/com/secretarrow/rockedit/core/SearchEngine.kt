package com.secretarrow.rockedit.core

import java.util.Locale

/**
 * Pure search helpers shared by the Find/Replace dialog.
 * All functions operate on plain strings; no Android dependencies.
 *
 * Locale note (v0.22.0): every case fold uses [Locale.ROOT], never the
 * default locale. The default locale can change string LENGTH when folding
 * (Turkish 'I'.lowercase(tr) yields two UTF-16 units), which silently
 * corrupts every match offset — a length-preserving, locale-independent
 * fold is a hard requirement for span math and replacement arithmetic.
 */
object SearchEngine {
    /** Options for a search operation. */
    data class Options(
        val caseSensitive: Boolean = false,
    )

    /**
     * All non-overlapping matches of a query, in document order.
     * [truncated] is true when [SearchEngine.DEFAULT_MATCH_LIMIT] was hit —
     * the list then holds exactly [SearchEngine.DEFAULT_MATCH_LIMIT] ranges
     * and the counter must render a "+" suffix instead of a false total.
     */
    data class MatchList(
        val ranges: List<IntRange>,
        val truncated: Boolean,
    ) {
        val count: Int
            get() = ranges.size

        companion object {
            val EMPTY = MatchList(emptyList(), truncated = false)
        }
    }

    /**
     * Hard cap on the matches computed for live highlighting. A short query
     * on a huge document can yield tens of thousands of hits; highlighting
     * them all would freeze the main thread. Matches past the cap are still
     * reachable one-by-one via [indexOf]/[indexOfPrev] — only the bulk
     * highlight/total is capped.
     */
    const val DEFAULT_MATCH_LIMIT: Int = 1_000

    /**
     * Finds the first index of [query] in [text] at or after [startIndex].
     * When [wrapAround] is true, the search wraps to the start of the document.
     * Returns -1 when nothing is found.
     */
    fun indexOf(
        text: String,
        query: String,
        startIndex: Int,
        options: Options,
        wrapAround: Boolean = true,
    ): Int {
        if (query.isEmpty()) return -1
        if (text.isEmpty()) return -1
        var from = startIndex.coerceIn(0, text.length)
        val hit =
            if (options.caseSensitive) {
                text.indexOf(query, from)
            } else {
                text.lowercase(Locale.ROOT).indexOf(query.lowercase(Locale.ROOT), from)
            }
        if (hit >= 0) return hit
        if (wrapAround && from > 0) {
            return if (options.caseSensitive) {
                text.indexOf(query, 0)
            } else {
                text.lowercase(Locale.ROOT).indexOf(query.lowercase(Locale.ROOT), 0)
            }
        }
        return -1
    }

    /**
     * Counts occurrences of [query] in [text].
     * Overlapping matches are not counted twice (matches advance past the hit).
     */
    fun countMatches(
        text: String,
        query: String,
        options: Options,
    ): Int {
        if (query.isEmpty() || text.isEmpty()) return 0
        var count = 0
        var i = 0
        val hay = if (options.caseSensitive) text else text.lowercase(Locale.ROOT)
        val needle = if (options.caseSensitive) query else query.lowercase(Locale.ROOT)
        while (i <= hay.length - needle.length) {
            val idx = hay.indexOf(needle, i)
            if (idx < 0) break
            count++
            i = idx + needle.length
        }
        return count
    }

    /**
     * Replaces every occurrence of [query] with [replacement].
     * Returns the new text and the number of replacements performed.
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        options: Options,
    ): Pair<String, Int> {
        if (query.isEmpty()) return text to 0
        val sb = StringBuilder()
        var count = 0
        var i = 0
        val hay = if (options.caseSensitive) text else text.lowercase(Locale.ROOT)
        val needle = if (options.caseSensitive) query else query.lowercase(Locale.ROOT)
        while (i < text.length) {
            val idx = hay.indexOf(needle, i)
            if (idx < 0) {
                sb.append(text, i, text.length)
                break
            }
            sb.append(text, i, idx)
            sb.append(replacement)
            count++
            i = idx + needle.length
        }
        return sb.toString() to count
    }

    /**
     * Lists every non-overlapping match of [query] in [text], in document
     * order, up to [limit] hits (see [DEFAULT_MATCH_LIMIT] for why the cap
     * exists). Ranges are INCLUSIVE on both ends (`idx..idx+query.length-1`)
     * so they map 1:1 onto span start/end arithmetic. Empty query, empty
     * text, non-positive limit, or a query longer than the text are all
     * legitimate no-match inputs and return [MatchList.EMPTY] — never throw.
     */
    fun findAllMatches(
        text: String,
        query: String,
        options: Options,
        limit: Int = DEFAULT_MATCH_LIMIT,
    ): MatchList {
        if (query.isEmpty() || text.isEmpty() || limit <= 0) return MatchList.EMPTY
        val hay = if (options.caseSensitive) text else text.lowercase(Locale.ROOT)
        val needle = if (options.caseSensitive) query else query.lowercase(Locale.ROOT)
        if (needle.length > hay.length) return MatchList.EMPTY
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i <= hay.length - needle.length) {
            val idx = hay.indexOf(needle, i)
            if (idx < 0) break
            if (ranges.size >= limit) return MatchList(ranges, truncated = true)
            ranges.add(idx..(idx + needle.length - 1))
            i = idx + needle.length
        }
        if (ranges.isEmpty()) return MatchList.EMPTY
        return MatchList(ranges, truncated = false)
    }

    /**
     * 1-based ordinal of the match in [ranges] that covers [index]
     * (inclusive on both ends, so the caret sitting on a match start or end
     * still counts). Returns 0 when [index] is outside every match or the
     * list is empty. Ranges are sorted and non-overlapping (contract of
     * [findAllMatches]), so scanning stops at the first range past the index.
     */
    fun matchOrdinalAt(
        ranges: List<IntRange>,
        index: Int,
    ): Int {
        if (index < 0) return 0
        for ((i, r) in ranges.withIndex()) {
            if (r.first > index) break
            if (index <= r.last) return i + 1
        }
        return 0
    }

    /**
     * Language-neutral counter label for the Find dialog. Numeric-only by
     * design — no plurals, no locale formatting, so the label cannot be a
     * translation or RTL hazard. Returns "" when there is nothing to show
     * (caller hides the counter), "k/N" when the caret sits on match k of N,
     * and plain "N" otherwise; "N+" when the match list was truncated.
     */
    fun counterLabel(
        list: MatchList,
        currentIndex: Int,
    ): String {
        if (list.ranges.isEmpty()) return ""
        val total = if (list.truncated) "${list.count}+" else "${list.count}"
        val ordinal = matchOrdinalAt(list.ranges, currentIndex)
        return if (ordinal > 0) "$ordinal/$total" else total
    }

    /**
     * Finds the last match of [query] that ENDS at or before [beforeIndex]
     * (i.e. the match immediately to the left of the caret). With
     * [wrapAround], a document with no match left of the caret falls back to
     * the LAST match in the whole text — pressing Previous from the very top
     * lands on the final hit, matching desktop-editor convention; a document
     * with exactly one match therefore re-selects it instead of reporting
     * failure. Returns -1 for empty query/text or a true no-match.
     */
    fun indexOfPrev(
        text: String,
        query: String,
        beforeIndex: Int,
        options: Options,
        wrapAround: Boolean = true,
    ): Int {
        if (query.isEmpty() || text.isEmpty()) return -1
        val hay = if (options.caseSensitive) text else text.lowercase(Locale.ROOT)
        val needle = if (options.caseSensitive) query else query.lowercase(Locale.ROOT)
        if (needle.length > hay.length) return -1
        // Largest legal start index whose match does not cross beforeIndex.
        val cap = minOf(beforeIndex, hay.length) - needle.length
        if (cap >= 0) {
            val hit = hay.lastIndexOf(needle, cap)
            if (hit >= 0) return hit
        }
        if (wrapAround) {
            return hay.lastIndexOf(needle, hay.length - needle.length)
        }
        return -1
    }
}
