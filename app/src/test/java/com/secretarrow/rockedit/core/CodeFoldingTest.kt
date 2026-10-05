package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [CodeFolding] (v0.14.0): brace and indentation range
 * detection (outermost vs deepest selection), string/comment/multiline
 * immunity, silent handling of unbalanced braces, placeholder format and
 * indentation, ambiguity/region-contains/region-gone/too-many-folds/
 * too-large failure branches, archive statefulness (round trips, edits
 * between fold and unfold, identical siblings), and CRLF normalization.
 * Every test builds its own [CodeFolding] instance because the engine is
 * stateful by design.
 */
class CodeFoldingTest {
    private fun done(result: FoldResult): FoldResult.Done {
        assertTrue("expected Done, was $result", result is FoldResult.Done)
        return result as FoldResult.Done
    }

    private fun failureOf(result: FoldResult): FoldResult.Failure {
        assertTrue("expected Failure, was $result", result is FoldResult.Failure)
        return result as FoldResult.Failure
    }

    private fun folding(languageId: String = "kotlin"): CodeFolding = CodeFolding(languageId)

    private fun pythonBlocks(count: Int): String = (0 until count).joinToString("") { "def f$it():\n    pass\n" }

    // ------------------------------------------------------- brace folding

    @Test
    fun braceFoldAllUsesOutermostRangeOnly() {
        val f = folding()
        val result = done(f.foldAll(KOTLIN_NESTED))
        assertEquals("class A {\n⟦⋯ 7 ⟧", result.text)
        assertEquals(7, result.hiddenLines)
        assertEquals(1, f.activeFoldCount())
    }

    @Test
    fun braceComputeRangesReturnsOutermostOnly() {
        assertEquals(listOf(FoldRange(0, 7)), folding().computeRanges(KOTLIN_NESTED))
    }

    @Test
    fun braceFoldAtLineDeepestRegionStartingAtLine() {
        val f = folding()
        val result = done(f.foldAtLine(KOTLIN_NESTED, 1))
        assertEquals(2, result.hiddenLines)
        assertEquals(
            "class A {\n    fun a() {\n    ⟦⋯ 2 ⟧\n    fun b() {\n        y = 2\n    }\n}",
            result.text,
        )
    }

    @Test
    fun braceFoldAtLineDeepestRegionContainingLine() {
        val f = folding()
        val result = done(f.foldAtLine(KOTLIN_NESTED, 5))
        assertEquals(2, result.hiddenLines)
        assertTrue(result.text.contains("    fun b() {\n    ⟦⋯ 2 ⟧\n}"))
    }

    @Test
    fun braceFoldAtLinePrefersRegionStartingAtLine() {
        val f = folding()
        // Line 0 starts the outer region; the inner ones start later, so the
        // outermost range wins over any region merely containing line 0.
        assertEquals(7, done(f.foldAtLine(KOTLIN_NESTED, 0)).hiddenLines)
        // Line 1 starts the inner fun a() region: it wins over the outer.
        assertEquals(2, done(f.foldAtLine(KOTLIN_NESTED, 1)).hiddenLines)
    }

    @Test
    fun braceFoldUnfoldRoundTripRestoresExactText() {
        val f = folding()
        val folded = done(f.foldAll(KOTLIN_NESTED))
        val restored = done(f.unfoldAll(folded.text))
        assertEquals(KOTLIN_NESTED, restored.text)
        assertEquals(7, restored.hiddenLines)
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun braceInsideStringIsIgnored() {
        val text =
            """
            class A {
                val open = "{"
                val close = '}'
                val mixed = "a } b { c"
            }
            """.trimIndent()
        assertEquals(listOf(FoldRange(0, 4)), folding().computeRanges(text))
    }

    @Test
    fun braceInsideLineCommentIsIgnored() {
        val text =
            """
            class A {
                // } { }}} no braces here
                val x = 1
            }
            """.trimIndent()
        assertEquals(listOf(FoldRange(0, 3)), folding().computeRanges(text))
    }

    @Test
    fun braceInsideBlockCommentIsIgnored() {
        val text =
            """
            class A {
                /* } { */
                /* multi
                   }} {{
                 */
                val x = 1
            }
            """.trimIndent()
        assertEquals(listOf(FoldRange(0, 6)), folding().computeRanges(text))
    }

    @Test
    fun braceInsideMultilineStringIsIgnored() {
        val text =
            "class A {\n" +
                "    val s = \"\"\"\n" +
                "    {\n" +
                "    }\n" +
                "    \"\"\"\n" +
                "}"
        assertEquals(listOf(FoldRange(0, 5)), folding().computeRanges(text))
    }

    @Test
    fun braceInsideGoRawStringIsIgnored() {
        val f = folding("go")
        val text = "func a() {\n\ts := `\n    {\n    }\n`\n}"
        assertEquals(listOf(FoldRange(0, 5)), f.computeRanges(text))
    }

    @Test
    fun unclosedBraceIsIgnoredSilently() {
        val f = folding()
        val text = "class A {\n    fun a() {\n        x\n"
        assertTrue(f.computeRanges(text).isEmpty())
        assertTrue(failureOf(f.foldAll(text)).message.isNotBlank())
    }

    @Test
    fun unmatchedClosingBraceIsIgnoredSilently() {
        val f = folding()
        val text = "    }\n}\nval x = 1\n"
        assertTrue(f.computeRanges(text).isEmpty())
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun pairOnSameLineYieldsNoRange() {
        val f = folding()
        val text = "fun x() { doIt() }"
        assertTrue(f.computeRanges(text).isEmpty())
        assertTrue(failureOf(f.foldAll(text)).message.isNotBlank())
    }

    @Test
    fun closerAndOpenerOnSameLinePairSeparately() {
        val f = folding()
        val text =
            """
            if (a) {
                x = 1
            } else {
                y = 2
            }
            """.trimIndent()
        // [0,2] and [2,4] share line 2 ("} else {"), so only the outermost
        // (first) range survives computeRanges.
        assertEquals(listOf(FoldRange(0, 2)), f.computeRanges(text))
        val result = done(f.foldAtLine(text, 3))
        assertEquals(2, result.hiddenLines)
        assertEquals("if (a) {\n    x = 1\n} else {\n⟦⋯ 2 ⟧", result.text)
    }

    @Test
    fun foldAllTwiceOnFoldedTextIsNoFoldRange() {
        val f = folding()
        val folded = done(f.foldAll(KOTLIN_NESTED)).text
        // The closer is hidden inside the placeholder, so nothing pairs.
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAll(folded)).code)
    }

    // --------------------------------------------------- indentation folds

    @Test
    fun pythonFoldAllTwoBlocks() {
        val f = folding("python")
        val result = done(f.foldAll(PYTHON_TWO_DEFS))
        // Openers stay visible; only the indented bodies collapse.
        assertEquals("def a():\n⟦⋯ 2 ⟧\n\ndef b():\n⟦⋯ 3 ⟧\n", result.text)
        assertEquals(5, result.hiddenLines)
    }

    @Test
    fun pythonTrailingBlankLinesExcluded() {
        val f = folding("python")
        val text = "def a():\n    x = 1\n\n\n"
        val result = done(f.foldAll(text))
        assertEquals("def a():\n⟦⋯ 1 ⟧\n\n\n", result.text)
        assertEquals(1, result.hiddenLines)
    }

    @Test
    fun pythonBlankLineInsideBlockIncluded() {
        val f = folding("python")
        val text = "def a():\n    x = 1\n\n    y = 2\n"
        val result = done(f.foldAll(text))
        // The trailing empty element after the final newline survives the fold.
        assertEquals("def a():\n⟦⋯ 3 ⟧\n", result.text)
        assertEquals(3, result.hiddenLines)
    }

    @Test
    fun pythonBlankOnlyBodyHasNoRange() {
        val f = folding("python")
        val text = "def a():\n\n\n"
        assertTrue(f.computeRanges(text).isEmpty())
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAll(text)).code)
    }

    @Test
    fun pythonNestedBlockFoldsDeepest() {
        val f = folding("python")
        val text = "def a():\n    if x:\n        y = 1\n    z = 2\n"
        assertEquals(listOf(FoldRange(0, 3)), f.computeRanges(text))
        val inner = done(f.foldAtLine(text, 1))
        assertEquals(1, inner.hiddenLines)
        assertEquals("def a():\n    if x:\n    ⟦⋯ 1 ⟧\n    z = 2\n", inner.text)
        assertEquals(3, done(f.foldAtLine(text, 0)).hiddenLines)
    }

    @Test
    fun pythonCommentLineCountedByIndent() {
        val f = folding("python")
        val inside =
            """
            def a():
                x = 1
                # note
                y = 2
            """.trimIndent()
        assertEquals(listOf(FoldRange(0, 3)), f.computeRanges(inside))
        val closing =
            """
            def a():
                x = 1
            # top level comment
            z = 0
            """.trimIndent()
        assertEquals(listOf(FoldRange(0, 1)), f.computeRanges(closing))
    }

    // --------------------------------------------------- placeholder rules

    @Test
    fun placeholderIndentMatchesOpenerIndent() {
        val f = folding()
        val result = done(f.foldAtLine(KOTLIN_NESTED, 1))
        assertTrue(result.text.contains("\n    ⟦⋯ 2 ⟧\n"))
        assertEquals(2, CodeFolding.placeholderHiddenCount("    ⟦⋯ 2 ⟧"))
    }

    @Test
    fun placeholderHelpersAcceptAndReject() {
        assertTrue(CodeFolding.isPlaceholderLine("    ⟦⋯ 12 ⟧"))
        assertEquals(12, CodeFolding.placeholderHiddenCount("    ⟦⋯ 12 ⟧"))
        assertTrue(CodeFolding.isPlaceholderLine("⟦⋯ 1 ⟧"))
        assertEquals(1, CodeFolding.placeholderHiddenCount("⟦⋯ 1 ⟧"))
        assertTrue(CodeFolding.isPlaceholderLine("\t⟦⋯ 2 ⟧"))
        assertEquals(2, CodeFolding.placeholderHiddenCount("\t⟦⋯ 2 ⟧"))
        assertEquals(0, CodeFolding.placeholderHiddenCount("⟦⋯ 0 ⟧"))
        assertEquals(7, CodeFolding.placeholderHiddenCount("⟦⋯ 07 ⟧"))
        assertEquals(Int.MAX_VALUE, CodeFolding.placeholderHiddenCount("⟦⋯ 2147483647 ⟧"))
        assertNull(CodeFolding.placeholderHiddenCount("⟦⋯ ⟧"))
        assertNull(CodeFolding.placeholderHiddenCount("⟦⋯ x ⟧"))
        assertNull(CodeFolding.placeholderHiddenCount("⟦⋯ -1 ⟧"))
        assertNull(CodeFolding.placeholderHiddenCount("⟦⋯ 99999999999 ⟧"))
        assertNull(CodeFolding.placeholderHiddenCount("⟦⋯ 1 ⟧ trailing"))
        assertNull(CodeFolding.placeholderHiddenCount("x ⟦⋯ 1 ⟧"))
        assertNull(CodeFolding.placeholderHiddenCount("⟦⋯ 1"))
        assertNull(CodeFolding.placeholderHiddenCount(""))
        assertFalse(CodeFolding.isPlaceholderLine("val x = 1"))
    }

    @Test
    fun originalPlaceholderLookingTextIsAmbiguous() {
        val f = folding()
        val text = "class A {\n⟦⋯ 3 ⟧\n}"
        val all = failureOf(f.foldAll(text))
        assertEquals(FoldErrorCode.PLACEHOLDER_AMBIGUOUS, all.code)
        val atLine = failureOf(f.foldAtLine(text, 0))
        assertEquals(FoldErrorCode.PLACEHOLDER_AMBIGUOUS, atLine.code)
        assertTrue(atLine.message.contains("⟦⋯ 3 ⟧"))
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun foldingRegionContainingOwnPlaceholderRejected() {
        val f = folding()
        val text =
            """
            class A {
                fun a() {
                    x = 1
                }
            }
            """.trimIndent()
        val folded = done(f.foldAtLine(text, 1)).text
        assertEquals(1, f.activeFoldCount())
        // The inner "}" was folded away, so the outer class brace can no
        // longer be paired on the folded text: no region can start at line 0.
        val atLine = failureOf(f.foldAtLine(folded, 0))
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, atLine.code)
        val all = failureOf(f.foldAll(folded))
        assertEquals(FoldErrorCode.REGION_CONTAINS_PLACEHOLDER, all.code)
        assertEquals(1, f.activeFoldCount())
    }

    // -------------------------------------------------------- unfold rules

    @Test
    fun unfoldAtLineOnPlainLineIsNotAPlaceholder() {
        val f = folding()
        val text = "class A {\n    x = 1\n}"
        assertEquals(FoldErrorCode.NOT_A_PLACEHOLDER, failureOf(f.unfoldAtLine(text, 0)).code)
        assertEquals(FoldErrorCode.NOT_A_PLACEHOLDER, failureOf(f.unfoldAtLine(text, 1)).code)
        assertEquals(FoldErrorCode.NOT_A_PLACEHOLDER, failureOf(f.unfoldAtLine(text, -1)).code)
        assertEquals(FoldErrorCode.NOT_A_PLACEHOLDER, failureOf(f.unfoldAtLine(text, 99)).code)
    }

    @Test
    fun unfoldAtLineRestoresExactBody() {
        val f = folding("python")
        val text = "def a():\n    x = 1\n    y = 2"
        val folded = done(f.foldAll(text)).text
        assertEquals("def a():\n⟦⋯ 2 ⟧", folded)
        val restored = done(f.unfoldAtLine(folded, 1))
        assertEquals(text, restored.text)
        assertEquals(2, restored.hiddenLines)
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun regionGoneWhenTextChanged() {
        val f = folding("python")
        val folded = done(f.foldAll("def a():\n    x = 1\n    y = 2")).text
        val edited = folded.replace("⟦⋯ 2 ⟧", "⟦⋯ 9 ⟧")
        val result = failureOf(f.unfoldAtLine(edited, 1))
        assertEquals(FoldErrorCode.REGION_GONE, result.code)
        // The stale archive entry is pruned so the state cannot leak.
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun regionGoneWhenStateLost() {
        val folder = folding("python")
        val folded = done(folder.foldAll("def a():\n    x = 1\n    y = 2")).text
        val fresh = CodeFolding("python")
        assertEquals(FoldErrorCode.REGION_GONE, failureOf(fresh.unfoldAtLine(folded, 1)).code)
        // unfoldAll never fails: the foreign placeholder is left as-is.
        val untouched = done(fresh.unfoldAll(folded))
        assertEquals(folded, untouched.text)
        assertEquals(0, untouched.hiddenLines)
    }

    @Test
    fun unfoldAllSilentlyDropsMissingPlaceholders() {
        val f = folding("python")
        val folded = done(f.foldAll("def a():\n    x = 1\n    y = 2")).text
        val edited = "def a():\ngone\n"
        val result = done(f.unfoldAll(edited))
        assertEquals("def a():\ngone\n", result.text)
        assertEquals(0, result.hiddenLines)
        assertEquals(0, f.activeFoldCount())
    }

    // ------------------------------------------------------ failure limits

    @Test
    fun tooManyFoldsOn257Ranges() {
        val f = folding("python")
        val text = pythonBlocks(257)
        val result = failureOf(f.foldAll(text))
        assertEquals(FoldErrorCode.TOO_MANY_FOLDS, result.code)
        assertTrue("message must mention 257: ${result.message}", result.message.contains("257"))
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun tooManyFoldsBoundary256Accepted() {
        val f = folding("python")
        val text = pythonBlocks(256)
        val result = done(f.foldAll(text))
        assertEquals(256, result.hiddenLines)
        assertEquals(256, f.activeFoldCount())
        assertEquals(text, done(f.unfoldAll(result.text)).text)
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun tooManyFoldsAcrossOperations() {
        val f = folding("python")
        done(f.foldAll(pythonBlocks(256)))
        val result = failureOf(f.foldAll("class X {\n    y = 1\n}"))
        assertEquals(FoldErrorCode.TOO_MANY_FOLDS, result.code)
        assertTrue("message must mention 257: ${result.message}", result.message.contains("257"))
        assertEquals(256, f.activeFoldCount())
    }

    @Test
    fun inputTooLargeRejected() {
        val f = folding("python")
        val text = "x".repeat(CodeFolding.MAX_TEXT_CHARS + 1)
        val all = failureOf(f.foldAll(text))
        assertEquals(FoldErrorCode.INPUT_TOO_LARGE, all.code)
        assertTrue(all.message.contains("1000001"))
        assertEquals(FoldErrorCode.INPUT_TOO_LARGE, failureOf(f.foldAtLine(text, 0)).code)
        assertEquals(FoldErrorCode.INPUT_TOO_LARGE, failureOf(f.unfoldAll(text)).code)
        assertEquals(FoldErrorCode.INPUT_TOO_LARGE, failureOf(f.unfoldAtLine(text, 0)).code)
        assertTrue(f.computeRanges(text).isEmpty())
    }

    @Test
    fun inputAtExactLimitAccepted() {
        val f = folding("python")
        val sb = StringBuilder("def a():")
        while (sb.length + 6 <= CodeFolding.MAX_TEXT_CHARS) sb.append("\n    x")
        sb.append(" ".repeat(CodeFolding.MAX_TEXT_CHARS - sb.length))
        val text = sb.toString()
        assertEquals(CodeFolding.MAX_TEXT_CHARS, text.length)
        val result = done(f.foldAll(text))
        assertTrue(result.hiddenLines > 0)
    }

    // ----------------------------------------------------- degenerate text

    @Test
    fun emptyTextHasNoRanges() {
        val f = folding()
        assertTrue(f.computeRanges("").isEmpty())
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAll("")).code)
        val restored = done(f.unfoldAll(""))
        assertEquals("", restored.text)
        assertEquals(0, restored.hiddenLines)
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAtLine("", 0)).code)
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun singleLineTextHasNoRanges() {
        val f = folding("python")
        val text = "x = 1"
        assertTrue(f.computeRanges(text).isEmpty())
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAll(text)).code)
        assertEquals(text, done(f.unfoldAll(text)).text)
    }

    @Test
    fun foldAtLineOutOfRangeIsNoFoldRange() {
        val f = folding()
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAtLine(KOTLIN_NESTED, -1)).code)
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAtLine(KOTLIN_NESTED, 8)).code)
    }

    @Test
    fun foldAtLineWithoutRegionIsNoFoldRange() {
        val f = folding("python")
        val text = "x = 1\ny = 2\n"
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAtLine(text, 0)).code)
        assertEquals(FoldErrorCode.NO_FOLD_RANGE, failureOf(f.foldAtLine(text, 1)).code)
    }

    // ------------------------------------------------- statefulness across

    @Test
    fun unfoldAllRestoresTwoSeparateFolds() {
        val f = folding("python")
        val first = done(f.foldAtLine(PYTHON_TWO_DEFS, 0))
        assertEquals(1, f.activeFoldCount())
        // Line 3 of the folded text is the "def b():" opener (openers stay
        // visible; the blank separator sits at line 2).
        val second = done(f.foldAtLine(first.text, 3))
        assertEquals(2, f.activeFoldCount())
        assertEquals(3, second.hiddenLines)
        val restored = done(f.unfoldAll(second.text))
        assertEquals(PYTHON_TWO_DEFS, restored.text)
        assertEquals(5, restored.hiddenLines)
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun unfoldAllIsIdempotentOnPlainText() {
        val f = folding()
        val text = "a = 1\nb = 2\n"
        val once = done(f.unfoldAll(text))
        assertEquals(text, once.text)
        assertEquals(0, once.hiddenLines)
        assertEquals(text, done(f.unfoldAll(once.text)).text)
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun hiddenLinesMatchesFoldedLineCount() {
        val f = folding()
        val originalLines = KOTLIN_NESTED.split('\n').size
        val folded = done(f.foldAll(KOTLIN_NESTED))
        assertEquals(originalLines - folded.text.split('\n').size + 1, folded.hiddenLines)
    }

    @Test
    fun crlfIsNormalizedToLf() {
        val f = folding()
        val text = "class A {\r\n    fun a() {\r\n        x\r\n    }\r\n}"
        val folded = done(f.foldAll(text))
        assertEquals("class A {\n⟦⋯ 4 ⟧", folded.text)
        assertEquals(4, folded.hiddenLines)
        assertFalse(folded.text.contains('\r'))
        val restored = done(f.unfoldAll(folded.text))
        assertEquals(text.replace("\r\n", "\n"), restored.text)
    }

    @Test
    fun activeFoldCountTracksArchive() {
        val f = folding("python")
        assertEquals(0, f.activeFoldCount())
        val first = done(f.foldAtLine(PYTHON_TWO_DEFS, 0))
        assertEquals(1, f.activeFoldCount())
        done(f.foldAtLine(first.text, 2))
        assertEquals(2, f.activeFoldCount())
        done(f.unfoldAll(first.text))
        assertEquals(0, f.activeFoldCount())
    }

    @Test
    fun restoreWorksAfterEditsOutsideFold() {
        val f = folding("python")
        val folded = done(f.foldAll("def a():\n    x = 1\n    y = 2\n")).text
        val edited = "import os\n$folded"
        val restored = done(f.unfoldAll(edited))
        assertEquals("import os\ndef a():\n    x = 1\n    y = 2\n", restored.text)
        assertEquals(2, restored.hiddenLines)
    }

    @Test
    fun identicalSiblingPlaceholdersRoundTrip() {
        val f = folding("python")
        // Both regions hide 1 line, so the second placeholder is
        // disambiguated with a #ordinal; the archive keys stay unique and
        // unfolding restores the exact body of each fold.
        val text = "def a():\n    x = 1\n\ndef b():\n    y = 2\n"
        val folded = done(f.foldAll(text))
        assertEquals("def a():\n⟦⋯ 1#1 ⟧\n\ndef b():\n⟦⋯ 1 ⟧\n", folded.text)
        assertEquals(2, f.activeFoldCount())
        assertEquals(text, done(f.unfoldAll(folded.text)).text)
    }

    @Test
    fun placeholderHelpersAcceptOrdinalForm() {
        assertTrue(CodeFolding.isPlaceholderLine("⟦⋯ 3#12 ⟧"))
        assertEquals(3, CodeFolding.placeholderHiddenCount("⟦⋯ 3#12 ⟧"))
        assertTrue(CodeFolding.isPlaceholderLine("    ⟦⋯ 3#1 ⟧"))
        assertFalse(CodeFolding.isPlaceholderLine("⟦⋯ 3# ⟧"))
        assertFalse(CodeFolding.isPlaceholderLine("⟦⋯ 3#x ⟧"))
        assertFalse(CodeFolding.isPlaceholderLine("⟦⋯ 3#1"))
    }

    @Test
    fun unknownLanguageUsesBraceFallback() {
        val f = folding("someconfig")
        assertEquals("someconfig", f.languageId)
        val text = "if x {\n    y = 1\n}\n"
        assertEquals(listOf(FoldRange(0, 2)), f.computeRanges(text))
        assertEquals(2, done(f.foldAll(text)).hiddenLines)
    }

    @Test
    fun languageIdIsPreservedAndNormalizedInternally() {
        val f = folding("Python")
        assertEquals("Python", f.languageId)
        assertEquals(
            listOf(FoldRange(0, 1)),
            f.computeRanges("def a():\n    x\n"),
        )
    }

    // ------------------------------------------------------------ fixtures

    private companion object {
        val KOTLIN_NESTED =
            """
            class A {
                fun a() {
                    x = 1
                }
                fun b() {
                    y = 2
                }
            }
            """.trimIndent()

        val PYTHON_TWO_DEFS =
            """
            def a():
                x = 1
                y = 2

            def b():
                z = 3
                w = 4
                v = 5
            """.trimIndent() + "\n"
    }
}
