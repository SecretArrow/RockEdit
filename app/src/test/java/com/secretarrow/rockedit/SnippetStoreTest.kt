package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.InMemoryKeyValueStore
import com.secretarrow.rockedit.core.SnippetStore
import com.secretarrow.rockedit.core.SnippetStore.ErrorCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [SnippetStore] (v0.12.0): create/update validation,
 * duplicate names, language normalization, usage ordering, corrupt-storage
 * recovery and every tabstop-expansion branch.
 */
class SnippetStoreTest {

    private fun store(): SnippetStore = SnippetStore(InMemoryKeyValueStore())

    private fun ok(result: SnippetStore.MutateResult): List<SnippetStore.Snippet> {
        assertTrue("expected Success, was $result", result is SnippetStore.MutateResult.Success)
        return (result as SnippetStore.MutateResult.Success).snippets
    }

    private fun fail(result: SnippetStore.MutateResult): SnippetStore.MutateResult.Failure {
        assertTrue("expected Failure, was $result", result is SnippetStore.MutateResult.Failure)
        return result as SnippetStore.MutateResult.Failure
    }

    private fun expandOk(body: String): SnippetStore.Insert.InsertResult.Success {
        val result = SnippetStore.Insert.expand(body)
        assertTrue("expected expand Success, was $result", result is SnippetStore.Insert.InsertResult.Success)
        return result as SnippetStore.Insert.InsertResult.Success
    }

    // --------------------------------------------------------------- create

    @Test
    fun blankNameIsRejected() {
        assertEquals(
            ErrorCode.BLANK_NAME,
            fail(store().create("  ", "kotlin", "body")).code
        )
    }

    @Test
    fun overLongNameIsRejected() {
        val result = store().create("n".repeat(SnippetStore.MAX_NAME_CHARS + 1), "kotlin", "body")
        assertEquals(ErrorCode.NAME_TOO_LONG, fail(result).code)
    }

    @Test
    fun blankBodyIsRejected() {
        assertEquals(
            ErrorCode.BODY_BLANK,
            fail(store().create("snip", "kotlin", "   ")).code
        )
    }

    @Test
    fun overLongBodyIsRejected() {
        val body = "x".repeat(SnippetStore.MAX_BODY_CHARS + 1)
        assertEquals(ErrorCode.BODY_TOO_LARGE, fail(store().create("snip", "kotlin", body)).code)
    }

    @Test
    fun duplicateNameIsCaseInsensitive() {
        val s = store()
        assertTrue(ok(s.create("Greeting", "kotlin", "hello")) .isNotEmpty())
        val result = s.create("  greeting ", "kotlin", "hi")
        assertEquals(ErrorCode.DUPLICATE_NAME, fail(result).code)
        assertTrue(fail(result).message.contains("Greeting"))
    }

    @Test
    fun createTrimsNameAndNormalizesLanguage() {
        val s = store()
        ok(s.create("  Config block  ", "  KOTLIN ", "val x = 1"))
        val snippet = s.list().single()
        assertEquals("Config block", snippet.name)
        assertEquals("kotlin", snippet.language)
        assertEquals("s1", snippet.id)
    }

    @Test
    fun blankLanguageBecomesWildcardAll() {
        val s = store()
        ok(s.create("shared", "", "common text"))
        assertEquals(SnippetStore.LANG_ALL, s.list().single().language)
    }

    @Test
    fun sequentialIdsSkipNothingAndResumeAfterDelete() {
        val s = store()
        ok(s.create("a", "all", "a"))
        ok(s.create("b", "all", "b"))
        s.delete("s1")
        ok(s.create("c", "all", "c"))
        assertEquals(listOf("b", "c").sorted(), s.list().map { it.id }.sorted())
        assertEquals("s3", s.list().first { it.name == "c" }.id)
    }

    // ----------------------------------------------------------------- list

    @Test
    fun listFiltersByLanguageAndKeepsWildcard() {
        val s = store()
        ok(s.create("kt", "kotlin", "k body"))
        ok(s.create("py", "python", "p body"))
        ok(s.create("any", "all", "a body"))
        val kotlinOnly = s.list("kotlin")
        assertEquals(listOf("any", "kt"), kotlinOnly.map { it.name })
    }

    @Test
    fun listIsSortedByUsageThenName() {
        val s = store()
        ok(s.create("beta", "all", "b"))
        ok(s.create("alpha", "all", "a"))
        ok(s.create("hot", "all", "h"))
        s.touch("hot")
        s.touch("hot")
        assertEquals(listOf("hot", "alpha", "beta"), s.list().map { it.name })
    }

    @Test
    fun listWithNullLanguageReturnsEverything() {
        val s = store()
        ok(s.create("kt", "kotlin", "k"))
        ok(s.create("py", "python", "p"))
        assertEquals(2, s.list().size)
    }

    // --------------------------------------------------------------- update

    @Test
    fun updateUnknownIdFails() {
        assertEquals(
            ErrorCode.NOT_FOUND,
            fail(store().update("s404", name = "new")).code
        )
    }

    @Test
    fun updateRenameValidations() {
        val s = store()
        ok(s.create("first", "all", "one"))
        ok(s.create("second", "all", "two"))
        assertEquals(ErrorCode.BLANK_NAME, fail(s.update("s1", name = " ")).code)
        assertEquals(
            ErrorCode.NAME_TOO_LONG,
            fail(s.update("s1", name = "x".repeat(SnippetStore.MAX_NAME_CHARS + 1))).code
        )
        assertEquals(ErrorCode.DUPLICATE_NAME, fail(s.update("s1", name = "SECOND")).code)
    }

    @Test
    fun updateBodyValidations() {
        val s = store()
        ok(s.create("snip", "all", "one"))
        assertEquals(ErrorCode.BODY_BLANK, fail(s.update("s1", body = "  ")).code)
        assertEquals(
            ErrorCode.BODY_TOO_LARGE,
            fail(s.update("s1", body = "x".repeat(SnippetStore.MAX_BODY_CHARS + 1))).code
        )
    }

    @Test
    fun partialUpdateKeepsUntouchedFields() {
        val s = store()
        ok(s.create("snip", "kotlin", "old body"))
        ok(s.update("s1", body = "new body"))
        val snippet = s.find("s1")
        assertNotNull(snippet)
        assertEquals("snip", snippet!!.name)
        assertEquals("kotlin", snippet.language)
        assertEquals("new body", snippet.body)
    }

    @Test
    fun updateLanguageNormalizes() {
        val s = store()
        ok(s.create("snip", "kotlin", "x"))
        ok(s.update("s1", language = "  PYTHON "))
        assertEquals("python", s.find("s1")!!.language)
    }

    // ---------------------------------------------------------- delete/touch

    @Test
    fun deleteUnknownIdReturnsFalse() {
        assertFalse(store().delete("s404"))
    }

    @Test
    fun deleteExistingRemovesIt() {
        val s = store()
        ok(s.create("snip", "all", "x"))
        assertTrue(s.delete("s1"))
        assertNull(s.find("s1"))
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun touchUnknownIdReturnsFalse() {
        assertFalse(store().touch("s404"))
    }

    @Test
    fun touchIncrementsUsage() {
        val s = store()
        ok(s.create("snip", "all", "x"))
        assertTrue(s.touch("s1"))
        assertTrue(s.touch("s1"))
        assertEquals(2, s.find("s1")!!.usageCount)
    }

    // --------------------------------------------------------------- storage

    @Test
    fun corruptStorageLoadsAsEmptyAndRecovers() {
        val kv = InMemoryKeyValueStore()
        kv.putString(SnippetStore.KEY, "this is not json")
        val s = SnippetStore(kv)
        assertTrue(s.list().isEmpty())
        assertTrue(ok(s.create("fresh", "all", "works")).isNotEmpty())
        assertEquals(1, s.list().size)
    }

    @Test
    fun entriesWithMissingFieldsAreSkipped() {
        val kv = InMemoryKeyValueStore()
        val arr = JSONArray()
        arr.put(JSONObject().put("id", "s9").put("name", "broken")) // no body
        arr.put(
            JSONObject()
                .put("id", "s10")
                .put("name", "good")
                .put("language", "kotlin")
                .put("body", "ok")
                .put("usage", -7)
        )
        kv.putString(SnippetStore.KEY, JSONObject().put("snippets", arr).toString())
        val s = SnippetStore(kv)
        val only = s.list().single()
        assertEquals("good", only.name)
        assertEquals(0, only.usageCount) // negative usage clamped
    }

    // ------------------------------------------------------ Insert expansion

    @Test
    fun plainBodyHasNoStopsAndCaretAtEnd() {
        val result = expandOk("just text")
        assertEquals("just text", result.text)
        assertTrue(result.stops.isEmpty())
        assertEquals(9, SnippetStore.Insert.finalCaret(result))
    }

    @Test
    fun numberedStopsAreRecorded() {
        val result = expandOk("<$1|$2>")
        assertEquals("<|>", result.text)
        assertEquals(2, result.stops.size)
        assertEquals(1, result.stops[0].index)
        assertEquals(1, result.stops[0].start) // between < and |
        assertEquals(2, result.stops[1].start) // between | and >
        assertTrue(result.stops[0].start > result.stops[0].endInclusive)
    }

    @Test
    fun defaultStopExpandsAndSpansDefaultText() {
        val result = expandOk("hello \${1:world}!")
        assertEquals("hello world!", result.text)
        val stop = result.stops.single()
        assertEquals(1, stop.index)
        assertEquals(6, stop.start)
        assertEquals(10, stop.endInclusive)
    }

    @Test
    fun doubleDollarIsLiteralDollar() {
        val result = expandOk("cost = $$5")
        assertEquals("cost = $5", result.text)
        assertTrue(result.stops.isEmpty())
    }

    @Test
    fun trailingDollarStaysLiteral() {
        val result = expandOk("amount$")
        assertEquals("amount$", result.text)
    }

    @Test
    fun dollarBeforeNonPlaceholderStaysLiteral() {
        val result = expandOk("\$name and \$x9 end")
        assertEquals("\$name and \$x9 end", result.text)
        assertTrue(result.stops.isEmpty())
    }

    @Test
    fun unclosedBraceDegradesToLiteral() {
        val result = expandOk("prefix \${1:open tail")
        assertEquals("prefix \${1:open tail", result.text)
        assertTrue(result.stops.isEmpty())
    }

    @Test
    fun malformedIndexStaysVerbatim() {
        val result = expandOk("keep \${x:me} intact")
        assertEquals("keep \${x:me} intact", result.text)
        assertTrue(result.stops.isEmpty())
    }

    @Test
    fun negativeIndexStaysVerbatim() {
        val result = expandOk("keep \${-1:me} intact")
        assertEquals("keep \${-1:me} intact", result.text)
    }

    @Test
    fun bareBraceStopWithoutDefault() {
        val result = expandOk("a \${2} b")
        assertEquals("a  b", result.text)
        assertEquals(2, result.stops.single().index)
    }

    @Test
    fun sameIndexMayRepeat() {
        val result = expandOk("\${1:x} and \${1:y}")
        assertEquals("x and y", result.text)
        assertEquals(2, result.stops.size)
        assertEquals(0, result.stops[0].start)
        assertEquals(6, result.stops[1].start)
    }

    @Test
    fun finalCaretPrefersZeroStop() {
        val result = expandOk("\${1:first} end\$0")
        assertEquals("first end", result.text)
        assertEquals("first end".length, SnippetStore.Insert.finalCaret(result))
        assertEquals(0, result.stops.last { it.index == 0 }.start)
    }

    @Test
    fun zeroStopWithDefaultCaretAtStart() {
        val result = expandOk("value: \${0:default}")
        assertEquals("value: default", result.text)
        assertEquals(7, SnippetStore.Insert.finalCaret(result))
    }

    @Test
    fun expandRejectsBlankBody() {
        assertEquals(
            ErrorCode.BODY_BLANK,
            (SnippetStore.Insert.expand("  ") as SnippetStore.Insert.InsertResult.Failure).code
        )
    }

    @Test
    fun expandRejectsOverLongBody() {
        val body = "x".repeat(SnippetStore.MAX_BODY_CHARS + 1)
        val result = SnippetStore.Insert.expand(body)
        assertTrue(result is SnippetStore.Insert.InsertResult.Failure)
        assertEquals(ErrorCode.BODY_TOO_LARGE, (result as SnippetStore.Insert.InsertResult.Failure).code)
    }

    // --------------------------------------------------------------- options

    @Test
    fun languageNormalizationHelpers() {
        assertEquals("kotlin", SnippetStore.normalizeLanguage(" Kotlin "))
        assertEquals(SnippetStore.LANG_ALL, SnippetStore.normalizeLanguage(""))
        assertEquals(SnippetStore.LANG_ALL, SnippetStore.normalizeLanguage("   "))
    }
}
