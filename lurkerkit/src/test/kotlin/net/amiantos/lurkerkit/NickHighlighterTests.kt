// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.rendering.IRCPalette
import net.amiantos.lurkerkit.rendering.NickHighlighter
import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.substring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * In-body nick coloring, ported from the web client's `colorNicksInText`: a known nick is a
 * match only as a whole word, longest name wins, matching is case-insensitive. Self-exclusion
 * happens where the highlighter is built (the caller drops the reader's own nick), so it isn't
 * tested here.
 */
class NickHighlighterTests {

    /** The matched substrings, in order — easier to assert on than raw TextRanges. */
    private fun hits(nicks: List<String>, text: String): List<String> =
        NickHighlighter(nicks).matches(text).map { text.substring(it) }

    @Test
    fun testMatchesAWholeWordNick() {
        assertEquals(listOf("alice", "bob"), hits(listOf("alice", "bob"), "hey alice and bob"))
    }

    @Test
    fun testDoesNotMatchInsideALongerWord() {
        // "bob" inside "bobby" must not match — the trailing "b" is a nick char.
        assertEquals(emptyList(), hits(listOf("bob"), "hi bobby"))
    }

    @Test
    fun testDoesNotMatchWithATrailingNickChar() {
        // The away/alt suffix chars are nick chars, so "bob_" and "bob-" aren't a bare "bob".
        assertEquals(emptyList(), hits(listOf("bob"), "bob_ bob- bob|"))
    }

    @Test
    fun testMatchesNextToPunctuation() {
        assertEquals(listOf("bob", "bob", "bob"), hits(listOf("bob"), "hey bob! and (bob), bob."))
    }

    @Test
    fun testLongestNickWinsAtAPosition() {
        // Both could match at the same spot; the alternation must prefer the longer one.
        assertEquals(listOf("alibaba"), hits(listOf("ali", "alibaba"), "hi alibaba"))
    }

    @Test
    fun testMatchingIsCaseInsensitive() {
        assertEquals(listOf("ALICE", "alice"), hits(listOf("Alice"), "hey ALICE and alice"))
    }

    @Test
    fun testMatchesNicksWithSpecialChars() {
        // IRC nicks allow - _ [ ] \ ^ { | } — all inside the boundary class.
        assertEquals(
            listOf("[a\\b]", "{c|d}"),
            hits(listOf("[a\\b]", "{c|d}"), "poke [a\\b] and {c|d} now"),
        )
    }

    @Test
    fun testReturnsAccurateRanges() {
        val text = "yo bob"
        val ranges = NickHighlighter(listOf("bob")).matches(text)
        assertEquals(listOf(TextRange.of(location = 3, length = 3)), ranges)
    }

    @Test
    fun testEmptyNickSetMatchesNothing() {
        val highlighter = NickHighlighter(emptyList())
        assertTrue(highlighter.isEmpty)
        assertEquals(emptyList(), highlighter.matches("anyone home?"))
    }
}

/**
 * The light-mode palette exists and lines up with the dark one, so `hashedColor` can pair
 * them by index and every nick has both variants.
 */
class NickPaletteTests {

    @Test
    fun testLightPaletteParallelsDark() {
        assertEquals(IRCPalette.nick.size, IRCPalette.nickLight.size)
        assertEquals(IRCPalette.mirc.size, IRCPalette.mircLight.size)
    }

    /**
     * The four mono slots are the same colour in both tables, on purpose.
     *
     * These are the ones a sender pairs with a background, and re-tinting them per scheme is
     * what broke every such pair: `^C00,01` drew the theme's near-black on black, and `^C01,00`
     * — where slot 0 is the *background* — drew a dark box with black text in it. The type
     * stops them being theme references at all; this stops them being re-tinted.
     */
    @Test
    fun testMonoSlotsAreSchemeIndependentLiterals() {
        for ((slot, hex) in listOf(0 to "#ffffff", 1 to "#000000", 14 to "#7f7f7f", 15 to "#d2d2d2")) {
            assertEquals(hex, IRCPalette.mirc[slot], "mirc[$slot]")
            assertEquals(hex, IRCPalette.mircLight[slot], "mircLight[$slot]")
        }
    }

    @Test
    fun testEveryLightHexParses() {
        for (hex in IRCPalette.nickLight + IRCPalette.mircLight) {
            val digits = hex.drop(1)
            // Port note: ASCII hex only, where Swift's `isHexDigit` also takes the fullwidth
            // forms — the stricter reading, and the one a colour parser will apply.
            assertTrue(
                hex.startsWith("#") && digits.length == 6 &&
                    digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
                "malformed light hex: $hex",
            )
        }
    }

    /**
     * Both palettes are copies of the web client's built-in themes, and the whole point is that
     * they don't drift. Pinning them here means a change to either table is a change someone has
     * to make deliberately, in two places, rather than a hex someone nudged.
     *
     * Source: `lurker/shared/settingsRegistry.ts` (`look.nick.colors`,
     * `look.color.mirc_colors`) for dark, `lurker/shared/themePresets.ts` (`LIGHT_OVERRIDES`)
     * for light.
     */
    @Test
    fun testPalettesMatchTheWebPresets() {
        assertEquals(
            listOf(
                "#ff6188", "#fc9867", "#ffd866", "#a9dc76", "#78dce8", "#ab9df2", "#ed6c89",
                "#d4996e", "#f9d978", "#b3db82", "#91dae6", "#a99dec", "#ff7494", "#ffaf75",
                "#c4e29a", "#a0f1ff", "#b6aaff", "#7ba4ff", "#6799f3",
            ),
            IRCPalette.nick,
        )
        assertEquals(
            listOf(
                "#e14775", "#e16032", "#cc7a0a", "#269d69", "#1c8ca8", "#7058be", "#b52d55",
                "#9a5f30", "#a68500", "#688f2d", "#3d8f9b", "#7061b1", "#c12d5b", "#b66621",
                "#759247", "#409ba9", "#7767bd", "#4268c5", "#3163c0",
            ),
            IRCPalette.nickLight,
        )
        assertEquals(
            listOf(
                "#ffffff", "#000000", "#6799f3", "#a9dc76", "#ff6188", "#ed6c89", "#ab9df2", "#fc9867",
                "#ffd866", "#b3db82", "#78dce8", "#a0f1ff", "#7ba4ff", "#ff7494", "#7f7f7f", "#d2d2d2",
            ),
            IRCPalette.mirc,
        )
        assertEquals(
            listOf(
                "#ffffff", "#000000", "#3163c0", "#269d69", "#e14775", "#b52d55", "#7058be", "#e16032",
                "#cc7a0a", "#688f2d", "#1c8ca8", "#409ba9", "#4268c5", "#c12d5b", "#7f7f7f", "#d2d2d2",
            ),
            IRCPalette.mircLight,
        )
    }

    /**
     * The six hues Monokai Pro Light officially defines are shared between the two light tables
     * — a `^C04` and a nick that hash to the same slot have to be the same red. This is the
     * pairing `mircLight` was derived from, and the half of it that drifted when the web theme
     * adopted the official accents and iOS didn't.
     */
    @Test
    fun testLightMircTracksTheLightNickPalette() {
        // The four mono slots are scheme-independent and aren't drawn from the nick palette;
        // they have their own test.
        val mono = setOf(0, 1, 14, 15)
        for ((slot, hex) in IRCPalette.mircLight.withIndex()) {
            if (slot in mono) continue
            val darkHex = IRCPalette.mirc[slot]
            val nickIndex = IRCPalette.nick.indexOf(darkHex)
            if (nickIndex < 0) {
                fail("mirc[$slot] = $darkHex is neither a mono slot nor a nick-palette hue")
            }
            assertEquals(
                IRCPalette.nickLight[nickIndex], hex,
                "mircLight[$slot] should be the light variant of $darkHex",
            )
        }
    }
}
