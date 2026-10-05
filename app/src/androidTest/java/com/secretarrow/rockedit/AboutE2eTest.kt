package com.secretarrow.rockedit

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the About dialog (v0.19.0).
 *
 * The dialog is launched deterministically through AboutDialog.show() on the
 * real MainActivity — this exercises the real layout inflation, the real
 * localized resources and the AboutInfo wiring without depending on overflow
 * menu automation, which proved device-specific on the headless CI emulator
 * (insets-E2E lesson: assert device-agnostic properties only).
 *
 * "Maragung" is locale-independent by design, so the credit assertion is
 * safe in every language.
 */
@RunWith(AndroidJUnit4::class)
class AboutE2eTest {
    @Test
    fun aboutDialogShowsCreator() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            com.secretarrow.rockedit.ui.AboutDialog
                .show(activity)
        }
        onView(withId(R.id.about_creator)).check(matches(withText(containsString("Maragung"))))
        onView(
            allOf(
                withText(R.string.about_title),
                isDisplayed(),
            ),
        ).check(matches(isDisplayed()))
        pressBack()
    }

    @Test
    fun aboutDialogShowsAppNameAndVersionLine() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            com.secretarrow.rockedit.ui.AboutDialog
                .show(activity)
        }
        // Version comes from BuildConfig at runtime; assert the app-name part
        // (locale-agnostic) plus the version digit pattern of this release.
        onView(withId(R.id.about_name_version)).check(
            matches(withText(containsString("Rock Edit"))),
        )
        onView(withId(R.id.about_name_version)).check(
            matches(withText(containsString("0.19"))),
        )
        pressBack()
    }

    @Test
    fun aboutDialogFallbackPathStillShowsCreator() {
        // Defensive branch: even the minimal fallback dialog must name the
        // creator. We exercise the pure logic the fallback composes (the
        // fallback itself only triggers if inflation fails, which cannot be
        // forced without breaking the app module).
        val credit =
            com.secretarrow.rockedit.core.AboutInfo.creditLine(
                ApplicationProvider
                    .getApplicationContext<android.content.Context>()
                    .getString(com.secretarrow.rockedit.R.string.about_creator_label),
            )
        org.junit.Assert.assertTrue(credit.contains("Maragung"))
    }
}
