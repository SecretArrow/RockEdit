package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.XmlFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage of the XML formatter: pretty/minify, attributes,
 * self-closing, inline vs block text, CDATA, comments, declaration,
 * XXE rejection and parser error positions.
 */
class XmlFormatterTest {

    private val formatter = XmlFormatter()

    private fun format(text: String, options: FormatOptions = FormatOptions()): FormatResult =
        formatter.format(FormatRequest(text, "xml", options))

    // ------------------------------------------------------------ pretty

    @Test
    fun prettyPrintsNestedElements() {
        val out = (format("""<root><child><leaf>v</leaf></child></root>""") as FormatResult.Success).formattedText
        assertEquals("<root>\n    <child>\n        <leaf>v</leaf>\n    </child>\n</root>\n", out)
    }

    @Test
    fun attributesPreservedWithValueIntact() {
        val out = (format("""<root b="2" a="1"/>""") as FormatResult.Success).formattedText
        // DOM NamedNodeMap does not guarantee document order across engines,
        // so assert presence and values, not relative order.
        assertTrue(out.contains("b=\"2\""))
        assertTrue(out.contains("a=\"1\""))
        assertTrue(out.contains("<root") && out.contains("/>") )
    }

    @Test
    fun attributeValuesEscaped() {
        // Raw '<' is illegal inside attribute values; use entities in the
        // input and verify unescape -> re-escape round-trips identically.
        val out = (format("""<root t="a&lt;b&amp;c&quot;d"/>""") as FormatResult.Success).formattedText
        assertTrue(out.contains("t=\"a&lt;b&amp;c&quot;d\""))
    }

    @Test
    fun selfClosingPreserved() {
        val out = (format("""<root><br/></root>""") as FormatResult.Success).formattedText
        assertTrue(out.contains("<br/>"))
    }

    @Test
    fun emptyElementBecomesSelfClosing() {
        val out = (format("""<root><x></x></root>""") as FormatResult.Success).formattedText
        assertTrue(out.contains("<x/>"))
    }

    @Test
    fun shortTextStaysInline() {
        val out = (format("""<root><name>Rock</name></root>""") as FormatResult.Success).formattedText
        assertTrue(out.contains("<name>Rock</name>"))
    }

    @Test
    fun longTextGoesBlock() {
        val long = "x".repeat(120)
        val out = (format("<root><p>$long</p></root>") as FormatResult.Success).formattedText
        // Block layout: text is indented one level deeper than <p>.
        assertTrue(out.contains("<p>\n        $long\n    </p>"))
    }

    @Test
    fun textEscaped() {
        val out = (format("""<root><t>1 &lt; 2 &amp; 3</t></root>""") as FormatResult.Success).formattedText
        assertTrue(out.contains("1 &lt; 2 &amp; 3"))
    }

    // ---------------------------------------------- CDATA / comment / decl

    @Test
    fun cdataRoundTripsVerbatim() {
        val src = "<root><![CDATA[a<b>&c]]></root>"
        val out = (format(src) as FormatResult.Success).formattedText
        // CDATA content is emitted untouched: no escaping inside CDATA.
        assertTrue(out.contains("<![CDATA[a<b>&c]]>"))
    }

    @Test
    fun commentPreserved() {
        val out = (format("""<root><!-- keep me --><x/></root>""") as FormatResult.Success).formattedText
        assertTrue(out.contains("<!-- keep me -->"))
    }

    @Test
    fun xmlDeclarationPreserved() {
        val src = """<?xml version="1.0" encoding="UTF-8"?><root/>"""
        val out = (format(src) as FormatResult.Success).formattedText
        assertTrue(out.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
    }

    @Test
    fun processingInstructionPreserved() {
        val out = (format("""<root><?php echo 1; ?></root>""") as FormatResult.Success).formattedText
        assertTrue(out.contains("<?php echo 1; ?>"))
    }

    // -------------------------------------------------------- security

    @Test
    fun doctypeRejectedByPrescan() {
        val xxe = """<!DOCTYPE r [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><r>&xxe;</r>"""
        val out = format(xxe)
        assertTrue(out is FormatResult.Failure)
        assertEquals(FormatErrorCode.PARSE_ERROR, (out as FormatResult.Failure).error.code)
        assertTrue((out as FormatResult.Failure).error.message.contains("XXE"))
    }

    @Test
    fun entityWithoutDoctypeStillRejected() {
        val out = format("""<r><!ENTITY x "y"/></r>""")
        assertTrue(out is FormatResult.Failure)
        assertTrue((out as FormatResult.Failure).error.message.contains("XXE"))
    }

    // -------------------------------------------------------- errors

    @Test
    fun mismatchedTagReportsLine() {
        val src = "<root>\n  <a>\n</root>"
        val out = format(src)
        assertTrue(out is FormatResult.Failure)
        val error = (out as FormatResult.Failure).error
        assertEquals(FormatErrorCode.PARSE_ERROR, error.code)
        assertEquals(3, error.line)
        assertNotNull(error.column)
    }

    @Test
    fun unclosedTagRejected() {
        val out = format("<root><child>")
        assertTrue(out is FormatResult.Failure)
    }

    // -------------------------------------------------------- minify

    @Test
    fun minifyStripsWhitespaceBetweenElements() {
        val out = (format("<root>\n  <a>1</a>\n  <b>2</b>\n</root>\n", FormatOptions(minify = true)) as FormatResult.Success)
            .formattedText
        assertEquals("<root><a>1</a><b>2</b></root>\n", out)
    }

    @Test
    fun minifyKeepsTextContent() {
        val out = (format("<root>  spaced  text  </root>", FormatOptions(minify = true)) as FormatResult.Success)
            .formattedText
        assertTrue(out.contains("spaced  text"))
    }

    @Test
    fun idempotentFormatting() {
        val messy = """<root a="1"><x>v</x><y><!--c--><z/></y></root>"""
        val once = (format(messy) as FormatResult.Success).formattedText
        val twice = (format(once) as FormatResult.Success).formattedText
        assertEquals(once, twice)
        assertFalse(twice.isEmpty())
    }
}
