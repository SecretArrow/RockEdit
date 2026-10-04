package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.IndentStyle
import com.secretarrow.rockedit.core.LineBreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for [BraceFormatter] (C-family + smart contracts).
 *
 * Every guard and structural branch has at least one test:
 * happy path, strings/comments/char-literals/raw-strings, unbalanced
 * braces (extra closer / unclosed), unterminated strings, lenient vs
 * strict, CRLF input, preprocessor, depth cap, minify, tabs, idempotency.
 */
class BraceFormatterTest {

    private val fmt = com.secretarrow.rockedit.core.BraceFormatter()

    private fun run(text: String, language: String, options: FormatOptions = FormatOptions()) =
        fmt.format(FormatRequest(text, language, options.copy(insertFinalNewline = false)))

    private fun ok(result: FormatResult): String {
        assertTrue("expected Success but was $result", result is FormatResult.Success)
        return (result as FormatResult.Success).formattedText
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun kotlinNestedBlocksIndent() {
        val out = ok(run("class A {\nfun f() {\nif (x) {\ny()\n}\n}\n}", "kotlin"))
        assertEquals(
            "class A {\n    fun f() {\n        if (x) {\n            y()\n        }\n    }\n}",
            out
        )
    }

    @Test
    fun elseOnCloserLineDedentsOnce() {
        val out = ok(run("if (a) {\nb()\n} else {\nc()\n}", "java"))
        assertEquals("if (a) {\n    b()\n} else {\n    c()\n}", out)
    }

    @Test
    fun callbackCloseParenBraceDedentsOnce() {
        val out = ok(run("foo(() => {\nbar();\n});", "javascript"))
        assertEquals("foo(() => {\n    bar();\n});", out)
    }

    @Test
    fun alreadyFormattedIsUnchanged() {
        val src = "fun f() {\n    return 1\n}"
        val result = run(src, "kotlin")
        assertTrue(result is FormatResult.Success && !result.changed)
        assertEquals(src, (result as FormatResult.Success).formattedText)
    }

    // ---------------------------------------- braces outside code are ignored

    @Test
    fun bracesInsideStringsIgnored() {
        val out = ok(run("val s = \"{} {\" {\n}", "kotlin"))
        assertEquals("val s = \"{} {\" {\n}", out)
    }

    @Test
    fun bracesInsideCharLiteralIgnored() {
        val out = ok(run("fun f() {\nval c = '{'\n}", "kotlin"))
        assertEquals("fun f() {\n    val c = '{'\n}", out)
    }

    @Test
    fun bracesInsideLineCommentIgnored() {
        val out = ok(run("fun f() { // } {\nbody()\n}\n", "java"))
        assertEquals("fun f() { // } {\n    body()\n}\n", out)
    }

    @Test
    fun finalNewlineInsertedWhenRequested() {
        val out = ok(
            fmt.format(
                FormatRequest("fun f() {\n    y()\n}", "kotlin", FormatOptions(insertFinalNewline = true))
            )
        )
        assertEquals("fun f() {\n    y()\n}\n", out)
    }

    @Test
    fun blockCommentSpansLinesVerbatim() {
        val src = "/* { unclosed\nstill comment } */\nval x = 1"
        val out = ok(run(src, "kotlin"))
        assertEquals("/* { unclosed\nstill comment } */\nval x = 1", out)
    }

    @Test
    fun kotlinRawStringPreservedVerbatim() {
        val src = "val s = \"\"\"\nfun fake() {\n}\n\"\"\""
        val out = ok(run(src, "kotlin"))
        assertEquals(src, out)
    }

    // ------------------------------------------------------------ strict errors

    @Test
    fun extraClosingBraceFailsWithLine() {
        val result = run("fun f() {\n}\n}", "kotlin")
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertEquals(3, error.line)
    }

    @Test
    fun unclosedBraceFailsAtEof() {
        val result = run("fun f() {\nval x = 1", "java")
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertTrue(error.message.contains("unclosed"))
    }

    @Test
    fun unterminatedStringFailsWithLine() {
        val result = run("val s = \"abc", "kotlin")
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(1, error.line)
        assertTrue(error.message.contains("string", ignoreCase = true))
    }

    // ------------------------------------------------------------ lenient mode

    @Test
    fun lenientClampsExtraClosingBrace() {
        // Lenient keeps the offending line (best effort) instead of failing.
        val out = ok(run("fun f() {\n}\n}", "kotlin", FormatOptions(lenient = true)))
        assertEquals("fun f() {\n}\n}", out)
    }

    @Test
    fun twoClosersOnOneLineDedentOneLevel() {
        val out = ok(run("fun f() {\nif (x) {\n}}", "java"))
        assertEquals("fun f() {\n    if (x) {\n    }}", out)
    }

    @Test
    fun lenientToleratesUnterminatedString() {
        val out = ok(run("val s = \"abc", "kotlin", FormatOptions(lenient = true)))
        assertEquals("val s = \"abc", out)
    }

    // --------------------------------------------------------- input variants

    @Test
    fun crlfInputWithCrlfOptionKeepsLineBreaks() {
        val options = FormatOptions(lineBreak = LineBreak.CRLF)
        val out = ok(run("class A {\r\nval x = 1\r\n}", "kotlin", options))
        assertEquals("class A {\r\n    val x = 1\r\n}", out)
    }

    @Test
    fun cPreprocessorStartsAtColumnZero() {
        val out = ok(run("#include <stdio.h>\nint main(void) {\nreturn 0;\n}", "c"))
        assertEquals("#include <stdio.h>\nint main(void) {\n    return 0;\n}", out)
    }

    @Test
    fun rustLifetimesDoNotOpenStrings() {
        val out = ok(run("fn f<'a>(x: &'a str) {\nlet y = 1;\n}", "rust"))
        assertEquals("fn f<'a>(x: &'a str) {\n    let y = 1;\n}", out)
    }

    @Test
    fun blankLinesAreKeptEmpty() {
        val out = ok(run("fun f() {\n\n\n    y()\n}", "kotlin"))
        assertEquals("fun f() {\n\n\n    y()\n}", out)
    }

    @Test
    fun emptyAndBlankInputAreSkipped() {
        assertTrue(run("", "kotlin") is FormatResult.Skipped)
        assertTrue(run("   \n  ", "kotlin") is FormatResult.Skipped)
    }

    // ------------------------------------------------------------- rendering

    @Test
    fun minifyRemovesIndentButKeepsLines() {
        val out = ok(run("if (x) {\ny()\n}", "kotlin", FormatOptions(minify = true)))
        assertEquals("if (x) {\ny()\n}", out)
    }

    @Test
    fun tabIndentOption() {
        val out = ok(run("if (x) {\ny()\n}", "kotlin", FormatOptions(indentStyle = IndentStyle.TABS)))
        assertEquals("if (x) {\n\ty()\n}", out)
    }

    @Test
    fun depthBeyondLimitFailsSafely() {
        val pathological = List(600) { "{" }.joinToString("\n")
        val result = run(pathological, "java")
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertTrue(error.message.contains("depth"))
    }

    @Test
    fun sizeCapRejectsHugeInput() {
        val huge = "a".repeat(FormatRequest.MAX_TEXT_CHARS + 1)
        val result = run(huge, "kotlin")
        assertTrue(result is FormatResult.Failure)
        assertEquals(FormatErrorCode.INPUT_TOO_LARGE, (result as FormatResult.Failure).error.code)
    }

    // ------------------------------------------------------------ idempotency

    @Test
    fun formattingTwiceIsStable() {
        val src = "fun f() {\nif (x) {\ny()\n}\n}"
        val once = ok(run(src, "kotlin"))
        val twice = ok(run(once, "kotlin"))
        assertEquals(once, twice)
    }
}
