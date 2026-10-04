package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.CursorNav
import org.junit.Assert.assertEquals
import org.junit.Test

class CursorNavTest {
    @Test
    fun offsetForLineBasic() {
        val text = "line1\nline2\nline3"
        assertEquals(0, CursorNav.offsetForLine(text, 1))
        assertEquals(6, CursorNav.offsetForLine(text, 2))
        assertEquals(12, CursorNav.offsetForLine(text, 3))
    }

    @Test
    fun offsetForLineClampsBeyondEnd() {
        assertEquals("a\nb".length, CursorNav.offsetForLine("a\nb", 99))
    }

    @Test
    fun offsetForLineHandlesCrlf() {
        val text = "a\r\nb"
        assertEquals(3, CursorNav.offsetForLine(text, 2))
    }

    @Test
    fun lineForOffset() {
        val text = "line1\nline2\nline3"
        assertEquals(1, CursorNav.lineForOffset(text, 0))
        assertEquals(2, CursorNav.lineForOffset(text, 6))
        assertEquals(3, CursorNav.lineForOffset(text, 12))
    }

    @Test
    fun lineForOffsetAtEnd() {
        assertEquals(2, CursorNav.lineForOffset("a\nb", 3))
    }
}
