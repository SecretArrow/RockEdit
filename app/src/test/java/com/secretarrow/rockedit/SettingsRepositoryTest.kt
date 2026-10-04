package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import com.secretarrow.rockedit.core.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun defaults() {
        val s = SettingsRepository(InMemoryKeyValueStore())
        assertEquals(SettingsRepository.THEME_SYSTEM, s.theme)
        assertTrue(s.lineNumbers)
        assertFalse(s.wordWrap)
        assertFalse(s.fullScreen)
        assertEquals(SettingsRepository.LINE_BREAK_AUTO, s.lineBreakDefault)
        assertTrue(s.syntaxHighlight)
        assertEquals(14, s.fontSizeSp)
        assertFalse(s.autoSave)
    }

    @Test
    fun syntaxHighlightRoundTrip() {
        val store = InMemoryKeyValueStore()
        val s = SettingsRepository(store)
        s.syntaxHighlight = false
        assertFalse(SettingsRepository(store).syntaxHighlight)
    }

    @Test
    fun fontSizeRoundTripAndFallback() {
        val store = InMemoryKeyValueStore()
        val s = SettingsRepository(store)
        s.fontSizeSp = 20
        assertEquals(20, SettingsRepository(store).fontSizeSp)
        store.putString(SettingsRepository.KEY_FONT_SIZE, "bogus")
        assertEquals(14, SettingsRepository(store).fontSizeSp)
    }

    @Test
    fun autoSaveRoundTrip() {
        val store = InMemoryKeyValueStore()
        val s = SettingsRepository(store)
        s.autoSave = true
        assertTrue(SettingsRepository(store).autoSave)
    }

    @Test
    fun blackThemeFlag() {
        val store = InMemoryKeyValueStore()
        val s = SettingsRepository(store)
        assertFalse(s.isBlackTheme())
        store.putString(SettingsRepository.KEY_THEME, SettingsRepository.THEME_BLACK)
        assertTrue(s.isBlackTheme())
    }

    @Test
    fun fullScreenRoundTrip() {
        val store = InMemoryKeyValueStore()
        val s = SettingsRepository(store)
        assertFalse(s.fullScreen)
        s.fullScreen = true
        assertTrue(SettingsRepository(store).fullScreen)
    }

    @Test
    fun roundTripValues() {
        // Values persist when the same store instance is reused:
        val store = InMemoryKeyValueStore()
        val writer = SettingsRepository(store)
        writer.theme = SettingsRepository.THEME_DARK
        writer.lineBreakDefault = SettingsRepository.LINE_BREAK_CRLF
        val reader = SettingsRepository(store)
        assertEquals(SettingsRepository.THEME_DARK, reader.theme)
        assertEquals(SettingsRepository.LINE_BREAK_CRLF, reader.lineBreakDefault)
    }

    @Test
    fun invalidThemeValueFallsBackToSystem() {
        val store = InMemoryKeyValueStore()
        store.putString(SettingsRepository.KEY_THEME, "neon")
        val s = SettingsRepository(store)
        assertEquals("neon", s.theme) // stored raw; applyTheme() maps unknown to follow-system
    }

    @Test
    fun resolveLineBreakMapsSettings() {
        val store = InMemoryKeyValueStore()
        val s = SettingsRepository(store)
        store.putString(SettingsRepository.KEY_LINE_BREAK, SettingsRepository.LINE_BREAK_LF)
        assertEquals(com.secretarrow.rockedit.core.LineBreak.LF, s.resolveLineBreak())
        store.putString(SettingsRepository.KEY_LINE_BREAK, SettingsRepository.LINE_BREAK_CRLF)
        assertEquals(com.secretarrow.rockedit.core.LineBreak.CRLF, s.resolveLineBreak())
    }

    @Test
    fun filesAndTabsDefaults() {
        val s = SettingsRepository(InMemoryKeyValueStore())
        assertTrue(s.rememberTabs)
        assertTrue(s.sortFoldersFirst)
        assertFalse(s.showHiddenFiles)
        assertEquals("", s.lastFolderUri)
    }

    @Test
    fun filesAndTabsRoundTrip() {
        val store = InMemoryKeyValueStore()
        val writer = SettingsRepository(store)
        writer.rememberTabs = false
        writer.sortFoldersFirst = false
        writer.showHiddenFiles = true
        writer.lastFolderUri = "content://com.android.externalstorage/tree/home"
        val reader = SettingsRepository(store)
        assertFalse(reader.rememberTabs)
        assertFalse(reader.sortFoldersFirst)
        assertTrue(reader.showHiddenFiles)
        assertEquals("content://com.android.externalstorage/tree/home", reader.lastFolderUri)
    }
}
