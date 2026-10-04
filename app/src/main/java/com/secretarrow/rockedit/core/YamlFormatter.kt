package com.secretarrow.rockedit.core

/**
 * YAML formatter (conservative tier).
 *
 * YAML structure is defined by indentation, so a full re-indent would
 * require parsing the whole document. This formatter therefore performs
 * only transformations that are safe by construction:
 * - line breaks are normalized,
 * - trailing whitespace outside block scalars is trimmed,
 * - a final newline is inserted per [FormatOptions],
 * - tab characters in the INDENTATION of a non-block-scalar line are
 *   rejected (strict) with the offending line number — YAML forbids
 *   tabs there and no useful re-indent is possible; lenient mode treats
 *   each tab as [FormatOptions.indentSize] spaces.
 *
 * Block scalars (`|`, `>`, with optional indicators) are detected so their
 * content — where whitespace IS data — is preserved byte-for-byte.
 */
class YamlFormatter(nowMs: () -> Long = System::currentTimeMillis) : AbstractCodeFormatter(nowMs) {

    override val id: String = "yaml"

    override val supportedLanguages: Set<String> = setOf("yaml")

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline
    ): FormatResult {
        val source = LineBreak.normalize(text, LineBreak.LF)
        val lines = source.split('\n')
        val out = StringBuilder(source.length + 16)
        var blockScalarBaseIndent = -1
        var pendingError: FormatError? = null

        for ((index, raw) in lines.withIndex()) {
            if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
            val lineNo = index + 1
            val trimmed = raw.trim()

            // Inside a block scalar: whitespace is data, preserve verbatim.
            if (blockScalarBaseIndent >= 0) {
                if (trimmed.isEmpty()) {
                    appendLine(out, index, lines.size, raw)
                    continue
                }
                val indent = raw.length - raw.trimStart(' ').length
                if (indent > blockScalarBaseIndent) {
                    appendLine(out, index, lines.size, raw)
                    continue
                }
                blockScalarBaseIndent = -1
            }

            if (trimmed.isEmpty()) {
                appendLine(out, index, lines.size, "")
                continue
            }

            val indent = raw.length - raw.trimStart(' ').length
            if (raw.contains('\t') && raw.take(indent + 1).contains('\t') &&
                pendingError == null && !options.lenient
            ) {
                pendingError = FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "tab character in indentation: YAML forbids tabs where indentation is significant",
                    lineNo,
                    null
                )
            }

            // A block scalar opener: key line ending in | or > plus optional
            // indicators (-, +, digit) before an optional trailing comment.
            if (BLOCK_SCALAR_REGEX.containsMatchIn(trimmed)) {
                blockScalarBaseIndent = indent
            }

            val content = if (raw.take(indent + 1).contains('\t')) {
                // Lenient: convert leading tabs to spaces so structure survives.
                val leading = raw.takeWhile { it == ' ' || it == '\t' }
                val width = leading.count { it == '\t' } * options.indentSize + leading.count { it == ' ' }
                " ".repeat(width) + trimmed
            } else {
                raw.trimEnd(' ', '\t')
            }
            appendLine(out, index, lines.size, content)
        }

        pendingError?.let { return FormatResult.Failure(it) }
        val formatted = applyFinalTouches(out, options, trimTrailing = false)
        return FormatResult.Success(formatted, formatted != source, 0L)
    }

    private fun appendLine(out: StringBuilder, index: Int, total: Int, content: String) {
        out.append(content)
        if (index < total - 1) out.append('\n')
    }

    companion object {
        /**
         * Matches `key: |`, `key: >-`, `key: |2`, `- |+`, including an
         * optional trailing comment. Anchored to the `|`/`>` token so plain
         * text containing a pipe character does not match.
         */
        val BLOCK_SCALAR_REGEX = Regex("""[|>][+-]?\d*(\s+#.*)?$""")
    }
}
