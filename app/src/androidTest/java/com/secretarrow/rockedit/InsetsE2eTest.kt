package com.secretarrow.rockedit

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.18.0 regression guard: no screen may draw under the Android status bar.
 *
 * Device-independence note (v0.18.1 lesson): the status bar exists on every
 * device, so a non-zero top inset is universally assertable. The navigation
 * bar is NOT: headless emulator profiles (and devices with hardware keys)
 * report a zero bottom inset, and a real gesture-nav phone reports > 0 —
 * asserting bottom > 0 here made the test wrong on exactly one class of
 * devices, which CI caught. The bottom edge is instead covered by the
 * idempotence check below: padding must stay byte-equal across repeated
 * inset dispatches, which is the actual regression the listener could have
 * (accumulating padding instead of recomputing it from the baseline).
 */
@RunWith(AndroidJUnit4::class)
class InsetsE2eTest {
    private fun readRootPaddings(
        scenario: ActivityScenario<*>,
        out: IntArray,
    ) {
        scenario.onActivity { activity ->
            // android.R.id.content is a FrameLayout (a ViewGroup); its
            // child 0 is the view-binding root installed by setContentView.
            val content =
                activity.findViewById<ViewGroup>(android.R.id.content)
            val root = content?.getChildAt(0)
            if (root != null) {
                out[0] = root.paddingTop
                out[1] = root.paddingBottom
            } else {
                out[0] = -1
                out[1] = -1
            }
        }
    }

    private fun assertRootClearsSystemBars(scenario: ActivityScenario<*>) {
        val first = IntArray(2)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && first[0] <= 0) {
            readRootPaddings(scenario, first)
            if (first[0] <= 0) Thread.sleep(100)
        }
        assertTrue(
            "Content overlaps the status bar (root paddingTop=${first[0]})",
            first[0] > 0,
        )
        assertTrue(
            "Root bottom padding must not be negative (got ${first[1]})",
            first[1] >= 0,
        )
        // Re-dispatch stability: rotation-free re-layout (a second insets
        // pass after focus changes) must not grow the padding.
        Thread.sleep(300)
        val second = IntArray(2)
        readRootPaddings(scenario, second)
        assertEquals(
            "Insets padding changed across dispatches (accumulation bug)",
            first[0],
            second[0],
        )
        assertEquals(
            "Insets padding changed across dispatches (accumulation bug)",
            first[1],
            second[1],
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
