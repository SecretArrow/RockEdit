package com.secretarrow.rockedit

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.StorageManagerActivity
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Storage Manager e2e: add an FTP connection through the editor dialog and
 * verify it is listed. No network I/O happens here.
 */
@RunWith(AndroidJUnit4::class)
class StorageManagerE2eTest {
    @Test
    fun addsConnectionAndListsIt() {
        ActivityScenario.launch<StorageManagerActivity>(
            Intent(ApplicationProvider.getApplicationContext(), StorageManagerActivity::class.java),
        )

        onView(withId(R.id.btn_add_connection)).perform(click())

        // The dialog is a ScrollView; scroll to each field before typing.
        onView(withId(R.id.input_name)).perform(scrollTo(), typeText("ci-ftp"), closeSoftKeyboard())
        onView(withId(R.id.input_host)).perform(scrollTo(), typeText("127.0.0.1"), closeSoftKeyboard())
        onView(withId(R.id.input_user)).perform(scrollTo(), typeText("ci"), closeSoftKeyboard())
        onView(withId(R.id.input_password)).perform(scrollTo(), typeText("pw"), closeSoftKeyboard())

        onView(withText(R.string.save)).perform(click())

        // Poll until the dialog is dismissed and the list shows the entry.
        val deadline = System.currentTimeMillis() + 5000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            try {
                onView(withId(R.id.connections)).check(
                    matches(hasDescendant(withText(containsString("ci-ftp")))),
                )
                found = true
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        assertTrue("Connection never appeared in the list", found)
    }
}
