package com.secretarrow.rockedit.core

/**
 * Structural formatter for brace languages (C-family and smart contracts).
 *
 * Covers: Kotlin, Java, C, C++, C#, Objective-C, Swift, Dart, JavaScript,
 * TypeScript, Go, Rust (also ink!/CosmWasm/Soroban via aliases), PHP,
 * Scala, Groovy, Zig, R, PowerShell, Protobuf, GraphQL, Solidity, Move,
 * Cairo, Cadence, Motoko, Aiken, Leo, Fe.
 *
 * Algorithm (single pass, O(n)):
 * - [LineScanner] skips strings/char literals/comments, so braces inside
 *   them never affect depth; multiline raw strings and block comments are
 *   reproduced verbatim (interior trailing whitespace is trimmed because
 *   it is never semantically significant in these languages),
 * - each line renders at (depth + minPrefix) so lines that open with
 *   `}` / `});` dedent exactly one level,
 * - strict mode rejects unbalanced braces and unterminated strings with
 *   the offending line number; lenient mode clamps and continues,
 * - depth is bounded ([MAX_DEPTH], surfaced as DepthSignal -> PARSE_ERROR)
 *   and the deadline is polled per line, so pathological input can neither
 *   overflow the stack nor hang the editor.
 *
 * Documented limitations (assumptions):
 * - regex literals containing quotes (e.g. `/it's/` in JavaScript) can be
 *   mistaken for strings; strict mode reports the line, lenient proceeds,
 * - case labels indent like the switch body (no special case handling).
 */
class BraceFormatter(nowMs: () -> Long = System::currentTimeMillis) : AbstractCodeFormatter(nowMs) {

    override val id: String = "brace"

    override val supportedLanguages: Set<String> = setOf(
        "kotlin", "java", "c", "cpp", "csharp", "objc", "swift", "dart",
        "javascript", "typescript", "go", "rust", "php", "scala", "groovy",
        "zig", "r", "powershell", "protobuf", "graphql",
        "solidity", "move", "cairo", "cadence", "motoko", "aiken", "leo", "fe",
        "sol", "cosmwasm", "ink", "soroban"
    )

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline
    ): FormatResult {
        val config = FormatterLanguages.braceConfigFor(language)
        // Internal pass always works on LF; applyFinalTouches converts back.
        val source = LineBreak.normalize(text, LineBreak.LF)
        val scanner = LineScanner(
            lineComments = config.lineComments,
            blockComments = config.blockComments,
            stringDelims = config.stringDelims,
            multilineDelims = config.multilineStringDelims,
            openChars = listOf('{'),
            closeChars = listOf('}')
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
            val isVerbatim = scan.startsInsideBlockComment || scan.startsInsideMultiline

            if (trimmed.isEmpty()) {
                appendLine(out, index, lines.size, "")
                continue
            }
            if (isVerbatim) {
                val verbatim = if (options.trimTrailingWhitespace) raw.trimEnd(' ', '\t') else raw
                appendLine(out, index, lines.size, verbatim)
                continue
            }

            if (scan.unterminatedString && pendingError == null) {
                pendingError = FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "unterminated string literal: a closing quote is missing " +
                        "(regex literals containing quotes are a known limitation)",
                    lineNo,
                    null
                )
            }
            // Dedent exactly ONE level for a leading-closer line (`}` ,
            // `});` , `} else {` , `}}` — the first closer defines the
            // line's level; further closers on the same line stay there).
            val before = depth
            val renderDepth = before - (if (scan.leadingDedent > 0) 1 else 0)
            if (renderDepth < 0) {
                if (!options.lenient && pendingError == null) {
                    pendingError = FormatError(
                        FormatErrorCode.PARSE_ERROR,
                        "unbalanced closing brace: one '}' too many",
                        lineNo,
                        null
                    )
                }
                depth = 0
            } else {
                depth = renderDepth
            }
            if (depth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
            val isPreprocessor = config.hashPreprocessor && trimmed.startsWith("#")
            val pad = if (options.minify || isPreprocessor) "" else pad(options, depth)
            val body = if (options.trimTrailingWhitespace) trimmed else raw.trimStart()
            appendLine(out, index, lines.size, pad + body)
            // The full net delta (all closers minus openers on the line)
            // moves the running balance measured from BEFORE the line;
            // below zero means an extra closer (e.g. `x }` at depth 0).
            val after = before + scan.netDelta
            if (after < 0) {
                if (!options.lenient && pendingError == null) {
                    pendingError = FormatError(
                        FormatErrorCode.PARSE_ERROR,
                        "unbalanced closing brace: one '}' too many",
                        lineNo,
                        null
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
                    "unclosed '{': $depth block(s) never close before end of file",
                    lines.size,
                    null
                )
            )
        }
        return finish(out, options, source)
    }

    /** Shared tail: final touches and changed flag. Never throws. */
    private fun finish(out: StringBuilder, options: FormatOptions, source: String): FormatResult {
        val formatted = applyFinalTouches(out, options, trimTrailing = false)
        return FormatResult.Success(formatted, formatted != source, 0L)
    }

    private fun appendLine(out: StringBuilder, index: Int, total: Int, content: String) {
        out.append(content)
        if (index < total - 1) out.append('\n')
    }

    companion object {
        const val MAX_DEPTH = 512
    }
}
