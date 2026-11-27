package dev.rotalex.lutter.editor

import androidx.window.core.layout.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 18.5 T1's capability probe: the window size class the shell derives its layout from must
 * resolve on every target the editor builds for, before any scene strategy depends on it.
 *
 * The breakpoints are asserted as literals rather than through the library's own constants, so a
 * wrong constant spelling cannot be mistaken for a missing artifact — this test answers one
 * question, and it is whether the dependency exists and classifies widths as Material does.
 */
class AdaptiveCapabilityTest {

    @Test
    fun `the window size class resolves on this target and splits the three layouts`() {
        val compact = WindowSizeClass.compute(widthDp = 320f, heightDp = 500f)
        val medium = WindowSizeClass.compute(widthDp = 700f, heightDp = 500f)
        val expanded = WindowSizeClass.compute(widthDp = 1024f, heightDp = 768f)

        assertFalse(compact.isWidthAtLeastBreakpoint(600), "320dp is compact")
        assertTrue(medium.isWidthAtLeastBreakpoint(600), "700dp is at least medium")
        assertFalse(medium.isWidthAtLeastBreakpoint(840), "700dp is below expanded")
        assertTrue(expanded.isWidthAtLeastBreakpoint(840), "1024dp is expanded")
    }
}
