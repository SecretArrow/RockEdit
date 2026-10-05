package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [SplitSessionCodec] (v0.17.0): encode/decode round
 * trips (unicode, quotes, newlines, tabs, CRLF, emoji surrogate pairs), null
 * and blank URI handling, dirty flag persistence, structural corruption
 * (malformed JSON, wrong array lengths, non-object elements), charset
 * sanitization (unsupported/blank/illegal to UTF-8, valid kept), name
 * sanitization, per-pane truncation with the documented marker, and the
 * combined-size encode cap.
 */
class SplitSessionStateTest {
    private fun pane(
        uri: String? = null,
        name: String = "",
        charsetName: String = "UTF-8",
        savedText: String = "",
        wasDirty: Boolean = false,
    ): SplitPaneState = SplitPaneState(uri, name, charsetName, savedText, wasDirty)

    /** Asserts encode succeeded and returns the JSON (no raw non-null assertion). */
    private fun encoded(
        a: SplitPaneState,
        b: SplitPaneState,
    ): String {
        val json = SplitSessionCodec.encode(a, b)
        assertNotNull("encode returned null", json)
        return json ?: ""
    }

    private fun assertEmptySession(
        a: SplitPaneState,
        b: SplitPaneState,
    ) {
        assertEquals(SplitSessionCodec.empty(), a)
        assertEquals(SplitSessionCodec.empty(), b)
    }

    // ---------------------------------------------------------- defaults

    @Test
    fun emptyHasDocumentedDefaults() {
        val e = SplitSessionCodec.empty()
        assertNull(e.uri)
        assertEquals("", e.name)
        assertEquals("UTF-8", e.charsetName)
        assertEquals("", e.savedText)
        assertFalse(e.wasDirty)
    }

    // -------------------------------------------------------- round trips

    @Test
    fun encodeDecodeRoundTripWithUnicodeQuotesAndNewlines() {
        val text = "line1\n\"quoted\" \\ back\ttab – 中文\nline3"
        val a =
            pane(
                uri = "content://a/doc1",
                name = "one.txt",
                savedText = text,
                wasDirty = true,
            )
        val b =
            pane(
                name = "second",
                charsetName = "Shift_JIS",
                savedText = "b\r\ntext",
            )
        val (ra, rb) = SplitSessionCodec.decode(encoded(a, b))
        assertEquals(a, ra)
        assertEquals(b, rb)
    }

    @Test
    fun encodeDecodeRoundTripPreservesDirtyFlags() {
        val a = pane(name = "a", savedText = "ta", wasDirty = true)
        val b = pane(name = "b", savedText = "tb", wasDirty = false)
        val (ra, rb) = SplitSessionCodec.decode(encoded(a, b))
        assertTrue(ra.wasDirty)
        assertFalse(rb.wasDirty)
        assertEquals("ta", ra.savedText)
        assertEquals("tb", rb.savedText)
    }

    @Test
    fun encodeDecodeRoundTripPreservesNullUri() {
        val a = pane(uri = null, name = "inmem", savedText = "s")
        val (ra, _) = SplitSessionCodec.decode(encoded(a, SplitSessionCodec.empty()))
        assertNull(ra.uri)
        assertEquals("inmem", ra.name)
    }

    @Test
    fun encodeBlankUriRestoresAsNull() {
        val a = pane(uri = "", name = "n")
        val json = encoded(a, SplitSessionCodec.empty())
        assertTrue(json.contains("\"uri\":\"\""))
        val (ra, _) = SplitSessionCodec.decode(json)
        assertNull(ra.uri)
    }

    @Test
    fun encodeDecodeRoundTripPreservesCrlf() {
        val text = "a\r\nb\r\rc\n"
        val s = pane(savedText = text, wasDirty = true)
        val (ra, _) = SplitSessionCodec.decode(encoded(s, SplitSessionCodec.empty()))
        assertEquals(text, ra.savedText)
    }

    @Test
    fun roundTripSurvivesEmojiSurrogatePairs() {
        val text = "code \uD83D\uDE00 fun \uD83E\uDD16"
        val s = pane(savedText = text)
        val (ra, _) = SplitSessionCodec.decode(encoded(s, SplitSessionCodec.empty()))
        assertEquals(text, ra.savedText)
    }

    @Test
    fun encodeOutputIsCompactJsonArray() {
        val json =
            encoded(
                pane(name = "n", savedText = "x", wasDirty = true),
                SplitSessionCodec.empty(),
            )
        assertTrue(json.startsWith("[{"))
        assertTrue(json.endsWith("}]"))
        assertFalse(json.contains(": "))
        assertFalse(json.contains(", "))
        assertTrue(json.contains("\"dirty\":true"))
    }

    // ---------------------------------------------------------- caps

    @Test
    fun encodeReturnsNullWhenCombinedExceedsSessionCap() {
        val a = pane(savedText = "x".repeat(1_200_000))
        val b = pane(savedText = "y".repeat(900_000))
        assertNull(SplitSessionCodec.encode(a, b))
    }

    @Test
    fun encodeAcceptsCombinedExactlyAtSessionCap() {
        val a = pane(savedText = "x".repeat(SplitSessionCodec.MAX_TEXT_CHARS))
        val b = pane(savedText = "y".repeat(SplitSessionCodec.MAX_TEXT_CHARS))
        assertNotNull(SplitSessionCodec.encode(a, b))
    }

    @Test
    fun encodeAcceptsCombinedJustUnderCap() {
        val a = pane(savedText = "x".repeat(SplitSessionCodec.MAX_TEXT_CHARS - 1))
        val b = pane(savedText = "y".repeat(SplitSessionCodec.MAX_TEXT_CHARS))
        assertNotNull(SplitSessionCodec.encode(a, b))
    }

    // ------------------------------------------------------- corruption

    @Test
    fun decodeMalformedJsonYieldsEmptySession() {
        val (a, b) = SplitSessionCodec.decode("not json {{{")
        assertEmptySession(a, b)
    }

    @Test
    fun decodeBlankJsonYieldsEmptySession() {
        val (a, b) = SplitSessionCodec.decode("   ")
        assertEmptySession(a, b)
    }

    @Test
    fun decodeNonArrayRootYieldsEmptySession() {
        val (a, b) = SplitSessionCodec.decode("{\"uri\":\"content://x\"}")
        assertEmptySession(a, b)
    }

    @Test
    fun decodeArrayLengthOneYieldsEmptySession() {
        val (a, b) = SplitSessionCodec.decode("[{\"name\":\"x\"}]")
        assertEmptySession(a, b)
    }

    @Test
    fun decodeArrayLengthThreeYieldsEmptySession() {
        val (a, b) = SplitSessionCodec.decode("[{},{},{}]")
        assertEmptySession(a, b)
    }

    @Test
    fun decodeNonObjectElementYieldsEmptySession() {
        val (a, b) = SplitSessionCodec.decode("[\"nope\", {}]")
        assertEmptySession(a, b)
        val (c, d) = SplitSessionCodec.decode("[{}, \"nope\"]")
        assertEmptySession(c, d)
    }

    @Test
    fun decodeMissingFieldsYieldsSanitizedDefaults() {
        val (a, b) = SplitSessionCodec.decode("[{}, {}]")
        assertNull(a.uri)
        assertEquals(SplitSessionCodec.UNTITLED, a.name)
        assertEquals("UTF-8", a.charsetName)
        assertEquals("", a.savedText)
        assertFalse(a.wasDirty)
        assertEquals(a, b)
    }

    // ------------------------------------------------ charsets on decode

    @Test
    fun decodeUnsupportedCharsetFallsBackToUtf8() {
        val (a, _) =
            SplitSessionCodec.decode(
                "[{\"charset\":\"NOT-A-CHARSET\",\"text\":\"kept\"}, {}]",
            )
        assertEquals("UTF-8", a.charsetName)
        assertEquals("kept", a.savedText)
    }

    @Test
    fun decodeBlankCharsetFallsBackToUtf8() {
        val (a, _) = SplitSessionCodec.decode("[{\"charset\":\"\"}, {}]")
        assertEquals("UTF-8", a.charsetName)
    }

    @Test
    fun decodeIllegalCharsetNameFallsBackToUtf8() {
        val (a, _) = SplitSessionCodec.decode("[{\"charset\":\"bad name\"}, {}]")
        assertEquals("UTF-8", a.charsetName)
    }

    @Test
    fun decodeKeepsValidNonDefaultCharset() {
        val (a, _) = SplitSessionCodec.decode("[{\"charset\":\"Shift_JIS\"}, {}]")
        assertEquals("Shift_JIS", a.charsetName)
    }

    @Test
    fun decodeCoercesNonStringNameLikeOrgJson() {
        val (a, _) = SplitSessionCodec.decode("[{\"name\": 5}, {}]")
        assertEquals("5", a.name)
    }

    // ------------------------------------------------- truncation/decode

    @Test
    fun decodeTruncatesOversizePaneTextWithMarker() {
        val raw = "x".repeat(1_200_000)
        val (a, _) = SplitSessionCodec.decode("[{\"text\":\"$raw\"}, {}]")
        assertEquals(SplitSessionCodec.MAX_TEXT_CHARS, a.savedText.length)
        assertTrue(a.savedText.endsWith(SplitSessionCodec.TRUNCATION_MARKER))
        assertTrue(a.savedText.startsWith("xxx"))
    }

    @Test
    fun decodeKeepsTextAtExactPaneCap() {
        val raw = "y".repeat(SplitSessionCodec.MAX_TEXT_CHARS)
        val (a, _) = SplitSessionCodec.decode("[{\"text\":\"$raw\"}, {}]")
        assertEquals(raw, a.savedText)
    }

    // ------------------------------------------------------- sanitize

    @Test
    fun sanitizeBlankNameBecomesUntitled() {
        assertEquals(SplitSessionCodec.UNTITLED, SplitSessionCodec.sanitize(pane(name = "")).name)
        assertEquals(
            SplitSessionCodec.UNTITLED,
            SplitSessionCodec.sanitize(pane(name = "   ")).name,
        )
    }

    @Test
    fun sanitizeKeepsNonBlankNameVerbatim() {
        assertEquals("readme.md", SplitSessionCodec.sanitize(pane(name = "readme.md")).name)
    }

    @Test
    fun sanitizeBlankUriBecomesNull() {
        assertNull(SplitSessionCodec.sanitize(pane(uri = "  ")).uri)
        assertNull(SplitSessionCodec.sanitize(pane(uri = "")).uri)
    }

    @Test
    fun sanitizeKeepsContentUriVerbatim() {
        assertEquals(
            "content://docs/abc",
            SplitSessionCodec.sanitize(pane(uri = "content://docs/abc")).uri,
        )
    }

    @Test
    fun sanitizeUnsupportedAndBlankCharsetsBecomeUtf8() {
        val bad = SplitSessionCodec.sanitize(pane(charsetName = "NOT-A-CHARSET"))
        val blank = SplitSessionCodec.sanitize(pane(charsetName = ""))
        val illegal = SplitSessionCodec.sanitize(pane(charsetName = "bad name"))
        assertEquals("UTF-8", bad.charsetName)
        assertEquals("UTF-8", blank.charsetName)
        assertEquals("UTF-8", illegal.charsetName)
    }

    @Test
    fun sanitizeValidCharsetIsKept() {
        val kept = SplitSessionCodec.sanitize(pane(charsetName = "windows-1251"))
        assertEquals("windows-1251", kept.charsetName)
    }

    @Test
    fun sanitizeTruncatesWithMarkerAtExactCap() {
        val big = "z".repeat(SplitSessionCodec.MAX_TEXT_CHARS + 5)
        val over = SplitSessionCodec.sanitize(pane(savedText = big))
        assertEquals(SplitSessionCodec.MAX_TEXT_CHARS, over.savedText.length)
        assertTrue(over.savedText.endsWith(SplitSessionCodec.TRUNCATION_MARKER))
        val atCap =
            SplitSessionCodec.sanitize(
                pane(savedText = "z".repeat(SplitSessionCodec.MAX_TEXT_CHARS)),
            )
        assertFalse(atCap.savedText.endsWith(SplitSessionCodec.TRUNCATION_MARKER))
        assertEquals(SplitSessionCodec.MAX_TEXT_CHARS, atCap.savedText.length)
    }

    @Test
    fun sanitizeKeepsDirtyFlag() {
        assertTrue(SplitSessionCodec.sanitize(pane(wasDirty = true)).wasDirty)
        assertFalse(SplitSessionCodec.sanitize(pane()).wasDirty)
    }
}
