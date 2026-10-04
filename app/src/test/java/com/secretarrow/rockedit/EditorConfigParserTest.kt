package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.EditorConfigParser
import com.secretarrow.rockedit.core.EditorConfigParser.IndentStyle
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.LineBreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.secretarrow.rockedit.core.IndentStyle as FormatterIndentStyle

/**
 * Per-branch tests for [EditorConfigParser] (v0.11.0): blank files, comments,
 * root header, malformed lines, quoted values, invalid values ignored,
 * globs (exact, star, question mark, escaped specials), last-wins merging,
 * and the mapping onto [FormatOptions].
 */
class EditorConfigParserTest {
    // ---------------------------------------------------------------- parse

    @Test
    fun blankAndEmptyConfigsAreEmpty() {
        assertTrue(EditorConfigParser.parse("").isEmpty())
        assertTrue(EditorConfigParser.parse("  \n \t ").isEmpty())
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val config = EditorConfigParser.parse("# comment\n; another\n\n[*.kt]\nindent_size = 2\n")
        assertEquals(1, config.sections.size)
        assertEquals(0, config.malformedLines)
    }

    @Test
    fun rootHeaderIsParsed() {
        assertTrue(EditorConfigParser.parse("root = true\n").isRoot)
        assertFalse(EditorConfigParser.parse("root = false\n").isRoot)
        assertFalse(EditorConfigParser.parse("root = yes\n").isRoot) // invalid -> not root
    }

    @Test
    fun bomIsStripped() {
        val config = EditorConfigParser.parse("\uFEFF[*]\nindent_size=2\n")
        assertTrue(config.sections.isNotEmpty())
    }

    @Test
    fun malformedLinesAreCountedNotFatal() {
        val config = EditorConfigParser.parse("garbage line\n[unclosed\n[*.txt]\nkey value\n")
        assertEquals(1, config.sections.size) // [*.txt] still parsed
        assertTrue(config.malformedLines >= 3) // garbage + unclosed + "key value"
    }

    @Test
    fun unknownKeysAreIgnored() {
        val config = EditorConfigParser.parse("[*]\ncharset = utf-8\nindent_size = 2\n")
        assertEquals(2, config.sections[0].indentSize)
    }

    @Test
    fun quotedValuesAreStripped() {
        val config = EditorConfigParser.parse("[*]\nindent_style = \"tab\"\nend_of_line = 'crlf'\n")
        assertEquals(IndentStyle.TAB, config.sections[0].indentStyle)
        assertEquals(LineBreak.CRLF, config.sections[0].endOfLine)
    }

    @Test
    fun invalidValuesAreIgnoredKeyByKey() {
        val config =
            EditorConfigParser.parse(
                "[*]\nindent_style = spacey\nindent_size = 0\nindent_size2 = 9\ntab_width = -1\nend_of_line = banana\ntrim_trailing_whitespace = maybe\n",
            )
        val section = config.sections[0]
        assertNull(section.indentStyle)
        assertNull(section.indentSize)
        assertNull(section.tabWidth)
        assertNull(section.endOfLine)
        assertNull(section.trimTrailing)
    }

    @Test
    fun boundaryIndentSizesAccepted() {
        val config = EditorConfigParser.parse("[*]\nindent_size = 8\ntab_width = 16\n")
        assertEquals(8, config.sections[0].indentSize)
        assertEquals(16, config.sections[0].tabWidth)
    }

    @Test
    fun endOfLineVariants() {
        val config = EditorConfigParser.parse("[*]\nend_of_line = cr\n")
        assertEquals(LineBreak.CR, config.sections[0].endOfLine)
    }

    // ---------------------------------------------------------------- globs

    @Test
    fun exactNameMatchesCaseInsensitive() {
        assertTrue(EditorConfigParser.matches("README.md", "readme.md"))
        assertFalse(EditorConfigParser.matches("README.md", "readme.md.bak"))
    }

    @Test
    fun starMatchesEverything() {
        assertTrue(EditorConfigParser.matches("*", "anything.txt"))
        assertTrue(EditorConfigParser.matches("*", ""))
    }

    @Test
    fun starExtensionMatchesSuffixOnly() {
        assertTrue(EditorConfigParser.matches("*.kt", "Main.kt"))
        assertFalse(EditorConfigParser.matches("*.kt", "Main.kts"))
        assertFalse(EditorConfigParser.matches("*.kt", "kt"))
    }

    @Test
    fun questionMarkMatchesSingleChar() {
        assertTrue(EditorConfigParser.matches("fo?.txt", "foo.txt"))
        assertFalse(EditorConfigParser.matches("fo?.txt", "fooo.txt"))
    }

    @Test
    fun regexSpecialsInPatternAreEscaped() {
        assertTrue(EditorConfigParser.matches("file[1].txt", "file[1].txt"))
        assertFalse(EditorConfigParser.matches("file[1].txt", "fileA1].txt"))
    }

    // --------------------------------------------------------------- resolve

    @Test
    fun lastMatchingSectionWins() {
        val config =
            EditorConfigParser.parse(
                "[*]\nindent_size = 2\n[*.md]\nindent_size = 4\n",
            )
        assertEquals(4, EditorConfigParser.resolve(config, "notes.md").indentSize)
        assertEquals(2, EditorConfigParser.resolve(config, "Main.kt").indentSize)
    }

    @Test
    fun resolveWithoutMatchYieldsAllNulls() {
        val resolved =
            EditorConfigParser.resolve(
                EditorConfigParser.parse("[*.md]\nindent_size = 4\n"),
                "Main.kt",
            )
        assertNull(resolved.indentSize)
        assertNull(resolved.indentStyle)
        assertNull(resolved.endOfLine)
    }

    // ------------------------------------------------------- toFormatOptions

    @Test
    fun toFormatOptionsMapsAllFields() {
        val resolved =
            EditorConfigParser.Resolved(
                indentStyle = IndentStyle.TAB,
                indentSize = 2,
                endOfLine = LineBreak.CRLF,
                trimTrailing = false,
                insertFinalNewline = false,
            )
        val options = EditorConfigParser.toFormatOptions(resolved, FormatOptions())
        assertEquals(FormatterIndentStyle.TABS, options.indentStyle)
        assertEquals(2, options.indentSize)
        assertEquals(LineBreak.CRLF, options.lineBreak)
        assertFalse(options.trimTrailingWhitespace)
        assertFalse(options.insertFinalNewline)
    }

    @Test
    fun toFormatOptionsKeepsBaseWhenUnspecified() {
        val base = FormatOptions(indentSize = 3, lenient = true)
        val options = EditorConfigParser.toFormatOptions(EditorConfigParser.Resolved(), base)
        assertEquals(3, options.indentSize)
        assertEquals(FormatterIndentStyle.SPACES, options.indentStyle)
        assertTrue(options.lenient) // base-only fields survive the copy
    }

    @Test
    fun toFormatOptionsClampsOutOfRangeSize() {
        // The parser rejects sizes > 8, but resolve() from a hand-built
        // Resolved must still be safe.
        val resolved = EditorConfigParser.Resolved(indentSize = 99)
        val options = EditorConfigParser.toFormatOptions(resolved, FormatOptions())
        assertEquals(FormatOptions.MAX_INDENT_SIZE, options.indentSize)
    }

    @Test
    fun parseNeverThrowsOnHostileInput() {
        // Long nonsense input: must complete, not throw.
        val hostile =
            buildString {
                repeat(200) { append("[[[\nnoequalsign\n=\n[*.x]\n@@@\n") }
            }
        val config = EditorConfigParser.parse(hostile)
        assertTrue(config.sections.isNotEmpty() || config.malformedLines > 0)
    }
}
