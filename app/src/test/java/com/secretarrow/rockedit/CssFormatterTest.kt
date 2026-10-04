package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.CssFormatter
import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage of the CSS formatter: block layout, nesting, comments,
 * string/paren isolation, unbalanced-structure errors with lines, minify.
 */
class CssFormatterTest {

    private val formatter = CssFormatter()

    private fun format(text: String, options: FormatOptions = FormatOptions()): FormatResult =
        formatter.format(FormatRequest(text, "css", options))

    // ------------------------------------------------------------ pretty

    @Test
    fun prettyPrintsRules() {
        val out = (format("body{color:red;background:#fff;}") as FormatResult.Success).formattedText
        assertEquals(
            "body {\n    color: red;\n    background: #fff;\n}\n",
            out
        )
    }

    @Test
    fun multiSelectorRule() {
        val out = (format("h1, h2{margin:0}") as FormatResult.Success).formattedText
        assertEquals("h1, h2 {\n    margin: 0\n}\n", out)
    }

    @Test
    fun nestedMediaQuery() {
        val out = (format("@media (max-width:600px){.a{color:red}}") as FormatResult.Success).formattedText
        assertEquals(
            "@media (max-width:600px) {\n    .a {\n        color: red\n    }\n}\n",
            out
        )
    }

    @Test
    fun commentsPreservedInPrettyMode() {
        val out = (format("/* header */\nbody{color:red}") as FormatResult.Success).formattedText
        assertTrue(out.contains("/* header */"))
        assertTrue(out.contains("color: red"))
    }

    @Test
    fun bracesInsideCommentDoNotAffectDepth() {
        val out = (format("/* { } { */\na{color:red}") as FormatResult.Success).formattedText
        assertTrue(out.contains("color: red"))
    }

    @Test
    fun bracesInsideStringDoNotAffectDepth() {
        val out = (format("a:after{content:\"{\";}") as FormatResult.Success).formattedText
        assertTrue(out.contains("content: \"{\";"))
    }

    @Test
    fun dataUriNotBrokenBySemicolon() {
        val src = ".i{background:url(data:image/png;base64,AAA/BBB==) no-repeat}"
        val out = (format(src) as FormatResult.Success).formattedText
        // The data URI stays on one single line, untouched.
        assertTrue(out.contains("url(data:image/png;base64,AAA/BBB==)"))
    }

    // -------------------------------------------------------- errors

    @Test
    fun strayClosingBraceReportsLine() {
        val out = format("a{color:red}\n}")
        assertTrue(out is FormatResult.Failure)
        val error = (out as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertEquals(2, error.line)
    }

    @Test
    fun unclosedBlockReportsOpeningLine() {
        val out = format("a{\nb{color:red}")
        assertTrue(out is FormatResult.Failure)
        assertEquals(1, (out as FormatResult.Failure).error.line)
        assertTrue((out as FormatResult.Failure).error.message.contains("line 1"))
    }

    @Test
    fun unterminatedStringReportsStartLine() {
        val out = format("a{content:\"open\nb{color:red}}")
        assertTrue(out is FormatResult.Failure)
        assertEquals(1, (out as FormatResult.Failure).error.line)
    }

    @Test
    fun unterminatedCommentReportsStartLine() {
        val out = format("/* never closed\na{color:red}")
        assertTrue(out is FormatResult.Failure)
        assertEquals(1, (out as FormatResult.Failure).error.line)
        assertTrue((out as FormatResult.Failure).error.message.contains("comment"))
    }

    @Test
    fun unclosedParenReportsLine() {
        val out = format("a{background:url(unclosed}")
        assertTrue(out is FormatResult.Failure)
        assertTrue((out as FormatResult.Failure).error.message.contains("'('"))
    }

    // -------------------------------------------------------- minify

    @Test
    fun minifyStripsCommentsAndWhitespace() {
        val out = (format("/* c */\na { color : red ; margin : 0 }\n", FormatOptions(minify = true)) as FormatResult.Success)
            .formattedText
        assertEquals("a{color:red;margin:0}\n", out)
    }

    @Test
    fun minifyKeepsStringContentVerbatim() {
        val out = (format("a{content:\"x  y\"}", FormatOptions(minify = true)) as FormatResult.Success).formattedText
        assertTrue(out.contains("\"x  y\""))
    }

    // ---------------------------------------------------- final touches

    @Test
    fun finalNewlineInserted() {
        val out = (format("a{color:red}") as FormatResult.Success).formattedText
        assertTrue(out.endsWith("}\n"))
        assertFalse(out.endsWith("\n\n"))
    }

    @Test
    fun idempotentFormatting() {
        val messy = "body{color : red; /* note */ margin : 0 }"
        val once = (format(messy) as FormatResult.Success).formattedText
        val twice = (format(once) as FormatResult.Success).formattedText
        assertEquals(once, twice)
    }
}
