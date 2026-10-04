package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [ClipboardHistoryStore] (v0.13.0): add validation,
 * newest-first ordering, consecutive dedupe (with pinned preservation),
 * eviction rules (oldest unpinned, pinned skip, all-pinned fallback),
 * clamped limits, pin/unpin/delete/clear, case-insensitive search, corrupt
 * storage recovery, and pinned-first listing.
 */
class ClipboardHistoryStoreTest {
    private fun ok(result: ClipboardHistoryStore.AddResult): List<ClipboardHistoryStore.Entry> {
        assertTrue(
            "expected Success, was $result",
            result is ClipboardHistoryStore.AddResult.Success,
        )
        return (result as ClipboardHistoryStore.AddResult.Success).entries
    }

    private fun failureOf(result: ClipboardHistoryStore.AddResult): ClipboardHistoryStore.AddResult.Failure {
        assertTrue(
            "expected Failure, was $result",
            result is ClipboardHistoryStore.AddResult.Failure,
        )
        return result as ClipboardHistoryStore.AddResult.Failure
    }

    private fun cappedEntries(): ClipboardHistoryStore = ClipboardHistoryStore(InMemoryKeyValueStore(), maxEntries = 5)

    // ---------------------------------------------------------- add & order

    @Test
    fun addAndListAreNewestFirst() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("alpha"))
        ok(s.add("beta"))
        ok(s.add("gamma"))
        assertEquals(listOf("gamma", "beta", "alpha"), s.list().map { it.text })
        assertEquals(3, s.size())
    }

    @Test
    fun orderSurvivesReopen() {
        val kv = InMemoryKeyValueStore()
        val first = ClipboardHistoryStore(kv)
        ok(first.add("alpha"))
        ok(first.add("beta"))
        val second = ClipboardHistoryStore(kv)
        assertEquals(listOf("beta", "alpha"), second.list().map { it.text })
    }

    @Test
    fun idsAreUnique() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("a"))
        ok(s.add("b"))
        assertEquals(
            2,
            s
                .list()
                .map { it.id }
                .toSet()
                .size,
        )
    }

    // --------------------------------------------------------------- dedupe

    @Test
    fun consecutiveDuplicateMovesToTopAndKeepsId() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("alpha"))
        ok(s.add("beta"))
        val betaId = s.list().first().id
        ok(s.add("beta"))
        val entries = s.list()
        assertEquals(2, entries.size)
        assertEquals("beta", entries.first().text)
        assertEquals(betaId, entries.first().id)
        assertFalse(entries.first().pinned)
    }

    @Test
    fun consecutiveDuplicateKeepsPinnedState() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("alpha"))
        ok(s.add("beta"))
        val betaId = s.list().first().id
        assertTrue(s.pin(betaId))
        ok(s.add("beta"))
        val first = s.list().first()
        assertEquals(betaId, first.id)
        assertTrue(first.pinned)
        assertEquals(2, s.size())
    }

    @Test
    fun nonConsecutiveDuplicateStaysSeparate() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("alpha"))
        ok(s.add("beta"))
        ok(s.add("alpha"))
        assertEquals(3, s.size())
        assertEquals("alpha", s.list().first().text)
    }

    // ------------------------------------------------------------ validation

    @Test
    fun blankTextIsRejected() {
        assertEquals(
            ClipboardHistoryStore.ErrorCode.BLANK_TEXT,
            failureOf(ClipboardHistoryStore(InMemoryKeyValueStore()).add("")).code,
        )
    }

    @Test
    fun whitespaceOnlyTextIsRejected() {
        val result = ClipboardHistoryStore(InMemoryKeyValueStore()).add("  \n\t ")
        assertEquals(ClipboardHistoryStore.ErrorCode.BLANK_TEXT, failureOf(result).code)
    }

    @Test
    fun oversizeTextIsRejectedWithActualAndCap() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore(), maxEntryChars = 5)
        val result = failureOf(s.add("abcdef"))
        assertEquals(ClipboardHistoryStore.ErrorCode.TEXT_TOO_LARGE, result.code)
        assertTrue(result.message.contains("6"))
        assertTrue(result.message.contains("5"))
        // Boundary: exactly the cap is accepted.
        assertEquals(1, ok(s.add("abcde")).size)
    }

    // ------------------------------------------------------------- eviction

    @Test
    fun evictsOldestUnpinned() {
        val s = cappedEntries()
        for (i in 1..5) ok(s.add("e$i"))
        ok(s.add("e6"))
        assertEquals(5, s.size())
        assertEquals(listOf("e6", "e5", "e4", "e3", "e2"), s.list().map { it.text })
    }

    @Test
    fun evictionSkipsPinnedOldest() {
        val s = cappedEntries()
        for (i in 1..5) ok(s.add("e$i"))
        assertTrue(s.pin(s.list().last().id)) // pin the oldest entry
        ok(s.add("e6"))
        // e2 (oldest unpinned) is evicted; pinned e1 survives and lists first.
        assertEquals(listOf("e1", "e6", "e5", "e4", "e3"), s.list().map { it.text })
    }

    @Test
    fun allPinnedEvictsOldestOverall() {
        val s = cappedEntries()
        for (i in 1..5) ok(s.add("e$i"))
        for (id in s.list().map { it.id }) assertTrue(s.pin(id))
        ok(s.add("e6"))
        assertEquals(5, s.size())
        // Oldest overall (e1) is evicted although pinned; the fresh entry
        // (index 0, unpinned) is never the victim. list() shows the
        // surviving pinned entries first (newest first), then e6.
        assertTrue(s.list().none { it.text == "e1" })
        assertTrue(s.list().any { it.text == "e6" })
        assertEquals(listOf("e5", "e4", "e3", "e2", "e6"), s.list().map { it.text })
    }

    // ---------------------------------------------------------------- clamps

    @Test
    fun maxEntriesClampedLow() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore(), maxEntries = 1)
        assertEquals(ClipboardHistoryStore.MIN_MAX_ENTRIES, s.effectiveMaxEntries)
        for (i in 1..6) ok(s.add("e$i"))
        assertEquals(ClipboardHistoryStore.MIN_MAX_ENTRIES, s.size())
        assertTrue(s.list().none { it.text == "e1" })
    }

    @Test
    fun maxEntriesClampedHigh() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore(), maxEntries = 500)
        assertEquals(ClipboardHistoryStore.MAX_MAX_ENTRIES, s.effectiveMaxEntries)
        for (i in 1..201) ok(s.add("e$i"))
        assertEquals(ClipboardHistoryStore.MAX_MAX_ENTRIES, s.size())
        assertEquals("e201", s.list().first().text)
    }

    @Test
    fun maxEntryCharsClamped() {
        val low = ClipboardHistoryStore(InMemoryKeyValueStore(), maxEntryChars = 0)
        assertEquals(1, low.effectiveMaxEntryChars)
        assertEquals(ClipboardHistoryStore.ErrorCode.TEXT_TOO_LARGE, failureOf(low.add("ab")).code)
        assertEquals(1, ok(low.add("a")).size)
        val high = ClipboardHistoryStore(InMemoryKeyValueStore(), maxEntryChars = 2_000_000)
        assertEquals(ClipboardHistoryStore.MAX_ENTRY_CHARS_LIMIT, high.effectiveMaxEntryChars)
    }

    // ------------------------------------------------ pin / unpin / delete

    @Test
    fun pinUnpinDeleteAndMissingIds() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("alpha"))
        ok(s.add("beta"))
        assertFalse(s.pin("c404"))
        assertFalse(s.unpin("c404"))
        assertFalse(s.delete("c404"))
        val id = s.list().first().id
        assertTrue(s.pin(id))
        assertTrue(s.list().first().pinned)
        assertTrue(s.unpin(id))
        assertFalse(s.list().first().pinned)
        assertTrue(s.delete(id))
        assertEquals(1, s.size())
    }

    @Test
    fun clearRemovesEverything() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("alpha"))
        ok(s.add("beta"))
        s.clear()
        assertEquals(0, s.size())
        assertTrue(s.list().isEmpty())
    }

    // -------------------------------------------------------------- search

    @Test
    fun listSearchIsCaseInsensitive() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("Hello World"))
        ok(s.add("kotlin code"))
        assertEquals(listOf("Hello World"), s.list("hello wor").map { it.text })
        assertEquals(listOf("kotlin code"), s.list("KOTLIN").map { it.text })
    }

    @Test
    fun listSearchNoHitsAndBlankQueryReturnsAll() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("Hello World"))
        ok(s.add("kotlin code"))
        assertTrue(s.list("zzz").isEmpty())
        assertEquals(2, s.list(null).size)
        assertEquals(2, s.list("   ").size)
    }

    @Test
    fun pinnedFirstOrderingOverNewerUnpinned() {
        val s = ClipboardHistoryStore(InMemoryKeyValueStore())
        ok(s.add("alpha"))
        ok(s.add("beta"))
        ok(s.add("gamma"))
        assertTrue(s.pin(s.list().last().id)) // pin the oldest entry
        assertEquals(listOf("alpha", "gamma", "beta"), s.list().map { it.text })
    }

    // ------------------------------------------------------------- storage

    @Test
    fun corruptStorageLoadsEmptyAndRecovers() {
        val kv = InMemoryKeyValueStore()
        kv.putString(ClipboardHistoryStore.KEY, "this is not json")
        val s = ClipboardHistoryStore(kv)
        assertEquals(0, s.size())
        assertTrue(ok(s.add("fresh")).isNotEmpty())
        assertEquals(1, s.size())
    }

    @Test
    fun entryMissingTextIsSkippedOnLoad() {
        val kv = InMemoryKeyValueStore()
        val arr = JSONArray()
        arr.put(JSONObject().put("id", "c9")) // no text field
        arr.put(
            JSONObject()
                .put("id", "c10")
                .put("text", "ok")
                .put("createdAt", 5L)
                .put("pinned", true),
        )
        kv.putString(
            ClipboardHistoryStore.KEY,
            JSONObject().put("entries", arr).toString(),
        )
        val s = ClipboardHistoryStore(kv)
        val only = s.list().single()
        assertEquals("ok", only.text)
        assertEquals(5L, only.createdAt)
        assertTrue(only.pinned)
    }
}
