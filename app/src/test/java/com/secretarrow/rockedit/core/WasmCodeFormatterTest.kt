package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for [WasmCodeFormatter] with a fake host launcher:
 * success (changed/unchanged), engine PARSE_ERROR passthrough, unavailable
 * host with and without degraded native fallback, deadline expiry, and the
 * shared guard pipeline (Skipped / INPUT_TOO_LARGE / UNSUPPORTED_LANGUAGE).
 */
class WasmCodeFormatterTest {
    private companion object {
        const val FMT_ID = "wasm-prettier"
        const val FORMATTED = "let x = { a: 1, b: 2 };\n"
        const val INPUT = "let x={a:1};"
    }

    /** Records the arguments and answers with a canned response envelope. */
    private class RecordingLauncher(
        val response: String?,
        val failure: WasmHostUnavailableException? = null,
    ) {
        var payloads = mutableListOf<String>()
        var budgets = mutableListOf<Long>()

        fun launch(
            payload: String,
            budgetMs: Long,
        ): String {
            payloads.add(payload)
            budgets.add(budgetMs)
            failure?.let { throw it }
            return response ?: throw AssertionError("launcher called without a response")
        }
    }

    /** Tiny degraded-mode engine used instead of the real BraceFormatter. */
    private class FakeFallbackFormatter(
        override val supportedLanguages: Set<String>,
        private val output: String,
    ) : AbstractCodeFormatter() {
        override val id: String = "fake-fallback"

        override fun formatValidated(
            language: String,
            text: String,
            options: FormatOptions,
            deadline: Deadline,
        ): FormatResult = FormatResult.Success(output, output != text, 0L)
    }

    private fun okLauncher(text: String): RecordingLauncher = RecordingLauncher(WasmFormatterContract.buildOkResponse(FMT_ID, text))

    // ------------------------------------------------------------ success

    @Test
    fun successReportsChangedText() {
        val launcher = okLauncher(FORMATTED)
        val formatter = WasmCodeFormatter(launchHost = launcher::launch)
        val result = formatter.format(FormatRequest(INPUT, "javascript"))
        assertTrue("expected Success but was $result", result is FormatResult.Success)
        val success = result as FormatResult.Success
        assertEquals(FORMATTED, success.formattedText)
        assertTrue(success.changed)
        assertTrue(success.durationMs >= 0)
    }

    @Test
    fun successReportsUnchangedWhenTextIsAlreadyFormatted() {
        val launcher = okLauncher(FORMATTED)
        val formatter = WasmCodeFormatter(launchHost = launcher::launch)
        val result = formatter.format(FormatRequest(FORMATTED, "javascript"))
        val success = result as FormatResult.Success
        assertEquals(FORMATTED, success.formattedText)
        assertTrue(!success.changed)
    }

    @Test
    fun launcherReceivesPayloadWithParserAndSaneBudget() {
        val launcher = okLauncher(FORMATTED)
        val formatter = WasmCodeFormatter(launchHost = launcher::launch)
        formatter.format(FormatRequest(INPUT, "javascript"))
        assertEquals(1, launcher.payloads.size)
        val payload = launcher.payloads[0]
        assertTrue(payload.contains("\"parser\":\"babel\""))
        assertTrue(payload.contains("\"id\":\"wasm-prettier\""))
        assertTrue(payload.contains("\"text\":\"let x={a:1};\""))
        // Default request budget is 5000 ms; a bit may have elapsed.
        val budget = launcher.budgets[0]
        assertTrue("budget should be ~5000 but was $budget", budget in 4_500..5_000)
    }

    // ------------------------------------------------------- engine errors

    @Test
    fun engineParseErrorIsPassedThrough() {
        val launcher =
            RecordingLauncher(
                WasmFormatterContract.buildErrResponse(FMT_ID, "PARSE_ERROR", "Unexpected token (1:6)"),
            )
        val formatter = WasmCodeFormatter(launchHost = launcher::launch)
        val result = formatter.format(FormatRequest(INPUT, "javascript"))
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertEquals("Unexpected token (1:6)", error.message)
    }

    @Test
    fun otherEngineErrorBecomesInternalError() {
        val launcher =
            RecordingLauncher(WasmFormatterContract.buildErrResponse(FMT_ID, "ENGINE_ERROR", "boom"))
        val formatter = WasmCodeFormatter(launchHost = launcher::launch)
        val result = formatter.format(FormatRequest(INPUT, "javascript"))
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.INTERNAL_ERROR, error.code)
        assertTrue(error.message.contains("engine: ENGINE_ERROR: boom"))
    }

    @Test
    fun malformedResponseBecomesInternalError() {
        val launcher = RecordingLauncher("not json at all")
        val formatter = WasmCodeFormatter(launchHost = launcher::launch)
        val result = formatter.format(FormatRequest(INPUT, "javascript"))
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.INTERNAL_ERROR, error.code)
        assertTrue(error.message.contains("MALFORMED"))
    }

    // ---------------------------------------------------- host unavailable

    @Test
    fun unavailableHostWithoutFallbackIsEngineUnavailable() {
        val launcher =
            RecordingLauncher(null, WasmHostUnavailableException("webview asset missing"))
        val formatter = WasmCodeFormatter(launchHost = launcher::launch)
        val result = formatter.format(FormatRequest(INPUT, "javascript"))
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.ENGINE_UNAVAILABLE, error.code)
        assertTrue(error.message.contains("prettier engine unavailable"))
        assertTrue(error.message.contains("webview asset missing"))
    }

    @Test
    fun degradedModeUsesNativeFallbackForClaimedLanguage() {
        val launcher =
            RecordingLauncher(null, WasmHostUnavailableException("webview crashed at startup"))
        val fallback = FakeFallbackFormatter(setOf("javascript", "typescript", "graphql"), "FALLBACK-OUTPUT")
        val formatter = WasmCodeFormatter(launchHost = launcher::launch, nativeFallback = fallback)
        val result = formatter.format(FormatRequest(INPUT, "javascript"))
        assertTrue(result is FormatResult.Success)
        assertEquals("FALLBACK-OUTPUT", (result as FormatResult.Success).formattedText)
    }

    @Test
    fun degradedModeSkippedWhenFallbackDoesNotClaimLanguage() {
        val launcher =
            RecordingLauncher(null, WasmHostUnavailableException("webview crashed at startup"))
        val fallback = FakeFallbackFormatter(setOf("javascript"), "FALLBACK-OUTPUT")
        val formatter = WasmCodeFormatter(launchHost = launcher::launch, nativeFallback = fallback)
        // Markdown is claimed by the WASM engine but by no native formatter.
        val result = formatter.format(FormatRequest("# Title\n", "markdown"))
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.ENGINE_UNAVAILABLE, error.code)
        assertTrue(error.message.contains("webview crashed at startup"))
    }

    // ------------------------------------------------------------ deadline

    @Test
    fun expiredDeadlineAfterHostCallProducesTimeout() {
        var clock = 1_000_000L
        val launcher = okLauncher(FORMATTED)
        val formatter =
            WasmCodeFormatter(
                nowMs = {
                    clock += 10_000L
                    clock
                },
                launchHost = launcher::launch,
            )
        val result = formatter.format(FormatRequest(INPUT, "javascript", FormatOptions(), timeBudgetMs = 5_000L))
        assertTrue(result is FormatResult.Failure)
        assertEquals(FormatErrorCode.TIMEOUT, (result as FormatResult.Failure).error.code)
        // Remaining time was 0 -> the launcher still got the 100 ms floor.
        assertEquals(100L, launcher.budgets[0])
    }

    // ------------------------------------------------------ guard pipeline

    @Test
    fun emptyAndBlankInputAreSkipped() {
        val formatter = WasmCodeFormatter(launchHost = { _, _ -> "must not be called" })
        assertTrue(formatter.format(FormatRequest("", "javascript")) is FormatResult.Skipped)
        assertTrue(formatter.format(FormatRequest("   \n\t", "javascript")) is FormatResult.Skipped)
    }

    @Test
    fun inputAboveCatalogCapIsTooLarge() {
        val formatter = WasmCodeFormatter(launchHost = { _, _ -> "must not be called" })
        val big = "a".repeat(WasmFormatterCatalog.MAX_INPUT_CHARS + 1)
        val result = formatter.format(FormatRequest(big, "javascript"))
        assertTrue(result is FormatResult.Failure)
        val error = (result as FormatResult.Failure).error
        assertEquals(FormatErrorCode.INPUT_TOO_LARGE, error.code)
        assertTrue(error.message.contains("1000001"))
        assertTrue(error.message.contains("1000000"))
    }

    @Test
    fun languageWithoutParserIsUnsupported() {
        val formatter = WasmCodeFormatter(launchHost = { _, _ -> "must not be called" })
        val result = formatter.format(FormatRequest("print('x')", "python"))
        assertTrue(result is FormatResult.Failure)
        assertEquals(FormatErrorCode.UNSUPPORTED_LANGUAGE, (result as FormatResult.Failure).error.code)
    }

    // ----------------------------------------------------------- contract

    @Test
    fun parserResponseContractStaysWired() {
        // Guard against accidental drift between the contract and the
        // formatter: a built Ok must parse back into the same text.
        val response = WasmFormatterContract.parsePayload(FMT_ID, WasmFormatterContract.buildOkResponse(FMT_ID, FORMATTED))
        assertEquals(WasmResponse.Ok(FORMATTED), response)
    }
}
