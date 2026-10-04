package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Per-branch tests for [HexDump] (v0.13.0): dump layout (empty, boundaries,
 * padding, grouping, offsets, uppercase), parse tolerance and positional
 * errors, the 1 MiB cap, binary sniffing and every DumpOptions failure.
 */
class HexDumpTest {

    private fun lines(result: HexDump.ResultLines): List<HexDump.DumpLine> {
        assertTrue("expected Success, was $result", result is HexDump.ResultLines.Success)
        return (result as HexDump.ResultLines.Success).lines
    }

    private fun fail(result: HexDump.ResultLines): HexDump.ResultLines.Failure {
        assertTrue("expected Failure, was $result", result is HexDump.ResultLines.Failure)
        return result as HexDump.ResultLines.Failure
    }

    private fun bytes(result: HexDump.ParseResult): ByteArray {
        assertTrue("expected Success, was $result", result is HexDump.ParseResult.Success)
        return (result as HexDump.ParseResult.Success).bytes
    }

    private fun parseFail(result: HexDump.ParseResult): HexDump.ParseResult.Failure {
        assertTrue("expected Failure, was $result", result is HexDump.ParseResult.Failure)
        return result as HexDump.ParseResult.Failure
    }

    private fun expectIllegal(block: () -> Unit): String {
        return try {
            block()
            fail("expected IllegalArgumentException")
            error("unreachable: expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            expected.message ?: ""
        }
    }

    // ------------------------------------------------------------- dump

    @Test
    fun emptyArrayDumpsToZeroLines() {
        assertTrue(lines(HexDump.toDumpLines(ByteArray(0))).isEmpty())
        assertEquals("", HexDump.toDumpText(ByteArray(0)))
    }

    @Test
    fun singleByteDumpsOneAlignedLine() {
        val result = lines(HexDump.toDumpLines(byteArrayOf(0x48)))
        assertEquals(1, result.size)
        assertEquals(0L, result[0].offset)
        assertEquals("48", result[0].hexText)
        assertEquals("H", result[0].asciiText)
    }

    @Test
    fun exactlyBytesPerLineFitsOneLine() {
        val data = ByteArray(16) { it.toByte() }
        val result = lines(HexDump.toDumpLines(data))
        assertEquals(1, result.size)
        assertEquals("0001 0203 0405 0607 0809 0a0b 0c0d 0e0f", result[0].hexText)
        assertEquals(39, result[0].hexText.length)
        assertEquals("................", result[0].asciiText)
        assertEquals(
            "00000000  0001 0203 0405 0607 0809 0a0b 0c0d 0e0f  ................",
            HexDump.toDumpText(data)
        )
    }

    @Test
    fun bytesPerLinePlusOnePadsTheLastLine() {
        val data = ByteArray(17) { it.toByte() }
        val result = lines(HexDump.toDumpLines(data))
        assertEquals(2, result.size)
        assertEquals(16L, result[1].offset)
        assertEquals("10", result[1].hexText.trimEnd())
        assertEquals(result[0].hexText.length, result[1].hexText.length)
        assertEquals(result[0].asciiText.length, result[1].asciiText.length)
        assertEquals("." + " ".repeat(15), result[1].asciiText)
    }

    @Test
    fun asciiBoundaryCharactersRenderExactly() {
        val data = byteArrayOf(0x00, 0x1F, 0x20, 0x7E, 0x7F, 0xFF.toByte())
        val ascii = lines(HexDump.toDumpLines(data))[0].asciiText
        assertEquals(".. ~..", ascii.take(6))
        assertEquals(".. ~.." + " ".repeat(10), ascii)
    }

    @Test
    fun offsetBaseShiftsEveryLine() {
        val options = HexDump.DumpOptions(offsetBase = 16)
        val result = lines(HexDump.toDumpLines(byteArrayOf(1, 2), options))
        assertEquals(16L, result[0].offset)
        assertTrue(HexDump.toDumpText(byteArrayOf(1, 2), options).startsWith("00000010  "))
    }

    @Test
    fun uppercaseOptionAppliesToHexAndOffsets() {
        val data = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
        val options = HexDump.DumpOptions(uppercase = true, offsetBase = 0xCAFE)
        val line = lines(HexDump.toDumpLines(data, options))[0]
        assertEquals("AB CD", line.hexText)
        assertTrue(HexDump.toDumpText(data, options).startsWith("0000CAFE  "))
    }

    @Test
    fun groupingStaysAlignedAcrossPartialLines() {
        val data = ByteArray(20) { it.toByte() }
        val options = HexDump.DumpOptions(bytesPerLine = 8, groupSize = 3)
        val result = lines(HexDump.toDumpLines(data, options))
        assertEquals(3, result.size)
        assertTrue(result.all { it.hexText.length == 18 })
        assertEquals("101112 13", result[2].hexText.trimEnd())
    }

    @Test
    fun groupSizeBoundariesEmitExpectedSpacing() {
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val wide = HexDump.DumpOptions(bytesPerLine = 4, groupSize = 4)
        val paired = HexDump.DumpOptions(bytesPerLine = 4, groupSize = 2)
        assertEquals("deadbeef", lines(HexDump.toDumpLines(data, wide))[0].hexText)
        assertEquals("dead beef", lines(HexDump.toDumpLines(data, paired))[0].hexText)
    }

    @Test
    fun offsetWidthGrowsBeyondEightDigitsWhenNeeded() {
        val options = HexDump.DumpOptions(offsetBase = 0x1_0000_0000L)
        val text = HexDump.toDumpText(byteArrayOf(1), options)
        assertTrue(text.startsWith("100000000  "))
    }

    @Test
    fun toDumpTextHasNoTrailingNewline() {
        val text = HexDump.toDumpText(ByteArray(17) { it.toByte() })
        assertEquals(1, text.count { it == '\n' })
        assertFalse(text.endsWith("\n"))
    }

    @Test
    fun toDumpLinesRejectsOversizeInput() {
        val result = fail(HexDump.toDumpLines(ByteArray(HexDump.MAX_BYTES + 1)))
        assertEquals(HexDump.ErrorCode.TOO_LARGE, result.code)
        assertTrue(result.message.contains("1048577"))
        assertTrue(result.message.contains("1048576"))
    }

    @Test
    fun toDumpTextThrowsForOversizeAndInvalidOptions() {
        val oversize = expectIllegal { HexDump.toDumpText(ByteArray(HexDump.MAX_BYTES + 1)) }
        assertTrue(oversize.contains("1048577"))
        val invalid = expectIllegal {
            HexDump.toDumpText(byteArrayOf(1), HexDump.DumpOptions(bytesPerLine = 3))
        }
        assertTrue(invalid.contains("bytesPerLine"))
    }

    // ------------------------------------------------------------ options

    @Test
    fun optionsBoundaryValuesAreAccepted() {
        val min = HexDump.DumpOptions(bytesPerLine = 4, groupSize = 4)
        val max = HexDump.DumpOptions(bytesPerLine = 64, groupSize = 64)
        assertEquals(1, lines(HexDump.toDumpLines(byteArrayOf(1), min)).size)
        assertEquals(1, lines(HexDump.toDumpLines(byteArrayOf(1), max)).size)
    }

    @Test
    fun bytesPerLineBelowMinimumIsRejected() {
        val options = HexDump.DumpOptions(bytesPerLine = 3)
        val result = fail(HexDump.toDumpLines(byteArrayOf(1), options))
        assertEquals(HexDump.ErrorCode.BYTES_PER_LINE, result.code)
        assertTrue(result.message.contains("bytesPerLine is 3"))
        assertTrue(result.message.contains("4..64"))
    }

    @Test
    fun bytesPerLineAboveMaximumIsRejected() {
        val options = HexDump.DumpOptions(bytesPerLine = 65)
        val result = fail(HexDump.toDumpLines(byteArrayOf(1), options))
        assertEquals(HexDump.ErrorCode.BYTES_PER_LINE, result.code)
        assertTrue(result.message.contains("bytesPerLine is 65"))
    }

    @Test
    fun zeroGroupSizeIsRejected() {
        val result = fail(HexDump.toDumpLines(byteArrayOf(1), HexDump.DumpOptions(groupSize = 0)))
        assertEquals(HexDump.ErrorCode.GROUP_SIZE, result.code)
        assertTrue(result.message.contains("groupSize is 0"))
    }

    @Test
    fun groupSizeAboveBytesPerLineIsRejected() {
        val options = HexDump.DumpOptions(bytesPerLine = 16, groupSize = 17)
        val result = fail(HexDump.toDumpLines(byteArrayOf(1), options))
        assertEquals(HexDump.ErrorCode.GROUP_SIZE, result.code)
        assertTrue(result.message.contains("1..16"))
    }

    @Test
    fun negativeOffsetBaseIsRejected() {
        val result = fail(HexDump.toDumpLines(byteArrayOf(1), HexDump.DumpOptions(offsetBase = -1)))
        assertEquals(HexDump.ErrorCode.OFFSET_BASE, result.code)
        assertTrue(result.message.contains("offsetBase is -1"))
        assertTrue(result.message.contains(">= 0"))
    }

    // ------------------------------------------------------------- parse

    @Test
    fun parseRoundTripsToDumpText() {
        val data = ByteArray(300) { ((it * 37 + 11) and 0xFF).toByte() }
        val reparsed = bytes(HexDump.parse(HexDump.toDumpText(data)))
        assertTrue(data.contentEquals(reparsed))
    }

    @Test
    fun parseRoundTripsUppercaseDumps() {
        val data = byteArrayOf(0x00, 0x7E, 0x7F, 0xFF.toByte())
        val dump = HexDump.toDumpText(data, HexDump.DumpOptions(uppercase = true))
        assertTrue(dump.contains("7E 7F"))
        assertTrue(data.contentEquals(bytes(HexDump.parse(dump))))
    }

    @Test
    fun parseToleratesMissingAsciiColumn() {
        val result = bytes(HexDump.parse("00000000 48 65 6c 6c 6f"))
        assertEquals("Hello", String(result, Charsets.ISO_8859_1))
    }

    @Test
    fun parseSkipsBlankAndJunkLines() {
        val dump = "hello world\n\nzz 00\n0123456789abcdef0 22\nff\n00000000 68 69\n"
        assertTrue(byteArrayOf(0x68, 0x69).contentEquals(bytes(HexDump.parse(dump))))
    }

    @Test
    fun parseAcceptsShortAndUppercaseOffsets() {
        assertTrue(byteArrayOf(0x48, 0x65).contentEquals(bytes(HexDump.parse("0 48 65"))))
        val dump = "ABCDEF0  DE AD"
        val expected = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        assertTrue(expected.contentEquals(bytes(HexDump.parse(dump))))
    }

    @Test
    fun parseStripsCarriageReturns() {
        val result = bytes(HexDump.parse("00000000 48 49\r\n00000001 4a"))
        assertTrue(byteArrayOf(0x48, 0x49, 0x4A).contentEquals(result))
    }

    @Test
    fun parseReportsOddHexWithLineAndColumn() {
        val result = parseFail(HexDump.parse("00000000 68 6\n00000010 69"))
        assertEquals(HexDump.ErrorCode.ODD_HEX, result.code)
        assertTrue(result.message.contains("line 1"))
        assertTrue(result.message.contains("column 12"))
        assertTrue(result.message.contains("unpaired digit '6'"))
    }

    @Test
    fun parseReportsInvalidHexWithPositionAndChar() {
        val result = parseFail(HexDump.parse("00000000 6g"))
        assertEquals(HexDump.ErrorCode.INVALID_HEX, result.code)
        assertTrue(result.message.contains("line 1"))
        assertTrue(result.message.contains("column 10"))
        assertTrue(result.message.contains("'g'"))
    }

    @Test
    fun parseRejectsTabsInsideHexColumn() {
        val result = parseFail(HexDump.parse("00000000 48\t65"))
        assertEquals(HexDump.ErrorCode.INVALID_HEX, result.code)
        assertTrue(result.message.contains("column 11"))
    }

    @Test
    fun parseEmptyAndBlankInputsFail() {
        assertEquals(HexDump.ErrorCode.EMPTY, parseFail(HexDump.parse("")).code)
        assertEquals(HexDump.ErrorCode.EMPTY, parseFail(HexDump.parse("   \n\t \n")).code)
    }

    @Test
    fun parseOfOffsetOnlyLinesFailsAsEmpty() {
        val result = parseFail(HexDump.parse("00000000 \n00000010 "))
        assertEquals(HexDump.ErrorCode.EMPTY, result.code)
        assertTrue(result.message.contains("no hex data"))
    }

    @Test
    fun parseFailsBeyondTinyCapAndPassesAtExactCap() {
        val dump = "00000000 01 02 03 04 05"
        val over = parseFail(HexDump.parseWithCap(dump, 4))
        assertEquals(HexDump.ErrorCode.TOO_LARGE, over.code)
        assertTrue(over.message.contains("at least 5"))
        assertTrue(over.message.contains("cap is 4"))
        assertEquals(5, bytes(HexDump.parseWithCap(dump, 5)).size)
        assertEquals(5, bytes(HexDump.parse(dump)).size)
    }

    @Test
    fun parseWithCapRejectsNegativeCap() {
        val message = expectIllegal { HexDump.parseWithCap("00000000 01", -1) }
        assertTrue(message.contains("cap is -1"))
    }

    // ------------------------------------------------------ binary sniff

    @Test
    fun nulByteAtStartMeansBinary() {
        assertTrue(HexDump.isProbablyBinary(byteArrayOf(0, 0x41)))
    }

    @Test
    fun nulByteInsideSniffWindowMeansBinary() {
        val data = ByteArray(HexDump.BINARY_SNIFF_BYTES) { 0x41 }
        data[HexDump.BINARY_SNIFF_BYTES - 1] = 0
        assertTrue(HexDump.isProbablyBinary(data))
    }

    @Test
    fun nulByteAfterSniffWindowMeansText() {
        val data = ByteArray(HexDump.BINARY_SNIFF_BYTES + 1) { 0x41 }
        data[HexDump.BINARY_SNIFF_BYTES] = 0
        assertFalse(HexDump.isProbablyBinary(data))
    }

    @Test
    fun plainTextWithoutNulIsNotBinary() {
        assertFalse(HexDump.isProbablyBinary("just text".toByteArray()))
    }

    @Test
    fun emptyArrayIsNeverBinary() {
        assertFalse(HexDump.isProbablyBinary(ByteArray(0)))
    }
}
