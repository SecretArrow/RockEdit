package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * Persisted state of one split-editor pane (v0.17.0 "panel ganda full-app").
 *
 * - [uri]: content:// URI string of the pane's backing document, or null for
 *   an in-memory pane (handoff buffer, typed text with no document, or a
 *   blank persisted uri). A restored URI is metadata only in v1: the split
 *   editor never reloads the document automatically and saving still goes
 *   through SAF.
 * - [name]: display name shown in the pane's status chip; a blank name is
 *   sanitized to [SplitSessionCodec.UNTITLED].
 * - [charsetName]: charset used to encode the pane on save; an unsupported,
 *   blank, or illegal name is sanitized to UTF-8 on restore.
 * - [savedText]: the pane's content as last saved/loaded; in the
 *   snapshot-on-stop model it holds the CURRENT live text at persist time.
 * - [wasDirty]: dirty flag at persist time (true when the pane had unsaved
 *   edits in the snapshot).
 */
data class SplitPaneState(
    val uri: String?,
    val name: String,
    val charsetName: String,
    val savedText: String,
    val wasDirty: Boolean = false,
)

/**
 * Codec for the split-editor session snapshot: a compact JSON array of
 * exactly two pane objects
 * `[{"uri":...,"name":...,"charset":...,"text":...,"dirty":...},{...}]`.
 * Pure JVM (org.json + java.nio.charset only) and never throws on hostile
 * input, so a corrupted snapshot can never crash the split editor. String
 * escaping inside the JSON is handled by org.json.
 *
 * Snapshot-on-stop model: the caller persists the CURRENT live text of both
 * panes plus their dirty flags; on restore the text goes back into the
 * editors and the dirty flags are taken from [SplitPaneState.wasDirty] (the
 * saved baseline of a dirty pane is unknowable after process death, so a
 * live-vs-baseline comparison is impossible - the persisted flag is the
 * truth).
 *
 * Corruption handling table (authoritative for [decode]):
 *
 * | Input problem                            | Result                          |
 * |------------------------------------------|---------------------------------|
 * | blank / unparseable snapshot             | all-empty session               |
 * | JSON root is not an array                | all-empty session               |
 * | array length != 2                        | all-empty session               |
 * | element is not a JSON object             | all-empty session               |
 * | missing fields                           | defaults, then sanitized        |
 * | unsupported / blank / illegal charset    | pane kept, charset to UTF-8     |
 * | blank name                               | pane kept, name to "untitled"   |
 * | per-pane text over [MAX_TEXT_CHARS]      | pane kept, truncated (marker)   |
 * | combined sanitized text over session cap | all-empty session (defense)     |
 *
 * Oversize handling on [encode] is different by design: encode stores the
 * values as given and returns null when the combined text exceeds
 * [MAX_SESSION_CHARS] so the caller skips persisting entirely; only decode
 * sanitizes.
 */
object SplitSessionCodec {
    /** Combined cap for both panes' savedText; exceeding it skips persisting. */
    const val MAX_SESSION_CHARS = 2_000_000

    /** Per-pane cap (mirrors the split editor's own 1M character guard). */
    const val MAX_TEXT_CHARS = 1_000_000

    /**
     * Trailing marker appended by [sanitize] when a text had to be
     * truncated. Truncation is a last-resort fail-safe for hostile or
     * drifted snapshots (normal flow never exceeds the cap because the split
     * editor refuses oversized panes on open). The marker is INCLUDED in the
     * [MAX_TEXT_CHARS] budget, so a truncated pane is exactly
     * [MAX_TEXT_CHARS] characters long.
     */
    const val TRUNCATION_MARKER = "\n…[truncated]"

    /** Fallback display name for a blank pane name. */
    const val UNTITLED = "untitled"

    private const val F_URI = "uri"
    private const val F_NAME = "name"
    private const val F_CHARSET = "charset"
    private const val F_TEXT = "text"
    private const val F_DIRTY = "dirty"

    /**
     * Serializes both panes into the compact JSON array documented on the
     * class. Returns null when the combined [SplitPaneState.savedText]
     * length exceeds [MAX_SESSION_CHARS] - the caller must skip persisting
     * (nothing is written). The values are stored as given; call [sanitize]
     * first for a guaranteed-safe artifact (decode sanitizes regardless, so
     * hostile or drifted JSON stays safe).
     */
    fun encode(
        a: SplitPaneState,
        b: SplitPaneState,
    ): String? {
        if (a.savedText.length + b.savedText.length > MAX_SESSION_CHARS) return null
        val arr = JSONArray()
        arr.put(paneToJson(a))
        arr.put(paneToJson(b))
        return arr.toString()
    }

    /**
     * Parses a persisted snapshot; never throws (see the corruption table on
     * the class). Missing fields map to defaults (null uri, empty name,
     * UTF-8, empty text, not dirty) and the panes are then passed through
     * [sanitize].
     */
    fun decode(json: String): Pair<SplitPaneState, SplitPaneState> {
        val arr = parseArray(json) ?: return emptySession()
        if (arr.length() != 2) return emptySession()
        val first = arr.optJSONObject(0) ?: return emptySession()
        val second = arr.optJSONObject(1) ?: return emptySession()
        val a = sanitize(paneFromJson(first))
        val b = sanitize(paneFromJson(second))
        if (a.savedText.length + b.savedText.length > MAX_SESSION_CHARS) {
            // Unreachable with the shipped constants (2 x MAX_TEXT_CHARS ==
            // MAX_SESSION_CHARS); guards future constant drift so a hostile
            // snapshot can never balloon memory on restore.
            return emptySession()
        }
        return a to b
    }

    /**
     * The all-empty pane: null uri, empty name, UTF-8 charset, empty text,
     * not dirty. (The name stays empty here on purpose; [sanitize] is what
     * turns a blank name into "untitled".)
     */
    fun empty(): SplitPaneState =
        SplitPaneState(
            uri = null,
            name = "",
            charsetName = EncodingDetector.DEFAULT_CHARSET,
            savedText = "",
            wasDirty = false,
        )

    /**
     * Returns a safe copy of [state]: a blank name becomes "untitled", a
     * blank or JVM-unsupported charset (including illegal names such as
     * "bad name", which make [Charset.isSupported] throw) becomes UTF-8, a
     * null-or-blank uri becomes null (in-memory pane), and a savedText
     * longer than [MAX_TEXT_CHARS] is truncated to exactly [MAX_TEXT_CHARS]
     * characters with [TRUNCATION_MARKER] as the trailing part. [wasDirty]
     * is passed through untouched.
     */
    fun sanitize(state: SplitPaneState): SplitPaneState {
        val text =
            if (state.savedText.length > MAX_TEXT_CHARS) {
                val keep = (MAX_TEXT_CHARS - TRUNCATION_MARKER.length).coerceAtLeast(0)
                state.savedText.take(keep) + TRUNCATION_MARKER
            } else {
                state.savedText
            }
        return SplitPaneState(
            uri = state.uri?.takeIf { it.isNotBlank() },
            name = state.name.ifBlank { UNTITLED },
            charsetName =
                if (isSupportedCharset(state.charsetName)) {
                    state.charsetName
                } else {
                    EncodingDetector.DEFAULT_CHARSET
                },
            savedText = text,
            wasDirty = state.wasDirty,
        )
    }

    private fun encodePane(p: SplitPaneState): JSONObject =
        JSONObject()
            .put(F_URI, p.uri ?: "")
            .put(F_NAME, p.name)
            .put(F_CHARSET, p.charsetName)
            .put(F_TEXT, p.savedText)
            .put(F_DIRTY, p.wasDirty)

    private fun parseArray(json: String): JSONArray? =
        try {
            JSONArray(json)
        } catch (_: Exception) {
            // Corrupt snapshot (truncated write, foreign data): the caller
            // maps null to the all-empty session.
            null
        }

    private fun paneFromJson(o: JSONObject): SplitPaneState =
        SplitPaneState(
            uri = o.optString(F_URI).ifEmpty { null },
            name = o.optString(F_NAME),
            charsetName = o.optString(F_CHARSET, EncodingDetector.DEFAULT_CHARSET),
            savedText = o.optString(F_TEXT),
            wasDirty = o.optBoolean(F_DIRTY, false),
        )

    private fun emptySession(): Pair<SplitPaneState, SplitPaneState> = empty() to empty()

    private fun isSupportedCharset(charsetName: String): Boolean =
        try {
            charsetName.isNotBlank() && Charset.isSupported(charsetName)
        } catch (_: Exception) {
            // Illegal charset name (IllegalCharsetNameException): unsupported.
            false
        }
}
