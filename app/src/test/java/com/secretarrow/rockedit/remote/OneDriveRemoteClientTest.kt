package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudResponse
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-branch tests for [OneDriveRemoteClient] (v0.15.0). */
class OneDriveRemoteClientTest {
    private val connection = RemoteConnection(3L, "OneDrive", RemoteType.ONEDRIVE, "", 443, "", "", clientId = "cid")

    private fun client(fake: FakeCloudHttp) = OneDriveRemoteClient(connection, fake, { "test-token" })

    private fun json(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): CloudResponse = CloudResponse(code, headers, body.toByteArray())

    @Test
    fun `list root uses the root children endpoint`() {
        val fake =
            FakeCloudHttp { _, _, _, _ ->
                json(
                    200,
                    """{"value":[{"name":"Documents","folder":{},"size":0},""" +
                        """{"name":"a.txt","size":42}]}""",
                )
            }
        val files = client(fake).list("/")
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertEquals(-1L, files[0].size)
        assertEquals("/Documents", files[0].path)
        assertEquals(42L, files[1].size)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/children", fake.calls.single().url)
    }

    @Test
    fun `list subfolder encodes each segment and keeps slashes`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(200, """{"value":[]}""") }
        client(fake).list("/My Docs/Sub Folder")
        val url = fake.calls.single().url
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/My+Docs/Sub+Folder:/children", url)
    }

    @Test
    fun `list follows odata next link pages`() {
        var count = 0
        val fake =
            FakeCloudHttp { _, _, _, _ ->
                count += 1
                if (count == 1) {
                    json(200, """{"value":[{"name":"1","size":1}],"@odata.nextLink":"https://graph.microsoft.com/v1.0/me/drive/next"}""")
                } else {
                    json(200, """{"value":[{"name":"2","size":2}]}""")
                }
            }
        val files = client(fake).list("/")
        assertEquals(2, files.size)
        assertEquals(2, fake.calls.size)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/next", fake.calls[1].url)
    }

    @Test
    fun `read follows the pre-authenticated redirect`() {
        var count = 0
        val fake =
            FakeCloudHttp { _, _, _, _ ->
                count += 1
                if (count == 1) {
                    json(302, "", headers = mapOf("Location" to "https://public.example/preauth"))
                } else {
                    CloudResponse(200, emptyMap(), "onedrive-bytes".toByteArray())
                }
            }
        val bytes = client(fake).read("/Docs/a.txt")
        assertEquals("onedrive-bytes", bytes.toString(Charsets.UTF_8))
        assertEquals(2, fake.calls.size)
        assertEquals("https://public.example/preauth", fake.calls[1].url)
        // Authorization must not leak to the redirect host.
        assertTrue(!fake.calls[1].headers.containsKey("Authorization"))
    }

    @Test
    fun `read redirect without location fails informatively`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(302, "") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).read("/a.txt") }
        assertTrue(error.message!!.contains("redirect without Location"))
    }

    @Test
    fun `read uses the content endpoint`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> CloudResponse(200, emptyMap(), "x".toByteArray()) }
        client(fake).read("/Docs/a.txt")
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/Docs/a.txt:/content", fake.calls.single().url)
    }

    @Test
    fun `write puts the bytes at the item content url`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(201, """{"name":"a.txt"}""") }
        client(fake).write("/Docs/a.txt", "data".toByteArray())
        val call = fake.calls.single()
        assertEquals("PUT", call.method)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/Docs/a.txt:/content", call.url)
        assertEquals("data", call.bodyText())
    }

    @Test
    fun `mkdir posts folder facet to the parent children endpoint`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(201, """{"name":"New"}""") }
        client(fake).mkdir("/Docs/New")
        val call = fake.calls.single()
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/Docs:/children", call.url)
        assertTrue(call.bodyText().contains("\"folder\""))
        assertTrue(call.bodyText().contains("\"name\":\"New\""))
    }

    @Test
    fun `mkdir conflict is no-op safe`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(409, """{"error":{"code":"nameAlreadyExists"}}""") }
        client(fake).mkdir("/Docs/New")
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `delete of the root is rejected with a clear message`() {
        val fake = FakeCloudHttp { _, _, _, _ -> throw IllegalStateException("no calls expected") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).delete("/") }
        assertTrue(error.message!!.contains("root"))
    }

    @Test
    fun `delete tolerates 404 and hits the item url`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(404, "") }
        client(fake).delete("/Docs/a.txt")
        val call = fake.calls.single()
        assertEquals("DELETE", call.method)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/Docs/a.txt:", call.url)
    }

    @Test
    fun `failure surfaces HTTP code with the operation`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(500, """{"error":{"code":"generalException"}}""") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).list("/") }
        assertTrue(error.message!!.contains("HTTP 500"))
        assertTrue(error.message!!.contains("OneDrive list of /"))
    }
}
