package com.secretarrow.rockedit.core

/**
 * Thrown by the WebView host bridge ([com.secretarrow.rockedit.ui.WasmFormatterHost])
 * when the prettier engine could not be run AT ALL — missing formatter
 * assets, WebView creation failure, page load error, render process crash,
 * or the time budget running out. This is deliberately distinct from a
 * formatting failure: the document was never processed, so the caller can
 * degrade gracefully instead of reporting a syntax error to the user.
 */
class WasmHostUnavailableException(
    val reason: String,
) : RuntimeException(reason)

/**
 * Formatter engine bridge that runs prettier 2.8.8 inside a headless
 * WebView (backlog item 10). Claims the tier-2 languages from
 * [WasmFormatterCatalog]: JavaScript/JSX, TypeScript/TSX, HTML, Markdown
 * and GraphQL — all formatted 100% locally, no network, no telemetry.
 *
 * This class never touches Android APIs: the WebView lives behind the
 * injected [launchHost] lambda (usually
 * `WasmFormatterHost::launch`), so the whole pipeline stays JVM-testable.
 *
 * Launch host contract ([launchHost]):
 * - is called on a NON-UI thread with the request envelope from
 *   [WasmFormatterContract.buildPayload] and the remaining time budget,
 * - returns the JSON response envelope from host.html on success,
 * - throws [WasmHostUnavailableException] when the engine cannot run.
 *   Any OTHER exception escapes to the shared guard pipeline and surfaces
 *   as INTERNAL_ERROR (a broken host is an implementation bug, documented
 *   and observable as such).
 *
 * Defensive rules (kejadian -> penanganan):
 *
 * | Event                                          | Handling |
 * |------------------------------------------------|----------|
 * | Language has no prettier parser                 | Failure(UNSUPPORTED_LANGUAGE) |
 * | Input above [WasmFormatterCatalog.MAX_INPUT_CHARS] | Failure(INPUT_TOO_LARGE, actual size) |
 * | launchHost throws WasmHostUnavailableException  | Degraded mode: nativeFallback.format() when the fallback claims the language, else Failure(ENGINE_UNAVAILABLE, reason) |
 * | Host response is malformed / id mismatch        | Failure(INTERNAL_ERROR, "engine: MALFORMED: ...") — never crashes |
 * | Engine reports PARSE_ERROR                      | Failure(PARSE_ERROR, engine message) passthrough |
 * | Engine reports any other error code             | Failure(INTERNAL_ERROR, "engine: <code>: <message>") |
 * | Deadline expired when the host call returns     | timeoutResult(budget) — Failure(TIMEOUT) |
 * | Empty/blank input or oversize beyond 2M chars   | Handled by AbstractCodeFormatter guards (Skipped / INPUT_TOO_LARGE) |
 *
 * Degraded mode (documented): when the JS engine cannot start and a
 * [nativeFallback] is installed that claims the requested language, the
 * request is re-entered through `nativeFallback.format(...)` — the
 * original heuristic formatter (e.g. [BraceFormatter] for JavaScript).
 * The fallback re-runs the idempotent guard pipeline with the same time
 * budget, so a degraded run can use at most one extra budget window.
 */
class WasmCodeFormatter(
    nowMs: () -> Long = System::currentTimeMillis,
    private val launchHost: (payload: String, budgetMs: Long) -> String,
    private val nativeFallback: CodeFormatter? = null,
) : AbstractCodeFormatter(nowMs) {
    override val id: String = "wasm-prettier"

    override val supportedLanguages: Set<String> = WasmFormatterCatalog.languages

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
    ): FormatResult {
        val parser =
            WasmFormatterCatalog.parserFor(language)
                ?: return FormatResult.Failure(
                    FormatError(
                        FormatErrorCode.UNSUPPORTED_LANGUAGE,
                        "language '$language' has no prettier parser (engine ${WasmFormatterCatalog.ENGINE_ID})",
                    ),
                )
        if (text.length > WasmFormatterCatalog.MAX_INPUT_CHARS) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.INPUT_TOO_LARGE,
                    "input has ${text.length} characters, prettier engine limit is " +
                        WasmFormatterCatalog.MAX_INPUT_CHARS,
                ),
            )
        }
        val payload = WasmFormatterContract.buildPayload(id, parser, text, options)
        val budget = deadline.remainingMs().coerceAtLeast(100)
        val raw =
            try {
                launchHost(payload, budget)
            } catch (e: WasmHostUnavailableException) {
                return degradedOrUnavailable(language, text, options, deadline, e)
            }
        if (deadline.isExpired()) return timeoutResult(deadline.budgetMs)
        return when (val response = WasmFormatterContract.parsePayload(id, raw)) {
            is WasmResponse.Ok ->
                FormatResult.Success(response.text, response.text != text, 0L)
            is WasmResponse.Err ->
                when (response.code) {
                    "PARSE_ERROR" ->
                        FormatResult.Failure(
                            FormatError(FormatErrorCode.PARSE_ERROR, response.message),
                        )
                    else ->
                        FormatResult.Failure(
                            FormatError(
                                FormatErrorCode.INTERNAL_ERROR,
                                "engine: ${response.code}: ${response.message}",
                            ),
                        )
                }
        }
    }

    /**
     * Engine unavailable: fall back to the native heuristic formatter when
     * it claims the language (degraded mode), otherwise report
     * ENGINE_UNAVAILABLE with the host reason so the UI can localize it.
     */
    private fun degradedOrUnavailable(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
        cause: WasmHostUnavailableException,
    ): FormatResult {
        val fallback = nativeFallback
        if (fallback != null && language in fallback.supportedLanguages) {
            // Guards are idempotent: re-entering format() re-checks
            // empty/blank/size and gives the fallback its own deadline
            // window derived from the same budget (documented above).
            val request = FormatRequest(text, language, options, deadline.budgetMs)
            return fallback.format(request)
        }
        return FormatResult.Failure(
            FormatError(
                FormatErrorCode.ENGINE_UNAVAILABLE,
                "prettier engine unavailable: ${cause.reason}",
            ),
        )
    }
}
