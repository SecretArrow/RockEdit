package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.SyntaxRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxRegistryTest {

    @Test
    fun mapsCommonExtensions() {
        assertEquals("kotlin", SyntaxRegistry.languageForFileName("Main.kt")?.id)
        assertEquals("kotlin", SyntaxRegistry.languageForFileName("script.kts")?.id)
        assertEquals("java", SyntaxRegistry.languageForFileName("App.java")?.id)
        assertEquals("python", SyntaxRegistry.languageForFileName("app.py")?.id)
        assertEquals("shell", SyntaxRegistry.languageForFileName("run.sh")?.id)
        assertEquals("json", SyntaxRegistry.languageForFileName("package.json")?.id)
        assertEquals("cpp", SyntaxRegistry.languageForFileName("engine.cpp")?.id)
        assertEquals("html", SyntaxRegistry.languageForFileName("index.html")?.id)
        assertEquals("css", SyntaxRegistry.languageForFileName("style.css")?.id)
    }

    @Test
    fun extensionIsCaseInsensitive() {
        assertEquals("kotlin", SyntaxRegistry.languageForFileName("MAIN.KT")?.id)
        assertEquals("python", SyntaxRegistry.languageForFileName("App.PY")?.id)
    }

    @Test
    fun unknownOrMissingExtensionReturnsNull() {
        assertNull(SyntaxRegistry.languageForFileName("README"))
        assertNull(SyntaxRegistry.languageForFileName(".gitignore"))
        assertNull(SyntaxRegistry.languageForFileName("notes.md"))
        assertNull(SyntaxRegistry.languageForFileName("photo.png"))
        assertNull(SyntaxRegistry.languageForFileName(null))
        assertNull(SyntaxRegistry.languageForFileName(""))
    }

    @Test
    fun allLanguagesAreWellFormed() {
        assertTrue("expected at least 20 languages", SyntaxRegistry.languageCount >= 20)
        // Known representative languages must exist with unique ids.
        val ids = HashSet<String>()
        for (id in listOf(
            "kotlin", "java", "c", "cpp", "csharp", "go", "rust", "javascript",
            "typescript", "python", "ruby", "php", "swift", "shell", "sql",
            "json", "yaml", "xml", "html", "css"
        )) {
            val language = SyntaxRegistry.languageById(id)
            assertTrue("missing language $id", language != null)
            assertTrue(language!!.displayName.isNotBlank())
            assertTrue("id $id duplicated", ids.add(language.id))
        }
    }

    @Test
    fun unknownIdReturnsNull() {
        assertNull(SyntaxRegistry.languageById("brainfuck"))
    }
}
