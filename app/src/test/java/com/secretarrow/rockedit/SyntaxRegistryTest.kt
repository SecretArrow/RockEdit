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
        assertTrue("expected at least 45 languages", SyntaxRegistry.languageCount >= 45)
        // Known representative languages must exist with unique ids.
        val ids = HashSet<String>()
        for (id in listOf(
            "kotlin", "java", "c", "cpp", "csharp", "go", "rust", "javascript",
            "typescript", "python", "ruby", "php", "swift", "shell", "sql",
            "json", "yaml", "xml", "html", "css",
            "lua", "perl", "r", "objc", "dart", "scala", "groovy", "haskell",
            "erlang", "elixir", "clojure", "fsharp", "vb", "assembly", "toml",
            "ini", "makefile", "cmake", "batch", "powershell", "vue", "graphql",
            "julia", "nim", "ocaml", "latex", "zig", "protobuf"
        )) {
            val language = SyntaxRegistry.languageById(id)
            assertTrue("missing language $id", language != null)
            assertTrue(language!!.displayName.isNotBlank())
            assertTrue(language.keywords.isNotEmpty())
            assertTrue("id $id duplicated", ids.add(language.id))
        }
        assertEquals(SyntaxRegistry.languageCount, ids.size)
    }

    @Test
    fun extendedExtensionsResolve() {
        assertEquals("lua", SyntaxRegistry.languageForFileName("init.lua")?.id)
        assertEquals("perl", SyntaxRegistry.languageForFileName("script.pl")?.id)
        assertEquals("objc", SyntaxRegistry.languageForFileName("View.m")?.id)
        assertEquals("powershell", SyntaxRegistry.languageForFileName("deploy.ps1")?.id)
        assertEquals("latex", SyntaxRegistry.languageForFileName("thesis.tex")?.id)
        assertEquals("zig", SyntaxRegistry.languageForFileName("main.zig")?.id)
        assertEquals("protobuf", SyntaxRegistry.languageForFileName("api.proto")?.id)
    }

    @Test
    fun extensionlessWellKnownNamesResolve() {
        assertEquals("makefile", SyntaxRegistry.languageForFileName("Makefile")?.id)
        assertEquals("makefile", SyntaxRegistry.languageForFileName("makefile")?.id)
        assertEquals("shell", SyntaxRegistry.languageForFileName("Dockerfile")?.id)
        assertEquals("ruby", SyntaxRegistry.languageForFileName("Gemfile")?.id)
        assertEquals("cmake", SyntaxRegistry.languageForFileName("CMakeLists.txt")?.id)
    }

    @Test
    fun tokenizerTreatsLuaBlockComments() {
        val language = SyntaxRegistry.languageById("lua")!!
        val tokens = com.secretarrow.rockedit.core.SyntaxTokenizer.tokenize(
            "--[[ hidden ]]\ncode = 1",
            language
        )
        assertTrue(tokens.any { it.type == com.secretarrow.rockedit.core.SyntaxTokenType.COMMENT })
    }

    @Test
    fun unknownIdReturnsNull() {
        assertNull(SyntaxRegistry.languageById("brainfuck"))
    }
}
