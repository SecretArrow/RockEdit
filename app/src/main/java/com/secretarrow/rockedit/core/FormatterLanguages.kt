package com.secretarrow.rockedit.core

/**
 * Per-language configuration tables for the structural formatters.
 *
 * Tiering principle (correctness first):
 * - Tier 1 (structural): languages whose block structure can be derived
 *   reliably line-by-line get real re-indentation.
 * - Tier 2 (safe cleanup): everything else stays on the whitespace
 *   fallback formatter, which never risks breaking working code.
 *
 * Smart-contract languages and their tiers:
 *   Solidity, Move, Cairo, Cadence, Motoko, Aiken, Leo, Fe, ink!, CosmWasm,
 *   Soroban  -> [BraceFormatter] (brace structure, C-family rules)
 *   Vyper    -> [IndentFormatter] (Python-style indentation)
 *   Clarity, Michelson -> [LispFormatter] (parenthesis structure)
 */
object FormatterLanguages {
    // ------------------------------------------------------- brace family

    /**
     * Configuration for [BraceFormatter]: block structure comes from
     * `{`/`}`; strings and comments are skipped by [LineScanner].
     */
    class BraceLanguage(
        val id: String,
        val lineComments: List<String> = listOf("//"),
        val blockComments: List<Pair<String, String>> = listOf("/*" to "*/"),
        val stringDelims: List<Char> = listOf('"', '\''),
        val multilineStringDelims: List<Pair<String, String>> = emptyList(),
        /** C-family preprocessor lines start at column 0. */
        val hashPreprocessor: Boolean = false,
    )

    private fun cFamily(
        id: String,
        hash: Boolean = false,
    ) = BraceLanguage(
        id = id,
        hashPreprocessor = hash,
    )

    private val KOTLIN =
        BraceLanguage(
            id = "kotlin",
            multilineStringDelims = listOf("\"\"\"" to "\"\"\""),
        )
    private val GO =
        BraceLanguage(
            id = "go",
            multilineStringDelims = listOf("`" to "`"),
        )
    private val JS_FAMILY =
        BraceLanguage(
            id = "javascript",
            multilineStringDelims = listOf("`" to "`"),
        )

    /** Rust has no `'` string delim: lifetimes like `'a` must not open a string. */
    private val RUST =
        BraceLanguage(
            id = "rust",
            stringDelims = listOf('"'),
        )

    /** id -> config; unlisted claimed ids use the generic C-family default. */
    private val BRACE_CONFIGS: Map<String, BraceLanguage> =
        listOf(
            KOTLIN,
            cFamily("java"),
            cFamily("c", hash = true),
            cFamily("cpp", hash = true),
            cFamily("csharp", hash = true),
            cFamily("objc", hash = true),
            cFamily("swift"),
            cFamily("dart"),
            JS_FAMILY,
            BraceLanguage(id = "typescript", multilineStringDelims = JS_FAMILY.multilineStringDelims),
            GO,
            RUST,
            cFamily("php"),
            cFamily("scala"),
            cFamily("groovy"),
            cFamily("zig"),
            cFamily("r"),
            BraceLanguage(
                id = "powershell",
                lineComments = listOf("#"),
                stringDelims = listOf('"', '\''),
                blockComments = listOf("<#" to "#>"),
            ),
            cFamily("protobuf"),
            cFamily("graphql"),
            // --- smart contracts: EVM and beyond ---
            BraceLanguage(
                id = "solidity",
                lineComments = listOf("//"),
            ),
            cFamily("move"),
            cFamily("cairo"),
            cFamily("cadence"),
            cFamily("motoko"),
            cFamily("aiken"),
            cFamily("leo"),
            cFamily("fe"),
        ).associateBy { it.id }

    /** Alias ids route to the config of their parent language. */
    private val BRACE_ALIASES: Map<String, String> =
        mapOf(
            "sol" to "solidity",
            "cosmwasm" to "rust",
            "ink" to "rust",
            "soroban" to "rust",
        )

    fun braceConfigFor(languageId: String): BraceLanguage {
        val key = languageId.trim().lowercase()
        BRACE_CONFIGS[key]?.let { return it }
        BRACE_ALIASES[key]?.let { alias ->
            BRACE_CONFIGS[alias]?.let { return it }
        }
        return BraceLanguage(id = key)
    }

    // ------------------------------------------------------- indent family

    /**
     * Configuration for [IndentFormatter].
     *
     * Two structural styles are supported:
     * - REINDENT (Python/Vyper): user indentation IS the structure; it is
     *   detected and normalized, never re-derived from syntax.
     * - KEYWORD (Ruby/Lua/Elixir/Julia/LaTeX): blocks open and close with
     *   anchored keywords, so depth can be re-derived.
     */
    class IndentLanguage(
        val id: String,
        val style: Style,
        val lineComments: List<String> = emptyList(),
        val stringDelims: List<Char> = listOf('"', '\''),
        val multilineStringDelims: List<Pair<String, String>> = emptyList(),
        /** keywords that start a line belonging one level up (else, elsif...). */
        val dedentStarts: List<Regex> = emptyList(),
        /** anchored openers, e.g. ^def\b (keyword style only). */
        val openers: List<Regex> = emptyList(),
        /** anchored closers, e.g. ^end\b (keyword style only). */
        val closers: List<Regex> = emptyList(),
        /** a line matching this never opens a block (guards `x = 1 if y` style). */
        val openerGuard: Regex? = null,
    ) {
        enum class Style { REINDENT, KEYWORD }
    }

    private val PYTHON =
        IndentLanguage(
            id = "python",
            style = IndentLanguage.Style.REINDENT,
            lineComments = listOf("#"),
            multilineStringDelims = listOf("\"\"\"" to "\"\"\"", "'''" to "'''"),
        )

    private val RUBY =
        IndentLanguage(
            id = "ruby",
            style = IndentLanguage.Style.KEYWORD,
            lineComments = listOf("#"),
            dedentStarts = listOf(Regex("^(else|elsif|when|rescue|ensure)\\b")),
            openers =
                listOf(
                    Regex("^(def|class|module|if|unless|case|while|until|begin)\\b"),
                    Regex("\\bdo\\b"),
                ),
            closers = listOf(Regex("^end\\b")),
            openerGuard = Regex("\\bend\\b"),
        )

    private val LUA =
        IndentLanguage(
            id = "lua",
            style = IndentLanguage.Style.KEYWORD,
            lineComments = listOf("--"),
            stringDelims = listOf('"', '\''),
            dedentStarts = listOf(Regex("^(else|elseif)\\b")),
            openers =
                listOf(
                    Regex("^(function|if|for|while|do)\\b"),
                    Regex("\\bdo\\b"),
                ),
            closers = listOf(Regex("^(end|until)\\b")),
            openerGuard = Regex("\\bend\\b"),
        )

    private val ELIXIR =
        IndentLanguage(
            id = "elixir",
            style = IndentLanguage.Style.KEYWORD,
            lineComments = listOf("#"),
            stringDelims = listOf('"'),
            dedentStarts = listOf(Regex("^(else|rescue|catch|after)\\b")),
            openers = listOf(Regex("\\bdo\\s*$"), Regex("^fn\\b"), Regex("->$")),
            closers = listOf(Regex("^end\\b")),
            openerGuard = Regex("\\bend\\b"),
        )

    private val JULIA =
        IndentLanguage(
            id = "julia",
            style = IndentLanguage.Style.KEYWORD,
            lineComments = listOf("#"),
            dedentStarts = listOf(Regex("^(else|elseif|catch|finally)\\b")),
            openers =
                listOf(
                    Regex("^(function|macro|if|while|for|begin|let|do|module|struct|quote|try)\\b"),
                    Regex("^(mutable\\s+struct|abstract\\s+type|primitive\\s+type)\\b"),
                ),
            closers = listOf(Regex("^end\\b")),
            openerGuard = Regex("\\bend\\b"),
        )

    private val LATEX =
        IndentLanguage(
            id = "latex",
            style = IndentLanguage.Style.KEYWORD,
            lineComments = listOf("%"),
            stringDelims = emptyList(),
            openers = listOf(Regex("^\\\\begin\\{[^}]*}")),
            closers = listOf(Regex("^\\\\end\\{[^}]*}")),
        )

    private val INDENT_CONFIGS: Map<String, IndentLanguage> =
        listOf(
            PYTHON,
            IndentLanguage(
                id = "vyper",
                style = IndentLanguage.Style.REINDENT,
                lineComments = listOf("#"),
                multilineStringDelims = listOf("\"\"\"" to "\"\"\"", "'''" to "'''"),
            ),
            RUBY,
            LUA,
            ELIXIR,
            JULIA,
            LATEX,
        ).associateBy { it.id }

    fun indentConfigFor(languageId: String): IndentLanguage {
        val key = languageId.trim().lowercase()
        return INDENT_CONFIGS[key] ?: IndentLanguage(
            id = key,
            style = IndentLanguage.Style.REINDENT,
            lineComments = listOf("#"),
        )
    }

    // ------------------------------------------------------- lisp family

    /** Configuration for [LispFormatter]: structure = balanced parentheses. */
    class LispLanguage(
        val id: String,
        val lineComments: List<String>,
        val blockComments: List<Pair<String, String>> = emptyList(),
    )

    fun lispConfigFor(languageId: String): LispLanguage =
        when (languageId.trim().lowercase()) {
            "clarity" -> LispLanguage("clarity", lineComments = listOf(";;"))
            "michelson" -> LispLanguage("michelson", lineComments = listOf("//", "#"))
            "clojure", "clojurescript" ->
                LispLanguage(
                    "clojure",
                    lineComments = listOf(";"),
                    blockComments = listOf("#|" to "|#"),
                )
            "scheme", "racket" -> LispLanguage("scheme", lineComments = listOf(";"), blockComments = listOf("#|" to "|#"))
            "lisp" -> LispLanguage("lisp", lineComments = listOf(";"), blockComments = listOf("#|" to "|#"))
            else -> LispLanguage(languageId.trim().lowercase(), lineComments = listOf(";"))
        }
}
