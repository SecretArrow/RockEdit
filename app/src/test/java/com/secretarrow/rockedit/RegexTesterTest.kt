package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.RegexTester
import com.secretarrow.rockedit.core.RegexTester.Flags
import com.secretarrow.rockedit.core.RegexTester.RegexOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [RegexTester] (v0.11.0): blank/invalid patterns,
 * zero-length match protection, capture groups (including non-participating
 * optional groups), caps + truncation, and each flag independently.
 */
class RegexTesterTest {

    private fun found(pattern: String, text: String, flags: Flags = Flags(), cap: Int = RegexTester.DEFAULT_MAX_MATCHES): RegexTester.RegexResult {
        val outcome = RegexTester.run(pattern, text, flags, cap)
        assertTrue("expected Found, was $outcome", outcome is RegexOutcome.Found)
        return (outcome as RegexOutcome.Found).result
    }

    private fun failure(pattern: String, text: String = "abc"): RegexTester.RegexError {
        val outcome = RegexTester.run(pattern, text)
        assertTrue("expected Failure, was $outcome", outcome is RegexOutcome.Failure)
        return (outcome as RegexOutcome.Failure).error
    }

    // ------------------------------------------------------------- contract

    @Test
    fun blankPatternFails() {
        assertEquals(RegexTester.ErrorCode.EMPTY_PATTERN, failure("").code)
        assertEquals(RegexTester.ErrorCode.EMPTY_PATTERN, failure("   ").code)
    }

    @Test
    fun invalidPatternIsParseErrorNotCrash() {
        assertEquals(RegexTester.ErrorCode.PARSE_ERROR, failure("([").code)
        assertEquals(RegexTester.ErrorCode.PARSE_ERROR, failure("*x").code)
        assertEquals(RegexTester.ErrorCode.PARSE_ERROR, failure("a{2,1}").code)
    }

    @Test
    fun oversizedTextRejected() {
        val big = "a".repeat(RegexTester.MAX_TEXT_CHARS + 1)
        assertEquals(RegexTester.ErrorCode.INPUT_TOO_LARGE, failure("a", big).code)
    }

    // ------------------------------------------------------------ matching

    @Test
    fun noMatchesOnEmptyTextIsFoundNotError() {
        val result = found("a", "")
        assertEquals(0, result.matches.size)
        assertFalse(result.matchesTruncated)
    }

    @Test
    fun matchOffsetsAndGroups() {
        val result = found("a(b)c", "xxabcxx")
        assertEquals(1, result.matches.size)
        val m = result.matches[0]
        assertEquals(2, m.start)
        assertEquals(5, m.end)
        assertEquals(1, result.groupCount)
        assertEquals("abc", m.groups[0].text)
        assertEquals("b", m.groups[1].text)
        assertEquals(3, m.groups[1].start)
        assertEquals(4, m.groups[1].end)
    }

    @Test
    fun optionalGroupThatDidNotParticipateIsNull() {
        val result = found("(a)?b", "b")
        assertEquals(1, result.matches.size)
        assertNull(result.matches[0].groups[1].text)
        assertEquals(-1, result.matches[0].groups[1].start)
    }

    @Test
    fun zeroLengthMatchesTerminateWithForcedProgress() {
        // "a*" on "bb": empty match at 0, 1, 2 -> exactly 3, no hang.
        val result = found("a*", "bb")
        assertEquals(3, result.matches.size)
        assertEquals(0, result.matches[0].start)
        assertEquals(1, result.matches[1].start)
        assertEquals(2, result.matches[2].start)
    }

    @Test
    fun capTruncatesAndReports() {
        val result = found("a", "aaaa", cap = 2)
        assertEquals(2, result.matches.size)
        assertTrue(result.matchesTruncated)
    }

    @Test
    fun capExactlyReachedWithoutFurtherHitsIsNotTruncated() {
        val result = found("a", "aa", cap = 2)
        assertEquals(2, result.matches.size)
        assertFalse(result.matchesTruncated)
    }

    // --------------------------------------------------------------- flags

    @Test
    fun ignoreCaseFlag() {
        assertEquals(0, found("HELLO", "say hello now").matches.size)
        assertEquals(1, found("HELLO", "say hello now", Flags(ignoreCase = true)).matches.size)
    }

    @Test
    fun multilineFlag() {
        assertEquals(0, found("^b", "a\nb").matches.size)
        assertEquals(1, found("^b", "a\nb", Flags(multiline = true)).matches.size)
    }

    @Test
    fun dotAllFlag() {
        assertEquals(0, found("a.b", "a\nb").matches.size)
        assertEquals(1, found("a.b", "a\nb", Flags(dotAll = true)).matches.size)
    }
}
