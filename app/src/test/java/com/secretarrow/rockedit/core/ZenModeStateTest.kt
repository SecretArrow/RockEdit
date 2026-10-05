package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Per-branch tests for [ZenMode] and its [ZenSnapshot] / [ZenActive] /
 * [ZenResult] vocabulary: enter/exit transitions, idempotent re-entry
 * (rotation safety), the font boost with its cap, [ZenSnapshot] validation,
 * defensive sanitization of untrusted values, and the full enter-exit
 * round trip.
 */
class ZenModeStateTest {
    private fun snapshot(
        toolbar: Boolean = true,
        tabs: Boolean = true,
        font: Float = 14f,
    ): ZenSnapshot =
        ZenSnapshot(
            toolbarVisible = toolbar,
            tabsVisible = tabs,
            fontSizeSp = font,
        )

    private fun entered(result: ZenResult): ZenResult.Entered {
        assertTrue("expected Entered, was $result", result is ZenResult.Entered)
        return result as ZenResult.Entered
    }

    private fun expectIllegal(block: () -> Unit): String =
        try {
            block()
            fail("expected IllegalArgumentException")
            error("unreachable: expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            expected.message ?: ""
        }

    // -------------------------------------------------- enter / exit

    @Test
    fun enterFromNullReturnsEnteredWithSnapshot() {
        val before = snapshot(toolbar = true, tabs = false, font = 14f)

        val state = entered(ZenMode.enter(before, null)).state

        assertEquals(before, state.snapshot)
        assertTrue(state.snapshot.toolbarVisible)
        assertFalse(state.snapshot.tabsVisible)
        assertEquals(14f, state.snapshot.fontSizeSp, 0f)
    }

    @Test
    fun enterWhileActiveReturnsAlreadyActiveAndKeepsOriginalSnapshot() {
        val first = snapshot(toolbar = true, tabs = true, font = 14f)
        val second = snapshot(toolbar = false, tabs = false, font = 24f)
        val active = entered(ZenMode.enter(first, null)).state

        assertSame(ZenResult.AlreadyActive, ZenMode.enter(second, active))

        assertEquals(first, active.snapshot)
        assertNotEquals(second, active.snapshot)
    }

    @Test
    fun alreadyActiveIsSingleton() {
        val active = entered(ZenMode.enter(snapshot(), null)).state

        assertSame(ZenResult.AlreadyActive, ZenMode.enter(snapshot(font = 30f), active))
        assertSame(ZenResult.AlreadyActive, ZenMode.enter(snapshot(font = 30f), active))
    }

    @Test
    fun exitNullReturnsNotActive() {
        assertSame(ZenResult.NotActive, ZenMode.exit(null))
    }

    @Test
    fun exitActiveReturnsExited() {
        val active = entered(ZenMode.enter(snapshot(), null)).state

        assertSame(ZenResult.Exited, ZenMode.exit(active))
    }

    @Test
    fun exitWithStaleSessionStillReportsExited() {
        // The machine is stateless: a stale (already-exited) session passed
        // again still reports Exited, so the caller must drop the reference
        // and pass null once zen is off. Documented contract, tested here.
        val active = entered(ZenMode.enter(snapshot(), null)).state

        assertSame(ZenResult.Exited, ZenMode.exit(active))
        assertSame(ZenResult.Exited, ZenMode.exit(active))
        assertSame(ZenResult.NotActive, ZenMode.exit(null))
    }

    // -------------------------------------------------- font boost

    @Test
    fun boostAddsExactlyTwoSp() {
        val active = entered(ZenMode.enter(snapshot(font = 14f), null)).state

        assertEquals(16f, active.zenFontSizeSp, 0f)
    }

    @Test
    fun boostOnFractionalFontKeepsFraction() {
        val active = entered(ZenMode.enter(snapshot(font = 12.5f), null)).state

        assertEquals(14.5f, active.zenFontSizeSp, 0f)
    }

    @Test
    fun boostCoercedAtMaxFrom39() {
        val active = entered(ZenMode.enter(snapshot(font = 39f), null)).state

        assertEquals(40f, active.zenFontSizeSp, 0f)
    }

    @Test
    fun boostCoercedAtMaxFrom40() {
        val active = entered(ZenMode.enter(snapshot(font = 40f), null)).state

        assertEquals(40f, active.zenFontSizeSp, 0f)
    }

    // -------------------------------------------------- snapshot validation

    @Test
    fun snapshotRejectsFontBelowMin() {
        val message = expectIllegal { snapshot(font = 7.9f) }

        assertEquals("fontSizeSp must be in 8.0..40.0, was: 7.9", message)
    }

    @Test
    fun snapshotRejectsFontAboveMax() {
        val message = expectIllegal { snapshot(font = 40.1f) }

        assertEquals("fontSizeSp must be in 8.0..40.0, was: 40.1", message)
    }

    @Test
    fun snapshotAcceptsBoundaryFonts() {
        assertEquals(8f, snapshot(font = 8f).fontSizeSp, 0f)
        assertEquals(40f, snapshot(font = 40f).fontSizeSp, 0f)
    }

    @Test
    fun snapshotRejectsNaNFont() {
        // NaN is never inside a closed range, so the constructor rejects it
        // (coerceIn would NOT fix NaN: it is not stable on NaN by design).
        val message = expectIllegal { snapshot(font = Float.NaN) }

        assertTrue("unexpected message: $message", message.startsWith("fontSizeSp must be in"))
    }

    // -------------------------------------------------- sanitizeSnapshot

    @Test
    fun sanitizeNaNFallsBackToDefaultFont() {
        val clean = ZenMode.sanitizeSnapshot(true, false, Float.NaN)

        assertEquals(ZenSnapshot.DEFAULT_FONT_SP, clean.fontSizeSp, 0f)
        assertEquals(14f, clean.fontSizeSp, 0f)
    }

    @Test
    fun sanitizePositiveInfinityFallsBackToDefaultFont() {
        val clean = ZenMode.sanitizeSnapshot(false, true, Float.POSITIVE_INFINITY)

        assertEquals(14f, clean.fontSizeSp, 0f)
    }

    @Test
    fun sanitizeNegativeInfinityFallsBackToDefaultFont() {
        val clean = ZenMode.sanitizeSnapshot(true, true, Float.NEGATIVE_INFINITY)

        assertEquals(14f, clean.fontSizeSp, 0f)
    }

    @Test
    fun sanitizeClampsOversizedFontToMax() {
        val clean = ZenMode.sanitizeSnapshot(true, true, 100f)

        assertEquals(40f, clean.fontSizeSp, 0f)
    }

    @Test
    fun sanitizeClampsTinyFontToMin() {
        val clean = ZenMode.sanitizeSnapshot(true, true, 0f)

        assertEquals(8f, clean.fontSizeSp, 0f)
    }

    @Test
    fun sanitizeKeepsValidFontUnchanged() {
        val clean = ZenMode.sanitizeSnapshot(true, true, 13.7f)

        assertEquals(13.7f, clean.fontSizeSp, 0f)
    }

    @Test
    fun sanitizePassesVisibilityThrough() {
        val clean =
            ZenMode.sanitizeSnapshot(
                toolbarVisible = true,
                tabsVisible = false,
                fontSizeSp = 14f,
            )
        assertTrue(clean.toolbarVisible)
        assertFalse(clean.tabsVisible)

        val flipped =
            ZenMode.sanitizeSnapshot(
                toolbarVisible = false,
                tabsVisible = true,
                fontSizeSp = 14f,
            )
        assertFalse(flipped.toolbarVisible)
        assertTrue(flipped.tabsVisible)
    }

    // -------------------------------------------------- round trip & rotation

    @Test
    fun roundTripEnterExitThenFreshSession() {
        val first = snapshot(toolbar = true, tabs = true, font = 14f)
        val active = entered(ZenMode.enter(first, null)).state

        assertSame(ZenResult.Exited, ZenMode.exit(active))
        assertEquals(first, active.snapshot)

        // Zen is off again: exiting without a session is a no-op ...
        assertSame(ZenResult.NotActive, ZenMode.exit(null))
        // ... and a fresh session may start with a different snapshot.
        val second = snapshot(toolbar = false, tabs = false, font = 20f)
        val fresh = entered(ZenMode.enter(second, null)).state

        assertEquals(second, fresh.snapshot)
        assertNotEquals(first, fresh.snapshot)
    }

    @Test
    fun rotationKeepsFirstSnapshotAndRestoresIt() {
        val preZen = snapshot(toolbar = true, tabs = true, font = 14f)
        val rotationValues = snapshot(toolbar = false, tabs = false, font = 36f)

        val active = entered(ZenMode.enter(preZen, null)).state

        // Screen rotation: the rebuilt activity re-enters zen with values
        // measured while zen was already applied. Twice, defensively.
        assertSame(ZenResult.AlreadyActive, ZenMode.enter(rotationValues, active))
        assertSame(ZenResult.AlreadyActive, ZenMode.enter(rotationValues, active))

        // The original pre-zen snapshot is still the one to restore from.
        assertEquals(preZen, active.snapshot)
        assertTrue(active.snapshot.toolbarVisible)
        assertTrue(active.snapshot.tabsVisible)
        assertEquals(14f, active.snapshot.fontSizeSp, 0f)

        assertSame(ZenResult.Exited, ZenMode.exit(active))
        // Caller restores from the held snapshot: toolbar and tabs come back
        // exactly as they were before zen.
        val restored = active.snapshot
        assertEquals(preZen, restored)
        assertTrue(restored.toolbarVisible)
        assertTrue(restored.tabsVisible)
        assertEquals(14f, restored.fontSizeSp, 0f)
    }
}
