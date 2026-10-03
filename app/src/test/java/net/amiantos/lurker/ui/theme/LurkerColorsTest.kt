// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import net.amiantos.lurkerkit.model.StatusLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LurkerColorsTest {

    // MARK: - Hex

    @Test
    fun hexParsesToOpaqueArgb() {
        assertEquals(0xFF212022.toInt(), parseHex("#212022"))
        assertEquals(0xFFFAF4F2.toInt(), parseHex("#faf4f2"))
        assertEquals(0xFFFAF4F2.toInt(), parseHex("#FAF4F2"))
        assertEquals(0xFF000000.toInt(), parseHex("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), parseHex("#ffffff"))
    }

    @Test
    fun malformedHexIsNull() {
        for (hex in listOf("", "#", "212022", "#21202", "#2120222", "#gggggg", "#21 022", "##12345", "#ff212022")) {
            assertNull(hex, parseHex(hex))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMalformedLiteralThrows() {
        hexColor("#gggggg")
    }

    // MARK: - The palettes

    /** Builds both: a typo in any literal throws here, which is the only place it can be seen. */
    @Test
    fun everyTokenMatchesPalette() {
        val dark = LurkerColors.Dark
        val light = LurkerColors.Light
        assertHex("#212022", dark.bg)
        assertHex("#faf4f2", light.bg)
        assertHex("#fcfcfa", dark.fg)
        assertHex("#29242a", light.fg)
        assertHex("#939293", dark.fgMuted)
        assertHex("#706b6e", light.fgMuted)
        assertHex("#a99dec", dark.accent)
        assertHex("#7058be", light.accent)
        assertHex("#2c2a2e", dark.bgSoft)
        assertHex("#ede7e5", light.bgSoft)
        assertHex("#38353b", dark.border)
        assertHex("#e0dad9", light.border)
        assertHex("#b3db82", dark.good)
        assertHex("#269d69", light.good)
        assertHex("#f9d978", dark.warn)
        assertHex("#cc7a0a", light.warn)
        assertHex("#ed6c89", dark.bad)
        assertHex("#e14775", light.bad)
        assertHex("#ed6c89", dark.memberOwner)
        assertHex("#e14775", light.memberOwner)
        assertHex("#fc9867", dark.memberAdmin)
        assertHex("#e16032", light.memberAdmin)
        assertEquals(dark.accent, dark.memberOp)
        assertEquals(light.accent, light.memberOp)
        assertHex("#78dce8", dark.memberHalfop)
        assertHex("#1c8ca8", light.memberHalfop)
        assertHex("#b3db82", dark.memberVoice)
        assertHex("#269d69", light.memberVoice)
    }

    @Test
    fun derivedTokensKeepTheirSchemesHue() {
        for (colors in listOf(LurkerColors.Dark, LurkerColors.Light)) {
            assertEquals(colors.fgMuted.copy(alpha = 0.7f), colors.fgFaint)
            assertEquals(colors.warn.copy(alpha = 0.16f), colors.highlightBubble)
        }
        assertNotEquals(LurkerColors.Dark.highlightBubble, LurkerColors.Light.highlightBubble)
    }

    @Test
    fun theIrcTablesAreTheKitsInOrder() {
        assertEquals(19, LurkerColors.Dark.nick.size)
        assertEquals(19, LurkerColors.Light.nick.size)
        assertEquals(16, LurkerColors.Dark.mirc.size)
        assertEquals(16, LurkerColors.Light.mirc.size)
        assertHex("#ff6188", LurkerColors.Dark.nick[0])
        assertHex("#e14775", LurkerColors.Light.nick[0])
        assertHex("#3163c0", LurkerColors.Light.nick[18])
        // The mono mIRC slots are literal and identical in both schemes.
        for (slot in listOf(0, 1, 14, 15)) assertEquals(LurkerColors.Dark.mirc[slot], LurkerColors.Light.mirc[slot])
        assertHex("#ffffff", LurkerColors.Light.mirc[0])
    }

    @Test
    fun lightsAndPrefixes() {
        val colors = LurkerColors.Dark
        assertEquals(colors.good, colors.color(StatusLight.Good))
        assertEquals(colors.warn, colors.color(StatusLight.Warn))
        assertEquals(colors.bad, colors.color(StatusLight.Bad))
        assertEquals(colors.memberOwner, colors.memberPrefix("~"))
        assertEquals(colors.memberAdmin, colors.memberPrefix("&"))
        assertEquals(colors.memberOp, colors.memberPrefix("@"))
        assertEquals(colors.memberHalfop, colors.memberPrefix("%"))
        assertEquals(colors.memberVoice, colors.memberPrefix("+"))
        assertNull(colors.memberPrefix(""))
        assertNull(colors.memberPrefix("!"))
    }

    // MARK: - The Material scheme

    @Test
    fun theMaterialSchemeIsThePalette() {
        for (colors in listOf(LurkerColors.Dark, LurkerColors.Light)) {
            val scheme = lurkerColorScheme(colors)
            assertEquals(colors.bg, scheme.background)
            assertEquals(colors.fg, scheme.onBackground)
            assertEquals(colors.bgSoft, scheme.surface)
            assertEquals(colors.fg, scheme.onSurface)
            assertEquals(colors.accent, scheme.primary)
            assertEquals(colors.badText, scheme.error)
            assertEquals(colors.fgMuted, scheme.outline)
            assertEquals(colors.border, scheme.outlineVariant)
            assertEquals(colors.fgMuted, scheme.onSurfaceVariant)
        }
        // A snackbar's action sits on the other scheme's ground, so it takes the other accent.
        assertEquals(LurkerColors.Light.accent, lurkerColorScheme(LurkerColors.Dark).inversePrimary)
        assertEquals(LurkerColors.Dark.accent, lurkerColorScheme(LurkerColors.Light).inversePrimary)
    }

    /**
     * The text-safe `bad` clears WCAG's 4.5:1 for normal text on both grounds it is drawn over,
     * in both schemes; the palette's own `bad` does not in light (3.6:1), which is why the token
     * exists. Computed, not asserted by eye.
     */
    @Test
    fun errorTextClearsNormalTextContrast() {
        for (colors in listOf(LurkerColors.Dark, LurkerColors.Light)) {
            assert(contrast(colors.badText, colors.bg) >= 4.5) { "${colors.isDark}: badText on bg" }
            assert(contrast(colors.badText, colors.bgSoft) >= 4.5) { "${colors.isDark}: badText on bgSoft" }
        }
        assert(contrast(LurkerColors.Light.bad, LurkerColors.Light.bg) < 4.5) { "the palette's light bad is why" }
    }

    private fun contrast(a: Color, b: Color): Double {
        fun channel(c: Float): Double = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertHex(hex: String, color: Color) {
        assertEquals(hex, "#%06x".format(color.toArgb() and 0xFFFFFF))
        assertEquals(1f, color.alpha)
    }
}
