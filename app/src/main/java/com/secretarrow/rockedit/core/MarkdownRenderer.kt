package com.secretarrow.rockedit.core

/**
 * Small, dependency-free Markdown-to-HTML renderer for the live preview.
 * Supports the common subset: headings, paragraphs, bold/italic/code spans,
 * fenced code blocks, links, images, ordered/unordered lists, block quotes
 * and horizontal rules. Deterministic and side-effect free (pure JVM).
 */
object MarkdownRenderer {

    /**
     * Renders [markdown] into a full HTML document. [dark] switches the
     * embedded stylesheet to a dark palette.
     */
    fun renderDocument(markdown: String, dark: Boolean, title: String = "Preview"): String {
        val style = if (dark) DARK_STYLE else LIGHT_STYLE
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
            append("<title>").append(escapeHtml(title)).append("</title><style>").append(style)
            append("</style></head><body>")
            append(render(markdown))
            append("</body></html>")
        }
    }

    /** Renders the Markdown body fragment only (no document wrapper). */
    fun render(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    out.append("<pre><code>").append(escapeHtml(code.toString().trimEnd('\n')))
                        .append("</code></pre>\n")
                    i++ // skip the closing fence
                }
                line.isNotBlank() && isHeading(line) -> {
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text = line.dropWhile { it == '#' }.trim()
                    out.append("<h$level>").append(inline(text)).append("</h$level>\n")
                    i++
                }
                isHorizontalRule(line) -> {
                    out.append("<hr/>\n")
                    i++
                }
                line.startsWith(">") -> {
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].startsWith(">")) {
                        quote.append(lines[i].removePrefix(">").removePrefix(" ").trim())
                            .append('\n')
                        i++
                    }
                    out.append("<blockquote>").append(inline(quote.toString().trim()))
                        .append("</blockquote>\n")
                }
                isListItem(line) -> {
                    val ordered = line.trimStart().startsWithFirstDigitDot()
                    val tag = if (ordered) "ol" else "ul"
                    out.append("<$tag>")
                    while (i < lines.size && isListItem(lines[i])) {
                        val item = lines[i].trimStart()
                            .dropWhile { it.isDigit() || it == '.' || it == '-' || it == '*' || it == '+' }
                            .removePrefix(" ").trim()
                        out.append("<li>").append(inline(item)).append("</li>")
                        i++
                    }
                    out.append("</$tag>\n")
                }
                line.isBlank() -> i++
                else -> {
                    val paragraph = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !isHeading(lines[i]) && !isListItem(lines[i]) &&
                        !lines[i].startsWith(">") && !lines[i].startsWith("```") &&
                        !isHorizontalRule(lines[i])
                    ) {
                        paragraph.append(lines[i].trim()).append('\n')
                        i++
                    }
                    val rendered = inline(paragraph.toString().trim()).replace("\n", "<br/>\n")
                    out.append("<p>").append(rendered).append("</p>\n")
                }
            }
        }
        return out.toString()
    }

    /** Applies inline formatting to an already-HTML-escaped fragment. */
    fun inline(text: String): String {
        val escaped = escapeHtml(text)
        // Ordered application: code spans first protect their content.
        val parts = escaped.split(Regex("(`[^`]+`)"))
        val built = parts.joinToString("") { part ->
            if (part.startsWith("`") && part.endsWith("`") && part.length >= 2) {
                "<code>" + part.substring(1, part.length - 1) + "</code>"
            } else {
                applyFormatting(part)
            }
        }
        return built
    }

    private fun applyFormatting(fragment: String): String {
        var result = fragment
        // Bold: **text** or __text__
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        result = result.replace(Regex("__(.+?)__"), "<strong>$1</strong>")
        // Italic: *text* or _text_
        result = result.replace(Regex("(?<!\\*)\\*([^*\\s][^*]*?)\\*(?!\\*)"), "<em>$1</em>")
        result = result.replace(Regex("(?<!_)_([^_\\s][^_]*?)_(?!_)"), "<em>$1</em>")
        // Images before links: ![alt](url)
        result = result.replace(Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            "<img alt=\"$alt\" src=\"$url\"/>"
        }
        // Links: [text](url)
        result = result.replace(Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")) { match ->
            val label = match.groupValues[1]
            val url = match.groupValues[2]
            "<a href=\"$url\">$label</a>"
        }
        return result
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun isHeading(line: String): Boolean {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return false
        val hashes = trimmed.takeWhile { it == '#' }
        return hashes.length in 1..6 && trimmed.getOrNull(hashes.length) == ' '
    }

    private fun isHorizontalRule(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= 3 && (
            trimmed.all { it == '-' || it == ' ' } ||
                trimmed.all { it == '*' || it == ' ' } ||
                trimmed.all { it == '_' || it == ' ' }
            ) && trimmed.any { it == '-' || it == '*' || it == '_' }
    }

    private fun isListItem(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
            trimmed.startsWith("+ ") || trimmed.startsWithFirstDigitDot()
    }

    private fun String.startsWithFirstDigitDot(): Boolean {
        val trimmed = trimStart()
        if (trimmed.length < 2) return false
        return trimmed[0].isDigit() && trimmed[1] == '.'
    }

    private const val LIGHT_STYLE =
        "body{font-family:sans-serif;margin:16px;line-height:1.5;color:#1f2937;background:#ffffff}" +
            "pre{background:#f1f5f9;padding:10px;border-radius:6px;overflow-x:auto}" +
            "code{font-family:monospace;background:#f1f5f9;padding:1px 4px;border-radius:3px}" +
            "pre code{background:transparent;padding:0}" +
            "blockquote{border-left:4px solid #cbd5e1;margin:8px 0;padding:4px 12px;color:#475569}" +
            "img{max-width:100%}"

    private const val DARK_STYLE =
        "body{font-family:sans-serif;margin:16px;line-height:1.5;color:#e2e8f0;background:#0f172a}" +
            "pre{background:#1e293b;padding:10px;border-radius:6px;overflow-x:auto}" +
            "code{font-family:monospace;background:#1e293b;padding:1px 4px;border-radius:3px}" +
            "pre code{background:transparent;padding:0}" +
            "blockquote{border-left:4px solid #475569;margin:8px 0;padding:4px 12px;color:#94a3b8}" +
            "img{max-width:100%}"
}
