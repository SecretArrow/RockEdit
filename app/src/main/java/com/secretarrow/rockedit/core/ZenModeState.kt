package com.secretarrow.rockedit.core

/**
 * Immutable snapshot of the editor UI state taken right before zen mode is
 * entered. It is carried inside [ZenActive] for the whole zen session and is
 * the single source of truth for restoring the UI on exit.
 *
 * @property toolbarVisible whether the editor toolbar was visible pre-zen.
 * @property tabsVisible whether the tab strip was visible pre-zen.
 * @property fontSizeSp editor font size in sp, always within
 *   [MIN_FONT_SP]..[MAX_FONT_SP] (the constructor throws otherwise, and so
 *   does [copy] — the invariant holds for every produced instance).
 *
 * Defensive rule: NaN and infinite font sizes are rejected by the range
 * check because they are never "inside" a closed range. This is deliberate:
 * [Float.coerceIn] is not stable on NaN (it would silently return NaN), so
 * raw values from untrusted sources (for example restored from a Bundle)
 * must go through [ZenMode.sanitizeSnapshot] instead of this constructor.
 */
data class ZenSnapshot(
    val toolbarVisible: Boolean,
    val tabsVisible: Boolean,
    val fontSizeSp: Float,
) {
    init {
        require(fontSizeSp in MIN_FONT_SP..MAX_FONT_SP) {
            "fontSizeSp must be in $MIN_FONT_SP..$MAX_FONT_SP, was: $fontSizeSp"
        }
    }

    companion object {
        const val MIN_FONT_SP = 8f

        const val MAX_FONT_SP = 40f

        /** Fallback font size for non-finite (NaN/infinite) input values. */
        const val DEFAULT_FONT_SP = 14f
    }
}

/**
 * Outcome of a [ZenMode.enter] or [ZenMode.exit] call. Zen transitions are
 * explicit and total: every call maps to exactly one outcome, so the host UI
 * can handle them exhaustively with a `when`.
 */
sealed class ZenResult {
    /** Zen was entered; [state] carries the pre-zen snapshot to restore. */
    data class Entered(val state: ZenActive) : ZenResult()

    /**
     * Zen was already active: the original [ZenActive] (and its
     * [ZenActive.snapshot]) is preserved and the newly offered snapshot is
     * ignored. The caller keeps using the [ZenActive] reference it holds.
     */
    object AlreadyActive : ZenResult()

    /** Zen was exited; restore the UI from the [ZenActive.snapshot] held. */
    object Exited : ZenResult()

    /** No zen session was active, so there is nothing to restore. */
    object NotActive : ZenResult()
}

/**
 * An active zen session: an immutable holder of the pre-zen [snapshot].
 *
 * Construction is restricted (internal constructor) so [ZenMode.enter] is
 * the only path to an active session; no code can activate zen without a
 * snapshot to restore from. Equality is identity: two sessions are the same
 * object only if the state machine handed out the same instance.
 */
class ZenActive internal constructor(
    val snapshot: ZenSnapshot,
) {
    /**
     * Editor font size while zen is active: [ZenSnapshot.fontSizeSp] plus
     * [FONT_BOOST_SP], capped at [ZenSnapshot.MAX_FONT_SP] so a pre-zen
     * font that is already at the limit never grows beyond it.
     */
    val zenFontSizeSp: Float =
        (snapshot.fontSizeSp + FONT_BOOST_SP).coerceAtMost(ZenSnapshot.MAX_FONT_SP)

    companion object {
        /** Extra sp added to the editor font while zen is active. */
        const val FONT_BOOST_SP = 2f
    }
}

/**
 * State machine for zen mode (backlog item 13): a distraction-free
 * full-screen writing mode. In v1 the host UI hides the toolbar and the tab
 * strip, keeps the editor (gutter included) visible, applies
 * [ZenActive.zenFontSizeSp] and an immersive sticky system-bar policy.
 *
 * Pure JVM with immutable value semantics: this object holds no state of its
 * own. The caller owns the current [ZenActive] (or null while zen is off)
 * and passes it into every call. All functions are total: they never throw
 * for any input of the declared types — [ZenSnapshot] instances are already
 * valid by construction, and [sanitizeSnapshot] normalizes anything else.
 *
 * Idempotency (rotation safety): while zen is active, a second [enter] —
 * for example after a configuration change rebuilds the activity with a
 * freshly assembled snapshot — returns [ZenResult.AlreadyActive] and leaves
 * the original [ZenActive] untouched. The pre-zen snapshot captured first
 * is therefore never overwritten by values measured while zen is already
 * applied (which would corrupt the exit restore step).
 *
 * Exit contract: [exit] only reports the outcome; the caller restores the
 * UI from the [ZenActive.snapshot] it still holds and must then drop the
 * session reference. The machine is stateless, so passing a stale
 * (already-exited) session again would still report [ZenResult.Exited] —
 * pass null instead once zen is off.
 *
 * Documented decisions (v1):
 * - Zen hides the toolbar and the tab strip as a whole; there is no
 *   per-component granularity yet, and the gutter always stays visible.
 * - Zen is a session-scoped state and is intentionally NOT persisted across
 *   app sessions (no KeyValueStore key, no settings entry): after process
 *   death or a fresh launch zen is simply off. Surviving rotation is the
 *   job of the idempotent [enter] plus [sanitizeSnapshot] for any
 *   Bundle-restored values the host chooses to keep.
 * - The host applies the immersive sticky window flags itself; core only
 *   supplies the zen font size and the snapshot to restore from.
 */
object ZenMode {
    /**
     * Enters zen mode.
     *
     * @param snapshot pre-zen UI state; build it directly (the constructor
     *   validates the font range) or via [sanitizeSnapshot] for untrusted
     *   values.
     * @param currentlyActive the active session, or null when zen is off.
     * @return [ZenResult.Entered] with a fresh [ZenActive] when zen was
     *   off; [ZenResult.AlreadyActive] when it was already on (the original
     *   session and its snapshot are preserved; [snapshot] is ignored).
     */
    fun enter(
        snapshot: ZenSnapshot,
        currentlyActive: ZenActive?,
    ): ZenResult =
        if (currentlyActive == null) {
            ZenResult.Entered(ZenActive(snapshot))
        } else {
            ZenResult.AlreadyActive
        }

    /**
     * Exits zen mode.
     *
     * @param currentlyActive the active session, or null when zen is off.
     * @return [ZenResult.Exited] when a session was active (restore the UI
     *   from `currentlyActive.snapshot`); [ZenResult.NotActive] when zen
     *   was off.
     */
    fun exit(currentlyActive: ZenActive?): ZenResult =
        if (currentlyActive == null) {
            ZenResult.NotActive
        } else {
            ZenResult.Exited
        }

    /**
     * Builds a [ZenSnapshot] from possibly-corrupted values (defensive
     * funnel for values restored from a Bundle or user preferences):
     * - NaN and infinite font sizes become [ZenSnapshot.DEFAULT_FONT_SP]
     *   (checked explicitly because [Float.coerceIn] is not stable on NaN);
     * - finite font sizes are clamped into [ZenSnapshot.MIN_FONT_SP] ..
     *   [ZenSnapshot.MAX_FONT_SP];
     * - the visibility flags are already normalized by their Boolean type
     *   and pass through unchanged (a Bundle can only carry real booleans).
     */
    fun sanitizeSnapshot(
        toolbarVisible: Boolean,
        tabsVisible: Boolean,
        fontSizeSp: Float,
    ): ZenSnapshot {
        val safeFont = if (fontSizeSp.isNaN() || fontSizeSp.isInfinite()) {
            ZenSnapshot.DEFAULT_FONT_SP
        } else {
            fontSizeSp.coerceIn(ZenSnapshot.MIN_FONT_SP, ZenSnapshot.MAX_FONT_SP)
        }
        return ZenSnapshot(toolbarVisible, tabsVisible, safeFont)
    }
}
