package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.PrintLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintLayoutTest {
    @Test
    fun paginateRespectsLinesPerPage() {
        val text = (1..100).joinToString("\n") { "line $it" }
        val pages = PrintLayout.paginate(text, linesPerPage = 47, charsPerLine = 88)
        assertEquals(3, pages.size)
        assertEquals(47, pages[0].size)
        assertEquals(47, pages[1].size)
        assertEquals(6, pages[2].size)
        assertTrue(pages[0][0].startsWith("line 1"))
        assertTrue(pages[2].last().startsWith("line 100"))
    }

    @Test
    fun longLinesAreHardWrapped() {
        val longLine = "x".repeat(200)
        val pages = PrintLayout.paginate(longLine, linesPerPage = 10, charsPerLine = 88)
        val allLines = pages.flatten()
        assertEquals(3, allLines.size)
        assertTrue(allLines.all { it.length <= 88 })
        assertEquals(200, allLines.sumOf { it.length })
    }

    @Test
    fun crlfAndTabsAreNormalized() {
        val text = "a\r\nb\tc"
        val lines = PrintLayout.paginate(text, 10, 88).flatten()
        assertEquals(listOf("a", "b    c"), lines)
    }

    @Test
    fun emptyTextYieldsSingleEmptyPage() {
        val pages = PrintLayout.paginate("", 47, 88)
        assertEquals(1, pages.size)
        assertEquals(listOf(""), pages[0])
    }

    @Test
    fun blankLinesArePreservedAsSeparators() {
        val text = "one\n\ntwo"
        val lines = PrintLayout.paginate(text, 47, 88).flatten()
        assertEquals(listOf("one", "", "two"), lines)
    }

    @Test
    fun geometryConstantsAreA4Compatible() {
        assertTrue(PrintLayout.PAGE_WIDTH_POINTS < PrintLayout.PAGE_HEIGHT_POINTS)
        assertTrue(PrintLayout.DEFAULT_LINES_PER_PAGE > 0)
        assertTrue(PrintLayout.DEFAULT_CHARS_PER_LINE > 0)
    }
}
