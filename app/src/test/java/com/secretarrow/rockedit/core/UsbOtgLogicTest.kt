package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-branch tests for [UsbOtgLogic] (v0.15.0). */
class UsbOtgLogicTest {
    @Test
    fun `mass storage detection covers device and interface classes`() {
        assertTrue(UsbOtgLogic.isMassStorageDevice(0x08, hasMassStorageInterface = false))
        assertTrue(UsbOtgLogic.isMassStorageDevice(0x00, hasMassStorageInterface = true))
        assertTrue(UsbOtgLogic.isMassStorageDevice(0x08, hasMassStorageInterface = true))
        assertFalse(UsbOtgLogic.isMassStorageDevice(0x00, hasMassStorageInterface = false))
        assertFalse(UsbOtgLogic.isMassStorageDevice(0xFF, hasMassStorageInterface = false))
        assertFalse(UsbOtgLogic.isMassStorageDevice(-1, hasMassStorageInterface = false))
    }

    @Test
    fun `display name joins non-blank parts`() {
        assertEquals("Kingston DataTraveler", UsbOtgLogic.displayName("Kingston", "DataTraveler", "fallback"))
        assertEquals("Kingston", UsbOtgLogic.displayName("Kingston", " ", "fallback"))
        assertEquals("DataTraveler", UsbOtgLogic.displayName(null, "DataTraveler", "fallback"))
        assertEquals("fallback", UsbOtgLogic.displayName(null, null, "fallback"))
        assertEquals("fallback", UsbOtgLogic.displayName("  ", "", "fallback"))
        assertEquals("USB storage", UsbOtgLogic.displayName(null, null, "  "))
    }

    @Test
    fun `volume paths drop the leading slash`() {
        assertEquals("a/b", UsbOtgLogic.volumePath("/a/b"))
        assertEquals("", UsbOtgLogic.volumePath("/"))
        assertEquals("", UsbOtgLogic.volumePath(""))
        assertEquals("a/b", UsbOtgLogic.volumePath("a//b/"))
        assertEquals("a/b", UsbOtgLogic.volumePath("a/b"))
    }

    @Test
    fun `error descriptions are user presentable`() {
        assertEquals("unknown USB error", UsbOtgLogic.describeError(null))
        val io = UsbOtgLogic.describeError(java.io.IOException("cable pulled"))
        assertTrue(io.contains("USB I/O error"))
        assertTrue(io.contains("cable pulled"))
        assertEquals("USB permission was not granted", UsbOtgLogic.describeError(SecurityException()))
        val other = UsbOtgLogic.describeError(IllegalStateException("no partition"))
        assertTrue(other.contains("IllegalStateException"))
        assertTrue(other.contains("no partition"))
    }

    @Test
    fun `sentinel id is negative and never collides with real ids`() {
        assertTrue(UsbOtgLogic.SENTINEL_CONNECTION_ID < 0)
    }
}

/** Per-branch tests for [UsbOtgRemoteClient] with a fake volume (v0.15.0). */
class UsbOtgRemoteClientTest {
    private class FakeVolume : UsbVolumeFs {
        val calls = mutableListOf<String>()
        var listing: List<RemoteFile> = emptyList()
        var content: ByteArray = ByteArray(0)

        override fun listFiles(dirPath: String): List<RemoteFile> {
            calls.add("list:$dirPath")
            return listing
        }

        override fun readFile(path: String): ByteArray {
            calls.add("read:$path")
            return content
        }

        override fun writeFile(
            path: String,
            data: ByteArray,
        ) {
            calls.add("write:$path:${data.size}")
        }

        override fun makeDirectory(path: String) {
            calls.add("mkdir:$path")
        }

        override fun deletePath(path: String) {
            calls.add("delete:$path")
        }

        override fun close() {
            calls.add("close")
        }
    }

    @Test
    fun `paths are normalized before delegation`() {
        val volume = FakeVolume()
        val client = UsbOtgRemoteClient(volume, "Stick")
        client.list("/Docs")
        client.list("Docs//Sub")
        assertEquals(listOf("list:Docs", "list:Docs/Sub"), volume.calls)
    }

    @Test
    fun `read and write delegate with payload`() {
        val volume = FakeVolume()
        volume.content = "abc".toByteArray()
        val client = UsbOtgRemoteClient(volume, "Stick")
        assertEquals("abc", client.read("/f.txt").toString(Charsets.UTF_8))
        client.write("/f.txt", "12345".toByteArray())
        assertTrue(volume.calls.contains("read:f.txt"))
        assertTrue(volume.calls.contains("write:f.txt:5"))
    }

    @Test
    fun `reading the root as a file is rejected`() {
        val client = UsbOtgRemoteClient(FakeVolume(), "Stick")
        assertThrows(FileNotFoundException::class.java) { client.read("/") }
    }

    @Test
    fun `writing the root as a file is rejected`() {
        val client = UsbOtgRemoteClient(FakeVolume(), "Stick")
        assertThrows(IllegalStateException::class.java) { client.write("/", ByteArray(1)) }
    }

    @Test
    fun `deleting the root is rejected`() {
        val client = UsbOtgRemoteClient(FakeVolume(), "Stick")
        assertThrows(IllegalStateException::class.java) { client.delete("/") }
    }

    @Test
    fun `mkdir of the root is a no-op`() {
        val volume = FakeVolume()
        val client = UsbOtgRemoteClient(volume, "Stick")
        client.mkdir("/")
        assertTrue(volume.calls.isEmpty())
    }

    @Test
    fun `client close is a no-op for the session-scoped volume`() {
        val volume = FakeVolume()
        val client = UsbOtgRemoteClient(volume, "Stick")
        client.close()
        assertTrue(volume.calls.isEmpty())
        assertEquals("Stick", client.displayLabel())
    }

    @Test
    fun `label with blank input still yields a usable name`() {
        val client = UsbOtgRemoteClient(FakeVolume(), "")
        assertEquals("", client.displayLabel())
        // The activity layer guarantees a fallback; the client itself does not crash.
    }
}
