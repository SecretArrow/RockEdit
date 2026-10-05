package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [ImageExportPlanner] (v0.16.0): preprocess parity with
 * [PdfExportPlanner] (CRLF/CR/tab), the EMPTY guard for blank text,
 * TOKEN_MISMATCH guards, the TOO_LARGE line cap with its exact boundary,
 * fail-safe option clamping (wrap columns / font / padding / maxLines), role
 * mapping from [SyntaxTokenizer] tokens against the registered Kotlin
 * language, adjacent-same-role segment merging, wrap chunking with tokens
 * crossing chunk boundaries, the repeated-source-number gutter decision,
 * title-line handling, palette selection, trailing-newline semantics, and
 * the global invariants "concatenated segments == printed line text" and
 * "no two adjacent segments share a role".
 */
class ImageExportPlannerTest {
    // Named kotlinLanguage (not `kotlin`) so the kotlin.* package namespace is
    // never shadowed inside this class.
    private val kotlinLanguage: SyntaxLanguage =
        requireNotNull(SyntaxRegistry.languageById("kotlin")) {
            "kotlin must be registered in SyntaxRegistry"
        }

    private fun tokenize(text: String): List<SyntaxToken> = SyntaxTokenizer.tokenize(text, kotlinLanguage)

    /** Runs the documented pipeline for one call: tokenize(pre) then plan(pre, tokens). */
    private fun planTok(
        text: String,
        options: ImageExportOptions = ImageExportOptions(),
    ): ImagePlanResult = ImageExportPlanner.plan(text, tokenize(text), options)

    private fun successOf(result: ImagePlanResult): ImagePlanResult.Success {
        assertTrue("expected Success, was $result", result is ImagePlanResult.Success)
        return result as ImagePlanResult.Success
    }

    private fun failureOf(result: ImagePlanResult): ImagePlanResult.Failure {
        assertTrue("expected Failure, was $result", result is ImagePlanResult.Failure)
        return result as ImagePlanResult.Failure
    }

    private fun linesOf(plan: ImagePlan): List<ImageLine> = plan.lines

    private fun textOf(line: ImageLine): String = line.segments.joinToString("") { it.text }

    private fun segmentTexts(line: ImageLine): List<String> = line.segments.map { it.text }

    private fun rolesOf(line: ImageLine): List<ColorRole> = line.segments.map { it.role }

    /**
     * Replays the documented rules independently and asserts the full plan
     * matches: line count (title row included), per-line number pattern with
     * the repeated-source-number decision, source line count, the
     * concatenated-segments == printed-line-text invariant, non-empty
     * segment lists, and the adjacent-roles-always-differ merge invariant.
     */
    private fun assertInvariants(
        plan: ImagePlan,
        text: String,
        options: ImageExportOptions,
    ) {
        val wrap =
            options.wrapColumns.coerceIn(
                ImageExportPlanner.MIN_WRAP_COLUMNS,
                ImageExportPlanner.MAX_WRAP_COLUMNS,
            )
        val expected = ArrayList<Pair<Int?, String>>()
        if (options.title.isNotBlank()) expected.add(null to options.title)
        var lineNo = 0
        for (raw in text.split('\n')) {
            lineNo++
            val number = if (options.lineNumbers) lineNo else null
            var start = 0
            do {
                val end = minOf(start + wrap, raw.length)
                expected.add(number to raw.substring(start, end))
                start = end
            } while (start < raw.length)
        }
        val lines = linesOf(plan)
        assertEquals("flat line count", expected.size, lines.size)
        for (i in expected.indices) {
            assertEquals("line $i number", expected[i].first, lines[i].number)
            assertTrue("line $i has segments", lines[i].segments.isNotEmpty())
            assertEquals("line $i concat", expected[i].second, textOf(lines[i]))
            val roles = rolesOf(lines[i])
            for (j in 1 until roles.size) {
                assertTrue("line $i adjacent roles differ at $j", roles[j - 1] != roles[j])
            }
        }
        assertEquals("sourceLineCount", text.split('\n').size, plan.sourceLineCount)
    }

    // ------------------------------------------------------------ preprocess

    @Test
    fun preprocessMatchesPdfPlannerOnCrLf() {
        val raw = "a\r\nb\r\nc"
        assertEquals(PdfExportPlanner.preprocess(raw), ImageExportPlanner.preprocess(raw))
        assertEquals("a\nb\nc", ImageExportPlanner.preprocess(raw))
    }

    @Test
    fun preprocessMatchesPdfPlannerOnLoneCr() {
        val raw = "a\rb\rc"
        assertEquals(PdfExportPlanner.preprocess(raw), ImageExportPlanner.preprocess(raw))
        assertEquals("a\nb\nc", ImageExportPlanner.preprocess(raw))
    }

    @Test
    fun preprocessMatchesPdfPlannerOnTabs() {
        val raw = "\tca\tt"
        assertEquals(PdfExportPlanner.preprocess(raw), ImageExportPlanner.preprocess(raw))
        assertEquals("    ca    t", ImageExportPlanner.preprocess(raw))
    }

    @Test
    fun preprocessMatchesPdfPlannerOnMixedVector() {
        val raw = "val a = 1\r\n\t// mixed\rc\rd\r\n"
        assertEquals(PdfExportPlanner.preprocess(raw), ImageExportPlanner.preprocess(raw))
        assertEquals("val a = 1\n    // mixed\nc\nd\n", ImageExportPlanner.preprocess(raw))
    }

    @Test
    fun preprocessKeepsCleanTextIdentical() {
        val clean = "fun main() {\n    return\n}\n"
        assertEquals(clean, ImageExportPlanner.preprocess(clean))
        assertEquals("", ImageExportPlanner.preprocess(""))
    }

    // ------------------------------------------------------------- empty

    @Test
    fun emptyTextFailsEmpty() {
        val failure = failureOf(ImageExportPlanner.plan("", emptyList(), ImageExportOptions()))
        assertEquals(ImageExportErrorCode.EMPTY, failure.code)
        assertEquals(-1, failure.actualLines)
        assertTrue(failure.message.contains("blank"))
    }

    @Test
    fun blankSpacesTextFailsEmpty() {
        val failure = failureOf(ImageExportPlanner.plan("   ", emptyList(), ImageExportOptions()))
        assertEquals(ImageExportErrorCode.EMPTY, failure.code)
    }

    @Test
    fun whitespaceMixTextFailsEmpty() {
        val failure =
            failureOf(ImageExportPlanner.plan(" \n\t ", emptyList(), ImageExportOptions()))
        assertEquals(ImageExportErrorCode.EMPTY, failure.code)
    }

    // -------------------------------------------------------- token guards

    @Test
    fun tokenStartNegativeFailsTokenMismatch() {
        val failure =
            failureOf(
                ImageExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.STRING, -1, 1)),
                    ImageExportOptions(),
                ),
            )
        assertEquals(ImageExportErrorCode.TOKEN_MISMATCH, failure.code)
        assertTrue(failure.message.contains("token[0]"))
        assertTrue(failure.message.contains("[-1, 1)"))
    }

    @Test
    fun tokenEndBeyondLengthFailsTokenMismatch() {
        val failure =
            failureOf(
                ImageExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.KEYWORD, 0, 4)),
                    ImageExportOptions(),
                ),
            )
        assertEquals(ImageExportErrorCode.TOKEN_MISMATCH, failure.code)
        assertTrue(failure.message.contains("3"))
    }

    @Test
    fun invertedRangeFailsTokenMismatch() {
        val failure =
            failureOf(
                ImageExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.STRING, 2, 1)),
                    ImageExportOptions(),
                ),
            )
        assertEquals(ImageExportErrorCode.TOKEN_MISMATCH, failure.code)
    }

    @Test
    fun tokenIndexIsReportedInMismatch() {
        val tokens =
            listOf(
                SyntaxToken(SyntaxTokenType.KEYWORD, 0, 3),
                SyntaxToken(SyntaxTokenType.NUMBER, 4, 9),
            )
        val failure = failureOf(ImageExportPlanner.plan("val x", tokens, ImageExportOptions()))
        assertEquals(ImageExportErrorCode.TOKEN_MISMATCH, failure.code)
        assertTrue(failure.message.contains("token[1]"))
    }

    @Test
    fun hugeEndNeverCrashesPlanner() {
        val failure =
            failureOf(
                ImageExportPlanner.plan(
                    "abc",
                    listOf(SyntaxToken(SyntaxTokenType.COMMENT, 1, Int.MAX_VALUE)),
                    ImageExportOptions(),
                ),
            )
        assertEquals(ImageExportErrorCode.TOKEN_MISMATCH, failure.code)
    }

    // ------------------------------------------------------------ line cap

    @Test
    fun overMaxLinesFailsTooLargeWithActual() {
        val options = ImageExportOptions(maxLines = 3)
        val failure = failureOf(ImageExportPlanner.plan("a\nb\nc\nd", emptyList(), options))
        assertEquals(ImageExportErrorCode.TOO_LARGE, failure.code)
        assertEquals(4, failure.actualLines)
        assertTrue(failure.message.contains("4"))
        assertTrue(failure.message.contains("3"))
    }

    @Test
    fun atMaxLinesBoundarySucceeds() {
        val options = ImageExportOptions(maxLines = 4)
        val plan = successOf(ImageExportPlanner.plan("a\nb\nc\nd", emptyList(), options)).plan
        assertEquals(4, plan.sourceLineCount)
        assertEquals(4, linesOf(plan).size)
    }

    @Test
    fun zeroMaxLinesClampsToOne() {
        // maxLines = 0 clamps to 1, so any two-line document must be TOO_LARGE
        // (proves the clamp ran before the cap comparison).
        val failure =
            failureOf(
                ImageExportPlanner.plan("a\nb", emptyList(), ImageExportOptions(maxLines = 0)),
            )
        assertEquals(ImageExportErrorCode.TOO_LARGE, failure.code)
        assertEquals(2, failure.actualLines)
    }

    @Test
    fun negativeMaxLinesClampsToOne() {
        val failure =
            failureOf(
                ImageExportPlanner.plan("a\nb", emptyList(), ImageExportOptions(maxLines = -5)),
            )
        assertEquals(ImageExportErrorCode.TOO_LARGE, failure.code)
    }

    // -------------------------------------------------------- option clamps

    @Test
    fun wrapColumnsBelowMinClampsTo20() {
        // 45 columns at the clamped 20 wrap into 20/20/5 — nine 5-column
        // chunks would prove the clamp silently failed.
        val options = ImageExportOptions(wrapColumns = 5)
        val plan = successOf(ImageExportPlanner.plan("x".repeat(45), emptyList(), options)).plan
        assertEquals(listOf(20, 20, 5), linesOf(plan).map { textOf(it).length })
        assertEquals(20, plan.options.wrapColumns)
    }

    @Test
    fun wrapColumnsAboveMaxClampsTo200() {
        val options = ImageExportOptions(wrapColumns = 999)
        val plan = successOf(ImageExportPlanner.plan("x".repeat(450), emptyList(), options)).plan
        assertEquals(listOf(200, 200, 50), linesOf(plan).map { textOf(it).length })
        assertEquals(200, plan.options.wrapColumns)
    }

    @Test
    fun wrapColumnsInRangeAreKept() {
        val options = ImageExportOptions(wrapColumns = 30)
        val plan = successOf(ImageExportPlanner.plan("x".repeat(45), emptyList(), options)).plan
        assertEquals(30, plan.options.wrapColumns)
        assertEquals(listOf(30, 15), linesOf(plan).map { textOf(it).length })
    }

    @Test
    fun fontSizeBelowMinClampsTo8() {
        val options = ImageExportOptions(fontSizeSp = 1)
        val plan = successOf(ImageExportPlanner.plan("ok", emptyList(), options)).plan
        assertEquals(ImageExportPlanner.MIN_FONT_SP, plan.options.fontSizeSp)
    }

    @Test
    fun fontSizeAboveMaxClampsTo40() {
        val options = ImageExportOptions(fontSizeSp = 99)
        val plan = successOf(ImageExportPlanner.plan("ok", emptyList(), options)).plan
        assertEquals(ImageExportPlanner.MAX_FONT_SP, plan.options.fontSizeSp)
    }

    @Test
    fun negativePaddingClampsToZero() {
        val options = ImageExportOptions(paddingSp = -7)
        val plan = successOf(ImageExportPlanner.plan("ok", emptyList(), options)).plan
        assertEquals(0, plan.options.paddingSp)
    }

    @Test
    fun planEchoesClampedOptions() {
        val options =
            ImageExportOptions(wrapColumns = 5, fontSizeSp = 1, paddingSp = -3, maxLines = 0)
        val plan = successOf(ImageExportPlanner.plan("ok", emptyList(), options)).plan
        assertEquals(20, plan.options.wrapColumns)
        assertEquals(8, plan.options.fontSizeSp)
        assertEquals(0, plan.options.paddingSp)
        assertEquals(1, plan.options.maxLines)
        assertNotNull(plan.palette)
    }

    @Test
    fun defaultOptionsAreValidAndEchoed() {
        val options = ImageExportOptions()
        assertEquals(80, options.wrapColumns)
        assertEquals(5_000, options.maxLines)
        assertEquals(14, options.fontSizeSp)
        assertEquals(16, options.paddingSp)
        assertEquals("", options.title)
        val plan = successOf(ImageExportPlanner.plan("ok", emptyList(), options)).plan
        assertEquals(options, plan.options)
    }

    // --------------------------------------------------- role mapping (lexer)

    @Test
    fun keywordTokenMapsToKeywordRole() {
        val line = linesOf(successOf(planTok("val x")).plan).single()
        assertEquals(
            listOf(ImageSegment("val", ColorRole.KEYWORD), ImageSegment(" x", ColorRole.PLAIN)),
            line.segments,
        )
    }

    @Test
    fun stringTokenMapsToStringRole() {
        val line = linesOf(successOf(planTok("val s = \"hi\"")).plan).single()
        assertEquals(
            listOf(
                ImageSegment("val", ColorRole.KEYWORD),
                ImageSegment(" s = ", ColorRole.PLAIN),
                ImageSegment("\"hi\"", ColorRole.STRING),
            ),
            line.segments,
        )
    }

    @Test
    fun commentTokenMapsToCommentRole() {
        val line = linesOf(successOf(planTok("// header")).plan).single()
        assertEquals(listOf(ImageSegment("// header", ColorRole.COMMENT)), line.segments)
    }

    @Test
    fun numberTokenMapsToNumberRole() {
        val line = linesOf(successOf(planTok("x = 42")).plan).single()
        assertEquals(
            listOf(ImageSegment("x = ", ColorRole.PLAIN), ImageSegment("42", ColorRole.NUMBER)),
            line.segments,
        )
    }

    @Test
    fun plainGapsBetweenTokensStayPlain() {
        val line = linesOf(successOf(planTok("val n = 12 // ok")).plan).single()
        assertEquals(
            listOf(
                ImageSegment("val", ColorRole.KEYWORD),
                ImageSegment(" n = ", ColorRole.PLAIN),
                ImageSegment("12", ColorRole.NUMBER),
                ImageSegment(" ", ColorRole.PLAIN),
                ImageSegment("// ok", ColorRole.COMMENT),
            ),
            line.segments,
        )
    }

    @Test
    fun emojiDoesNotShiftTokenSegments() {
        // Offsets are UTF-16 code units: "x " (0..1), emoji (2..3), " val"
        // (4..7); the keyword must still land on the right columns.
        val line = linesOf(successOf(planTok("x \uD83D\uDE80 val")).plan).single()
        assertEquals(
            listOf(
                ImageSegment("x \uD83D\uDE80 ", ColorRole.PLAIN),
                ImageSegment("val", ColorRole.KEYWORD),
            ),
            line.segments,
        )
    }

    // ------------------------------------------------------------- merging

    @Test
    fun adjacentSameRoleTokensMerge() {
        val tokens =
            listOf(
                SyntaxToken(SyntaxTokenType.KEYWORD, 0, 3),
                SyntaxToken(SyntaxTokenType.KEYWORD, 3, 6),
            )
        val plan = successOf(ImageExportPlanner.plan("abcdef", tokens, ImageExportOptions())).plan
        val line = plan.lines.single()
        assertEquals(listOf(ImageSegment("abcdef", ColorRole.KEYWORD)), line.segments)
    }

    @Test
    fun overlappingTokensEarlierWins() {
        // KEYWORD covers [0, 5); the overlapping STRING only colors the
        // uncovered remainder [5, 8); the tail stays plain.
        val tokens =
            listOf(
                SyntaxToken(SyntaxTokenType.KEYWORD, 0, 5),
                SyntaxToken(SyntaxTokenType.STRING, 3, 8),
            )
        val plan =
            successOf(ImageExportPlanner.plan("abcdefghij", tokens, ImageExportOptions())).plan
        val line = plan.lines.single()
        assertEquals(
            listOf(
                ImageSegment("abcde", ColorRole.KEYWORD),
                ImageSegment("fgh", ColorRole.STRING),
                ImageSegment("ij", ColorRole.PLAIN),
            ),
            line.segments,
        )
    }

    // ---------------------------------------------------------------- wrap

    @Test
    fun longPlainLineWrapsAtWrapColumns() {
        val options = ImageExportOptions(wrapColumns = 20)
        val plan = successOf(ImageExportPlanner.plan("x".repeat(45), emptyList(), options)).plan
        val lines = linesOf(plan)
        assertEquals(3, lines.size)
        assertEquals(listOf(20, 20, 5), lines.map { textOf(it).length })
        lines.forEach { line ->
            assertEquals(
                listOf(ImageSegment(textOf(line), ColorRole.PLAIN)),
                line.segments,
            )
        }
    }

    @Test
    fun exactBoundaryWrapsWithoutPhantomLine() {
        // A line exactly wrapColumns long must stay ONE chunk: no empty
        // continuation row may appear.
        val options = ImageExportOptions(wrapColumns = 20)
        val plan = successOf(ImageExportPlanner.plan("y".repeat(20), emptyList(), options)).plan
        val lines = linesOf(plan)
        assertEquals(1, lines.size)
        assertEquals("y".repeat(20), textOf(lines.single()))
        assertEquals(1, lines.single().number)
    }

    @Test
    fun continuationChunksRepeatSourceNumber() {
        // v1 decision: every wrapped chunk carries the SAME 1-based number.
        val options = ImageExportOptions(wrapColumns = 20)
        val plan =
            successOf(
                ImageExportPlanner.plan("012345678901234567890123456789", emptyList(), options),
            ).plan
        val lines = linesOf(plan)
        assertEquals(listOf("01234567890123456789", "0123456789"), lines.map(::textOf))
        assertEquals(listOf(1, 1), lines.map { it.number })
    }

    @Test
    fun tokenCrossingWrapKeepsRoles() {
        val options = ImageExportOptions(wrapColumns = 20)
        val text = "abcdefghijklmnopqrstuvwxyz0123"
        val tokens = listOf(SyntaxToken(SyntaxTokenType.KEYWORD, 3, 25))
        val lines = linesOf(successOf(ImageExportPlanner.plan(text, tokens, options)).plan)
        assertEquals(2, lines.size)
        assertEquals(
            listOf(
                ImageSegment("abc", ColorRole.PLAIN),
                ImageSegment("defghijklmnopqrst", ColorRole.KEYWORD),
            ),
            lines[0].segments,
        )
        assertEquals(
            listOf(
                ImageSegment("uvwxy", ColorRole.KEYWORD),
                ImageSegment("z0123", ColorRole.PLAIN),
            ),
            lines[1].segments,
        )
        assertEquals(listOf(1, 1), lines.map { it.number })
    }

    @Test
    fun stringTokenSpanningChunksSplitsWithRoles() {
        val options = ImageExportOptions(wrapColumns = 20)
        val lines = linesOf(successOf(planTok("val s = \"0123456789ABCDEF\"", options)).plan)
        assertEquals(2, lines.size)
        assertEquals(
            listOf(
                ImageSegment("val", ColorRole.KEYWORD),
                ImageSegment(" s = ", ColorRole.PLAIN),
                ImageSegment("\"0123456789A", ColorRole.STRING),
            ),
            lines[0].segments,
        )
        assertEquals(listOf(ImageSegment("BCDEF\"", ColorRole.STRING)), lines[1].segments)
        assertEquals(listOf(1, 1), lines.map { it.number })
    }

    @Test
    fun emojiAcrossWrapBoundaryKeepsConcatenation() {
        // 19 ASCII units + 2 surrogate units + 3 ASCII units; the 20-unit cut
        // lands inside the emoji pair (high surrogate ends row 0, low
        // surrogate starts row 1) and the concatenation invariant must hold.
        val options = ImageExportOptions(wrapColumns = 20)
        val text = "b".repeat(19) + "\uD83D\uDE80" + "c".repeat(3)
        val plan = successOf(ImageExportPlanner.plan(text, emptyList(), options)).plan
        val lines = linesOf(plan)
        assertEquals(2, lines.size)
        assertEquals("b".repeat(19) + "\uD83D", textOf(lines[0]))
        assertEquals("\uDE80ccc", textOf(lines[1]))
        assertInvariants(plan, text, options)
    }

    // ---------------------------------------------------------------- title

    @Test
    fun nonBlankTitleBecomesFirstTitleLine() {
        val options = ImageExportOptions(title = "main.kt")
        val plan = successOf(planTok("val x", options)).plan
        val lines = linesOf(plan)
        assertEquals(2, lines.size)
        assertNull(lines[0].number)
        assertEquals(listOf(ImageSegment("main.kt", ColorRole.TITLE)), lines[0].segments)
        assertEquals(1, lines[1].number)
        assertEquals(1, plan.sourceLineCount)
    }

    @Test
    fun blankTitleSuppressed() {
        val options = ImageExportOptions(title = "  ")
        val plan = successOf(planTok("val x", options)).plan
        val lines = linesOf(plan)
        assertEquals(1, lines.size)
        assertEquals(1, lines.single().number)
        assertTrue(rolesOf(lines.single()).none { it == ColorRole.TITLE })
    }

    @Test
    fun emptyTitleSuppressedByDefault() {
        val plan = successOf(planTok("val x")).plan
        assertEquals(1, linesOf(plan).size)
        assertTrue(linesOf(plan).none { line -> line.segments.any { it.role == ColorRole.TITLE } })
    }

    @Test
    fun titleSurvivesWithLineNumbersDisabled() {
        val options = ImageExportOptions(lineNumbers = false, title = "notes")
        val plan = successOf(planTok("val x", options)).plan
        val lines = linesOf(plan)
        assertEquals(2, lines.size)
        assertNull(lines[0].number)
        assertEquals(ColorRole.TITLE, lines[0].segments.single().role)
        assertNull(lines[1].number)
    }

    // ---------------------------------------------------------- line numbers

    @Test
    fun lineNumbersFalseNullsEveryNumber() {
        val options = ImageExportOptions(lineNumbers = false)
        val plan = successOf(ImageExportPlanner.plan("a\nbb\nccc", emptyList(), options)).plan
        assertEquals(listOf<Int?>(null, null, null), linesOf(plan).map { it.number })
        assertEquals(listOf("a", "bb", "ccc"), linesOf(plan).map(::textOf))
    }

    @Test
    fun lineNumbersFalseStillColors() {
        val options = ImageExportOptions(lineNumbers = false)
        val line = linesOf(successOf(planTok("val n", options)).plan).single()
        assertNull(line.number)
        assertEquals(
            listOf(ImageSegment("val", ColorRole.KEYWORD), ImageSegment(" n", ColorRole.PLAIN)),
            line.segments,
        )
    }

    @Test
    fun multilineNumbersAreOneBased() {
        val plan =
            successOf(ImageExportPlanner.plan("a\nb\nc", emptyList(), ImageExportOptions())).plan
        assertEquals(listOf(1, 2, 3), linesOf(plan).map { it.number })
    }

    @Test
    fun numbersRepeatAcrossWrappedTokensToo() {
        val options = ImageExportOptions(wrapColumns = 20)
        val lines = linesOf(successOf(planTok("val s = \"0123456789ABCDEF\"", options)).plan)
        assertEquals(listOf(1, 1), lines.map { it.number })
    }

    // -------------------------------------------------------------- palette

    @Test
    fun lightPaletteSelectedByDefault() {
        val plan = successOf(planTok("val x")).plan
        assertEquals(ImagePalettes.LIGHT, plan.palette)
        assertEquals(0xFFFAF7F2L, plan.palette.background)
        assertEquals(0xFF2E3440L, plan.palette.foreground)
    }

    @Test
    fun darkPaletteSelectedByDarkTheme() {
        val plan = successOf(planTok("val x", ImageExportOptions(darkTheme = true))).plan
        assertEquals(ImagePalettes.DARK, plan.palette)
        assertEquals(0xFF1E1E2EL, plan.palette.background)
        assertTrue(plan.palette.background != ImagePalettes.LIGHT.background)
    }

    // ------------------------------------------------------ newline semantics

    @Test
    fun trailingNewlineMatchesPdfSplitSemantics() {
        // Verified against PdfExportPlanner (trailingNewlineCountsEmptyLastLine):
        // Kotlin split('\n') keeps the trailing empty string, so "a\n" is two
        // source lines and the last printed line is empty — image export
        // matches this PDF planner behavior on purpose.
        val plan = successOf(ImageExportPlanner.plan("a\n", emptyList(), ImageExportOptions())).plan
        assertEquals(2, plan.sourceLineCount)
        val lines = linesOf(plan)
        assertEquals(listOf(1, 2), lines.map { it.number })
        assertEquals("a", textOf(lines[0]))
        assertEquals("", textOf(lines[1]))
        assertEquals(listOf(ImageSegment("", ColorRole.PLAIN)), lines[1].segments)
    }

    @Test
    fun lastLineWithoutTrailingNewlineStillEmitted() {
        val plan =
            successOf(ImageExportPlanner.plan("a\nb", emptyList(), ImageExportOptions())).plan
        assertEquals(2, plan.sourceLineCount)
        assertEquals("b", textOf(linesOf(plan)[1]))
    }

    @Test
    fun emptyMiddleLineKeepsGridRow() {
        val plan =
            successOf(ImageExportPlanner.plan("a\n\nb", emptyList(), ImageExportOptions())).plan
        val lines = linesOf(plan)
        assertEquals(listOf(1, 2, 3), lines.map { it.number })
        assertEquals(listOf(ImageSegment("", ColorRole.PLAIN)), lines[1].segments)
        assertEquals("", segmentTexts(lines[1]).single())
    }

    // ------------------------------------------------------------- invariants

    @Test
    fun invariantsHoldOnRepresentativeDocument() {
        // Short line, a wrapped line with a token crossing the boundary,
        // empty lines, and a trailing newline — the whole grid must replay
        // exactly through the documented rules.
        val text =
            "val header = 1\n" +
                "\"0123456789ABCDEF\" val\n" +
                "\n" +
                "42 // tail\n"
        val options = ImageExportOptions(wrapColumns = 20, title = "snippet")
        val plan = successOf(planTok(text, options)).plan
        assertInvariants(plan, text, options)
    }

    @Test
    fun invariantsHoldWithoutLineNumbers() {
        val text = "aaaaaaa\nb\n"
        val options = ImageExportOptions(lineNumbers = false, wrapColumns = 20)
        val plan = successOf(planTok(text, options)).plan
        assertInvariants(plan, text, options)
    }

    @Test
    fun crlfOffsetsMatchAfterPreprocessAndTokenize() {
        val raw = "val a = 1\r\n// done"
        val pre = ImageExportPlanner.preprocess(raw)
        val plan = successOf(planTok(pre)).plan
        assertEquals(2, plan.sourceLineCount)
        val lines = linesOf(plan)
        assertEquals(
            listOf(
                ImageSegment("val", ColorRole.KEYWORD),
                ImageSegment(" a = ", ColorRole.PLAIN),
                ImageSegment("1", ColorRole.NUMBER),
            ),
            lines[0].segments,
        )
        assertEquals(listOf(ImageSegment("// done", ColorRole.COMMENT)), lines[1].segments)
        assertEquals(listOf(1, 2), lines.map { it.number })
    }
}
