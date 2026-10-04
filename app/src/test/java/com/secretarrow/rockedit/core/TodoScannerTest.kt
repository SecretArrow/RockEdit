package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [TodoScanner] (v0.13.0): default and custom markers,
 * case sensitivity, word boundaries, tag capture and malformed tags,
 * line/column/offset math for `\n`, `\r\n`, and lone `\r`, message trimming
 * and ellipsis, truncation, and the input-size failure path.
 */
class TodoScannerTest {

    private fun ok(result: TodoScanner.ScanResult): TodoScanner.ScanResult.Success {
        assertTrue("expected Success, was $result", result is TodoScanner.ScanResult.Success)
        return result as TodoScanner.ScanResult.Success
    }

    private fun failureOf(result: TodoScanner.ScanResult): TodoScanner.ScanResult.Failure {
        assertTrue("expected Failure, was $result", result is TodoScanner.ScanResult.Failure)
        return result as TodoScanner.ScanResult.Failure
    }

    private fun expectOptionsRejected(build: () -> Unit) {
        try {
            build()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Expected: invalid TodoOptions must never be constructed.
        }
    }

    // ------------------------------------------------------------- markers

    @Test
    fun findsEveryDefaultMarker() {
        val text = "TODO one\nFIXME two\nHACK three\nXXX four\nBUG five\nNOTE six"
        val result = ok(TodoScanner.scan(text))
        assertEquals(6, result.items.size)
        assertEquals(
            setOf("TODO", "FIXME", "HACK", "XXX", "BUG", "NOTE"),
            result.items.map { it.marker }.toSet()
        )
        assertFalse(result.truncated)
    }

    @Test
    fun lowercaseMarkerMatchesWhenCaseInsensitive() {
        val item = ok(TodoScanner.scan("todo fix this")).items.single()
        assertEquals("TODO", item.marker)
        assertEquals(1, item.lineNumber)
        assertEquals(1, item.column)
    }

    @Test
    fun mixedCaseMarkerMatchesWhenCaseInsensitive() {
        val item = ok(TodoScanner.scan("FixMe now please")).items.single()
        assertEquals("FIXME", item.marker)
    }

    @Test
    fun caseSensitiveSkipsLowercase() {
        val result = ok(
            TodoScanner.scan("todo fix this", TodoScanner.TodoOptions(caseSensitive = true))
        )
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun caseSensitiveFindsUppercaseOnly() {
        val items = ok(
            TodoScanner.scan("todo and TODO", TodoScanner.TodoOptions(caseSensitive = true))
        ).items
        assertEquals(1, items.size)
        assertEquals("TODO", items.single().marker)
        assertEquals(10, items.single().column)
    }

    @Test
    fun customMarkerSetReplacesDefaults() {
        val options = TodoScanner.TodoOptions(markers = setOf("OPT"))
        val result = ok(TodoScanner.scan("OPT do it\nTODO skip", options))
        assertEquals(1, result.items.size)
        assertEquals("OPT", result.items.single().marker)
    }

    // ------------------------------------------------------ word boundaries

    @Test
    fun wordBoundaryRejectsSuffixWord() {
        assertTrue(ok(TodoScanner.scan("TODOS many things")).items.isEmpty())
    }

    @Test
    fun wordBoundaryRejectsPrefixWord() {
        assertTrue(ok(TodoScanner.scan("xTODO keep")).items.isEmpty())
    }

    @Test
    fun wordBoundaryRejectsUnderscoreNeighbor() {
        assertTrue(ok(TodoScanner.scan("TODO_x trailing")).items.isEmpty())
        assertTrue(ok(TodoScanner.scan("lead_TODO trailing")).items.isEmpty())
    }

    @Test
    fun boundaryAllowsPunctuationSuffix() {
        val item = ok(TodoScanner.scan("TODO: ship it")).items.single()
        assertEquals("TODO", item.marker)
        assertEquals(": ship it", item.message)
    }

    @Test
    fun markerInsideUrlMatchesByDesign() {
        // Documented v1 assumption: no URL/comment awareness, so a marker
        // framed by non-word characters matches anywhere.
        val item = ok(TodoScanner.scan("see https://x/TODO/1")).items.single()
        assertEquals("TODO", item.marker)
    }

    @Test
    fun markerNeverSpansLineBreak() {
        assertTrue(ok(TodoScanner.scan("TO\nDO")).items.isEmpty())
    }

    // ------------------------------------------------------ tags & messages

    @Test
    fun tagIsCapturedAndMessageFollows() {
        val item = ok(TodoScanner.scan("TODO(p1) write tests")).items.single()
        assertEquals("p1", item.tag)
        assertEquals("write tests", item.message)
    }

    @Test
    fun tagAllowsDashDigitsUnderscore() {
        val item = ok(TodoScanner.scan("FIXME(p2-fix_3) go")).items.single()
        assertEquals("p2-fix_3", item.tag)
    }

    @Test
    fun malformedTagStaysInMessage() {
        val item = ok(TodoScanner.scan("TODO(p q) check")).items.single()
        assertNull(item.tag)
        assertEquals("(p q) check", item.message)
    }

    @Test
    fun emptyTagIsMalformed() {
        val item = ok(TodoScanner.scan("TODO() nothing")).items.single()
        assertNull(item.tag)
        assertEquals("() nothing", item.message)
    }

    @Test
    fun unclosedTagIsMalformed() {
        val item = ok(TodoScanner.scan("TODO(p1 open")).items.single()
        assertNull(item.tag)
        assertEquals("(p1 open", item.message)
    }

    @Test
    fun tagLengthBoundary() {
        val tag20 = "a".repeat(TodoScanner.MAX_TAG_CHARS)
        assertEquals(tag20, ok(TodoScanner.scan("TODO($tag20) ok")).items.single().tag)
        val tag21 = "a".repeat(TodoScanner.MAX_TAG_CHARS + 1)
        val item = ok(TodoScanner.scan("TODO($tag21) no")).items.single()
        assertNull(item.tag)
        assertEquals("($tag21) no", item.message)
    }

    @Test
    fun messageIsTrimmed() {
        val item = ok(TodoScanner.scan("TODO    spaced out   ")).items.single()
        assertEquals("spaced out", item.message)
    }

    @Test
    fun messageAfterTagIsTrimmed() {
        val item = ok(TodoScanner.scan("TODO(p1)    indented")).items.single()
        assertEquals("indented", item.message)
    }

    @Test
    fun longMessageIsCutWithEllipsis() {
        val item = ok(TodoScanner.scan("TODO " + "x".repeat(250))).items.single()
        assertEquals(TodoScanner.MAX_MESSAGE_CHARS + 1, item.message.length)
        assertTrue(item.message.endsWith(TodoScanner.ELLIPSIS))
        assertEquals("x".repeat(TodoScanner.MAX_MESSAGE_CHARS), item.message.dropLast(1))
    }

    @Test
    fun emptyMessageStaysEmpty() {
        assertEquals("", ok(TodoScanner.scan("TODO")).items.single().message)
        assertEquals("", ok(TodoScanner.scan("TODO(p1)")).items.single().message)
    }

    // ------------------------------------------------------ lines & offsets

    @Test
    fun emptyTextSucceedsWithNoItems() {
        val result = ok(TodoScanner.scan(""))
        assertTrue(result.items.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun textWithoutMarkersSucceedsWithNoItems() {
        val result = ok(TodoScanner.scan("just some plain text\nnothing to see"))
        assertTrue(result.items.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun markerAtEndOfFileWithoutNewline() {
        val text = "TODO first\nplain middle\nTODO last"
        val items = ok(TodoScanner.scan(text)).items
        assertEquals(2, items.size)
        assertEquals(1, items[0].lineNumber)
        assertEquals(3, items[1].lineNumber)
        assertEquals(1, items[1].column)
    }

    @Test
    fun trailingNewlineProducesNoExtraLine() {
        assertEquals(1, ok(TodoScanner.scan("TODO x\n")).items.size)
        assertEquals(1, ok(TodoScanner.scan("TODO x\n\n")).items.size)
    }

    @Test
    fun crlfColumnsLinesAndOffsets() {
        val text = "TODO one\r\nFIXME two\r\nplain\r\nBUG three"
        val items = ok(TodoScanner.scan(text)).items
        assertEquals(3, items.size)
        assertEquals(1, items[0].lineNumber)
        assertEquals(1, items[0].column)
        assertEquals(0, items[0].offset)
        assertEquals(2, items[1].lineNumber)
        assertEquals(1, items[1].column)
        assertEquals(10, items[1].offset)
        assertEquals(4, items[2].lineNumber)
        assertEquals(1, items[2].column)
    }

    @Test
    fun loneCrSeparatesLines() {
        val items = ok(TodoScanner.scan("TODO a\rFIXME b")).items
        assertEquals(2, items.size)
        assertEquals(1, items[0].lineNumber)
        assertEquals(2, items[1].lineNumber)
    }

    @Test
    fun consecutiveMarkersOnOneLine() {
        val items = ok(TodoScanner.scan("TODO and FIXME here")).items
        assertEquals(2, items.size)
        assertEquals("TODO", items[0].marker)
        assertEquals(1, items[0].column)
        assertEquals("FIXME", items[1].marker)
        assertEquals(10, items[1].column)
    }

    @Test
    fun columnCountsCharactersIncludingTabs() {
        val item = ok(TodoScanner.scan("\tTODO x")).items.single()
        assertEquals(2, item.column)
    }

    @Test
    fun columnForMidLineMarker() {
        val item = ok(TodoScanner.scan("code TODO here")).items.single()
        assertEquals(6, item.column)
    }

    @Test
    fun offsetPointsAtMarkerStart() {
        val text = "line one\n// TODO hello"
        val item = ok(TodoScanner.scan(text)).items.single()
        assertEquals(text.indexOf("TODO"), item.offset)
        assertEquals("TODO", text.substring(item.offset, item.offset + item.marker.length))
    }

    // ----------------------------------------------------------- truncation

    @Test
    fun truncatesAtMaxItems() {
        val text = "TODO a\nFIXME b\nHACK c\nXXX d\nBUG e"
        val result = ok(TodoScanner.scan(text, TodoScanner.TodoOptions(maxItems = 3)))
        assertEquals(3, result.items.size)
        assertTrue(result.truncated)
        assertEquals(listOf("TODO", "FIXME", "HACK"), result.items.map { it.marker })
    }

    @Test
    fun reachingLimitExactlyStillReportsTruncated() {
        val text = "TODO a\nFIXME b\nHACK c"
        val result = ok(TodoScanner.scan(text, TodoScanner.TodoOptions(maxItems = 3)))
        assertEquals(3, result.items.size)
        assertTrue(result.truncated)
    }

    // ------------------------------------------------------------ size caps

    @Test
    fun inputAboveLimitFailsWithActualAndLimit() {
        val text = "a".repeat(TodoScanner.MAX_INPUT_CHARS + 1)
        val result = failureOf(TodoScanner.scan(text))
        assertEquals(TodoScanner.ErrorCode.INPUT_TOO_LARGE, result.code)
        assertTrue(result.message.contains("${TodoScanner.MAX_INPUT_CHARS + 1}"))
        assertTrue(result.message.contains("${TodoScanner.MAX_INPUT_CHARS}"))
    }

    @Test
    fun inputAboveSmallCapFailsAndExactCapPasses() {
        val over = failureOf(
            TodoScanner.scanWithCap("0123456789ABC", TodoScanner.TodoOptions(), 10)
        )
        assertEquals(TodoScanner.ErrorCode.INPUT_TOO_LARGE, over.code)
        val exact = ok(TodoScanner.scanWithCap("0123456789", TodoScanner.TodoOptions(), 10))
        assertTrue(exact.items.isEmpty())
        assertFalse(exact.truncated)
    }

    // ------------------------------------------------------ options defenses

    @Test
    fun optionsNormalizeAndTrimMarkers() {
        val options = TodoScanner.TodoOptions(markers = setOf(" todo "))
        assertEquals(setOf("TODO"), options.normalizedMarkers)
        val item = ok(TodoScanner.scan("todo x", options)).items.single()
        assertEquals("TODO", item.marker)
    }

    @Test
    fun optionsRejectBlankMarker() {
        expectOptionsRejected { TodoScanner.TodoOptions(markers = setOf("TODO", "   ")) }
    }

    @Test
    fun optionsRejectEmptyMarkerSet() {
        expectOptionsRejected { TodoScanner.TodoOptions(markers = emptySet()) }
    }

    @Test
    fun optionsRejectMaxItemsOutOfBounds() {
        expectOptionsRejected { TodoScanner.TodoOptions(maxItems = 0) }
        expectOptionsRejected { TodoScanner.TodoOptions(maxItems = -5) }
        expectOptionsRejected {
            TodoScanner.TodoOptions(maxItems = TodoScanner.TodoOptions.MAX_ITEMS_LIMIT + 1)
        }
        assertEquals(1, TodoScanner.TodoOptions(maxItems = 1).maxItems)
        assertEquals(
            TodoScanner.TodoOptions.MAX_ITEMS_LIMIT,
            TodoScanner.TodoOptions(maxItems = TodoScanner.TodoOptions.MAX_ITEMS_LIMIT).maxItems
        )
    }
}
