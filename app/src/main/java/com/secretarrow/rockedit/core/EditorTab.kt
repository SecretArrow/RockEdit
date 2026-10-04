package com.secretarrow.rockedit.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * One document open in an editor tab. All mutable editing state lives here so
 * the editor surface can be swapped between tabs without losing anything
 * (text, undo history, caret, scroll, encoding, read-only flag).
 *
 * Pure JVM class: no Android imports, aggressively unit tested.
 */
data class EditorTab(
    val id: Int,
    /** content:// URI string, or null for an untitled in-memory document. */
    var uri: String?,
    /** Display name shown on the tab chip and in the toolbar. */
    var name: String,
    var charsetName: String = EncodingDetector.DEFAULT_CHARSET,
    var lineBreak: LineBreak = LineBreak.LF,
    /** Content as last loaded from / saved to disk. */
    var savedText: String = "",
    /** Current editor content (what the user sees right now). */
    var lastCommitted: String = "",
    val undoStack: UndoStack = UndoStack(),
    var caretStart: Int = 0,
    var caretEnd: Int = 0,
    var scrollY: Int = 0,
    var readOnly: Boolean = false,
    /**
     * False when the tab exists only as metadata (restored session) and its
     * content still has to be read from [uri] on first activation.
     */
    var loaded: Boolean = true,
) {
    val isDirty: Boolean get() = lastCommitted != savedText

    companion object {
        private val NEXT_ID = AtomicInteger(1)

        fun newId(): Int = NEXT_ID.getAndIncrement()

        /** Untitled, empty document. */
        fun untitled(name: String = "untitled"): EditorTab =
            EditorTab(
                id = newId(),
                uri = null,
                name = name,
                loaded = true,
            )

        /** Placeholder tab restored from a persisted session; content loads lazily. */
        fun pending(
            uri: String,
            name: String,
        ): EditorTab =
            EditorTab(
                id = newId(),
                uri = uri,
                name = name,
                loaded = false,
            )
    }
}

/**
 * Pure-logic manager for the open-tab list: add/focus/close semantics shared
 * between the UI layer and unit tests. Index-based; callers keep indexes only
 * for the duration of a synchronous operation.
 */
class TabManager(
    private val maxTabs: Int = MAX_TABS,
) {
    private val tabs = mutableListOf<EditorTab>()
    private var activeIndex = -1
    private val nextUntitled = AtomicInteger(1)

    fun tabs(): List<EditorTab> = tabs.toList()

    fun size(): Int = tabs.size

    fun activeIndex(): Int = activeIndex

    fun activeTab(): EditorTab? = tabs.getOrNull(activeIndex)

    fun isFull(): Boolean = tabs.size >= maxTabs

    /** Name for the next untitled document ("untitled", "untitled 2", ...). */
    fun nextUntitledName(): String {
        val n = nextUntitled.getAndIncrement()
        return if (n == 1) "untitled" else "untitled $n"
    }

    /**
     * Adds [tab] as a new tab and activates it. When a tab with the same
     * non-null URI already exists it is focused instead of duplicated.
     * Returns the activated index, or -1 when the tab bar is full.
     */
    fun add(tab: EditorTab): Int {
        val uri = tab.uri
        if (uri != null) {
            val existing = indexOfUri(uri)
            if (existing >= 0) {
                activeIndex = existing
                return existing
            }
        }
        if (tabs.size >= maxTabs) return -1
        tabs.add(tab)
        activeIndex = tabs.size - 1
        return activeIndex
    }

    /** Activates the tab at [index] (clamped). Returns the tab or null when empty. */
    fun setActive(index: Int): EditorTab? {
        if (tabs.isEmpty()) return null
        activeIndex = index.coerceIn(0, tabs.size - 1)
        return tabs[activeIndex]
    }

    /** Cycles to the next tab (wraps around). Returns null with a single tab. */
    fun next(): EditorTab? {
        if (tabs.size <= 1) return null
        return setActive((activeIndex + 1) % tabs.size)
    }

    /** Index of the tab editing [uri], or -1. */
    fun indexOfUri(uri: String): Int = tabs.indexOfFirst { it.uri == uri }

    /** Index of the tab with [id], or -1. */
    fun indexOfId(id: Int): Int = tabs.indexOfFirst { it.id == id }

    /**
     * Removes the tab at [index] and activates a sensible neighbour
     * (the tab that took its place, else the last one). Returns the removed
     * tab, or null when the index is invalid.
     */
    fun close(index: Int): EditorTab? {
        if (index !in tabs.indices) return null
        val removed = tabs.removeAt(index)
        if (tabs.isEmpty()) {
            activeIndex = -1
        } else {
            activeIndex = index.coerceAtMost(tabs.size - 1)
        }
        return removed
    }

    /** Closes every tab except [keepIndex]; the kept tab stays active. */
    fun closeOthers(keepIndex: Int): List<EditorTab> {
        if (keepIndex !in tabs.indices) return emptyList()
        val kept = tabs[keepIndex]
        val removed = tabs.filterIndexed { i, _ -> i != keepIndex }
        tabs.clear()
        tabs.add(kept)
        activeIndex = 0
        return removed
    }

    /** Closes everything. Returns the removed tabs. */
    fun closeAll(): List<EditorTab> {
        val removed = tabs.toList()
        tabs.clear()
        activeIndex = -1
        return removed
    }

    /** Dirty tabs that have a real file behind them (auto-save candidates). */
    fun dirtyFileTabs(): List<EditorTab> = tabs.filter { it.isDirty && it.uri != null && it.loaded }

    companion object {
        const val MAX_TABS = 10
    }
}
