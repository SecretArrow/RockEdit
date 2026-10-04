package com.secretarrow.rockedit.core

/**
 * Pure search helpers shared by the Find/Replace dialog.
 * All functions operate on plain strings; no Android dependencies.
 */
object SearchEngine {
    /** Options for a search operation. */
    data class Options(
        val caseSensitive: Boolean = false,
    )

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
                text.lowercase().indexOf(query.lowercase(), from)
            }
        if (hit >= 0) return hit
        if (wrapAround && from > 0) {
            return if (options.caseSensitive) {
                text.indexOf(query, 0)
            } else {
                text.lowercase().indexOf(query.lowercase(), 0)
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
        val hay = if (options.caseSensitive) text else text.lowercase()
        val needle = if (options.caseSensitive) query else query.lowercase()
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
        val hay = if (options.caseSensitive) text else text.lowercase()
        val needle = if (options.caseSensitive) query else query.lowercase()
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
}
