package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.EditorTab
import com.secretarrow.rockedit.core.LineBreak
import com.secretarrow.rockedit.core.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTabTest {
    private fun tab(
        uri: String?,
        name: String = uri ?: "untitled",
    ): EditorTab = EditorTab(id = EditorTab.newId(), uri = uri, name = name)

    // ------------------------------------------------------------- dirty flag

    @Test
    fun dirtyWhenCommittedDiffersFromSaved() {
        val t = tab("content://a")
        t.savedText = "abc"
        t.lastCommitted = "abc"
        assertFalse(t.isDirty)
        t.lastCommitted = "abcd"
        assertTrue(t.isDirty)
    }

    // ------------------------------------------------------------ add / focus

    @Test
    fun addActivatesNewTabAndReturnsIndex() {
        val m = TabManager()
        assertEquals(0, m.add(tab("content://a")))
        assertEquals(1, m.add(tab("content://b")))
        assertEquals(1, m.activeIndex())
        assertEquals(2, m.size())
    }

    @Test
    fun addSameUriFocusesExistingTabInsteadOfDuplicating() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.add(tab("content://b"))
        m.setActive(1)
        val again = tab("content://a")
        val index = m.add(again)
        assertEquals(0, index)
        assertEquals(0, m.activeIndex())
        assertEquals(2, m.size())
        assertNull(m.activeTab()?.let { if (it === again) "duplicate" else null })
    }

    @Test
    fun addUntitledNeverDeduplicates() {
        val m = TabManager()
        m.add(EditorTab.untitled())
        m.add(EditorTab.untitled())
        assertEquals(2, m.size())
    }

    @Test
    fun addReturnsMinusOneWhenFull() {
        val m = TabManager(maxTabs = 2)
        assertEquals(0, m.add(tab("content://a")))
        assertEquals(1, m.add(tab("content://b")))
        assertEquals(-1, m.add(tab("content://c")))
        assertTrue(m.isFull())
    }

    // ------------------------------------------------------------------ close

    @Test
    fun closeMiddleActivatesTheTabThatTookItsPlace() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.add(tab("content://b"))
        m.add(tab("content://c"))
        m.setActive(0)
        val removed = m.close(1)
        assertEquals("content://b", removed?.uri)
        assertEquals(1, m.activeIndex())
        assertEquals("content://c", m.activeTab()?.uri)
    }

    @Test
    fun closeLastActivatesPreviousTab() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.add(tab("content://b"))
        m.setActive(1)
        m.close(1)
        assertEquals(0, m.activeIndex())
    }

    @Test
    fun closeLastTabLeavesEmptyManager() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.close(0)
        assertEquals(0, m.size())
        assertEquals(-1, m.activeIndex())
        assertNull(m.activeTab())
    }

    @Test
    fun closeInvalidIndexReturnsNull() {
        val m = TabManager()
        assertNull(m.close(-1))
        assertNull(m.close(5))
    }

    @Test
    fun closeOthersKeepsSingleTab() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.add(tab("content://b"))
        m.add(tab("content://c"))
        val removed = m.closeOthers(1)
        assertEquals(2, removed.size)
        assertEquals(1, m.size())
        assertEquals(0, m.activeIndex())
        assertEquals("content://b", m.activeTab()?.uri)
    }

    @Test
    fun closeAllClearsEverything() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.add(tab("content://b"))
        val removed = m.closeAll()
        assertEquals(2, removed.size)
        assertEquals(0, m.size())
    }

    // ------------------------------------------------------------------- next

    @Test
    fun nextCyclesThroughTabs() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.add(tab("content://b"))
        m.add(tab("content://c"))
        // add() activates the newest tab (index 2); cycling wraps to 0 first.
        m.next()
        assertEquals(0, m.activeIndex())
        m.next()
        assertEquals(1, m.activeIndex())
        m.next()
        assertEquals(2, m.activeIndex())
    }

    @Test
    fun nextReturnsNullWithSingleTab() {
        val m = TabManager()
        m.add(tab("content://a"))
        assertNull(m.next())
    }

    // ------------------------------------------------------------------- misc

    @Test
    fun indexOfUriFindsCaseExactMatch() {
        val m = TabManager()
        m.add(tab("content://a/xyz"))
        assertEquals(0, m.indexOfUri("content://a/xyz"))
        assertEquals(-1, m.indexOfUri("content://a/XYZ"))
    }

    @Test
    fun setActiveClampsToValidRange() {
        val m = TabManager()
        m.add(tab("content://a"))
        m.add(tab("content://b"))
        val clamped = m.setActive(99)
        assertEquals("content://b", clamped?.uri)
        assertEquals(1, m.activeIndex())
    }

    @Test
    fun dirtyFileTabsExcludesUntitledAndUnloaded() {
        val m = TabManager()
        val fileTab = tab("content://a")
        fileTab.savedText = "x"
        fileTab.lastCommitted = "xy"
        m.add(fileTab)
        val untitled = EditorTab.untitled()
        untitled.savedText = "s"
        untitled.lastCommitted = "sd"
        m.add(untitled)
        val pending = EditorTab.pending("content://c", "c.txt")
        pending.savedText = "s"
        pending.lastCommitted = "sd"
        m.add(pending)
        val dirty = m.dirtyFileTabs()
        assertEquals(1, dirty.size)
        assertEquals("content://a", dirty[0].uri)
    }

    @Test
    fun pendingTabIsNotLoaded() {
        val t = EditorTab.pending("content://a", "a.txt")
        assertFalse(t.loaded)
        assertEquals("content://a", t.uri)
        assertEquals("a.txt", t.name)
    }

    @Test
    fun lineBreakAndCharsetDefaults() {
        val t = EditorTab.untitled()
        assertEquals(LineBreak.LF, t.lineBreak)
        assertEquals(com.secretarrow.rockedit.core.EncodingDetector.DEFAULT_CHARSET, t.charsetName)
        assertEquals(0, t.scrollY)
        assertFalse(t.readOnly)
    }
}
