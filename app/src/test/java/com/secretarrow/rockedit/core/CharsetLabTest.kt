package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [CharsetLab] (v0.16.0): BOM detection including the
 * UTF-32LE-before-UTF-16LE overlap rule, the hex renderer, the encode table
 * (byte counts, per-sequence unmappable counting, UTF-16 BOM alias behavior,
 * unknown/illegal names, round trips), the decode report (REPORT counting
 * vs. REPLACE preview, the -1-on-success / 0-on-empty contract quirk,
 * preview truncation with surrogate splitting), and detectionReport
 * passthrough against [EncodingDetector].
 *
 * Detection expectations for BOM-less samples were pinned empirically
 * against juniversalchardet 2.5.0 (the exact dependency in
 * app/build.gradle.kts): pure ASCII reports "US-ASCII", valid UTF-8 reports
 * "UTF-8", Cyrillic windows-1251 bytes report "WINDOWS-1251". The
 * passthrough tests additionally assert equality with
 * [EncodingDetector.detectName] so the delegation contract is checked even
 * if the library's verdict ever changed.
 */
class CharsetLabTest {
    /** UTF-8 bytes of U+1F600 (grinning face), used by the preview tests. */
    private val SMILE = intArrayOf(0xF0, 0x9F, 0x98, 0x80)

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private fun unsupportedEncode(name: String): EncodeReport =
        EncodeReport(
            charsetName = name,
            supported = false,
            byteCount = -1,
            unmappableCount = -1,
            firstBytesHex = "",
            roundTripOk = false,
        )

    // ------------------------------------------------------------ BOM rules

    @Test
    fun bomEmptyHasNoBom() {
        val info = CharsetLab.bomInfo(ByteArray(0))
        assertFalse(info.hasBom)
        assertNull(info.bomName)
    }

    @Test
    fun bomPlainAsciiHasNoBom() {
        val info = CharsetLab.bomInfo(bytes(0x41))
        assertFalse(info.hasBom)
        assertNull(info.bomName)
    }

    @Test
    fun bomDetectsUtf8() {
        val info = CharsetLab.bomInfo(bytes(0xEF, 0xBB, 0xBF, 0x68, 0x69))
        assertTrue(info.hasBom)
        assertEquals("UTF-8", info.bomName)
    }

    @Test
    fun bomDetectsUtf16Le() {
        val info = CharsetLab.bomInfo(bytes(0xFF, 0xFE, 0x68, 0x00))
        assertTrue(info.hasBom)
        assertEquals("UTF-16LE", info.bomName)
    }

    @Test
    fun bomDetectsUtf16Be() {
        val info = CharsetLab.bomInfo(bytes(0xFE, 0xFF, 0x00, 0x68))
        assertTrue(info.hasBom)
        assertEquals("UTF-16BE", info.bomName)
    }

    @Test
    fun bomDetectsUtf32Le() {
        val info = CharsetLab.bomInfo(bytes(0xFF, 0xFE, 0x00, 0x00, 0x68, 0x00, 0x00, 0x00))
        assertTrue(info.hasBom)
        assertEquals("UTF-32LE", info.bomName)
    }

    @Test
    fun bomDetectsUtf32Be() {
        val info = CharsetLab.bomInfo(bytes(0x00, 0x00, 0xFE, 0xFF, 0x00, 0x00, 0x00, 0x68))
        assertTrue(info.hasBom)
        assertEquals("UTF-32BE", info.bomName)
    }

    @Test
    fun bomUtf32LeBeatsUtf16Le() {
        // FF FE 00 00 is a full UTF-32LE BOM AND the start of a UTF-16LE BOM;
        // UTF-32LE is checked first, so it must win (rule 2).
        val info = CharsetLab.bomInfo(bytes(0xFF, 0xFE, 0x00, 0x00))
        assertTrue(info.hasBom)
        assertEquals("UTF-32LE", info.bomName)
    }

    @Test
    fun bomShortFfFeIsUtf16Le() {
        // Two bytes: only the UTF-16LE BOM fits. Three bytes: still no UTF-32
        // BOM (four required), so UTF-16LE stays the answer (rule 3).
        assertEquals("UTF-16LE", CharsetLab.bomInfo(bytes(0xFF, 0xFE)).bomName)
        assertEquals("UTF-16LE", CharsetLab.bomInfo(bytes(0xFF, 0xFE, 0x00)).bomName)
    }

    // ----------------------------------------------------------------- hex

    @Test
    fun hexEmptyIsEmpty() {
        assertEquals("", CharsetLab.hex(ByteArray(0)))
    }

    @Test
    fun hexLowercaseSpacePairs() {
        assertEquals("ff 0a 00", CharsetLab.hex(bytes(0xFF, 0x0A, 0x00)))
    }

    @Test
    fun hexExactlyThirtyTwoBytes() {
        val input = ByteArray(32) { it.toByte() }
        val expected =
            "00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f " +
                "10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f"
        assertEquals(expected, CharsetLab.hex(input))
        assertEquals(95, CharsetLab.hex(input).length)
    }

    @Test
    fun hexTruncatesPastDefault() {
        val input = ByteArray(40) { it.toByte() }
        val hex = CharsetLab.hex(input)
        assertFalse(hex.contains("20"))
        assertEquals(32, hex.split(" ").size)
        assertEquals(
            CharsetLab.hex(input, CharsetLab.HEX_BYTES),
            hex,
        )
    }

    @Test
    fun hexHonorsCustomMaxBytes() {
        assertEquals("01 02", CharsetLab.hex(bytes(0x01, 0x02, 0x03), 2))
        assertEquals("ff", CharsetLab.hex(bytes(0xFF), 1))
    }

    @Test
    fun hexNonPositiveMaxIsEmpty() {
        assertEquals("", CharsetLab.hex(bytes(0x01), 0))
        assertEquals("", CharsetLab.hex(bytes(0x01), -3))
    }

    // ------------------------------------------------------- encode reports

    @Test
    fun encodeUtf8ByteCounts() {
        // Hand-computed UTF-8 lengths: 1, 2, 3, 4 bytes for the four samples.
        assertEquals(1, singleEncode("a", "UTF-8").byteCount)
        assertEquals(2, singleEncode("\u00E9", "UTF-8").byteCount)
        assertEquals(3, singleEncode("\u20AC", "UTF-8").byteCount)
        assertEquals(4, singleEncode("\uD83D\uDE00", "UTF-8").byteCount)
        listOf("a", "\u00E9", "\u20AC", "\uD83D\uDE00").forEach { text ->
            val report = singleEncode(text, "UTF-8")
            assertTrue("UTF-8 must support $text", report.roundTripOk)
            assertEquals(0, report.unmappableCount)
            assertTrue(report.supported)
        }
    }

    @Test
    fun encodeUtf8EmojiHex() {
        assertEquals("f0 9f 98 80", singleEncode("\uD83D\uDE00", "UTF-8").firstBytesHex)
    }

    @Test
    fun encodeIso88591Latin1() {
        val report = singleEncode("\u00E9", "ISO-8859-1")
        assertTrue(report.supported)
        assertEquals(1, report.byteCount)
        assertEquals(0, report.unmappableCount)
        assertEquals("e9", report.firstBytesHex)
        assertTrue(report.roundTripOk)
    }

    @Test
    fun encodeIso88591Euro() {
        val report = singleEncode("\u20AC", "ISO-8859-1")
        assertTrue(report.supported)
        assertEquals(0, report.byteCount)
        assertEquals(1, report.unmappableCount)
        assertEquals("", report.firstBytesHex)
        assertFalse(report.roundTripOk)
    }

    @Test
    fun encodeIso88591EuroEmoji() {
        // U+20AC is one unmappable char; U+1F600 is a surrogate pair reported
        // as UNMAPPABLE[2] but still ONE character: 2 counts, not 3 units.
        val report = singleEncode("\u20AC\uD83D\uDE00", "ISO-8859-1")
        assertTrue(report.supported)
        assertEquals(2, report.unmappableCount)
        assertEquals(0, report.byteCount)
        assertFalse(report.roundTripOk)
    }

    @Test
    fun encodeIso88591Ascii() {
        val report = singleEncode("hello", "ISO-8859-1")
        assertEquals(5, report.byteCount)
        assertEquals(0, report.unmappableCount)
        assertTrue(report.roundTripOk)
    }

    @Test
    fun encodeUsAsciiUnmappables() {
        assertEquals(1, singleEncode("\u00E9", "US-ASCII").unmappableCount)
        assertEquals(2, singleEncode("\u20AC\uD83D\uDE00", "US-ASCII").unmappableCount)
        assertFalse(singleEncode("\u00E9", "US-ASCII").roundTripOk)
    }

    @Test
    fun encodeUnknownNameUnsupported() {
        val report = CharsetLab.encodeReports("a", listOf("NOT-A-CHARSET")).single()
        assertEquals(unsupportedEncode("NOT-A-CHARSET"), report)
    }

    @Test
    fun encodeIllegalNamesNeverThrow() {
        val names = listOf("", "a b", "not valid!", "\u00FCn\u00E7ode")
        for (name in names) {
            val report = singleEncode("a", name)
            assertFalse("name <$name> must be unsupported", report.supported)
            assertEquals(-1, report.byteCount)
            assertEquals(-1, report.unmappableCount)
            assertEquals("", report.firstBytesHex)
            assertFalse(report.roundTripOk)
        }
    }

    @Test
    fun encodeEmptyTextZeroBytes() {
        for (name in listOf("UTF-8", "ISO-8859-1", "US-ASCII", "UTF-16")) {
            val report = singleEncode("", name)
            assertTrue("$name must be supported", report.supported)
            assertEquals("$name empty byteCount", 0, report.byteCount)
            assertEquals("$name empty unmappable", 0, report.unmappableCount)
            assertEquals("$name empty hex", "", report.firstBytesHex)
            assertTrue("$name empty round trip", report.roundTripOk)
        }
    }

    @Test
    fun encodeUtf16AliasAddsBom() {
        // Java's "UTF-16" alias is BE with a BOM: 2 BOM bytes + 2 per char.
        val report = singleEncode("h\u00E9llo", "UTF-16")
        assertTrue(report.supported)
        assertEquals(12, report.byteCount)
        assertEquals(0, report.unmappableCount)
        assertTrue(report.firstBytesHex.startsWith("fe ff"))
        assertTrue(report.roundTripOk)
    }

    @Test
    fun encodeUtf16LeHasNoBom() {
        val report = singleEncode("a", "UTF-16LE")
        assertEquals(2, report.byteCount)
        assertEquals("61 00", report.firstBytesHex)
        assertTrue(report.roundTripOk)
    }

    @Test
    fun encodeUtf16BeHasNoBom() {
        val report = singleEncode("a", "UTF-16BE")
        assertEquals(2, report.byteCount)
        assertEquals("00 61", report.firstBytesHex)
        assertTrue(report.roundTripOk)
    }

    @Test
    fun encodeHexCapsAtThirtyTwo() {
        val report = singleEncode("x".repeat(40), "UTF-8")
        assertEquals(40, report.byteCount)
        val expected = "78 ".repeat(32).trim()
        assertEquals(95, report.firstBytesHex.length)
        assertEquals(expected, report.firstBytesHex)
    }

    @Test
    fun encodeLoneSurrogateIsLossy() {
        // A lone surrogate cannot be encoded: the MALFORMED result shares the
        // unmappable bucket, nothing was produced, and the round trip breaks.
        val report = singleEncode("\uD800", "UTF-8")
        assertTrue(report.supported)
        assertEquals(1, report.unmappableCount)
        assertEquals(0, report.byteCount)
        assertEquals("", report.firstBytesHex)
        assertFalse(report.roundTripOk)
    }

    @Test
    fun encodeWindows1252Cafe() {
        val report = singleEncode("caf\u00E9", "windows-1252")
        assertTrue(report.supported)
        assertEquals(4, report.byteCount)
        assertEquals(0, report.unmappableCount)
        assertEquals("63 61 66 e9", report.firstBytesHex)
        assertTrue(report.roundTripOk)
    }

    @Test
    fun encodeDuplicateNames() {
        val reports = CharsetLab.encodeReports("a", listOf("UTF-8", "ISO-8859-1", "UTF-8"))
        assertEquals(3, reports.size)
        assertEquals(listOf("UTF-8", "ISO-8859-1", "UTF-8"), reports.map { it.charsetName })
        assertEquals(reports[0], reports[2])
    }

    @Test
    fun encodeReportOrderFollowsInput() {
        val reports = CharsetLab.encodeReports("\u00E9", listOf("ISO-8859-1", "US-ASCII", "UTF-8"))
        assertEquals(listOf("ISO-8859-1", "US-ASCII", "UTF-8"), reports.map { it.charsetName })
        assertEquals(1, reports[0].byteCount)
        assertEquals(1, reports[1].unmappableCount)
        assertEquals(2, reports[2].byteCount)
    }

    @Test
    fun encodeEmptyNameList() {
        assertTrue(CharsetLab.encodeReports("a", emptyList()).isEmpty())
    }

    // ------------------------------------------------------- decode reports

    @Test
    fun decodeValidUtf8Succeeds() {
        val report = CharsetLab.decodeReport(bytes(0x68, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F), "UTF-8")
        assertTrue(report.supported)
        assertTrue(report.success)
        // Clean non-empty decode: the -1 "nothing replaced" sentinel.
        assertEquals(-1, report.replacementCount)
        assertEquals("h\u00E9llo", report.preview)
    }

    @Test
    fun decodeTruncatedUtf8TwoBytes() {
        // The leading byte of "U+00E9" alone is one malformed sequence.
        val report = CharsetLab.decodeReport(bytes(0xC3), "UTF-8")
        assertTrue(report.supported)
        assertFalse(report.success)
        assertEquals(1, report.replacementCount)
        assertEquals("\uFFFD", report.preview)
    }

    @Test
    fun decodeTruncatedUtf8ThreeBytes() {
        // "U+20AC" missing its last byte: reported as ONE bad sequence
        // (MALFORMED[2]), not one per byte, and REPLACE also yields one
        // U+FFFD for the maximal subpart.
        val report = CharsetLab.decodeReport(bytes(0xE2, 0x82), "UTF-8")
        assertFalse(report.success)
        assertEquals(1, report.replacementCount)
        assertEquals("\uFFFD", report.preview)
    }

    @Test
    fun decodeThreeBadUtf8Sequences() {
        val report = CharsetLab.decodeReport(bytes(0xC3, 0xC3, 0xC3), "UTF-8")
        assertFalse(report.success)
        assertEquals(3, report.replacementCount)
        assertEquals("\uFFFD\uFFFD\uFFFD", report.preview)
    }

    @Test
    fun decodeInvalidUtf8Byte() {
        val report = CharsetLab.decodeReport(bytes(0xFF), "UTF-8")
        assertFalse(report.success)
        assertEquals(1, report.replacementCount)
        assertEquals("\uFFFD", report.preview)
    }

    @Test
    fun decodeOddUtf16ByteCount() {
        // Odd byte count: the trailing byte is one malformed sequence. The
        // FF FE prefix still decodes to U+FEFF before the U+FFFD replacement.
        val report = CharsetLab.decodeReport(bytes(0xFF, 0xFE, 0x00), "UTF-16LE")
        assertTrue(report.supported)
        assertFalse(report.success)
        assertEquals(1, report.replacementCount)
        assertEquals("\uFEFF\uFFFD", report.preview)
    }

    @Test
    fun decodeUnknownCharset() {
        val report = CharsetLab.decodeReport(bytes(0x61), "NOT-A-CHARSET")
        assertFalse(report.supported)
        assertFalse(report.success)
        assertEquals(-1, report.replacementCount)
        assertEquals("", report.preview)
    }

    @Test
    fun decodeEmptyBytesSucceeds() {
        val report = CharsetLab.decodeReport(ByteArray(0), "UTF-8")
        assertTrue(report.supported)
        assertTrue(report.success)
        // Documented empty-input special case: 0 (not the -1 sentinel).
        assertEquals(0, report.replacementCount)
        assertEquals("", report.preview)
    }

    @Test
    fun decodePreviewCutAtTwoHundred() {
        val sample = ByteArray(300) { 0x61 }
        val report = CharsetLab.decodeReport(sample, "UTF-8")
        assertTrue(report.success)
        assertEquals(CharsetLab.PREVIEW_CHARS, report.preview.length)
        assertEquals("a".repeat(CharsetLab.PREVIEW_CHARS), report.preview)
    }

    @Test
    fun decodePreviewSplitsSurrogate() {
        // "a" + 150 emoji decode to 301 UTF-16 units, so the 200-unit cut
        // lands mid-pair: the preview ends with a lone high surrogate
        // (accepted v1 assumption, documented in the KDoc).
        val sample =
            ByteArray(601) { index ->
                if (index == 0) {
                    0x61.toByte()
                } else {
                    SMILE[(index - 1) % 4].toByte()
                }
            }
        val report = CharsetLab.decodeReport(sample, "UTF-8")
        assertTrue(report.success)
        assertEquals(-1, report.replacementCount)
        assertEquals(CharsetLab.PREVIEW_CHARS, report.preview.length)
        assertEquals('a', report.preview[0])
        assertTrue(Character.isHighSurrogate(report.preview[report.preview.length - 1]))
    }

    // ---------------------------------------------------- detection reports

    @Test
    fun detectionEmptyReportsNulls() {
        val report = CharsetLab.detectionReport(ByteArray(0))
        assertEquals(0, report.byteCount)
        assertFalse(report.bom.hasBom)
        assertNull(report.bom.bomName)
        assertNull(report.detectedName)
        assertNull(report.effectiveName)
    }

    @Test
    fun detectionUtf8BomFile() {
        val sample = bytes(0xEF, 0xBB, 0xBF, 0x68, 0x69)
        val report = CharsetLab.detectionReport(sample)
        assertEquals(5, report.byteCount)
        assertTrue(report.bom.hasBom)
        assertEquals("UTF-8", report.bom.bomName)
        assertEquals("UTF-8", report.detectedName)
        assertEquals("UTF-8", report.effectiveName)
        assertEquals(EncodingDetector.detectName(sample), report.detectedName)
    }

    @Test
    fun detectionUtf16LeBomFile() {
        val sample = bytes(0xFF, 0xFE, 0x68, 0x00, 0x69, 0x00)
        val report = CharsetLab.detectionReport(sample)
        assertEquals("UTF-16LE", report.bom.bomName)
        assertEquals("UTF-16LE", report.effectiveName)
        assertEquals(EncodingDetector.detectName(sample), report.detectedName)
    }

    @Test
    fun detectionUtf32LeWins() {
        // FF FE 00 00 ... must surface as UTF-32LE, never UTF-16LE.
        val sample = bytes(0xFF, 0xFE, 0x00, 0x00, 0x68, 0x00, 0x00, 0x00)
        val report = CharsetLab.detectionReport(sample)
        assertEquals("UTF-32LE", report.bom.bomName)
        assertEquals("UTF-32LE", report.effectiveName)
        assertEquals(EncodingDetector.detectName(sample), report.detectedName)
    }

    @Test
    fun detectionPlainAsciiPassthrough() {
        // Pinned against juniversalchardet 2.5.0: pure ASCII reports
        // "US-ASCII"; detectionReport passes it through without inventing
        // defaults, and there is no BOM to override it.
        val sample = bytes(0x68, 0x65, 0x6C, 0x6C, 0x6F)
        val report = CharsetLab.detectionReport(sample)
        assertEquals(5, report.byteCount)
        assertFalse(report.bom.hasBom)
        assertEquals("US-ASCII", report.detectedName)
        assertEquals("US-ASCII", report.effectiveName)
        assertEquals(EncodingDetector.detectName(sample), report.detectedName)
    }

    @Test
    fun detectionBomLessUtf8() {
        val sample = bytes(0x68, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F)
        val report = CharsetLab.detectionReport(sample)
        assertFalse(report.bom.hasBom)
        assertEquals("UTF-8", report.detectedName)
        assertEquals("UTF-8", report.effectiveName)
        assertEquals(EncodingDetector.detectName(sample), report.detectedName)
    }

    @Test
    fun detectionCyrillicPassthrough() {
        // windows-1251 bytes: the library verdict flows through verbatim.
        val sample = bytes(0xCF, 0xF0, 0xE8)
        val report = CharsetLab.detectionReport(sample)
        assertFalse(report.bom.hasBom)
        assertEquals("WINDOWS-1251", report.detectedName)
        assertEquals("WINDOWS-1251", report.effectiveName)
        assertEquals(EncodingDetector.detectName(sample), report.detectedName)
    }

    // ------------------------------------------------------------ constants

    @Test
    fun constantsMatchSpec() {
        assertEquals(200, CharsetLab.PREVIEW_CHARS)
        assertEquals(32, CharsetLab.HEX_BYTES)
    }

    // --------------------------------------------------------------- helpers

    private fun singleEncode(
        text: String,
        charsetName: String,
    ): EncodeReport = CharsetLab.encodeReports(text, listOf(charsetName)).single()
}
