package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.DiffEngine
import com.secretarrow.rockedit.core.DiffEngine.DiffErrorCode
import com.secretarrow.rockedit.core.DiffEngine.DiffKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Per-branch tests for [DiffEngine] (v0.12.0): empty sides, identical texts,
 * minimal middle edits, option handling, caps, fallback, CRLF and
 * determinism.
 */
class DiffEngineTest {

    private fun done(outcome: DiffEngine.DiffOutcome): DiffEngine.DiffOutcome.Done {
        assertTrue("expected Done, was $outcome", outcome is DiffEngine.DiffOutcome.Done)
        return outcome as DiffEngine.DiffOutcome.Done
    }

    private fun failure(outcome: DiffEngine.DiffOutcome): DiffEngine.DiffOutcome.Failure {
        assertTrue("expected Failure, was $outcome", outcome is DiffEngine.DiffOutcome.Failure)
        return outcome as DiffEngine.DiffOutcome.Failure
    }

    private fun linesOf(op: DiffEngine.DiffOp): List<String> = op.text.split('\n')

    // ------------------------------------------------------------- empties

    @Test
    fun bothSidesEmptyProducesNoOps() {
        val result = done(DiffEngine.diff("", ""))
        assertTrue(result.ops.isEmpty())
        assertEquals(0, result.stats.addedLines)
        assertEquals(0, result.stats.removedLines)
        assertFalse(result.stats.hasChanges)
        assertFalse(result.fellBack)
    }

    @Test
    fun oldSideEmptyInsertsEverything() {
        val result = done(DiffEngine.diff("", "alpha\nbravo"))
        assertEquals(1, result.ops.size)
        val insert = result.ops[0]
        assertEquals(DiffKind.INSERT, insert.kind)
        assertEquals(2, insert.lineCount)
        assertEquals(2, result.stats.addedLines)
        assertEquals(0, result.stats.removedLines)
    }

    @Test
    fun newSideEmptyDeletesEverything() {
        val result = done(DiffEngine.diff("alpha\nbravo", ""))
        assertEquals(1, result.ops.size)
        assertEquals(DiffKind.DELETE, result.ops[0].kind)
        assertEquals(2, result.stats.removedLines)
        assertEquals(0, result.stats.addedLines)
    }

    @Test
    fun identicalTextsAreOneEqualOp() {
        val result = done(DiffEngine.diff("alpha\nbravo", "alpha\nbravo"))
        assertEquals(1, result.ops.size)
        assertEquals(DiffKind.EQUAL, result.ops[0].kind)
        assertFalse(result.stats.hasChanges)
        assertEquals(2, result.stats.unchangedLines)
    }

    @Test
    fun trailingNewlineDifferenceIsInvisible() {
        val result = done(DiffEngine.diff("alpha\nbravo\n", "alpha\nbravo"))
        assertFalse(result.stats.hasChanges)
        assertEquals(2, result.stats.unchangedLines)
    }

    // -------------------------------------------------------- minimal edits

    @Test
    fun middleChangeProducesMinimalDeleteInsert() {
        val result = done(
            DiffEngine.diff("alpha\nbravo\ncharlie", "alpha\nBRAVO\ncharlie")
        )
        assertEquals(3, result.ops.size)
        assertEquals(DiffKind.EQUAL, result.ops[0].kind)
        assertEquals(DiffKind.DELETE, result.ops[1].kind)
        assertEquals("bravo", result.ops[1].text)
        assertEquals(1, result.ops[1].oldStart)
        assertEquals(DiffKind.INSERT, result.ops[2].kind)
        assertEquals("BRAVO", result.ops[2].text)
        assertEquals(1, result.ops[2].newStart)
        assertEquals(1, result.stats.addedLines)
        assertEquals(1, result.stats.removedLines)
        assertEquals(2, result.stats.unchangedLines)
    }

    @Test
    fun pureAppendIsSingleInsert() {
        val result = done(DiffEngine.diff("alpha\nbravo", "alpha\nbravo\ncharlie"))
        assertEquals(2, result.ops.size)
        val insert = result.ops[1]
        assertEquals(DiffKind.INSERT, insert.kind)
        assertEquals("charlie", insert.text)
        assertEquals(2, insert.newStart)
    }

    @Test
    fun interleavedChangesStayMinimal() {
        val result = done(
            DiffEngine.diff("a\nX\nb\nY\nc", "a\nb\nc")
        )
        assertEquals(0, result.stats.addedLines)
        assertEquals(2, result.stats.removedLines)
        assertEquals(3, result.stats.unchangedLines)
    }

    @Test
    fun emptyLineRemovalIsReportedWithEmptyText() {
        val result = done(DiffEngine.diff("a\n\nb", "a\nb"))
        val delete = result.ops.first { it.kind == DiffKind.DELETE }
        assertEquals("", delete.text)
        assertEquals(1, delete.lineCount)
        assertEquals(1, delete.oldStart)
        assertEquals(1, result.stats.removedLines)
        assertEquals(2, result.stats.unchangedLines)
    }

    // -------------------------------------------------------------- options

    @Test
    fun whitespaceOnlyDifferenceNeedsOption() {
        val old = "alpha  bravo"
        val new = "alpha bravo"
        val strict = done(DiffEngine.diff(old, new))
        assertTrue(strict.stats.hasChanges)
        val lenient = done(DiffEngine.diff(old, new, DiffEngine.DiffOptions(ignoreWhitespace = true)))
        assertFalse(lenient.stats.hasChanges)
    }

    @Test
    fun ignoreWhitespaceKeepsOriginalText() {
        val result = done(
            DiffEngine.diff("a   b", "a b", DiffEngine.DiffOptions(ignoreWhitespace = true))
        )
        assertEquals(1, result.ops.size)
        assertEquals(DiffKind.EQUAL, result.ops[0].kind)
        assertEquals("a   b", result.ops[0].text)
    }

    @Test
    fun caseOnlyDifferenceNeedsIgnoreCase() {
        val strict = done(DiffEngine.diff("Alpha", "alpha"))
        assertTrue(strict.stats.hasChanges)
        val lenient = done(DiffEngine.diff("Alpha", "alpha", DiffEngine.DiffOptions(ignoreCase = true)))
        assertFalse(lenient.stats.hasChanges)
    }

    // ----------------------------------------------------------------- caps

    @Test
    fun maxLinesExceededFails() {
        val outcome = DiffEngine.diff("a\nb\nc\nd", "x", DiffEngine.DiffOptions(maxLines = 3))
        val error = failure(outcome)
        assertEquals(DiffErrorCode.INPUT_TOO_LARGE, error.code)
        assertTrue(error.message.contains("limit"))
    }

    @Test
    fun maxLinesAllowsTextWithinBudget() {
        val result = done(
            DiffEngine.diff("a\nb\nc", "a\nb\nc", DiffEngine.DiffOptions(maxLines = 3))
        )
        assertFalse(result.stats.hasChanges)
    }

    @Test
    fun matrixCapFallsBackToWholeBlock() {
        val old = "1\n2\n3\n4"
        val new = "a\nb\nc\nd"
        val options = DiffEngine.DiffOptions(maxMatrixCells = 10)
        val result = done(DiffEngine.diff(old, new, options))
        assertTrue(result.fellBack)
        assertEquals(2, result.ops.size)
        assertEquals(DiffKind.DELETE, result.ops[0].kind)
        assertEquals(DiffKind.INSERT, result.ops[1].kind)
        assertEquals(4, result.stats.addedLines)
        assertEquals(4, result.stats.removedLines)
        assertEquals(0, result.stats.unchangedLines)
    }

    @Test
    fun fallbackInsertOldStartPointsAfterDeletedBlock() {
        val result = done(
            DiffEngine.diff("1\n2\n3", "a\nb", DiffEngine.DiffOptions(maxMatrixCells = 4))
        )
        assertTrue(result.fellBack)
        assertEquals(0, result.ops[0].oldStart)
        assertEquals(0, result.ops[0].newStart)
        assertEquals(3, result.ops[1].oldStart)
        assertEquals(0, result.ops[1].newStart)
    }

    // ------------------------------------------------------------ integrity

    @Test
    fun crlfLineEndingsAreHandled() {
        val result = done(DiffEngine.diff("alpha\r\nbravo", "alpha\r\nbravo\r\ncharlie"))
        assertEquals(1, result.stats.addedLines)
        assertEquals(2, result.stats.unchangedLines)
    }

    @Test
    fun sameInputsAlwaysProduceSameOps() {
        val old = "k\nm\np\nq\nr\ns\nt"
        val new = "k\nn\np\nx\nr\ns\nt"
        val first = done(DiffEngine.diff(old, new))
        val second = done(DiffEngine.diff(old, new))
        assertEquals(first, second)
    }

    @Test
    fun statsCoverEveryEmittedLine() {
        val old = "one\ntwo\nthree\nfour\nfive"
        val new = "one\ntwo\nTWO!\nfour\nfive\nsix"
        val result = done(DiffEngine.diff(old, new))
        var total = 0
        for (op in result.ops) {
            assertEquals("op lineCount must match text", op.lineCount, linesOf(op).size)
            total += op.lineCount
        }
        assertEquals(
            result.stats.addedLines + result.stats.removedLines + result.stats.unchangedLines,
            total
        )
    }

    @Test
    fun equalOpStartPositionsAreZeroBased() {
        val result = done(DiffEngine.diff("a\nb\nc", "a\nX\nc"))
        val delete = result.ops[1]
        assertEquals(1, delete.oldStart)
        val insert = result.ops[2]
        assertEquals(1, insert.newStart)
    }

    // ------------------------------------------------------------- options validation

    @Test
    fun invalidOptionsAreRejected() {
        try {
            DiffEngine.DiffOptions(maxLines = 0)
            fail("maxLines = 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("maxLines"))
        }
        try {
            DiffEngine.DiffOptions(maxMatrixCells = -1)
            fail("negative maxMatrixCells must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("maxMatrixCells"))
        }
    }
}
