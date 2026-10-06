package com.secretarrow.rockedit

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the zen mode exit paths (v0.20.0): the visible exit FAB and the
 * Back gesture. Entering zen hides the toolbar, so before this change there
 * was no on-screen way out.
 *
 * Overflow automation is reliable on EditorActivity (options menu is provided
 * via onCreateOptionsMenu, unlike MainActivity) — the same proven pattern as
 * TextToolsE2eTest.
 */
@RunWith(AndroidJUnit4::class)
class ZenExitE2eTest {
    private fun launchEditor(): ActivityScenario<EditorActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                setClass(context, EditorActivity::class.java)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "zen seed")
            }
        return ActivityScenario.launch(intent)
    }

    /** Delegates to the shared overflow helper (handles deep, recycled rows). */
    private fun tapOverflowItem(titleRes: Int) = OverflowMenu.tap(titleRes)

    @Test
    fun exitFabLeavesZenAndRestoresToolbar() {
        launchEditor()
        tapOverflowItem(R.string.zen_mode)
        // Zen hides the toolbar and shows the exit FAB.
        onView(withId(R.id.toolbar)).check(
            matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)),
        )
        onView(withId(R.id.zen_exit)).check(matches(isDisplayed()))
        onView(withId(R.id.zen_exit)).perform(click())
        // Exit restores the toolbar and hides the FAB again.
        onView(withId(R.id.toolbar)).check(
            matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)),
        )
    }

    @Test
    fun backPressLeavesZenFirst() {
        launchEditor()
        tapOverflowItem(R.string.zen_mode)
        onView(withId(R.id.zen_exit)).check(matches(isDisplayed()))
        // Back is owned by zen first: one press leaves zen, not the activity.
        pressBack()
        onView(withId(R.id.toolbar)).check(
            matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)),
        )
    }
}
