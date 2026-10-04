package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for [IndentFormatter]:
 * REINDENT style (Python/Vyper): unit detection (GCD), rescaling,
 * continuation lines, docstrings verbatim, tab rejection, mixed indent,
 * comment-only lines.
 * KEYWORD style (Ruby/Lua/Elixir/Julia/LaTeX): openers/closers/dedent
 * keywords, one-line blocks, unbalanced blocks, lenient mode.
 */
class IndentFormatterTest {
    private val fmt =
        com.secretarrow.rockedit.core
            .IndentFormatter()

    private fun run(
        text: String,
        language: String,
        options: FormatOptions = FormatOptions(),
    ) = fmt.format(FormatRequest(text, language, options.copy(insertFinalNewline = false)))

    private fun ok(result: FormatResult): String {
        assertTrue("expected Success but was $result", result is FormatResult.Success)
        return (result as FormatResult.Success).formattedText
    }

    // ------------------------------------------------- REINDENT (python/vyper)

    @Test
    fun pythonTwoSpaceConvertedToFour() {
        val src = "def f():\n  if x:\n    return 1\n  else:\n    return 2"
        val out = ok(run(src, "python"))
        assertEquals(
            "def f():\n    if x:\n        return 1\n    else:\n        return 2",
            out,
        )
    }

    @Test
    fun pythonFourSpaceConvertedToTwo() {
        val src = "def f():\n    if x:\n        return 1"
        val out = ok(run(src, "python", FormatOptions(indentSize = 2)))
        assertEquals("def f():\n  if x:\n    return 1", out)
    }

    @Test
    fun pythonAlreadyConsistentIsUnchanged() {
        val src = "def f():\n    return 1"
        val result = run(src, "python")
        assertTrue(result is FormatResult.Success && !result.changed)
    }

    @Test
    fun continuationLinesInsideBracketsAreRescaledSafely() {
        val src = "result = foo(\n    a,\n    b,\n)\nx = 1"
        val out = ok(run(src, "python"))
        assertEquals(src, out)
    }

    @Test
    fun alignedContinuationKeepsOriginalWidth() {
        // width 6 is not a multiple of the detected unit 4: alignment kept.
        val src = "foo(\n      a)\nx = 1"
        val out = ok(run(src, "python"))
        assertEquals(src, out)
    }

    @Test
    fun docstringInteriorsArePreservedVerbatim() {
        val src = "def f():\n    \"\"\"Docs:\n    keep: this\n    \"\"\"\n    return 1"
        val out = ok(run(src, "python"))
        assertEquals(src, out)
    }

    @Test
    fun commentOnlyLinesDoNotBreakUnitDetection() {
        val src = "def f():\n    a = 1\n    # note at odd width\n    b = 2"
        val out = ok(run(src, "python"))
        assertEquals(src, out)
    }

    @Test
    fun mixedIndentFailsWithLine() {
        val src = "def f():\n   a = 1\n  b = 2"
        val result = run(src, "python")
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertEquals(3, error.line)
    }

    @Test
    fun tabIndentFailsInStrictMode() {
        val result = run("\tdef f():", "python")
        assertTrue(result is FormatResult.Failure)
        assertEquals(1, (result as FormatResult.Failure).error.line)
    }

    @Test
    fun tabIndentConvertedInLenientMode() {
        val out = ok(run("def f():\n\tpass", "python", FormatOptions(lenient = true)))
        assertEquals("def f():\n    pass", out)
    }

    @Test
    fun vyperRescalesWithDecorators() {
        val src = "# pragma version ^0.4.0\n\n@external\ndef f():\n  pass"
        val out = ok(run(src, "vyper"))
        assertEquals(
            "# pragma version ^0.4.0\n\n@external\ndef f():\n    pass",
            out,
        )
    }

    @Test
    fun minifyIsIgnoredForIndentLanguages() {
        val src = "def f():\n  return 1"
        val out = ok(run(src, "python", FormatOptions(minify = true)))
        assertEquals("def f():\n    return 1", out)
    }

    @Test
    fun blankInputIsSkipped() {
        assertTrue(run("", "python") is FormatResult.Skipped)
    }

    @Test
    fun reindentIsIdempotent() {
        val src = "def f():\n  if x:\n    return 1"
        val once = ok(run(src, "python"))
        val twice = ok(run(once, "python"))
        assertEquals(once, twice)
    }

    // ------------------------------------------------ KEYWORD (ruby/lua/etc.)

    @Test
    fun rubyDefClassEndIndents() {
        val src = "class Foo\ndef bar\nif z\nputs 1\nend\nend\nend"
        val out = ok(run(src, "ruby"))
        assertEquals(
            "class Foo\n    def bar\n        if z\n            puts 1\n        end\n    end\nend",
            out,
        )
    }

    @Test
    fun rubyModifierIfDoesNotOpenBlock() {
        val src = "def bar\nx = 1 if y\nend"
        val out = ok(run(src, "ruby"))
        assertEquals("def bar\n    x = 1 if y\nend", out)
    }

    @Test
    fun rubyBlockDoOpens() {
        val src = "def bar\n10.times do |i|\nputs i\nend\nend"
        val out = ok(run(src, "ruby"))
        assertEquals(
            "def bar\n    10.times do |i|\n        puts i\n    end\nend",
            out,
        )
    }

    @Test
    fun luaFunctionIfThenEnd() {
        val src = "function f(x)\nif x then\nprint(1)\nend\nend"
        val out = ok(run(src, "lua"))
        assertEquals(
            "function f(x)\n    if x then\n        print(1)\n    end\nend",
            out,
        )
    }

    @Test
    fun elixirDoEndBlocks() {
        val src = "defmodule M do\ndef f(x) do\nx + 1\nend\nend"
        val out = ok(run(src, "elixir"))
        assertEquals(
            "defmodule M do\n    def f(x) do\n        x + 1\n    end\nend",
            out,
        )
    }

    @Test
    fun juliaElseDedents() {
        val src = "function f(x)\nif x\nreturn 1\nelse\nreturn 2\nend\nend"
        val out = ok(run(src, "julia"))
        assertEquals(
            "function f(x)\n    if x\n        return 1\n    else\n        return 2\n    end\nend",
            out,
        )
    }

    @Test
    fun latexBeginEndEnvironments() {
        val src = "\\begin{document}\n\\begin{itemize}\n\\item one\n\\end{itemize}\n\\end{document}"
        val out = ok(run(src, "latex"))
        assertEquals(
            "\\begin{document}\n    \\begin{itemize}\n        \\item one\n    \\end{itemize}\n\\end{document}",
            out,
        )
    }

    @Test
    fun unclosedKeywordBlockFailsStrict() {
        val result = run("def f\nx = 1", "ruby")
        assertTrue(result is FormatResult.Failure)
        assertTrue((result as FormatResult.Failure).error.message.contains("unclosed"))
    }

    @Test
    fun extraEndFailsWithLineAndLenientClamps() {
        val strict = run("end", "ruby")
        assertTrue(strict is FormatResult.Failure)
        assertEquals(1, (strict as FormatResult.Failure).error.line)
        val lenient = ok(run("end", "ruby", FormatOptions(lenient = true)))
        assertEquals("end", lenient)
    }
}
