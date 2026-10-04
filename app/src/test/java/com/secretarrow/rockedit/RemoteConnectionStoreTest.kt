package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.PlainEncryptor
import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteConnectionStore
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.core.RemoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {

    @Test
    fun childJoinsAndNormalizes() {
        assertEquals("/b", RemotePath.child("/", "b"))
        assertEquals("/a/b", RemotePath.child("/a", "b"))
        assertEquals("/a/b", RemotePath.child("/a/", "b"))
        assertEquals("/b", RemotePath.child("", "b"))
    }

    @Test
    fun parentStopsAtRoot() {
        assertEquals("/", RemotePath.parent("/"))
        assertEquals("/", RemotePath.parent("/a"))
        assertEquals("/a", RemotePath.parent("/a/b"))
        assertEquals("/a/b", RemotePath.parent("/a/b/c"))
    }

    @Test
    fun nameReturnsLastSegment() {
        assertEquals("", RemotePath.name("/"))
        assertEquals("b", RemotePath.name("/a/b"))
        assertEquals("b", RemotePath.name("/a/b/"))
    }

    @Test
    fun normalizeCollapsesSlashesAndEnsuresRoot() {
        assertEquals("/", RemotePath.normalize(""))
        assertEquals("/", RemotePath.normalize("  "))
        assertEquals("/a/b", RemotePath.normalize("a/b"))
        assertEquals("/a/b", RemotePath.normalize("/a//b/"))
        assertEquals("/", RemotePath.normalize("///"))
    }

    @Test
    fun resolvePortUsesDefaults() {
        assertEquals(21, RemotePath.resolvePort(RemoteType.FTP, 0))
        assertEquals(21, RemotePath.resolvePort(RemoteType.FTPS, 0))
        assertEquals(22, RemotePath.resolvePort(RemoteType.SFTP, 0))
        assertEquals(2222, RemotePath.resolvePort(RemoteType.SFTP, 2222))
    }

    @Test
    fun webDavUrlBuildsSchemeAndPath() {
        assertEquals(
            "http://host:8080/dav/dir",
            RemotePath.webDavUrl("host", 8080, "/dav/dir", https = false)
        )
        assertEquals(
            "https://host:443/dav",
            RemotePath.webDavUrl("host", 443, "dav", https = true)
        )
    }
}

class RemoteConnectionStoreTest {

    private fun connection(name: String, password: String = "secret") = RemoteConnection(
        id = RemoteConnection.newId(),
        name = name,
        type = RemoteType.SFTP,
        host = "example.org",
        port = 0,
        user = "user",
        password = password,
        initialPath = "docs/"
    )

    @Test
    fun saveEncryptsAndFindDecrypts() {
        val store = RemoteConnectionStore(InMemoryKeyValueStore(), PlainEncryptor)
        store.save(connection("home"))
        val found = store.list().first()
        assertEquals("secret", store.find(found.id)?.password)
    }

    @Test
    fun savedJsonNeverContainsPlainPassword() {
        val kv = InMemoryKeyValueStore()
        val store = RemoteConnectionStore(kv, PlainEncryptor)
        store.save(connection("home"))
        val raw = kv.getString(RemoteConnectionStore.KEY, null) ?: ""
        assertTrue(raw.isNotEmpty())
        assertTrue("plain password leaked: $raw", !raw.contains("\"password\":\"secret\""))
        assertTrue(raw.contains("plain:secret"))
    }

    @Test
    fun updateReplacesById() {
        val store = RemoteConnectionStore(InMemoryKeyValueStore(), PlainEncryptor)
        val c = connection("home")
        store.save(c)
        store.save(c.copy(name = "renamed", host = "other.org"))
        val all = store.list()
        assertEquals(1, all.size)
        assertEquals("renamed", all[0].name)
        assertEquals("other.org", all[0].host)
    }

    @Test
    fun removeDeletesEntry() {
        val store = RemoteConnectionStore(InMemoryKeyValueStore(), PlainEncryptor)
        val c = connection("home")
        store.save(c)
        store.save(connection("work"))
        store.remove(c.id)
        assertEquals(1, store.list().size)
        assertNull(store.find(c.id))
    }

    @Test
    fun defaultsAndNormalization() {
        val store = RemoteConnectionStore(InMemoryKeyValueStore(), PlainEncryptor)
        val saved = store.save(
            connection("home").copy(port = 0, initialPath = "docs/")
        ).first()
        assertEquals(22, saved.port)
        assertEquals("/docs", saved.initialPath)
    }

    @Test
    fun corruptStorageYieldsEmptyList() {
        val kv = InMemoryKeyValueStore()
        kv.putString(RemoteConnectionStore.KEY, "{{{not json")
        assertTrue(RemoteConnectionStore(kv, PlainEncryptor).list().isEmpty())
    }
}
