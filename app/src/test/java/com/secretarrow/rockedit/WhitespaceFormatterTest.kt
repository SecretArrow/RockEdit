package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.FormatterRegistry
import com.secretarrow.rockedit.core.LineBreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage of the universal whitespace formatter (the fallback every
 * unknown language gets): line-break normalization, trailing trim, final
 * newline, changed flag, and registry integration for arbitrary languages.
 */
class WhitespaceFormatterTest {
    private val registry = FormatterRegistry.default()

    private fun format(
        text: String,
        options: FormatOptions = FormatOptions(),
    ): FormatResult = registry.format(FormatRequest(text, "python", options))

    // ------------------------------------------------- line break handling

    @Test
    fun crlfNormalizedToLf() {
        val out = (format("a\r\nb\r\nc") as FormatResult.Success).formattedText
        assertEquals("a\nb\nc\n", out)
    }

    @Test
    fun loneCrNormalizedToLf() {
        val out = (format("a\rb\rc") as FormatResult.Success).formattedText
        assertEquals("a\nb\nc\n", out)
    }

    @Test
    fun mixedBreaksNormalized() {
        val out = (format("a\r\nb\nc\rd") as FormatResult.Success).formattedText
        assertEquals("a\nb\nc\nd\n", out)
    }

    @Test
    fun crlfOutputWhenRequested() {
        val out = (format("a\nb", FormatOptions(lineBreak = LineBreak.CRLF)) as FormatResult.Success).formattedText
        assertEquals("a\r\nb\r\n", out)
    }

    // ------------------------------------------------------ trailing trim

    @Test
    fun trailingSpacesTrimmed() {
        val out = (format("a  \nb\t\n") as FormatResult.Success).formattedText
        assertEquals("a\nb\n", out)
    }

    @Test
    fun crlfTrailingSpacesTrimmed() {
        // Regression: line breaks must be normalized BEFORE trimming,
        // otherwise the CR of CRLF input blocks the trailing-space trim.
        val out = (format("a  \r\nb\t\r\n") as FormatResult.Success).formattedText
        assertEquals("a\nb\n", out)
    }

    @Test
    fun interiorSpacingPreserved() {
        val out = (format("a  b\tc") as FormatResult.Success).formattedText
        assertEquals("a  b\tc\n", out)
    }

    @Test
    fun trailingWhitespaceBeforeEofTrimmed() {
        val out = (format("a\n   ") as FormatResult.Success).formattedText
        assertEquals("a\n", out)
    }

    @Test
    fun trimCanBeDisabled() {
        val options = FormatOptions(trimTrailingWhitespace = false)
        val out = (format("a  \n", options) as FormatResult.Success).formattedText
        assertEquals("a  \n", out)
    }

    // ------------------------------------------------------- final newline

    @Test
    fun finalNewlineInserted() {
        val out = (format("a") as FormatResult.Success).formattedText
        assertEquals("a\n", out)
    }

    @Test
    fun finalNewlineNotDuplicated() {
        val out = (format("a\n") as FormatResult.Success).formattedText
        assertEquals("a\n", out)
    }

    @Test
    fun finalNewlineCanBeDisabled() {
        val out = (format("a", FormatOptions(insertFinalNewline = false)) as FormatResult.Success).formattedText
        assertEquals("a", out)
    }

    // ------------------------------------------------------- changed flag

    @Test
    fun changedTrueWhenDirty() {
        val out = format("a  \r\nb") as FormatResult.Success
        assertTrue(out.changed)
    }

    @Test
    fun changedFalseWhenAlreadyClean() {
        val out = format("a\nb\n") as FormatResult.Success
        assertFalse(out.changed)
    }

    // ------------------------------------------------- registry integration

    @Test
    fun unknownLanguagesRouteToFallback() {
        for (lang in listOf("txt", "python", "kotlin", "csv", "log")) {
            val out = registry.format(FormatRequest("x  \n", lang))
            assertTrue("language $lang should format", out is FormatResult.Success)
            assertEquals("x\n", (out as FormatResult.Success).formattedText)
        }
    }

    @Test
    fun markdownTableFormattingSafe() {
        // Real-world case: a Markdown file keeps its tables, just cleaner.
        val src = "| A | B |  \n|---|---|  \n| 1 | 2 |  "
        val out = (registry.format(FormatRequest(src, "markdown")) as FormatResult.Success).formattedText
        assertEquals("| A | B |\n|---|---|\n| 1 | 2 |\n", out)
    }

    @Test
    fun idempotent() {
        val once = (format("a  \r\nb\t\n") as FormatResult.Success).formattedText
        val twice = (format(once) as FormatResult.Success).formattedText
        assertEquals(once, twice)
    }
}
