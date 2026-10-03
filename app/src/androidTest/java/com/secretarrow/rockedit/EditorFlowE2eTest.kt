package com.secretarrow.rockedit

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
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
 * Full real-path e2e: create a physical file, open it through a content URI
 * (the same contract used by the system picker), edit it, save it, and
 * verify the bytes actually landed on disk.
 */
@RunWith(AndroidJUnit4::class)
class EditorFlowE2eTest {

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

    private fun awaitFileContent(file: File, expected: String, timeoutMs: Long = 5000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var content = file.readText()
        while (System.currentTimeMillis() < deadline && !content.contains(expected)) {
            Thread.sleep(100)
            content = file.readText()
        }
        return content
    }

    @Test
    fun opensEditsAndSavesRealFile() {
        val (file, intent) = newTestFile("e2e_rock.txt", "hello rock\n")
        ActivityScenario.launch<EditorActivity>(intent)

        // Wait for the async load to complete before interacting.
        waitForText("hello rock")

        onView(withId(R.id.editor)).perform(click(), typeText("ROCK-EDIT"))
        onView(withContentDescription(R.string.save)).perform(click())

        // The save goes through a coroutine; poll the file on disk.
        val content = awaitFileContent(file, "ROCK-EDIT")
        assertTrue("File content was: $content", content.contains("ROCK-EDIT"))
        assertTrue(content.contains("hello rock"))
    }
}
