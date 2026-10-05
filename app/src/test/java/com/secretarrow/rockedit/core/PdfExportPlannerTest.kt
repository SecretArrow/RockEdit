package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [PdfExportPlanner] (v0.14.0): preprocessing (CRLF/CR/
 * tab), the plan() no-preprocess contract, TOKEN_MISMATCH guards, the
 * TOO_LARGE line cap and its exact boundary, coloring from
 * [SyntaxTokenizer] tokens against the registered Kotlin language, wrap
 * chunking with tokens crossing chunk boundaries, line-number and color
 * switches, page theory (44/45/89/90 lines), UTF-16 emoji offsets, and the
 * global invariant "concatenated segments == printed line text".
 */
class PdfExportPlannerTest {
    // Named kotlinLanguage (not `kotlin`) so the kotlin.* package namespace is
    // never shadowed inside this class.
    private val kotlinLanguage: SyntaxLanguage =
        requireNotNull(SyntaxRegistry.languageById("kotlin")) {
            "kotlin must be registered in SyntaxRegistry"
        }

    private fun tokenize(text: String): List<SyntaxToken> =
        SyntaxTokenizer.tokenize(text, kotlinLanguage)

    /** Runs the documented pipeline for one call: tokenize(pre) then plan(pre, tokens). */
    private fun planTok(
        text: String,
        options: PdfExportOptions = PdfExportOptions(),
    ): PdfPlanResult = PdfExportPlanner.plan(text, tokenize(text), options)

    private fun successOf(result: PdfPlanResult): PdfPlanResult.Success {
        assertTrue("expected Success, was $result", result is PdfPlanResult.Success)
        return result as PdfPlanResult.Success
    }

    private fun failureOf(result: PdfPlanResult): PdfPlanResult.Failure {
        assertTrue("expected Failure, was $result", result is PdfPlanResult.Failure)
        return result as PdfPlanResult.Failure
    }

    private fun expectRejected(build: () -> Unit) {
        try {
            build()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Expected: programmatic misuse must fail fast.
        }
    }

    private fun flatLines(plan: PdfPlan): List<PdfLine> = plan.pages.flatMap { it.lines }

    private fun textOf(line: PdfLine): String = line.segments.joinToString("") { it.text }

    private fun rolesOf(line: PdfLine): List<SyntaxTokenType?> = line.segments.map { it.role }

    private fun segmentTexts(line: PdfLine): List<String> = line.segments.map { it.text }

    /**
     * Replays the documented pagination rules independently and asserts the
     * full plan matches: line count, per-line number pattern, source line
     * count, and the concatenated-segments == printed-line-text invariant.
     */
    private fun assertInvariants(
        plan: PdfPlan,
        text: String,
        options: PdfExportOptions,
    ) {
        val expected = ArrayList<Pair<Int?, String>>()
        var lineNo = 0
        for (raw in text.split('\n')) {
            lineNo++
            if (raw.isEmpty()) {
                expected.add(if (options.lineNumbers) lineNo else null to "")
                continue
            }
            var start = 0
            var first = true
            do {
                val end = minOf(start + options.charsPerLine, raw.length)
                val number = if (options.lineNumbers && first) lineNo else null
                expected.add(number to raw.substring(start, end))
                first = false
                start = end
            } while (start < raw.length)
        }
        val flat = flatLines(plan)
        assertEquals("flat line count", expected.size, flat.size)
        for (i in expected.indices) {
            assertEquals("line $i number", expected[i].first, flat[i].number)
            assertTrue("line $i has segments", flat[i].segments.isNotEmpty())
            assertEquals("line $i concat", expected[i].second, textOf(flat[i]))
        }
        assertEquals("sourceLineCount", text.split('\n').size, plan.sourceLineCount)
    }

    // ------------------------------------------------------------ preprocess

    @Test
    fun preprocessNormalizesCrLfAndLoneCr() {
        assertEquals("a\nb\nc\nd", PdfExportPlanner.preprocess("a\r\nb\rc\nd"))
    }

    @Test
    fun preprocessExpandsTabsToFourSpaces() {
        assertEquals("    ca    t", PdfExportPlanner.preprocess("\tca\tt"))
    }

    @Test
    fun preprocessKeepsCleanTextIdentical() {
        val clean = "fun main() {\n    return\n}\n"
        assertEquals(clean, PdfExportPlanner.preprocess(clean))
    }

    @Test
    fun preprocessKeepsEmptyTextEmpty() {
        assertEquals("", PdfExportPlanner.preprocess(""))
    }

    // ------------------------------------------------------------ plan basics

    @Test
    fun emptyTextYieldsSinglePageSingleEmptyLine() {
        val plan = successOf(PdfExportPlanner.plan("", emptyList(), PdfExportOptions())).plan
        assertEquals(1, plan.pages.size)
        assertEquals(1, plan.sourceLineCount)
        val line = plan.pages.single().lines.single()
        assertEquals(1, line.number)
        assertEquals(listOf(PdfSegment("", null)), line.segments)
    }

    @Test
    fun shortPlainLineIsSinglePlainSegment() {
        val result = PdfExportPlanner.plan("hello world", emptyList(), PdfExportOptions())
        val line = flatLines(successOf(result).plan).single()
        assertEquals(1, line.number)
        assertEquals(listOf(PdfSegment("hello world", null)), line.segments)
    }

    @Test
    fun multilineLinesAreNumberedOneBased() {
        val plan = successOf(PdfExportPlanner.plan("a\nb\nc", emptyList(), PdfExportOptions())).plan
        assertEquals(listOf(1, 2, 3), flatLines(plan).map { it.number })
    }

    @Test
    fun trailingNewlineCountsEmptyLastLine() {
        val plan = successOf(PdfExportPlanner.plan("a\n", emptyList(), PdfExportOptions())).plan
        assertEquals(2, plan.sourceLineCount)
        val lines = flatLines(plan)
        assertEquals(listOf(1, 2), lines.map { it.number })
        assertEquals("a", textOf(lines[0]))
        assertEquals("", textOf(lines[1]))
    }

    @Test
    fun planDoesNotPreprocessByContract() {
        // plan() never normalizes: a caller that skips preprocess() gets a
        // single source line whose text still contains the raw \r.
        val plan = successOf(PdfExportPlanner.plan("a\rb", emptyList(), PdfExportOptions())).plan
        assertEquals(1, plan.sourceLineCount)
        assertEquals("a\rb", textOf(flatLines(plan).single()))
    }

    // ------------------------------------------------- coloring (tokenizer)

    @Test
    fun keywordSegmentGetsKeywordRole() {
        val plan = successOf(planTok("val x")).plan
        val line = flatLines(plan).single()
        assertEquals(
            listOf(PdfSegment("val", SyntaxTokenType.KEYWORD), PdfSegment(" x", null)),
            line.segments,
        )
    }

    @Test
    fun stringSegmentGetsStringRole() {
        val plan = successOf(planTok("val s = \"hi\"")).plan
        val line = flatLines(plan).single()
        assertEquals(
            listOf(
                PdfSegment("val", SyntaxTokenType.KEYWORD),
                PdfSegment(" s = ", null),
                PdfSegment("\"hi\"", SyntaxTokenType.STRING),
            ),
            line.segments,
        )
    }

    @Test
    fun lineCommentSegmentGetsCommentRole() {
        val plan = successOf(planTok("// header")).plan
        val line = flatLines(plan).single()
        assertEquals(listOf(PdfSegment("// header", SyntaxTokenType.COMMENT)), line.segments)
    }

    @Test
    fun blockCommentSegmentGetsCommentRole() {
        val plan = successOf(planTok("/* box */ val")).plan
        val line = flatLines(plan).single()
        assertEquals(
            listOf(
                PdfSegment("/* box */", SyntaxTokenType.COMMENT),
                PdfSegment(" ", null),
                PdfSegment("val", SyntaxTokenType.KEYWORD),
            ),
            line.segments,
        )
    }

    @Test
    fun numberSegmentGetsNumberRole() {
        val plan = successOf(planTok("x = 42")).plan
        val line = flatLines(plan).single()
        assertEquals(
            listOf(PdfSegment("x = ", null), PdfSegment("42", SyntaxTokenType.NUMBER)),
            line.segments,
        )
    }

    @Test
    fun multipleTokensInOneLineAreOrdered() {
        val plan = successOf(planTok("val n = 12 // ok")).plan
        val line = flatLines(plan).single()
        assertEquals(
            listOf(
                PdfSegment("val", SyntaxTokenType.KEYWORD),
                PdfSegment(" n = ", null),
                PdfSegment("12", SyntaxTokenType.NUMBER),
                PdfSegment(" ", null),
                PdfSegment("// ok", SyntaxTokenType.COMMENT),
            ),
            line.segments,
        )
    }

    @Test
    fun rolesStayInTheirOwnLines() {
        val plan = successOf(planTok("val a\n// done")).plan
        val lines = flatLines(plan)
        assertEquals(listOf(SyntaxTokenType.KEYWORD, null), rolesOf(lines[0]))
        assertEquals(listOf(SyntaxTokenType.COMMENT), rolesOf(lines[1]))
    }

    @Test
    fun numberWordIsOneTokenWithoutPlainGap() {
        // Digits followed by letters scan as a single NUMBER token, so the
        // line is one colored segment with no plain filler between.
        val plan = successOf(planTok("1ab")).plan
        val line = flatLines(plan).single()
        assertEquals(listOf(PdfSegment("1ab", SyntaxTokenType.NUMBER)), line.segments)
    }

    // ---------------------------------------------------------------- wrap

    @Test
    fun longPlainLineWrapsAtCharsPerLine() {
        val options = PdfExportOptions(charsPerLine = 88)
        val plan = successOf(PdfExportPlanner.plan("x".repeat(200), emptyList(), options)).plan
        val lines = flatLines(plan)
        assertEquals(3, lines.size)
        assertEquals(listOf(1, null, null), lines.map { it.number })
        assertEquals(listOf(88, 88, 24), lines.map { textOf(it).length })
        lines.forEach { line ->
            assertEquals(listOf<PdfSegment>(PdfSegment(textOf(line), null)), line.segments)
        }
    }

    @Test
    fun wrappedContinuationLinesHaveNullNumber() {
        val options = PdfExportOptions(charsPerLine = 4)
        val plan = successOf(PdfExportPlanner.plan("0123456789", emptyList(), options)).plan
        val lines = flatLines(plan)
        assertEquals(listOf(1, null, null), lines.map { it.number })
        assertEquals(listOf("0123", "4567", "89"), lines.map(::textOf))
    }

    @Test
    fun tokenCrossingWrapIsSplitAcrossChunks() {
        // The string literal [8, 26) spans all three chunks of 12 columns.
        val options = PdfExportOptions(charsPerLine = 12)
        val plan = successOf(planTok("val s = \"0123456789ABCDEF\"", options)).plan
        val lines = flatLines(plan)
        assertEquals(3, lines.size)
        assertEquals(
            listOf(
                PdfSegment("val", SyntaxTokenType.KEYWORD),
                PdfSegment(" s = ", null),
                PdfSegment("\"012", SyntaxTokenType.STRING),
            ),
            lines[0].segments,
        )
        assertEquals(listOf(PdfSegment("3456789ABCDE", SyntaxTokenType.STRING)), lines[1].segments)
        assertEquals(listOf(PdfSegment("F\"", SyntaxTokenType.STRING)), lines[2].segments)
        assertEquals(listOf(1, null, null), lines.map { it.number })
    }

    @Test
    fun manualTokenCrossingWrapSplitsWithPlainTail() {
        val options = PdfExportOptions(charsPerLine = 10)
        val tokens = listOf(SyntaxToken(SyntaxTokenType.KEYWORD, 3, 15))
        val plan = successOf(PdfExportPlanner.plan("abcdefghijklmnop", tokens, options)).plan
        val lines = flatLines(plan)
        assertEquals(2, lines.size)
        assertEquals(
            listOf(PdfSegment("abc", null), PdfSegment("defghij", SyntaxTokenType.KEYWORD)),
            lines[0].segments,
        )
        assertEquals(
            listOf(PdfSegment("klmno", SyntaxTokenType.KEYWORD), PdfSegment("p", null)),
            lines[1].segments,
        )
    }

    @Test
    fun wrapRespectsCustomCharsPerLine() {
        val options = PdfExportOptions(charsPerLine = 3)
        val plan = successOf(PdfExportPlanner.plan("abcdef", emptyList(), options)).plan
        val lines = flatLines(plan)
        assertEquals(listOf("abc", "def"), lines.map(::textOf))
        assertEquals(listOf(1, null), lines.map { it.number })
    }

    // ---------------------------------------------------------- line numbers

    @Test
    fun lineNumbersFalseNullsEveryNumber() {
        val options = PdfExportOptions(lineNumbers = false)
        val plan = successOf(PdfExportPlanner.plan("a\nbb\nccc", emptyList(), options)).plan
        assertEquals(listOf<Int?>(null, null, null), flatLines(plan).map { it.number })
        assertEquals(listOf("a", "bb", "ccc"), flatLines(plan).map(::textOf))
    }

    @Test
    fun lineNumbersFalseKeepsPaginationInvariants() {
        val options = PdfExportOptions(lineNumbers = false, charsPerLine = 5)
        val plan = successOf(PdfExportPlanner.plan("aaaaaaa\nb\n", emptyList(), options)).plan
        assertInvariants(plan, "aaaaaaa\nb\n", options)
    }

    @Test
    fun lineNumbersFalseStillColors() {
        val options = PdfExportOptions(lineNumbers = false)
        val plan = successOf(planTok("val n", options)).plan
        val line = flatLines(plan).single()
        assertNull(line.number)
        assertEquals(
            listOf(PdfSegment("val", SyntaxTokenType.KEYWORD), PdfSegment(" n", null)),
            line.segments,
        )
    }

    // ---------------------------------------------------------------- color

    @Test
    fun coloredFalseStripsAllRoles() {
        val options = PdfExportOptions(colored = false)
        val plan = successOf(planTok("val n = 12 // ok", options)).plan
        val line = flatLines(plan).single()
        assertEquals(listOf<PdfSegment>(PdfSegment("val n = 12 // ok", null)), line.segments)
        assertTrue(rolesOf(line).all { it == null })
    }

    @Test
    fun coloredFalseStillWrapsLongLines() {
        val options = PdfExportOptions(colored = false, charsPerLine = 88)
        val plan = successOf(PdfExportPlanner.plan("x".repeat(200), emptyList(), options)).plan
        val lines = flatLines(plan)
        assertEquals(3, lines.size)
        lines.forEach { line ->
            assertEquals(listOf<PdfSegment>(PdfSegment(textOf(line), null)), line.segments)
        }
    }

    // ---------------------------------------------------------------- pages

    @Test
    fun exactlyLinesPerPageIsOnePage() {
        val text = (1..PdfExportPlanner.LINES_PER_PAGE).joinToString("\n") { "l$it" }
        val plan = successOf(planTok(text)).plan
        assertEquals(1, plan.pages.size)
        assertEquals(PdfExportPlanner.LINES_PER_PAGE, plan.pages.single().lines.size)
    }

    @Test
    fun oneLineOverFillsSecondPage() {
        val text = (1..(PdfExportPlanner.LINES_PER_PAGE + 1)).joinToString("\n") { "l$it" }
        val plan = successOf(planTok(text)).plan
        assertEquals(2, plan.pages.size)
        assertEquals(PdfExportPlanner.LINES_PER_PAGE, plan.pages[0].lines.size)
        assertEquals(1, plan.pages[1].lines.size)
    }

    @Test
    fun pageChunkingFollowsLinesPerPage() {
        val text = (1..89).joinToString("\n") { "l$it" }
        val plan = successOf(planTok(text)).plan
        assertEquals(3, plan.pages.size)
        assertEquals(listOf(44, 44, 1), plan.pages.map { it.lines.size })
    }

    @Test
    fun lastPartialPageHoldsRemainder() {
        val text = (1..90).joinToString("\n") { "l$it" }
        val plan = successOf(planTok(text)).plan
        assertEquals(listOf(44, 44, 2), plan.pages.map { it.lines.size })
        assertEquals(90, plan.sourceLineCount)
    }

    // ------------------------------------------------------------ size caps

    @Test
    fun overLineLimitFailsWithActualAndLimit() {
        val text = "\n".repeat(PdfExportPlanner.MAX_EXPORT_LINES)
        val failure = failureOf(PdfExportPlanner.plan(text, emptyList(), PdfExportOptions()))
        assertEquals(PdfExportErrorCode.TOO_LARGE, failure.code)
        assertEquals(PdfExportPlanner.MAX_EXPORT_LINES + 1, failure.actualLines)
        assertTrue(failure.message.contains("100001"))
        assertTrue(failure.message.contains("100000"))
    }

    @Test
    fun atLineLimitSucceeds() {
        val text = "\n".repeat(PdfExportPlanner.MAX_EXPORT_LINES - 1)
        val plan = successOf(PdfExportPlanner.plan(text, emptyList(), PdfExportOptions())).plan
        assertEquals(PdfExportPlanner.MAX_EXPORT_LINES, plan.sourceLineCount)
        val expectedPages =
            (PdfExportPlanner.MAX_EXPORT_LINES + PdfExportPlanner.LINES_PER_PAGE - 1) /
                PdfExportPlanner.LINES_PER_PAGE
        assertEquals(expectedPages, plan.pages.size)
        assertEquals(32, plan.pages.last().lines.size)
    }

    // -------------------------------------------------------- token guards

    @Test
    fun tokenEndBeyondTextFails() {
        val failure =
            failureOf(
                PdfExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.KEYWORD, 0, 4)),
                    PdfExportOptions(),
                ),
            )
        assertEquals(PdfExportErrorCode.TOKEN_MISMATCH, failure.code)
        assertTrue(failure.message.contains("token[0]"))
        assertTrue(failure.message.contains("[0, 4)"))
        assertTrue(failure.message.contains("3"))
    }

    @Test
    fun negativeStartAndInvertedRangeFail() {
        val options = PdfExportOptions()
        val negative =
            failureOf(
                PdfExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.STRING, -1, 1)),
                    options,
                ),
            )
        assertEquals(PdfExportErrorCode.TOKEN_MISMATCH, negative.code)
        val inverted =
            failureOf(
                PdfExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.STRING, 2, 1)),
                    options,
                ),
            )
        assertEquals(PdfExportErrorCode.TOKEN_MISMATCH, inverted.code)
    }

    @Test
    fun tokenIndexIsReportedInMismatch() {
        val tokens =
            listOf(
                SyntaxToken(SyntaxTokenType.KEYWORD, 0, 3),
                SyntaxToken(SyntaxTokenType.NUMBER, 4, 9),
            )
        val failure = failureOf(PdfExportPlanner.plan("val x", tokens, PdfExportOptions()))
        assertEquals(PdfExportErrorCode.TOKEN_MISMATCH, failure.code)
        assertTrue(failure.message.contains("token[1]"))
    }

    @Test
    fun hugeEndNeverCrashesPlanner() {
        val failure =
            failureOf(
                PdfExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.COMMENT, 1, Int.MAX_VALUE)),
                    PdfExportOptions(),
                ),
            )
        assertEquals(PdfExportErrorCode.TOKEN_MISMATCH, failure.code)
    }

    // --------------------------------------------------------- options guard

    @Test
    fun zeroCharsPerLineIsRejected() {
        expectRejected {
            PdfExportPlanner.plan("abc", emptyList(), PdfExportOptions(charsPerLine = 0))
        }
    }

    @Test
    fun negativeCharsPerLineIsRejected() {
        expectRejected {
            PdfExportPlanner.plan("abc", emptyList(), PdfExportOptions(charsPerLine = -1))
        }
    }

    // -------------------------------------------------------- unicode offsets

    @Test
    fun emojiDoesNotShiftTokenSegments() {
        // Offsets are UTF-16 code units: "x " (0..1), emoji (2..3), " val"
        // (4..7). The keyword must still be found and the segments must not
        // shift by the astral-plane character.
        val plan = successOf(planTok("x \uD83D\uDE80 val")).plan
        val line = flatLines(plan).single()
        assertEquals(
            listOf(PdfSegment("x \uD83D\uDE80 ", null), PdfSegment("val", SyntaxTokenType.KEYWORD)),
            line.segments,
        )
    }

    @Test
    fun emojiAcrossWrapBoundaryKeepsConcatenation() {
        // "a🚀b" is 4 UTF-16 units; a 3-unit cut splits after the low
        // surrogate, which is exactly how the renderer will measure columns.
        val options = PdfExportOptions(charsPerLine = 3)
        val text = "a\uD83D\uDE80b"
        val plan = successOf(PdfExportPlanner.plan(text, emptyList(), options)).plan
        val lines = flatLines(plan)
        assertEquals(listOf(1, null), lines.map { it.number })
        assertEquals(listOf("a\uD83D\uDE80", "b"), lines.map(::textOf))
        assertInvariants(plan, text, options)
    }

    // -------------------------------------------------------------- CRLF flow

    @Test
    fun crlfOffsetsMatchAfterPreprocessAndTokenize() {
        val raw = "val a = 1\r\n// done"
        val pre = PdfExportPlanner.preprocess(raw)
        val plan = successOf(planTok(pre)).plan
        assertEquals(2, plan.sourceLineCount)
        val lines = flatLines(plan)
        assertEquals(
            listOf(
                PdfSegment("val", SyntaxTokenType.KEYWORD),
                PdfSegment(" a = ", null),
                PdfSegment("1", SyntaxTokenType.NUMBER),
            ),
            lines[0].segments,
        )
        assertEquals(listOf(PdfSegment("// done", SyntaxTokenType.COMMENT)), lines[1].segments)
        assertEquals(listOf(1, 2), lines.map { it.number })
    }

    // -------------------------------------------------------------- invariants

    @Test
    fun invariantsHoldOnRepresentativeDocument() {
        // Short line, a line wrapping with a token crossing the boundary,
        // empty lines, and a trailing comment line — the whole grid must
        // replay exactly through the documented rules.
        val text =
            "val header = 1\n" +
                "\"0123456789ABCDEF\" val\n" +
                "\n" +
                "42 // tail\n"
        val options = PdfExportOptions(charsPerLine = 12)
        val plan = successOf(planTok(text, options)).plan
        assertInvariants(plan, text, options)
        assertTrue(plan.pages.size >= 1)
    }

    @Test
    fun emptyLinesKeepGridRowWithEmptyPlainSegment() {
        val plan = successOf(PdfExportPlanner.plan("a\n\nb", emptyList(), PdfExportOptions())).plan
        val lines = flatLines(plan)
        assertEquals(listOf(1, 2, 3), lines.map { it.number })
        assertEquals(listOf(PdfSegment("", null)), lines[1].segments)
        assertEquals("", segmentTexts(lines[1]).single())
    }

    // ------------------------------------------------------- default plumbing

    @Test
    fun defaultOptionsUsePrintLayoutGeometry() {
        assertEquals(88, PdfExportOptions().charsPerLine)
        assertEquals(PrintLayout.DEFAULT_CHARS_PER_LINE, PdfExportOptions().charsPerLine)
        assertEquals(100_000, PdfExportPlanner.MAX_EXPORT_LINES)
        assertEquals(44, PdfExportPlanner.LINES_PER_PAGE)
        assertNotNull(PdfExportOptions().lineNumbers)
        assertTrue(PdfExportOptions().lineNumbers)
        assertTrue(PdfExportOptions().colored)
    }
}
