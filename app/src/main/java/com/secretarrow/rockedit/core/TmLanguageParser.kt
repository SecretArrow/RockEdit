package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Best-effort importer for TextMate grammars in JSON form (the .tmLanguage
 * JSON dialect exported by editor extensions). Extracts a plain
 * [SyntaxLanguage] (keywords, comment and string delimiters) that the
 * built-in tokenizer can render. This is deliberately NOT a full TextMate
 * engine: the goal is useful highlighting for languages Rock Edit does not
 * ship, not perfect emulation. Pure JVM, no Android dependencies, never
 * throws for any input string.
 *
 * Extraction decisions (numbered; mirrored 1:1 by scripts/v017_tmlanguage_verify.py):
 * 1. Validity: a payload longer than [MAX_JSON_CHARS] characters fails with
 *    [ErrorCode.TOO_LARGE] (checked before parsing; characters, not bytes).
 *    Text no JSON parser accepts yields [ErrorCode.NOT_JSON]; text that only
 *    parses as a JSON array yields [ErrorCode.NOT_GRAMMAR]. An object must
 *    carry a non-empty string "scopeName" and a "patterns" array (the array
 *    MAY be empty - a grammar without useful patterns still parses to a
 *    language with default string delimiters), otherwise [ErrorCode.NOT_GRAMMAR].
 * 2. Traversal: patterns are walked with an explicit worklist (no recursion,
 *    so pathological nesting cannot overflow the stack). Includes of the
 *    forms "#name" (repository lookup) and "$base" / "$self" (the top-level
 *    patterns) are followed; every other include (external scope references
 *    such as "source.js") is ignored. Each repository key - and the pseudo
 *    keys "$base"/"$self" - is expanded at most once, which breaks cycles and
 *    collapses diamond includes. Every visited pattern object counts against
 *    [MAX_PATTERNS_VISITED]; exceeding the budget fails with
 *    [ErrorCode.PATTERN_BUDGET_EXCEEDED] instead of hanging. An internal
 *    include takes precedence over the rule's own "patterns"; an unresolved
 *    or external include falls back to those patterns.
 * 3. Keywords: a pattern whose scope name (case-insensitively) starts with
 *    "keyword." and that has a "match" regex contributes candidate words.
 *    The regex is split on top-level unescaped "|" alternatives; a single
 *    group whose prefix and suffix are only anchors/boundaries/quantifiers is
 *    descended into (the dominant VS Code style: backslash-b, parenthesized
 *    alternation, backslash-b); each leaf is stripped of escapes and anchors
 *    and kept when the remainder is a pure word (letters, digits, underscore,
 *    length at least 2). Capped at [MAX_KEYWORDS] words.
 * 4. Line comments: scope starting "comment.line" with a "match" regex - the
 *    longest literal prefix (after skipping zero-width padding: anchors,
 *    any-char dots, character classes, shorthand escapes and their
 *    quantifiers) is kept when it is 1-3 punctuation characters, e.g. a
 *    double slash, a hash, a double dash or a semicolon. Capped at
 *    [MAX_LINE_COMMENTS] distinct strings.
 * 5. Block comments: scope starting "comment.block" with "begin" and "end" -
 *    the same literal-prefix extraction runs on both sides; a pair is kept
 *    when both extract cleanly (1-6 punctuation characters each, so a
 *    C-style open of slash-star with a close of star-slash maps to the
 *    literal open/close markers and HTML-style 4-character markers survive).
 *    Capped at [MAX_BLOCK_COMMENTS] pairs.
 * 6. String delimiters: scope starting "string.quoted" with a "begin" regex -
 *    the first character of its literal prefix is kept when it is a quote
 *    character. When any string.quoted pattern exists but nothing extracts,
 *    the double quote is always included; when the grammar has no string
 *    pattern at all, the [SyntaxLanguage] default (double and single quote)
 *    is left untouched.
 * 7. Extensions: normalized by [normalizeExtension] (trimmed, lowercased,
 *    leading dots and stars stripped) and attached to the returned language
 *    so [SyntaxRegistry.languageForFileName] can match them. The returned
 *    language id is [CUSTOM_ID_PREFIX] plus the requestedId (a blank
 *    requestedId falls back to "grammar"); the display name is the grammar
 *    "name" field, falling back to the scopeName.
 * 8. caseInsensitive is always false in v1 (case-related grammar fields such
 *    as "foldingOffSide" are ignored; documented decision).
 *
 * Failure cases (every input string produces a result, nothing is thrown;
 * the JSON parse boundary catches Throwable because org.json can raise a
 * StackOverflowError on absurdly nested payloads - everything after the
 * parse uses only non-throwing opt* accessors):
 * - [ErrorCode.NOT_JSON]: the text is not JSON at all.
 * - [ErrorCode.NOT_GRAMMAR]: JSON, but not a grammar object (array root,
 *   missing or empty scopeName, missing or non-array patterns).
 * - [ErrorCode.TOO_LARGE]: payload above [MAX_JSON_CHARS] characters.
 * - [ErrorCode.PATTERN_BUDGET_EXCEEDED]: traversal visited more than
 *   [MAX_PATTERNS_VISITED] pattern objects (cycle or pathological grammar);
 *   the message names the JSON path where the budget ran out.
 */
object TmLanguageParser {
    const val MAX_JSON_CHARS = 2_000_000
    const val MAX_PATTERNS_VISITED = 5_000
    const val MAX_KEYWORDS = 5_000
    const val MAX_LINE_COMMENTS = 4
    const val MAX_BLOCK_COMMENTS = 4
    const val CUSTOM_ID_PREFIX = "custom_"

    /** Depth budget for alternative splitting / group descent on one regex. */
    private const val MAX_KEYWORD_DEPTH = 4

    /** Accepted marker length: line comments 1-3, block comments 1-6. */
    private const val MAX_LINE_TOKEN_CHARS = 3
    private const val MAX_BLOCK_TOKEN_CHARS = 6

    sealed class ParseResult {
        data class Success(
            val language: SyntaxLanguage,
            val scopeName: String,
            val grammarName: String,
        ) : ParseResult()

        data class Failure(
            val code: ErrorCode,
            val message: String,
        ) : ParseResult()
    }

    enum class ErrorCode { NOT_JSON, NOT_GRAMMAR, TOO_LARGE, PATTERN_BUDGET_EXCEEDED }

    /**
     * Parses a TextMate grammar in JSON form and extracts a best-effort
     * [SyntaxLanguage]. Throws nothing for any input string; every rejection
     * is reported as a [ParseResult.Failure] whose message names the rule
     * (JSON path) that rejected the payload.
     */
    fun parse(
        json: String,
        requestedId: String,
        extensions: List<String>,
    ): ParseResult {
        if (json.length > MAX_JSON_CHARS) {
            return ParseResult.Failure(
                ErrorCode.TOO_LARGE,
                "root: ${json.length} characters, limit is $MAX_JSON_CHARS",
            )
        }
        val root =
            try {
                JSONObject(json)
            } catch (_: Throwable) {
                val isArray =
                    try {
                        JSONArray(json)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                return if (isArray) {
                    ParseResult.Failure(
                        ErrorCode.NOT_GRAMMAR,
                        "root: JSON array, expected a grammar object",
                    )
                } else {
                    ParseResult.Failure(ErrorCode.NOT_JSON, "root: input is not valid JSON")
                }
            }
        val scopeName = root.opt("scopeName") as? String
        if (scopeName.isNullOrEmpty()) {
            return ParseResult.Failure(
                ErrorCode.NOT_GRAMMAR,
                "root.scopeName: missing, non-string or empty",
            )
        }
        val rootPatterns =
            root.optJSONArray("patterns")
                ?: return ParseResult.Failure(
                    ErrorCode.NOT_GRAMMAR,
                    "root.patterns: missing or not an array",
                )
        val state = WalkState(rootPatterns, root.optJSONObject("repository") ?: JSONObject())
        val overflowPath = walk(state)
        if (overflowPath != null) {
            return ParseResult.Failure(
                ErrorCode.PATTERN_BUDGET_EXCEEDED,
                "pattern budget of $MAX_PATTERNS_VISITED exceeded at $overflowPath " +
                    "(cycle or pathological grammar)",
            )
        }
        val grammarName = (root.opt("name") as? String)?.takeIf { it.isNotEmpty() } ?: scopeName
        val normalizedExtensions =
            extensions.mapNotNull { normalizeExtension(it).ifEmpty { null } }.distinct()
        val requested = requestedId.trim().ifEmpty { "grammar" }
        val language =
            SyntaxLanguage(
                id = CUSTOM_ID_PREFIX + requested,
                displayName = grammarName,
                keywords = state.keywords,
                lineComments = state.lineComments.toList(),
                blockComments = state.blockComments.toList(),
                stringDelims = stringDelims(state),
                caseInsensitive = false,
                extensions = normalizedExtensions,
            )
        return ParseResult.Success(language, scopeName, grammarName)
    }

    /**
     * Normalizes one file extension for matching: trimmed, lowercased, with
     * leading dots and stars stripped, so ".py", "PY" and "*.py" all become
     * "py". Inner dots are kept as-is.
     */
    fun normalizeExtension(raw: String): String = raw.trim().lowercase().trimStart('.', '*')

    // ------------------------------------------------------------ traversal

    /** Mutable accumulation state for one parse run. */
    private class WalkState(
        val rootPatterns: JSONArray,
        val repository: JSONObject,
    ) {
        var visited: Int = 0
        val expandedIncludes = HashSet<String>()
        val keywords = LinkedHashSet<String>()
        val lineComments = LinkedHashSet<String>()
        val blockComments = LinkedHashSet<Pair<String, String>>()
        val stringQuotes = LinkedHashSet<Char>()
        var sawStringQuoted: Boolean = false
    }

    /**
     * Iterative depth-first walk in document order. Returns the JSON path of
     * the pattern that exhausted the budget, or null on success.
     */
    private fun walk(state: WalkState): String? {
        val stack = ArrayDeque<Pair<JSONObject, String>>()
        pushAll(stack, state.rootPatterns, "patterns")
        while (stack.isNotEmpty()) {
            val (obj, path) = stack.removeLast()
            state.visited += 1
            if (state.visited > MAX_PATTERNS_VISITED) return path
            extract(obj, state)
            if (!followInclude(obj, path, state, stack)) {
                val nested = obj.optJSONArray("patterns")
                if (nested != null) pushAll(stack, nested, "$path.patterns")
            }
        }
        return null
    }

    /** Pushes the objects of [arr] so index 0 is processed first. */
    private fun pushAll(
        stack: ArrayDeque<Pair<JSONObject, String>>,
        arr: JSONArray,
        path: String,
    ) {
        for (i in arr.length() - 1 downTo 0) {
            val obj = arr.optJSONObject(i) ?: continue
            stack.addLast(obj to "$path[$i]")
        }
    }

    /**
     * Resolves one include reference, pushing the target onto [stack].
     * Returns true when the include was handled (internal or already
     * expanded) so the rule's own patterns are skipped, false when the
     * reference is missing or external and the own patterns should be walked.
     */
    private fun followInclude(
        obj: JSONObject,
        path: String,
        state: WalkState,
        stack: ArrayDeque<Pair<JSONObject, String>>,
    ): Boolean {
        val include = obj.opt("include") as? String ?: return false
        return when {
            include.startsWith("#") -> {
                val key = include.substring(1)
                val rule = state.repository.optJSONObject(key) ?: return false
                if (!state.expandedIncludes.add(key)) return true
                stack.addLast(rule to "$path.include($key)")
                true
            }
            include == "\$base" || include == "\$self" -> {
                if (!state.expandedIncludes.add(include)) return true
                pushAll(stack, state.rootPatterns, "$path.include($include)")
                true
            }
            else -> false // external scope reference: ignored (documented)
        }
    }

    /** Classifies one visited pattern and feeds the matching collector. */
    private fun extract(
        obj: JSONObject,
        state: WalkState,
    ) {
        val scope = obj.opt("name") as? String ?: return
        val match = obj.opt("match") as? String
        val begin = obj.opt("begin") as? String
        val end = obj.opt("end") as? String
        val lower = scope.lowercase()
        when {
            lower.startsWith("keyword.") && match != null -> collectKeywords(match, state)
            lower.startsWith("comment.line") && match != null -> collectLineComment(match, state)
            lower.startsWith("comment.block") && begin != null && end != null ->
                collectBlockComment(begin, end, state)
            lower.startsWith("string.quoted") -> collectStringQuote(begin, state)
        }
    }

    private fun stringDelims(state: WalkState): List<Char> =
        when {
            state.stringQuotes.isNotEmpty() -> state.stringQuotes.toList()
            state.sawStringQuoted -> listOf('"')
            else -> listOf('"', '\'')
        }

    // ------------------------------------------------------- keyword rules

    private fun collectKeywords(
        regex: String,
        state: WalkState,
    ) {
        if (state.keywords.size >= MAX_KEYWORDS) return
        collectKeywordWords(regex, 0, state.keywords)
    }

    private fun collectKeywordWords(
        regex: String,
        depth: Int,
        out: LinkedHashSet<String>,
    ) {
        if (depth > MAX_KEYWORD_DEPTH) return
        val parts = splitAlternatives(regex)
        if (parts.size > 1) {
            for (part in parts) collectKeywordWords(part, depth + 1, out)
            return
        }
        val only = parts[0]
        val inner = coreGroupBody(only)
        if (inner != null) {
            collectKeywordWords(inner, depth + 1, out)
            return
        }
        val word = literalKeywordWord(only)
        if (word != null && out.size < MAX_KEYWORDS) out.add(word)
    }

    /** Splits on unescaped "|" at paren depth 0, outside character classes. */
    private fun splitAlternatives(regex: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var inClass = false
        var i = 0
        while (i < regex.length) {
            val c = regex[i]
            if (c == '\\') {
                current.append(c)
                if (i + 1 < regex.length) {
                    current.append(regex[i + 1])
                    i += 2
                } else {
                    i += 1
                }
                continue
            }
            when {
                inClass -> {
                    if (c == ']') inClass = false
                    current.append(c)
                }
                c == '[' -> {
                    inClass = true
                    current.append(c)
                }
                c == '(' -> {
                    depth += 1
                    current.append(c)
                }
                c == ')' -> {
                    if (depth > 0) depth -= 1
                    current.append(c)
                }
                c == '|' && depth == 0 -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i += 1
        }
        parts.add(current.toString())
        return parts
    }

    /**
     * Returns the body of the first group whose prefix and suffix are only
     * wrapper metacharacters (anchors, boundaries, quantifiers, padding), or
     * null when there is no such group. Group introducers such as "(?:" are
     * excluded from the returned body.
     */
    private fun coreGroupBody(regex: String): String? {
        var i = 0
        var inClass = false
        while (i < regex.length) {
            val c = regex[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (inClass) {
                if (c == ']') inClass = false
                i += 1
                continue
            }
            if (c == '[') {
                inClass = true
            } else if (c == '(') {
                val close = matchingParen(regex, i)
                if (close != null) {
                    val introEnd = introducerEnd(regex, i)
                    if (introEnd < close &&
                        isWrapper(regex.substring(0, i)) &&
                        isWrapper(regex.substring(close + 1))
                    ) {
                        return regex.substring(introEnd, close)
                    }
                }
            }
            i += 1
        }
        return null
    }

    /** Index of the ")" matching the "(" at [open], or null. */
    private fun matchingParen(
        regex: String,
        open: Int,
    ): Int? {
        var depth = 0
        var inClass = false
        var i = open
        while (i < regex.length) {
            val c = regex[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (inClass) {
                if (c == ']') inClass = false
                i += 1
                continue
            }
            when (c) {
                '[' -> inClass = true
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
            i += 1
        }
        return null
    }

    /** End index (exclusive) of a group introducer: "(", "(?:" and friends. */
    private fun introducerEnd(
        regex: String,
        open: Int,
    ): Int {
        if (open + 1 >= regex.length) return open + 1
        if (regex[open + 1] != '?') return open + 1
        if (open + 2 < regex.length && regex[open + 2] == ':') return open + 3
        if (open + 2 < regex.length && (regex[open + 2] == '<' || regex[open + 2] == '\'')) {
            val closer = if (regex[open + 2] == '<') '>' else '\''
            var j = open + 3
            while (j < regex.length && regex[j] != closer) j += 1
            return if (j < regex.length) j + 1 else regex.length
        }
        return open + 2
    }

    /**
     * True when [segment] contains only zero-width regex plumbing: anchors,
     * any-char dots, quantifiers and escape pairs (boundaries, shorthands).
     */
    private fun isWrapper(segment: String): Boolean {
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '\\') {
                if (i + 1 >= segment.length) return false
                i += 2
                continue
            }
            if (c !in "^$. *+?\t") return false
            i += 1
        }
        return true
    }

    /**
     * A leaf alternative yields a keyword only when it is a plain literal:
     * word characters plus optional anchors, with letter/digit escapes
     * (classes, boundaries) dropped and everything else rejected.
     */
    private fun literalKeywordWord(alternative: String): String? {
        if (alternative.length < 2) return null
        val word = StringBuilder()
        var i = 0
        while (i < alternative.length) {
            val c = alternative[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= alternative.length) return null
                    if (!alternative[i + 1].isLetterOrDigit()) return null
                    i += 2
                }
                c == '^' || c == '$' -> i += 1
                c.isLetterOrDigit() || c == '_' -> {
                    word.append(c)
                    i += 1
                }
                else -> return null
            }
        }
        val result = word.toString()
        return if (result.length >= 2) result else null
    }

    // ------------------------------------------------- comment / string rules

    private fun collectLineComment(
        regex: String,
        state: WalkState,
    ) {
        if (state.lineComments.size >= MAX_LINE_COMMENTS) return
        val token = literalPrefix(regex)
        if (isPunctToken(token, MAX_LINE_TOKEN_CHARS)) state.lineComments.add(token)
    }

    private fun collectBlockComment(
        begin: String,
        end: String,
        state: WalkState,
    ) {
        if (state.blockComments.size >= MAX_BLOCK_COMMENTS) return
        val openToken = literalPrefix(begin)
        val closeToken = literalPrefix(end)
        if (isPunctToken(openToken, MAX_BLOCK_TOKEN_CHARS) &&
            isPunctToken(closeToken, MAX_BLOCK_TOKEN_CHARS)
        ) {
            state.blockComments.add(openToken to closeToken)
        }
    }

    private fun collectStringQuote(
        begin: String?,
        state: WalkState,
    ) {
        state.sawStringQuoted = true
        if (begin == null) return
        val literal = literalPrefix(begin)
        if (literal.isEmpty()) return
        val quote = literal[0]
        if (quote == '"' || quote == '\'') state.stringQuotes.add(quote)
    }

    /**
     * Longest literal prefix of a regex: zero-width padding (anchors, dots,
     * classes, shorthand escapes and quantifiers) is skipped first, then the
     * run of literal characters is taken until the first metacharacter.
     */
    private fun literalPrefix(regex: String): String {
        val literal = StringBuilder()
        var i = skipPadding(regex)
        while (i < regex.length) {
            val c = regex[i]
            if (c == '\\') {
                if (i + 1 >= regex.length) break
                val n = regex[i + 1]
                if (n.isLetterOrDigit()) break
                literal.append(n)
                i += 2
                continue
            }
            if (c == '^' ||
                c == '$' ||
                c == '.' ||
                c == '*' ||
                c == '+' ||
                c == '?' ||
                c == '|' ||
                c == '(' ||
                c == ')' ||
                c == '[' ||
                c == ']' ||
                c == '{' ||
                c == '}'
            ) {
                break
            }
            if (c.isWhitespace()) break
            literal.append(c)
            i += 1
        }
        return literal.toString()
    }

    /** Skips a leading run of zero-width / padding elements. */
    private fun skipPadding(regex: String): Int {
        var i = 0
        while (i < regex.length) {
            val c = regex[i]
            when {
                c == '^' || c == '$' || c == '.' || c == '*' || c == '+' || c == '?' -> i += 1
                c == '{' -> {
                    val close = regex.indexOf('}', i)
                    i = if (close >= 0) close + 1 else regex.length
                }
                c == '[' -> {
                    val close = classClose(regex, i) ?: return i
                    i = close + 1
                }
                c == '\\' && i + 1 < regex.length && regex[i + 1] in "sSbBnrt " -> i += 2
                else -> return i
            }
        }
        return i
    }

    /** Index of the unescaped "]" closing the class opened at [open]. */
    private fun classClose(
        regex: String,
        open: Int,
    ): Int? {
        var i = open + 1
        while (i < regex.length) {
            when (regex[i]) {
                '\\' -> i += 2
                ']' -> return i
                else -> i += 1
            }
        }
        return null
    }

    /** 1-[maxChars] characters, none of them a letter, digit or whitespace. */
    private fun isPunctToken(
        token: String,
        maxChars: Int,
    ): Boolean =
        token.isNotEmpty() &&
            token.length <= maxChars &&
            token.all { !it.isLetterOrDigit() && !it.isWhitespace() }
}
