package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import com.secretarrow.rockedit.core.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTest {
    private fun store() = SessionStore(InMemoryKeyValueStore())

    @Test
    fun saveAndLoadRoundtrip() {
        val s = store()
        s.saveCursor("content://a", 12, 15, 240)
        val c = s.loadCursor("content://a")
        assertNotNull(c)
        assertEquals(12, c!!.selStart)
        assertEquals(15, c.selEnd)
        assertEquals(240, c.scrollY)
    }

    @Test
    fun overwriteSameUri() {
        val s = store()
        s.saveCursor("content://a", 1, 1)
        s.saveCursor("content://a", 99, 100, 7)
        val c = s.loadCursor("content://a")!!
        assertEquals(99, c.selStart)
        assertEquals(100, c.selEnd)
        assertEquals(7, c.scrollY)
    }

    @Test
    fun unknownUriReturnsNull() {
        assertNull(store().loadCursor("content://missing"))
    }

    @Test
    fun clearRemovesEntry() {
        val s = store()
        s.saveCursor("content://a", 5, 5)
        s.clear("content://a")
        assertNull(s.loadCursor("content://a"))
    }

    @Test
    fun lruCapEvictsOldest() {
        val s = SessionStore(InMemoryKeyValueStore(), capacity = 3)
        var t = 1000L
        s.saveCursor("u1", 1, 1, 0, t++)
        s.saveCursor("u2", 2, 2, 0, t++)
        s.saveCursor("u3", 3, 3, 0, t++)
        s.saveCursor("u4", 4, 4, 0, t++) // evicts u1 (oldest)
        assertNull(s.loadCursor("u1"))
        assertNotNull(s.loadCursor("u4"))
        assertNotNull(s.loadCursor("u2"))
    }

    @Test
    fun refreshingKeepsEntryAlive() {
        val s = SessionStore(InMemoryKeyValueStore(), capacity = 2)
        s.saveCursor("u1", 1, 1, 0, 10)
        s.saveCursor("u2", 2, 2, 0, 20)
        s.saveCursor("u1", 3, 3, 0, 30) // u1 refreshed; u2 becomes oldest
        s.saveCursor("u3", 3, 3, 0, 40) // evicts u2
        assertNull(s.loadCursor("u2"))
        assertNotNull(s.loadCursor("u1"))
        assertNotNull(s.loadCursor("u3"))
    }

    @Test
    fun blankUriIgnored() {
        val s = store()
        s.saveCursor("", 1, 1)
        assertNull(s.loadCursor(""))
    }

    @Test
    fun negativeOffsetsCoerced() {
        val s = store()
        s.saveCursor("u", -5, -1)
        assertEquals(0, s.loadCursor("u")!!.selStart)
        assertEquals(0, s.loadCursor("u")!!.selEnd)
    }

    @Test
    fun corruptJsonYieldsEmpty() {
        assertEquals(0, SessionStore.parse("not-json{").size)
        assertEquals(0, SessionStore.parse("").size)
    }

    @Test
    fun parseToleratesMalformedEntries() {
        val json =
            "{\"u1\":{\"selStart\":4,\"selEnd\":6,\"scrollY\":1,\"updatedAt\":5}," +
                "\"bad\":\"nope\"}"
        val map = SessionStore.parse(json)
        assertEquals(1, map.size)
        assertEquals(4, map["u1"]!!.selStart)
    }
}
