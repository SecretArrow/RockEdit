package com.secretarrow.rockedit.core

/**
 * Pure path helpers for Git hosting connections (GitHub/GitLab via PAT).
 *
 * A connection's [RemoteConnection.initialPath] encodes the repository base
 * as `owner/repo/branch` (branch may contain slashes: everything after the
 * second segment is the branch). The remote browser navigates from `/` down
 * into that base, then the repository tree itself.
 */
object GitPath {
    data class Base(
        val owner: String,
        val repo: String,
        val branch: String,
    ) {
        /** `/owner/repo/branch` — the mount point inside the browser. */
        val mountPath: String get() = "/$owner/$repo/$branch"
    }

    /** Parses `owner/repo/branch[/...]`; null when fewer than 3 segments. */
    fun parseBase(initialPath: String): Base? {
        val normalized = RemotePath.normalize(initialPath).trimStart('/')
        val parts = normalized.split('/')
        if (parts.size < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return null
        }
        return Base(parts[0], parts[1], parts.drop(2).joinToString("/"))
    }

    /**
     * Repo-relative path of [path] when it is at or below the base mount
     * ("" for the mount itself), null otherwise.
     */
    fun repoRelative(
        base: Base,
        path: String,
    ): String? {
        val p = RemotePath.normalize(path)
        val mount = base.mountPath
        if (p == mount) return ""
        return if (p.startsWith("$mount/")) p.removePrefix("$mount/") else null
    }

    /**
     * When [path] is a strict prefix of the mount, returns the next synthetic
     * segment the browser should show ("owner", then "repo", then "branch").
     */
    fun nextSegmentTowardBase(
        base: Base,
        path: String,
    ): String? {
        val p = RemotePath.normalize(path)
        val segments = base.mountPath.trimStart('/').split('/')
        val walked = p.trimStart('/').split('/').filter { it.isNotEmpty() }
        if (walked.size >= segments.size) return null
        if (walked != segments.take(walked.size)) return null
        return segments[walked.size]
    }
}
