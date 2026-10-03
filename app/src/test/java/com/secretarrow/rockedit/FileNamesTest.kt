package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun splitBasic() {
        assertEquals("notes" to "txt", FileNames.split("notes.txt"))
        assertEquals("archive" to "tar", FileNames.split("archive.tar.gz"))
        assertEquals("Makefile" to "", FileNames.split("Makefile"))
    }

    @Test
    fun splitDotfiles() {
        // ".hidden" has no extension per common editor conventions.
        assertEquals(".hidden" to "", FileNames.split(".hidden"))
        assertEquals("" to "", FileNames.split(""))
    }

    @Test
    fun sanitizeRemovesPathSeparators() {
        assertEquals(".._etc_passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("a_b", FileNames.sanitize("a/b"))
        assertEquals("a_b", FileNames.sanitize("a\\b"))
    }

    @Test
    fun sanitizeReservedCharacters() {
        assertEquals("what_", FileNames.sanitize("what?"))
        assertEquals("col_on", FileNames.sanitize("col:on"))
    }

    @Test
    fun sanitizeWhitespaceAndEmpty() {
        assertEquals("my file", FileNames.sanitize("  my   file  "))
        assertEquals("untitled.txt", FileNames.sanitize(""))
        assertEquals("untitled.txt", FileNames.sanitize("   "))
    }

    @Test
    fun looksLikeText() {
        assertTrue(FileNames.looksLikeTextFile("main.kt"))
        assertTrue(FileNames.looksLikeTextFile("README.md"))
        assertTrue(FileNames.looksLikeTextFile("Makefile"))
        assertFalse(FileNames.looksLikeTextFile("image.png"))
        assertFalse(FileNames.looksLikeTextFile("movie.MP4"))
    }
}
