package com.secretarrow.rockedit

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke e2e for the main screen: the launcher activity renders its
 * recent files surface and the new-file FAB.
 */
@RunWith(AndroidJUnit4::class)
class MainSmokeE2eTest {

    @Test
    fun mainActivityShowsRecentsAndFab() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.recents_list)).check(matches(isDisplayed()))
        onView(withId(R.id.fab_new)).check(matches(isDisplayed()))
    }
}
