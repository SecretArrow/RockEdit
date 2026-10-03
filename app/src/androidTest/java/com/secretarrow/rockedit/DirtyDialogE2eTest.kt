package com.secretarrow.rockedit

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dirty-state e2e: editing shared-in text and pressing back must surface
 * the unsaved-changes dialog; Discard closes the editor.
 */
@RunWith(AndroidJUnit4::class)
class DirtyDialogE2eTest {

    @Test
    fun backWithChangesShowsUnsavedDialogAndDiscardCloses() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, EditorActivity::class.java)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "seed")
        }
        val scenario = ActivityScenario.launch<EditorActivity>(intent)

        onView(withId(R.id.editor)).check(matches(withText("seed")))
        onView(withId(R.id.editor)).perform(click(), typeText("dirty"), closeSoftKeyboard())
        onView(withId(R.id.editor)).perform(pressBack())

        onView(withText(R.string.discard_changes_title)).check(matches(isDisplayed()))
        onView(withText(R.string.discard)).perform(click())

        scenario.onActivity { activity ->
            assertTrue("Activity should be finishing after Discard", activity.isFinishing)
        }
    }
}
