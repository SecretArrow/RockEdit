package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for user-imported TextMate grammars (v0.17.0), backed by
 * [KeyValueStore] so it runs in plain JVM unit tests. The raw imported JSON
 * is persisted verbatim as a JSON string value; parsing happens only in
 * [loadIntoRegistry], so a grammar written by a future parser version is
 * never lost.
 *
 * Case map (defensive rules, never throws):
 * - Import with a blank id, an empty grammar payload or no valid extension
 *   after normalization is a silent no-op (the UI validates upstream);
 *   a blank name falls back to the id.
 * - Import replaces the entry with the same id AND removes every OTHER entry
 *   claiming one of the same extensions - one extension belongs to exactly
 *   one grammar, so the last import wins a conflicted extension.
 * - Entry ids are the caller's (conventionally [TmLanguageParser.CUSTOM_ID_PREFIX]
 *   plus the requested id used at parse time, so the registered language id
 *   round-trips); ids are stored verbatim, not re-prefixed.
 * - Corrupt or missing storage data loads as an empty library (fail-safe);
 *   entries missing required fields are skipped.
 * - [entryFor] and [loadIntoRegistry] resolve duplicated extensions in
 *   persisted (insertion) order: the FIRST entry wins. Note that [entries]
 *   sorts by name for display only - resolution order is the stored order.
 */
class CustomGrammarStore(
    private val store: KeyValueStore,
) {
    /** One imported grammar. [extensions] is lowercase, without dots. */
    data class Entry(
        val id: String,
        val name: String,
        val extensions: List<String>,
        val grammarJson: String,
    )

    /** All entries sorted by name (display order; see class KDoc). */
    fun entries(): List<Entry> = load().sortedBy { it.name.lowercase() }

    /** First entry (stored order) claiming [extension]; null when none. */
    fun entryFor(extension: String): Entry? {
        val wanted = TmLanguageParser.normalizeExtension(extension)
        if (wanted.isEmpty()) return null
        return load().firstOrNull { entry -> entry.extensions.any { it == wanted } }
    }

    /**
     * Inserts or replaces a grammar. Replaces the entry with [id] and removes
     * every OTHER entry whose extensions intersect the new ones (one
     * extension = one grammar). No-op on a blank id, an empty payload or no
     * valid extension after normalization.
     */
    fun import(
        id: String,
        name: String,
        extensions: List<String>,
        grammarJson: String,
    ) {
        val normId = id.trim()
        if (normId.isEmpty() || grammarJson.isEmpty()) return
        val normExtensions =
            extensions
                .mapNotNull { TmLanguageParser.normalizeExtension(it).ifEmpty { null } }
                .distinct()
        if (normExtensions.isEmpty()) return
        val normName = name.trim().ifEmpty { normId }
        val all = load().toMutableList()
        all.removeAll { entry ->
            entry.id == normId || entry.extensions.any { it in normExtensions }
        }
        all.add(Entry(normId, normName, normExtensions, grammarJson))
        save(all)
    }

    /** Removes the entry with [id]; unknown ids are a no-op. */
    fun remove(id: String) {
        val all = load().toMutableList()
        if (all.removeAll { it.id == id }) {
            save(all)
        }
    }

    /**
     * Rebuilds the custom part of [SyntaxRegistry] from this store: every
     * entry is parsed and successful ones are registered; failures are
     * skipped (kept in the store) and reported by [loadIntoRegistryReport].
     * The registry is cleared first so it always mirrors this store.
     */
    fun loadIntoRegistry() {
        loadIntoRegistryReport()
    }

    /** Same as [loadIntoRegistry], returning one description per failure. */
    fun loadIntoRegistryReport(): List<String> {
        SyntaxRegistry.clearCustomLanguages()
        val failures = ArrayList<String>()
        for (entry in load()) {
            val requestedId = entry.id.removePrefix(TmLanguageParser.CUSTOM_ID_PREFIX)
            val result =
                TmLanguageParser.parse(entry.grammarJson, requestedId, entry.extensions)
            when (result) {
                is TmLanguageParser.ParseResult.Success ->
                    // The USER's entry name wins over the grammar's internal
                    // "name"/scopeName: the UI (manage list, status bar) shows
                    // the name the user typed at import time. SyntaxLanguage
                    // is a plain class, so rebuild it explicitly.
                    SyntaxRegistry.registerCustomLanguage(
                        SyntaxLanguage(
                            id = result.language.id,
                            displayName = entry.name,
                            keywords = result.language.keywords,
                            lineComments = result.language.lineComments,
                            blockComments = result.language.blockComments,
                            stringDelims = result.language.stringDelims,
                            caseInsensitive = result.language.caseInsensitive,
                            extensions = result.language.extensions,
                        ),
                    )
                is TmLanguageParser.ParseResult.Failure ->
                    failures.add("${entry.name}: ${result.code} - ${result.message}")
            }
        }
        return failures
    }

    // ------------------------------------------------------------ internals

    private fun load(): List<Entry> {
        val raw = store.getString(KEY, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    // Defensive: only well-typed, complete entries are kept.
                    val id = obj.opt(F_ID) as? String ?: continue
                    val grammarJson = obj.opt(F_JSON) as? String ?: continue
                    if (id.isEmpty() || grammarJson.isEmpty()) continue
                    val name = obj.opt(F_NAME) as? String ?: ""
                    val extensions =
                        obj
                            .optJSONArray(F_EXTENSIONS)
                            ?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }
                            ?: emptyList()
                    add(Entry(id, name, extensions, grammarJson))
                }
            }
        } catch (_: Exception) {
            // Corrupt data: fail-safe empty library, clean file on next save.
            emptyList()
        }
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (entry in entries) {
            val extensions = JSONArray()
            entry.extensions.forEach { extensions.put(it) }
            arr.put(
                JSONObject()
                    .put(F_ID, entry.id)
                    .put(F_NAME, entry.name)
                    .put(F_EXTENSIONS, extensions)
                    .put(F_JSON, entry.grammarJson),
            )
        }
        store.putString(KEY, arr.toString())
    }

    companion object {
        const val KEY = "custom_grammars_v1"
        private const val F_ID = "id"
        private const val F_NAME = "name"
        private const val F_EXTENSIONS = "extensions"
        private const val F_JSON = "grammarJson"
    }
}
