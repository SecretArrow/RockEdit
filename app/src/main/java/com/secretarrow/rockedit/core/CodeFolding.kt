package com.secretarrow.rockedit.core

/**
 * Machine-readable failure categories of the folding engine. The UI maps
 * each code to a localized message; every failure also carries an
 * informative English [FoldResult.Failure.message] (what happened and why).
 */
enum class FoldErrorCode {
    INPUT_TOO_LARGE,
    NO_FOLD_RANGE,
    REGION_CONTAINS_PLACEHOLDER,
    NOT_A_PLACEHOLDER,
    TOO_MANY_FOLDS,
    PLACEHOLDER_AMBIGUOUS,
    REGION_GONE,
}

/**
 * The single result contract of every folding operation.
 *
 * - [Done]: the operation completed; [Done.text] is the new buffer content
 *   (always `\n`-separated) and [Done.hiddenLines] is the number of body
 *   lines hidden by THIS operation (fold) or restored by it (unfold).
 * - [Failure]: the operation was refused; the archive is untouched unless
 *   explicitly documented otherwise (REGION_GONE pruning).
 */
sealed class FoldResult {
    data class Done(
        val text: String,
        val hiddenLines: Int,
    ) : FoldResult()

    data class Failure(
        val code: FoldErrorCode,
        val message: String,
    ) : FoldResult()
}

/**
 * A foldable line range, 0-based INCLUSIVE on both ends. Invariant:
 * endLine > startLine, so every range hides at least one body line. The
 * opener line (startLine) stays visible when the range is folded.
 */
data class FoldRange(
    val startLine: Int,
    val endLine: Int,
)

/**
 * Code folding engine (backlog P0#4, v0.14.0): computes foldable line ranges,
 * replaces the body of a folded region with a single placeholder line, and
 * restores the original lines on unfold. Pure JVM, no Android dependencies,
 * fully unit-testable. Bracket pair colorization is out of scope (postponed).
 *
 * Structure detection:
 * - Brace family (every id that is not an indentation-style language,
 *   including unknown ids, via [FormatterLanguages.braceConfigFor]):
 *   `{`/`}` pairs are matched with a stack. Single-line strings (with
 *   backslash escapes), multiline strings (`"""..."""`, backticks), line
 *   comments and block comments are skipped with cross-line state, mirroring
 *   the shared LineScanner used by the formatters. Folding uses its own
 *   character scanner because it needs the ORDERED brace events of each line
 *   to pair openers/closers on mixed lines such as `} else {` — the net
 *   delta reported by LineScanner cannot express that. An opener/closer pair
 *   on the SAME line yields no range (at least one body line is required);
 *   braces without a partner are ignored silently, never an error.
 * - Indentation family (python, vyper, ruby, lua, elixir, julia, latex — the
 *   languages profiled by [FormatterLanguages.indentConfigFor]): a non-blank
 *   line with indent I opens a block over the following lines with indent
 *   greater than I. Blank lines at the END of a block are excluded, blank
 *   lines in the MIDDLE are included, and at least one non-blank body line
 *   is required.
 *
 * Placeholder: folding a range hides its body lines (startLine + 1 up to and
 * including endLine — the opener line stays visible) and inserts one line of
 * the form: indentation of the opener + [PLACEHOLDER_PREFIX] + hidden count
 * + [PLACEHOLDER_SUFFIX], e.g. `    ⟦⋯ 12 ⟧`. The opener indentation is kept
 * so folded buffers keep their visual outline.
 *
 * Documented decisions and assumptions (v1):
 * - Light statefulness: the engine archives the removed body lines keyed by
 *   the exact placeholder line. Conceptually a Map<String, List<String>> in
 *   insertion order; when two sibling folds produce the SAME placeholder
 *   text, the key holds a queue of archives and unfolding consumes the most
 *   recently inserted one (LIFO). That makes the foldAll -> unfoldAll round
 *   trip exact even for identical siblings (archive insertion is top-to-
 *   bottom, restore is bottom-up); the residual limitation is that
 *   unfoldAtLine on one of two identical placeholders may restore the
 *   sibling's body — inherent to text-keyed archives, accepted for v1.
 * - [activeFoldCount] is the archive size; the fold that would create the
 *   257th active fold fails with TOO_MANY_FOLDS (message carries the actual
 *   count).
 * - PLACEHOLDER_AMBIGUOUS: if the input text already contains a line
 *   matching the placeholder pattern that is NOT backed by an archive entry,
 *   fold operations refuse to run (protects the archive from restoring
 *   foreign content). The check runs once per operation over the whole text.
 *   Ownership is tracked per placeholder TEXT, not per position: an exact
 *   text match in the archive legitimizes every occurrence of that line.
 * - REGION_CONTAINS_PLACEHOLDER: a region about to be folded must not
 *   contain any placeholder line (nested folds are not supported; unfold
 *   first). Text edits OUTSIDE a folded region between fold and unfold are
 *   allowed: restore matches the placeholder text, not positions.
 * - REGION_GONE: unfoldAtLine on a placeholder line without archive backing
 *   fails and prunes archive entries whose placeholder no longer occurs in
 *   the text, so stale state cannot leak. unfoldAll never fails: entries
 *   whose placeholder is no longer found are dropped silently, so after
 *   unfoldAll the archive is always empty and hiddenLines is the total
 *   number of restored lines.
 * - NOT_A_PLACEHOLDER: unfoldAtLine on a line that is not a placeholder
 *   (including out-of-range lines).
 * - INPUT_TOO_LARGE: inputs above [MAX_TEXT_CHARS] characters are rejected
 *   by every FoldResult operation (message contains the actual size);
 *   [computeRanges] mirrors the cap by returning an empty list. Empty text
 *   is not an error: [computeRanges] returns an empty list and unfold
 *   operations succeed with zero hidden lines.
 * - Range bookkeeping: ranges sharing even a single line count as
 *   overlapping, so a `} else {` chain keeps only the outermost range in
 *   [computeRanges]; foldAtLine can still target the inner one because it
 *   selects from the full (nested) range tree.
 * - Perf guards (documented): brace scanning gives up once more than 512
 *   braces are open at once and returns the pairs found so far; indentation
 *   scanning is a single O(n) stack pass. computeRanges on oversized input
 *   returns an empty list instead of scanning.
 * - Text is processed as-is apart from `\r\n` -> `\n` normalization; results
 *   always use `\n`. Line numbers are indexes into the normalized lines
 *   (0-based). Indentation counts leading spaces/tabs as one character each
 *   (no tab expansion); comment-only lines in the indentation family are
 *   counted purely by their indentation, and strings are NOT tracked there
 *   (v1: a multi-line string is scanned line by line, like grep).
 * - Every public function catches Throwable as a last-resort defense and
 *   reports Failure(NO_FOLD_RANGE, "internal error: ...") — computeRanges
 *   returns an empty list instead — because the public API must never throw
 *   and no other error code describes an impossible state. That path is not
 *   expected to ever run.
 * - Not thread safe by design: use one instance per editor buffer.
 *
 * @property languageId the language id exactly as passed to the constructor
 *   (lookups normalize it by trimming and lowercasing internally).
 */
class CodeFolding(
    languageId: String,
) {
    val languageId: String = languageId

    private val normalizedLanguageId = languageId.trim().lowercase()

    /**
     * Restore archive: full placeholder line -> removed body lines. Keys are
     * UNIQUE per active fold: when two folds would show the same placeholder
     * text (same hidden-line count), the newer one gets a `#<n>` ordinal
     * (see [applyFold]), so unfolding always finds the exact body — the
     * pairing can never swap two equal-looking folds.
     */
    private val archive = LinkedHashMap<String, ArrayDeque<List<String>>>()

    /** Monotonic source of `#<n>` ordinals for disambiguated placeholders. */
    private var nextFoldId = 1

    private val usesIndentStyle = normalizedLanguageId in INDENT_STYLE_LANGUAGE_IDS

    private val braceConfig = FormatterLanguages.braceConfigFor(normalizedLanguageId)

    /**
     * Computes the OUTERMOST (non-overlapping) fold ranges of [text], sorted
     * by start line ascending. Never throws; returns an empty list for empty
     * text, oversized text (see [MAX_TEXT_CHARS]) or as a last-resort
     * defense. Nested regions are intentionally not reported here —
     * [foldAtLine] selects from the full tree internally.
     */
    fun computeRanges(text: String): List<FoldRange> =
        try {
            if (text.length > MAX_TEXT_CHARS) {
                emptyList()
            } else {
                val lines = normalize(text).split('\n')
                outermostRanges(allRanges(lines))
            }
        } catch (t: Throwable) {
            // Last-resort defense; must never happen (see class KDoc).
            emptyList()
        }

    /**
     * Folds every outermost region at once. Atomic: on any failure nothing
     * is folded and the archive is untouched. [FoldResult.Done.hiddenLines]
     * is the total number of body lines hidden.
     */
    fun foldAll(text: String): FoldResult =
        guarded(text) { input ->
            if (input.length > MAX_TEXT_CHARS) return@guarded tooLarge(input.length)
            val lines = normalize(input).split('\n')
            foreignPlaceholder(lines)?.let { return@guarded ambiguous(it) }
            val ranges = outermostRanges(allRanges(lines))
            if (ranges.isEmpty()) {
                return@guarded noFoldRange(
                    "no foldable region detected in \"$normalizedLanguageId\" text",
                )
            }
            for (range in ranges) {
                val offender = firstPlaceholderIn(lines, range)
                if (offender >= 0) {
                    return@guarded containsPlaceholder(range, offender)
                }
            }
            val total = activeFoldCount() + ranges.size
            if (total > MAX_ACTIVE_FOLDS) {
                return@guarded tooManyFolds(total, ranges.size)
            }
            val result = lines.toMutableList()
            var hidden = 0
            for (range in ranges.sortedByDescending { it.startLine }) {
                hidden += applyFold(result, range)
            }
            FoldResult.Done(result.joinToString("\n"), hidden)
        }

    /**
     * Unfolds every placeholder still found in [text], line by line from the
     * bottom up so that earlier line indexes stay stable. Archive entries
     * whose placeholder is no longer found are dropped silently, so this
     * always returns [FoldResult.Done] (hiddenLines = total restored lines)
     * unless the input is oversized or an internal error occurs.
     */
    fun unfoldAll(text: String): FoldResult =
        guarded(text) { input ->
            if (input.length > MAX_TEXT_CHARS) return@guarded tooLarge(input.length)
            val lines = normalize(input).split('\n').toMutableList()
            var restored = 0
            for (i in lines.indices.reversed()) {
                val current = lines[i]
                if (!isPlaceholderLine(current)) continue
                val body = takeArchive(current) ?: continue // foreign placeholder: left as-is
                lines.removeAt(i)
                lines.addAll(i, body)
                restored += body.size
            }
            pruneOrphans(lines)
            FoldResult.Done(lines.joinToString("\n"), restored)
        }

    /**
     * Folds the deepest region that STARTS at [line] (0-based); if none
     * starts there, the deepest region containing it (startLine < line <=
     * endLine). Fails with NO_FOLD_RANGE when [line] is out of range or no
     * region matches, REGION_CONTAINS_PLACEHOLDER when the chosen region
     * holds a placeholder, TOO_MANY_FOLDS when the archive is full.
     */
    fun foldAtLine(
        text: String,
        line: Int,
    ): FoldResult =
        guarded(text) { input ->
            if (input.length > MAX_TEXT_CHARS) return@guarded tooLarge(input.length)
            val lines = normalize(input).split('\n')
            foreignPlaceholder(lines)?.let { return@guarded ambiguous(it) }
            if (line < 0 || line >= lines.size) {
                return@guarded noFoldRange(
                    "line $line is outside the text (0..${lines.lastIndex}); " +
                        "no fold region can start there",
                )
            }
            val all = allRanges(lines)
            val target = deepestStartingAt(all, line) ?: deepestContaining(all, line)
            if (target == null) {
                return@guarded noFoldRange(
                    "no fold region starts at line $line or contains it " +
                        "(${all.size} region(s) exist elsewhere)",
                )
            }
            val offender = firstPlaceholderIn(lines, target)
            if (offender >= 0) {
                return@guarded containsPlaceholder(target, offender)
            }
            val total = activeFoldCount() + 1
            if (total > MAX_ACTIVE_FOLDS) {
                return@guarded tooManyFolds(total, 1)
            }
            val result = lines.toMutableList()
            val hidden = applyFold(result, target)
            FoldResult.Done(result.joinToString("\n"), hidden)
        }

    /**
     * Unfolds the region represented by the placeholder at [line] (0-based).
     * Fails with NOT_A_PLACEHOLDER when the line is not a placeholder (or is
     * out of range) and with REGION_GONE when the placeholder has no
     * archived backing (text changed or state lost); REGION_GONE also prunes
     * archive entries that no longer occur in the text.
     */
    fun unfoldAtLine(
        text: String,
        line: Int,
    ): FoldResult =
        guarded(text) { input ->
            if (input.length > MAX_TEXT_CHARS) return@guarded tooLarge(input.length)
            val lines = normalize(input).split('\n').toMutableList()
            if (line < 0 || line >= lines.size) {
                return@guarded notAPlaceholder(
                    "line $line is outside the text (0..${lines.lastIndex}) " +
                        "and cannot be a fold placeholder",
                )
            }
            val current = lines[line]
            if (!isPlaceholderLine(current)) {
                return@guarded notAPlaceholder(
                    "line $line is not a fold placeholder: \"$current\"",
                )
            }
            val body = takeArchive(current)
            if (body == null) {
                pruneOrphans(lines)
                return@guarded regionGone(
                    "placeholder at line $line has no archived content " +
                        "('$current'): the text changed or the fold was made " +
                        "by another instance",
                )
            }
            lines.removeAt(line)
            lines.addAll(line, body)
            FoldResult.Done(lines.joinToString("\n"), body.size)
        }

    /** Number of currently archived (active) folds. Never throws. */
    fun activeFoldCount(): Int =
        try {
            archive.values.sumOf { it.size }
        } catch (t: Throwable) {
            // Last-resort defense; must never happen (see class KDoc).
            0
        }

    // ------------------------------------------------------------ internals

    /** Single funnel for the documented never-throws guarantee. */
    private inline fun guarded(
        text: String,
        op: (String) -> FoldResult,
    ): FoldResult =
        try {
            op(text)
        } catch (t: Throwable) {
            FoldResult.Failure(
                FoldErrorCode.NO_FOLD_RANGE,
                "internal error: ${t.javaClass.simpleName}: ${t.message} " +
                    "(this path must never happen, please report it)",
            )
        }

    private fun tooLarge(actual: Int): FoldResult.Failure =
        FoldResult.Failure(
            FoldErrorCode.INPUT_TOO_LARGE,
            "text is $actual characters but the fold engine accepts at most " +
                "$MAX_TEXT_CHARS; refusing to scan an oversized buffer",
        )

    private fun ambiguous(line: String): FoldResult.Failure =
        FoldResult.Failure(
            FoldErrorCode.PLACEHOLDER_AMBIGUOUS,
            "text already contains a placeholder-looking line \"$line\" that " +
                "was not created by this instance; folding would corrupt the " +
                "restore archive",
        )

    private fun noFoldRange(detail: String): FoldResult.Failure = FoldResult.Failure(FoldErrorCode.NO_FOLD_RANGE, detail)

    private fun containsPlaceholder(
        range: FoldRange,
        line: Int,
    ): FoldResult.Failure =
        FoldResult.Failure(
            FoldErrorCode.REGION_CONTAINS_PLACEHOLDER,
            "fold region lines ${range.startLine}..${range.endLine} contains " +
                "an existing placeholder at line $line; unfold it first " +
                "(nested folds are not supported)",
        )

    private fun tooManyFolds(
        total: Int,
        requested: Int,
    ): FoldResult.Failure =
        FoldResult.Failure(
            FoldErrorCode.TOO_MANY_FOLDS,
            "$total active fold(s) would exceed the limit of $MAX_ACTIVE_FOLDS " +
                "(requested $requested more); unfold some regions first",
        )

    private fun notAPlaceholder(detail: String): FoldResult.Failure = FoldResult.Failure(FoldErrorCode.NOT_A_PLACEHOLDER, detail)

    private fun regionGone(detail: String): FoldResult.Failure = FoldResult.Failure(FoldErrorCode.REGION_GONE, detail)

    private fun normalize(text: String): String = text.replace("\r\n", "\n")

    private fun allRanges(lines: List<String>): List<FoldRange> = if (usesIndentStyle) indentRanges(lines) else braceRanges(lines)

    private fun foreignPlaceholder(lines: List<String>): String? = lines.firstOrNull { isPlaceholderLine(it) && !archive.containsKey(it) }

    private fun firstPlaceholderIn(
        lines: List<String>,
        range: FoldRange,
    ): Int {
        for (i in range.startLine..range.endLine) {
            if (isPlaceholderLine(lines[i])) return i
        }
        return -1
    }

    /** Deepest region with startLine == line (smallest endLine wins). */
    private fun deepestStartingAt(
        all: List<FoldRange>,
        line: Int,
    ): FoldRange? {
        var best: FoldRange? = null
        for (range in all) {
            if (range.startLine != line) continue
            val current = best
            if (current == null || range.endLine < current.endLine) best = range
        }
        return best
    }

    /** Deepest region with startLine < line <= endLine (max start, min end). */
    private fun deepestContaining(
        all: List<FoldRange>,
        line: Int,
    ): FoldRange? {
        var best: FoldRange? = null
        for (range in all) {
            if (range.startLine >= line || line > range.endLine) continue
            val current = best
            if (
                current == null ||
                range.startLine > current.startLine ||
                (range.startLine == current.startLine && range.endLine < current.endLine)
            ) {
                best = range
            }
        }
        return best
    }

    /** Keeps the outermost ranges of a (start asc, end desc) sorted list. */
    private fun outermostRanges(all: List<FoldRange>): List<FoldRange> {
        val out = ArrayList<FoldRange>()
        var lastEnd = -1
        for (range in all) {
            if (range.startLine > lastEnd) {
                out.add(range)
                lastEnd = range.endLine
            }
        }
        return out
    }

    private fun sortedRanges(found: List<FoldRange>): List<FoldRange> = found.sortedWith(compareBy({ it.startLine }, { -it.endLine }))

    /**
     * Brace-family scan: pairs `{`/`}` with a stack while skipping strings,
     * comments and multiline strings. Gives up (keeping the pairs found so
     * far) once more than [MAX_SCAN_DEPTH] braces are open at once.
     */
    private fun braceRanges(lines: List<String>): List<FoldRange> {
        val openers = ArrayDeque<Int>()
        val found = ArrayList<FoldRange>()
        var blockCloser: String? = null
        var multilineCloser: String? = null
        lineLoop@ for (index in lines.indices) {
            val line = lines[index]
            var i = 0
            val n = line.length
            while (i < n) {
                val carriedBlock = blockCloser
                if (carriedBlock != null) {
                    val end = line.indexOf(carriedBlock, i)
                    if (end < 0) continue@lineLoop
                    i = end + carriedBlock.length
                    blockCloser = null
                    continue
                }
                val carriedMultiline = multilineCloser
                if (carriedMultiline != null) {
                    val end = line.indexOf(carriedMultiline, i)
                    if (end < 0) continue@lineLoop
                    i = end + carriedMultiline.length
                    multilineCloser = null
                    continue
                }
                val c = line[i]
                var consumed = false
                for (lc in braceConfig.lineComments) {
                    if (line.startsWith(lc, i)) {
                        consumed = true
                        break
                    }
                }
                if (consumed) break // rest of the line is a comment
                for ((open, close) in braceConfig.blockComments) {
                    if (line.startsWith(open, i)) {
                        val end = line.indexOf(close, i + open.length)
                        if (end < 0) {
                            blockCloser = close
                            i = n
                        } else {
                            i = end + close.length
                        }
                        consumed = true
                        break
                    }
                }
                if (consumed) continue
                for ((open, close) in braceConfig.multilineStringDelims) {
                    if (line.startsWith(open, i)) {
                        val end = line.indexOf(close, i + open.length)
                        if (end < 0) {
                            multilineCloser = close
                            i = n
                        } else {
                            i = end + close.length
                        }
                        consumed = true
                        break
                    }
                }
                if (consumed) continue
                if (braceConfig.stringDelims.contains(c)) {
                    i++
                    while (i < n) {
                        val sc = line[i]
                        if (sc == '\\') {
                            i += 2
                            continue
                        }
                        i++
                        if (sc == c) break
                    }
                    // Unterminated single-line strings end at the line end.
                    continue
                }
                when (c) {
                    '{' -> {
                        openers.addLast(index)
                        if (openers.size > MAX_SCAN_DEPTH) return sortedRanges(found)
                    }
                    '}' -> {
                        val start = openers.removeLastOrNull()
                        if (start != null && index > start) found.add(FoldRange(start, index))
                    }
                }
                i++
            }
        }
        return sortedRanges(found)
    }

    /**
     * Indentation-family scan: single O(n) stack pass. Each non-blank line
     * opens a block; a line with indent <= the open indent closes it. Blank
     * lines neither open, close, nor extend a block, so trailing blanks fall
     * outside the range while blanks followed by deeper lines stay inside.
     *
     * The stack keeps THREE parallel tracks per open block: the opener's
     * line index ([openStart]), the opener's indent ([openIndent], used for
     * the close condition only) and the block's current last body line
     * ([openEnd]). Keeping indent and start line apart is essential: the
     * indent is NOT the line number, and using it as the range start would
     * silently corrupt ranges whenever a block opens below the top of the
     * file (a sibling `def` at indent 0 on line 4 must not become a range
     * starting at line 0).
     */
    private fun indentRanges(lines: List<String>): List<FoldRange> {
        val indents = IntArray(lines.size) { -1 } // -1 = blank line
        for (i in lines.indices) {
            val line = lines[i]
            var j = 0
            while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
            indents[i] = if (j == line.length) -1 else j
        }
        val found = ArrayList<FoldRange>()
        val openStart = ArrayDeque<Int>()
        val openIndent = ArrayDeque<Int>()
        val openEnd = ArrayDeque<Int>()
        for (j in lines.indices) {
            val indent = indents[j]
            if (indent < 0) continue
            while (openIndent.isNotEmpty() && openIndent.last() >= indent) {
                openIndent.removeLast()
                val start = openStart.removeLast()
                val end = openEnd.removeLast()
                if (end > start) found.add(FoldRange(start, end))
            }
            for (k in openEnd.indices) openEnd[k] = j
            openStart.addLast(j)
            openIndent.addLast(indent)
            openEnd.addLast(j)
        }
        while (openIndent.isNotEmpty()) {
            openIndent.removeLast()
            val start = openStart.removeLast()
            val end = openEnd.removeLast()
            if (end > start) found.add(FoldRange(start, end))
        }
        return sortedRanges(found)
    }

    /**
     * Archives the body of [range] in [lines], replaces it with the
     * placeholder line, and returns the number of hidden lines.
     *
     * The placeholder text is `indent + "⟦⋯ N ⟧"`. When an ACTIVE fold
     * already uses that exact text (same hidden count), the new placeholder
     * is disambiguated to `⟦⋯ N#k ⟧` with a fresh ordinal, keeping every
     * archive key unique — unfolding then restores the exact body even for
     * sibling folds that look identical.
     */
    private fun applyFold(
        lines: MutableList<String>,
        range: FoldRange,
    ): Int {
        val opener = lines[range.startLine]
        val body = lines.subList(range.startLine + 1, range.endLine + 1).toList()
        val base = indentOf(opener) + PLACEHOLDER_PREFIX + body.size + PLACEHOLDER_SUFFIX
        var placeholder = base
        if (archive.containsKey(base)) {
            while (true) {
                val candidate =
                    indentOf(opener) + PLACEHOLDER_PREFIX + body.size + "#" + nextFoldId +
                        PLACEHOLDER_SUFFIX
                nextFoldId++
                if (!archive.containsKey(candidate)) {
                    placeholder = candidate
                    break
                }
            }
        }
        archive.getOrPut(placeholder) { ArrayDeque() }.addLast(body)
        lines.subList(range.startLine + 1, range.endLine + 1).clear()
        lines.add(range.startLine + 1, placeholder)
        return body.size
    }

    /** Pops the most recently archived body for [placeholder] (LIFO). */
    private fun takeArchive(placeholder: String): List<String>? {
        val queue = archive[placeholder] ?: return null
        val body = queue.removeLast()
        if (queue.isEmpty()) archive.remove(placeholder)
        return body
    }

    /** Drops archive entries whose placeholder no longer occurs in [lines]. */
    private fun pruneOrphans(lines: List<String>) {
        if (archive.isEmpty()) return
        val present = lines.filterTo(HashSet()) { isPlaceholderLine(it) }
        archive.keys.retainAll { it in present }
    }

    private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    companion object {
        /** Hard input cap; every FoldResult operation rejects larger text. */
        const val MAX_TEXT_CHARS = 1_000_000

        /** Maximum number of simultaneously active (archived) folds. */
        const val MAX_ACTIVE_FOLDS = 256

        /** Prefix of a placeholder line, e.g. `⟦⋯ 12 ⟧`. */
        const val PLACEHOLDER_PREFIX = "⟦⋯ "

        /** Suffix of a placeholder line. */
        const val PLACEHOLDER_SUFFIX = " ⟧"

        /** Perf guard: stop brace pairing once this many braces stay open. */
        private const val MAX_SCAN_DEPTH = 512

        /**
         * Languages folded by INDENTATION instead of braces. Kept in sync
         * with the indent-family profiles of [FormatterLanguages]
         * (indentConfigFor); that object exposes no family predicate, so the
         * ids are listed here (documented duplication).
         */
        private val INDENT_STYLE_LANGUAGE_IDS =
            setOf("python", "vyper", "ruby", "lua", "elixir", "julia", "latex")

        /**
         * True when [line] matches the placeholder pattern: optional
         * leading spaces/tabs, [PLACEHOLDER_PREFIX], digits, an optional
         * `#<digits>` disambiguation ordinal, and [PLACEHOLDER_SUFFIX] with
         * nothing else. The hidden count must be representable as an Int, so
         * absurdly long digit runs are NOT placeholders (defensive: they
         * cannot be confused with real ones).
         */
        fun isPlaceholderLine(line: String): Boolean = placeholderHiddenCount(line) != null

        /**
         * The hidden-line count encoded in a placeholder line, or null when
         * [line] is not a placeholder. `⟦⋯ 0 ⟧` parses as 0 (harmless: such
         * a line can never be produced by the engine, it only participates
         * in the ambiguity checks). The ordinal part of `⟦⋯ 3#2 ⟧` is
         * accepted but ignored here — it only disambiguates archive keys.
         */
        fun placeholderHiddenCount(line: String): Int? {
            var i = 0
            val n = line.length
            while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
            if (!line.startsWith(PLACEHOLDER_PREFIX, i)) return null
            val digitsStart = i + PLACEHOLDER_PREFIX.length
            var j = digitsStart
            while (j < n && line[j] in '0'..'9') j++
            if (j == digitsStart) return null
            if (j < n && line[j] == '#') {
                j++
                val ordinalStart = j
                while (j < n && line[j] in '0'..'9') j++
                if (j == ordinalStart) return null
            }
            if (!line.startsWith(PLACEHOLDER_SUFFIX, j)) return null
            if (j + PLACEHOLDER_SUFFIX.length != n) return null
            return line.substring(digitsStart, digitsStart + digitRunLength(line, digitsStart)).toIntOrNull()
        }

        private fun digitRunLength(
            line: String,
            from: Int,
        ): Int {
            var k = from
            while (k < line.length && line[k] in '0'..'9') k++
            return k - from
        }
    }
}
