package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Per-branch tests for [BraceMatcher] (v0.13.0): simple and nested pairs in
 * both directions, interleaving rules, string/char/comment skipping, escape
 * handling, scan limits, bounds handling and BracePairs validation.
 */
class BraceMatcherTest {

    private fun matched(result: BraceMatcher.MatchResult): BraceMatcher.MatchResult.Matched {
        assertTrue("expected Matched, was $result", result is BraceMatcher.MatchResult.Matched)
        return result as BraceMatcher.MatchResult.Matched
    }

    private fun unmatched(result: BraceMatcher.MatchResult): BraceMatcher.MatchResult.Unmatched {
        assertTrue("expected Unmatched, was $result", result is BraceMatcher.MatchResult.Unmatched)
        return result as BraceMatcher.MatchResult.Unmatched
    }

    private fun expectIllegal(block: () -> Unit): String {
        return try {
            block()
            fail("expected IllegalArgumentException")
            error("unreachable: expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            expected.message ?: ""
        }
    }

    // -------------------------------------------------- simple pairs

    @Test
    fun simpleParenForward() {
        val hit = matched(BraceMatcher.matchAt("a(b)c", 1))
        assertEquals(3, hit.partnerIndex)
        assertTrue(hit.isOpener)
        assertEquals('(', hit.bracket)
    }

    @Test
    fun simpleParenBackward() {
        val hit = matched(BraceMatcher.matchAt("a(b)c", 3))
        assertEquals(1, hit.partnerIndex)
        assertEquals(false, hit.isOpener)
        assertEquals(')', hit.bracket)
    }

    @Test
    fun squareAndCurlyBothDirections() {
        assertEquals(3, matched(BraceMatcher.matchAt("x[1]", 1)).partnerIndex)
        assertEquals(1, matched(BraceMatcher.matchAt("x[1]", 3)).partnerIndex)
        assertEquals(3, matched(BraceMatcher.matchAt("f{g}", 1)).partnerIndex)
        assertEquals(1, matched(BraceMatcher.matchAt("f{g}", 3)).partnerIndex)
    }

    @Test
    fun nestedSameTypeForward() {
        val hit = matched(BraceMatcher.matchAt("((a))", 0))
        assertEquals(4, hit.partnerIndex)
    }

    @Test
    fun nestedSameTypeBackward() {
        val hit = matched(BraceMatcher.matchAt("((a))", 4))
        assertEquals(0, hit.partnerIndex)
        assertEquals(false, hit.isOpener)
    }

    // -------------------------------------------------- mixed nesting

    @Test
    fun mixedNestingMatchesEveryDirection() {
        val text = "{ [ ( ) ] }"
        assertEquals(10, matched(BraceMatcher.matchAt(text, 0)).partnerIndex)
        assertEquals(8, matched(BraceMatcher.matchAt(text, 2)).partnerIndex)
        assertEquals(6, matched(BraceMatcher.matchAt(text, 4)).partnerIndex)
        assertEquals(4, matched(BraceMatcher.matchAt(text, 6)).partnerIndex)
        assertEquals(2, matched(BraceMatcher.matchAt(text, 8)).partnerIndex)
        assertEquals(0, matched(BraceMatcher.matchAt(text, 10)).partnerIndex)
    }

    @Test
    fun closerOfAnotherTypeNeverMatchesOurOpener() {
        // "{ [(] }": scanning forward from '(' must not match ']'.
        val fromParen = unmatched(BraceMatcher.matchAt("{ [(] }", 3))
        assertTrue(fromParen.reason.contains("no closing"))
        // From '{' the source is unbalanced (']' cannot close '('), so the
        // strict interleaving rule reports Unmatched instead of a false match.
        val fromCurly = unmatched(BraceMatcher.matchAt("{ [(] }", 0))
        assertTrue(fromCurly.reason.contains("unbalanced"))
    }

    @Test
    fun ourCloserWithOpenInnerBracketIsUnbalanced() {
        // "([)]" from '(': ')' arrives while '[' is still open.
        val result = unmatched(BraceMatcher.matchAt("([)]", 0))
        assertTrue(result.reason.contains("unbalanced"))
    }

    @Test
    fun ourCloserAtDepthZeroIsUnbalanced() {
        // "(( )))": the second ')' beyond depth 0 proves broken source.
        val result = unmatched(BraceMatcher.matchAt("(( )))", 0))
        assertTrue(result.reason.contains("unbalanced"))
    }

    @Test
    fun unclosedOpenerReportsNoClosing() {
        val result = unmatched(BraceMatcher.matchAt("((", 0))
        assertTrue(result.reason.contains("no closing ')'"))
    }

    @Test
    fun strayCloserBackwardReportsNoOpening() {
        val result = unmatched(BraceMatcher.matchAt("{ ) }", 2))
        assertTrue(result.reason.contains("no opening '('"))
    }

    @Test
    fun interleavedBackwardFromSquareReportsNoOpening() {
        val result = unmatched(BraceMatcher.matchAt("([)]", 3))
        assertTrue(result.reason.contains("no opening '['"))
    }

    // -------------------------------------------------- cursor & bounds

    @Test
    fun nonBracketCharactersReportNoBracket() {
        assertEquals(BraceMatcher.MatchResult.NoBracketAtCursor, BraceMatcher.matchAt("abc", 1))
        assertEquals(BraceMatcher.MatchResult.NoBracketAtCursor, BraceMatcher.matchAt("a1", 1))
    }

    @Test
    fun outOfRangeIndexNeverThrows() {
        val before = unmatched(BraceMatcher.matchAt("abc", -1))
        assertTrue(before.reason.contains("index out of bounds"))
        val after = unmatched(BraceMatcher.matchAt("abc", 3))
        assertTrue(after.reason.contains("index out of bounds"))
    }

    @Test
    fun emptyTextAlwaysReportsNoBracket() {
        // Documented precedence: empty text wins over the bounds check.
        assertEquals(BraceMatcher.MatchResult.NoBracketAtCursor, BraceMatcher.matchAt("", 0))
        assertEquals(BraceMatcher.MatchResult.NoBracketAtCursor, BraceMatcher.matchAt("", -1))
    }

    // -------------------------------------------------- string literals

    @Test
    fun bracketInsideStringIsSkippedForward() {
        // f ( " x ) " )
        val hit = matched(BraceMatcher.matchAt("f(\"x)\")", 1))
        assertEquals(6, hit.partnerIndex)
    }

    @Test
    fun escapedQuoteInsideStringIsSkippedForward() {
        // f ( " a \ " ( b ) " )
        val hit = matched(BraceMatcher.matchAt("f(\"a\\\"(b)\")", 1))
        assertEquals(10, hit.partnerIndex)
    }

    @Test
    fun escapedQuoteInsideStringIsSkippedBackward() {
        val hit = matched(BraceMatcher.matchAt("f(\"a\\\"(b)\")", 10))
        assertEquals(1, hit.partnerIndex)
    }

    @Test
    fun doubleEscapedQuoteBoundaryIsResolvedBackward() {
        // say ( " she said \ " h i \ " " ) - the closing quote is unescaped.
        val hit = matched(BraceMatcher.matchAt("say(\"she said \\\"hi\\\"\")", 21))
        assertEquals(3, hit.partnerIndex)
    }

    @Test
    fun apostropheInsideStringIsNotACharLiteral() {
        // g ( " i t ' s ( fine ) " )
        val hit = matched(BraceMatcher.matchAt("g(\"it's ( fine)\")", 1))
        assertEquals(16, hit.partnerIndex)
    }

    @Test
    fun escapedQuoteInCodeDoesNotOpenString() {
        // f ( \ " ) - pathological but the escape must not swallow the match.
        assertEquals(4, matched(BraceMatcher.matchAt("f(\\\")", 1)).partnerIndex)
        assertEquals(1, matched(BraceMatcher.matchAt("f(\\\")", 4)).partnerIndex)
    }

    @Test
    fun unterminatedStringRecoversAtNewline() {
        // f ( " a b c \n d e f )
        val hit = matched(BraceMatcher.matchAt("f(\"abc\ndef)", 1))
        assertEquals(10, hit.partnerIndex)
    }

    // -------------------------------------------------- char literals

    @Test
    fun charLiteralParenIsSkippedForward() {
        // a ( ' ( ' ) b
        val hit = matched(BraceMatcher.matchAt("a('(')b", 1))
        assertEquals(5, hit.partnerIndex)
    }

    @Test
    fun charLiteralParenIsSkippedBackward() {
        val hit = matched(BraceMatcher.matchAt("a('(')b", 5))
        assertEquals(1, hit.partnerIndex)
    }

    // -------------------------------------------------- comments

    @Test
    fun lineCommentContentIsSkippedForward() {
        // a ( / / ( ) \n b )
        val hit = matched(BraceMatcher.matchAt("a( // ( ) \nb)", 1))
        assertEquals(12, hit.partnerIndex)
    }

    @Test
    fun crlfTerminatesLineComment() {
        // a ( / / ) \r \n b )
        val hit = matched(BraceMatcher.matchAt("a( // )\r\nb)", 1))
        assertEquals(10, hit.partnerIndex)
    }

    @Test
    fun blockCommentContentIsSkippedForward() {
        // a ( / * ) * / b )
        val hit = matched(BraceMatcher.matchAt("a( /* ) */ b)", 1))
        assertEquals(12, hit.partnerIndex)
    }

    @Test
    fun blockCommentContentIsSkippedBackward() {
        val hit = matched(BraceMatcher.matchAt("a( /* ) */ b)", 12))
        assertEquals(1, hit.partnerIndex)
    }

    @Test
    fun commentOpenerInsideStringIsLiteralText() {
        // f ( " ( / * ) " ) - the /* never opens a comment.
        val hit = matched(BraceMatcher.matchAt("f(\"(/*)\")", 1))
        assertEquals(8, hit.partnerIndex)
    }

    @Test
    fun backwardScanLineCommentLimitationIsDocumented() {
        val text = "a(\n// ) mid\nb)"
        // Forward is authoritative and finds the partner.
        assertEquals(13, matched(BraceMatcher.matchAt(text, 1)).partnerIndex)
        // Backward visits the commented ')' first (counted as nesting), then
        // drops the // segment including the real opener, so it reports
        // "no opening" instead of a false match (documented simplification).
        val result = unmatched(BraceMatcher.matchAt(text, 13))
        assertTrue(result.reason.contains("no opening"))
    }

    // -------------------------------------------------- raw strings

    @Test
    fun kotlinRawStringsAreNotSpecialCased() {
        // f ( " " " ( ) " " " ) - each quote toggles the string state.
        val hit = matched(BraceMatcher.matchAt("f(\"\"\"( )\"\"\")", 1))
        assertEquals(11, hit.partnerIndex)
    }

    // -------------------------------------------------- scan limits

    @Test
    fun forwardScanStopsAtTinyLimit() {
        val text = "(a b c d e f g h)"
        val result = unmatched(BraceMatcher.matchAtWithLimit(text, 0, BracePairs.DEFAULT, 10))
        assertTrue(result.reason.contains("scan limit"))
        assertEquals(16, matched(BraceMatcher.matchAt(text, 0)).partnerIndex)
    }

    @Test
    fun backwardScanStopsAtTinyLimit() {
        val text = "(a b c d e f g h)"
        val result = unmatched(BraceMatcher.matchAtWithLimit(text, 16, BracePairs.DEFAULT, 5))
        assertTrue(result.reason.contains("scan limit"))
    }

    @Test
    fun zeroLimitMatchesNothing() {
        val result = unmatched(BraceMatcher.matchAtWithLimit("(x)", 0, BracePairs.DEFAULT, 0))
        assertTrue(result.reason.contains("scan limit"))
    }

    @Test
    fun negativeLimitIsAProgrammingError() {
        val message = expectIllegal {
            BraceMatcher.matchAtWithLimit("(x)", 0, BracePairs.DEFAULT, -1)
        }
        assertTrue(message.contains("maxScan is -1"))
    }

    // -------------------------------------------------- custom pairs

    @Test
    fun customPairsMatchTheirOwnBrackets() {
        val pairs = BracePairs(listOf('<' to '>'))
        val hit = matched(BraceMatcher.matchAt("<a>", 0, pairs))
        assertEquals(2, hit.partnerIndex)
        assertEquals('<', hit.bracket)
        // Default pairs do not know '<'.
        assertEquals(BraceMatcher.MatchResult.NoBracketAtCursor, BraceMatcher.matchAt("<a>", 0))
    }

    // -------------------------------------------------- BracePairs validation

    @Test
    fun emptyPairsAreRejected() {
        val message = expectIllegal { BracePairs(emptyList()) }
        assertTrue(message.contains("at least one"))
    }

    @Test
    fun identicalOpenAndCloseAreRejected() {
        val message = expectIllegal { BracePairs(listOf('x' to 'x')) }
        assertTrue(message.contains("must differ"))
    }

    @Test
    fun duplicateCloserIsRejected() {
        val message = expectIllegal { BracePairs(listOf('(' to ')', '[' to ')')) }
        assertTrue(message.contains("duplicate"))
        assertTrue(message.contains("')'"))
    }

    @Test
    fun duplicateAcrossPairsIsRejected() {
        val message = expectIllegal { BracePairs(listOf('a' to 'b', 'b' to 'c')) }
        assertTrue(message.contains("duplicate"))
        assertTrue(message.contains("'b'"))
    }
}
