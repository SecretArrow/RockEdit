package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Clipboard history (v0.13.0): a bounded, pinned-aware list of recently
 * copied texts, persisted as JSON through [KeyValueStore]. Pure JVM so it is
 * fully unit-testable.
 *
 * Case map (defensive rule 1):
 * - Blank (or whitespace-only) text -> BLANK_TEXT; text above
 *   [effectiveMaxEntryChars] -> TEXT_TOO_LARGE with the actual size vs the
 *   cap in the message.
 * - [effectiveMaxEntries] is CLAMPED into [MIN_MAX_ENTRIES]..[MAX_MAX_ENTRIES]
 *   and [effectiveMaxEntryChars] into 1..[MAX_ENTRY_CHARS_LIMIT] (documented
 *   decision: a misconfigured limit degrades gracefully instead of throwing;
 *   the effective values stay readable as properties).
 * - Consecutive duplicate text does NOT create a second entry: the most
 *   recent entry moves to the top with a fresh timestamp, keeping its id and
 *   pinned state. Non-consecutive duplicates stay separate entries.
 * - Eviction (when over [effectiveMaxEntries]): the oldest UNPINNED entry is
 *   dropped first; the just-added entry (top) is never the victim, so adding
 *   valid text always succeeds and is always visible; when every other entry
 *   is pinned, the oldest overall entry is dropped instead.
 * - pin/unpin/delete on a missing id -> `false`, never an exception;
 *   [clear] removes everything.
 * - [list] shows pinned entries first (newest first), then unpinned ones
 *   (newest first). A non-blank query keeps entries whose text contains it
 *   case-insensitively (query trimmed); a blank or null query returns all.
 * - Corrupt or missing storage data loads as an empty history (fail-safe);
 *   entries missing id or text are skipped on load (same pattern as
 *   SnippetStore). Internal order is newest first; the next mutation writes
 *   a clean file. Saving cannot throw: the KeyValueStore contract is
 *   non-blocking in-memory / SharedPreferences (documented decision).
 */
class ClipboardHistoryStore(
    private val kv: KeyValueStore,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS,
) {
    /**
     * Effective entry cap after clamping the constructor request into
     * [MIN_MAX_ENTRIES]..[MAX_MAX_ENTRIES] (raw values never throw).
     */
    val effectiveMaxEntries: Int =
        maxEntries.coerceIn(MIN_MAX_ENTRIES, MAX_MAX_ENTRIES)

    /**
     * Effective per-entry cap after clamping the constructor request into
     * 1..[MAX_ENTRY_CHARS_LIMIT] (raw values never throw).
     */
    val effectiveMaxEntryChars: Int =
        maxEntryChars.coerceIn(1, MAX_ENTRY_CHARS_LIMIT)

    data class Entry(
        val id: String,
        val text: String,
        val createdAt: Long,
        val pinned: Boolean,
    )

    enum class ErrorCode { BLANK_TEXT, TEXT_TOO_LARGE }

    sealed interface AddResult {
        data class Success(
            val entries: List<Entry>,
        ) : AddResult

        data class Failure(
            val code: ErrorCode,
            val message: String,
        ) : AddResult
    }

    /**
     * Adds [text] at the top of the history. Validation runs first (blank
     * maps to [ErrorCode.BLANK_TEXT], oversize to [ErrorCode.TEXT_TOO_LARGE]);
     * see the class KDoc for dedupe and eviction semantics.
     */
    fun add(text: String): AddResult {
        if (text.isBlank()) {
            return AddResult.Failure(ErrorCode.BLANK_TEXT, "clipboard text is blank")
        }
        if (text.length > effectiveMaxEntryChars) {
            return AddResult.Failure(
                ErrorCode.TEXT_TOO_LARGE,
                "text has ${text.length} characters, limit is $effectiveMaxEntryChars",
            )
        }
        val all = load().toMutableList()
        if (all.isNotEmpty() && all.first().text == text) {
            // Consecutive duplicate: move to top, fresh timestamp, keep id/pin.
            val moved = all.removeAt(0).copy(createdAt = System.currentTimeMillis())
            all.add(0, moved)
            save(all)
            return AddResult.Success(all)
        }
        all.add(0, Entry(nextId(all), text, System.currentTimeMillis(), pinned = false))
        while (all.size > effectiveMaxEntries) {
            all.removeAt(victimIndex(all))
        }
        save(all)
        return AddResult.Success(all)
    }

    /**
     * History entries, pinned first (newest first) then unpinned (newest
     * first). A non-blank [query] keeps only entries whose text contains it
     * case-insensitively (trimmed); blank or null returns everything.
     */
    fun list(query: String? = null): List<Entry> {
        val all = load()
        val filtered =
            if (query.isNullOrBlank()) {
                all
            } else {
                val needle = query.trim()
                if (needle.isEmpty()) {
                    all
                } else {
                    all.filter { it.text.contains(needle, ignoreCase = true) }
                }
            }
        val (pinned, unpinned) = filtered.partition { it.pinned }
        return pinned + unpinned
    }

    /** Pins an entry (missing id -> `false`). */
    fun pin(id: String): Boolean = setPinned(id, true)

    /** Unpins an entry (missing id -> `false`). */
    fun unpin(id: String): Boolean = setPinned(id, false)

    /** Deletes one entry (missing id -> `false`). */
    fun delete(id: String): Boolean {
        val all = load().toMutableList()
        val removed = all.removeAll { it.id == id }
        if (removed) {
            save(all)
        }
        return removed
    }

    /** Removes the whole history. */
    fun clear() {
        kv.remove(KEY)
    }

    /** Current entry count as persisted (before any pin-first ordering). */
    fun size(): Int = load().size

    // ------------------------------------------------------------ internals

    /**
     * Index of the entry to evict: the oldest unpinned one, except the
     * just-added entry (index 0) which is never a victim; when every other
     * entry is pinned the oldest overall entry is dropped instead.
     */
    private fun victimIndex(all: List<Entry>): Int {
        val oldestUnpinned = all.indexOfLast { !it.pinned }
        return if (oldestUnpinned > 0) oldestUnpinned else all.size - 1
    }

    private fun setPinned(
        id: String,
        pinned: Boolean,
    ): Boolean {
        val all = load().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) {
            return false
        }
        all[index] = all[index].copy(pinned = pinned)
        save(all)
        return true
    }

    private fun load(): List<Entry> {
        val raw = kv.getString(KEY, null) ?: return emptyList()
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray(F_ENTRIES) ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString(F_ID)
                    val text = o.optString(F_TEXT)
                    // Defensive: skip entries missing required fields instead
                    // of failing the whole history. Stored text is never
                    // blank because add() rejects blank input.
                    if (id.isEmpty() || text.isEmpty()) continue
                    add(
                        Entry(
                            id = id,
                            text = text,
                            createdAt = o.optLong(F_CREATED, 0L),
                            pinned = o.optBoolean(F_PINNED, false),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            // Corrupt data: fail-safe empty history, clean file on next save.
            emptyList()
        }
    }

    private fun save(all: List<Entry>) {
        val arr = JSONArray()
        for (e in all) {
            arr.put(
                JSONObject()
                    .put(F_ID, e.id)
                    .put(F_TEXT, e.text)
                    .put(F_CREATED, e.createdAt)
                    .put(F_PINNED, e.pinned),
            )
        }
        kv.putString(KEY, JSONObject().put(F_ENTRIES, arr).toString())
    }

    private fun nextId(all: List<Entry>): String {
        var max = 0
        for (e in all) {
            val n = e.id.removePrefix(ID_PREFIX).toIntOrNull() ?: continue
            if (n > max) {
                max = n
            }
        }
        return ID_PREFIX + (max + 1)
    }

    companion object {
        const val KEY = "clipboard_history_v1"
        const val DEFAULT_MAX_ENTRIES = 50
        const val DEFAULT_MAX_ENTRY_CHARS = 100_000
        const val MIN_MAX_ENTRIES = 5
        const val MAX_MAX_ENTRIES = 200
        const val MAX_ENTRY_CHARS_LIMIT = 1_000_000
        private const val ID_PREFIX = "c"
        private const val F_ENTRIES = "entries"
        private const val F_ID = "id"
        private const val F_TEXT = "text"
        private const val F_CREATED = "createdAt"
        private const val F_PINNED = "pinned"
    }
}
