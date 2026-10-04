package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.SyntaxRegistry
import com.secretarrow.rockedit.core.SyntaxTokenType
import com.secretarrow.rockedit.core.SyntaxTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxTokenizerTest {

    private fun tokens(text: String, langId: String) =
        SyntaxTokenizer.tokenize(text, SyntaxRegistry.languageById(langId)!!)

    private fun typesOf(text: String, langId: String) =
        tokens(text, langId).map { it.type }

    @Test
    fun kotlinKeywordAndNumber() {
        val result = tokens("val x = 1", "kotlin")
        assertEquals(listOf(SyntaxTokenType.KEYWORD, SyntaxTokenType.NUMBER), result.map { it.type })
        assertEquals(0, result[0].start)
        assertEquals(3, result[0].end)
        assertEquals(8, result[1].start)
        assertEquals(9, result[1].end)
    }

    @Test
    fun stringToken() {
        val result = tokens("s = \"hi\"", "kotlin")
        assertEquals(listOf(SyntaxTokenType.STRING), result.map { it.type })
        assertEquals(4, result[0].start)
        assertEquals(8, result[0].end)
    }

    @Test
    fun escapedQuoteStaysInsideString() {
        // Source text: a = "he\"o"
        val text = "a = \"he\\\"o\""
        val result = tokens(text, "kotlin")
        assertEquals(listOf(SyntaxTokenType.STRING), result.map { it.type })
        assertEquals(4, result[0].start)
        assertEquals(text.length, result[0].end)
    }

    @Test
    fun lineCommentCpp() {
        val result = tokens("// hi\nint x", "cpp")
        assertEquals(listOf(SyntaxTokenType.COMMENT, SyntaxTokenType.KEYWORD), result.map { it.type })
        assertEquals(0, result[0].start)
        assertEquals(5, result[0].end)
        assertEquals(6, result[1].start)
        assertEquals(9, result[1].end)
    }

    @Test
    fun hashCommentForShell() {
        val result = tokens("# comment\nx=1", "shell")
        assertEquals(listOf(SyntaxTokenType.COMMENT, SyntaxTokenType.NUMBER), result.map { it.type })
        assertEquals(0, result[0].start)
        assertEquals(9, result[0].end)
        assertEquals(12, result[1].start)
        assertEquals(13, result[1].end)
    }

    @Test
    fun dashDashCommentForSql() {
        val result = tokens("-- note\nselect", "sql")
        assertEquals(listOf(SyntaxTokenType.COMMENT, SyntaxTokenType.KEYWORD), result.map { it.type })
    }

    @Test
    fun blockCommentSpansLines() {
        // /* a\nb */x  -> comment covers [0, 9), no token for x
        val result = tokens("/* a\nb */x", "c")
        assertEquals(listOf(SyntaxTokenType.COMMENT), result.map { it.type })
        assertEquals(0, result[0].start)
        assertEquals(9, result[0].end)
    }

    @Test
    fun unterminatedBlockCommentRunsToEof() {
        val result = tokens("/* abc", "c")
        assertEquals(listOf(SyntaxTokenType.COMMENT), result.map { it.type })
        assertEquals(6, result[0].end)
    }

    @Test
    fun unterminatedStringRunsToEof() {
        val result = tokens("x = \"abc", "kotlin")
        assertEquals(listOf(SyntaxTokenType.STRING), result.map { it.type })
        assertEquals(4, result[0].start)
        assertEquals(8, result[0].end)
    }

    @Test
    fun sqlKeywordsAreCaseInsensitive() {
        val result = tokens("select a from t", "sql")
        assertEquals(2, result.size)
        assertEquals(SyntaxTokenType.KEYWORD, result[0].type)
        assertEquals(0, result[0].start)
        assertEquals(SyntaxTokenType.KEYWORD, result[1].type)
        assertEquals(9, result[1].start)
    }

    @Test
    fun kotlinKeywordsAreCaseSensitive() {
        val result = typesOf("Val val", "kotlin")
        assertEquals(listOf(SyntaxTokenType.KEYWORD), result)
        assertEquals(4, tokens("Val val", "kotlin")[0].start)
    }

    @Test
    fun numberFormats() {
        val result = tokens("0xFF 3.14 1_000", "kotlin")
        assertEquals(3, result.size)
        assertTrue(result.all { it.type == SyntaxTokenType.NUMBER })
        assertEquals(0, result[0].start)
        assertEquals(4, result[0].end)
        assertEquals(5, result[1].start)
        assertEquals(9, result[1].end)
        assertEquals(10, result[2].start)
        assertEquals(15, result[2].end)
    }

    @Test
    fun identifierWithDigitsIsNotNumber() {
        val result = tokens("abc123 = 1", "kotlin")
        assertEquals(listOf(SyntaxTokenType.NUMBER), result.map { it.type })
        assertEquals(9, result[0].start)
    }

    @Test
    fun plainTextHasNoTokens() {
        assertTrue(tokens("hello world", "kotlin").isEmpty())
    }

    @Test
    fun jsonBooleansAndStrings() {
        val result = typesOf("{\"a\": true}", "json")
        assertEquals(listOf(SyntaxTokenType.STRING, SyntaxTokenType.KEYWORD), result)
    }

    @Test
    fun htmlAttributesAndTags() {
        val result = typesOf("<div class=\"x\">", "html")
        assertTrue(SyntaxTokenType.KEYWORD in result)
        assertTrue(SyntaxTokenType.STRING in result)
    }

    @Test
    fun emptyTextProducesNoTokens() {
        assertTrue(SyntaxTokenizer.tokenize("", SyntaxRegistry.languageById("json")!!).isEmpty())
    }

    @Test
    fun pythonDocstringColoredAsComment() {
        val result = typesOf("x = 1\n\"\"\"doc\"\"\"", "python")
        assertEquals(SyntaxTokenType.COMMENT, result.last())
    }
}
