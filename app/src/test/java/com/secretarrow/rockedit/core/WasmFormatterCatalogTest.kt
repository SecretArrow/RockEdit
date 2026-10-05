package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for [WasmFormatterCatalog]: every language id must map
 * to the prettier parser the engine bundle actually ships, aliases must
 * normalize (trim + lowercase), unknown ids must yield null, and the
 * catalog keys must agree with [WasmCodeFormatter.supportedLanguages].
 */
class WasmFormatterCatalogTest {
    @Test
    fun javascriptJsAndJsxMapToBabel() {
        assertEquals("babel", WasmFormatterCatalog.parserFor("javascript"))
        assertEquals("babel", WasmFormatterCatalog.parserFor("js"))
        assertEquals("babel", WasmFormatterCatalog.parserFor("jsx"))
    }

    @Test
    fun typescriptTsAndTsxMapToTypescript() {
        assertEquals("typescript", WasmFormatterCatalog.parserFor("typescript"))
        assertEquals("typescript", WasmFormatterCatalog.parserFor("ts"))
        assertEquals("typescript", WasmFormatterCatalog.parserFor("tsx"))
    }

    @Test
    fun htmlHtmAndVueMapToHtml() {
        assertEquals("html", WasmFormatterCatalog.parserFor("html"))
        assertEquals("html", WasmFormatterCatalog.parserFor("htm"))
        assertEquals("html", WasmFormatterCatalog.parserFor("vue"))
    }

    @Test
    fun markdownAndMdMapToMarkdown() {
        assertEquals("markdown", WasmFormatterCatalog.parserFor("markdown"))
        assertEquals("markdown", WasmFormatterCatalog.parserFor("md"))
    }

    @Test
    fun graphqlMapsToGraphql() {
        assertEquals("graphql", WasmFormatterCatalog.parserFor("graphql"))
    }

    @Test
    fun inputIsTrimmedAndLowercased() {
        assertEquals("babel", WasmFormatterCatalog.parserFor("  JS "))
        assertEquals("typescript", WasmFormatterCatalog.parserFor("TypeScript"))
        assertEquals("html", WasmFormatterCatalog.parserFor("\tHTML\n"))
        assertEquals("markdown", WasmFormatterCatalog.parserFor("MD"))
    }

    @Test
    fun unknownLanguageReturnsNull() {
        assertNull(WasmFormatterCatalog.parserFor("python"))
        assertNull(WasmFormatterCatalog.parserFor("kotlin"))
        assertNull(WasmFormatterCatalog.parserFor("javascrip"))
    }

    @Test
    fun nullAndBlankReturnNull() {
        assertNull(WasmFormatterCatalog.parserFor(null))
        assertNull(WasmFormatterCatalog.parserFor(""))
        assertNull(WasmFormatterCatalog.parserFor("   "))
    }

    @Test
    fun catalogKeysMatchFormatterSupportedLanguages() {
        val formatter =
            WasmCodeFormatter(
                launchHost = { _, _ -> throw UnsupportedOperationException("not used here") },
            )
        assertEquals(WasmFormatterCatalog.languages, formatter.supportedLanguages)
        assertTrue(formatter.supportedLanguages.isNotEmpty())
    }

    @Test
    fun engineIdAndSizeCapAreStable() {
        assertEquals("prettier-2.8.8", WasmFormatterCatalog.ENGINE_ID)
        assertEquals(1_000_000, WasmFormatterCatalog.MAX_INPUT_CHARS)
    }
}
