package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.UndoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {
    @Test
    fun freshStackCannotUndoOrRedo() {
        val stack = UndoStack()
        assertFalse(stack.canUndo())
        assertFalse(stack.canRedo())
    }

    @Test
    fun undoReturnsPreviousState() {
        val stack = UndoStack()
        stack.commit("first")
        stack.commit("second")
        assertTrue(stack.canUndo())
        assertEquals("second", stack.undo("third"))
        assertTrue(stack.canRedo())
        assertEquals("first", stack.undo("second"))
        assertFalse(stack.canUndo())
    }

    @Test
    fun redoReversesUndo() {
        val stack = UndoStack()
        stack.commit("a")
        stack.commit("b")
        assertEquals("b", stack.undo("c"))
        assertEquals("c", stack.redo("b"))
        assertFalse(stack.canRedo())
    }

    @Test
    fun undoOnEmptyReturnsNull() {
        val stack = UndoStack()
        assertNull(stack.undo("anything"))
    }

    @Test
    fun redoOnEmptyReturnsNull() {
        val stack = UndoStack()
        assertNull(stack.redo("anything"))
    }

    @Test
    fun commitClearsRedoPath() {
        val stack = UndoStack()
        stack.commit("a")
        stack.undo("b")
        assertTrue(stack.canRedo())
        stack.commit("b")
        assertFalse(stack.canRedo())
    }

    @Test
    fun respectsLimit() {
        val stack = UndoStack(limit = 3)
        for (i in 1..10) stack.commit("state$i")
        assertEquals(3, stack.size())
        // Oldest entries were dropped; newest commits remain.
        assertEquals("state10", stack.undo("current"))
        assertEquals("state9", stack.undo("state10"))
        assertEquals("state8", stack.undo("state9"))
        assertFalse(stack.canUndo())
    }

    @Test
    fun clearResetsEverything() {
        val stack = UndoStack()
        stack.commit("a")
        stack.undo("b")
        stack.clear()
        assertFalse(stack.canUndo())
        assertFalse(stack.canRedo())
    }
}
