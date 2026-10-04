package com.secretarrow.rockedit.core

/**
 * CSS formatter — a single-pass state machine.
 *
 * Scope (v1, deliberately honest):
 * - normalizes block structure: selectors, declarations, indentation,
 * - preserves comments (pretty mode) or strips them (minify mode),
 * - string literals and url(...) tokens are copied VERBATIM (a ';' or '{'
 *   inside a string or a data URI can never corrupt the structure),
 * - unbalanced braces / unterminated strings / unterminated comments are
 *   reported as PARSE_ERROR with the responsible line number,
 * - it does NOT validate property values: CSS is fault-tolerant by design
 *   and the formatter only ever touches whitespace and line structure.
 */
class CssFormatter(nowMs: () -> Long = System::currentTimeMillis) : AbstractCodeFormatter(nowMs) {

    override val id: String = "css"
    override val supportedLanguages: Set<String> = setOf("css")

    private enum class State { CODE, IN_STRING, IN_COMMENT }

    private fun StringBuilder.endsWithSpace(): Boolean =
        isNotEmpty() && this[length - 1] == ' '

    private fun StringBuilder.endsWithNewline(): Boolean =
        isNotEmpty() && this[length - 1] == '\n'

    override fun formatValidated(text: String, options: FormatOptions, deadline: Deadline): FormatResult {
        val out = StringBuilder(text.length + 32)
        var state = State.CODE
        var braceDepth = 0
        var parenDepth = 0
        var line = 1
        var stringStartLine = 1
        var commentStartLine = 1
        var parenStartLine = 1
        var quote = '"'
        var atLineStart = true
        val braceLines = ArrayDeque<Int>()

        fun emitPad() {
            if (atLineStart) {
                out.append(pad(options, braceDepth.coerceAtLeast(0)))
                atLineStart = false
            }
        }

        fun prettyNewline() {
            if (!options.minify) {
                out.append('\n')
                atLineStart = true
            }
        }

        var i = 0
        while (i < text.length) {
            if ((i and 0x3FF) == 0 && deadline.isExpired()) return timeoutResult(deadline.budgetMs)
            val c = text[i]
            when (state) {
                State.IN_COMMENT -> {
                    if (c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                        if (!options.minify) out.append("*/")
                        state = State.CODE
                        i += 2
                    } else {
                        if (c == '\n') line++
                        if (!options.minify) {
                            if (c == '\n') {
                                out.append('\n')
                                atLineStart = true
                            } else {
                                emitPad()
                                out.append(c)
                            }
                        }
                        i++
                    }
                }
                State.IN_STRING -> {
                    if (c == '\\' && i + 1 < text.length) {
                        emitPad()
                        out.append(c).append(text[i + 1])
                        i += 2
                    } else if (c == quote) {
                        emitPad()
                        out.append(c)
                        state = State.CODE
                        i++
                    } else {
                        if (c == '\n') {
                            line++
                            out.append('\n')
                            atLineStart = true
                        } else {
                            emitPad()
                            out.append(c)
                        }
                        i++
                    }
                }
                State.CODE -> when {
                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                        if (!options.minify) {
                            emitPad()
                            out.append("/*")
                        }
                        state = State.IN_COMMENT
                        commentStartLine = line
                        i += 2
                    }
                    c == '"' || c == '\'' -> {
                        emitPad()
                        out.append(c)
                        state = State.IN_STRING
                        quote = c
                        stringStartLine = line
                        i++
                    }
                    c == '\n' -> {
                        line++
                        if (parenDepth == 0) {
                            // Minify drops structural whitespace entirely.
                            if (options.minify) {
                                // skip
                            } else if (!atLineStart && !out.endsWithSpace() && !out.endsWithNewline()) {
                                out.append(' ') // collapse whitespace runs to a single space
                            }
                        } else {
                            out.append(c) // inside url(...): copy verbatim
                            atLineStart = false
                        }
                        i++
                    }
                    c == ' ' || c == '\t' -> {
                        if (parenDepth == 0) {
                            if (options.minify) {
                                // skip
                            } else if (!atLineStart && !out.endsWithSpace() && !out.endsWithNewline()) {
                                out.append(' ')
                            }
                        } else {
                            out.append(c)
                            atLineStart = false
                        }
                        i++
                    }
                    c == '{' -> {
                        emitPad()
                        braceDepth++
                        braceLines.addLast(line)
                        if (options.minify) {
                            if (out.endsWithSpace()) out.setLength(out.length - 1)
                            out.append('{')
                        } else {
                            if (!atLineStart && !out.endsWithSpace()) out.append(' ')
                            out.append('{')
                            prettyNewline()
                        }
                        i++
                    }
                    c == '}' -> {
                        if (braceDepth == 0) {
                            return FormatResult.Failure(
                                FormatError(
                                    FormatErrorCode.PARSE_ERROR,
                                    "'}' without a matching '{'",
                                    line,
                                    null
                                )
                            )
                        }
                        braceDepth--
                        braceLines.removeLast()
                        if (options.minify) {
                            if (out.endsWithSpace()) out.setLength(out.length - 1)
                            out.append('}')
                        } else {
                            if (!out.endsWithNewline()) prettyNewline()
                            out.append(pad(options, braceDepth.coerceAtLeast(0))).append('}')
                            prettyNewline()
                        }
                        i++
                    }
                    c == ';' -> {
                        if (parenDepth == 0) {
                            emitPad()
                            out.append(';')
                            prettyNewline()
                        } else {
                            // ';' inside url(data:...;base64,...) is part of the
                            // token: copy verbatim, never break the line.
                            emitPad()
                            out.append(';')
                        }
                        i++
                    }
                    c == ':' && braceDepth > 0 && parenDepth == 0 -> {
                        // Inside a declaration block, normalize "color:red" and
                        // "color : red" to "color: red". Colons in selectors
                        // (:hover) and @media conditions (inside parens) are
                        // copied verbatim.
                        emitPad()
                        out.append(':')
                        if (!options.minify) out.append(' ')
                        i++
                    }
                    c == '(' -> {
                        if (parenDepth == 0) parenStartLine = line
                        parenDepth++
                        emitPad()
                        out.append(c)
                        i++
                    }
                    c == ')' -> {
                        if (parenDepth > 0) parenDepth-- // stray ')' stays lenient, not fatal
                        emitPad()
                        out.append(c)
                        i++
                    }
                    else -> {
                        emitPad()
                        out.append(c)
                        i++
                    }
                }
            }
        }

        // EOF — every remaining state has an explicit, located diagnosis.
        return when (state) {
            State.IN_COMMENT -> FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "comment opened at line $commentStartLine is never closed",
                    commentStartLine,
                    null
                )
            )
            State.IN_STRING -> FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "string opened at line $stringStartLine is never closed",
                    stringStartLine,
                    null
                )
            )
            State.CODE -> when {
                parenDepth > 0 -> FormatResult.Failure(
                    FormatError(
                        FormatErrorCode.PARSE_ERROR,
                        "'(' opened at line $parenStartLine is never closed",
                        parenStartLine,
                        null
                    )
                )
                braceDepth > 0 -> {
                    val openerLine = braceLines.firstOrNull() ?: line
                    FormatResult.Failure(
                        FormatError(
                            FormatErrorCode.PARSE_ERROR,
                            "block '{' opened at line $openerLine is never closed",
                            openerLine,
                            null
                        )
                    )
                }
                else -> {
                    val formatted = applyFinalTouches(out, options)
                    FormatResult.Success(formatted, formatted != text, 0L)
                }
            }
        }
    }
}
