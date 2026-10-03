package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.LineBreak
import org.junit.Assert.assertEquals
import org.junit.Test

class LineBreakTest {

    @Test
    fun detectLf() {
        assertEquals(LineBreak.LF, LineBreak.detect("a\nb"))
    }

    @Test
    fun detectCr() {
        assertEquals(LineBreak.CR, LineBreak.detect("a\rb"))
    }

    @Test
    fun detectCrlf() {
        assertEquals(LineBreak.CRLF, LineBreak.detect("a\r\nb"))
    }

    @Test
    fun detectFirstBreakWins() {
        assertEquals(LineBreak.LF, LineBreak.detect("a\nb\r\nc"))
        assertEquals(LineBreak.CRLF, LineBreak.detect("a\r\nb\nc"))
    }

    @Test
    fun detectEmptyUsesFallback() {
        assertEquals(LineBreak.CRLF, LineBreak.detect("", LineBreak.CRLF))
        assertEquals(LineBreak.LF, LineBreak.detect("no breaks", LineBreak.LF))
    }

    @Test
    fun normalizeToLf() {
        assertEquals("a\nb\nc", LineBreak.normalize("a\r\nb\rc", LineBreak.LF))
    }

    @Test
    fun normalizeToCrlf() {
        assertEquals("a\r\nb\r\nc", LineBreak.normalize("a\nb\rc", LineBreak.CRLF))
    }

    @Test
    fun normalizeToCr() {
        assertEquals("a\rb\rc", LineBreak.normalize("a\nb\r\nc", LineBreak.CR))
    }

    @Test
    fun normalizeKeepsEmptyText() {
        assertEquals("", LineBreak.normalize("", LineBreak.CRLF))
    }
}
