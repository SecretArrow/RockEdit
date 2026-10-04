package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.TextUtilities
import com.secretarrow.rockedit.core.TextUtilities.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [TextUtilities] (v0.11.0). Every branch of the
 * defensive contract is exercised: blank input, size cap, malformed
 * Base64/URL/JSON input with positions, HTML entity edge cases, line ops
 * with CRLF, and case transforms.
 */
class TextUtilitiesTest {

    private fun ok(op: Op, input: String): TextUtilities.TextResult.Success {
        val result = TextUtilities.run(op, input)
        assertTrue("expected Success, was $result", result is TextUtilities.TextResult.Success)
        return result as TextUtilities.TextResult.Success
    }

    private fun fail(op: Op, input: String): TextUtilities.TextError {
        val result = TextUtilities.run(op, input)
        assertTrue("expected Failure, was $result", result is TextUtilities.TextResult.Failure)
        return (result as TextUtilities.TextResult.Failure).error
    }

    // ------------------------------------------------------------ contract

    @Test
    fun blankInputIsSkippedForEveryOp() {
        for (op in Op.values()) {
            val result = TextUtilities.run(op, "   \n\t")
            assertTrue("op=$op expected Skipped", result is TextUtilities.TextResult.Skipped)
        }
    }

    @Test
    fun oversizedInputFailsFast() {
        val big = "a".repeat(TextUtilities.MAX_INPUT_CHARS + 1)
        val result = TextUtilities.run(Op.MD5, big)
        assertTrue(result is TextUtilities.TextResult.Failure)
        assertEquals(TextUtilities.ErrorCode.INPUT_TOO_LARGE, (result as TextUtilities.TextResult.Failure).error.code)
    }

    @Test
    fun changedFlagIsFalseWhenOutputEqualsInput() {
        assertFalse(ok(Op.URL_ENCODE, "abc").changed)
        assertTrue(ok(Op.URL_ENCODE, "a b").changed)
    }

    // -------------------------------------------------------------- base64

    @Test
    fun base64RoundTrip() {
        val encoded = ok(Op.BASE64_ENCODE, "Hello").text
        assertEquals("SGVsbG8=", encoded)
        assertEquals("Hello", ok(Op.BASE64_DECODE, encoded).text)
    }

    @Test
    fun base64UrlSafeAlphabetDecodes() {
        // " subjects?" -> use a URL-safe encoded value: byte 0xFB needs -_ form.
        val decoded = ok(Op.BASE64_DECODE, "-_8").text
        assertEquals(1, decoded.length) // structural: decodes without error
    }

    @Test
    fun base64UnpaddedAccepted() {
        assertEquals("hi", ok(Op.BASE64_DECODE, "aGk").text)
        assertEquals("hi", ok(Op.BASE64_DECODE, "aGk=").text)
    }

    @Test
    fun base64InvalidCharReportsPosition() {
        assertEquals(2, fail(Op.BASE64_DECODE, "SG*s").position)
    }

    @Test
    fun base64InvalidCharAtSecondPosition() {
        val error = fail(Op.BASE64_DECODE, "ab!c")
        assertEquals(2, error.position)
        assertEquals(TextUtilities.ErrorCode.PARSE_ERROR, error.code)
    }

    @Test
    fun base64MixedAlphabetsRejected() {
        val error = fail(Op.BASE64_DECODE, "ab+/-_")
        assertEquals(TextUtilities.ErrorCode.PARSE_ERROR, error.code)
    }

    @Test
    fun base64BadPaddingRejected() {
        // Canonical for "aGk" is 2 pads; 1 pad is invalid.
        val error = fail(Op.BASE64_DECODE, "aGk=")
        assertEquals(TextUtilities.ErrorCode.PARSE_ERROR, error.code)
    }

    @Test
    fun base64InvalidUtf8Rejected() {
        // 0x80 0x80 is never valid UTF-8.
        val error = fail(Op.BASE64_DECODE, "gIA=")
        assertEquals(TextUtilities.ErrorCode.PARSE_ERROR, error.code)
    }

    // ----------------------------------------------------------------- url

    @Test
    fun urlRoundTrip() {
        assertEquals("a+b%26c", ok(Op.URL_ENCODE, "a b&c").text)
        assertEquals("a b&c", ok(Op.URL_DECODE, "a+b%26c").text)
    }

    @Test
    fun urlDecodeInvalidEscapeReportsPosition() {
        assertEquals(1, fail(Op.URL_DECODE, "a%zz").position)
        assertEquals(0, fail(Op.URL_DECODE, "%4").position)
        assertEquals(3, fail(Op.URL_DECODE, "abc%").position)
    }

    // ---------------------------------------------------------------- html

    @Test
    fun htmlEncodeCoversTheFiveReserved() {
        assertEquals(
            "&lt;a &amp; &quot;q&quot;&#39;&gt;",
            ok(Op.HTML_ENCODE, "<a & \"q\"'>").text
        )
    }

    @Test
    fun htmlDecodeNamedNumericAndHex() {
        assertEquals("<b> && 'x'", ok(Op.HTML_DECODE, "&lt;b&gt; &amp;&amp; &#39;x&#39;").text)
        assertEquals("\u00A9 2024", ok(Op.HTML_DECODE, "&copy; 2024").text)
        assertEquals("A", ok(Op.HTML_DECODE, "&#65;").text)
        assertEquals("A", ok(Op.HTML_DECODE, "&#x41;").text)
    }

    @Test
    fun htmlDecodeUnknownEntityStaysVerbatim() {
        assertEquals("&nosuch;", ok(Op.HTML_DECODE, "&nosuch;").text)
        assertEquals("&amp", ok(Op.HTML_DECODE, "&amp").text) // no semicolon
        assertEquals("&#55296;", ok(Op.HTML_DECODE, "&#55296;").text) // lone surrogate
        assertEquals("&#xZZ;", ok(Op.HTML_DECODE, "&#xZZ;").text)
    }

    // --------------------------------------------------------------- hashes

    @Test
    fun md5AndSha256KnownVectors() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ok(Op.MD5, "abc").text)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ok(Op.SHA256, "abc").text
        )
        assertEquals(40, ok(Op.SHA1, "abc").text.length)
    }

    // ----------------------------------------------------------------- case

    @Test
    fun caseTransforms() {
        assertEquals("helloWorldTest", ok(Op.CAMEL_CASE, "hello world-test").text)
        assertEquals("httpServer", ok(Op.CAMEL_CASE, "HTTP_Server").text)
        assertEquals("hello_world", ok(Op.SNAKE_CASE, "Hello World").text)
        assertEquals("hello-world-2", ok(Op.KEBAB_CASE, "Hello World 2").text)
    }

    @Test
    fun caseTransformWithoutWordsKeepsInput() {
        val result = ok(Op.CAMEL_CASE, "...")
        assertEquals("...", result.text)
        assertFalse(result.changed)
    }

    // ---------------------------------------------------------------- lines

    @Test
    fun sortDedupeReverse() {
        assertEquals("a\nb\nc", ok(Op.SORT_LINES_ASC, "b\na\nc").text)
        assertEquals("c\nb\na", ok(Op.SORT_LINES_DESC, "b\na\nc").text)
        assertEquals("a\nb", ok(Op.DEDUPE_LINES, "a\nb\na\nb").text)
        assertEquals("3\n2\n1", ok(Op.REVERSE_LINES, "1\n2\n3").text)
    }

    @Test
    fun lineOpsPreserveCrlf() {
        assertEquals("a\r\nb\r\nc", ok(Op.SORT_LINES_ASC, "b\r\na\r\nc").text)
        assertEquals("a\r\nb", ok(Op.DEDUPE_LINES, "a\r\nb\r\na\r\nb").text)
    }

    @Test
    fun sortKeepsEmptyAndDuplicateLines() {
        assertEquals("\n\na", ok(Op.SORT_LINES_ASC, "a\n\n").text)
    }

    // ----------------------------------------------------------------- json

    @Test
    fun jsonEscapeUnescapeRoundTrip() {
        val raw = "a\"b\\c\nd\te\u000C"
        val escaped = ok(Op.JSON_ESCAPE, raw).text
        assertEquals("a\\\"b\\\\c\\nd\\te\\f", escaped)
        assertEquals(raw, ok(Op.JSON_UNESCAPE, escaped).text)
    }

    @Test
    fun jsonEscapeControlCharBecomesUnicodeEscape() {
        assertEquals("\\u0001", ok(Op.JSON_ESCAPE, "\u0001").text)
    }

    @Test
    fun jsonUnescapeUnicodeEscapes() {
        assertEquals("A", ok(Op.JSON_UNESCAPE, "\\u0041").text)
    }

    @Test
    fun jsonUnescapeRejectsMalformedWithPosition() {
        assertEquals(0, fail(Op.JSON_UNESCAPE, "\\uZZZZ").position)
        assertEquals(0, fail(Op.JSON_UNESCAPE, "\\u00").position) // truncated
        assertEquals(1, fail(Op.JSON_UNESCAPE, "a\\x41").position)
        assertEquals(3, fail(Op.JSON_UNESCAPE, "abc\\").position) // dangling
        assertEquals(0, fail(Op.JSON_UNESCAPE, "\\uD800").position) // lone surrogate
    }
}
