package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FolderGrep
import com.secretarrow.rockedit.core.FolderGrep.GrepFile
import com.secretarrow.rockedit.core.FolderGrep.GrepOptions
import com.secretarrow.rockedit.core.FolderGrep.GrepOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [FolderGrep] (v0.11.0): empty query, invalid regex,
 * literal quoting, case folding, aggregation, binary skipping, per-file and
 * total caps, CRLF lines, and preview clipping.
 */
class FolderGrepTest {
    private fun done(
        files: List<GrepFile>,
        query: String,
        options: GrepOptions = GrepOptions(),
    ): FolderGrep.GrepSummary {
        val outcome = FolderGrep.run(files, query, options)
        assertTrue("expected Done, was $outcome", outcome is GrepOutcome.Done)
        return (outcome as GrepOutcome.Done).summary
    }

    private fun failure(
        files: List<GrepFile>,
        query: String,
        options: GrepOptions = GrepOptions(),
    ): FolderGrep.GrepError {
        val outcome = FolderGrep.run(files, query, options)
        assertTrue("expected Failure, was $outcome", outcome is GrepOutcome.Failure)
        return (outcome as GrepOutcome.Failure).error
    }

    // ------------------------------------------------------------- contract

    @Test
    fun blankQueryFails() {
        assertEquals(FolderGrep.ErrorCode.EMPTY_QUERY, failure(emptyList(), "").code)
        assertEquals(FolderGrep.ErrorCode.EMPTY_QUERY, failure(emptyList(), " \t ").code)
    }

    @Test
    fun invalidRegexFailsWithoutCrash() {
        assertEquals(
            FolderGrep.ErrorCode.PARSE_ERROR,
            failure(emptyList(), "[", GrepOptions(isRegex = true)).code,
        )
        // Literal mode never fails: "[" quotes to a valid pattern.
        assertEquals(0, done(emptyList(), "[").hits.size)
    }

    @Test
    fun emptyFileListIsEmptySummary() {
        val summary = done(emptyList(), "x")
        assertEquals(0, summary.hits.size)
        assertEquals(0, summary.filesScanned)
        assertFalse(summary.truncatedMatches)
        assertFalse(summary.truncatedFiles)
    }

    // ------------------------------------------------------- literal vs regex

    @Test
    fun literalModeQuotesMetacharacters() {
        val summary = done(listOf(GrepFile("a.txt", "axb a.b")), "a.b")
        assertEquals(1, summary.hits.size)
        assertEquals(4, summary.hits[0].column)
    }

    @Test
    fun regexModeMatchesMetacharacters() {
        val summary = done(listOf(GrepFile("a.txt", "axb a.b")), "a.b", GrepOptions(isRegex = true))
        assertEquals(2, summary.hits.size)
    }

    @Test
    fun ignoreCaseOption() {
        val file = listOf(GrepFile("a.txt", "hello world"))
        assertEquals(0, done(file, "HELLO").hits.size)
        assertEquals(1, done(file, "HELLO", GrepOptions(ignoreCase = true)).hits.size)
    }

    // ------------------------------------------------------------ aggregation

    @Test
    fun hitsCarryPathLineAndColumn() {
        val summary =
            done(
                listOf(GrepFile("src/App.kt", "alpha\nbravo\nalpha again")),
                "alpha",
            )
        assertEquals(2, summary.hits.size)
        assertEquals("src/App.kt", summary.hits[0].path)
        assertEquals(1, summary.hits[0].lineNumber)
        assertEquals(0, summary.hits[0].column)
        assertEquals(3, summary.hits[1].lineNumber)
        assertEquals(1, summary.filesScanned)
        assertEquals(1, summary.filesWithHits)
    }

    @Test
    fun crlfLinesAreCountedCorrectly() {
        val summary = done(listOf(GrepFile("f.txt", "one\r\ntwo\r\nthree")), "three")
        assertEquals(1, summary.hits.size)
        assertEquals(3, summary.hits[0].lineNumber)
    }

    @Test
    fun emptyContentIsScannedWithNoHits() {
        val summary = done(listOf(GrepFile("empty.txt", "")), "x")
        assertEquals(1, summary.filesScanned)
        assertEquals(0, summary.hits.size)
        assertEquals(0, summary.filesWithHits)
    }

    // ---------------------------------------------------------------- binary

    @Test
    fun binaryFileIsSkippedAndCounted() {
        val summary =
            done(
                listOf(GrepFile("bin.dat", "abc\u0000xyz")),
                "abc",
            )
        assertEquals(1, summary.skippedBinary)
        assertEquals(0, summary.filesScanned)
        assertEquals(0, summary.hits.size)
    }

    @Test
    fun nulBeyondSniffWindowDoesNotTriggerBinarySkip() {
        val prefix = "z".repeat(FolderGrep.BINARY_SNIFF_BYTES + 10)
        val summary = done(listOf(GrepFile("big.txt", prefix + " needle")), "needle")
        assertEquals(0, summary.skippedBinary)
        assertEquals(1, summary.hits.size)
    }

    // ------------------------------------------------------------------ caps

    @Test
    fun perFileCapSetsTruncatedFlag() {
        val summary =
            done(
                listOf(GrepFile("f.txt", "x\nx\nx\nx")),
                "x",
                GrepOptions(maxMatchesPerFile = 2),
            )
        assertEquals(2, summary.hits.size)
        assertTrue(summary.truncatedMatches)
    }

    @Test
    fun totalCapStopsTraversalAndSetsFlags() {
        val files =
            listOf(
                GrepFile("a.txt", "hit\nhit\nhit"),
                GrepFile("b.txt", "hit\nhit"),
            )
        val summary = done(files, "hit", GrepOptions(maxTotalMatches = 4))
        assertEquals(4, summary.hits.size)
        assertTrue(summary.truncatedMatches)
        assertEquals("a.txt", summary.hits[0].path)
        assertEquals("b.txt", summary.hits[3].path)
    }

    // --------------------------------------------------------------- preview

    @Test
    fun longLinePreviewIsClipped() {
        val long = "needle" + "y".repeat(250) // exactly one match
        val summary = done(listOf(GrepFile("f.txt", long)), "needle")
        assertEquals(1, summary.hits.size)
        assertEquals(FolderGrep.PREVIEW_MAX_CHARS + 1, summary.hits[0].preview.length)
        assertTrue(summary.hits[0].preview.endsWith("…"))
    }

    @Test
    fun previewIsTrimmed() {
        val summary = done(listOf(GrepFile("f.txt", "    padded needle line    ")), "needle")
        assertEquals("padded needle line", summary.hits[0].preview)
    }
}
