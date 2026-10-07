package com.secretarrow.rockedit

import android.content.Context
import android.content.Intent
import android.text.style.BackgroundColorSpan
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the Find dialog upgrade (v0.22.0): live match highlighting, the
 * match counter, Previous navigation, and dismiss cleanup.
 *
 * Determinism follows the CI-proven dialog-window pattern: the dialog is
 * reached via [EditorActivity.activeFindDialog] (polled from the TEST thread
 * with short onActivity re-entries — polling inside onActivity would block
 * the main looper), and the widgets inside the dialog are driven/asserted
 * synchronously through the dialog's own view hierarchy, so no Espresso
 * root-picker is ever involved. The editor caret is pinned before each
 * assertion batch so counter labels are position-deterministic.
 */
@RunWith(AndroidJUnit4::class)
class FindHighlightE2eTest {
    private val seed = "alpha beta alpha gamma alpha"

    private fun launchEditor(): ActivityScenario<EditorActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                setClass(context, EditorActivity::class.java)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, seed)
            }
        return ActivityScenario.launch(intent)
    }

    /** Polls the activity until the Find dialog reports showing; test thread only. */
    private fun awaitFindDialog(scenario: ActivityScenario<EditorActivity>): Boolean {
        repeat(60) {
            var showing = false
            scenario.onActivity { activity -> showing = activity.activeFindDialog?.isShowing == true }
            if (showing) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun spanCount(activity: EditorActivity): Int {
        val editor = activity.findViewById<EditText>(R.id.editor)
        return editor.text!!.getSpans(0, editor.length(), BackgroundColorSpan::class.java).size
    }

    @Test
    fun queryHighlightsMatchesAndShowsCounter() {
        val scenario = launchEditor()
        OverflowMenu.tap(R.string.find)
        assertTrue("Find dialog did not appear", awaitFindDialog(scenario))
        scenario.onActivity { activity ->
            val dialog = activity.activeFindDialog!!
            val input = dialog.findViewById<EditText>(R.id.find_input)!!
            val counter = dialog.findViewById<TextView>(R.id.find_counter)!!
            val editor = activity.findViewById<EditText>(R.id.editor)

            // Counter starts hidden for an empty query.
            assertEquals(android.view.View.GONE, counter.visibility)

            // Pin the caret into a gap (between match 1 and 2) so the counter
            // renders the bare total instead of an ordinal.
            editor.setSelection(6)
            // setText fires the TextWatcher synchronously -> highlights land now.
            input.setText("alpha")
            assertEquals(android.view.View.VISIBLE, counter.visibility)
            assertEquals("3", counter.text.toString())

            assertEquals(3, spanCount(activity))
            val starts =
                editor.text!!.getSpans(0, editor.length(), BackgroundColorSpan::class.java)
                    .map { editor.text!!.getSpanStart(it) }
                    .sorted()
            assertEquals(listOf(0, 11, 23), starts)
            dialog.dismiss()
        }
        // Dismiss is handler-queued: poll (test thread) until spans are gone.
        repeat(60) {
            var clean = false
            scenario.onActivity { activity -> clean = spanCount(activity) == 0 }
            if (clean) return
            Thread.sleep(100)
        }
        assertTrue("Highlights survived dialog dismiss", spanCountIn(scenario) == 0)
    }

    private fun spanCountIn(scenario: ActivityScenario<EditorActivity>): Int {
        var count = 0
        scenario.onActivity { activity -> count = spanCount(activity) }
        return count
    }

    @Test
    fun nextAndPrevNavigateBothWays() {
        val scenario = launchEditor()
        OverflowMenu.tap(R.string.find)
        assertTrue(awaitFindDialog(scenario))
        scenario.onActivity { activity ->
            val dialog = activity.activeFindDialog!!
            val input = dialog.findViewById<EditText>(R.id.find_input)!!
            val counter = dialog.findViewById<TextView>(R.id.find_counter)!!
            val next = dialog.findViewById<android.view.View>(R.id.btn_next)!!
            val prev = dialog.findViewById<android.view.View>(R.id.btn_prev)!!
            val editor = activity.findViewById<EditText>(R.id.editor)

            // Caret at 0 -> typing pins searchStart to 0 deterministically.
            editor.setSelection(0)
            input.setText("alpha")

            // Next from the top selects match 1; counter shows 1/3.
            next.performClick()
            assertEquals(0, editor.selectionStart)
            assertEquals("1/3", counter.text.toString())

            // Next again -> match 2 at 11.
            next.performClick()
            assertEquals(11, editor.selectionStart)
            assertEquals("2/3", counter.text.toString())

            // Prev walks back to match 1.
            prev.performClick()
            assertEquals(0, editor.selectionStart)
            assertEquals("1/3", counter.text.toString())

            // Prev from the very first match wraps to the LAST match.
            prev.performClick()
            assertEquals(23, editor.selectionStart)
            assertEquals("3/3", counter.text.toString())
            dialog.dismiss()
        }
    }

    @Test
    fun replaceAllRefreshesHighlightsAndCounter() {
        val scenario = launchEditor()
        OverflowMenu.tap(R.string.find)
        assertTrue(awaitFindDialog(scenario))
        scenario.onActivity { activity ->
            val dialog = activity.activeFindDialog!!
            val input = dialog.findViewById<EditText>(R.id.find_input)!!
            val replace = dialog.findViewById<EditText>(R.id.replace_input)!!
            val counter = dialog.findViewById<TextView>(R.id.find_counter)!!
            val replaceAll = dialog.findViewById<android.view.View>(R.id.btn_replace_all)!!
            val editor = activity.findViewById<EditText>(R.id.editor)

            input.setText("alpha")
            replace.setText("delta")
            replaceAll.performClick()

            assertEquals("delta beta delta gamma delta", editor.text!!.toString())
            // The old query no longer matches anything: the counter says "0"
            // (never a stale total from before the replacement).
            assertEquals("0", counter.text.toString())
            assertEquals(0, spanCount(activity))
            dialog.dismiss()
        }
    }
}
