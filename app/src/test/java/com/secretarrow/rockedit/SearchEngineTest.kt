package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.SearchEngine
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ---------- findAllMatches (v0.22.0) ----------

    @Test
    fun findAllMatchesListsEveryHitInOrder() {
        val list = SearchEngine.findAllMatches("alpha beta alpha gamma alpha", "alpha", cs)
        assertEquals(3, list.count)
        assertEquals(0, list.ranges[0].first)
        assertEquals(11, list.ranges[1].first)
        assertEquals(23, list.ranges[2].first)
        assertFalse(list.truncated)
    }

    @Test
    fun findAllMatchesEmptyInputsReturnEmpty() {
        assertEquals(SearchEngine.MatchList.EMPTY, SearchEngine.findAllMatches("", "x", cs))
        assertEquals(SearchEngine.MatchList.EMPTY, SearchEngine.findAllMatches("abc", "", cs))
        // Query longer than the text can never match.
        assertEquals(SearchEngine.MatchList.EMPTY, SearchEngine.findAllMatches("hi", "hello", cs))
        // Non-positive limit is a legal no-op input, not a crash.
        assertEquals(SearchEngine.MatchList.EMPTY, SearchEngine.findAllMatches("abc", "a", cs, limit = 0))
    }

    @Test
    fun findAllMatchesAreNonOverlapping() {
        // "aaa" with query "aa" matches once at 0 (non-overlap advance), not twice.
        val list = SearchEngine.findAllMatches("aaa", "aa", cs)
        assertEquals(1, list.count)
        assertEquals(0, list.ranges[0].first)
        assertEquals(1, list.ranges[0].last)
    }

    @Test
    fun findAllMatchesCaseInsensitiveRangesMapToOriginalText() {
        val text = "Rock rock ROCK"
        val list = SearchEngine.findAllMatches(text, "rock", ci)
        assertEquals(3, list.count)
        // Ranges index the ORIGINAL text, so slicing must reproduce each hit.
        for (r in list.ranges) {
            assertEquals("rock", text.substring(r.first, r.last + 1).lowercase(Locale.ROOT))
        }
    }

    @Test
    fun findAllMatchesRespectsLimitAndFlagsTruncation() {
        val text = "ab".repeat(50)
        val exact = SearchEngine.findAllMatches(text, "ab", cs, limit = 50)
        assertEquals(50, exact.count)
        assertFalse(exact.truncated)
        val over = SearchEngine.findAllMatches(text, "ab", cs, limit = 49)
        assertEquals(49, over.count)
        assertTrue(over.truncated)
    }

    // ---------- matchOrdinalAt / counterLabel (v0.22.0) ----------

    @Test
    fun matchOrdinalAtCoversStartMiddleEnd() {
        val list = SearchEngine.findAllMatches("go go go", "go", cs)
        assertEquals(1, SearchEngine.matchOrdinalAt(list.ranges, 0)) // start
        assertEquals(1, SearchEngine.matchOrdinalAt(list.ranges, 1)) // middle
        assertEquals(1, SearchEngine.matchOrdinalAt(list.ranges, 1)) // end of first
        assertEquals(2, SearchEngine.matchOrdinalAt(list.ranges, 3))
        assertEquals(3, SearchEngine.matchOrdinalAt(list.ranges, 6))
    }

    @Test
    fun matchOrdinalAtOutsideMatchesReturnsZero() {
        val list = SearchEngine.findAllMatches("go go", "go", cs)
        assertEquals(0, SearchEngine.matchOrdinalAt(emptyList(), 0)) // empty list
        assertEquals(0, SearchEngine.matchOrdinalAt(list.ranges, 2)) // gap
        assertEquals(0, SearchEngine.matchOrdinalAt(list.ranges, 5)) // past end
        assertEquals(0, SearchEngine.matchOrdinalAt(list.ranges, -1)) // negative caret
    }

    @Test
    fun counterLabelBranches() {
        val list = SearchEngine.findAllMatches("go go go", "go", cs)
        assertEquals("", SearchEngine.counterLabel(SearchEngine.MatchList.EMPTY, 0))
        assertEquals("3", SearchEngine.counterLabel(list, 2)) // caret in a gap
        assertEquals("1/3", SearchEngine.counterLabel(list, 0)) // caret on match 1
        assertEquals("3/3", SearchEngine.counterLabel(list, 6)) // caret on match 3
        val truncated = SearchEngine.MatchList(list.ranges, truncated = true)
        assertEquals("1/3+", SearchEngine.counterLabel(truncated, 0))
    }

    // ---------- indexOfPrev (v0.22.0) ----------

    @Test
    fun indexOfPrevFindsMatchStrictlyBeforeCaret() {
        // "go go go": caret at 3 (second hit start) -> previous is at 0.
        assertEquals(0, SearchEngine.indexOfPrev("go go go", "go", 3, cs))
        // Caret at 6 -> previous ends at 5 -> match at 3.
        assertEquals(3, SearchEngine.indexOfPrev("go go go", "go", 6, cs))
    }

    @Test
    fun indexOfPrevWrapsToLastMatch() {
        // No match left of the caret -> wrap to the LAST match (desktop convention).
        // "go go" has matches at 0..1 and 3..4 -> wrap lands on 3.
        assertEquals(3, SearchEngine.indexOfPrev("go go", "go", 0, cs))
        // Exactly one match re-selects itself instead of failing.
        assertEquals(0, SearchEngine.indexOfPrev("go", "go", 0, cs))
    }

    @Test
    fun indexOfPrevNoWrapAndEmptyInputs() {
        assertEquals(-1, SearchEngine.indexOfPrev("go go", "go", 0, cs, wrapAround = false))
        assertEquals(-1, SearchEngine.indexOfPrev("", "go", 0, cs))
        assertEquals(-1, SearchEngine.indexOfPrev("go", "", 0, cs))
        assertEquals(-1, SearchEngine.indexOfPrev("hi", "hello", 2, cs))
        // beforeIndex beyond the text is clamped, not fatal (last match wins).
        assertEquals(3, SearchEngine.indexOfPrev("go go", "go", 99, cs))
    }

    @Test
    fun indexOfPrevCaseInsensitive() {
        assertEquals(0, SearchEngine.indexOfPrev("Go GO go", "go", 3, ci))
    }

    // ---------- Locale.ROOT regression (v0.22.0) ----------

    /**
     * Default-locale lowercase can CHANGE string length (Turkish 'I' folds to
     * two UTF-16 units), corrupting every match offset. All folding must be
     * locale-independent — the results below must hold under a Turkish
     * default locale exactly as they do anywhere else.
     */
    @Test
    fun matchingIsLocaleIndependent() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            // Case-insensitive: 'I' still folds to one unit; offsets stay intact.
            assertEquals(0, SearchEngine.indexOf("Istanbul Is", "is", 0, ci))
            assertEquals(9, SearchEngine.indexOf("Istanbul Is", "is", 1, ci))
            assertEquals(4, SearchEngine.countMatches("IiI i", "i", ci))
            val (text, count) = SearchEngine.replaceAll("Is IS is", "is", "X", ci)
            assertEquals("X X X", text)
            assertEquals(3, count)
            assertEquals(3, SearchEngine.findAllMatches("Is IS is", "is", ci).count)
            // Cap is negative at caret 0 -> wrap lands on the LAST match (index 6).
            assertEquals(6, SearchEngine.indexOfPrev("Is IS is", "is", 0, ci))
        } finally {
            Locale.setDefault(original)
        }
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(Locale.ENGLISH)
    }
}
