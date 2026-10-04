package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.MarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun headingsRenderAtCorrectLevel() {
        assertEquals("<h1>Title</h1>\n", MarkdownRenderer.render("# Title"))
        assertEquals("<h3>Deep</h3>\n", MarkdownRenderer.render("### Deep"))
        assertFalse(MarkdownRenderer.render("####### seven").contains("<h7>"))
    }

    @Test
    fun paragraphsWithLineBreaks() {
        val html = MarkdownRenderer.render("first\nsecond")
        assertTrue(html.startsWith("<p>"))
        assertTrue(html.contains("first"))
        assertTrue(html.contains("second"))
    }

    @Test
    fun codeFencesAreEscapedAndGrouped() {
        val html = MarkdownRenderer.render("```\nval x = \"<b>\" \nval y = 1\n```")
        assertTrue(html.contains("<pre><code>"))
        assertFalse(html.contains("<b>"))
        assertTrue(html.contains("&quot;&lt;b&gt;&quot;"))
    }

    @Test
    fun inlineFormatting() {
        assertEquals("a <strong>bold</strong> b", MarkdownRenderer.inline("a **bold** b"))
        assertEquals("a <em>it</em> b", MarkdownRenderer.inline("a *it* b"))
        assertEquals("<code>x*y</code>", MarkdownRenderer.inline("`x*y`"))
        assertEquals("<a href=\"https://e.org\">site</a>", MarkdownRenderer.inline("[site](https://e.org)"))
        assertEquals("<img alt=\"pic\" src=\"i.png\"/>", MarkdownRenderer.inline("![pic](i.png)"))
    }

    @Test
    fun htmlIsEscapedBeforeFormatting() {
        assertEquals("text &lt;script&gt;", MarkdownRenderer.inline("text <script>"))
    }

    @Test
    fun listsAndQuotesAndRules() {
        val html = MarkdownRenderer.render("- one\n- two\n\n1. first\n2. second\n\n> quoted\n\n---")
        assertTrue(html.contains("<ul><li>one</li><li>two</li></ul>"))
        assertTrue(html.contains("<ol><li>first</li><li>second</li></ol>"))
        assertTrue(html.contains("<blockquote>quoted</blockquote>"))
        assertTrue(html.contains("<hr/>"))
    }

    @Test
    fun documentWrapperIncludesStyleAndBody() {
        val doc = MarkdownRenderer.renderDocument("# Hi", dark = true)
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("background:#0f172a"))
        assertTrue(doc.contains("<h1>Hi</h1>"))
        assertFalse(MarkdownRenderer.renderDocument("# Hi", dark = false).contains("#0f172a"))
    }
}
