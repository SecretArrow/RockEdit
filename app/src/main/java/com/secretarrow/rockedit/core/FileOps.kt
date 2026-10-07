package com.secretarrow.rockedit.core

/**
 * Pure-JVM validation and decision layer for file-management operations in
 * the SAF folder browser (v0.21.0): create file, create folder, rename,
 * delete. No Android imports on purpose — every rule is unit-testable on the
 * JVM and the Android side ([com.secretarrow.rockedit.ui.FolderBrowserActivity])
 * only performs the actual DocumentFile calls after this layer approves.
 *
 * Design decision (assumption documented): unlike [FileNames.sanitize] —
 * which silently rewrites illegal characters for internal "untitled" files —
 * user-typed names in management dialogs are validated STRICTLY and rejected
 * with an explicit reason. The user stays in control of the exact name on
 * disk; silent rewriting caused names to differ from what was typed, which
 * is worse for a file manager.
 *
 * Rejected character set = [/ \ : * ? " < > |] plus control characters
 * (U+0000..U+001F, U+007F). This matches the rewrite set already used by
 * [FileNames.sanitize] and is the common denominator of ext4 (primary
 * storage) and FAT (USB OTG, where * ? " < > | : are invalid), so a name
 * accepted here works on every backend Rock Edit can reach.
 */
object FileOps {
    /** Upper bound for a single path segment: 255 UTF-8 bytes on ext4 and FAT. */
    const val MAX_NAME_BYTES: Int = 255

    /** Why a candidate name was rejected. UI maps each value to a localized message. */
    enum class NameError {
        /** Null, empty, or whitespace-only input. */
        EMPTY,

        /** Contains a character from the rejected set documented on [FileOps]. */
        INVALID_CHARS,

        /** Exactly "." or ".." (reserved self/parent references). */
        DOT_NAME,

        /** Longer than [MAX_NAME_BYTES] UTF-8 bytes. */
        TOO_LONG,
    }

    /** Outcome of [validateName]: the sanitized name or the rejection reason. */
    sealed class NameResult {
        /** Name is acceptable; [name] is trimmed and otherwise untouched. */
        data class Valid(
            val name: String,
        ) : NameResult()

        /** Name was rejected; [error] says why (never null). */
        data class Invalid(
            val error: NameError,
        ) : NameResult()
    }

    /**
     * Validates a user-typed file/folder name end to end. Total function:
     * never throws for any input of the declared types (null included).
     *
     * Order of checks is deliberate — cheapest and most specific first:
     * 1. null / blank (after trim) -> [NameError.EMPTY];
     * 2. "." or ".." -> [NameError.DOT_NAME];
     * 3. rejected characters or control characters -> [NameError.INVALID_CHARS];
     * 4. UTF-8 byte length > [MAX_NAME_BYTES] -> [NameError.TOO_LONG]
     *    (byte length also covers the char count, since bytes >= chars).
     *
     * Leading/trailing whitespace is trimmed (typing artifacts), interior
     * whitespace is preserved (legal on every supported backend).
     */
    fun validateName(raw: String?): NameResult {
        if (raw == null) return NameResult.Invalid(NameError.EMPTY)
        val name = raw.trim()
        if (name.isEmpty()) return NameResult.Invalid(NameError.EMPTY)
        if (name == "." || name == "..") return NameResult.Invalid(NameError.DOT_NAME)
        if (hasRejectedChar(name)) return NameResult.Invalid(NameError.INVALID_CHARS)
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            return NameResult.Invalid(NameError.TOO_LONG)
        }
        return NameResult.Valid(name)
    }

    private fun hasRejectedChar(name: String): Boolean = name.any { it in REJECTED_CHARS || it.code < 0x20 || it.code == 0x7F }

    /**
     * True when [name] already exists among [siblings]. Comparison is exact
     * (case-sensitive): primary storage (ext4) is case-sensitive, so "A.txt"
     * and "a.txt" may coexist. On case-insensitive FAT backends (USB OTG) the
     * provider itself will fail the create/rename call and the UI shows the
     * generic failure message — a deliberate trade-off documented in
     * docs/rockedit.md §4.20.
     */
    fun checkCollision(
        name: String,
        siblings: Collection<String>,
    ): Boolean = siblings.any { it == name }

    /**
     * True when a rename is a no-op (new name equals the current one after
     * trim). The UI closes the dialog without calling SAF and without an
     * error, because nothing would change.
     */
    fun renameIsNoOp(
        currentName: String,
        newName: String?,
    ): Boolean = newName != null && newName.trim() == currentName

    /**
     * Best-effort MIME type for creating a new empty file via SAF
     * (DocumentFile.createFile requires a non-empty mime). Pure JVM mirror of
     * MimeTypeMap for the extensions Rock Edit users actually create; unknown
     * or missing extensions fall back to "application/octet-stream", which
     * every DocumentsProvider accepts.
     */
    fun deriveMime(fileName: String): String {
        val ext = FileNames.split(fileName).second.lowercase()
        return when (ext) {
            "txt", "md", "log", "ini", "cfg", "conf", "csv", "tsv", "properties", "toml" -> "text/plain"
            "html", "htm" -> "text/html"
            "css", "scss" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private val REJECTED_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
