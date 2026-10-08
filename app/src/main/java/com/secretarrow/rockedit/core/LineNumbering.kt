package com.secretarrow.rockedit.core

/**
 * v0.24.0: line-numbering modes for the editor gutter.
 *
 * Pure JVM decision layer: given the total line count, the caret's line and
 * the active mode, produces exactly one label per editor line — the UI only
 * joins them with newlines. Conventions follow desktop editors (Vim, VS
 * Code): RELATIVE shows 0 on the caret line itself; HYBRID shows the caret
 * line's ABSOLUTE number and relative distances everywhere else.
 *
 * Defensive rules:
 * - Every input is clamped; no path throws. total <= 0 yields no labels
 *   (the UI renders its own fallback).
 * - [modeFrom] normalizes any unknown stored value to ABSOLUTE, so a
 *   corrupted setting can only ever fall back to today's behavior.
 */
object LineNumbering {
    enum class Mode { ABSOLUTE, RELATIVE, HYBRID }

    /** The largest supported document; anything above is clamped, not rejected. */
    const val MAX_LINES: Int = 1_000_000

    /** Maps a stored preference value to a mode; unknown/null -> ABSOLUTE. */
    fun modeFrom(raw: String?): Mode =
        when (raw) {
            "relative" -> Mode.RELATIVE
            "hybrid" -> Mode.HYBRID
            else -> Mode.ABSOLUTE
        }

    /**
     * One label per line (1..[total], inclusive).
     *
     * @param total number of editor lines (clamped into 1..MAX_LINES)
     * @param caretLine 1-based caret line (clamped into 1..total)
     * @param mode numbering mode
     */
    fun labels(
        total: Int,
        caretLine: Int,
        mode: Mode,
    ): List<String> {
        if (total <= 0) return emptyList()
        val last = total.coerceAtMost(MAX_LINES)
        val caret = caretLine.coerceIn(1, last)
        return when (mode) {
            Mode.ABSOLUTE -> (1..last).map { it.toString() }
            Mode.RELATIVE -> (1..last).map { distance(it, caret).toString() }
            Mode.HYBRID ->
                (1..last).map { line ->
                    if (line == caret) line.toString() else distance(line, caret).toString()
                }
        }
    }

    /** Signed distance collapsed to its magnitude (relative rows are unsigned). */
    private fun distance(
        line: Int,
        caret: Int,
    ): Int = if (line >= caret) line - caret else caret - line
}
