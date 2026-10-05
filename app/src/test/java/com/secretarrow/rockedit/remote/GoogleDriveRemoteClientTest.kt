package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudResponse
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/** Per-branch tests for [GoogleDriveRemoteClient] (v0.15.0). */
class GoogleDriveRemoteClientTest {
    private val connection = RemoteConnection(1L, "Drive", RemoteType.GOOGLE_DRIVE, "", 443, "", "", clientId = "cid")

    private fun client(fake: FakeCloudHttp) = GoogleDriveRemoteClient(connection, fake, { "test-token" })

    private fun json(
        code: Int,
        body: String,
    ): CloudResponse = CloudResponse(code, emptyMap(), body.toByteArray())

    private fun filesResponse(
        filesJson: String,
        pageToken: String = "",
    ): String {
        val root = JSONObject()
        root.put("files", JSONArray(filesJson))
        if (pageToken.isNotEmpty()) root.put("nextPageToken", pageToken)
        return root.toString()
    }

    @Test
    fun `list maps folder and file entries`() {
        val fake =
            FakeCloudHttp { method, url, _, _ ->
                assertEquals("GET", method)
                assertTrue(url.startsWith("https://www.googleapis.com/drive/v3/files?q="))
                assertTrue(url.contains("pageSize=200"))
                json(
                    200,
                    filesResponse(
                        """[{"id":"F1","name":"Docs","mimeType":"application/vnd.google-apps.folder"},""" +
                            """{"id":"F2","name":"a.txt","size":"123","modifiedTime":"2026-01-02T03:04:05Z"}]""",
                    ),
                )
            }
        val files = client(fake).list("/")
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertEquals(-1L, files[0].size)
        assertEquals("/Docs", files[0].path)
        assertTrue(!files[1].isFolder)
        assertEquals(123L, files[1].size)
        assertEquals(1767325445000L, files[1].lastModified)
        assertEquals("Bearer test-token", fake.calls[0].headers["Authorization"])
    }

    @Test
    fun `list follows page tokens until exhausted`() {
        var count = 0
        val fake =
            FakeCloudHttp { _, _, _, _ ->
                count += 1
                if (count == 1) {
                    json(200, filesResponse("""[{"id":"A","name":"1","mimeType":"x"}]""", "T2"))
                } else {
                    json(200, filesResponse("""[{"id":"B","name":"2","mimeType":"x"}]"""))
                }
            }
        val files = client(fake).list("/")
        assertEquals(2, files.size)
        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls[1].url.contains("pageToken=T2"))
    }

    @Test
    fun `list of a subfolder resolves the folder id first`() {
        val fake =
            FakeCloudHttp { _, url, _, _ ->
                if (url.contains("name")) {
                    json(200, filesResponse("""[{"id":"PARENT-1","name":"Docs","mimeType":"application/vnd.google-apps.folder"}]"""))
                } else {
                    json(200, filesResponse("""[]"""))
                }
            }
        val files = client(fake).list("/Docs")
        assertEquals(0, files.size)
        assertTrue(fake.calls[0].url.contains("Docs"))
        assertTrue(fake.calls[1].url.contains("PARENT-1"))
    }

    @Test
    fun `missing subfolder yields an actionable FileNotFoundException`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(200, filesResponse("""[]""")) }
        val error =
            assertThrows(FileNotFoundException::class.java) { client(fake).list("/Nope") }
        assertTrue(error.message!!.contains("Nope"))
        assertTrue(error.message!!.contains("create the folder first"))
    }

    @Test
    fun `list failure surfaces HTTP code`() {
        val fake = FakeCloudHttp { _, _, _, _ -> json(500, """{"error":"boom"}""") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).list("/") }
        assertTrue(error.message!!.contains("HTTP 500"))
    }

    @Test
    fun `read uses alt media and returns bytes`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> CloudResponse(200, emptyMap(), "file-bytes".toByteArray()) }
        val bytes = client(fake).read("/a.txt")
        assertEquals("file-bytes", bytes.toString(Charsets.UTF_8))
        assertTrue(
            fake.calls
                .last()
                .url
                .contains("alt=media"),
        )
    }

    @Test
    fun `write creates a new file via multipart upload`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(200, """{"id":"NEW-1"}""") }
        client(fake).write("/new.txt", "hello".toByteArray())
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.url.startsWith("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"))
        val body = call.bodyText()
        assertTrue(body.contains("\"name\":\"new.txt\""))
        assertTrue(body.contains("hello"))
        assertTrue(call.headers["Content-Type"]!!.startsWith("multipart/related; boundary="))
    }

    @Test
    fun `write over an existing file patches content only`() {
        val fake =
            FakeCloudHttp { _, url, _, _ ->
                if (url.contains("name=")) {
                    json(200, filesResponse("""[{"id":"EXIST-1","name":"old.txt"}]"""))
                } else {
                    json(200, """{}""")
                }
            }
        client(fake).write("/old.txt", "data".toByteArray())
        assertEquals(2, fake.calls.size)
        assertEquals("PATCH", fake.calls[1].method)
        assertTrue(fake.calls[1].url.contains("uploadType=media"))
        assertTrue(fake.calls[1].url.contains("EXIST-1"))
        assertEquals("data", fake.calls[1].bodyText())
    }

    @Test
    fun `write into a missing parent fails informatively`() {
        val fake =
            FakeCloudHttp { _, _, _, _ -> json(200, filesResponse("""[]""")) }
        val error =
            assertThrows(FileNotFoundException::class.java) { client(fake).write("/ghost/x.txt", ByteArray(1)) }
        assertTrue(error.message!!.contains("/ghost"))
    }

    @Test
    fun `write with empty file name is rejected`() {
        val fake = FakeCloudHttp { _, _, _, _ -> json(200, "{}") }
        val error =
            assertThrows(IllegalStateException::class.java) { client(fake).write("/", ByteArray(1)) }
        assertTrue(error.message!!.contains("no file name"))
    }

    @Test
    fun `mkdir of an existing folder is a no-op`() {
        val fake =
            FakeCloudHttp { _, url, _, _ ->
                if (url.contains("name=")) {
                    json(200, filesResponse("""[{"id":"FOLDER-1","name":"Docs","mimeType":"application/vnd.google-apps.folder"}]"""))
                } else {
                    throw IllegalStateException("create should not be called")
                }
            }
        client(fake).mkdir("/Docs")
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `mkdir of a new folder posts folder metadata`() {
        val fake =
            FakeCloudHttp { _, url, _, _ ->
                if (url.contains("name=")) {
                    json(200, filesResponse("""[]"""))
                } else {
                    json(200, """{"id":"NEW-F"}""")
                }
            }
        client(fake).mkdir("/Docs")
        assertEquals(2, fake.calls.size)
        val body = fake.calls[1].bodyText()
        assertTrue(body.contains("application/vnd.google-apps.folder"))
        assertTrue(body.contains("\"name\":\"Docs\""))
    }

    @Test
    fun `mkdir of the root is skipped entirely`() {
        val fake = FakeCloudHttp { _, _, _, _ -> throw IllegalStateException("no calls expected") }
        client(fake).mkdir("/")
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `delete hits the file id and tolerates 404`() {
        val fake =
            FakeCloudHttp { _, url, _, _ ->
                if (url.contains("name=")) {
                    json(200, filesResponse("""[{"id":"GONE-1","name":"x"}]"""))
                } else {
                    json(404, """{"error":"not_found"}""")
                }
            }
        client(fake).delete("/x")
        assertEquals(2, fake.calls.size)
        assertEquals("DELETE", fake.calls[1].method)
        assertTrue(fake.calls[1].url.contains("GONE-1"))
    }

    @Test
    fun `single-quoted names are escaped in queries`() {
        val fake =
            FakeCloudHttp { _, url, _, _ ->
                if (url.contains("name=")) {
                    json(200, filesResponse("""[{"id":"Q1","name":"o'brien"}]"""))
                } else {
                    json(200, filesResponse("""[]"""))
                }
            }
        client(fake).list("/o'brien")
        // %5C%27 is the URL-encoded \' escape for the Drive q grammar.
        assertTrue(fake.calls[0].url.contains("%5C%27"))
    }
}
