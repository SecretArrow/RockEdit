package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.BackupRestore
import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreTest {

    private fun populatedStore(): InMemoryKeyValueStore {
        val kv = InMemoryKeyValueStore()
        kv.putString("theme", "black")
        kv.putBoolean("line_numbers", false)
        kv.putString("font_size", "18")
        kv.putString("recent_files", """[{"uri":"content://a","name":"a.txt","lastOpened":1}]""")
        kv.putString("bookmarks", """[{"uri":"content://a","line":3,"label":"x","createdAt":2}]""")
        return kv
    }

    @Test
    fun exportIncludesOnlySetKeysWithTypes() {
        val kv = populatedStore()
        val backup = BackupRestore(kv)
        val json = backup.export(
            listOf(
                BackupRestore.BackupKey("theme", BackupRestore.BackupKey.EntryType.STRING),
                BackupRestore.BackupKey("line_numbers", BackupRestore.BackupKey.EntryType.BOOLEAN),
                BackupRestore.BackupKey("font_size", BackupRestore.BackupKey.EntryType.STRING),
                BackupRestore.BackupKey("recent_files", BackupRestore.BackupKey.EntryType.JSON),
                BackupRestore.BackupKey("never_set", BackupRestore.BackupKey.EntryType.STRING)
            )
        )
        val obj = JSONObject(json)
        assertEquals("rockedit", obj.getString("app"))
        assertEquals(1, obj.getInt("version"))
        val entries = obj.getJSONArray("entries")
        assertEquals(4, entries.length())
        assertTrue(json.contains("\"key\":\"theme\""))
        assertTrue(json.contains("\"type\":\"string\""))
        assertTrue(json.contains("\"value\":\"black\""))
        assertTrue(json.contains("\"key\":\"line_numbers\""))
        assertTrue(json.contains("\"type\":\"boolean\""))
        assertTrue(json.contains("\"value\":true") || json.contains("\"value\":false"))
        assertTrue(json.contains("\"key\":\"recent_files\""))
        assertFalse(json.contains("never_set"))
    }

    @Test
    fun restoreRoundTripAppliesAllKnownKeys() {
        val source = populatedStore()
        val keys = BackupRestore.defaultKeys()
        val json = BackupRestore(source).export(keys)

        val target = InMemoryKeyValueStore()
        val allowed = keys.map { it.key }.toSet()
        val result = BackupRestore(target).restore(json, allowed)
        assertEquals(0, result.skipped)
        assertEquals(5, result.applied)

        assertEquals("black", target.getString("theme", null))
        assertEquals(false, target.getBoolean("line_numbers", true))
        assertEquals("18", target.getString("font_size", null))
        assertEquals(
            source.getString("recent_files", null),
            target.getString("recent_files", null)
        )
        assertEquals(
            source.getString("bookmarks", null),
            target.getString("bookmarks", null)
        )
    }

    @Test
    fun restoreSkipsUnknownKeys() {
        val target = InMemoryKeyValueStore()
        val payload = """
            {"app":"rockedit","version":1,"entries":[
              {"key":"theme","type":"string","value":"dark"},
              {"key":"evil_key","type":"string","value":"boom"}
            ]}
        """.trimIndent()
        val result = BackupRestore(target).restore(payload, setOf("theme"))
        assertEquals(1, result.applied)
        assertEquals(1, result.skipped)
        assertEquals("dark", target.getString("theme", null))
        assertNull(target.getString("evil_key", null))
    }

    @Test
    fun restoreHandlesMalformedDocuments() {
        val target = InMemoryKeyValueStore()
        assertEquals(0, BackupRestore(target).restore("{broken", setOf("theme")).applied)
        assertEquals(0, BackupRestore(target).restore("[]", setOf("theme")).applied)
        assertEquals(
            0,
            BackupRestore(target).restore("""{"app":"x","entries":[]}""", setOf("theme")).applied
        )
    }

    @Test
    fun restoreSkipsUnknownTypesAndNullValues() {
        val target = InMemoryKeyValueStore()
        val payload = """
            {"entries":[
              {"key":"theme","type":"warp","value":"dark"},
              {"key":"font_size","type":"string","value":null},
              {"key":"line_numbers","type":"boolean","value":true}
            ]}
        """.trimIndent()
        val result = BackupRestore(target).restore(payload, setOf("theme", "font_size", "line_numbers"))
        assertEquals(1, result.applied)
        assertEquals(2, result.skipped)
        assertEquals(true, target.getBoolean("line_numbers", false))
    }

    @Test
    fun defaultKeysCoverStoresAndSettings() {
        val keys = BackupRestore.defaultKeys()
        val ids = keys.map { it.key }
        assertTrue("recent_files" in ids)
        assertTrue("session_cursors" in ids)
        assertTrue("bookmarks" in ids)
        assertTrue("open_tabs" in ids)
        assertTrue("theme" in ids)
        assertTrue("remember_tabs" in ids)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun emptyStoreExportsEmptyDocument() {
        val json = BackupRestore(InMemoryKeyValueStore()).export(BackupRestore.defaultKeys())
        val obj = JSONObject(json)
        assertEquals(0, obj.getJSONArray("entries").length())
    }
}
