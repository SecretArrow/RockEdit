package com.secretarrow.rockedit.core

/**
 * Pure helpers for folder browsing (Storage Manager groundwork):
 * sorting, hidden-file filtering and breadcrumb rendering.
 */
object FolderSort {
    /** One entry in a folder listing. [size] is -1 when unknown (folders). */
    data class Entry(
        val name: String,
        val isFolder: Boolean,
        val size: Long = -1,
        val lastModified: Long = 0,
    )

    /**
     * Sorts entries: folders before files (when [foldersFirst]), then
     * case-insensitive alphabetical. Pure and deterministic.
     */
    fun sort(
        entries: List<Entry>,
        foldersFirst: Boolean = true,
    ): List<Entry> {
        val cmp =
            compareBy<Entry> { it.name.lowercase() }
                .thenBy { it.name }
        return if (foldersFirst) {
            entries.sortedWith(compareByDescending<Entry> { it.isFolder }.then(cmp))
        } else {
            entries.sortedWith(cmp)
        }
    }

    /** Removes dot-files unless [showHidden] is true. */
    fun filterHidden(
        entries: List<Entry>,
        showHidden: Boolean,
    ): List<Entry> = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }

    /**
     * Renders a friendly breadcrumb: the folder display name followed by the
     * segments descended into, e.g. `Home / project / src`.
     */
    fun breadcrumb(
        root: String,
        path: List<String>,
        separator: String = " / ",
    ): String {
        val parts =
            buildList {
                if (root.isNotBlank()) add(root)
                addAll(path.filter { it.isNotBlank() })
            }
        return if (parts.isEmpty()) root else parts.joinToString(separator)
    }

    /** Parent path of [path] (segments), or an empty list at the root. */
    fun parentPath(path: List<String>): List<String> = path.dropLast(1)

    /** Joins a folder name onto the current path segments. */
    fun descend(
        path: List<String>,
        folderName: String,
    ): List<String> = path + listOf(folderName)
}
