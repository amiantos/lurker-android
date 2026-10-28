// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.PreviewImageBudget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canvas rule a preview still is judged by before a pixel of it is decoded.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Preview image budget"); the method names are
 * kept and each display name is the comment above it.
 */
class PreviewImageBudgetTests {

    /** a photograph passes: a 12 MP phone frame, a 48 MP one, a camera file */
    @Test
    fun photographsPass() {
        assertTrue(PreviewImageBudget.admits(pixelWidth = 4032, pixelHeight = 3024)) // 12 MP
        assertTrue(PreviewImageBudget.admits(pixelWidth = 8064, pixelHeight = 6048)) // 48 MP
        assertTrue(PreviewImageBudget.admits(pixelWidth = 6000, pixelHeight = 4000))
        assertTrue(PreviewImageBudget.admits(pixelWidth = 1, pixelHeight = 1))
        assertTrue(PreviewImageBudget.admits(pixelWidth = 1, pixelHeight = PreviewImageBudget.maxPixels))
    }

    /** a canvas past the bound is refused, however few bytes it took */
    @Test
    fun bombsAreRefused() {
        assertFalse(PreviewImageBudget.admits(pixelWidth = 20_000, pixelHeight = 20_000))
        assertFalse(PreviewImageBudget.admits(pixelWidth = 1, pixelHeight = PreviewImageBudget.maxPixels + 1))
        assertFalse(PreviewImageBudget.admits(pixelWidth = 10_000, pixelHeight = 5_001))
    }

    /** an empty or negative canvas is refused rather than divided by */
    @Test
    fun degenerateCanvases() {
        assertFalse(PreviewImageBudget.admits(pixelWidth = 0, pixelHeight = 100))
        assertFalse(PreviewImageBudget.admits(pixelWidth = 100, pixelHeight = 0))
        assertFalse(PreviewImageBudget.admits(pixelWidth = -1, pixelHeight = 100))
    }

    /** dimensions whose product overflows are refused without trapping */
    @Test
    fun overflowIsARefusal() {
        assertFalse(PreviewImageBudget.admits(pixelWidth = Int.MAX_VALUE, pixelHeight = 2))
        assertFalse(PreviewImageBudget.admits(pixelWidth = Int.MAX_VALUE, pixelHeight = Int.MAX_VALUE))
    }
}
