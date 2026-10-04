package com.secretarrow.rockedit

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v0.11.0 productivity tools end-to-end: the Text Tools dialog actually
 * mutates the document (sort proof), and the Regex Tester reports matches
 * without ever touching the document.
 */
@RunWith(AndroidJUnit4::class)
class TextToolsE2eTest {

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

    /** Opens the editor overflow and taps a menu entry regardless of layout. */
    private fun tapOverflowItem(titleRes: Int) {
        try {
            onView(withText(titleRes)).perform(click())
        } catch (_: Throwable) {
            Espresso.openActionBarOverflowOrOptionsMenu(
                ApplicationProvider.getApplicationContext<android.content.Context>()
            )
            onView(withText(titleRes)).perform(click())
        }
    }

    private fun waitUntil(block: () -> Boolean, what: String, timeoutMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (block()) return
            } catch (t: Throwable) {
                lastError = t
            }
            Thread.sleep(100)
        }
        assertTrue("Timed out waiting for: $what (last=$lastError)", false)
    }

    @Test
    fun textToolsSortLinesMutatesDocument() {
        val intent = newTestFile("e2e_tools.txt", "cherry\napple\nbanana")
        ActivityScenario.launch<EditorActivity>(intent)

        waitUntil({
            onView(withId(R.id.editor)).check(matches(withText(containsString("cherry"))))
            true
        }, "editor loaded")

        tapOverflowItem(R.string.text_tools)
        // Custom dialog: rows are plain children of a ScrollView, so they are
        // always laid out; scrollTo() + click() is deterministic on any size.
        waitUntil({
            onView(withText("Sort lines (A→Z)"))
                .perform(scrollTo(), click())
            true
        }, "sort row clicked")

        waitUntil({
            onView(withId(R.id.editor)).check(
                matches(withText("apple\nbanana\ncherry"))
            )
            true
        }, "document sorted")
    }

    @Test
    fun regexTesterReportsMatchesWithoutMutatingDocument() {
        val original = "one two three"
        val intent = newTestFile("e2e_regex.txt", original)
        ActivityScenario.launch<EditorActivity>(intent)

        waitUntil({
            onView(withId(R.id.editor)).check(matches(withText(containsString(original))))
            true
        }, "editor loaded")

        tapOverflowItem(R.string.regex_tester)
        onView(withId(R.id.regex_pattern)).perform(typeText("t\\w+"))
        onView(withId(R.id.regex_test_text)).perform(typeText(original))

        waitUntil({
            onView(withId(R.id.regex_results)).check(
                matches(withText(containsString("Found")))
            )
            true
        }, "regex results rendered")

        // Dismiss via the Close button (back can be swallowed by the IME),
        // then prove the document is untouched: the tester is read-only.
        var dismissed = false
        try {
            onView(withText(R.string.close)).perform(click())
            dismissed = true
        } catch (_: Throwable) {
            Espresso.pressBack()
        }
        waitUntil({
            onView(withId(R.id.editor)).check(matches(withText(containsString(original))))
            true
        }, "editor visible again with original text (dismissed=$dismissed)")
    }
}
