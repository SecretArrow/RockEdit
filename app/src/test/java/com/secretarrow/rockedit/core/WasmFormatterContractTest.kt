package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for the hand-rolled WASM envelope contract:
 * building (escaping, option mapping) and parsing (ok/error/malformed/
 * id-mismatch/\uXXXX). The scanner must never throw, whatever it gets.
 */
class WasmFormatterContractTest {
    // ------------------------------------------------------------ building

    @Test
    fun payloadHasExactDefaultShape() {
        val payload = WasmFormatterContract.buildPayload("i1", "babel", "a\"b", FormatOptions())
        assertEquals(
            "{\"id\":\"i1\",\"parser\":\"babel\",\"text\":\"a\\\"b\"," +
                "\"options\":{\"tabWidth\":4,\"useTabs\":false,\"endOfLine\":\"lf\"}}",
            payload,
        )
    }

    @Test
    fun payloadEscapesEverythingWithoutRawControlChars() {
        val gnarly = "say \"hi\"\tback\\slash\nline2\r\rend\u0001😀</script>"
        val payload = WasmFormatterContract.buildPayload("i1", "babel", gnarly, FormatOptions())
        // Named escapes for the big five.
        assertTrue(payload.contains("\\n"))
        assertTrue(payload.contains("\\t"))
        assertTrue(payload.contains("\\r"))
        assertTrue(payload.contains("\\\\"))
        assertTrue(payload.contains("\\\""))
        // Control char below 0x20 becomes a \u00XX escape.
        assertTrue(payload.contains("\\u0001"))
        // UTF-8 passthrough: emoji and the closing script tag stay literal.
        assertTrue(payload.contains("😀"))
        assertTrue(payload.contains("</script>"))
        // No raw control character anywhere in the envelope.
        assertFalse(payload.any { it.code < 0x20 })
    }

    @Test
    fun textSurvivesBuildThenParseRoundtrip() {
        val gnarly = "quote:\" slash:\\ tab:\t nl:\n cr:\r ctrl:\u0002 😀 </script> é"
        val json = WasmFormatterContract.buildOkResponse("i1", gnarly)
        val response = WasmFormatterContract.parsePayload("i1", json)
        assertEquals(WasmResponse.Ok(gnarly), response)
    }

    @Test
    fun optionsMapSpacesTabsIndentSizeAndEndOfLine() {
        val spaces =
            WasmFormatterContract.buildPayload(
                "i1",
                "babel",
                "x",
                FormatOptions(indentStyle = IndentStyle.SPACES, indentSize = 2),
            )
        assertTrue(spaces.contains("\"tabWidth\":2"))
        assertTrue(spaces.contains("\"useTabs\":false"))

        val tabs =
            WasmFormatterContract.buildPayload(
                "i1",
                "babel",
                "x",
                FormatOptions(indentStyle = IndentStyle.TABS, indentSize = 8),
            )
        assertTrue(tabs.contains("\"tabWidth\":8"))
        assertTrue(tabs.contains("\"useTabs\":true"))

        val minIndent =
            WasmFormatterContract.buildPayload("i1", "babel", "x", FormatOptions(indentSize = 1))
        assertTrue(minIndent.contains("\"tabWidth\":1"))
        val maxIndent =
            WasmFormatterContract.buildPayload("i1", "babel", "x", FormatOptions(indentSize = 8))
        assertTrue(maxIndent.contains("\"tabWidth\":8"))

        val crlf =
            WasmFormatterContract.buildPayload(
                "i1",
                "babel",
                "x",
                FormatOptions(lineBreak = LineBreak.CRLF),
            )
        assertTrue(crlf.contains("\"endOfLine\":\"crlf\""))
    }

    @Test
    fun minifyIsIgnoredAndNeverSerialized() {
        val payload =
            WasmFormatterContract.buildPayload("i1", "babel", "x", FormatOptions(minify = true))
        assertFalse(payload.contains("minify"))
    }

    @Test
    fun responseBuildersProduceHostShapes() {
        assertEquals(
            "{\"id\":\"i1\",\"ok\":true,\"text\":\"ok\"}",
            WasmFormatterContract.buildOkResponse("i1", "ok"),
        )
        assertEquals(
            "{\"id\":\"i1\",\"ok\":false,\"code\":\"PARSE_ERROR\",\"message\":\"bad\"}",
            WasmFormatterContract.buildErrResponse("i1", "PARSE_ERROR", "bad"),
        )
    }

    // ------------------------------------------------------------ parsing

    @Test
    fun parsesOkResponse() {
        val response =
            WasmFormatterContract.parsePayload("x", "{\"id\":\"x\",\"ok\":true,\"text\":\"hi\"}")
        assertEquals(WasmResponse.Ok("hi"), response)
    }

    @Test
    fun parsesOkResponseBuiltByBuilder() {
        val response = WasmFormatterContract.parsePayload("req", WasmFormatterContract.buildOkResponse("req", "done\n"))
        assertEquals(WasmResponse.Ok("done\n"), response)
    }

    @Test
    fun unescapesUnicodeEscapesIncludingSurrogatePairs() {
        val response =
            WasmFormatterContract.parsePayload(
                "x",
                "{\"id\":\"x\",\"ok\":true,\"text\":\"A\\u0041\\u00e9\\ud83d\\ude00\\n\"}",
            )
        assertEquals(WasmResponse.Ok("AAé😀\n"), response)
    }

    @Test
    fun parsesErrResponseWithCode() {
        val response =
            WasmFormatterContract.parsePayload(
                "x",
                "{\"id\":\"x\",\"ok\":false,\"code\":\"PARSE_ERROR\",\"message\":\"bad (1:2)\"}",
            )
        assertEquals(WasmResponse.Err("PARSE_ERROR", "bad (1:2)"), response)
    }

    @Test
    fun errWithoutCodeFallsBackToEngineError() {
        val response = WasmFormatterContract.parsePayload("x", "{\"id\":\"x\",\"ok\":false}")
        assertEquals(WasmResponse.Err("ENGINE_ERROR", ""), response)
    }

    @Test
    fun extraKeysAreToleratedIncludingContainers() {
        val response =
            WasmFormatterContract.parsePayload(
                "x",
                "{\"id\":\"x\",\"ok\":true,\"extra\":{\"a\":[1,2,{\"b\":null}]}," +
                    "\"n\":-1.5e3,\"z\":null,\"text\":\"t\"}",
            )
        assertEquals(WasmResponse.Ok("t"), response)
    }

    @Test
    fun mismatchedIdIsRejected() {
        val response = WasmFormatterContract.parsePayload("other", WasmFormatterContract.buildOkResponse("x", "t"))
        assertTrue(response is WasmResponse.Err)
        assertEquals("ID_MISMATCH", (response as WasmResponse.Err).code)
    }

    private fun assertMalformed(json: String) {
        val response = WasmFormatterContract.parsePayload("x", json)
        assertTrue("expected MALFORMED for: $json", response is WasmResponse.Err)
        assertEquals("MALFORMED", (response as WasmResponse.Err).code)
    }

    @Test
    fun brokenJsonIsMalformed() {
        assertMalformed("{oops")
        assertMalformed("")
        assertMalformed("[1,2,3]")
        assertMalformed("null")
    }

    @Test
    fun missingKeysAreMalformed() {
        assertMalformed("{\"id\":\"x\",\"text\":\"t\"}") // no "ok"
        assertMalformed("{\"ok\":true,\"text\":\"t\"}") // no "id"
        assertMalformed("{\"id\":\"x\",\"ok\":true}") // success without "text"
    }

    @Test
    fun wrongValueTypesAreMalformed() {
        assertMalformed("{\"id\":\"x\",\"ok\":\"yes\",\"text\":\"t\"}") // ok not boolean
        assertMalformed("{\"id\":7,\"ok\":true,\"text\":\"t\"}") // id not a string
        assertMalformed("{\"id\":\"x\",\"ok\":true,\"text\":9}") // text not a string
    }

    @Test
    fun trailingGarbageIsMalformed() {
        assertMalformed("{\"id\":\"x\",\"ok\":true,\"text\":\"t\"} and more")
    }

    @Test
    fun invalidEscapesAreMalformed() {
        // Unknown escape \q.
        assertMalformed("{\"id\":\"x\",\"ok\":true,\"text\":\"a\\q\"}")
        // Bad hex digits in \uZZZZ.
        assertMalformed("{\"id\":\"x\",\"ok\":true,\"text\":\"a\\uZZZZ\"}")
        // Truncated \u escape.
        assertMalformed("{\"id\":\"x\",\"ok\":true,\"text\":\"a\\u00\"}")
    }

    @Test
    fun rawControlCharacterInsideStringIsMalformed() {
        assertMalformed("{\"id\":\"x\",\"ok\":true,\"text\":\"a\nb\"}")
    }

    @Test
    fun parsePayloadNeverThrows() {
        // Fuzz-ish: these inputs must all come back as Err, never throw.
        for (garbage in listOf("{{{{", "\"just a string\"", "{\"id\":", "€{}€", "{\"id\":\"x\",\"ok\":true,\"text\":")) {
            assertTrue(WasmFormatterContract.parsePayload("x", garbage) is WasmResponse.Err)
        }
    }

    @Test
    fun bigTextSurvivesRoundtrip() {
        val big = "x".repeat(999_999)
        val response = WasmFormatterContract.parsePayload("i1", WasmFormatterContract.buildOkResponse("i1", big))
        assertEquals(WasmResponse.Ok(big), response)
    }

    // ---------------------------------------------------- host-side helper

    @Test
    fun decodeJsonStringAcceptsOnlyJsonStrings() {
        assertEquals("a\nbAé", WasmFormatterContract.decodeJsonString("\"a\\nb\\u0041\\u00e9\""))
        assertEquals("", WasmFormatterContract.decodeJsonString("\"\""))
        assertNull(WasmFormatterContract.decodeJsonString("nope"))
        assertNull(WasmFormatterContract.decodeJsonString("{\"a\":1}"))
        assertNull(WasmFormatterContract.decodeJsonString("\"unclosed"))
        assertNull(WasmFormatterContract.decodeJsonString("\"trailing\" x"))
    }
}
