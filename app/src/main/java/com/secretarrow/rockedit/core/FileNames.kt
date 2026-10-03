package com.secretarrow.rockedit.core

/**
 * Pure helpers for file names and text-file decisions.
 */
object FileNames {

    /**
     * Splits a file name into base name and extension (without the dot).
     * "notes.txt" -> ("notes", "txt"); "Makefile" -> ("Makefile", "");
     * ".hidden" -> (".hidden", "").
     */
    fun split(name: String): Pair<String, String> {
        val idx = name.lastIndexOf('.')
        return if (idx <= 0) name to "" else name.substring(0, idx) to name.substring(idx + 1)
    }

    /**
     * Sanitizes a user-typed file name so it can be safely created on disk:
     * trims whitespace, removes path separators and other reserved characters,
     * collapses to "_" and guarantees a non-empty result.
     */
    fun sanitize(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_")
            .replace(Regex("\\s+"), " ")
        return if (cleaned.isEmpty()) "untitled.txt" else cleaned
    }

    /**
     * True when the extension is commonly plain text. Used only for UX hints,
     * Rock Edit can open any file as text.
     */
    fun looksLikeTextFile(name: String): Boolean {
        val ext = split(name).second.lowercase()
        return ext.isEmpty() || ext in TEXT_EXTENSIONS
    }

    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "log", "ini", "cfg", "conf", "json", "xml", "yaml", "yml",
        "html", "htm", "css", "scss", "js", "ts", "jsx", "tsx", "java", "kt", "kts", "gradle",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "sh", "bash", "bat",
        "sql", "properties", "toml", "csv", "tsv", "gitignore", "pro", "smali", "lisp", "lua"
    )
}
