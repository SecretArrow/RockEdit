package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.GitPath
import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.core.RemoteType
import java.net.HttpURLConnection
import java.net.URL

/**
 * Git hosting transport: GitHub and GitLab via Personal Access Token.
 *
 * The repository is mounted at `owner/repo/branch` inside the remote browser
 * (from [RemoteConnection.initialPath]); reads and writes map onto the
 * contents/commits APIs, so saving in the editor creates a real commit.
 */
class GitRemoteClient(
    private val connection: RemoteConnection,
) : RemoteClient {
    private val type: RemoteType = connection.type
    private val base: GitPath.Base? = GitPath.parseBase(connection.initialPath)

    // ------------------------------------------------------------- navigation

    override fun list(path: String): List<RemoteFile> {
        val b = requireBase()
        val normalized = RemotePath.normalize(path)
        val rel = GitPath.repoRelative(b, normalized)
        return if (rel != null) {
            listRepo(b, normalized, rel)
        } else {
            val next =
                GitPath.nextSegmentTowardBase(b, normalized)
                    ?: throw IllegalArgumentException("Path outside repository: $path")
            listOf(
                RemoteFile(
                    name = next,
                    path = RemotePath.child(normalized, next),
                    isFolder = true,
                ),
            )
        }
    }

    private fun listRepo(
        b: GitPath.Base,
        fullPath: String,
        repoRel: String,
    ): List<RemoteFile> {
        val url =
            when (type) {
                RemoteType.GITHUB -> {
                    val contentsPart = if (repoRel.isEmpty()) "contents" else "contents/$repoRel"
                    "$API_ROOT/repos/${b.owner}/${b.repo}/$contentsPart?ref=${urlEncode(b.branch)}"
                }
                RemoteType.GITLAB ->
                    "$gitlabRoot/projects/${GitLabApi.projectId(b.owner, b.repo)}/repository/tree" +
                        "?ref=${urlEncode(b.branch)}" +
                        (if (repoRel.isEmpty()) "" else "&path=${urlEncode(repoRel)}") +
                        "&per_page=100"
                else -> throw IllegalArgumentException("Not a git connection: $type")
            }
        val body = get(url)
        return when (type) {
            RemoteType.GITHUB -> GitHubApi.parseContents(body, fullPath)
            else -> GitLabApi.parseTree(body, fullPath)
        }
    }

    // ------------------------------------------------------------------- I/O

    override fun read(path: String): ByteArray {
        val b = requireBase()
        val rel = requireRepoRel(b, path)
        return when (type) {
            RemoteType.GITHUB -> {
                val url = "$API_ROOT/repos/${b.owner}/${b.repo}/contents/$rel?ref=${urlEncode(b.branch)}"
                val entry =
                    GitHubApi.parseFileEntry(get(url))
                        ?: throw IllegalStateException("Not a file: $path")
                if (entry.second.length > MAX_CONTENT_BASE64) {
                    throw IllegalStateException("File too large for editing: $path")
                }
                GitHubApi.decodeContent(entry.second)
            }
            RemoteType.GITLAB -> {
                val url =
                    "$gitlabRoot/projects/${GitLabApi.projectId(b.owner, b.repo)}/" +
                        "repository/files/${GitLabApi.filePath(rel)}/raw?ref=${urlEncode(b.branch)}"
                get(url).toByteArray(Charsets.UTF_8)
            }
            else -> throw IllegalArgumentException("Not a git connection: $type")
        }
    }

    override fun write(
        path: String,
        data: ByteArray,
    ) {
        val b = requireBase()
        val rel = requireRepoRel(b, path)
        val encoded = GitHubApi.encodeContent(data)
        when (type) {
            RemoteType.GITHUB -> {
                val existing =
                    try {
                        val url = "$API_ROOT/repos/${b.owner}/${b.repo}/contents/$rel?ref=${urlEncode(b.branch)}"
                        GitHubApi.parseFileEntry(get(url))
                    } catch (_: Exception) {
                        null
                    }
                val body = GitHubApi.buildPutBody(rel, b.branch, encoded, existing?.first)
                val url = "$API_ROOT/repos/${b.owner}/${b.repo}/contents/$rel"
                val status = request(url, "PUT", body)
                if (status !in 200..299) throw IllegalStateException("GitHub save failed: HTTP $status")
            }
            RemoteType.GITLAB -> {
                val exists =
                    try {
                        val url =
                            "$gitlabRoot/projects/${GitLabApi.projectId(b.owner, b.repo)}/" +
                                "repository/files/${GitLabApi.filePath(rel)}?ref=${urlEncode(b.branch)}"
                        request(url, "HEAD", null) in 200..299
                    } catch (_: Exception) {
                        false
                    }
                val body =
                    GitLabApi.buildCommitBody(
                        b.branch,
                        "Update $rel (via Rock Edit)",
                        if (exists) "update" else "create",
                        rel,
                        encoded,
                    )
                val url = "$gitlabRoot/projects/${GitLabApi.projectId(b.owner, b.repo)}/repository/commits"
                val status = request(url, "POST", body)
                if (status !in 200..299) throw IllegalStateException("GitLab save failed: HTTP $status")
            }
            else -> throw IllegalArgumentException("Not a git connection: $type")
        }
    }

    override fun mkdir(path: String) {
        // Git has no empty directories: create a .gitkeep placeholder.
        val b = requireBase()
        requireRepoRel(b, path)
        write(RemotePath.child(RemotePath.normalize(path), ".gitkeep"), ByteArray(0))
    }

    override fun delete(path: String) {
        val b = requireBase()
        val rel = requireRepoRel(b, path)
        when (type) {
            RemoteType.GITHUB -> {
                val url = "$API_ROOT/repos/${b.owner}/${b.repo}/contents/$rel?ref=${urlEncode(b.branch)}"
                val sha =
                    GitHubApi.parseFileEntry(get(url))?.first
                        ?: throw IllegalStateException("Not found: $path")
                val status =
                    request(
                        "$API_ROOT/repos/${b.owner}/${b.repo}/contents/$rel",
                        "DELETE",
                        GitHubApi.buildDeleteBody(rel, b.branch, sha),
                    )
                if (status !in 200..299) throw IllegalStateException("GitHub delete failed: HTTP $status")
            }
            RemoteType.GITLAB -> {
                val body =
                    GitLabApi.buildCommitBody(
                        b.branch,
                        "Delete $rel (via Rock Edit)",
                        "delete",
                        rel,
                        null,
                    )
                val url = "$gitlabRoot/projects/${GitLabApi.projectId(b.owner, b.repo)}/repository/commits"
                val status = request(url, "POST", body)
                if (status !in 200..299) throw IllegalStateException("GitLab delete failed: HTTP $status")
            }
            else -> throw IllegalArgumentException("Not a git connection: $type")
        }
    }

    override fun close() {
        // Stateless.
    }

    // ------------------------------------------------------------------ http

    private val gitlabRoot: String
        get() = "https://${connection.host.ifEmpty { "gitlab.com" }}/api/v4"

    private fun requireBase(): GitPath.Base =
        base ?: throw IllegalStateException(
            "Initial path must be owner/repo/branch (was '${connection.initialPath}')",
        )

    private fun requireRepoRel(
        b: GitPath.Base,
        path: String,
    ): String =
        GitPath.repoRelative(b, RemotePath.normalize(path))
            ?: throw IllegalArgumentException("Path outside repository: $path")

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val conn = open(url, "GET")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also {
            conn.disconnect()
        }
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
    ): Int {
        val conn = open(url, method)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
        }
        val status = conn.responseCode
        conn.disconnect()
        return status
    }

    private fun open(
        url: String,
        method: String,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        when (type) {
            RemoteType.GITHUB -> {
                conn.setRequestProperty("Authorization", "Bearer ${connection.password}")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            RemoteType.GITLAB -> conn.setRequestProperty("PRIVATE-TOKEN", connection.password)
            else -> throw IllegalArgumentException("Not a git connection: $type")
        }
        conn.setRequestProperty("User-Agent", "RockEdit/0.8")
        return conn
    }

    companion object {
        private const val TIMEOUT_MS = 20_000
        private const val MAX_CONTENT_BASE64 = 1_400_000
        private const val API_ROOT = GitHubApi.API_ROOT
    }
}
