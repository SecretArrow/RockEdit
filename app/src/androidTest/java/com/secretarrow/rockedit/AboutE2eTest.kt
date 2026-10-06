package com.secretarrow.rockedit

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.core.AboutInfo
import com.secretarrow.rockedit.ui.AboutDialog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the About dialog (v0.19.0).
 *
 * The dialog is shown on the real MainActivity and inspected through its own
 * window, bypassing Espresso's root picker — headless CI emulators report
 * window focus unreliably for dialog windows (RootViewWithoutFocusException),
 * the same device-specific class of failure as the insets E2E. The assertions
 * here are therefore fully deterministic on any device.
 *
 * Coverage: real layout inflation, real localized resources, AboutInfo wiring
 * (name+version line, creator credit), both action buttons, and dismissal.
 * "Maragung" is locale-independent by design.
 */
@RunWith(AndroidJUnit4::class)
class AboutE2eTest {
    @Test
    fun aboutDialogShowsCreatorTitleAndButtons() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val dialog = AboutDialog.show(activity)
            assertNotNull("About dialog should be shown", dialog)
            val shown = dialog!!
            try {
                val decor = shown.window?.decorView
                assertNotNull("Dialog window must exist", decor)

                val credit = decor!!.findViewById<TextView>(R.id.about_creator)?.text?.toString()
                assertTrue("Credit must name the creator, got: $credit", credit?.contains("Maragung") == true)

                val titleLine = decor.findViewById<TextView>(R.id.about_name_version)?.text?.toString()
                assertTrue(
                    "Name+version line must contain app name, got: $titleLine",
                    titleLine?.contains("Rock Edit") == true,
                )
                // Version-agnostic on purpose: assert the real BuildConfig
                // version rendered (never the "unknown" fallback) so this test
                // survives future version bumps.
                assertTrue(
                    "Name+version line must not show the version fallback, got: $titleLine",
                    titleLine?.contains("unknown") == false,
                )

                assertNotNull("Positive (OK) button must exist", shown.getButton(AlertDialog.BUTTON_POSITIVE))
                assertNotNull("Neutral (Licenses) button must exist", shown.getButton(AlertDialog.BUTTON_NEUTRAL))
            } finally {
                shown.dismiss()
            }
        }
    }

    @Test
    fun aboutDialogRefusesFinishingActivity() {
        // Defensive branch of the isFinishing/isDestroyed guard in
        // AboutDialog.show(): finishing the activity first must make show()
        // a no-op (null) instead of leaking a window.
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            activity.finish()
            val dialog = AboutDialog.show(activity)
            assertTrue("show() must return null for a finishing activity", dialog == null)
        }
    }

    @Test
    fun creditLineFromRealResourcesNamesCreator() {
        // The minimal fallback dialog composes its message from this exact
        // logic; verified here against the real localized resource of the APK.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val credit = AboutInfo.creditLine(context.getString(R.string.about_creator_label))
        assertTrue(credit.contains("Maragung"))
        assertTrue(credit.startsWith(context.getString(R.string.about_creator_label).trim()))
    }
}
