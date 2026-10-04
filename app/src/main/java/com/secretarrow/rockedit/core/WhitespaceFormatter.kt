package com.secretarrow.rockedit.core

/**
 * Universal fallback formatter: it never touches syntax, only whitespace.
 *
 * Contract:
 * - line breaks are normalized to the requested [FormatOptions.lineBreak]
 *   style (CRLF and lone CR are both handled),
 * - trailing spaces/tabs at the end of each line are removed when
 *   [FormatOptions.trimTrailingWhitespace] is set,
 * - a final newline is appended when [FormatOptions.insertFinalNewline] is set,
 * - indentation, content and string literals are left untouched, so it is
 *   SAFE for every language (this is what unknown file types get).
 *
 * Registered with `isFallback = true` under the wildcard language "*".
 */
class WhitespaceFormatter(nowMs: () -> Long = System::currentTimeMillis) : AbstractCodeFormatter(nowMs) {

    override val id: String = "whitespace"
    override val supportedLanguages: Set<String> = setOf(WILDCARD)
    override val isFallback: Boolean = true

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline
    ): FormatResult {
        if (deadline.isExpired()) return timeoutResult(deadline.budgetMs)
        val formatted = applyFinalTouches(text, options)
        return FormatResult.Success(formatted, formatted != text, 0L)
    }

    companion object {
        /** Wildcard language id consumed by [FormatterRegistry] fallback routing. */
        const val WILDCARD = "*"
    }
}
