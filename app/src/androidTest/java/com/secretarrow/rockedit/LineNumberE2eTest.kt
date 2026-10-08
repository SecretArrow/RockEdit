package com.secretarrow.rockedit

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.SettingsRepository
import com.secretarrow.rockedit.ui.EditorActivity
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v0.24.0 e2e for gutter numbering modes. The mode is written into the real
 * KeyValueStore BEFORE launch; each test waits for the async file load, then
 * moves the caret inside onActivity and asserts the gutter text directly —
 * the deterministic view-level pattern (no Espresso root/window-focus
 * dependency). Programmatic setSelection fires onSelectionChanged, which is
 * exactly the production path keyboard navigation uses, so the assertion
 * also proves the caret-follow hook works.
 *
 * All keys are removed in [resetSettings]: leaked state would silently
 * change other editor tests' gutters (Task 16 lesson).
 */
@RunWith(AndroidJUnit4::class)
class LineNumberE2eTest {
    private val content = "alpha\nbravo\ncharlie\ndelta\necho"

    // Start offsets of every line inside [content].
    private val line3Start = "alpha\nbravo\n".length // 12

    private fun newTestFile(
        name: String,
        text: String,
    ): Pair<File, Intent> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, name)
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setClass(context, EditorActivity::class.java)
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        return file to intent
    }

    private fun waitForLoaded(timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            try {
                onView(withId(R.id.editor)).check(matches(withText(containsString("charlie"))))
                found = true
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        assertTrue("Editor never showed the file content", found)
    }

    @After
    fun resetSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        App.keyValueStore(context).remove(SettingsRepository.KEY_LINE_NUMBERING)
    }

    private fun setMode(mode: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        App.keyValueStore(context).putString(SettingsRepository.KEY_LINE_NUMBERING, mode)
    }

    @Test
    fun relativeModeFollowsTheCaret() {
        setMode(SettingsRepository.NUMBERING_RELATIVE)
        val (_, intent) = newTestFile("e2e_lines_relative.txt", content)
        val scenario = ActivityScenario.launch<EditorActivity>(intent)

        waitForLoaded()
        scenario.onActivity { activity ->
            val editor = activity.findViewById<TextView>(R.id.editor)
            val gutter = activity.findViewById<TextView>(R.id.gutter)

            editor.setSelection(line3Start)
            assertEquals("2\n1\n0\n1\n2", gutter.text.toString())

            // Moving the caret WITHOUT changing the text must repaint the
            // gutter — the whole point of the onSelectionChanged hook.
            editor.setSelection(0)
            assertEquals("0\n1\n2\n3\n4", gutter.text.toString())
        }
        scenario.close()
    }

    @Test
    fun hybridShowsAbsoluteOnlyOnCaretLine() {
        setMode(SettingsRepository.NUMBERING_HYBRID)
        val (_, intent) = newTestFile("e2e_lines_hybrid.txt", content)
        val scenario = ActivityScenario.launch<EditorActivity>(intent)

        waitForLoaded()
        scenario.onActivity { activity ->
            val editor = activity.findViewById<TextView>(R.id.editor)
            val gutter = activity.findViewById<TextView>(R.id.gutter)

            editor.setSelection(line3Start)
            assertEquals("2\n1\n3\n1\n2", gutter.text.toString())
        }
        scenario.close()
    }

    @Test
    fun absoluteModeStaysTheHistoricalDefault() {
        setMode(SettingsRepository.NUMBERING_ABSOLUTE)
        val (_, intent) = newTestFile("e2e_lines_absolute.txt", content)
        val scenario = ActivityScenario.launch<EditorActivity>(intent)

        waitForLoaded()
        scenario.onActivity { activity ->
            val gutter = activity.findViewById<TextView>(R.id.gutter)
            assertEquals("1\n2\n3\n4\n5", gutter.text.toString())
        }
        scenario.close()
    }
}
