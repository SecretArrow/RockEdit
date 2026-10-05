package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-branch tests for [TmLanguageParser] (v0.17.0 TextMate import): validity
 * cases, traversal budget (cycles, chains, external includes), keyword
 * extraction from match regexes, line/block comment delimiter extraction,
 * string delimiter defaults, extension normalization and identity rules.
 */
class TmLanguageParserTest {
    private fun successOf(result: TmLanguageParser.ParseResult): TmLanguageParser.ParseResult.Success {
        assertTrue("expected Success, was $result", result is TmLanguageParser.ParseResult.Success)
        return result as TmLanguageParser.ParseResult.Success
    }

    private fun failureOf(result: TmLanguageParser.ParseResult): TmLanguageParser.ParseResult.Failure {
        assertTrue("expected Failure, was $result", result is TmLanguageParser.ParseResult.Failure)
        return result as TmLanguageParser.ParseResult.Failure
    }

    /** Minimal but complete grammar: includes, keyword, comments, strings. */
    private fun miniGrammar(): String =
        """
        {
          "name": "MiniLang",
          "scopeName": "source.minilang",
          "patterns": [
            {"include": "#keywords"},
            {"name": "comment.line.double-slash.minilang", "match": "//.*$"},
            {"name": "comment.block.minilang", "begin": "/\\*", "end": "\\*/"},
            {"name": "string.quoted.double.minilang", "begin": "\"", "end": "\""}
          ],
          "repository": {
            "keywords": {
              "patterns": [
                {"name": "keyword.control.minilang", "match": "\\b(if|else|while)\\b"}
              ]
            }
          }
        }
        """.trimIndent()

    private fun grammarWithPatterns(
        patternsJson: String,
        repositoryJson: String = "",
    ): String =
        """
        {
          "scopeName": "source.test",
          "patterns": [$patternsJson],
          "repository": {$repositoryJson}
        }
        """.trimIndent()

    private fun keywordPattern(
        name: String,
        regex: String,
    ): String {
        // The regex arrives as a Kotlin string ("\b" = backslash + b). In
        // JSON text a bare "\b" is the BACKSPACE escape, which org.json
        // silently converts - corrupting the regex. Escape backslashes
        // (and quotes defensively) so JSON decodes back to the regex.
        val safe = regex.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"name\": \"$name\", \"match\": \"$safe\"}"
    }

    /** Include chain of [rules] repository rules ending in one keyword rule. */
    private fun chainGrammar(rules: Int): String {
        val repository = JSONObject()
        for (i in 0 until rules) {
            repository.put("r$i", JSONObject().put("include", "#r${i + 1}"))
        }
        repository.put(
            "r$rules",
            JSONObject().put("name", "keyword.other.chain").put("match", "\\bend\\b"),
        )
        val root =
            JSONObject()
                .put("scopeName", "source.chain")
                .put("patterns", JSONArray().put(JSONObject().put("include", "#r0")))
                .put("repository", repository)
        return root.toString()
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun happyPathExtractsEveryFieldExactly() {
        val result = TmLanguageParser.parse(miniGrammar(), "mini", listOf(".py"))
        val success = successOf(result)
        val language = success.language
        assertEquals("custom_mini", language.id)
        assertEquals("MiniLang", language.displayName)
        assertEquals("MiniLang", success.grammarName)
        assertEquals("source.minilang", success.scopeName)
        assertEquals(setOf("if", "else", "while"), language.keywords)
        assertEquals(listOf("//"), language.lineComments)
        assertEquals(listOf("/*" to "*/"), language.blockComments)
        assertEquals(listOf('"'), language.stringDelims)
        assertEquals(false, language.caseInsensitive)
        assertEquals(listOf("py"), language.extensions)
    }

    @Test
    fun includeResolvesRepositoryRule() {
        val json =
            grammarWithPatterns(
                """{"include": "#kw"}, """ + keywordPattern("keyword.other.x", "\\b(for)\\b"),
                "\"kw\": {\"patterns\": [" +
                    keywordPattern("keyword.control.y", "\\b(while)\\b") + "]}",
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(setOf("for", "while"), language.keywords)
    }

    // ------------------------------------------------------------- validity

    @Test
    fun plainTextIsNotJson() {
        val failure = failureOf(TmLanguageParser.parse("hello, world", "t", emptyList()))
        assertEquals(TmLanguageParser.ErrorCode.NOT_JSON, failure.code)
    }

    @Test
    fun jsonArrayRootIsNotGrammar() {
        val failure = failureOf(TmLanguageParser.parse("[1, 2, 3]", "t", emptyList()))
        assertEquals(TmLanguageParser.ErrorCode.NOT_GRAMMAR, failure.code)
    }

    @Test
    fun malformedGrammarObjectsAreRejected() {
        assertEquals(
            TmLanguageParser.ErrorCode.NOT_GRAMMAR,
            failureOf(TmLanguageParser.parse("{}", "t", emptyList())).code,
        )
        assertEquals(
            TmLanguageParser.ErrorCode.NOT_GRAMMAR,
            failureOf(
                TmLanguageParser.parse("""{"scopeName": "", "patterns": []}""", "t", emptyList()),
            ).code,
        )
        assertEquals(
            TmLanguageParser.ErrorCode.NOT_GRAMMAR,
            failureOf(TmLanguageParser.parse("""{"scopeName": "x"}""", "t", emptyList())).code,
        )
        assertEquals(
            TmLanguageParser.ErrorCode.NOT_GRAMMAR,
            failureOf(
                TmLanguageParser.parse("""{"scopeName": "x", "patterns": {}}""", "t", emptyList()),
            ).code,
        )
    }

    @Test
    fun emptyPatternsArrayParsesWithDefaults() {
        val json = """{"name": "Empty", "scopeName": "source.empty", "patterns": []}"""
        val language = successOf(TmLanguageParser.parse(json, "empty", emptyList())).language
        assertEquals("custom_empty", language.id)
        assertEquals("Empty", language.displayName)
        assertEquals(emptySet<String>(), language.keywords)
        assertEquals(emptyList<String>(), language.lineComments)
        assertEquals(emptyList<Pair<String, String>>(), language.blockComments)
        assertEquals(listOf('"', '\''), language.stringDelims)
    }

    @Test
    fun tooLargeFailsBeforeParsing() {
        val payload = String(CharArray(TmLanguageParser.MAX_JSON_CHARS + 1) { ' ' })
        val failure = failureOf(TmLanguageParser.parse(payload, "t", emptyList()))
        assertEquals(TmLanguageParser.ErrorCode.TOO_LARGE, failure.code)
    }

    // ------------------------------------------------- traversal & includes

    @Test
    fun selfReferencingIncludeTerminatesSuccessfully() {
        val json =
            grammarWithPatterns(
                """{"include": "#loop"}""",
                "\"loop\": {\"include\": \"#loop\"}",
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertTrue(language.keywords.isEmpty())
    }

    @Test
    fun patternBudgetBoundaryIsInclusive() {
        // Visits = 1 (top pattern) + rules chain rules + 1 final keyword rule.
        val ok = successOf(TmLanguageParser.parse(chainGrammar(4998), "t", emptyList()))
        assertEquals("custom_t", ok.language.id)
        val failure = failureOf(TmLanguageParser.parse(chainGrammar(4999), "t", emptyList()))
        assertEquals(TmLanguageParser.ErrorCode.PATTERN_BUDGET_EXCEEDED, failure.code)
        assertTrue(failure.message.contains("5000"))
    }

    @Test
    fun externalIncludeIsIgnoredButOwnPatternsStillWalk() {
        val json =
            grammarWithPatterns(
                """{"include": "source.js"}, """ + keywordPattern("keyword.control.x", "\\bfor\\b"),
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(setOf("for"), language.keywords)
    }

    @Test
    fun dollarBaseIncludeExpandsTopPatternsOnce() {
        val json =
            grammarWithPatterns(
                """{"include": "${'$'}base"}, """ + keywordPattern("keyword.control.x", "\\bif\\b"),
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(setOf("if"), language.keywords)
    }

    // ------------------------------------------------------------ keywords

    @Test
    fun keywordExtractionDominantAlternationStyle() {
        val json =
            grammarWithPatterns(keywordPattern("keyword.control.x", "\\b(?:true|false|null)\\b"))
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(setOf("true", "false", "null"), language.keywords)
    }

    @Test
    fun keywordExtractionAnchoredGroupAndSingleWord() {
        val json =
            grammarWithPatterns(
                keywordPattern("keyword.control.import", "^\\s*(import|from)\\b") + ", " +
                    keywordPattern("keyword.other.single", "\\bif\\b"),
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(setOf("import", "from", "if"), language.keywords)
    }

    @Test
    fun keywordExtractionRejectsOperatorsClassesAndPhrases() {
        val json =
            grammarWithPatterns(
                keywordPattern("keyword.operator.x", "\\+|\\d{2}") + ", " +
                    keywordPattern("keyword.other.phrase", "else if"),
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertTrue(language.keywords.isEmpty())
    }

    @Test
    fun keywordExtractionHandlesNestedAlternationGroups() {
        val json = grammarWithPatterns(keywordPattern("keyword.other.nested", "(a1|b2)|(c3|d4)"))
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(setOf("a1", "b2", "c3", "d4"), language.keywords)
    }

    @Test
    fun keywordCapAtMaxKeywords() {
        val regex = (0 until 5100).joinToString("|") { "w$it" }
        val json = grammarWithPatterns(keywordPattern("keyword.other.cap", regex))
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(TmLanguageParser.MAX_KEYWORDS, language.keywords.size)
        assertTrue("w0" in language.keywords)
        assertTrue("w4999" in language.keywords)
        assertTrue("w5000" !in language.keywords)
    }

    // ------------------------------------------------------------- comments

    @Test
    fun lineCommentExtractionVariantsAndPadding() {
        val json =
            grammarWithPatterns(
                keywordPattern("comment.line.double-slash", "//.*$") + ", " +
                    keywordPattern("comment.line.number-sign", "#.*") + ", " +
                    keywordPattern("comment.line.double-dash", "--[^\\n]*$") + ", " +
                    keywordPattern("comment.line.padded", "^[ \\t]*//.*"),
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(listOf("//", "#", "--"), language.lineComments)
    }

    @Test
    fun lineCommentRejectedWhenLiteralIsNotPunctuation() {
        val json = grammarWithPatterns(keywordPattern("comment.line.rem", "REM.*"))
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertTrue(language.lineComments.isEmpty())
    }

    @Test
    fun blockCommentExtractionAndRejection() {
        val json =
            grammarWithPatterns(
                "{\"name\": \"comment.block.c\", \"begin\": \"/\\\\*\", \"end\": \"\\\\*/\"}, " +
                    "{\"name\": \"comment.block.html\", \"begin\": \"<!--\", \"end\": \"-->\"}, " +
                    "{\"name\": \"comment.block.bad\", \"begin\": \"/\\\\*\", \"end\": \"[^*]\"}",
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(listOf("/*" to "*/", "<!--" to "-->"), language.blockComments)
    }

    @Test
    fun blockCommentCapIsFourPairs() {
        // Five distinct punctuation-only pairs; only the first four survive.
        val markers = listOf("<<" to ">>", "%%" to "%%", "!!" to "!!", "@@" to "@@", ";;" to ";;")
        val patterns =
            markers
                .mapIndexed { i, (open, close) ->
                    "{\"name\": \"comment.block.m$i\", \"begin\": \"$open\", \"end\": \"$close\"}"
                }.joinToString(", ")
        val parsed = TmLanguageParser.parse(grammarWithPatterns(patterns), "t", emptyList())
        val language = successOf(parsed).language
        assertEquals(TmLanguageParser.MAX_BLOCK_COMMENTS, language.blockComments.size)
        assertEquals("<<" to ">>", language.blockComments.first())
    }

    // -------------------------------------------------------------- strings

    @Test
    fun stringDelimiterExtractionAndDefaults() {
        val both =
            grammarWithPatterns(
                "{\"name\": \"string.quoted.double.x\", \"begin\": \"\\\"\", \"end\": \"\\\"\"}, " +
                    "{\"name\": \"string.quoted.single.x\", \"begin\": \"'\", \"end\": \"'\"}",
            )
        assertEquals(
            listOf('"', '\''),
            successOf(TmLanguageParser.parse(both, "t", emptyList())).language.stringDelims,
        )
        val failExtract =
            grammarWithPatterns(
                "{\"name\": \"string.quoted.other.x\", \"begin\": \"[[\", \"end\": \"]]\"}",
            )
        assertEquals(
            listOf('"'),
            successOf(TmLanguageParser.parse(failExtract, "t", emptyList())).language.stringDelims,
        )
        val none = grammarWithPatterns(keywordPattern("keyword.other.x", "\\bif\\b"))
        assertEquals(
            listOf('"', '\''),
            successOf(TmLanguageParser.parse(none, "t", emptyList())).language.stringDelims,
        )
    }

    // ------------------------------------------------- identity & robustness

    @Test
    fun extensionNormalizationAndIdentityFallbacks() {
        val noName = """{"scopeName": "source.noid", "patterns": []}"""
        val success = successOf(TmLanguageParser.parse(noName, "", listOf("PY", ".py")))
        assertEquals("custom_grammar", success.language.id)
        assertEquals("source.noid", success.language.displayName)
        assertEquals("source.noid", success.grammarName)
        assertEquals(listOf("py"), success.language.extensions)
    }

    @Test
    fun caseInsensitiveScopePrefixMatching() {
        val json =
            grammarWithPatterns(
                keywordPattern("Keyword.Control.x", "\\bif\\b") + ", " +
                    keywordPattern("COMMENT.LINE.REM", "%.*") + ", " +
                    "{\"name\": \"Comment.Block.x\", \"begin\": \"/\\\\*\", \"end\": \"\\\\*/\"}, " +
                    "{\"name\": \"String.Quoted.Single.x\", \"begin\": \"'\", \"end\": \"'\"}",
            )
        val language = successOf(TmLanguageParser.parse(json, "t", emptyList())).language
        assertEquals(setOf("if"), language.keywords)
        assertEquals(listOf("%"), language.lineComments)
        assertEquals(listOf("/*" to "*/"), language.blockComments)
        assertEquals(listOf('\''), language.stringDelims)
    }

    @Test
    fun parseNeverThrowsOnOddInput() {
        val oddInputs =
            listOf(
                "",
                "   ",
                "{",
                "null",
                "123",
                "\"a string\"",
                "[]",
                "{\"patterns\": [null, 5, \"text\", [], {}]}",
                "{\"scopeName\": 5, \"patterns\": []}",
                "{\"scopeName\": \"a\", \"patterns\": [{\"name\": 5, \"match\": 7}]}",
            )
        for (input in oddInputs) {
            val result = TmLanguageParser.parse(input, "t", emptyList())
            assertTrue(
                "expected a result for \"$input\"",
                result is TmLanguageParser.ParseResult.Success ||
                    result is TmLanguageParser.ParseResult.Failure,
            )
        }
    }
}
