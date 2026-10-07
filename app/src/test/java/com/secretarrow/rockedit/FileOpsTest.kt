package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FileOps
import com.secretarrow.rockedit.core.FileOps.NameError
import com.secretarrow.rockedit.core.FileOps.NameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full branch coverage for [FileOps] (v0.21.0 folder-browser file management).
 * Each test maps to one row of the scenario table in docs/rockedit.md §4.20.
 */
class FileOpsTest {
    // ---- validateName: EMPTY ---------------------------------------------

    @Test
    fun nullNameIsEmpty() {
        assertEquals(NameResult.Invalid(NameError.EMPTY), FileOps.validateName(null))
    }

    @Test
    fun blankNameIsEmpty() {
        assertEquals(NameResult.Invalid(NameError.EMPTY), FileOps.validateName(""))
    }

    @Test
    fun whitespaceOnlyNameIsEmpty() {
        assertEquals(NameResult.Invalid(NameError.EMPTY), FileOps.validateName("   \t "))
    }

    // ---- validateName: DOT_NAME ------------------------------------------

    @Test
    fun singleDotIsReserved() {
        assertEquals(NameResult.Invalid(NameError.DOT_NAME), FileOps.validateName("."))
    }

    @Test
    fun doubleDotIsReserved() {
        assertEquals(NameResult.Invalid(NameError.DOT_NAME), FileOps.validateName(".."))
    }

    @Test
    fun dotPrefixedHiddenNameIsAllowed() {
        assertEquals(NameResult.Valid(".hidden"), FileOps.validateName(".hidden"))
    }

    @Test
    fun dotDotPrefixIsAllowed() {
        assertEquals(NameResult.Valid("..gitignore"), FileOps.validateName("..gitignore"))
    }

    // ---- validateName: INVALID_CHARS --------------------------------------

    @Test
    fun slashIsRejected() {
        assertEquals(NameResult.Invalid(NameError.INVALID_CHARS), FileOps.validateName("a/b"))
    }

    @Test
    fun backslashIsRejected() {
        assertEquals(NameResult.Invalid(NameError.INVALID_CHARS), FileOps.validateName("a\\b"))
    }

    @Test
    fun colonIsRejected() {
        assertEquals(NameResult.Invalid(NameError.INVALID_CHARS), FileOps.validateName("a:b"))
    }

    @Test
    fun fatOnlyReservedCharsAreRejected() {
        for (bad in listOf("a*b", "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertEquals(
                "expected INVALID_CHARS for '$bad'",
                NameResult.Invalid(NameError.INVALID_CHARS),
                FileOps.validateName(bad),
            )
        }
    }

    @Test
    fun controlCharactersAreRejected() {
        assertEquals(NameResult.Invalid(NameError.INVALID_CHARS), FileOps.validateName("a\u0001b"))
        assertEquals(NameResult.Invalid(NameError.INVALID_CHARS), FileOps.validateName("a\u007Fb"))
    }

    @Test
    fun trailingDotIsAllowed() {
        assertEquals(NameResult.Valid("name."), FileOps.validateName("name."))
    }

    // ---- validateName: TOO_LONG -------------------------------------------

    @Test
    fun exactlyMaxBytesIsValid() {
        val name = "a".repeat(FileOps.MAX_NAME_BYTES)
        assertEquals(NameResult.Valid(name), FileOps.validateName(name))
    }

    @Test
    fun oneByteOverLimitIsTooLong() {
        val name = "a".repeat(FileOps.MAX_NAME_BYTES + 1)
        assertEquals(NameResult.Invalid(NameError.TOO_LONG), FileOps.validateName(name))
    }

    @Test
    fun multibyteCharsCountAsBytesNotChars() {
        // 100 CJK chars = 300 UTF-8 bytes: over the limit although only 100 chars.
        val name = "文".repeat(100)
        assertEquals(NameResult.Invalid(NameError.TOO_LONG), FileOps.validateName(name))
    }

    // ---- validateName: VALID paths -----------------------------------------

    @Test
    fun simpleNameIsValid() {
        assertEquals(NameResult.Valid("notes.txt"), FileOps.validateName("notes.txt"))
    }

    @Test
    fun leadingAndTrailingWhitespaceIsTrimmed() {
        assertEquals(NameResult.Valid("name.txt"), FileOps.validateName("  name.txt  "))
    }

    @Test
    fun interiorWhitespaceIsPreserved() {
        assertEquals(NameResult.Valid("my notes.txt"), FileOps.validateName("my notes.txt"))
    }

    @Test
    fun unicodeNameIsValid() {
        assertEquals(NameResult.Valid("文件.txt"), FileOps.validateName("文件.txt"))
    }

    @Test
    fun versionedNameIsValid() {
        assertEquals(NameResult.Valid("v1.2.3"), FileOps.validateName("v1.2.3"))
    }

    // ---- checkCollision -----------------------------------------------------

    @Test
    fun exactMatchIsCollision() {
        assertTrue(FileOps.checkCollision("a.txt", listOf("b.txt", "a.txt")))
    }

    @Test
    fun caseVariantIsNotACollision() {
        // Documented decision: primary storage is case-sensitive; on FAT the
        // provider itself rejects and the UI shows the generic failure.
        assertFalse(FileOps.checkCollision("A.txt", listOf("a.txt")))
    }

    @Test
    fun emptySiblingsNeverCollide() {
        assertFalse(FileOps.checkCollision("a.txt", emptyList()))
    }

    @Test
    fun collisionComparesNamesOnly() {
        // Folders and files share one namespace here: exact same name collides
        // regardless of what the sibling is on disk.
        assertTrue(FileOps.checkCollision("docs", listOf("docs")))
    }

    // ---- renameIsNoOp --------------------------------------------------------

    @Test
    fun identicalRenameIsNoOp() {
        assertTrue(FileOps.renameIsNoOp("a.txt", "a.txt"))
    }

    @Test
    fun trimmedIdenticalRenameIsNoOp() {
        assertTrue(FileOps.renameIsNoOp("a.txt", " a.txt "))
    }

    @Test
    fun caseOnlyRenameIsNotNoOp() {
        assertFalse(FileOps.renameIsNoOp("a.txt", "A.txt"))
    }

    @Test
    fun nullNewNameIsNotNoOp() {
        assertFalse(FileOps.renameIsNoOp("a.txt", null))
    }

    @Test
    fun differentNameIsNotNoOp() {
        assertFalse(FileOps.renameIsNoOp("a.txt", "b.txt"))
    }

    // ---- deriveMime -----------------------------------------------------------

    @Test
    fun plainTextFamilyMapsToTextPlain() {
        for (ext in listOf("txt", "md", "log", "csv", "toml")) {
            assertEquals("text/plain", FileOps.deriveMime("file.$ext"))
        }
    }

    @Test
    fun commonTypesMapExactly() {
        assertEquals("text/html", FileOps.deriveMime("index.html"))
        assertEquals("text/css", FileOps.deriveMime("style.css"))
        assertEquals("text/javascript", FileOps.deriveMime("app.js"))
        assertEquals("application/json", FileOps.deriveMime("data.json"))
        assertEquals("application/xml", FileOps.deriveMime("pom.xml"))
        assertEquals("image/png", FileOps.deriveMime("img.png"))
        assertEquals("image/jpeg", FileOps.deriveMime("photo.jpg"))
        assertEquals("application/pdf", FileOps.deriveMime("doc.pdf"))
        assertEquals("application/zip", FileOps.deriveMime("bundle.zip"))
    }

    @Test
    fun extensionCaseIsIgnored() {
        assertEquals("text/plain", FileOps.deriveMime("README.TXT"))
    }

    @Test
    fun missingExtensionFallsBack() {
        assertEquals("application/octet-stream", FileOps.deriveMime("Makefile"))
    }

    @Test
    fun unknownExtensionFallsBack() {
        assertEquals("application/octet-stream", FileOps.deriveMime("blob.xyzabc"))
    }

    @Test
    fun dotFileFallsBack() {
        // FileNames.split(".gitignore") -> (".gitignore", ""): no extension.
        assertEquals("application/octet-stream", FileOps.deriveMime(".gitignore"))
    }
}
