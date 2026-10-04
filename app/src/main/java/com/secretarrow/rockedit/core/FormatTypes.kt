package com.secretarrow.rockedit.core

/**
 * Core types of the code formatter pipeline.
 *
 * Design rules (defensive programming):
 * - Every type validates its own invariants at construction (fail fast).
 * - [FormatResult] is a sealed type: callers cannot miss a branch.
 * - Empty input is NOT an error: it maps to [FormatResult.Skipped].
 * - All failure paths carry a specific [FormatErrorCode] so the UI can
 *   translate them into localized messages.
 */
data class FormatOptions(
    val indentStyle: IndentStyle = IndentStyle.SPACES,
    val indentSize: Int = DEFAULT_INDENT_SIZE,
    val lineBreak: LineBreak = LineBreak.LF,
    val trimTrailingWhitespace: Boolean = true,
    val insertFinalNewline: Boolean = true,
    val minify: Boolean = false,
    /**
     * Lenient mode: when structural validation fails (unbalanced braces,
     * unclosed strings, non-multiple indentation), still produce a
     * best-effort result instead of a PARSE_ERROR. Default is strict:
     * suspicious input is rejected with a specific error and line number.
     */
    val lenient: Boolean = false,
) {
    init {
        require(indentSize in MIN_INDENT_SIZE..MAX_INDENT_SIZE) {
            "indentSize must be in $MIN_INDENT_SIZE..$MAX_INDENT_SIZE, was: $indentSize"
        }
    }

    companion object {
        const val MIN_INDENT_SIZE = 1
        const val MAX_INDENT_SIZE = 8
        const val DEFAULT_INDENT_SIZE = 4
    }
}

/** Whether indentation uses spaces or tabs. */
enum class IndentStyle { SPACES, TABS }

/**
 * An immutable, fully validated formatting request. Constructing an invalid
 * request throws [IllegalArgumentException] — the UI never builds one by
 * accident, and every downstream component can trust these invariants.
 */
data class FormatRequest(
    val text: String,
    val language: String,
    val options: FormatOptions = FormatOptions(),
    val timeBudgetMs: Long = DEFAULT_TIME_BUDGET_MS,
) {
    init {
        require(language.isNotBlank()) { "language must not be blank" }
        require(timeBudgetMs in MIN_TIME_BUDGET_MS..MAX_TIME_BUDGET_MS) {
            "timeBudgetMs must be in $MIN_TIME_BUDGET_MS..$MAX_TIME_BUDGET_MS, was: $timeBudgetMs"
        }
    }

    companion object {
        /**
         * Hard cap on input size. Formatting beyond this would risk OOM/ANR
         * on low-end devices, so it is rejected up front with a clear error.
         */
        const val MAX_TEXT_CHARS = 2_000_000

        const val DEFAULT_TIME_BUDGET_MS = 5_000L
        const val MIN_TIME_BUDGET_MS = 100L
        const val MAX_TIME_BUDGET_MS = 60_000L
    }
}

/** Machine-readable failure category. The UI maps each code to a localized string. */
enum class FormatErrorCode {
    INPUT_TOO_LARGE,
    UNSUPPORTED_LANGUAGE,
    PARSE_ERROR,
    TIMEOUT,
    INTERNAL_ERROR,
}

/**
 * A structured error. [line]/[column] are 1-based when the underlying parser
 * can pinpoint the problem, null otherwise — never zero, never negative.
 */
data class FormatError(
    val code: FormatErrorCode,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * The single result contract of every formatter call.
 *
 * - [Success]: formatting completed (possibly with `changed = false` when
 *   the input was already well formatted).
 * - [Failure]: the input could not be formatted; carries [FormatError].
 * - [Skipped]: there was nothing to do (empty/blank input) — not an error.
 */
sealed class FormatResult {
    data class Success(
        val formattedText: String,
        val changed: Boolean,
        val durationMs: Long,
    ) : FormatResult()

    data class Failure(
        val error: FormatError,
    ) : FormatResult()

    data class Skipped(
        val reason: String,
    ) : FormatResult()
}

/**
 * Cooperative time budget for long formatting runs. Formatters poll
 * [isExpired] between chunks; expiring aborts the run with a TIMEOUT
 * failure instead of blocking the caller forever.
 *
 * The clock is injectable so tests can force expiration deterministically.
 */
class Deadline internal constructor(
    private val deadlineAtMs: Long,
    private val nowMs: () -> Long,
    val budgetMs: Long,
) {
    fun isExpired(): Boolean = nowMs() >= deadlineAtMs

    fun remainingMs(): Long = (deadlineAtMs - nowMs()).coerceAtLeast(0)

    companion object {
        fun fromBudget(
            budgetMs: Long,
            nowMs: () -> Long = System::currentTimeMillis,
        ): Deadline {
            val safe = budgetMs.coerceIn(FormatRequest.MIN_TIME_BUDGET_MS, FormatRequest.MAX_TIME_BUDGET_MS)
            return Deadline(nowMs() + safe, nowMs, safe)
        }
    }
}
