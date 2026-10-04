package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Snippet manager (v0.12.0): named, language-tagged text templates with
 * `$n` / `${n:default}` tabstops, persisted as JSON through [KeyValueStore].
 * Pure JVM so it is fully unit-testable.
 *
 * Case map (defensive rule 1):
 * - Blank name -> BLANK_NAME; name above [MAX_NAME_CHARS] -> NAME_TOO_LONG.
 * - Duplicate name (trimmed, case-insensitive) -> DUPLICATE_NAME.
 * - Blank body -> BODY_BLANK; body above [MAX_BODY_CHARS] -> BODY_TOO_LARGE.
 * - Blank language normalizes to the wildcard `all` (matches every editor
 *   language when filtering).
 * - Unknown ids on update -> NOT_FOUND; on delete/touch -> `false`, never an
 *   exception.
 * - Corrupt or missing storage data loads as an empty library (fail-safe);
 *   the next mutation writes a clean file. Saving itself cannot throw: the
 *   KeyValueStore contract is non-blocking in-memory / SharedPreferences, so
 *   no extra I/O try/catch is needed here (documented decision).
 *
 * [Insert] expansion never throws: `$$` is a literal dollar, a trailing `$`
 * or a `$` before a non-placeholder character stays literal, an unclosed
 * `${` degrades the remainder to literal text, and a malformed index
 * (`${x:...}`) is kept verbatim so no user text is ever lost.
 */
class SnippetStore(
    private val kv: KeyValueStore,
) {
    data class Snippet(
        val id: String,
        val name: String,
        val language: String,
        val body: String,
        val usageCount: Int,
    )

    enum class ErrorCode {
        BLANK_NAME,
        NAME_TOO_LONG,
        DUPLICATE_NAME,
        BODY_BLANK,
        BODY_TOO_LARGE,
        NOT_FOUND,
    }

    sealed interface MutateResult {
        data class Success(
            val snippets: List<Snippet>,
        ) : MutateResult

        data class Failure(
            val code: ErrorCode,
            val message: String,
        ) : MutateResult
    }

    /**
     * All snippets, sorted by usage (descending) then name. With a non-blank
     * [language] only snippets of that exact language plus the `all` wildcard
     * are returned.
     */
    fun list(language: String? = null): List<Snippet> {
        val all = load()
        val filtered =
            if (language.isNullOrBlank()) {
                all
            } else {
                val wanted = normalizeLanguage(language)
                all.filter { it.language == wanted || it.language == LANG_ALL }
            }
        return filtered.sortedWith(
            compareByDescending<Snippet> { it.usageCount }
                .thenBy { it.name.lowercase() },
        )
    }

    fun find(id: String): Snippet? = load().firstOrNull { it.id == id }

    fun create(
        name: String,
        language: String,
        body: String,
    ): MutateResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return MutateResult.Failure(ErrorCode.BLANK_NAME, "snippet name is blank")
        }
        if (trimmedName.length > MAX_NAME_CHARS) {
            return MutateResult.Failure(
                ErrorCode.NAME_TOO_LONG,
                "name has ${trimmedName.length} characters, limit is $MAX_NAME_CHARS",
            )
        }
        if (body.isBlank()) {
            return MutateResult.Failure(ErrorCode.BODY_BLANK, "snippet body is blank")
        }
        if (body.length > MAX_BODY_CHARS) {
            return MutateResult.Failure(
                ErrorCode.BODY_TOO_LARGE,
                "body has ${body.length} characters, limit is $MAX_BODY_CHARS",
            )
        }
        val all = load().toMutableList()
        val duplicate = all.firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
        if (duplicate != null) {
            return MutateResult.Failure(
                ErrorCode.DUPLICATE_NAME,
                "a snippet named \"${duplicate.name}\" already exists",
            )
        }
        val snippet =
            Snippet(
                id = nextId(all),
                name = trimmedName,
                language = normalizeLanguage(language),
                body = body,
                usageCount = 0,
            )
        all.add(snippet)
        save(all)
        return MutateResult.Success(all)
    }

    /**
     * Partial update: `null` fields keep their current value. Validation only
     * runs for the fields that are actually being changed.
     */
    fun update(
        id: String,
        name: String? = null,
        language: String? = null,
        body: String? = null,
    ): MutateResult {
        val all = load().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) {
            return MutateResult.Failure(ErrorCode.NOT_FOUND, "no snippet with id \"$id\"")
        }
        val current = all[index]
        var newName = current.name
        if (name != null) {
            newName = name.trim()
            if (newName.isEmpty()) {
                return MutateResult.Failure(ErrorCode.BLANK_NAME, "snippet name is blank")
            }
            if (newName.length > MAX_NAME_CHARS) {
                return MutateResult.Failure(
                    ErrorCode.NAME_TOO_LONG,
                    "name has ${newName.length} characters, limit is $MAX_NAME_CHARS",
                )
            }
            val clash =
                all.firstOrNull {
                    it.id != id && it.name.equals(newName, ignoreCase = true)
                }
            if (clash != null) {
                return MutateResult.Failure(
                    ErrorCode.DUPLICATE_NAME,
                    "a snippet named \"${clash.name}\" already exists",
                )
            }
        }
        var newBody = current.body
        if (body != null) {
            if (body.isBlank()) {
                return MutateResult.Failure(ErrorCode.BODY_BLANK, "snippet body is blank")
            }
            if (body.length > MAX_BODY_CHARS) {
                return MutateResult.Failure(
                    ErrorCode.BODY_TOO_LARGE,
                    "body has ${body.length} characters, limit is $MAX_BODY_CHARS",
                )
            }
            newBody = body
        }
        val newLanguage = if (language == null) current.language else normalizeLanguage(language)
        all[index] = current.copy(name = newName, language = newLanguage, body = newBody)
        save(all)
        return MutateResult.Success(all)
    }

    fun delete(id: String): Boolean {
        val all = load().toMutableList()
        val removed = all.removeAll { it.id == id }
        if (removed) {
            save(all)
        }
        return removed
    }

    /** Bumps the usage counter so [list] can surface frequently used items. */
    fun touch(id: String): Boolean {
        val all = load().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) {
            return false
        }
        all[index] = all[index].copy(usageCount = all[index].usageCount + 1)
        save(all)
        return true
    }

    // ------------------------------------------------------------ expansion

    /**
     * Expands tabstop placeholders. Stops are 0-based offsets into the
     * expanded text; an empty default is stored as an empty range
     * (`start > endInclusive`) that marks a caret insertion point.
     */
    object Insert {
        data class Stop(
            val index: Int,
            val start: Int,
            val endInclusive: Int,
        )

        sealed interface InsertResult {
            data class Success(
                val text: String,
                val stops: List<Stop>,
            ) : InsertResult

            data class Failure(
                val code: ErrorCode,
                val message: String,
            ) : InsertResult
        }

        fun expand(body: String): InsertResult {
            if (body.isBlank()) {
                return InsertResult.Failure(ErrorCode.BODY_BLANK, "snippet body is blank")
            }
            if (body.length > MAX_BODY_CHARS) {
                return InsertResult.Failure(
                    ErrorCode.BODY_TOO_LARGE,
                    "body has ${body.length} characters, limit is $MAX_BODY_CHARS",
                )
            }
            val out = StringBuilder(body.length + 64)
            val stops = ArrayList<Stop>()
            var i = 0
            while (i < body.length) {
                val ch = body[i]
                if (ch != '$') {
                    out.append(ch)
                    i++
                    continue
                }
                if (i + 1 >= body.length) {
                    // Trailing '$': literal, never lose user text.
                    out.append('$')
                    break
                }
                val next = body[i + 1]
                when {
                    next == '$' -> {
                        out.append('$')
                        i += 2
                    }
                    next == '{' -> {
                        val close = body.indexOf('}', i + 2)
                        if (close < 0) {
                            // Unclosed '${': keep the remainder literally.
                            out.append(body.substring(i))
                            break
                        }
                        val header = body.substring(i + 2, close)
                        val colon = header.indexOf(':')
                        val indexPart = if (colon >= 0) header.substring(0, colon) else header
                        val defaultText = if (colon >= 0) header.substring(colon + 1) else ""
                        val stopIndex = indexPart.toIntOrNull()
                        if (stopIndex == null || stopIndex < 0) {
                            // Malformed placeholder: keep verbatim.
                            out.append(body.substring(i, close + 1))
                        } else {
                            val start = out.length
                            out.append(defaultText)
                            stops.add(Stop(stopIndex, start, out.length - 1))
                        }
                        i = close + 1
                    }
                    next in '0'..'9' -> {
                        val start = out.length
                        stops.add(Stop(next - '0', start, start - 1))
                        i += 2
                    }
                    else -> {
                        // '$' before a non-placeholder char: literal.
                        out.append('$')
                        i++
                    }
                }
            }
            return InsertResult.Success(out.toString(), stops)
        }

        /**
         * Final caret offset after insertion: the last `$0` stop when the
         * body declares one, otherwise the end of the expanded text.
         */
        fun finalCaret(result: InsertResult.Success): Int = result.stops.lastOrNull { it.index == 0 }?.start ?: result.text.length
    }

    // ------------------------------------------------------------ internals

    private fun load(): List<Snippet> {
        val raw = kv.getString(KEY, null) ?: return emptyList()
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray(F_SNIPPETS) ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString(F_NAME)
                    val body = o.optString(F_BODY)
                    val id = o.optString(F_ID)
                    // Defensive: skip entries missing required fields instead
                    // of failing the whole library.
                    if (id.isEmpty() || name.isEmpty() || body.isEmpty()) continue
                    add(
                        Snippet(
                            id = id,
                            name = name,
                            language =
                                normalizeLanguage(
                                    o.optString(F_LANGUAGE, LANG_ALL),
                                ),
                            body = body,
                            usageCount = o.optInt(F_USAGE, 0).coerceAtLeast(0),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            // Corrupt data: fail-safe empty library, clean file on next save.
            emptyList()
        }
    }

    private fun save(all: List<Snippet>) {
        val arr = JSONArray()
        for (s in all) {
            arr.put(
                JSONObject()
                    .put(F_ID, s.id)
                    .put(F_NAME, s.name)
                    .put(F_LANGUAGE, s.language)
                    .put(F_BODY, s.body)
                    .put(F_USAGE, s.usageCount),
            )
        }
        kv.putString(KEY, JSONObject().put(F_SNIPPETS, arr).toString())
    }

    private fun nextId(all: List<Snippet>): String {
        var max = 0
        for (s in all) {
            val n = s.id.removePrefix(ID_PREFIX).toIntOrNull() ?: continue
            if (n > max) {
                max = n
            }
        }
        return ID_PREFIX + (max + 1)
    }

    companion object {
        const val KEY = "snippets_v1"
        const val LANG_ALL = "all"
        const val MAX_NAME_CHARS = 80
        const val MAX_BODY_CHARS = 64_000
        private const val ID_PREFIX = "s"
        private const val F_SNIPPETS = "snippets"
        private const val F_ID = "id"
        private const val F_NAME = "name"
        private const val F_LANGUAGE = "language"
        private const val F_BODY = "body"
        private const val F_USAGE = "usage"

        fun normalizeLanguage(language: String): String = language.trim().lowercase().ifEmpty { LANG_ALL }
    }
}
