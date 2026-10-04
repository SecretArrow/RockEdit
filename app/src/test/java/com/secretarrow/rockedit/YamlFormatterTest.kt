package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for [YamlFormatter]: tab-in-indentation rejection
 * (strict) and conversion (lenient), block-scalar preservation, trailing
 * whitespace trimming outside block scalars, blank lines, CRLF, skip.
 */
class YamlFormatterTest {

    private val fmt = com.secretarrow.rockedit.core.YamlFormatter()

    private fun run(text: String, options: FormatOptions = FormatOptions()) =
        fmt.format(FormatRequest(text, "yaml", options.copy(insertFinalNewline = false)))

    private fun ok(result: FormatResult): String {
        assertTrue("expected Success but was $result", result is FormatResult.Success)
        return (result as FormatResult.Success).formattedText
    }

    @Test
    fun tabInIndentationFailsWithLine() {
        val result = run("key:\n\tvalue")
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertEquals(2, error.line)
    }

    @Test
    fun tabConvertedToSpacesInLenientMode() {
        val out = ok(run("key:\n\tvalue", FormatOptions(lenient = true)))
        assertEquals("key:\n    value", out)
    }

    @Test
    fun tabAsSeparatorIsLegal() {
        val out = ok(run("key:\tvalue with tab"))
        assertEquals("key:\tvalue with tab", out)
    }

    @Test
    fun blockScalarContentPreservedByteForByte() {
        val src = "text: |\n  line one  \n  trailing   \n  done"
        val out = ok(run(src, FormatOptions(lenient = true)))
        assertEquals(src, out)
    }

    @Test
    fun trailingWhitespaceTrimmedOutsideBlockScalars() {
        val out = ok(run("key: value   \nother: 1"))
        assertEquals("key: value\nother: 1", out)
    }

    @Test
    fun blockScalarEndsWhenDedented() {
        val src = "text: |\n  inner\nother: 1"
        val out = ok(run(src))
        assertEquals(src, out)
    }

    @Test
    fun foldedScalarIndicatorAlsoOpensBlockScalar() {
        val src = "desc: >-\n  folded   text\nnext: 1"
        val out = ok(run(src))
        assertEquals(src, out)
    }

    @Test
    fun crlfInputHandled() {
        val out = ok(run("key: value\r\nother: 1\r\n"))
        assertEquals("key: value\nother: 1\n", out)
    }

    @Test
    fun emptyInputIsSkipped() {
        assertTrue(run("") is FormatResult.Skipped)
    }
}
