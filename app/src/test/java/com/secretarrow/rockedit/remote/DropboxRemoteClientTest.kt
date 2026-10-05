package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudResponse
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/** Per-branch tests for [DropboxRemoteClient] (v0.15.0). */
class DropboxRemoteClientTest {
    private val connection = RemoteConnection(2L, "Dropbox", RemoteType.DROPBOX, "", 443, "", "", clientId = "cid")

    private fun client(fake: FakeCloudHttp) = DropboxRemoteClient(connection, fake, { "test-token" })

    private fun json(
        code: Int,
        body: String,
    ): CloudResponse = CloudResponse(code, emptyMap(), body.toByteArray())

    @Test
    fun `list root posts an empty path and maps entries`() {
        val fake =
            FakeCloudHttp { _, _, _, _ ->
                json(
                    200,
                    """{"cursor":"C1","has_more":false,"entries":[""" +
                        """{".tag":"folder","name":"Docs","path_display":"/Docs"},""" +
                        """{".tag":"file","name":"a.txt","path_display":"/a.txt","size":99},""" +
                        """{".tag":"deleted","name":"gone","path_display":"/gone"}]}""",
                )
            }
        val files = client(fake).list("/")
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertEquals(-1L, files[0].size)
        assertEquals("/Docs", files[0].path)
        assertEquals(99L, files[1].size)
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://api.dropboxapi.com/2/files/list_folder", call.url)
        assertTrue(call.bodyText().contains("\"path\":\"\""))
        assertEquals("Bearer test-token", call.headers["Authorization"])
    }

    @Test
    fun `list of a subfolder posts the trimmed path`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(200, """{"cursor":"C","has_more":false,"entries":[]}""") }
        client(fake).list("/Docs/Sub")
        assertTrue(
            fake.calls
                .single()
                .bodyText()
                .contains("\"path\":\"/Docs/Sub\""),
        )
    }

    @Test
    fun `list continues while has_more is true`() {
        var count = 0
        val fake =
            FakeCloudHttp { _, _, _, _ ->
                count += 1
                if (count == 1) {
                    json(200, """{"cursor":"K1","has_more":true,"entries":[{".tag":"file","name":"1","path_display":"/1"}]}""")
                } else {
                    json(200, """{"cursor":"K2","has_more":false,"entries":[{".tag":"file","name":"2","path_display":"/2"}]}""")
                }
            }
        val files = client(fake).list("/")
        assertEquals(2, files.size)
        assertEquals(2, fake.calls.size)
        assertEquals("https://api.dropboxapi.com/2/files/list_folder/continue", fake.calls[1].url)
        assertTrue(fake.calls[1].bodyText().contains("\"cursor\":\"K1\""))
    }

    @Test
    fun `read sends the API arg header and returns bytes`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> CloudResponse(200, emptyMap(), "dropbox-bytes".toByteArray()) }
        val bytes = client(fake).read("/Docs/a.txt")
        assertEquals("dropbox-bytes", bytes.toString(Charsets.UTF_8))
        val call = fake.calls.single()
        assertEquals("https://content.dropboxapi.com/2/files/download", call.url)
        assertTrue(call.headers["Dropbox-API-Arg"]!!.contains("\"path\":\"/Docs/a.txt\""))
    }

    @Test
    fun `read 409 yields a FileNotFoundException with the path`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(409, """{"error_summary":"path/not_found/.."}""") }
        val error =
            assertThrows(FileNotFoundException::class.java) { client(fake).read("/missing.txt") }
        assertTrue(error.message!!.contains("/missing.txt"))
        assertTrue(error.message!!.contains("does not exist on Dropbox"))
    }

    @Test
    fun `write uploads with overwrite mode`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(200, """{".tag":"file","name":"a.txt"}""") }
        client(fake).write("/a.txt", "data".toByteArray())
        val call = fake.calls.single()
        assertEquals("https://content.dropboxapi.com/2/files/upload", call.url)
        assertTrue(call.headers["Dropbox-API-Arg"]!!.contains("\"overwrite\""))
        assertEquals("application/octet-stream", call.headers["Content-Type"])
        assertEquals("data", call.bodyText())
    }

    @Test
    fun `mkdir conflict is no-op safe`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(409, """{"error_summary":"path/conflict/.."}""") }
        client(fake).mkdir("/Docs")
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `mkdir failure surfaces HTTP code`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(500, """{"error":"boom"}""") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).mkdir("/Docs") }
        assertTrue(error.message!!.contains("HTTP 500"))
    }

    @Test
    fun `delete tolerates lookup not found`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(409, """{"error_summary":"path_lookup/not_found/.."}""") }
        client(fake).delete("/gone.txt")
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `delete failure surfaces HTTP code`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(503, """{"error":"rate_limited"}""") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).delete("/x") }
        assertTrue(error.message!!.contains("HTTP 503"))
    }

    @Test
    fun `unauthorized surface has an informative message`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(401, """{"error":"invalid_access_token"}""") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).list("/") }
        assertTrue(error.message!!.contains("HTTP 401"))
        assertTrue(error.message!!.contains("Dropbox list of /"))
    }
}
