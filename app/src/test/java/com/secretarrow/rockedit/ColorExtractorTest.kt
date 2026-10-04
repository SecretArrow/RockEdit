package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.ColorExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [ColorExtractor] (v0.11.0): hex lengths 3/4/6/8,
 * malformed hex skipped, rgb/rgba channels, percent channels skipped,
 * hsl conversion, named colors, offsets/sources, cap + truncation.
 */
class ColorExtractorTest {
    private fun colors(text: String) = ColorExtractor.extract(text).colors

    private fun argbOf(
        text: String,
        index: Int = 0,
    ): Long = colors(text)[index].argb

    // ------------------------------------------------------------------ hex

    @Test
    fun hexThreeSixDigit() {
        assertEquals(0xFFFF0000L, argbOf("color: #f00;"))
        assertEquals(0xFFFF0000L, argbOf("color: #FF0000;"))
    }

    @Test
    fun hexFourAndEightDigitWithAlpha() {
        assertEquals(0xFFFF0000L, argbOf("#f00f"))
        assertEquals(0x80FF0000L, argbOf("#FF000080"))
    }

    @Test
    fun hexUppercaseAndMixedCase() {
        assertEquals(0xFF6495EDL, argbOf("#6495eD"))
    }

    @Test
    fun malformedHexIsSkippedNotFatal() {
        val summary = ColorExtractor.extract("a #abcde b #zzz c #12 d")
        assertEquals(0, summary.colors.size)
        assertEquals(3, summary.skipped)
    }

    @Test
    fun hexInsideLongerTokenStillMatches() {
        // A hash run ends at the first non-hex char: "#abc." -> #abc.
        assertEquals(0xFFAABBCCL, argbOf("fill #abc."))
    }

    // ------------------------------------------------------------- rgb/rgba

    @Test
    fun rgbAndRgbaParse() {
        assertEquals(0xFFFF0000L, argbOf("rgb(255, 0, 0)"))
        assertEquals(0x7F0000FFL, argbOf("rgba(0, 0, 255, 0.5)"))
    }

    @Test
    fun rgbOutOFRangeIsSkipped() {
        val summary = ColorExtractor.extract("rgb(300, 0, 0) rgb(-1,0,0) rgb(1,2)")
        assertEquals(0, summary.colors.size)
        assertEquals(3, summary.skipped)
    }

    @Test
    fun percentChannelsAreSkippedByDesign() {
        val summary = ColorExtractor.extract("rgb(50%, 0, 0)")
        assertEquals(0, summary.colors.size)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun unclosedParenIsSkipped() {
        val summary = ColorExtractor.extract("rgb(255, 0, 0")
        assertEquals(0, summary.colors.size)
        assertEquals(1, summary.skipped)
    }

    // ------------------------------------------------------------------ hsl

    @Test
    fun hslPrimaryColors() {
        assertEquals(0xFFFF0000L, argbOf("hsl(0, 100%, 50%)"))
        assertEquals(0xFF00FF00L, argbOf("hsl(120, 100%, 50%)"))
        assertEquals(0xFF0000FFL, argbOf("hsl(240, 100%, 50%)"))
    }

    @Test
    fun hslaWithAlpha() {
        val argb = argbOf("hsla(120, 100%, 50%, 0.5)")
        assertEquals(0x7F, (argb ushr 24) and 0xFF)
        assertEquals(0x00FF00L, argb and 0xFFFFFF)
    }

    @Test
    fun hslOutOfRangeIsSkipped() {
        val summary = ColorExtractor.extract("hsl(361, 100%, 50%) hsl(0, 200%, 50%)")
        assertEquals(0, summary.colors.size)
        assertEquals(2, summary.skipped)
    }

    // ---------------------------------------------------------------- named

    @Test
    fun namedColorsCaseInsensitive() {
        assertEquals(0xFFFF0000L, argbOf("stroke red"))
        assertEquals(0xFF6495EDL, argbOf("CornflowerBlue"))
    }

    @Test
    fun unknownWordsProduceNothingAndDoNotCrash() {
        val summary = ColorExtractor.extract("totally notacolor word")
        assertEquals(0, summary.colors.size)
    }

    // -------------------------------------------------------------- offsets

    @Test
    fun occurrenceOffsetsAndSourceAreExact() {
        val text = "a #abc b"
        val occurrence = colors(text)[0]
        assertEquals(2, occurrence.start)
        assertEquals(6, occurrence.end)
        assertEquals("#abc", occurrence.source)
    }

    @Test
    fun rgbaWordIsNotParsedAsRgbPlusGarbage() {
        val summary = ColorExtractor.extract("rgba(1, 2, 3, 0.9)")
        assertEquals(1, summary.colors.size)
        assertEquals(0, summary.skipped)
    }

    // ------------------------------------------------------------------ cap

    @Test
    fun occurrenceCapSetsTruncatedFlag() {
        val text = "#abc ".repeat(ColorExtractor.MAX_OCCURRENCES + 10)
        val summary = ColorExtractor.extract(text)
        assertEquals(ColorExtractor.MAX_OCCURRENCES, summary.colors.size)
        assertTrue(summary.truncated)
    }

    @Test
    fun emptyTextYieldsEmptySummary() {
        val summary = ColorExtractor.extract("")
        assertEquals(0, summary.colors.size)
        assertEquals(0, summary.skipped)
        assertTrue(!summary.truncated)
    }
}
