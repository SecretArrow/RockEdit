package com.secretarrow.rockedit

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Multi-tab e2e: two files open side by side as tabs; switching chips swaps
 * the editor content without losing either document.
 */
@RunWith(AndroidJUnit4::class)
class MultiTabE2eTest {

    private fun newTestFile(name: String, content: String): Intent {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, name)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setClass(context, EditorActivity::class.java)
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun waitForTabChip(namePart: String, timeoutMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            try {
                onView(
                    allOf(withParent(withId(R.id.tab_bar)), withText(containsString(namePart)))
                ).check(matches(withText(containsString(namePart))))
                found = true
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        assertTrue("Tab chip never appeared: $namePart", found)
    }

    private fun waitForEditorText(expected: String, timeoutMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            try {
                onView(withId(R.id.editor)).check(matches(withText(containsString(expected))))
                found = true
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        assertTrue("Editor never showed text: $expected", found)
    }

    @Test
    fun opensSecondFileAsNewTabAndSwitchesBetweenThem() {
        val intentA = newTestFile("e2e_multi_a.txt", "alpha content\n")
        val intentB = newTestFile("e2e_multi_b.txt", "beta content\n")

        val scenarioA = ActivityScenario.launch<EditorActivity>(intentA)
        waitForEditorText("alpha content")

        // Open the second file from the editor's own activity context: the
        // singleTask instance is reused, so the intent arrives as a new tab
        // through onNewIntent.
        scenarioA.onActivity { activity -> activity.startActivity(intentB) }
        waitForEditorText("beta content")

        waitForTabChip("e2e_multi_a.txt")
        waitForTabChip("e2e_multi_b.txt")

        // Switch back to tab A by tapping its chip; content must follow.
        onView(
            allOf(withParent(withId(R.id.tab_bar)), withText(containsString("e2e_multi_a.txt")))
        ).perform(click())
        waitForEditorText("alpha content")

        // And forward to tab B again.
        onView(
            allOf(withParent(withId(R.id.tab_bar)), withText(containsString("e2e_multi_b.txt")))
        ).perform(click())
        waitForEditorText("beta content")
    }
}
