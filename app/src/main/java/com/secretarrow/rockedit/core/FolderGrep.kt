package com.secretarrow.rockedit.core

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Pure multi-file search engine ("grep in folder", v0.11.0).
 *
 * The core receives an already-decoded list of [GrepFile]s so it stays 100%
 * JVM-testable; the SAF traversal lives in the UI layer and maps
 * DocumentFile trees onto this API.
 *
 * Defensive contract:
 * - Blank query maps to [GrepOutcome.Failure] (EMPTY_QUERY) before work.
 * - Invalid regex maps to PARSE_ERROR with the engine's description.
 * - Non-regex queries are quoted literally (no surprise metacharacters).
 * - Files containing NUL in the first [BINARY_SNIFF_BYTES] are skipped and
 *   counted in [GrepSummary.skippedBinary] instead of producing noise.
 * - Per-file and total match caps bound memory; hitting either sets the
 *   truncated flags and stops cleanly (no exception path).
 * - Line previews are clipped to [PREVIEW_MAX_CHARS] (one very long line
 *   can never blow up the result list).
 * - The aggregate content budget ([MAX_TOTAL_CHARS]) stops traversal early
 *   when the caller feeds a huge folder; the summary reports truncation.
 *
 * Documented assumptions:
 * - Line numbers are 1-based; columns are character offsets within the
 *   raw line (0-based), matching the Find dialog conventions.
 * - Content is decoded by the caller (UTF-8 lenient); byte-level encoding
 *   fidelity is out of scope for the search core.
 */
object FolderGrep {

    const val PREVIEW_MAX_CHARS = 200
    const val BINARY_SNIFF_BYTES = 8_192
    const val DEFAULT_MAX_MATCHES_PER_FILE = 100
    const val DEFAULT_MAX_TOTAL_MATCHES = 2_000
    const val MAX_TOTAL_CHARS = 4_000_000L

    enum class ErrorCode { EMPTY_QUERY, PARSE_ERROR, INTERNAL_ERROR }

    data class GrepError(val code: ErrorCode, val message: String)

    data class GrepOptions(
        val isRegex: Boolean = false,
        val ignoreCase: Boolean = false,
        val maxMatchesPerFile: Int = DEFAULT_MAX_MATCHES_PER_FILE,
        val maxTotalMatches: Int = DEFAULT_MAX_TOTAL_MATCHES
    )

    data class GrepFile(val path: String, val content: String)

    data class GrepHit(
        val path: String,
        val lineNumber: Int,
        val column: Int,
        val preview: String
    )

    data class GrepSummary(
        val hits: List<GrepHit>,
        val filesScanned: Int,
        val filesWithHits: Int,
        val skippedBinary: Int,
        val truncatedMatches: Boolean,
        val truncatedFiles: Boolean
    )

    sealed class GrepOutcome {
        data class Done(val summary: GrepSummary) : GrepOutcome()
        data class Failure(val error: GrepError) : GrepOutcome()
    }

    fun run(files: List<GrepFile>, query: String, options: GrepOptions = GrepOptions()): GrepOutcome {
        if (query.isBlank()) {
            return GrepOutcome.Failure(GrepError(ErrorCode.EMPTY_QUERY, "query is empty"))
        }
        val matcherFactory = try {
            buildMatcher(query, options)
        } catch (e: PatternSyntaxException) {
            return GrepOutcome.Failure(
                GrepError(ErrorCode.PARSE_ERROR, e.description ?: e.message ?: "invalid pattern")
            )
        }
        return try {
            GrepOutcome.Done(scan(files, matcherFactory, options))
        } catch (e: OutOfMemoryError) {
            GrepOutcome.Failure(GrepError(ErrorCode.INTERNAL_ERROR, "not enough memory"))
        } catch (e: Exception) {
            GrepOutcome.Failure(GrepError(ErrorCode.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun buildMatcher(query: String, options: GrepOptions): (String) -> Matcher {
        val effective = if (options.isRegex) query else Pattern.quote(query)
        val bits = if (options.ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        val pattern = Pattern.compile(effective, bits)
        return { line -> pattern.matcher(line) }
    }

    private fun scan(
        files: List<GrepFile>,
        matcherFactory: (String) -> Matcher,
        options: GrepOptions
    ): GrepSummary {
        val hits = ArrayList<GrepHit>()
        var filesScanned = 0
        var skippedBinary = 0
        var filesWithHits = 0
        var truncatedMatches = false
        var truncatedFiles = false
        var totalChars = 0L

        for (file in files) {
            if (hits.size >= options.maxTotalMatches) {
                // Files still queued: matches in them are suppressed.
                truncatedMatches = true
                truncatedFiles = true
                break
            }
            totalChars += file.content.length
            if (totalChars > MAX_TOTAL_CHARS) {
                truncatedFiles = true
                truncatedMatches = true
                break
            }
            if (looksBinary(file.content)) {
                skippedBinary++
                continue
            }
            filesScanned++
            var fileHits = 0
            var lineNumber = 1
            var lineStart = 0
            val content = file.content
            while (lineStart <= content.length) {
                var lineEnd = content.indexOf('\n', lineStart)
                var nextStart = lineEnd + 1
                if (lineEnd < 0) {
                    lineEnd = content.length
                    nextStart = content.length + 1
                }
                var line = content.substring(lineStart, lineEnd)
                if (line.endsWith('\r')) line = line.substring(0, line.length - 1)
                val matcher = matcherFactory(line)
                var column = 0
                while (column <= line.length) {
                    if (!matcher.find(column)) break
                    if (hits.size >= options.maxTotalMatches) {
                        truncatedMatches = true
                        truncatedFiles = true
                        break
                    }
                    if (fileHits >= options.maxMatchesPerFile) {
                        truncatedMatches = true
                        break
                    }
                    hits.add(
                        GrepHit(
                            path = file.path,
                            lineNumber = lineNumber,
                            column = matcher.start(),
                            preview = clipPreview(line)
                        )
                    )
                    fileHits++
                    val mEnd = matcher.end()
                    column = if (mEnd == matcher.start()) matcher.start() + 1 else mEnd
                }
                if (hits.size >= options.maxTotalMatches) {
                    // Scan stopped early: later lines/files may hold suppressed
                    // matches. Conservative: may be true even when the cap
                    // coincided with the end of input (documented trade-off).
                    truncatedMatches = true
                    truncatedFiles = true
                    break
                }
                if (fileHits >= options.maxMatchesPerFile) {
                    // Later lines of this file would yield suppressed matches.
                    truncatedMatches = true
                    break
                }
                if (nextStart > content.length) break
                lineStart = nextStart
                lineNumber++
            }
            if (fileHits > 0) filesWithHits++
        }
        return GrepSummary(
            hits = hits,
            filesScanned = filesScanned,
            filesWithHits = filesWithHits,
            skippedBinary = skippedBinary,
            truncatedMatches = truncatedMatches,
            truncatedFiles = truncatedFiles
        )
    }

    private fun looksBinary(content: String): Boolean {
        val sniffEnd = minOf(content.length, BINARY_SNIFF_BYTES)
        for (i in 0 until sniffEnd) {
            if (content[i] == '\u0000') return true
        }
        return false
    }

    private fun clipPreview(line: String): String {
        val trimmed = line.trim()
        return if (trimmed.length <= PREVIEW_MAX_CHARS) trimmed else trimmed.take(PREVIEW_MAX_CHARS) + "…"
    }
}
