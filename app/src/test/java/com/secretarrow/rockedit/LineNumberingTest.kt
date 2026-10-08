package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.LineNumbering
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Branch-matrix for the pure-JVM gutter decision layer (v0.24.0): mode
 * normalization, absolute/relative/hybrid rendering, and every guard
 * (empty docs, out-of-range carets) that must clamp instead of throw.
 */
class LineNumberingTest {
    // ------------------------------------------------------------ modeFrom

    @Test
    fun modeFromKnownValues() {
        assertEquals(LineNumbering.Mode.ABSOLUTE, LineNumbering.modeFrom("absolute"))
        assertEquals(LineNumbering.Mode.RELATIVE, LineNumbering.modeFrom("relative"))
        assertEquals(LineNumbering.Mode.HYBRID, LineNumbering.modeFrom("hybrid"))
    }

    @Test
    fun modeFromUnknownFallsBackToAbsolute() {
        // Null (missing key), empty, and corrupted values must all land on
        // the historical behavior — never a crash, never relative-by-accident.
        assertEquals(LineNumbering.Mode.ABSOLUTE, LineNumbering.modeFrom(null))
        assertEquals(LineNumbering.Mode.ABSOLUTE, LineNumbering.modeFrom(""))
        assertEquals(LineNumbering.Mode.ABSOLUTE, LineNumbering.modeFrom("bogus"))
        // Deliberately case-sensitive: stored values come from our own
        // ListPreference (fixed lowercase), so an odd case is corruption.
        assertEquals(LineNumbering.Mode.ABSOLUTE, LineNumbering.modeFrom("RELATIVE"))
    }

    // ---------------------------------------------------------- absolute

    @Test
    fun absoluteLabelsCountUpFromOne() {
        assertEquals(listOf("1", "2", "3", "4", "5"), LineNumbering.labels(5, 3, LineNumbering.Mode.ABSOLUTE))
    }

    @Test
    fun absoluteSingleLine() {
        assertEquals(listOf("1"), LineNumbering.labels(1, 1, LineNumbering.Mode.ABSOLUTE))
    }

    // ---------------------------------------------------------- relative

    @Test
    fun relativeShowsZeroOnCaretLine() {
        assertEquals(listOf("0", "1", "2", "3", "4"), LineNumbering.labels(5, 1, LineNumbering.Mode.RELATIVE))
    }

    @Test
    fun relativeDistancesCollapseSign() {
        // Caret in the middle: distances are unsigned (1 above and 1 below
        // both render "1") — vim/VS Code convention.
        assertEquals(listOf("2", "1", "0", "1", "2"), LineNumbering.labels(5, 3, LineNumbering.Mode.RELATIVE))
    }

    @Test
    fun relativeCaretOnLastLine() {
        assertEquals(listOf("4", "3", "2", "1", "0"), LineNumbering.labels(5, 5, LineNumbering.Mode.RELATIVE))
    }

    // ------------------------------------------------------------ hybrid

    @Test
    fun hybridShowsAbsoluteOnlyOnCaret() {
        assertEquals(listOf("2", "1", "3", "1", "2"), LineNumbering.labels(5, 3, LineNumbering.Mode.HYBRID))
    }

    @Test
    fun hybridCaretOnFirstLineStillShowsAbsolute() {
        // Row 1 = "1" (absolute caret line), rest count away from it.
        assertEquals(listOf("1", "1", "2", "3", "4"), LineNumbering.labels(5, 1, LineNumbering.Mode.HYBRID))
    }

    // ------------------------------------------------------------ guards

    @Test
    fun emptyAndNegativeTotalsProduceNoLabels() {
        assertEquals(emptyList<String>(), LineNumbering.labels(0, 1, LineNumbering.Mode.ABSOLUTE))
        assertEquals(emptyList<String>(), LineNumbering.labels(-3, 1, LineNumbering.Mode.RELATIVE))
    }

    @Test
    fun caretBeforeFirstLineClampsToOne() {
        // A stale selection of -1 must behave like the first line.
        assertEquals(listOf("0", "1", "2"), LineNumbering.labels(3, 0, LineNumbering.Mode.RELATIVE))
    }

    @Test
    fun caretBeyondLastLineClampsToLast() {
        // A caret line beyond the document (e.g. text shrank since the
        // selection was captured) must clamp, not throw.
        assertEquals(listOf("4", "3", "2", "1", "0"), LineNumbering.labels(5, 99, LineNumbering.Mode.RELATIVE))
        // Hybrid with clamped caret: line 5 renders its ABSOLUTE number,
        // every other line the distance to line 5.
        assertEquals(listOf("4", "3", "2", "1", "5"), LineNumbering.labels(5, 99, LineNumbering.Mode.HYBRID))
    }

    @Test
    fun labelsSizeAlwaysEqualsTotal() {
        // Every editor line must own exactly one gutter row in every mode.
        for (mode in LineNumbering.Mode.values()) {
            assertEquals(7, LineNumbering.labels(7, 4, mode).size)
        }
    }
}
