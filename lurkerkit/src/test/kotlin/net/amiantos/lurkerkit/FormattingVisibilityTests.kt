// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.rendering.IRCFormatting
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `IRCFormatting.drawsNothing`: whether a wire string has anything to show (lurker#1297). A topic that
 * draws nothing gets the "No topic set." placeholder; one that paints a box does not.
 */
class FormattingVisibilityTests {

    @Test
    fun testControlCodesAndUnpaintedWhitespaceDrawNothing() {
        assertTrue(IRCFormatting.drawsNothing(""))
        assertTrue(IRCFormatting.drawsNothing("   \n"))
        assertTrue(IRCFormatting.drawsNothing("\u0003\u0002"))
        // A foreground on spaces colours nothing visible.
        assertTrue(IRCFormatting.drawsNothing("\u000304   \u0003"))
        assertTrue(IRCFormatting.drawsNothing("\u0002 \u001D \u000F"))
    }

    @Test
    fun testTextDraws() {
        assertFalse(IRCFormatting.drawsNothing("hi"))
        assertFalse(IRCFormatting.drawsNothing("\u000304x\u0003"))
        assertFalse(IRCFormatting.drawsNothing("   .   "))
    }

    @Test
    fun testPaintedWhitespaceDraws() {
        // A background is a box, reverse video a block, an underline or a strike a rule.
        assertFalse(IRCFormatting.drawsNothing("\u000304,04   \u0003"))
        assertFalse(IRCFormatting.drawsNothing("\u0016   \u0016"))
        assertFalse(IRCFormatting.drawsNothing("\u001F   \u001F"))
        assertFalse(IRCFormatting.drawsNothing("\u001E   \u001E"))
    }
}
