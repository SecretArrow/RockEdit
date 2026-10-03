package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import com.secretarrow.rockedit.core.LineOps
import org.junit.Assert.assertEquals
import org.junit.Test

class LineOpsTest {

    // ------------------------------------------------------- duplicate line

    @Test
    fun duplicateMiddleLineLf() {
        val r = LineOps.duplicateLine("a\nb\nc", 1, 1) // caret in "a", col 1
        // Caret at offset 1 is still line 1 ("a") since break at index 1.
        assertEquals("a\na\nb\nc", r.text)
        // Caret lands on the copy (line 2 starts at 2) with the same column.
        assertEquals(3, r.selStart)
    }

    @Test
    fun duplicateLineOnLineTwo() {
        val r = LineOps.duplicateLine("a\nb\nc", 2, 2) // offset 2 = start of "b"
        assertEquals("a\nb\nb\nc", r.text)
        assertEquals(4, r.selStart)
    }

    @Test
    fun duplicateLastLineWithoutBreak() {
        val r = LineOps.duplicateLine("a\nb", 3, 3)
        assertEquals("a\nb\nb", r.text)
    }

    @Test
    fun duplicatePreservesCrlf() {
        val r = LineOps.duplicateLine("a\r\nb", 0, 0)
        assertEquals("a\r\na\r\nb", r.text)
    }

    @Test
    fun duplicateCaretColumnPreservedOnCopy() {
        val r = LineOps.duplicateLine("hello\nworld", 8, 8) // line 2, col 2
        assertEquals("hello\nworld\nworld", r.text)
        assertEquals("hello\nworld\n".length + 2, r.selStart)
    }

    // --------------------------------------------------------- delete line

    @Test
    fun deleteMiddleLine() {
        val r = LineOps.deleteLine("a\nb\nc", 2, 2)
        assertEquals("a\nc", r.text)
        assertEquals(2, r.selStart)
    }

    @Test
    fun deleteLastLineRemovesDanglingBreak() {
        val r = LineOps.deleteLine("a\nb", 2, 2)
        assertEquals("a", r.text)
        assertEquals(1, r.selStart)
    }

    @Test
    fun deleteOnlyLineYieldsEmpty() {
        val r = LineOps.deleteLine("abc", 1, 1)
        assertEquals("", r.text)
        assertEquals(0, r.selStart)
    }

    @Test
    fun deletePreservesCrlfOfOthers() {
        val r = LineOps.deleteLine("a\r\nb\r\nc", 3, 3)
        assertEquals("a\r\nc", r.text)
    }

    // ----------------------------------------------------------- move up/down

    @Test
    fun moveUpSwapsAdjacentLines() {
        val r = LineOps.moveLineUp("a\nb\nc", 2, 2) // "b" up
        assertEquals("b\na\nc", r.text)
        assertEquals(0, r.selStart) // caret follows the line
    }

    @Test
    fun moveUpOnFirstLineIsNoOp() {
        val r = LineOps.moveLineUp("a\nb", 0, 0)
        assertEquals("a\nb", r.text)
    }

    @Test
    fun moveDownSwapsAdjacentLines() {
        val r = LineOps.moveLineDown("a\nb\nc", 0, 0) // "a" down
        assertEquals("b\na\nc", r.text)
        assertEquals(2, r.selStart)
    }

    @Test
    fun moveDownOnLastLineIsNoOp() {
        val r = LineOps.moveLineDown("a\nb", 2, 2)
        assertEquals("a\nb", r.text)
    }

    @Test
    fun moveUpLastLineWithoutBreak() {
        val r = LineOps.moveLineUp("a\nb", 2, 2) // "b" (no trailing break) up
        assertEquals("b\na", r.text)
    }

    @Test
    fun moveDownLineWithoutFinalBreak() {
        val r = LineOps.moveLineDown("a\nb", 0, 0) // "a" down; "b" has no break
        assertEquals("b\na", r.text)
        assertEquals(2, r.selStart) // caret follows "a" to line 2
    }

    @Test
    fun moveDownPreservesCrlf() {
        val r = LineOps.moveLineDown("a\r\nb\nc", 0, 0)
        assertEquals("b\r\na\nc", r.text)
    }

    @Test
    fun moveUpPreservesCrlf() {
        val r = LineOps.moveLineUp("a\r\nb\nc", 3, 3) // "b" up
        assertEquals("b\r\na\nc", r.text)
    }

    @Test
    fun moveUpKeepsColumn() {
        val text = "abcd\nxy\nef"
        val r = LineOps.moveLineUp(text, 7, 7) // "xy" col 2
        assertEquals("xy\nabcd\nef", r.text)
        assertEquals(2, r.selStart)
    }

    @Test
    fun moveDownCaretFollowsLine() {
        val text = "abcdef\nx"
        val r = LineOps.moveLineDown(text, 0, 0)
        assertEquals("x\nabcdef", r.text)
        // Caret follows "abcdef" to line 2, which now starts at offset 2.
        assertEquals(2, r.selStart)
    }

    @Test
    fun helpersHandleBoundaries() {
        assertEquals(0, LineOps.lineStart("abc", 0))
        assertEquals(0, LineOps.lineStart("a\nb", 2))
        assertEquals(3, LineOps.lineEnd("abc", 0))
        assertEquals(1, LineOps.lineEnd("a\nb", 0))
        assertEquals(2, LineOps.lineEndIncludingBreak("a\nb", 0))
        assertEquals(3, LineOps.lineEndIncludingBreak("a\r\nb", 0))
        assertEquals(1, LineOps.lineEndIncludingBreak("a", 0)) // no break
    }

    @Test
    fun deleteLineWithSelectionStartInsideLine() {
        // Selection start inside "b", end inside "c": operation follows selStart.
        val r = LineOps.deleteLine("a\nb\nc", 3, 5)
        assertEquals("a\nc", r.text)
    }
}
