package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEngineTest {

    private val ci = SearchEngine.Options(caseSensitive = false)
    private val cs = SearchEngine.Options(caseSensitive = true)

    @Test
    fun indexOfFindsFirstOccurrence() {
        assertEquals(0, SearchEngine.indexOf("rockedit", "rock", 0, cs))
        assertEquals(4, SearchEngine.indexOf("rockedit", "edit", 0, cs))
    }

    @Test
    fun indexOfCaseInsensitive() {
        assertEquals(0, SearchEngine.indexOf("RockEdit", "rock", 0, ci))
        assertEquals(-1, SearchEngine.indexOf("RockEdit", "rock", 0, cs))
    }

    @Test
    fun indexOfFromIndex() {
        // "ababab" has matches at 0, 2, 4 -> searching from 2 finds 2.
        assertEquals(2, SearchEngine.indexOf("ababab", "ab", 2, cs))
    }

    @Test
    fun indexOfWrapsAround() {
        // From index 3 there is no full match; wrap-around finds the one at 0.
        assertEquals(0, SearchEngine.indexOf("abab", "ab", 3, cs, wrapAround = true))
        assertEquals(-1, SearchEngine.indexOf("abab", "ab", 3, cs, wrapAround = false))
    }

    @Test
    fun indexOfEmptyInputs() {
        assertEquals(-1, SearchEngine.indexOf("", "x", 0, cs))
        assertEquals(-1, SearchEngine.indexOf("abc", "", 0, cs))
    }

    @Test
    fun countMatches() {
        assertEquals(3, SearchEngine.countMatches("ababab", "ab", cs))
        // Case-sensitive: "aXaXa" contains no lowercase "ax".
        assertEquals(0, SearchEngine.countMatches("aXaXa", "ax", cs))
        // Case-insensitive: lowercase haystack matches twice.
        assertEquals(2, SearchEngine.countMatches("aXaXa", "ax", ci))
        assertEquals(0, SearchEngine.countMatches("abc", "", cs))
    }

    @Test
    fun replaceAllBasic() {
        val (text, count) = SearchEngine.replaceAll("one two one two", "one", "1", cs)
        assertEquals("1 two 1 two", text)
        assertEquals(2, count)
    }

    @Test
    fun replaceAllCaseInsensitive() {
        val (text, count) = SearchEngine.replaceAll("Rock ROCK rock", "rock", "stone", ci)
        assertEquals("stone stone stone", text)
        assertEquals(3, count)
    }

    @Test
    fun replaceAllEmptyQueryIsNoOp() {
        val (text, count) = SearchEngine.replaceAll("abc", "", "x", cs)
        assertEquals("abc", text)
        assertEquals(0, count)
    }
}
