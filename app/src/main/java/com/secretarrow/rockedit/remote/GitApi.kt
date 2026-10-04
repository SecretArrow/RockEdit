package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Pure helpers for the GitHub and GitLab REST APIs: response parsing, request
 * body building and content encoding. JVM-safe (java.util.Base64, API 26+).
 */
object GitHubApi {
    const val API_ROOT = "https://api.github.com"

    /** Parses a /contents listing into files. [dir] is the browsed folder. */
    fun parseContents(
        json: String,
        dir: String,
    ): List<RemoteFile> =
        try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isEmpty()) return@mapNotNull null
                val isFolder = o.optString("type") == "dir"
                RemoteFile(
                    name = name,
                    path = RemotePath.child(dir, name),
                    isFolder = isFolder,
                    size = o.optLong("size", -1),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    /** Extracts (sha, base64 content) of a single-file /contents response. */
    fun parseFileEntry(json: String): Pair<String, String>? =
        try {
            val obj = JSONObject(json)
            val content = obj.optString("content").replace("\n", "")
            val sha = obj.optString("sha")
            if (sha.isEmpty()) null else sha to content
        } catch (_: Exception) {
            null
        }

    /** Decodes the base64 payload of a /contents entry. */
    fun decodeContent(base64: String): ByteArray = Base64.getMimeDecoder().decode(base64)

    fun encodeContent(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    /** Builds the PUT /contents body. [existingSha] set when updating. */
    fun buildPutBody(
        path: String,
        branch: String,
        content: String,
        existingSha: String?,
    ): String {
        val obj =
            JSONObject()
                .put("message", "Update $path (via Rock Edit)")
                .put("content", content)
                .put("branch", branch)
        if (!existingSha.isNullOrEmpty()) obj.put("sha", existingSha)
        return obj.toString()
    }

    /** Builds the DELETE /contents body. */
    fun buildDeleteBody(
        path: String,
        branch: String,
        sha: String,
    ): String =
        JSONObject()
            .put("message", "Delete $path (via Rock Edit)")
            .put("sha", sha)
            .put("branch", branch)
            .toString()
}

object GitLabApi {
    /** Project ID path segment: `owner/repo` URL-encoded (slash -> %2F). */
    fun projectId(
        owner: String,
        repo: String,
    ): String = java.net.URLEncoder.encode("$owner/$repo", "UTF-8")

    fun filePath(path: String): String = java.net.URLEncoder.encode(path, "UTF-8")

    /** Parses a /repository/tree listing. [dir] is the browsed folder. */
    fun parseTree(
        json: String,
        dir: String,
    ): List<RemoteFile> =
        try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isEmpty()) return@mapNotNull null
                val isFolder = o.optString("type") == "tree"
                RemoteFile(
                    name = name,
                    path = RemotePath.child(dir, name),
                    isFolder = isFolder,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    /** Builds the commit request used for create/update/delete. */
    fun buildCommitBody(
        branch: String,
        message: String,
        action: String,
        filePath: String,
        base64Content: String?,
    ): String {
        val actionObj =
            JSONObject()
                .put("action", action)
                .put("file_path", filePath)
        if (base64Content != null) {
            actionObj.put("encoding", "base64")
            actionObj.put("content", base64Content)
        }
        return JSONObject()
            .put("branch", branch)
            .put("commit_message", message)
            .put("actions", JSONArray().put(actionObj))
            .toString()
    }
}
