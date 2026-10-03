package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.EncodingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingDetectorTest {

    @Test
    fun detectsUtf8() {
        val text = "Rock Edit — plain ASCII and beyond"
        assertEquals("UTF-8", EncodingDetector.detectName(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun detectsUtf16LeWithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "abc".toByteArray(Charsets.UTF_16LE)
        val name = EncodingDetector.detectName(bytes)
        assertTrue("got $name", name == "UTF-16LE" || name.startsWith("UTF-16"))
    }

    @Test
    fun detectsShiftJis() {
        // "こんにちは" (hello in Japanese) encoded in Shift_JIS.
        val shiftJis = byteArrayOf(
            0x82, 0xB1, 0x82, 0xF1, 0x82, 0xC9, 0x82, 0xBF, 0x82, 0xCD
        )
        assertEquals("SHIFT_JIS", EncodingDetector.detectName(shiftJis))
    }

    @Test
    fun emptyBytesDefaultToUtf8() {
        assertEquals(EncodingDetector.DEFAULT_CHARSET, EncodingDetector.detectName(ByteArray(0)))
    }

    @Test
    fun decodeRoundTrip() {
        val text = "Bahasa Indonesia + UTF-8 ✓"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, EncodingDetector.decode(bytes, "UTF-8"))
        assertTrue(EncodingDetector.encode(text, "UTF-8").contentEquals(bytes))
    }

    @Test
    fun decodeFallsBackGracefullyOnBadName() {
        val decoded = EncodingDetector.decode("abc".toByteArray(), "NOT-A-CHARSET")
        assertEquals("abc", decoded)
    }
}
