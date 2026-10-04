package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.GitPath
import com.secretarrow.rockedit.remote.GitHubApi
import com.secretarrow.rockedit.remote.GitLabApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitPathTest {
    private val base = GitPath.Base("octocat", "hello", "main")

    @Test
    fun parseBaseAcceptsOwnerRepoBranch() {
        val parsed = GitPath.parseBase("octocat/hello/main")
        assertEquals("octocat", parsed?.owner)
        assertEquals("hello", parsed?.repo)
        assertEquals("main", parsed?.branch)
    }

    @Test
    fun parseBaseKeepsSlashedBranches() {
        val parsed = GitPath.parseBase("/octocat/hello/feature/x")
        assertEquals("feature/x", parsed?.branch)
    }

    @Test
    fun parseBaseRejectsIncompletePaths() {
        assertNull(GitPath.parseBase("/"))
        assertNull(GitPath.parseBase("octocat"))
        assertNull(GitPath.parseBase("octocat/hello"))
    }

    @Test
    fun repoRelativeMapsMountAndBelow() {
        assertEquals("", GitPath.repoRelative(base, "/octocat/hello/main"))
        assertEquals("docs/readme.md", GitPath.repoRelative(base, "/octocat/hello/main/docs/readme.md"))
        assertNull(GitPath.repoRelative(base, "/octocat/hello"))
        assertNull(GitPath.repoRelative(base, "/other/repo/main"))
    }

    @Test
    fun nextSegmentWalksTowardMount() {
        assertEquals("octocat", GitPath.nextSegmentTowardBase(base, "/"))
        assertEquals("hello", GitPath.nextSegmentTowardBase(base, "/octocat"))
        assertEquals("main", GitPath.nextSegmentTowardBase(base, "/octocat/hello"))
        assertNull(GitPath.nextSegmentTowardBase(base, "/octocat/hello/main"))
    }
}

class GitApiTest {
    private val contentsJson =
        """
        [
          {"name":"src","path":"src","type":"dir","size":0},
          {"name":"README.md","path":"README.md","type":"file","size":1024}
        ]
        """.trimIndent()

    @Test
    fun githubContentsParsesFoldersAndFiles() {
        val files = GitHubApi.parseContents(contentsJson, "/octocat/hello/main")
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertEquals("/octocat/hello/main/src", files[0].path)
        assertEquals("README.md", files[1].name)
        assertEquals(1024, files[1].size)
    }

    @Test
    fun githubContentsHandlesGarbage() {
        assertTrue(GitHubApi.parseContents("{", "/x").isEmpty())
    }

    @Test
    fun githubFileEntryExtractsShaAndDecodesContent() {
        val payload =
            java.util.Base64
                .getEncoder()
                .encodeToString("hello world".toByteArray())
        val entry =
            GitHubApi.parseFileEntry(
                """{"sha":"abc123","content":"$payload\n"}""",
            )
        assertEquals("abc123", entry?.first)
        assertEquals("hello world", String(GitHubApi.decodeContent(entry!!.second)))
    }

    @Test
    fun githubPutBodyIncludesShaOnlyWhenUpdating() {
        val created = JSONObject(GitHubApi.buildPutBody("a.md", "main", "QQ==", null))
        assertFalse(created.has("sha"))
        assertEquals("Update a.md (via Rock Edit)", created.getString("message"))

        val updated = JSONObject(GitHubApi.buildPutBody("a.md", "main", "QQ==", "sha456"))
        assertEquals("sha456", updated.getString("sha"))
    }

    @Test
    fun githubDeleteBodyCarriesSha() {
        val body = JSONObject(GitHubApi.buildDeleteBody("a.md", "main", "sha1"))
        assertEquals("sha1", body.getString("sha"))
        assertEquals("main", body.getString("branch"))
    }

    @Test
    fun gitlabProjectIdEncodesSlash() {
        assertEquals("octocat%2Fhello", GitLabApi.projectId("octocat", "hello"))
        assertEquals("docs%2Freadme.md", GitLabApi.filePath("docs/readme.md"))
    }

    @Test
    fun gitlabTreeParsesEntries() {
        val tree =
            """
            [
              {"id":"1","name":"docs","type":"tree","path":"docs"},
              {"id":"2","name":"app.rb","type":"blob","path":"app.rb"}
            ]
            """.trimIndent()
        val files = GitLabApi.parseTree(tree, "/o/r/main")
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertTrue(!files[1].isFolder)
        assertEquals("/o/r/main/app.rb", files[1].path)
    }

    @Test
    fun gitlabCommitBodyCarriesActionAndEncoding() {
        val body =
            JSONObject(
                GitLabApi.buildCommitBody("main", "msg", "create", "a.md", "QQ=="),
            )
        val action = body.getJSONArray("actions").getJSONObject(0)
        assertEquals("create", action.getString("action"))
        assertEquals("base64", action.getString("encoding"))
        assertEquals("a.md", action.getString("file_path"))
    }
}
