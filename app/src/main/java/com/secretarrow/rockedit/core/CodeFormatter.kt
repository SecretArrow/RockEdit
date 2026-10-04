package com.secretarrow.rockedit.core

/**
 * Contract of a single code formatter.
 *
 * Implementations MUST be stateless (thread safe) and MUST extend
 * [AbstractCodeFormatter] instead of implementing this interface directly,
 * so the common guard pipeline (empty/blank/size/deadline/throw-catch)
 * always runs.
 */
interface CodeFormatter {
    /** Stable id, e.g. "json". Used in logs and tests. */
    val id: String

    /** Language ids this formatter handles (lowercase, trimmed). */
    val supportedLanguages: Set<String>

    /**
     * True for the catch-all formatter that accepts any language
     * (see [FormatterRegistry]). At most one fallback per registry.
     */
    val isFallback: Boolean get() = false

    fun format(request: FormatRequest): FormatResult
}

/**
 * Internal control-flow signals. They unwind deep recursions quickly and are
 * ALWAYS converted into [FormatResult.Failure] by the guard pipeline below —
 * they are never leaked to callers.
 */
internal class TimeoutSignal(
    val budgetMs: Long,
) : RuntimeException("formatting timed out after ${budgetMs}ms")

internal class DepthSignal(
    val maxDepth: Int,
) : RuntimeException("nesting exceeds $maxDepth levels")

/**
 * Guard pipeline shared by all formatters.
 *
 * Ordering of guards is deliberate:
 * 1. empty/blank input  -> Skipped (cheap, no error surfaced)
 * 2. size cap           -> INPUT_TOO_LARGE (protects memory)
 * 3. deadline prepared  -> cooperative cancellation for long runs
 * 4. formatValidated()  -> subclass work, wrapped in exhaustive catch:
 *    TimeoutSignal / DepthSignal / StackOverflowError / OutOfMemoryError /
 *    any other Exception. No execution path can throw past this class.
 */
abstract class AbstractCodeFormatter(
    protected val nowMs: () -> Long = System::currentTimeMillis,
) : CodeFormatter {
    final override fun format(request: FormatRequest): FormatResult {
        val started = nowMs()

        // GUARD 1 — empty / blank input: nothing to do, not an error.
        if (request.text.isEmpty()) return FormatResult.Skipped("input is empty")
        if (request.text.isBlank()) return FormatResult.Skipped("input is whitespace only")

        // GUARD 2 — size cap: reject before doing any work.
        if (request.text.length > FormatRequest.MAX_TEXT_CHARS) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.INPUT_TOO_LARGE,
                    "input has ${request.text.length} characters, limit is ${FormatRequest.MAX_TEXT_CHARS}",
                ),
            )
        }

        val deadline = Deadline.fromBudget(request.timeBudgetMs, nowMs)
        return try {
            when (val result = formatValidated(request.language, request.text, request.options, deadline)) {
                is FormatResult.Failure -> result
                is FormatResult.Skipped -> result
                is FormatResult.Success ->
                    result.copy(durationMs = (nowMs() - started).coerceAtLeast(0))
            }
        } catch (e: TimeoutSignal) {
            timeoutResult(e.budgetMs)
        } catch (e: DepthSignal) {
            depthResult(e.maxDepth)
        } catch (e: StackOverflowError) {
            // Recursive descent on pathological input — fail safely, not fatally.
            FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "input nesting is too deep (stack overflow); the file may be generated or corrupted",
                ),
            )
        } catch (e: OutOfMemoryError) {
            FormatResult.Failure(
                FormatError(FormatErrorCode.INPUT_TOO_LARGE, "not enough memory to format this input"),
            )
        } catch (e: Exception) {
            FormatResult.Failure(
                FormatError(
                    FormatErrorCode.INTERNAL_ERROR,
                    "unexpected failure in '$id': ${e.message ?: e::class.java.simpleName}",
                ),
            )
        }
    }

    /**
     * Subclass work. Input is guaranteed non-empty and within the size cap;
     * deadline is live. [language] is the trimmed, lowercased request id so
     * multi-language formatters can pick a per-language configuration while
     * staying stateless (no mutable formatter fields, hence thread safe).
     */
    protected abstract fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
    ): FormatResult

    protected fun timeoutResult(budgetMs: Long): FormatResult =
        FormatResult.Failure(
            FormatError(
                FormatErrorCode.TIMEOUT,
                "formatting exceeded its $budgetMs ms time budget; shrink the file or raise timeBudgetMs",
            ),
        )

    protected fun depthResult(maxDepth: Int): FormatResult =
        FormatResult.Failure(
            FormatError(
                FormatErrorCode.PARSE_ERROR,
                "nesting exceeds the maximum depth of $maxDepth levels",
            ),
        )

    /** Indentation unit for a nesting depth; empty string in minify mode. */
    protected fun pad(
        options: FormatOptions,
        depth: Int,
    ): String {
        if (options.minify) return ""
        val unit = if (options.indentStyle == IndentStyle.TABS) "\t" else " ".repeat(options.indentSize)
        return unit.repeat(depth.coerceAtLeast(0))
    }

    /**
     * Shared finishing pass for rendered output: apply the requested line
     * break style, trim trailing whitespace per line, and optionally insert
     * a final newline. The order matters: line breaks MUST be normalized
     * before trimming, otherwise a CR from CRLF input would block the
     * trailing-space trim. Renderers always emit '\n' internally, so this
     * is the ONLY place line breaks are converted.
     *
     * [trimTrailing] can be passed as false by structural formatters that
     * must preserve content verbatim inside multiline strings, block
     * comments or block scalars (trailing spaces there can be semantically
     * significant). Those formatters trim normal lines themselves.
     */
    protected fun applyFinalTouches(
        out: CharSequence,
        options: FormatOptions,
        trimTrailing: Boolean = true,
    ): String {
        var s = out.toString()
        s = LineBreak.normalize(s, options.lineBreak)
        if (options.trimTrailingWhitespace && trimTrailing) {
            s = s.split('\n').joinToString("\n") { it.trimEnd(' ', '\t') }
        }
        if (options.insertFinalNewline && !s.endsWith(options.lineBreak.value)) {
            s += options.lineBreak.value
        }
        return s
    }
}

/**
 * Routes languages to formatters. Exact matches win; unknown languages go to
 * the fallback formatter (whitespace normalization). Registration problems
 * (duplicate claims, blank ids, missing fallback) fail fast at construction
 * so misconfiguration can never hide.
 *
 * [format] is the outermost entry point and NEVER throws: even a broken
 * formatter implementation surfaces as [FormatErrorCode.INTERNAL_ERROR].
 */
class FormatterRegistry(
    formatters: List<CodeFormatter>,
) {
    private val exact: Map<String, CodeFormatter>
    private val fallback: CodeFormatter?

    init {
        val map = HashMap<String, CodeFormatter>()
        val fb = formatters.firstOrNull { it.isFallback }
        for (formatter in formatters) {
            if (formatter.isFallback) continue
            for (lang in formatter.supportedLanguages) {
                val key = lang.trim().lowercase()
                check(key.isNotEmpty()) { "formatter '${formatter.id}' declares a blank language id" }
                val previous = map.put(key, formatter)
                check(previous == null) {
                    "language '$key' is claimed by both '${previous?.id}' and '${formatter.id}'"
                }
            }
        }
        exact = map
        fallback = fb
    }

    /** Case-insensitive lookup; trims surrounding whitespace; null when absent. */
    fun formatterFor(language: String?): CodeFormatter? {
        if (language.isNullOrBlank()) return null
        return exact[language.trim().lowercase()] ?: fallback
    }

    fun supportedLanguageIds(): Set<String> = exact.keys.toSet()

    fun format(request: FormatRequest?): FormatResult {
        // GUARD A — null request (defensive for Java/interop callers).
        if (request == null) {
            return FormatResult.Failure(
                FormatError(FormatErrorCode.INTERNAL_ERROR, "format request is null"),
            )
        }
        // GUARD B — blank language (unreachable via FormatRequest's own check,
        // kept as belt-and-suspenders for deeply defensive layering).
        if (request.language.isBlank()) {
            return FormatResult.Failure(
                FormatError(FormatErrorCode.UNSUPPORTED_LANGUAGE, "language is blank"),
            )
        }
        // GUARD C — no formatter at all (registry without fallback).
        val formatter =
            formatterFor(request.language)
                ?: return FormatResult.Failure(
                    FormatError(
                        FormatErrorCode.UNSUPPORTED_LANGUAGE,
                        "no formatter for language '${request.language}'",
                    ),
                )
        // GUARD D — last-resort net: a formatter must never crash the editor.
        return try {
            formatter.format(request)
        } catch (t: Throwable) {
            FormatResult.Failure(
                FormatError(
                    FormatErrorCode.INTERNAL_ERROR,
                    "unexpected failure in '${formatter.id}': ${t.message ?: t::class.java.simpleName}",
                ),
            )
        }
    }

    companion object {
        /**
         * Standard registry: structural formatters for the mainstream and
         * smart-contract languages plus the universal whitespace fallback.
         * Language coverage lives in [FormatterLanguages].
         */
        fun default(nowMs: () -> Long = System::currentTimeMillis): FormatterRegistry =
            FormatterRegistry(
                listOf(
                    JsonFormatter(nowMs),
                    XmlFormatter(nowMs),
                    CssFormatter(nowMs),
                    BraceFormatter(nowMs),
                    IndentFormatter(nowMs),
                    LispFormatter(nowMs),
                    YamlFormatter(nowMs),
                    WhitespaceFormatter(nowMs),
                ),
            )
    }
}
