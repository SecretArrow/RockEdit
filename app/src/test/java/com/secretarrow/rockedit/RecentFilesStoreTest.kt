package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import com.secretarrow.rockedit.core.RecentFilesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFilesStoreTest {
    private fun store(capacity: Int = RecentFilesStore.MAX_ITEMS) = RecentFilesStore(InMemoryKeyValueStore(), capacity)

    @Test
    fun emptyByDefault() {
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun addMovesExistingToTop() {
        val s = store()
        s.add("uri://a", "a.txt", 100)
        s.add("uri://b", "b.txt", 200)
        s.add("uri://a", "a.txt", 300)
        val items = s.list()
        assertEquals(2, items.size)
        assertEquals("uri://a", items[0].uri)
        assertEquals(300, items[0].lastOpened)
    }

    @Test
    fun removeDeletesEntry() {
        val s = store()
        s.add("uri://a", "a.txt", 1)
        s.add("uri://b", "b.txt", 2)
        s.remove("uri://a")
        assertEquals(listOf("uri://b"), s.list().map { it.uri })
    }

    @Test
    fun clearRemovesEverything() {
        val s = store()
        s.add("uri://a", "a.txt")
        s.clear()
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun capacityIsEnforced() {
        val s = store(capacity = 3)
        for (i in 1..5) s.add("uri://$i", "file$i.txt")
        val items = s.list()
        assertEquals(3, items.size)
        assertEquals("uri://5", items[0].uri)
    }

    @Test
    fun blankUriIgnored() {
        val s = store()
        s.add("", "empty")
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun malformedJsonParsesToEmpty() {
        assertTrue(RecentFilesStore.parse("not-json-at-all{").isEmpty())
        assertTrue(RecentFilesStore.parse("").isEmpty())
    }

    @Test
    fun parseSkipsMalformedEntries() {
        val json = """[{"uri":"uri://ok","name":"ok.txt","lastOpened":5},{"name":"no-uri"},{"uri":"","name":""}]"""
        val items = RecentFilesStore.parse(json)
        assertEquals(1, items.size)
        assertEquals("uri://ok", items[0].uri)
    }
}
