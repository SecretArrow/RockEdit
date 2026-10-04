package com.secretarrow.rockedit.core

/**
 * Line-based diff engine (v0.12.0 "Compare with file"). Pure JVM, no Android
 * dependencies, deterministic output.
 *
 * Case map (defensive rule 1 — every case has a code path below):
 * - Both sides empty -> Done with no ops.
 * - Old side empty -> INSERT of every new line; new side empty -> DELETE of
 *   every old line.
 * - Identical sides -> one EQUAL op, `stats.hasChanges == false`.
 * - Mixed/interleaved changes -> minimal DELETE/INSERT blocks via LCS with
 *   common prefix/suffix trimmed first.
 * - `ignoreWhitespace` / `ignoreCase` -> lines are compared through a
 *   normalizer; original text is still emitted unchanged.
 * - Either side above [DiffOptions.maxLines] -> Failure(INPUT_TOO_LARGE),
 *   never a silent truncation.
 * - LCS matrix above [DiffOptions.maxMatrixCells] -> `fellBack = true` and
 *   the middle is emitted as one DELETE + INSERT block (memory stays bounded
 *   on hostile inputs).
 * - CRLF / CR line endings are handled by [String.lines]; a trailing line
 *   terminator at the very end of a file is invisible to the diff (documented
 *   v1 assumption).
 * - Determinism: with equal LCS scores the walk prefers DELETE over INSERT,
 *   so the same inputs always produce the same ops.
 */
object DiffEngine {

    enum class DiffKind { EQUAL, DELETE, INSERT }

    /**
     * One merged run of lines. [oldStart]/[newStart] are 0-based positions of
     * the first line on the respective side; [lineCount] always matches the
     * number of lines in [text] (`"".split('\n')` keeps a single empty line).
     */
    data class DiffOp(
        val kind: DiffKind,
        val text: String,
        val oldStart: Int,
        val newStart: Int,
        val lineCount: Int
    )

    data class DiffStats(
        val addedLines: Int,
        val removedLines: Int,
        val unchangedLines: Int
    ) {
        val hasChanges: Boolean get() = addedLines > 0 || removedLines > 0
    }

    data class DiffOptions(
        val ignoreWhitespace: Boolean = false,
        val ignoreCase: Boolean = false,
        val maxLines: Int = 100_000,
        val maxMatrixCells: Long = 4_000_000L
    ) {
        init {
            require(maxLines in 1..1_000_000) {
                "maxLines must be in 1..1000000 but was $maxLines"
            }
            require(maxMatrixCells in 1..100_000_000) {
                "maxMatrixCells must be in 1..100000000 but was $maxMatrixCells"
            }
        }
    }

    enum class DiffErrorCode { INPUT_TOO_LARGE }

    sealed interface DiffOutcome {
        data class Done(
            val ops: List<DiffOp>,
            val stats: DiffStats,
            val fellBack: Boolean
        ) : DiffOutcome

        data class Failure(val code: DiffErrorCode, val message: String) : DiffOutcome
    }

    fun diff(
        oldText: String,
        newText: String,
        options: DiffOptions = DiffOptions()
    ): DiffOutcome {
        if (oldText.isEmpty() && newText.isEmpty()) {
            return DiffOutcome.Done(emptyList(), DiffStats(0, 0, 0), fellBack = false)
        }
        val oldLines = toLines(oldText)
        val newLines = toLines(newText)
        if (oldLines.size > options.maxLines || newLines.size > options.maxLines) {
            return DiffOutcome.Failure(
                DiffErrorCode.INPUT_TOO_LARGE,
                "input has ${maxOf(oldLines.size, newLines.size)} lines, " +
                    "limit is ${options.maxLines}"
            )
        }
        if (oldText.isEmpty()) {
            return done(listOf(insertOp(newLines, oldStart = 0)), false)
        }
        if (newText.isEmpty()) {
            return done(listOf(deleteOp(oldLines, newStart = 0)), false)
        }

        // Trim the common prefix and suffix first: the LCS work then only
        // covers the changed middle, which keeps the matrix small for the
        // typical "edit in the middle of a file" case.
        var start = 0
        while (start < oldLines.size && start < newLines.size &&
            equal(oldLines[start], newLines[start], options)
        ) {
            start++
        }
        var oldEnd = oldLines.size - 1
        var newEnd = newLines.size - 1
        while (oldEnd >= start && newEnd >= start &&
            equal(oldLines[oldEnd], newLines[newEnd], options)
        ) {
            oldEnd--
            newEnd--
        }
        val prefixOps = if (start == 0) emptyList() else listOf(equalOp(oldLines, start))
        val suffixOps = if (oldEnd + 1 < oldLines.size && oldEnd + 1 < oldLines.size) {
            val count = oldLines.size - 1 - oldEnd
            listOf(
                DiffOp(
                    DiffKind.EQUAL,
                    oldLines.drop(oldEnd + 1).joinToString("\n"),
                    oldStart = oldEnd + 1,
                    newStart = newEnd + 1,
                    lineCount = count
                )
            )
        } else {
            emptyList()
        }

        val midOld = oldLines.subList(start, oldEnd + 1)
        val midNew = newLines.subList(start, newEnd + 1)
        val (midOps, fellBack) = middleOps(midOld, midNew, options, start)
        return done(prefixOps + midOps + suffixOps, fellBack)
    }

    // ------------------------------------------------------------ internals

    /**
     * LCS over the changed middle. Returns the ops plus whether the cell
     * budget forced the whole-block fallback.
     */
    private fun middleOps(
        oldLines: List<String>,
        newLines: List<String>,
        options: DiffOptions,
        offset: Int
    ): Pair<List<DiffOp>, Boolean> {
        if (oldLines.isEmpty() && newLines.isEmpty()) return Pair(emptyList(), false)
        if (oldLines.isEmpty()) {
            return Pair(
                listOf(
                    DiffOp(
                        DiffKind.INSERT,
                        newLines.joinToString("\n"),
                        oldStart = offset,
                        newStart = offset,
                        lineCount = newLines.size
                    )
                ),
                false
            )
        }
        if (newLines.isEmpty()) {
            return Pair(
                listOf(
                    DiffOp(
                        DiffKind.DELETE,
                        oldLines.joinToString("\n"),
                        oldStart = offset,
                        newStart = offset,
                        lineCount = oldLines.size
                    )
                ),
                false
            )
        }
        val n = oldLines.size
        val m = newLines.size
        val width = m + 1
        val cells = (n.toLong() + 1) * (m.toLong() + 1)
        if (cells > options.maxMatrixCells) {
            val ops = buildList {
                add(deleteOp(oldLines, offset))
                add(
                    DiffOp(
                        DiffKind.INSERT,
                        newLines.joinToString("\n"),
                        oldStart = offset + n,
                        newStart = offset,
                        lineCount = m
                    )
                )
            }
            return Pair(ops, true)
        }
        // dp[i * width + j] = LCS length of oldLines[i..] and newLines[j..].
        val dp = IntArray(cells.toInt())
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i * width + j] = if (equal(oldLines[i], newLines[j], options)) {
                    dp[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(dp[(i + 1) * width + j], dp[i * width + j + 1])
                }
            }
        }
        val ops = ArrayList<DiffOp>(n + m)
        var pendingKind: DiffKind? = null
        val pendingLines = ArrayList<String>(maxOf(n, m))
        var pendingOld = -1
        var pendingNew = -1
        var oldLine = 0
        var newLine = 0

        fun flushPending() {
            val kind = pendingKind ?: return
            ops.add(
                DiffOp(
                    kind = kind,
                    text = pendingLines.joinToString("\n"),
                    oldStart = pendingOld,
                    newStart = pendingNew,
                    lineCount = pendingLines.size
                )
            )
            pendingKind = null
            pendingLines.clear()
        }

        var i = 0
        var j = 0
        while (i < n || j < m) {
            val takeEqual = i < n && j < m && equal(oldLines[i], newLines[j], options)
            val takeDelete = i < n && !takeEqual &&
                (j >= m || dp[(i + 1) * width + j] >= dp[i * width + j + 1])
            when {
                takeEqual -> {
                    if (pendingKind != DiffKind.EQUAL) {
                        flushPending()
                        pendingKind = DiffKind.EQUAL
                        pendingOld = offset + oldLine
                        pendingNew = offset + newLine
                    }
                    pendingLines.add(oldLines[i])
                    oldLine++
                    newLine++
                    i++
                    j++
                }
                takeDelete -> {
                    if (pendingKind != DiffKind.DELETE) {
                        flushPending()
                        pendingKind = DiffKind.DELETE
                        pendingOld = offset + oldLine
                        pendingNew = offset + newLine
                    }
                    pendingLines.add(oldLines[i])
                    oldLine++
                    i++
                }
                j < m -> {
                    if (pendingKind != DiffKind.INSERT) {
                        flushPending()
                        pendingKind = DiffKind.INSERT
                        pendingOld = offset + oldLine
                        pendingNew = offset + newLine
                    }
                    pendingLines.add(newLines[j])
                    newLine++
                    j++
                }
                // Defensive guard: i < n and j >= m always routes to
                // takeDelete, so this branch is unreachable by construction.
                else -> break
            }
        }
        flushPending()
        return Pair(ops, false)
    }

    private fun toLines(text: String): List<String> {
        val lines = text.lines()
        return if (text.endsWith('\n') || text.endsWith('\r')) {
            lines.dropLast(1)
        } else {
            lines
        }
    }

    private fun equal(a: String, b: String, options: DiffOptions): Boolean {
        var left = a
        var right = b
        if (options.ignoreWhitespace) {
            left = WHITESPACE_RUNS.replace(left.trim(), " ")
            right = WHITESPACE_RUNS.replace(right.trim(), " ")
        }
        if (options.ignoreCase) {
            left = left.lowercase()
            right = right.lowercase()
        }
        return left == right
    }

    private fun equalOp(lines: List<String>, upTo: Int): DiffOp =
        DiffOp(
            DiffKind.EQUAL,
            lines.subList(0, upTo).joinToString("\n"),
            oldStart = 0,
            newStart = 0,
            lineCount = upTo
        )

    private fun insertOp(lines: List<String>, oldStart: Int): DiffOp =
        DiffOp(
            DiffKind.INSERT,
            lines.joinToString("\n"),
            oldStart = oldStart,
            newStart = 0,
            lineCount = lines.size
        )

    private fun deleteOp(lines: List<String>, newStart: Int): DiffOp =
        DiffOp(
            DiffKind.DELETE,
            lines.joinToString("\n"),
            oldStart = 0,
            newStart = newStart,
            lineCount = lines.size
        )

    private fun done(
        ops: List<DiffOp>,
        fellBack: Boolean
    ): DiffOutcome {
        var added = 0
        var removed = 0
        var unchanged = 0
        for (op in ops) {
            when (op.kind) {
                DiffKind.INSERT -> added += op.lineCount
                DiffKind.DELETE -> removed += op.lineCount
                DiffKind.EQUAL -> unchanged += op.lineCount
            }
        }
        return DiffOutcome.Done(ops, DiffStats(added, removed, unchanged), fellBack)
    }

    private val WHITESPACE_RUNS = Regex("\\s+")
}
