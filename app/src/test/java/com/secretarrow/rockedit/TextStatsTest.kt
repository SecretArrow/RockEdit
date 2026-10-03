package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.TextStats
import org.junit.Assert.assertEquals
import org.junit.Test

class TextStatsTest {

    @Test
    fun charCountCountsCodeUnits() {
        assertEquals(5, TextStats.charCount("hello"))
        assertEquals(0, TextStats.charCount(""))
    }

    @Test
    fun wordCountHandlesBasicSentences() {
        assertEquals(3, TextStats.wordCount("hello rock edit"))
        assertEquals(2, TextStats.wordCount("  two  words "))
        assertEquals(0, TextStats.wordCount(""))
        assertEquals(0, TextStats.wordCount("   \n\t "))
        assertEquals(1, TextStats.wordCount("single"))
    }

    @Test
    fun wordCountHandlesTabsAndNewlines() {
        assertEquals(4, TextStats.wordCount("a\tb\nc d"))
    }

    @Test
    fun lineCountBasics() {
        assertEquals(0, TextStats.lineCount(""))
        assertEquals(1, TextStats.lineCount("a"))
        assertEquals(1, TextStats.lineCount("a\n"))
        assertEquals(2, TextStats.lineCount("a\nb"))
        assertEquals(2, TextStats.lineCount("a\n\n"))
        assertEquals(3, TextStats.lineCount("a\nb\nc"))
    }

    @Test
    fun lineCountHandlesCRLFAndCR() {
        assertEquals(2, TextStats.lineCount("a\r\nb"))
        assertEquals(3, TextStats.lineCount("a\r\nb\rc"))
    }
}
