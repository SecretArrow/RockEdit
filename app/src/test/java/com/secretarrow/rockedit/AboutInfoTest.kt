package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.AboutInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for AboutInfo (v0.19.0) — every defensive path of the
 * About dialog logic is exercised on the JVM.
 */
class AboutInfoTest {
    // --- displayVersion: fallback paths -----------------------------------

    @Test
    fun nullVersionFallsBackToUnknown() {
        assertEquals("unknown", AboutInfo.displayVersion(null))
    }

    @Test
    fun blankVersionFallsBackToUnknown() {
        assertEquals("unknown", AboutInfo.displayVersion(""))
        assertEquals("unknown", AboutInfo.displayVersion("   "))
    }

    // --- displayVersion: trimming + marker strip ---------------------------

    @Test
    fun cleanVersionIsUntouched() {
        assertEquals("0.19.0", AboutInfo.displayVersion("0.19.0"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("0.19.0", AboutInfo.displayVersion("  0.19.0  "))
    }

    @Test
    fun singleLeadingVIsStripped() {
        assertEquals("0.19.0", AboutInfo.displayVersion("v0.19.0"))
        assertEquals("0.19.0", AboutInfo.displayVersion("V0.19.0"))
    }

    @Test
    fun doubleLeadingVStripsOnlyOneMarker() {
        // Guard against loops: exactly one leading marker is removed.
        assertEquals("v0.19.0", AboutInfo.displayVersion("vv0.19.0"))
    }

    @Test
    fun bareVMarkerIsKeptAsIs() {
        // A single-character input "v" cannot be stripped (needs length > 1).
        assertEquals("v", AboutInfo.displayVersion("v"))
    }

    @Test
    fun fullSemverWithMetadataIsUntouched() {
        // Pre-release/build metadata must survive the sanitizer verbatim.
        assertEquals("0.19.0-beta.1+build.2", AboutInfo.displayVersion("0.19.0-beta.1+build.2"))
    }

    @Test
    fun controlCharacterWhitespaceFallsBackToUnknown() {
        // isBlank() covers tabs/newlines too, not just spaces.
        assertEquals("unknown", AboutInfo.displayVersion(" \n\t"))
    }

    // --- displayVersion: overflow cap --------------------------------------

    @Test
    fun overlyLongVersionIsTruncated() {
        val long = "0.19.0-" + "a".repeat(60)
        val result = AboutInfo.displayVersion(long)
        assertEquals(AboutInfo.MAX_VERSION_LENGTH, result.length)
        assertTrue(result.startsWith("0.19.0-"))
    }

    // --- titleLine ----------------------------------------------------------

    @Test
    fun titleLineCombinesNameAndVersion() {
        assertEquals("Rock Edit 0.19.0", AboutInfo.titleLine("Rock Edit", "0.19.0"))
    }

    @Test
    fun titleLineTrimsAppName() {
        assertEquals("Rock Edit 1.0", AboutInfo.titleLine("  Rock Edit ", "1.0"))
    }

    @Test
    fun titleLineFallsBackToDefaultAppNameWhenBlank() {
        assertEquals("Rock Edit 1.0", AboutInfo.titleLine("", "1.0"))
        assertEquals("Rock Edit 1.0", AboutInfo.titleLine("   ", "1.0"))
    }

    @Test
    fun titleLineWithNullVersionShowsFallback() {
        assertEquals("Rock Edit unknown", AboutInfo.titleLine("Rock Edit", null))
    }

    // --- creditLine ----------------------------------------------------------

    @Test
    fun creditLineShowsCreator() {
        assertEquals("Created by Maragung", AboutInfo.creditLine("Created by"))
    }

    @Test
    fun creditLineTrimsLabel() {
        assertEquals("Created by Maragung", AboutInfo.creditLine("  Created by  "))
    }

    @Test
    fun creditLineFallsBackToEnglishLabelWhenBlank() {
        assertEquals("Created by Maragung", AboutInfo.creditLine(""))
        assertEquals("Created by Maragung", AboutInfo.creditLine("   "))
    }

    @Test
    fun creatorConstantIsNonBlank() {
        // The whole feature depends on this: dialog shows a real creator name.
        assertTrue(AboutInfo.CREATOR.isNotBlank())
        assertEquals("Maragung", AboutInfo.CREATOR)
    }
}
