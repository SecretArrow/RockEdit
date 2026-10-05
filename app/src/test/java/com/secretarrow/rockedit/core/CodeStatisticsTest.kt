package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Branch tests for [CodeStatistics]. Every scenario in the KDoc case table
 * has at least one test; lineCount/wordCount consistency with [TextStats] is
 * asserted on mixed-ending vectors.
 */
class CodeStatisticsTest {

    // ------------------------------------------------- TextStats consistency

    @Test
    fun lineCountMatchesTextStatsOnEmpty() {
        assertEquals(
            TextStats.lineCount(""),
            CodeStatistics.analyze("").lineCount,
        )
    }

    @Test
    fun lineCountMatchesTextStatsOnMixedEndings() {
        val vectors =
            listOf(
                "a\r\nb",
                "a\rb",
                "a\n\n",
                "a\r\n",
                "\n",
                "\r\n",
                "\r\n\r\nx",
                "one\rtwo\nthree\r\nfour",
                "no terminator",
            )
        for (v in vectors) {
            assertEquals(
                "vector: " + v.replace("\r", "<CR>").replace("\n", "<LF>"),
                TextStats.lineCount(v),
                CodeStatistics.analyze(v).lineCount,
            )
        }
    }

    @Test
    fun wordCountMatchesTextStats() {
        for (v in listOf("", "  ", "hello world", "a\tb\rc\nd", "ünïcödé words here")) {
            assertEquals(
                TextStats.wordCount(v),
                CodeStatistics.analyze(v).wordCount,
            )
        }
    }

    // ------------------------------------------------------- empty / blank

    @Test
    fun emptyTextGivesAllZeroStats() {
        val stats = CodeStatistics.analyze("")
        assertEquals(0, stats.charCount)
        assertEquals(0, stats.wordCount)
        assertEquals(0, stats.lineCount)
        assertEquals(0, stats.blankLines)
        assertEquals(0, stats.commentOnlyLines)
        assertEquals(0, stats.codeLines)
        assertEquals(0, stats.longestLineLength)
        assertEquals(0.0, stats.averageLineLength, 0.0)
        assertEquals(0, stats.lineEndings.crlf)
        assertEquals(0, stats.lineEndings.lf)
        assertEquals(0, stats.lineEndings.cr)
        assertEquals(-1, stats.indentation.commonSpaceWidth)
        assertEquals(false, stats.indentation.mixed)
        assertEquals(0, stats.trailingWhitespaceLines)
        assertEquals(0, stats.todos.total())
    }

    @Test
    fun whitespaceOnlyTextIsOneBlankLine() {
        val stats = CodeStatistics.analyze("   ")
        assertEquals(1, stats.lineCount)
        assertEquals(1, stats.blankLines)
        assertEquals(0, stats.codeLines)
        assertEquals(3, stats.longestLineLength)
    }

    // -------------------------------------------------------- line endings

    @Test
    fun crlfCountedAsOneUnit() {
        val stats = CodeStatistics.analyze("a\r\nb\r\nc")
        assertEquals(2, stats.lineEndings.crlf)
        assertEquals(0, stats.lineEndings.lf)
        assertEquals(0, stats.lineEndings.cr)
        assertEquals(3, stats.lineCount)
    }

    @Test
    fun loneLfAndCrCounted() {
        val stats = CodeStatistics.analyze("a\nb\rc\r\nd")
        assertEquals(1, stats.lineEndings.crlf)
        assertEquals(1, stats.lineEndings.lf)
        assertEquals(1, stats.lineEndings.cr)
    }

    @Test
    fun crlfNotDoubleCountedAsLf() {
        val stats = CodeStatistics.analyze("x\r\ny\r\n")
        assertEquals(0, stats.lineEndings.lf)
    }

    // ------------------------------------------------------ classification

    @Test
    fun codeBlankAndCommentLinesClassified() {
        val src = "int a = 1;\n\n// note\nint b = 2;\n   \n/* block */\nint c;\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(7, stats.lineCount)
        assertEquals(2, stats.blankLines)
        assertEquals(2, stats.commentOnlyLines)
        assertEquals(3, stats.codeLines)
    }

    @Test
    fun commentOnlyFileHasZeroCodeLines() {
        val stats = CodeStatistics.analyze("// a\n// b\n")
        assertEquals(2, stats.commentOnlyLines)
        assertEquals(0, stats.codeLines)
    }

    @Test
    fun codeBeforeLineCommentIsCodeLine() {
        val stats = CodeStatistics.analyze("x = 1 // trailing\n")
        assertEquals(0, stats.commentOnlyLines)
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun whitespaceAroundCommentStillCommentOnly() {
        val stats = CodeStatistics.analyze("   // indented note   \n")
        assertEquals(1, stats.commentOnlyLines)
        assertEquals(1, stats.trailingWhitespaceLines)
    }

    // ------------------------------------------------- string awareness

    @Test
    fun hashInsideStringIsNotComment() {
        val stats = CodeStatistics.analyze("s = \"a # b\"\n")
        assertEquals(0, stats.commentOnlyLines)
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun slashesInsideStringAreNotComment() {
        val stats = CodeStatistics.analyze("url = \"http://example.com\"\n")
        assertEquals(0, stats.commentOnlyLines)
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun commentStarterInsideTripleStringIsCode() {
        val src = "x = \"\"\"\n# not a comment\n\"\"\"\n"
        val stats = CodeStatistics.analyze(src, CommentProfiles.forFileName("m.py"))
        assertEquals(0, stats.commentOnlyLines)
        assertEquals(3, stats.codeLines)
    }

    @Test
    fun tripleStringDelimsAreCodeNotComment() {
        val stats =
            CodeStatistics.analyze("x = \"\"\"doc\"\"\"\n", CommentProfiles.forFileName("a.py"))
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun escapedQuoteDoesNotEndString() {
        val stats = CodeStatistics.analyze("s = \"a \\\" # not comment\"\n")
        assertEquals(1, stats.codeLines)
        assertEquals(0, stats.commentOnlyLines)
    }

    @Test
    fun unclosedQuoteConfinedToItsLine() {
        // Apostrophe in prose must not hide the comment on the NEXT line.
        val src = "don't worry\n// real comment\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(1, stats.codeLines)
        assertEquals(1, stats.commentOnlyLines)
    }

    // ------------------------------------------------------- block comments

    @Test
    fun blockCommentOneLineIsCommentOnly() {
        val stats = CodeStatistics.analyze("/* note */\n")
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun blockCommentWithTrailingCodeIsCodeLine() {
        val stats = CodeStatistics.analyze("/* note */ int x;\n")
        assertEquals(1, stats.codeLines)
        assertEquals(0, stats.commentOnlyLines)
    }

    @Test
    fun blockCommentSpanningLines() {
        val src = "/* one\ntwo\nthree */ int x;\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(3, stats.lineCount)
        assertEquals(2, stats.commentOnlyLines)
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun unclosedBlockCommentConsumesRest() {
        val src = "/* open\nint hidden = 1;\n// also comment\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(3, stats.lineCount)
        assertEquals(3, stats.commentOnlyLines)
        assertEquals(0, stats.codeLines)
    }

    @Test
    fun emptyBlockCommentIsCommentOnly() {
        val stats = CodeStatistics.analyze("/**/\n")
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun blockCommentAfterCodeThenClosedLater() {
        val src = "int a; /* start\nmiddle\nend */ int b;\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(3, stats.lineCount)
        assertEquals(1, stats.commentOnlyLines)
        assertEquals(2, stats.codeLines)
    }

    // ------------------------------------------------------- todo counting

    @Test
    fun todoInCommentCounted() {
        val stats = CodeStatistics.analyze("// TODO fix this\n")
        assertEquals(1, stats.todos.todo)
        assertEquals(1, stats.todos.total())
    }

    @Test
    fun todoWithColonCounts() {
        val stats = CodeStatistics.analyze("// TODO: refactor\n")
        assertEquals(1, stats.todos.todo)
    }

    @Test
    fun todosPluralDoesNotCount() {
        val stats = CodeStatistics.analyze("// list of TODOS here\n")
        assertEquals(0, stats.todos.todo)
    }

    @Test
    fun todoInStringDoesNotCount() {
        val stats = CodeStatistics.analyze("msg = \"TODO later\"\n")
        assertEquals(0, stats.todos.total())
    }

    @Test
    fun todoInCodeDoesNotCount() {
        val stats = CodeStatistics.analyze("int TODO = 1;\n")
        assertEquals(0, stats.todos.total())
    }

    @Test
    fun fixmeHackXxxCounted() {
        val src = "// FIXME a\n// HACK b\n// XXX c\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(1, stats.todos.fixme)
        assertEquals(1, stats.todos.hack)
        assertEquals(1, stats.todos.xxx)
        assertEquals(3, stats.todos.total())
    }

    @Test
    fun todoInBlockCommentCounted() {
        val src = "/* TODO one\ntwo */\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(1, stats.todos.todo)
    }

    @Test
    fun todoWordBoundaryUnderscorePrefix() {
        val stats = CodeStatistics.analyze("// _TODO underscore prefix\n")
        assertEquals(0, stats.todos.todo)
    }

    // --------------------------------------------------------- line metrics

    @Test
    fun longestLineAndAverage() {
        val src = "ab\nabcd\nabc\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(4, stats.longestLineLength)
        assertEquals(3.0, stats.averageLineLength, 1e-9)
    }

    @Test
    fun trailingWhitespaceLinesCounted() {
        val src = "code   \n\t\nmore\t\nlast\n"
        val stats = CodeStatistics.analyze(src)
        // "\t" line is blank, not trailing whitespace.
        assertEquals(2, stats.trailingWhitespaceLines)
    }

    @Test
    fun crlfNotCountedInLineLength() {
        val stats = CodeStatistics.analyze("abcd\r\n")
        assertEquals(4, stats.longestLineLength)
    }

    // ---------------------------------------------------------- indentation

    @Test
    fun tabIndentationDetected() {
        val src = "class A {\n\tint x;\n\tint y;\n}\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(2, stats.indentation.tabIndentedLines)
        assertEquals(0, stats.indentation.spaceIndentedLines)
        assertEquals(false, stats.indentation.mixed)
        assertEquals(-1, stats.indentation.commonSpaceWidth)
    }

    @Test
    fun spaceIndentationCommonWidthFour() {
        val src = "if a:\n    x = 1\n    y = 2\nz = 3\n"
        val stats = CodeStatistics.analyze(src, CommentProfiles.forFileName("s.py"))
        assertEquals(2, stats.indentation.spaceIndentedLines)
        assertEquals(4, stats.indentation.commonSpaceWidth)
        assertEquals(false, stats.indentation.mixed)
    }

    @Test
    fun mixedTabsAndSpacesFlagged() {
        val src = "a\n\tb\n    c\n"
        val stats = CodeStatistics.analyze(src)
        assertEquals(1, stats.indentation.tabIndentedLines)
        assertEquals(1, stats.indentation.spaceIndentedLines)
        assertEquals(true, stats.indentation.mixed)
    }

    @Test
    fun blankTabLineNotCountedAsIndented() {
        val src = "code\n\t\n\treal\n"
        val stats = CodeStatistics.analyze(src)
        // "\t" alone is blank; only "\treal" counts.
        assertEquals(1, stats.indentation.tabIndentedLines)
        assertEquals(1, stats.blankLines)
    }

    @Test
    fun commonWidthTiePrefersSmaller() {
        val src = " a\n  b\n   c\n"
        // widths 1,2,3 each once -> tie -> smallest (1).
        val stats = CodeStatistics.analyze(src)
        assertEquals(1, stats.indentation.commonSpaceWidth)
    }

    @Test
    fun unusualWidthIgnoredForCommon() {
        val src = "     a\n     b\n"
        // width 5 not in {1,2,3,4,6,8} -> common stays -1.
        val stats = CodeStatistics.analyze(src)
        assertEquals(2, stats.indentation.spaceIndentedLines)
        assertEquals(-1, stats.indentation.commonSpaceWidth)
    }

    // ------------------------------------------------------ profile mapping

    @Test
    fun pythonProfileUsesHashComments() {
        val stats = CodeStatistics.analyze("# note\n", CommentProfiles.forFileName("m.py"))
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun hashProfileForShell() {
        val stats =
            CodeStatistics.analyze("#!/bin/sh\necho hi\n", CommentProfiles.forFileName("run.sh"))
        assertEquals(1, stats.commentOnlyLines)
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun luaBlockCommentLongestFirst() {
        val stats = CodeStatistics.analyze("--[[ note ]]\n", CommentProfiles.forFileName("a.lua"))
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun luaLineComment() {
        val stats = CodeStatistics.analyze("-- note\n", CommentProfiles.forFileName("a.lua"))
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun htmlBlockComment() {
        val stats = CodeStatistics.analyze("<!-- hi -->\n", CommentProfiles.forFileName("i.html"))
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun sqlLineAndBlock() {
        val stats =
            CodeStatistics.analyze(
                "-- q1\n/* q2 */\nSELECT 1;\n",
                CommentProfiles.forFileName("q.sql"),
            )
        assertEquals(2, stats.commentOnlyLines)
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun cssHasBlockCommentsOnly() {
        val stats =
            CodeStatistics.analyze(
                "// not a comment\n/* yes */\n",
                CommentProfiles.forFileName("s.css"),
            )
        assertEquals(1, stats.commentOnlyLines)
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun iniSemicolonAndHash() {
        val stats = CodeStatistics.analyze("; c1\n# c2\n", CommentProfiles.forFileName("a.ini"))
        assertEquals(2, stats.commentOnlyLines)
    }

    @Test
    fun matlabPercent() {
        val stats = CodeStatistics.analyze("% note\n", CommentProfiles.forFileName("f.m"))
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun latexPercent() {
        val stats = CodeStatistics.analyze("% note\n", CommentProfiles.forFileName("d.tex"))
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun haskellProfile() {
        val stats =
            CodeStatistics.analyze("-- note\n{- block -}\n", CommentProfiles.forFileName("m.hs"))
        assertEquals(2, stats.commentOnlyLines)
    }

    @Test
    fun nullAndUnknownFallBackToDefault() {
        assertEquals(CommentProfiles.default(), CommentProfiles.forFileName(null))
        assertEquals(CommentProfiles.default(), CommentProfiles.forFileName(""))
        assertEquals(CommentProfiles.default(), CommentProfiles.forFileName("noext"))
        assertEquals(CommentProfiles.default(), CommentProfiles.forFileName("weird.xyzzy"))
        assertEquals(CommentProfiles.default(), CommentProfiles.forFileName("trailing."))
    }

    @Test
    fun uppercaseExtensionMatches() {
        assertEquals(CommentProfiles.forFileName("a.PY"), CommentProfiles.forFileName("a.py"))
    }

    // ------------------------------------------------------------- misc

    @Test
    fun defaultProfileIsCLike() {
        val stats = CodeStatistics.analyze("// c\n")
        assertEquals(1, stats.commentOnlyLines)
    }

    @Test
    fun charCountEqualsRawLength() {
        val src = "a\r\nb\n"
        assertEquals(5, CodeStatistics.analyze(src).charCount)
    }

    @Test
    fun kotlinStyleNestedBlockCommentsNotSupported() {
        // v1 closes at the FIRST closer; nested `/* /* */` leaves `*/` behind.
        val stats = CodeStatistics.analyze("/* /* nested */ still comment\n")
        // "still comment" is inside the block? No: closed at first */ -> rest
        // of line is code; next state is free. Line has code chars.
        assertEquals(1, stats.codeLines)
    }

    @Test
    fun largeSyntheticFileStaysConsistent() {
        val sb = StringBuilder()
        for (i in 0 until 1000) {
            sb.append("line ").append(i).append(" // note TODO\n")
        }
        val stats = CodeStatistics.analyze(sb.toString())
        assertEquals(1000, stats.lineCount)
        assertEquals(1000, stats.commentOnlyLines)
        assertEquals(1000, stats.todos.todo)
        assertEquals(0, stats.codeLines)
    }
}
