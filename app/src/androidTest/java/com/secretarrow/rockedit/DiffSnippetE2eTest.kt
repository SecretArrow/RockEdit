package com.secretarrow.rockedit

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.SnippetStore
import com.secretarrow.rockedit.ui.DiffActivity
import com.secretarrow.rockedit.ui.EditorActivity
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v0.12.0 end-to-end: the compare screen renders per-line changes and cleans
 * its cache files, and an inserted snippet actually expands into the
 * document.
 */
@RunWith(AndroidJUnit4::class)
class DiffSnippetE2eTest {
    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>()

    private fun newTestFileIntent(
        name: String,
        content: String,
    ): Intent {
        val file = File(context().filesDir, name)
        file.writeText(content)
        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                context(),
                context().packageName + ".fileprovider",
                file,
            )
        return Intent(Intent.ACTION_VIEW).apply {
            setClass(context(), EditorActivity::class.java)
            setDataAndType(uri, "text/plain")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Opens the editor overflow and taps a menu entry regardless of layout. */
    private fun tapOverflowItem(titleRes: Int) {
        try {
            onView(withText(titleRes)).perform(click())
        } catch (_: Throwable) {
            Espresso.openActionBarOverflowOrOptionsMenu(context())
            onView(withText(titleRes)).perform(click())
        }
    }

    private fun waitUntil(
        block: () -> Boolean,
        what: String,
        timeoutMs: Long = 8000,
    ) {
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
    fun diffActivityRendersChangesAndCleansCache() {
        val oldFile = File(context().cacheDir, "e2e_diff_old.txt")
        val newFile = File(context().cacheDir, "e2e_diff_new.txt")
        oldFile.writeText("alpha\nbravo\ncharlie\ndelta\necho")
        newFile.writeText("alpha\nBRAVO\ncharlie\ndelta\necho")
        val intent =
            Intent(context(), DiffActivity::class.java)
                .putExtra("rockedit.extra.DIFF_OLD_PATH", oldFile.absolutePath)
                .putExtra("rockedit.extra.DIFF_NEW_PATH", newFile.absolutePath)

        ActivityScenario.launch<DiffActivity>(intent)

        waitUntil({
            onView(withId(R.id.diff_summary)).check(
                matches(withText(containsString("1 added, 1 removed, 4 unchanged"))),
            )
            true
        }, "diff summary rendered")

        waitUntil({
            onView(withText(containsString("+BRAVO"))).check(matches(isDisplayed()))
            true
        }, "insert row visible")

        waitUntil({
            onView(withText(containsString("-bravo"))).check(matches(isDisplayed()))
            true
        }, "delete row visible")

        // Defensive contract: both cache sides are gone after the read.
        waitUntil({
            !oldFile.exists() && !newFile.exists()
        }, "cache files deleted")
    }

    @Test
    fun snippetInsertExpandsIntoDocument() {
        val store = SnippetStore(App.keyValueStore(context()))
        store.create("greet", "all", "hello \${1:world}!")
        assertTrue(store.list("txt").isNotEmpty())

        ActivityScenario.launch<EditorActivity>(newTestFileIntent("e2e_snip.txt", "x"))
        waitUntil({
            onView(withId(R.id.editor)).check(matches(withText(containsString("x"))))
            true
        }, "editor loaded")

        tapOverflowItem(R.string.snippets)
        waitUntil({
            onView(withText(containsString("greet")))
                .perform(scrollTo(), click())
            true
        }, "snippet row tapped")

        waitUntil({
            onView(withId(R.id.editor)).check(
                matches(withText(containsString("hello world!"))),
            )
            true
        }, "snippet expanded into document")
    }
}
