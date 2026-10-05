package com.secretarrow.rockedit.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * v0.18.0: one shared system-bar installer so no screen draws under the
 * status bar (top) or the gesture/button navigation bar (bottom).
 *
 * Why a helper: the app targets SDK 36, so Android 15+ forces edge-to-edge
 * (window content extends behind both system bars) and ignores
 * `statusBarColor`. Before this helper only zen mode touched insets, so
 * every activity overlapped the bars on modern devices.
 *
 * Scenario -> handling table (defensive rules):
 * - Normal API 29+ window      -> decorFits(false); root padding = system
 *   bars + display cutout + IME (union per edge), so the toolbar clears the
 *   status bar and lists/FABs clear the navigation bar and keyboard.
 * - API 26-28 window           -> enableEdgeToEdge applies its own scrims
 *   and keeps decor fitting; the DecorView consumes the insets, this
 *   listener sees zeros and adds nothing (no double padding).
 * - Bars hidden (zen/fullscreen)-> insets report 0 -> padding falls back to
 *   the captured base padding; exiting zen re-shows bars and a new inset
 *   dispatch restores the padding automatically.
 * - IME visible                -> `ime()` joined into the requested types so
 *   the bottom padding tracks the keyboard (adjustResize behaviour that
 *   Android 11+ requires apps to implement themselves when not fitting).
 * - Landscape cutout           -> displayCutout() joined in; left/right
 *   padding keeps content off the notch.
 * - Insets dispatched repeatedly (rotation, IME, bar visibility) -> padding
 *   is always computed from the captured base + current insets, never
 *   accumulated, so repeated dispatches are idempotent.
 * - Unusual root padding (in-app OAuth screen built in code) -> the base
 *   padding is captured at install time and preserved under the insets.
 */
object SystemBars {
    private val watchedTypes =
        WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()

    /**
     * Makes [activity] edge-to-edge and pads [root] by the current insets.
     * Call once from onCreate after setContentView. Idempotent: a second
     * call only replaces the listener and re-captures the base padding.
     * Takes [ComponentActivity] because enableEdgeToEdge is defined on it;
     * every Rock Edit screen is an AppCompatActivity, which qualifies.
     */
    fun install(
        activity: ComponentActivity,
        root: View,
    ) {
        activity.enableEdgeToEdge()
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(watchedTypes)
            view.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
        applyIconAppearance(activity, root)
    }

    /**
     * Keeps status-bar icons readable in both themes: dark icons on a light
     * surface, light icons on dark/AMOLED. The activity window is always
     * attached by the time onCreate runs install(), and the night mode is
     * read from the effective configuration so it also reacts to the app's
     * light/dark/AMOLED setting, not just the system toggle.
     */
    private fun applyIconAppearance(
        activity: Activity,
        root: View,
    ) {
        val window = activity.window
        val nightMode =
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val controller = WindowInsetsControllerCompat(window, root)
        controller.isAppearanceLightStatusBars = nightMode != Configuration.UI_MODE_NIGHT_YES
        controller.isAppearanceLightNavigationBars = nightMode != Configuration.UI_MODE_NIGHT_YES
    }
}
