package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FolderSort
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSortTest {
    private fun e(
        name: String,
        isFolder: Boolean = false,
    ) = FolderSort.Entry(name, isFolder)

    @Test
    fun foldersComeFirstThenAlphabeticalCaseInsensitive() {
        val sorted =
            FolderSort.sort(
                listOf(e("zebra.txt"), e("Banana"), e("apple.txt"), e("Antelope", isFolder = true)),
                foldersFirst = true,
            )
        assertEquals(listOf("Antelope", "apple.txt", "Banana", "zebra.txt"), sorted.map { it.name })
    }

    @Test
    fun withoutFoldersFirstPureAlphabetical() {
        val sorted =
            FolderSort.sort(
                listOf(e("zebra.txt"), e("folder", true), e("apple.txt")),
                foldersFirst = false,
            )
        assertEquals(listOf("apple.txt", "folder", "zebra.txt"), sorted.map { it.name })
    }

    @Test
    fun tieBreakByCaseSensitiveNameForStableOrder() {
        val sorted = FolderSort.sort(listOf(e("B"), e("a")), foldersFirst = false)
        assertEquals(listOf("a", "B"), sorted.map { it.name })
    }

    @Test
    fun filterHiddenRemovesDotFilesByDefault() {
        val visible =
            FolderSort.filterHidden(
                listOf(e(".git", true), e("src", true), e(".gitignore"), e("main.kt")),
                showHidden = false,
            )
        assertEquals(listOf("src", "main.kt"), visible.map { it.name })
    }

    @Test
    fun filterHiddenKeepsEverythingWhenEnabled() {
        val all = listOf(e(".git", true), e("main.kt"))
        assertEquals(2, FolderSort.filterHidden(all, showHidden = true).size)
    }

    @Test
    fun breadcrumbJoinsRootAndSegments() {
        assertEquals(
            "Home / project / src",
            FolderSort.breadcrumb("Home", listOf("project", "src")),
        )
    }

    @Test
    fun breadcrumbAtRootIsJustRoot() {
        assertEquals("Home", FolderSort.breadcrumb("Home", emptyList()))
    }

    @Test
    fun parentPathDropsLastSegment() {
        assertEquals(listOf("a", "b"), FolderSort.parentPath(listOf("a", "b", "c")))
    }

    @Test
    fun parentPathAtRootIsEmpty() {
        assertEquals(emptyList<String>(), FolderSort.parentPath(listOf("a")))
        assertEquals(emptyList<String>(), FolderSort.parentPath(emptyList()))
    }

    @Test
    fun descendAppendsFolder() {
        assertEquals(listOf("a", "src"), FolderSort.descend(listOf("a"), "src"))
    }
}
