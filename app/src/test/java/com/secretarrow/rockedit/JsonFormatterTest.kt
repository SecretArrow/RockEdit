package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.IndentStyle
import com.secretarrow.rockedit.core.JsonFormatter
import com.secretarrow.rockedit.core.LineBreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage of the JSON formatter: pretty/minify, indent styles,
 * verbatim numbers, escapes, strict-grammar errors with positions,
 * depth limit, line-break handling and idempotency.
 */
class JsonFormatterTest {

    private val formatter = JsonFormatter()

    private fun format(text: String, options: FormatOptions = FormatOptions()): FormatResult =
        formatter.format(FormatRequest(text, "json", options))

    // ------------------------------------------------------------ pretty

    @Test
    fun prettyPrintsNestedStructures() {
        val out = format("""{"b":1,"a":[2,{"c":null}]}""")
        assertTrue(out is FormatResult.Success)
        val expected = """
            {
                "b": 1,
                "a": [
                    2,
                    {
                        "c": null
                    }
                ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, (out as FormatResult.Success).formattedText)
    }

    @Test
    fun preservesKeyOrder() {
        val out = format("""{"z":1,"a":2,"m":3}""")
        val text = (out as FormatResult.Success).formattedText
        assertTrue(text.indexOf("\"z\"") < text.indexOf("\"a\""))
        assertTrue(text.indexOf("\"a\"") < text.indexOf("\"m\""))
    }

    @Test
    fun emptyObjectAndArrayStayCompact() {
        val out = (format("""{"e":{},"l":[]}""") as FormatResult.Success).formattedText
        assertTrue(out.contains("\"e\": {}"))
        assertTrue(out.contains("\"l\": []"))
    }

    @Test
    fun tabIndentStyle() {
        val out = format("""{"a":{"b":1}}""", FormatOptions(indentStyle = IndentStyle.TABS))
        assertTrue((out as FormatResult.Success).formattedText.contains("\t\t\"b\": 1"))
    }

    @Test
    fun indentSizeTwo() {
        val out = format("""{"a":{"b":1}}""", FormatOptions(indentSize = 2))
        assertTrue((out as FormatResult.Success).formattedText.contains("\n  \"a\": {"))
    }

    // ----------------------------------------------------------- numbers

    @Test
    fun numbersArePreservedVerbatim() {
        // 1.10 must NOT become 1.1; huge integers must NOT lose precision.
        val src = """{"f":1.10,"e":1e3,"big":123456789012345678901234567890,"neg":-0.5}"""
        val out = (format(src) as FormatResult.Success).formattedText
        assertTrue(out.contains("1.10"))
        assertTrue(out.contains("1e3"))
        assertTrue(out.contains("123456789012345678901234567890"))
        assertTrue(out.contains("-0.5"))
    }

    // ----------------------------------------------------------- strings

    @Test
    fun unicodeStaysReadable() {
        val out = (format("""{"nama":"héllo"}""") as FormatResult.Success).formattedText
        assertTrue(out.contains("héllo"))
        assertFalse(out.contains("\\u00e9"))
    }

    @Test
    fun controlCharactersStayEscaped() {
        val out = (format("""{"t":"line1\nline2\ttab"}""") as FormatResult.Success).formattedText
        assertTrue(out.contains("line1\\nline2\\ttab"))
    }

    @Test
    fun quotesAndBackslashesReescape() {
        val out = (format("""{"q":"a\"b\\c"}""") as FormatResult.Success).formattedText
        assertTrue(out.contains("a\\\"b\\\\c"))
    }

    // ------------------------------------------------------ strict errors

    @Test
    fun trailingCommaInObjectRejected() {
        val out = format("""{"a":1,}""")
        assertTrue(out is FormatResult.Failure)
        assertEquals(FormatErrorCode.PARSE_ERROR, (out as FormatResult.Failure).error.code)
    }

    @Test
    fun trailingCommaInArrayRejected() {
        val out = format("""[1,2,]""")
        assertTrue(out is FormatResult.Failure)
    }

    @Test
    fun parseErrorCarriesPosition() {
        val src = "{\n  \"a\": 1,\n  \"b\": ,\n}"
        val out = format(src)
        assertTrue(out is FormatResult.Failure)
        val error = (out as FormatResult.Failure).error
        assertEquals(3, error.line) // the faulty line
        assertTrue(error.column != null && error.column > 0)
    }

    @Test
    fun trailingGarbageRejected() {
        val out = format("""{} {}""")
        assertTrue(out is FormatResult.Failure)
        assertEquals(FormatErrorCode.PARSE_ERROR, (out as FormatResult.Failure).error.code)
    }

    @Test
    fun singleQuotesRejected() {
        val out = format("""{'a':1}""")
        assertTrue(out is FormatResult.Failure)
    }

    @Test
    fun leadingZeroNumberRejected() {
        val out = format("""{"n":01}""")
        assertTrue(out is FormatResult.Failure)
    }

    @Test
    fun depthLimitRejected() {
        val deep = "[".repeat(600) + "]".repeat(600)
        val out = format(deep)
        assertTrue(out is FormatResult.Failure)
        assertEquals(FormatErrorCode.PARSE_ERROR, (out as FormatResult.Failure).error.code)
        assertTrue((out as FormatResult.Failure).error.message.contains("depth"))
    }

    @Test
    fun deepButWithinLimitAccepted() {
        val deep = "[".repeat(100) + "]".repeat(100)
        val out = format(deep)
        assertTrue(out is FormatResult.Success)
    }

    // ----------------------------------------------------- line handling

    @Test
    fun crlfInputNormalizedToLf() {
        val out = (format("{\r\n\"a\":1\r\n}") as FormatResult.Success).formattedText
        assertFalse(out.contains("\r"))
        assertTrue(out.contains("\n"))
    }

    @Test
    fun crlfOutputWhenRequested() {
        val out = (format("""{"a":1}""", FormatOptions(lineBreak = LineBreak.CRLF)) as FormatResult.Success)
            .formattedText
        assertTrue(out.contains("\r\n"))
    }

    @Test
    fun finalNewlineInsertedOnce() {
        val out = (format("""{"a":1}""") as FormatResult.Success).formattedText
        assertTrue(out.endsWith("}\n"))
        assertFalse(out.endsWith("\n\n"))
    }

    @Test
    fun finalNewlineOmittedWhenDisabled() {
        val options = FormatOptions(insertFinalNewline = false)
        val out = (format("""{"a":1}""", options) as FormatResult.Success).formattedText
        assertFalse(out.endsWith("\n"))
    }

    // -------------------------------------------------------- minify & idem

    @Test
    fun minifyRemovesAllOptionalWhitespace() {
        val out = (format("{\n  \"a\" : [ 1 , 2 ] ,\n  \"b\" : \"x y\"\n}") as FormatResult.Success)
        val minified = (format(out.formattedText, FormatOptions(minify = true)) as FormatResult.Success).formattedText
        assertEquals("""{"a":[1,2],"b":"x y"}""" + "\n", minified)
    }

    @Test
    fun minifyKeepsSpacesInsideStrings() {
        val out = (format("""{"s":"a  b"}""", FormatOptions(minify = true)) as FormatResult.Success).formattedText
        assertTrue(out.contains("a  b"))
    }

    @Test
    fun formattingIsIdempotent() {
        val messy = """{"b"  :   [1,2,{"c":"d e"}],"a":3.50}"""
        val once = (format(messy) as FormatResult.Success).formattedText
        val twice = (format(once) as FormatResult.Success).formattedText
        assertEquals(once, twice)
    }

    @Test
    fun changedFlagFalseWhenAlreadyFormatted() {
        val clean = "{\n    \"a\": 1\n}\n"
        val out = format(clean) as FormatResult.Success
        assertFalse(out.changed)
    }

    @Test
    fun scalarTopLevelValuesAccepted() {
        assertTrue(format("42") is FormatResult.Success)
        assertTrue(format("\"just a string\"") is FormatResult.Success)
        assertTrue(format("true") is FormatResult.Success)
        assertTrue(format("null") is FormatResult.Success)
        val bad = format("nul")
        assertTrue(bad is FormatResult.Failure)
        val error = (bad as FormatResult.Failure).error
        // Position is tracked even on the very first token: "nul" fails when
        // the 4th character of "null" is missing (after 3 consumed chars).
        assertEquals(1, error.line)
        assertEquals(4, error.column)
    }
}
