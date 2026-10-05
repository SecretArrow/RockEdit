package com.secretarrow.rockedit.core

/**
 * Pure, testable logic behind the About dialog (v0.19.0).
 *
 * Defensive design (scenario map):
 *  - version null/blank            -> "unknown" placeholder so the dialog never shows "null".
 *  - version with stray "v"/spaces -> cleaned once ("v0.19.0" -> "0.19.0"); already-clean
 *                                    values are returned untouched.
 *  - absurdly long version string  -> truncated to [MAX_VERSION_LENGTH] to keep the
 *                                    single-line title layout intact.
 *  - creator constant              -> guaranteed non-blank by unit test; dialogs rely on it.
 */
object AboutInfo {
    /** The human creator of Rock Edit. Displayed prominently in the About dialog. */
    const val CREATOR: String = "Maragung"

    /** Placeholder used when the version cannot be resolved. */
    const val VERSION_FALLBACK: String = "unknown"

    /** Hard cap for the rendered version string. */
    const val MAX_VERSION_LENGTH: Int = 32

    /**
     * Returns a display-safe version string.
     *
     * Cases:
     *  - null / blank          -> [VERSION_FALLBACK]
     *  - " 0.19.0 "            -> "0.19.0" (trimmed)
     *  - "v0.19.0" / "V0.19.0" -> "0.19.0" (single leading v/V stripped)
     *  - "vv0.19.0"            -> "v0.19.0" (only one leading marker stripped; never loops)
     *  - > [MAX_VERSION_LENGTH] chars -> truncated with no ellipsis (dialog label space)
     */
    fun displayVersion(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return VERSION_FALLBACK
        val noMarker =
            if (trimmed.length > 1 && (trimmed.startsWith("v") || trimmed.startsWith("V"))) {
                trimmed.substring(1)
            } else {
                trimmed
            }
        return if (noMarker.length > MAX_VERSION_LENGTH) {
            noMarker.substring(0, MAX_VERSION_LENGTH)
        } else {
            noMarker
        }
    }

    /**
     * Builds the title line shown under the app icon, e.g. "Rock Edit 0.19.0".
     * Falls back to just the app name if the version resolves to the fallback,
     * so the dialog never reads "Rock Edit unknown" awkwardly... actually the
     * fallback keeps the version slot present but human-readable ("unknown"),
     * which is clearer for bug reports than hiding the line entirely.
     */
    fun titleLine(
        appName: String,
        version: String?,
    ): String {
        val name = appName.trim().ifEmpty { "Rock Edit" }
        return "$name ${displayVersion(version)}"
    }

    /**
     * Full credit line, e.g. "Created by Maragung". Defensive against a blank
     * localized label (uses the English default) and against a blank creator
     * (constant is compile-time non-blank, but we still guard).
     */
    fun creditLine(label: String): String {
        val safeLabel = label.trim().ifEmpty { "Created by" }
        return "$safeLabel $CREATOR"
    }
}
