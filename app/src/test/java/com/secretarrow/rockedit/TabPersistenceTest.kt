package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.EditorTab
import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import com.secretarrow.rockedit.core.LineBreak
import com.secretarrow.rockedit.core.TabPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabPersistenceTest {
    private fun loadedTab(
        uri: String?,
        name: String,
    ): EditorTab =
        EditorTab(id = EditorTab.newId(), uri = uri, name = name).apply {
            savedText = "saved"
            lastCommitted = "committed"
        }

    @Test
    fun roundTripPreservesTabsAndActiveIndex() {
        val kv = InMemoryKeyValueStore()
        val store = TabPersistence(kv)
        val a = loadedTab("content://a", "a.txt")
        a.charsetName = "Shift_JIS"
        a.lineBreak = LineBreak.CRLF
        a.readOnly = true
        val b = loadedTab(null, "untitled")
        store.save(listOf(a, b), activeIndex = 1)

        val loaded = store.load()!!
        assertEquals(2, loaded.tabs.size)
        assertEquals(1, loaded.activeIndex)

        val first = loaded.tabs[0]
        assertEquals("content://a", first.uri)
        assertEquals("a.txt", first.name)
        assertEquals("Shift_JIS", first.charset)
        assertEquals("CRLF", first.lineBreak)
        assertTrue(first.readOnly)

        val second = loaded.tabs[1]
        assertNull(second.uri)
        assertEquals("untitled", second.name)
    }

    @Test
    fun emptyUriBecomesNull() {
        val kv = InMemoryKeyValueStore()
        val store = TabPersistence(kv)
        val tab = loadedTab(null, "untitled")
        store.save(listOf(tab), 0)
        assertNull(store.load()!!.tabs[0].uri)
    }

    @Test
    fun loadReturnsNullWhenNothingSaved() {
        val store = TabPersistence(InMemoryKeyValueStore())
        assertNull(store.load())
    }

    @Test
    fun loadReturnsNullOnCorruptJson() {
        val kv = InMemoryKeyValueStore()
        kv.putString("open_tabs", "{not json at all")
        assertNull(TabPersistence(kv).load())
    }

    @Test
    fun clearRemovesState() {
        val kv = InMemoryKeyValueStore()
        val store = TabPersistence(kv)
        store.save(listOf(loadedTab("content://a", "a.txt")), 0)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun saveOverwritesPreviousState() {
        val kv = InMemoryKeyValueStore()
        val store = TabPersistence(kv)
        store.save(listOf(loadedTab("content://a", "a.txt")), 0)
        store.save(emptyList(), -1)
        val loaded = store.load()!!
        assertEquals(0, loaded.tabs.size)
        assertEquals(-1, loaded.activeIndex)
    }
}
