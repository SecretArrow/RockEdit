package com.secretarrow.rockedit.core

/**
 * Structural formatter for indentation-based and keyword-block languages.
 *
 * Covers:
 * - REINDENT style: Python and Vyper. The user's indentation IS the block
 *   structure, so it is detected (GCD of positive indent deltas) and
 *   rescaled to the requested width — the structure itself is never
 *   re-derived, which guarantees working code can never be broken.
 * - KEYWORD style: Ruby, Lua, Elixir, Julia, LaTeX. Blocks open/close with
 *   anchored keywords (`def`/`end`, `\begin{...}`/`\end{...}`), so depth
 *   is re-derived line by line.
 *
 * Defensive behaviour:
 * - docstring / multiline-string interiors are preserved verbatim
 *   (trailing whitespace there can be significant),
 * - tabs used for indentation are rejected with a line number in strict
 *   mode and treated as [FormatOptions.indentSize] spaces in lenient mode,
 * - code lines whose indentation is not a multiple of the detected unit
 *   are rejected in strict mode and kept as-is in lenient mode,
 * - keyword style rejects unbalanced `end`/`else` at depth 0 (strict) and
 *   clamps in lenient mode,
 * - depth is bounded ([MAX_DEPTH] -> DepthSignal) and the deadline is
 *   polled per line,
 * - minify is deliberately IGNORED: removing indentation in these
 *   languages changes program semantics.
 *
 * Documented limitations (assumptions): raw strings ending in an odd
 * backslash (r"\") can false-trigger the string scanner; Ruby heredoc
 * bodies are not tracked.
 */
class IndentFormatter(
    nowMs: () -> Long = System::currentTimeMillis,
) : AbstractCodeFormatter(nowMs) {
    override val id: String = "indent"

    override val supportedLanguages: Set<String> =
        setOf(
            "python",
            "vyper",
            "ruby",
            "lua",
            "elixir",
            "julia",
            "latex",
        )

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
    ): FormatResult {
        val config = FormatterLanguages.indentConfigFor(language)
        return when (config.style) {
            FormatterLanguages.IndentLanguage.Style.REINDENT -> formatReindent(config, text, options, deadline)
            FormatterLanguages.IndentLanguage.Style.KEYWORD -> formatKeyword(config, text, options, deadline)
        }
    }

    // ------------------------------------------------------------ REINDENT

    private fun formatReindent(
        config: FormatterLanguages.IndentLanguage,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
    ): FormatResult {
        val source = LineBreak.normalize(text, LineBreak.LF)
        val scanner =
            LineScanner(
                lineComments = config.lineComments,
                blockComments = emptyList(),
                stringDelims = config.stringDelims,
                multilineDelims = config.multilineStringDelims,
                openChars = listOf('(', '[', '{'),
                closeChars = listOf(')', ']', '}'),
            )
        val lines = source.split('\n')
        val widths = IntArray(lines.size)
        val kinds = Array(lines.size) { LineKind.BLANK }
        var bracketDepth = 0
        var pendingError: FormatError? = null

        // PASS 1 — classify lines and collect indentation widths.
        for (index in lines.indices) {
            if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
            val raw = lines[index]
            val lineNo = index + 1
            val scan = scanner.scanLine(raw)
            val isContinuation = bracketDepth > 0
            bracketDepth = (bracketDepth + scan.netDelta).coerceAtLeast(0)
            if (bracketDepth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
            when {
                scan.startsInsideMultiline -> kinds[index] = LineKind.VERBATIM
                raw.isBlank() -> kinds[index] = LineKind.BLANK
                else -> {
                    val tabbed = raw.startsWith('\t') || raw.startsWith(" \t")
                    if (tabbed && pendingError == null && !options.lenient) {
                        pendingError =
                            FormatError(
                                FormatErrorCode.PARSE_ERROR,
                                "tab character used for indentation: Python requires consistent spaces",
                                lineNo,
                                null,
                            )
                    }
                    // Tabs count as indentSize spaces so lenient mode can
                    // rescale them; middle-of-line tabs never count.
                    val leading = raw.takeWhile { it == ' ' || it == '\t' }
                    widths[index] = leading.count { it == '\t' } * options.indentSize +
                        leading.count { it == ' ' }
                    kinds[index] =
                        if (isContinuation || !scan.hasCode) {
                            LineKind.SOFT
                        } else {
                            LineKind.CODE
                        }
                }
            }
        }

        // PASS 2 — detect the indentation unit (GCD of positive deltas).
        val unit = detectIndentUnit(lines, kinds, widths)
        if (unit <= 0) {
            // Nothing to rescale — but trailing whitespace is still cleaned
            // (never inside docstrings) and a reported validation error
            // (e.g. a tab used for indentation) must still surface.
            pendingError?.let { return FormatResult.Failure(it) }
            return formatWhitespaceOnly(lines, kinds, options)
        }

        // PASS 3 — rescale; CODE lines must match the unit exactly (strict).
        val out = StringBuilder(source.length + 16)
        for (index in lines.indices) {
            if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
            val raw = lines[index]
            when (kinds[index]) {
                LineKind.BLANK -> appendLine(out, index, lines.size, "")
                LineKind.VERBATIM -> appendLine(out, index, lines.size, raw)
                else -> {
                    val remainder = widths[index] % unit
                    if (remainder != 0 && kinds[index] == LineKind.CODE && pendingError == null && !options.lenient) {
                        pendingError =
                            FormatError(
                                FormatErrorCode.PARSE_ERROR,
                                "inconsistent indentation: ${widths[index]} spaces is not a multiple of the file's $unit-space unit",
                                index + 1,
                                null,
                            )
                    }
                    // CODE lines on the unit grid are rescaled exactly;
                    // anything off-grid (aligned continuations, odd
                    // comments) keeps its original width so the author's
                    // alignment intent survives.
                    val newWidth =
                        if (remainder == 0) {
                            widths[index] / unit * options.indentSize
                        } else {
                            widths[index]
                        }
                    val body = if (options.trimTrailingWhitespace) raw.trim() else raw.trimStart()
                    appendLine(out, index, lines.size, " ".repeat(newWidth) + body)
                }
            }
        }
        pendingError?.let { return FormatResult.Failure(it) }
        val formatted = applyFinalTouches(out, options, trimTrailing = false)
        return FormatResult.Success(formatted, formatted != source, 0L)
    }

    private fun detectIndentUnit(
        lines: List<String>,
        kinds: Array<LineKind>,
        widths: IntArray,
    ): Int {
        // Only CODE lines participate: comment-only and continuation lines
        // may use arbitrary widths without saying anything about the unit.
        var gcd = 0
        var previous = -1
        for (index in lines.indices) {
            if (kinds[index] == LineKind.CODE) {
                if (previous >= 0) {
                    val delta = widths[index] - previous
                    if (delta > 0) gcd = gcdOf(gcd, delta)
                }
                previous = widths[index]
            }
        }
        return gcd
    }

    private fun gcdOf(
        a: Int,
        b: Int,
    ): Int = if (b == 0) a else gcdOf(b, a % b)

    // ------------------------------------------------------------- KEYWORD

    private fun formatKeyword(
        config: FormatterLanguages.IndentLanguage,
        text: String,
        options: FormatOptions,
        deadline: Deadline,
    ): FormatResult {
        val source = LineBreak.normalize(text, LineBreak.LF)
        val scanner =
            LineScanner(
                lineComments = config.lineComments,
                blockComments = emptyList(),
                stringDelims = config.stringDelims,
                multilineDelims = config.multilineStringDelims,
                openChars = emptyList(),
                closeChars = emptyList(),
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
            if (scan.startsInsideMultiline) {
                appendLine(out, index, lines.size, raw)
                continue
            }

            val isCloser = config.closers.any { it.containsMatchIn(trimmed) }
            val isDedent = config.dedentStarts.any { it.containsMatchIn(trimmed) }
            val guarded = config.openerGuard?.containsMatchIn(trimmed) == true
            val isOpener = !guarded && !isCloser && config.openers.any { it.containsMatchIn(trimmed) }

            var renderDepth = depth
            if (isCloser || isDedent) renderDepth -= 1
            if (renderDepth < 0) {
                if (!options.lenient && pendingError == null) {
                    pendingError =
                        FormatError(
                            FormatErrorCode.PARSE_ERROR,
                            "unbalanced block keyword ('$trimmed' closes a block that is not open)",
                            lineNo,
                            null,
                        )
                }
                renderDepth = 0
            }
            if (renderDepth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
            val body = if (options.trimTrailingWhitespace) trimmed else raw.trimStart()
            appendLine(out, index, lines.size, pad(options, renderDepth) + body)

            when {
                isCloser -> depth = (depth - 1).coerceAtLeast(0)
                isOpener -> {
                    depth += 1
                    if (depth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
                }
            }
        }

        pendingError?.let {
            return if (options.lenient) {
                val formatted = applyFinalTouches(out, options, trimTrailing = false)
                FormatResult.Success(formatted, formatted != source, 0L)
            } else {
                FormatResult.Failure(it)
            }
        }
        if (depth != 0 && !options.lenient) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "unclosed block: $depth block keyword(s) never close before end of file",
                    lines.size,
                    null,
                ),
            )
        }
        val formatted = applyFinalTouches(out, options, trimTrailing = false)
        return FormatResult.Success(formatted, formatted != source, 0L)
    }

    // -------------------------------------------------------------- shared

    private fun formatWhitespaceOnly(
        lines: List<String>,
        kinds: Array<LineKind>,
        options: FormatOptions,
    ): FormatResult {
        val out = StringBuilder()
        for (index in lines.indices) {
            val content =
                when {
                    kinds[index] == LineKind.VERBATIM -> lines[index]
                    options.trimTrailingWhitespace -> lines[index].trimEnd(' ', '\t')
                    else -> lines[index]
                }
            appendLine(out, index, lines.size, content)
        }
        val formatted = applyFinalTouches(out, options, trimTrailing = false)
        return FormatResult.Success(formatted, formatted != lines.joinToString("\n"), 0L)
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

    private enum class LineKind { BLANK, CODE, SOFT, VERBATIM }

    companion object {
        const val MAX_DEPTH = 512
    }
}
