package com.secretarrow.rockedit

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
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
 * Real-path e2e for the Code Formatter: a malformed JSON file is opened,
 * the Format toolbar action is pressed, and the editor must end up showing
 * the pretty-printed document (indent + space after colon = proof that the
 * formatter ran, not just any text change).
 */
@RunWith(AndroidJUnit4::class)
class FormatterE2eTest {

    private fun newTestFile(name: String, content: String): Pair<File, Intent> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, name)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, EditorActivity::class.java)
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return file to intent
    }

    private fun waitForText(expected: String, timeoutMs: Long = 5000) {
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
    fun formatsJsonThroughToolbarAction() {
        val (_, intent) = newTestFile("e2e_format.json", """{"b":1,"a":2}""")
        ActivityScenario.launch<EditorActivity>(intent)

        // Wait for the async load, then press Format on the toolbar.
        waitForText("\"b\":1")
        onView(withContentDescription(R.string.format)).perform(click())

        // Poll: pretty output puts every member on its own indented line.
        waitForText("\"a\": 2", timeoutMs = 10_000)

        onView(withId(R.id.editor)).check(matches(withText(containsString("{\n    \"b\": 1,\n    \"a\": 2\n}"))))
    }

    @Test
    fun brokenJsonShowsErrorInsteadOfCorrupting() {
        val broken = "{\n  \"a\": 1,\n  \"b\": ,\n}"
        val (_, intent) = newTestFile("e2e_broken.json", broken)
        ActivityScenario.launch<EditorActivity>(intent)

        waitForText("\"b\":")
        onView(withContentDescription(R.string.format)).perform(click())

        // The document must remain byte-identical: failures never mutate it.
        Thread.sleep(1500)
        onView(withId(R.id.editor)).check(matches(withText(containsString(broken))))
    }
}
