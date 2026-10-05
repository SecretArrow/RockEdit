package com.secretarrow.rockedit

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E: the About dialog opens from the main overflow menu and shows the
 * creator credit. "Maragung" is locale-independent by design, so the
 * assertion is device- and language-agnostic (insets-E2E lesson applied).
 */
@RunWith(AndroidJUnit4::class)
class AboutE2eTest {
    @Test
    fun aboutDialogShowsCreator() {
        ActivityScenario.launch(MainActivity::class.java)
        openActionBarOverflowOrOptionsMenu(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        onView(withTextId(R.string.about)).perform(click())
        onView(withId(R.id.about_creator)).check(matches(withTextContains("Maragung")))
    }

    private fun withTextId(id: Int) =
        androidx.test.espresso.matcher.ViewMatchers.withText(id)

    private fun withTextContains(text: String) =
        org.hamcrest.Matchers.containsString(text)
}
