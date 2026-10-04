package com.secretarrow.rockedit

import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withChild
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
 *
 * The second file is delivered through [EditorActivity.onNewIntent] — the
 * entry point the system uses for a singleTask editor that is already on
 * screen (e.g. when another app or the folder browser opens a file).
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

    /** Reads the current chip labels from the tab bar for diagnostics. */
    private fun chipNames(scenario: ActivityScenario<EditorActivity>): List<String> {
        var names = listOf<String>()
        scenario.onActivity { activity ->
            val bar = activity.findViewById<LinearLayout>(R.id.tab_bar)
            names = (0 until bar.childCount).mapNotNull { c ->
                bar.getChildAt(c).findViewById<TextView>(R.id.tab_name)?.text?.toString()
            }
        }
        return names
    }

    private fun waitForTabChip(
        scenario: ActivityScenario<EditorActivity>,
        namePart: String,
        timeoutMs: Long = 8000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var matched = false
        var lastError = ""
        while (System.currentTimeMillis() < deadline && !matched) {
            try {
                chipMatcher(namePart).check(matches(isDisplayed()))
                matched = true
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}"
                Thread.sleep(100)
            }
        }
        assertTrue(
            "Tab chip never appeared: $namePart; chips were ${chipNames(scenario)}; last error: $lastError",
            matched
        )
    }

    private fun chipMatcher(namePart: String) =
        onView(
            allOf(
                withId(R.id.tab_chip),
                withChild(withText(containsString(namePart)))
            )
        )

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

        val scenario = ActivityScenario.launch<EditorActivity>(intentA)
        waitForEditorText("alpha content")

        scenario.onActivity { activity -> activity.onNewIntent(intentB) }
        waitForEditorText("beta content")

        waitForTabChip(scenario, "e2e_multi_a.txt")
        waitForTabChip(scenario, "e2e_multi_b.txt")

        // Switch back to tab A by tapping its chip; content must follow.
        // The tab bar may have scrolled the chip out of view (the active tab
        // auto-scrolls into view), so scroll it back before clicking.
        chipMatcher("e2e_multi_a.txt").perform(scrollTo(), click())
        waitForEditorText("alpha content")

        // And forward to tab B again.
        chipMatcher("e2e_multi_b.txt").perform(scrollTo(), click())
        waitForEditorText("beta content")
    }
}
