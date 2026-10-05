package com.secretarrow.rockedit.ui

import android.content.Intent
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.secretarrow.rockedit.BuildConfig
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.AboutInfo

/**
 * Reusable About dialog (v0.19.0) with creator credit ("Maragung").
 *
 * Used from MainActivity (menu) and EditorActivity (menu), so it lives here
 * once instead of being duplicated per activity. Returns the shown dialog
 * (or null) so callers and tests can inspect/dismiss it.
 *
 * Defensive scenario map:
 *  - finishing/destroyed context -> returns null without showing (anti window-leak).
 *  - layout inflation failure / missing views -> dialog still opens with the
 *    classic title+message fallback (never crashes, never silently no-ops).
 *  - BuildConfig version null/blank (impossible in practice, still guarded) ->
 *    AboutInfo.displayVersion() renders "unknown".
 *  - LicensesActivity launch fails (activity missing / state destroyed) ->
 *    caught, dialog dismisses normally; no dead button path.
 */
object AboutDialog {
    fun show(activity: AppCompatActivity): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        return try {
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_about, null)

            view.findViewById<TextView>(R.id.about_name_version)?.text =
                AboutInfo.titleLine(activity.getString(R.string.app_name), BuildConfig.VERSION_NAME)
            view.findViewById<TextView>(R.id.about_creator)?.text =
                AboutInfo.creditLine(activity.getString(R.string.about_creator_label))

            val dialog =
                AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.about_title)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.licenses_title, null)
                    .create()

            dialog.show()
            // Wired after show() so a failed launch can never leave a half-built dialog.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                try {
                    activity.startActivity(Intent(activity, LicensesActivity::class.java))
                    dialog.dismiss()
                } catch (_: Exception) {
                    // Keep the dialog open; the license list is also reachable
                    // from the main menu, so the user still has a path forward.
                }
            }
            dialog
        } catch (_: Exception) {
            // Fallback: minimal text dialog so About is always reachable.
            runCatching {
                AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.about_title)
                    .setMessage(
                        AboutInfo.creditLine(activity.getString(R.string.about_creator_label)) +
                            "\n" +
                            AboutInfo.titleLine(
                                activity.getString(R.string.app_name),
                                BuildConfig.VERSION_NAME,
                            ),
                    ).setPositiveButton(android.R.string.ok, null)
                    .show()
            }.getOrNull()
        }
    }
}
