package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.BookmarkStore
import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkStoreTest {

    private fun store() = BookmarkStore(InMemoryKeyValueStore())

    @Test
    fun toggleAddsThenRemoves() {
        val s = store()
        assertTrue(s.toggle("u", 3, "line three"))
        assertTrue(s.has("u", 3))
        assertFalse(s.toggle("u", 3, "line three")) // second toggle removes
        assertFalse(s.has("u", 3))
    }

    @Test
    fun listSortedByLine() {
        val s = store()
        s.toggle("u", 9, "nine")
        s.toggle("u", 2, "two")
        s.toggle("u", 5, "five")
        assertEquals(listOf(2, 5, 9), s.list("u").map { it.line })
    }

    @Test
    fun listFilteredByUri() {
        val s = store()
        s.toggle("a", 1, "x")
        s.toggle("b", 2, "y")
        assertEquals(1, s.list("a").size)
        assertEquals(1, s.list("b").size)
        assertEquals(0, s.list("c").size)
    }

    @Test
    fun labelStoredAndReturned() {
        val s = store()
        s.toggle("u", 4, "  int main() {  ")
        assertEquals("  int main() {  ", s.list("u").first().label)
    }

    @Test
    fun clearRemovesOnlyThatUri() {
        val s = store()
        s.toggle("a", 1, "x")
        s.toggle("b", 1, "y")
        s.clear("a")
        assertTrue(s.list("a").isEmpty())
        assertEquals(1, s.list("b").size)
    }

    @Test
    fun invalidInputIgnored() {
        val s = store()
        assertFalse(s.toggle("", 3, "x")) // blank URI
        assertFalse(s.toggle("u", 0, "x")) // line 0 invalid
        assertFalse(s.toggle("u", -2, "x"))
    }

    @Test
    fun capacityEvictsOldest() {
        val s = BookmarkStore(InMemoryKeyValueStore(), capacity = 2)
        s.toggle("u", 1, "one", 10)
        s.toggle("u", 2, "two", 20)
        s.toggle("u", 3, "three", 30) // evicts line 1
        assertTrue(s.list("u").none { it.line == 1 })
        assertEquals(2, s.list("u").size)
    }

    @Test
    fun corruptJsonYieldsEmpty() {
        assertEquals(0, BookmarkStore.parse("][ nope").size)
        assertEquals(0, BookmarkStore.parse("").size)
    }

    @Test
    fun parseSkipsMalformedEntries() {
        val json = "[" +
            "{\"uri\":\"u\",\"line\":7,\"label\":\"ok\",\"createdAt\":1}," +
            "{\"line\":8}," +               // missing uri
            "{\"uri\":\"v\",\"line\":-3}," + // invalid line
            "\"junk\"]"                      // not an object
        val all = BookmarkStore.parse(json)
        assertEquals(1, all.size)
        assertEquals(7, all[0].line)
    }
}
