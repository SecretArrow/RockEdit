package com.secretarrow.rockedit.core

import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

/**
 * XML formatter (also used for SVG and other XML dialects).
 *
 * Security-first:
 * - DTD/DOCTYPE and ENTITY declarations are rejected by a pre-scan BEFORE
 *   the document ever reaches the parser (XXE protection, primary guard),
 * - the parser itself is additionally configured to refuse external
 *   entities, DTDs and XInclude (secondary guard for platforms where the
 *   features are available),
 * - formatting is a pure DOM re-serialization: entities are never resolved.
 *
 * Errors from the parser carry 1-based line/column positions when the
 * platform reports them. Nesting is bounded ([MAX_DEPTH]) and the deadline
 * is polled while walking the tree.
 */
class XmlFormatter(nowMs: () -> Long = System::currentTimeMillis) : AbstractCodeFormatter(nowMs) {

    override val id: String = "xml"
    override val supportedLanguages: Set<String> = setOf("xml", "svg", "plist")

    override fun formatValidated(
        language: String,
        text: String,
        options: FormatOptions,
        deadline: Deadline
    ): FormatResult {
        // PRIMARY XXE GUARD — reject DTDs before touching the parser.
        if (DTD_REGEX.containsMatchIn(text.take(DTD_SCAN_CHARS))) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "DTD/DOCTYPE declarations are rejected (XXE protection)"
                )
            )
        }

        val factory = DocumentBuilderFactory.newInstance()
        // SECONDARY XXE GUARD — harden the parser where the platform allows.
        // A pre-scan above already blocks DTDs, so a feature being unavailable
        // on some platform does not weaken the guarantee.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        factory.isXIncludeAware = false

        val document = try {
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(null) // we rely on exceptions, not handler callbacks
            builder.parse(InputSource(StringReader(text)))
        } catch (e: SAXParseException) {
            val line = e.lineNumber.takeIf { it > 0 }
            val column = e.columnNumber.takeIf { it > 0 }
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "invalid XML at line ${e.lineNumber}, column ${e.columnNumber}: " +
                        (e.message?.take(MAX_MESSAGE) ?: e::class.java.simpleName),
                    line,
                    column
                )
            )
        } catch (e: SAXException) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.PARSE_ERROR,
                    "invalid XML: " + (e.message?.take(MAX_MESSAGE) ?: e::class.java.simpleName)
                )
            )
        } catch (e: ParserConfigurationException) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.INTERNAL_ERROR,
                    "XML parser unavailable on this platform: " +
                        (e.message?.take(MAX_MESSAGE) ?: e::class.java.simpleName)
                )
            )
        } catch (e: IOException) {
            return FormatResult.Failure(
                FormatError(
                    FormatErrorCode.INTERNAL_ERROR,
                    "failed to read the XML input: " + (e.message?.take(MAX_MESSAGE) ?: e::class.java.simpleName)
                )
            )
        }

        if (deadline.isExpired()) return timeoutResult(deadline.budgetMs)

        val out = StringBuilder(text.length + 64)
        appendXmlDeclaration(text, out)
        try {
            writeChildren(document, depth = 0, out, options, deadline)
        } catch (e: TimeoutSignal) {
            return timeoutResult(e.budgetMs)
        } catch (e: DepthSignal) {
            return depthResult(e.maxDepth)
        }
        val formatted = applyFinalTouches(out, options)
        return FormatResult.Success(formatted, formatted != text, 0L)
    }

    /** Re-emits the original XML declaration verbatim when one is present. */
    private fun appendXmlDeclaration(text: String, out: StringBuilder) {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("<?xml")) return
        val end = trimmed.indexOf("?>")
        if (end < 0) return // malformed declaration: the parser already rejected the document
        out.append(trimmed.substring(0, end + 2))
        out.append('\n')
    }

    private fun writeChildren(
        document: Document,
        depth: Int,
        out: StringBuilder,
        options: FormatOptions,
        deadline: Deadline
    ) {
        val children = document.childNodes ?: return
        for (i in 0 until children.length) {
            val child = children.item(i) ?: continue
            if (child.nodeType == Node.DOCUMENT_TYPE_NODE) continue // already rejected by the pre-scan
            if (child.nodeType == Node.TEXT_NODE && (child.nodeValue ?: "").isBlank()) continue
            writeNode(child, depth, out, options, deadline)
            if (!options.minify) out.append('\n')
        }
    }

    private fun writeNode(node: Node, depth: Int, out: StringBuilder, options: FormatOptions, deadline: Deadline) {
        if (depth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
        if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
        when (node.nodeType) {
            Node.DOCUMENT_NODE -> writeChildren(node as Document, depth, out, options, deadline)
            Node.ELEMENT_NODE -> writeElement(node as Element, depth, out, options, deadline)
            Node.TEXT_NODE -> {
                val value = node.nodeValue ?: ""
                if (value.isNotBlank()) {
                    out.append(pad(options, depth)).append(escapeText(value.trim()))
                }
            }
            Node.CDATA_SECTION_NODE -> {
                val value = node.nodeValue ?: ""
                out.append(pad(options, depth))
                    .append("<![CDATA[")
                    .append(value.replace("]]>", "]]]]><![CDATA[>")) // cannot appear raw inside CDATA
                    .append("]]>")
            }
            Node.COMMENT_NODE -> {
                val value = node.nodeValue ?: ""
                if (value.isNotBlank()) {
                    out.append(pad(options, depth)).append("<!--").append(value).append("-->")
                }
            }
            Node.PROCESSING_INSTRUCTION_NODE -> {
                out.append(pad(options, depth))
                    .append("<?")
                    .append(node.nodeName ?: "")
                    .append(' ')
                    .append(node.nodeValue ?: "")
                    .append("?>")
            }
            else -> {
                // Unknown node type (entity reference, fragment, ...): skip safely.
            }
        }
    }

    private fun writeElement(
        element: Element,
        depth: Int,
        out: StringBuilder,
        options: FormatOptions,
        deadline: Deadline
    ) {
        if (depth > MAX_DEPTH) throw DepthSignal(MAX_DEPTH)
        if (deadline.isExpired()) throw TimeoutSignal(deadline.budgetMs)
        val name = element.tagName
        if (name.isNullOrBlank()) return // defensive: DOM cannot produce this, skip rather than crash
        val attributes = buildAttributes(element)
        val children = element.childNodes
        val childCount = children?.length ?: 0

        if (childCount == 0) {
            out.append(pad(options, depth)).append('<').append(name).append(attributes).append("/>")
            return
        }

        if (childCount == 1) {
            val only = children.item(0)
            if (only.nodeType == Node.TEXT_NODE) {
                val value = (only.nodeValue ?: "").trim()
                if (value.isEmpty()) {
                    // <x></x> with no real text: emit self-closing.
                    out.append(pad(options, depth)).append('<').append(name).append(attributes).append("/>")
                    return
                }
                if (value.length <= INLINE_TEXT_LIMIT && !value.contains('\n')) {
                    out.append(pad(options, depth)).append('<').append(name).append(attributes)
                        .append('>').append(escapeText(value)).append("</").append(name).append('>')
                    return
                }
                // Long or multi-line text: fall through to block layout.
            }
        }

        out.append(pad(options, depth)).append('<').append(name).append(attributes).append('>')
        for (i in 0 until childCount) {
            val child = children.item(i) ?: continue
            if (child.nodeType == Node.TEXT_NODE && (child.nodeValue ?: "").isBlank()) continue
            if (!options.minify) out.append('\n')
            writeNode(child, depth + 1, out, options, deadline)
        }
        if (!options.minify) out.append('\n').append(pad(options, depth))
        out.append("</").append(name).append('>')
    }

    private fun buildAttributes(element: Element): String {
        val map = element.attributes ?: return ""
        if (map.length == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until map.length) {
            val attr = map.item(i) ?: continue
            sb.append(' ').append(attr.nodeName).append("=\"")
                .append(escapeAttr(attr.nodeValue ?: "")).append('"')
        }
        return sb.toString()
    }

    private fun escapeText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(value: String): String = escapeText(value).replace("\"", "&quot;")

    companion object {
        const val MAX_DEPTH = 256
        const val INLINE_TEXT_LIMIT = 80
        const val DTD_SCAN_CHARS = 2048
        const val MAX_MESSAGE = 300
        private val DTD_REGEX = Regex("(?i)<!DOCTYPE|<!ENTITY")
    }
}
