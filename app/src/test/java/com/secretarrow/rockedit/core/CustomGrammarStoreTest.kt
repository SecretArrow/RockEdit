package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [CustomGrammarStore] (v0.17.0): import validation and
 * conflict rules, replace-by-id, first-wins extension resolution, corrupt
 * storage recovery, and registry loading (success, skipped failure with
 * report, and mirror-after-remove). Uses an in-memory [KeyValueStore] fake.
 */
class CustomGrammarStoreTest {
    private val kv = InMemoryKeyValueStore()
    private val store = CustomGrammarStore(kv)

    @After
    fun cleanRegistry() {
        // Tests register into the global SyntaxRegistry; drop everything so
        // the suite stays order-independent.
        SyntaxRegistry.clearCustomLanguages()
    }

    /** Minimal valid grammar whose single keyword is [word]. */
    private fun grammarJson(word: String): String =
        """{"scopeName": "source.$word", """ +
            """"patterns": [{"name": "keyword.control.t", "match": "\\b($word)\\b"}]}"""

    private fun import(
        id: String,
        name: String,
        extensions: List<String>,
        word: String = "demo",
    ) {
        store.import(id, name, extensions, grammarJson(word))
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun importThenEntriesRoundTripVerbatim() {
        val payload = """{"scopeName": "source.demo", "patterns": [  ], "note": "keep me"}"""
        store.import("custom_demo", "DemoLang", listOf("dm"), payload)
        val entries = store.entries()
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("custom_demo", entry.id)
        assertEquals("DemoLang", entry.name)
        assertEquals(listOf("dm"), entry.extensions)
        assertEquals(payload, entry.grammarJson)
    }

    @Test
    fun entriesSurviveReopen() {
        import("custom_a", "Beta", listOf("b1"), "beta")
        import("custom_b", "Alpha", listOf("a1"), "alpha")
        val reopened = CustomGrammarStore(kv)
        assertEquals(store.entries(), reopened.entries())
        assertEquals(listOf("Alpha", "Beta"), reopened.entries().map { it.name })
    }

    // ---------------------------------------------------------------- import

    @Test
    fun importReplacesSameId() {
        import("custom_demo", "Old", listOf("old"), "while")
        import("custom_demo", "New", listOf("new"), "if")
        val entries = store.entries()
        assertEquals(1, entries.size)
        assertEquals("New", entries.single().name)
        assertEquals(listOf("new"), entries.single().extensions)
    }

    @Test
    fun importRemovesConflictingExtension() {
        import("custom_a", "A", listOf("py"), "alpha")
        import("custom_b", "B", listOf("py"), "beta")
        assertEquals(listOf("B"), store.entries().map { it.name })
    }

    @Test
    fun importConflictsIntersectOnAnyExtension() {
        import("custom_a", "A", listOf("py"), "alpha")
        import("custom_b", "B", listOf("pyw"), "beta")
        import("custom_c", "C", listOf("py", "pyw"), "gamma")
        assertEquals(listOf("C"), store.entries().map { it.name })
        assertEquals(listOf("py", "pyw"), store.entries().single().extensions)
    }

    @Test
    fun invalidImportsAreNoOp() {
        store.import("", "A", listOf("py"), "{}")
        store.import("custom_b", "B", listOf("py"), "")
        store.import("custom_c", "C", listOf("  ", "..."), "{}")
        assertTrue(store.entries().isEmpty())
        assertFalse(kv.contains(CustomGrammarStore.KEY))
    }

    @Test
    fun blankNameFallsBackToId() {
        import("custom_demo", "   ", listOf("dm"))
        assertEquals("custom_demo", store.entries().single().name)
    }

    @Test
    fun extensionNormalizationOnImportAndLookup() {
        import("custom_demo", "Demo", listOf("PY", "*.Py2"))
        assertEquals(listOf("py", "py2"), store.entries().single().extensions)
        assertEquals("custom_demo", store.entryFor("py")?.id)
        assertEquals("custom_demo", store.entryFor(".PY")?.id)
        assertEquals("custom_demo", store.entryFor("py2")?.id)
        assertNull(store.entryFor("py3"))
        assertNull(store.entryFor(""))
    }

    // ---------------------------------------------------------------- remove

    @Test
    fun removeDeletesEntryAndUnknownIdIsNoOp() {
        import("custom_demo", "Demo", listOf("dm"))
        store.remove("custom_other")
        assertEquals(1, store.entries().size)
        store.remove("custom_demo")
        assertTrue(store.entries().isEmpty())
    }

    // ------------------------------------------------------- corrupt storage

    @Test
    fun corruptStoreJsonIsTreatedAsEmpty() {
        kv.putString(CustomGrammarStore.KEY, "{not an array")
        assertTrue(store.entries().isEmpty())
        assertNull(store.entryFor("py"))
        assertTrue(store.loadIntoRegistryReport().isEmpty())
    }

    @Test
    fun malformedEntriesAreSkipped() {
        val arr =
            JSONArray()
                .put("junk")
                .put(JSONObject().put("name", "no id"))
                .put(JSONObject().put("id", "custom_x").put("name", "no json"))
                .put(
                    JSONObject()
                        .put("id", "custom_ok")
                        .put("name", "Ok")
                        .put("extensions", JSONArray().put("ok").put(5))
                        .put("grammarJson", grammarJson("ok")),
                )
        kv.putString(CustomGrammarStore.KEY, arr.toString())
        val entries = store.entries()
        assertEquals(1, entries.size)
        assertEquals("custom_ok", entries.single().id)
        assertEquals(listOf("ok"), entries.single().extensions)
    }

    // --------------------------------------------------------- registry load

    @Test
    fun loadIntoRegistryRegistersAndResolvesFileNames() {
        import("custom_demo", "Demo", listOf("py"), "sparkle")
        assertTrue(store.loadIntoRegistryReport().isEmpty())
        val languages = SyntaxRegistry.customLanguages()
        assertEquals(1, languages.size)
        assertEquals(setOf("sparkle"), languages.single().keywords)
        assertEquals("custom_demo", SyntaxRegistry.languageForFileName("f.py")?.id)
        assertEquals("custom_demo", SyntaxRegistry.languageForFileName("F.PY")?.id)
        assertEquals("Demo", SyntaxRegistry.languageForFileName("f.py")?.displayName)
    }

    @Test
    fun loadIntoRegistrySkipsFailuresAndReportsThem() {
        import("custom_bad", "Bad", listOf("bd"), "alpha")
        // Overwrite the bad entry's payload with a non-grammar JSON object.
        val entries =
            store.entries().map {
                if (it.id == "custom_bad") it.copy(grammarJson = "{}") else it
            }
        kv.putString(CustomGrammarStore.KEY, toJson(entries))
        import("custom_good", "Good", listOf("gd"), "beta")
        val report = store.loadIntoRegistryReport()
        assertEquals(1, report.size)
        assertTrue(report.single().contains("Bad"))
        assertTrue(report.single().contains("NOT_GRAMMAR"))
        assertEquals(1, SyntaxRegistry.customLanguages().size)
        assertEquals("custom_good", SyntaxRegistry.languageForFileName("x.gd")?.id)
        assertNull(SyntaxRegistry.languageForFileName("x.bd"))
    }

    @Test
    fun loadIntoRegistryExtensionPriorityIsFirstStoredEntry() {
        val arr =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "custom_a")
                        .put("name", "Alpha")
                        .put("extensions", JSONArray().put("py"))
                        .put("grammarJson", grammarJson("alpha")),
                )
                .put(
                    JSONObject()
                        .put("id", "custom_b")
                        .put("name", "Beta")
                        .put("extensions", JSONArray().put("py"))
                        .put("grammarJson", grammarJson("beta")),
                )
        kv.putString(CustomGrammarStore.KEY, arr.toString())
        assertEquals("custom_a", store.entryFor("py")?.id)
        assertTrue(store.loadIntoRegistryReport().isEmpty())
        assertEquals(2, SyntaxRegistry.customLanguages().size)
        assertEquals(setOf("alpha"), SyntaxRegistry.languageForFileName("x.py")?.keywords)
    }

    @Test
    fun loadIntoRegistryMirrorsRemoval() {
        import("custom_demo", "Demo", listOf("dm"))
        store.loadIntoRegistry()
        assertEquals(1, SyntaxRegistry.customLanguages().size)
        store.remove("custom_demo")
        store.loadIntoRegistry()
        assertTrue(SyntaxRegistry.customLanguages().isEmpty())
        assertNull(SyntaxRegistry.languageForFileName("f.dm"))
    }

    // ------------------------------------------------------------- internals

    private fun toJson(entries: List<CustomGrammarStore.Entry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            val extensions = JSONArray()
            entry.extensions.forEach { extensions.put(it) }
            arr.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("extensions", extensions)
                    .put("grammarJson", entry.grammarJson),
            )
        }
        return arr.toString()
    }
}
