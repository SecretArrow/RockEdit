package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.EncodingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EncodingCharsetsTest {

    @Test
    fun commonCharsetsAreUniqueAndSupported() {
        val names = EncodingDetector.COMMON_CHARSETS
        assertEquals(names.size, names.toSet().size)
        for (name in names) {
            Charset.forName(name) // throws when the JVM cannot resolve the charset
        }
    }

    @Test
    fun utf8IsPresentedFirst() {
        assertEquals(EncodingDetector.DEFAULT_CHARSET, EncodingDetector.COMMON_CHARSETS.first())
    }

    @Test
    fun forcedCharsetDecodesBytes() {
        // "你好" encoded in GBK.
        val bytes = byteArrayOf(0xC4.toByte(), 0xE3.toByte(), 0xBA.toByte(), 0xC3.toByte())
        assertEquals("你好", EncodingDetector.decode(bytes, "GBK"))
    }

    @Test
    fun forcedCharsetEncodesBytes() {
        val encoded = EncodingDetector.encode("A", "UTF-16LE")
        assertEquals(2, encoded.size)
        assertEquals(65, encoded[0].toInt())
        assertEquals(0, encoded[1].toInt())
    }
}
