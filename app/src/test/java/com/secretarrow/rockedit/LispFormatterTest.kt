package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for [LispFormatter] (Clarity, Michelson, Clojure,
 * Scheme, Lisp): paren depth, leading-closer dedent, strings, comments,
 * block comments, unbalanced forms, lenient mode, idempotency.
 */
class LispFormatterTest {

    private val fmt = com.secretarrow.rockedit.core.LispFormatter()

    private fun run(text: String, language: String, options: FormatOptions = FormatOptions()) =
        fmt.format(FormatRequest(text, language, options.copy(insertFinalNewline = false)))

    private fun ok(result: FormatResult): String {
        assertTrue("expected Success but was $result", result is FormatResult.Success)
        return (result as FormatResult.Success).formattedText
    }

    @Test
    fun clojureDefnIndentsByDepth() {
        val out = ok(run("(defn foo [x]\n(+ x 1))", "clojure"))
        assertEquals("(defn foo [x]\n    (+ x 1))", out)
    }

    @Test
    fun clarityPublicFunctionIndents() {
        val src = "(define-public (transfer (amount uint))\n(begin\n(print \"hi\")\n(ok true)\n)\n)"
        val out = ok(run(src, "clarity"))
        assertEquals(
            "(define-public (transfer (amount uint))\n" +
                "    (begin\n" +
                "        (print \"hi\")\n" +
                "        (ok true)\n" +
                "    )\n" +
                ")",
            out
        )
    }

    @Test
    fun michelsonScriptIndents() {
        val src = "(parameter unit)\n(storage unit)\n(code\n(PUSH unit (UNIT))\n)"
        val out = ok(run(src, "michelson"))
        assertEquals(
            "(parameter unit)\n(storage unit)\n(code\n    (PUSH unit (UNIT))\n)",
            out
        )
    }

    @Test
    fun parensInsideStringsIgnored() {
        val out = ok(run("(print \"(not a form)\")", "clojure"))
        assertEquals("(print \"(not a form)\")", out)
    }

    @Test
    fun lineCommentsIgnored() {
        val src = ";; ;; fake open (\n(defn f []\nnil)"
        val out = ok(run(src, "clojure"))
        assertEquals(";; ;; fake open (\n(defn f []\n    nil)", out)
    }

    @Test
    fun blockCommentInteriorsPreserved() {
        val src = "#| ( unclosed\nstill comment ) |#\n(defn f [] nil)"
        val out = ok(run(src, "clojure"))
        assertEquals(src, out)
    }

    @Test
    fun unclosedFormFailsAtEof() {
        val result = run("(defn foo [x]\n(+ x 1)", "clojure")
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertTrue(error.message.contains("unclosed"))
    }

    @Test
    fun extraCloserMidLineFails() {
        val result = run("(foo))", "clojure")
        assertTrue(result is FormatResult.Failure)
        assertEquals(1, (result as FormatResult.Failure).error.line)
    }

    @Test
    fun lenientClampsUnbalancedForms() {
        val out = ok(run("(foo))", "clojure", FormatOptions(lenient = true)))
        assertEquals("(foo))", out)
    }

    @Test
    fun blankInputIsSkipped() {
        assertTrue(run("", "clarity") is FormatResult.Skipped)
    }

    @Test
    fun formattingTwiceIsStable() {
        val src = "(define-data-var counter int 0)\n(define-public (inc)\n(begin\n(var-set counter (+ 1 (var-get counter)))\n(ok (var-get counter))\n)\n)"
        val once = ok(run(src, "clarity"))
        val twice = ok(run(once, "clarity"))
        assertEquals(once, twice)
    }
}
