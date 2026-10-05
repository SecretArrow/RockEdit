package com.secretarrow.rockedit.core

/**
 * Static catalog of the prettier 2.8.8 WASM/WebView formatter engine
 * (backlog item 10). The engine takes over the tier-2 languages that the
 * structural formatters can only re-indent heuristically: JavaScript/JSX,
 * TypeScript/TSX, HTML, Markdown and GraphQL — all formatted 100% locally
 * in a headless WebView (no network, no telemetry).
 *
 * Pure JVM: no Android imports, fully unit-testable.
 *
 * Fail-fast validation: the mapping table is checked at class
 * initialization, so a typo in a parser name crashes immediately instead
 * of producing [FormatErrorCode.ENGINE_UNAVAILABLE] at runtime.
 */
object WasmFormatterCatalog {
    /** Version tag of the bundled prettier standalone build (assets/formatter). */
    const val ENGINE_ID = "prettier-2.8.8"

    /**
     * Hard cap on input handed to the JS engine. A megabyte of source can
     * take multiple seconds inside the WebView and the payload travels
     * through evaluateJavascript, so larger documents are rejected with
     * INPUT_TOO_LARGE before any work starts.
     */
    const val MAX_INPUT_CHARS = 1_000_000

    /**
     * language id -> prettier parser name. Keys are lowercase and trimmed
     * (the same normalization [parserFor] applies). Aliases map to the same
     * parser as their parent language: js/jsx -> babel, ts/tsx -> typescript,
     * htm/vue -> html, md -> markdown.
     */
    private val LANGUAGE_TO_PARSER: Map<String, String> =
        linkedMapOf(
            "javascript" to "babel",
            "js" to "babel",
            "jsx" to "babel",
            "typescript" to "typescript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "html" to "html",
            "htm" to "html",
            "vue" to "html",
            "markdown" to "markdown",
            "md" to "markdown",
            "graphql" to "graphql",
        )

    /** Prettier parser names this catalog is allowed to emit. */
    private val KNOWN_PARSERS: Set<String> = setOf("babel", "typescript", "html", "markdown", "graphql")

    /** Language ids claimed by the WASM engine (lowercase, non-blank). */
    val languages: Set<String>
        get() = LANGUAGE_TO_PARSER.keys

    /**
     * Resolves the prettier parser for a language id. Trims and lowercases
     * first ("  JS " -> "babel"); returns null when the language is unknown,
     * blank or null.
     */
    fun parserFor(languageId: String?): String? {
        if (languageId == null) return null
        val key = languageId.trim().lowercase()
        if (key.isEmpty()) return null
        return LANGUAGE_TO_PARSER[key]
    }

    init {
        check(LANGUAGE_TO_PARSER.isNotEmpty()) { "WASM formatter catalog must not be empty" }
        for ((lang, parser) in LANGUAGE_TO_PARSER) {
            check(lang.isNotEmpty()) { "blank language id in WASM formatter catalog" }
            check(lang == lang.trim().lowercase()) {
                "language id '$lang' must be stored lowercase and trimmed"
            }
            check(parser in KNOWN_PARSERS) {
                "language '$lang' maps to unknown prettier parser '$parser'"
            }
        }
    }
}
