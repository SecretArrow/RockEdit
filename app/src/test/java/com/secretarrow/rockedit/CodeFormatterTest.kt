package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.AbstractCodeFormatter
import com.secretarrow.rockedit.core.Deadline
import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.FormatterRegistry
import com.secretarrow.rockedit.core.JsonFormatter
import com.secretarrow.rockedit.core.WhitespaceFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for the guard pipeline and the registry routing:
 * empty/blank input, size cap, timeout, fallback routing, misconfiguration.
 */
class CodeFormatterTest {
    /** Minimal formatter used to test the shared pipeline in isolation. */
    private class StubFormatter(
        override val id: String,
        override val supportedLanguages: Set<String>,
        private val result: FormatResult,
        override val isFallback: Boolean = false,
    ) : AbstractCodeFormatter() {
        override fun formatValidated(
            language: String,
            text: String,
            options: FormatOptions,
            deadline: Deadline,
        ): FormatResult {
            if (deadline.isExpired()) return timeoutResult(deadline.budgetMs)
            return result
        }
    }

    // ------------------------------------------------------------ registry

    @Test
    fun registryRoutesExactLanguage() {
        val registry =
            FormatterRegistry(
                listOf(StubFormatter("f-json", setOf("json"), FormatResult.Skipped("x"))),
            )
        assertEquals("f-json", registry.formatterFor("json")?.id)
    }

    @Test
    fun registryIsCaseInsensitiveAndTrims() {
        val registry =
            FormatterRegistry(
                listOf(StubFormatter("f-json", setOf("json"), FormatResult.Skipped("x"))),
            )
        assertEquals("f-json", registry.formatterFor("  JSON ")?.id)
    }

    @Test
    fun registryFallsBackForUnknownLanguage() {
        val registry = FormatterRegistry.default()
        assertEquals("indent", registry.formatterFor("python")?.id)
        assertEquals("whitespace", registry.formatterFor("txt")?.id)
    }

    @Test
    fun registryDuplicateLanguageClaimThrows() {
        val f1 = StubFormatter("f1", setOf("json"), FormatResult.Skipped("x"))
        val f2 = StubFormatter("f2", setOf("json"), FormatResult.Skipped("x"))
        try {
            FormatterRegistry(listOf(f1, f2))
            throw AssertionError("expected IllegalStateException for duplicate claim")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("json"))
        }
    }

    @Test
    fun registryBlankLanguageClaimThrows() {
        try {
            FormatterRegistry(listOf(StubFormatter("bad", setOf("  "), FormatResult.Skipped("x"))))
            throw AssertionError("expected IllegalStateException for blank language")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun registryNullRequestFailsSafely() {
        val result = FormatterRegistry.default().format(null)
        assertTrue(result is FormatResult.Failure)
        assertEquals(FormatErrorCode.INTERNAL_ERROR, (result as FormatResult.Failure).error.code)
    }

    @Test
    fun registryFallbackFormatterNeverCrashes() {
        // A formatter that literally throws must surface as INTERNAL_ERROR,
        // never propagate an exception to the caller.
        val bomb =
            object : AbstractCodeFormatter() {
                override val id = "bomb"
                override val supportedLanguages = setOf("bomb")

                override fun formatValidated(
                    language: String,
                    text: String,
                    options: FormatOptions,
                    deadline: Deadline,
                ): FormatResult {
                    error("exploded")
                }
            }
        val registry = FormatterRegistry(listOf(bomb, WhitespaceFormatter()))
        val result = registry.format(FormatRequest("hello", "bomb"))
        assertTrue(result is FormatResult.Failure)
        assertEquals(FormatErrorCode.INTERNAL_ERROR, (result as FormatResult.Failure).error.code)
    }

    // -------------------------------------------------- guard pipeline

    @Test
    fun emptyTextIsSkippedNotError() {
        val result = FormatterRegistry.default().format(FormatRequest("", "json"))
        assertTrue(result is FormatResult.Skipped)
    }

    @Test
    fun blankTextIsSkippedNotError() {
        val result = FormatterRegistry.default().format(FormatRequest("   \n\t  ", "json"))
        assertTrue(result is FormatResult.Skipped)
    }

    @Test
    fun oversizeTextFailsWithTooLarge() {
        val big = "a".repeat(FormatRequest.MAX_TEXT_CHARS + 1)
        val result = FormatterRegistry.default().format(FormatRequest(big, "json"))
        assertTrue(result is FormatResult.Failure)
        assertEquals(FormatErrorCode.INPUT_TOO_LARGE, (result as FormatResult.Failure).error.code)
    }

    @Test
    fun expiredDeadlineProducesTimeout() {
        // A clock that jumps 10 seconds on every read forces the deadline to
        // expire immediately after construction.
        var clock = 1_000_000L
        val formatter =
            JsonFormatter {
                clock += 10_000L
                clock
            }
        val result = formatter.format(FormatRequest("""{"a":1}""", "json", FormatOptions(), timeBudgetMs = 5_000L))
        assertTrue(result is FormatResult.Failure)
        assertEquals(FormatErrorCode.TIMEOUT, (result as FormatResult.Failure).error.code)
    }

    @Test
    fun successCarriesNonNegativeDuration() {
        val result = FormatterRegistry.default().format(FormatRequest("""{"a":1}""", "json"))
        assertTrue(result is FormatResult.Success)
        assertTrue((result as FormatResult.Success).durationMs >= 0)
    }

    // ---------------------------------------------- constructor validation

    @Test(expected = IllegalArgumentException::class)
    fun blankLanguageRejectedAtConstruction() {
        FormatRequest("x", "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun timeBudgetBelowMinimumRejected() {
        FormatRequest("x", "json", FormatOptions(), timeBudgetMs = 50L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timeBudgetAboveMaximumRejected() {
        FormatRequest("x", "json", FormatOptions(), timeBudgetMs = 61_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun indentSizeZeroRejected() {
        FormatOptions(indentSize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun indentSizeAboveEightRejected() {
        FormatOptions(indentSize = 9)
    }

    @Test
    fun defaultRegistryExposesExpectedLanguages() {
        val ids = FormatterRegistry.default().supportedLanguageIds()
        assertTrue(ids.containsAll(setOf("json", "xml", "svg", "plist", "css")))
    }
}
