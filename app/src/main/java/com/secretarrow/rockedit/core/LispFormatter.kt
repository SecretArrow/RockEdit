package com.secretarrow.rockedit.core

/**
 * Structural formatter for parenthesis-based languages: Clarity (Stacks),
 * Michelson (Tezos), Clojure, ClojureScript, Scheme, Racket, Common Lisp.
 *
 * Block depth equals the number of unclosed `(`/`[` at the start of the
 * line; lines that open with `)` dedent via the minimum prefix rule.
 * Strings and comments are skipped by [LineScanner]; `)`-runs like `))))`
 * dedent the line and rebalance the counter.
 *
 * Strict mode rejects unbalanced parentheses with the offending line;
 * lenient mode clamps to depth 0 and continues. Depth is bounded
 * ([MAX_DEPTH] -> DepthSignal) and the deadline is polled per line.
 */
class LispFormatter(
    nowMs: () -> Long = System::currentTimeMillis,
) : AbstractCodeFormatter(nowMs) {
    override val id: String = "lisp"

    override val supportedLanguages: Set<String> =
        setOf(
            "clojure",
            "clojurescript",
            "scheme",
            "racket",
            "lisp",
            "clarity",
            "michelson",
        )

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
    ): FormatResult {
        val config = FormatterLanguages.lispConfigFor(language)
        val source = LineBreak.normalize(text, LineBreak.LF)
        val scanner =
            LineScanner(
                lineComments = config.lineComments,
                blockComments = config.blockComments,
                stringDelims = listOf('"'),
                multilineDelims = emptyList(),
                openChars = listOf('(', '['),
                closeChars = listOf(')', ']'),
            )
        val lines = source.split('\n')
        val out = StringBuilder(source.length + 16)
        var depth = 0
        var pendingError: FormatError? = null

        for ((index, raw) in lines.withIndex()) {
            if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
            val lineNo = index + 1
            val scan = scanner.scanLine(raw)
            val trimmed = raw.trim()

            if (trimmed.isEmpty()) {
                appendLine(out, index, lines.size, "")
                continue
            }
            if (scan.startsInsideBlockComment) {
                // Preserve block comment interiors (e.g. Clojure `#| |#`).
                val verbatim = if (options.trimTrailingWhitespace) raw.trimEnd(' ', '\t') else raw
                appendLine(out, index, lines.size, verbatim)
                continue
            }

            // Dedent exactly ONE level for a leading-closer line (`)` ,
            // `)))` — the first closer defines the line's level).
            val before = depth
            val renderDepth = before - (if (scan.leadingDedent > 0) 1 else 0)
            if (renderDepth < 0) {
                if (!options.lenient && pendingError == null) {
                    pendingError =
                        FormatError(
                            FormatErrorCode.PARSE_ERROR,
                            "unbalanced closing parenthesis: one ')' too many",
                            lineNo,
                            null,
                        )
                }
                depth = 0
            } else {
                depth = renderDepth
            }
            if (depth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
            val body = if (options.trimTrailingWhitespace) trimmed else raw.trimStart()
            appendLine(out, index, lines.size, pad(options, depth) + body)
            // The full net delta moves the running balance measured from
            // BEFORE the line; below zero means an extra closer
            // (e.g. `(foo))` at depth 0).
            val after = before + scan.netDelta
            if (after < 0) {
                if (!options.lenient && pendingError == null) {
                    pendingError =
                        FormatError(
                            FormatErrorCode.PARSE_ERROR,
                            "unbalanced closing parenthesis: one ')' too many",
                            lineNo,
                            null,
                        )
                }
                depth = 0
            } else {
                depth = after
            }
        }

        pendingError?.let {
            return if (options.lenient) finish(out, options, source) else FormatResult.Failure(it)
        }
        if (depth != 0 && !options.lenient) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "unclosed '(': $depth form(s) never close before end of file",
                    lines.size,
                    null,
                ),
            )
        }
        return finish(out, options, source)
    }

    private fun finish(
        out: StringBuilder,
        options: FormatOptions,
        source: String,
    ): FormatResult {
        val formatted = applyFinalTouches(out, options, trimTrailing = false)
        return FormatResult.Success(formatted, formatted != source, 0L)
    }

    private fun appendLine(
        out: StringBuilder,
        index: Int,
        total: Int,
        content: String,
    ) {
        out.append(content)
        if (index < total - 1) out.append('\n')
    }

    companion object {
        const val MAX_DEPTH = 512
    }
}
