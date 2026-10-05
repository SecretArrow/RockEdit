package com.secretarrow.rockedit

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.18.0 regression guard: no screen may draw under the Android status bar
 * (top) or the system navigation bar / gesture area (bottom). The insets
 * dispatch happens right after the first layout pass, so the assertions poll
 * briefly instead of assuming it completed synchronously.
 */
@RunWith(AndroidJUnit4::class)
class InsetsE2eTest {
    private fun assertRootClearsSystemBars(scenario: ActivityScenario<*>) {
        var top = -1
        var bottom = -1
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && (top <= 0 || bottom <= 0)) {
            scenario.onActivity { activity ->
                val root =
                    activity.findViewById<View>(android.R.id.content)?.getChildAt(0)
                if (root != null) {
                    top = root.paddingTop
                    bottom = root.paddingBottom
                }
            }
            if (top <= 0 || bottom <= 0) Thread.sleep(100)
        }
        assertTrue(
            "Content overlaps the status bar (root paddingTop=$top)",
            top > 0,
        )
        assertTrue(
            "Content overlaps the navigation bar (root paddingBottom=$bottom)",
            bottom > 0,
        )
    }

    @Test
    fun mainActivityClearsSystemBars() {
        assertRootClearsSystemBars(ActivityScenario.launch(MainActivity::class.java))
    }

    @Test
    fun editorActivityClearsSystemBars() {
        assertRootClearsSystemBars(ActivityScenario.launch(EditorActivity::class.java))
    }
}
