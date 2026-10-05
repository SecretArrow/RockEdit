package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-branch tests for [BracketPairColorizer] (v0.15.0). */
class BracketPairColorizerTest {
    private fun offsets(brackets: List<BracketPairColorizer.ColoredBracket>) = brackets.map { it.offset }
    private fun depths(brackets: List<BracketPairColorizer.ColoredBracket>) = brackets.map { it.depth }

    @Test
    fun `nesting depths increase and decrease in document order`() {
        val text = "{ ([ ]) }"
        val result = BracketPairColorizer.colorize(text)
        assertEquals(6, result.size)
        assertEquals(listOf(0, 2, 3, 4, 6, 8), offsets(result))
        assertEquals(listOf(1, 2, 3, 3, 2, 1), depths(result))
    }

    @Test
    fun `brackets inside strings are ignored`() {
        val result = BracketPairColorizer.colorize("fn() { val s = \"{ [ (\" }")
        // Only the two real code brackets: '(' at 3 and '{' at 6, '}' at end.
        assertEquals(listOf(1, 6, 24), offsets(result))
        assertEquals(listOf(1, 2, 1), depths(result))
    }

    @Test
    fun `brackets inside char literals are ignored`() {
        val result = BracketPairColorizer.colorize("a = '(' + ']';")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `brackets inside line comments are ignored`() {
        val result = BracketPairColorizer.colorize("x(1) // ) ] } ( [")
        assertEquals(1, result.size)
        assertEquals(1, result[0].offset)
    }

    @Test
    fun `brackets inside block comments are ignored and scanner resumes`() {
        val result = BracketPairColorizer.colorize("/* ( [ ) */ x{y}")
        assertEquals(1, result.size)
        assertEquals(14, result[0].offset)
    }

    @Test
    fun `escaped quote inside string does not close it`() {
        val result = BracketPairColorizer.colorize("\"a\\\"{b\" }")
        assertEquals(1, result.size)
        assertEquals(8, result[0].offset)
    }

    @Test
    fun `unbalanced closer clamps to depth one`() {
        val result = BracketPairColorizer.colorize("} } }")
        assertEquals(listOf(1, 1, 1), depths(result))
    }

    @Test
    fun `unclosed openers keep increasing depth`() {
        val result = BracketPairColorizer.colorize("(((")
        assertEquals(listOf(1, 2, 3), depths(result))
    }

    @Test
    fun `empty text returns empty list`() {
        assertTrue(BracketPairColorizer.colorize("").isEmpty())
    }

    @Test
    fun `oversized text returns empty list`() {
        val text = "()".repeat(80_000) // 160k chars > default cap
        assertTrue(BracketPairColorizer.colorize(text).isEmpty())
    }

    @Test
    fun `non-positive maxChars guard returns empty list`() {
        assertTrue(BracketPairColorizer.colorize("()", maxChars = 0).isEmpty())
    }

    @Test
    fun `unterminated string resets at newline fail-safe`() {
        val text = "\" never closed\n{ ok }"
        val result = BracketPairColorizer.colorize(text)
        assertEquals(2, result.size)
        assertEquals(listOf(1, 1), depths(result))
    }

    @Test
    fun `color index cycles through palette`() {
        assertEquals(0, BracketPairColorizer.colorIndexFor(1))
        assertEquals(3, BracketPairColorizer.colorIndexFor(4))
        assertEquals(0, BracketPairColorizer.colorIndexFor(5))
    }

    @Test
    fun `color index clamps invalid depth and palette size`() {
        assertEquals(0, BracketPairColorizer.colorIndexFor(0))
        assertEquals(0, BracketPairColorizer.colorIndexFor(-5))
        assertEquals(0, BracketPairColorizer.colorIndexFor(2, paletteSize = 0))
    }

    @Test
    fun `crlf text keeps offsets aligned`() {
        val text = "a(\r\nb)"
        val result = BracketPairColorizer.colorize(text)
        assertEquals(listOf(1, 4), offsets(result))
    }
}
