// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.rendering.EmojiShortcodes
import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.substring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:shortcode:` emoji (lurker#1294): the bundled table, the grammar the web's
 * `emojiShortcodes.ts` tests pin down, and the replacement arithmetic the body renderer's marks
 * follow — the cases of `splitTextByEmoji` in the web's `nickColor.test.ts`.
 */
class EmojiShortcodesTests {

    // MARK: - The table

    @Test
    fun testTheTableLoadsFromTheBundle() {
        // A resource that doesn't reach the classpath fails silently — an empty table, and every
        // shortcode in every message stays literal, which looks like the bug this feature fixes.
        assertTrue(EmojiShortcodes.table.size > 1800)
        assertEquals("💀", EmojiShortcodes.glyph("skull"))
        assertEquals("👍", EmojiShortcodes.glyph("+1"))
        assertEquals("📧", EmojiShortcodes.glyph("e-mail"))
    }

    @Test
    fun testLookupIsCaseInsensitiveAndUnknownIsNull() {
        assertEquals("🦴", EmojiShortcodes.glyph("Bone"))
        assertEquals("🎉", EmojiShortcodes.glyph("TADA"))
        assertNull(EmojiShortcodes.glyph("nope"))
        assertNull(EmojiShortcodes.glyph(""))
    }

    // MARK: - Scanning a message (`shortcodeScanRegex`, `splitTextByEmoji`)

    private fun scan(text: String, protecting: List<TextRange> = emptyList()): List<Pair<String, String>> =
        EmojiShortcodes.matches(text, protecting).map { text.substring(it.range) to it.glyph }

    @Test
    fun testMatchesEveryCompletedShortcode() {
        assertEquals(listOf(":smile:", ":tada:"), scan("a :smile: b :tada:").map { it.first })
        assertEquals(listOf("😄", "🎉"), scan("a :smile: b :tada:").map { it.second })
    }

    @Test
    fun testMatchesNamesWithPlusAndMinus() {
        assertEquals(listOf("👍", "📧"), scan(":+1: and :e-mail:").map { it.second })
    }

    @Test
    fun testConvertsAdjacentShortcodes() {
        assertEquals(listOf(TextRange(0, 4), TextRange(4, 8)), EmojiShortcodes.matches(":+1::+1:").map { it.range })
    }

    @Test
    fun testLeavesAnUnknownShortcodeLiteral() {
        assertEquals(0, scan("what :nope: is").size)
    }

    @Test
    fun testSkipsAColonGluedToAWordOrNumber() {
        // The composer's boundary rule: `word:smile:` is not a shortcode start, and neither is any
        // colon in a clock.
        assertEquals(0, scan("word:smile: 12:00:00").size)
        assertEquals(0, scan("at 12:00:00 sharp").size)
        assertEquals(0, scan("C:\\path").size)
        assertEquals(0, scan(":-)").size)
    }

    @Test
    fun testMatchesCaseInsensitivelyOverASCIIOnly() {
        assertEquals(listOf("🎉"), scan(":Tada:").map { it.second })
        // The web's regex has no Unicode case folding: a Kelvin sign is not a `k`, and a long s
        // before the colon is not a name character gluing it shut.
        assertEquals(0, scan(":s\u212Aull:").size)
        assertEquals(listOf("💀"), scan("\u017F:skull:").map { it.second })
    }

    @Test
    fun testAMatchTouchingAProtectedRangeIsLeftAlone() {
        // The renderer hands over its spoilers and links; a shortcode inside either stays text.
        val text = "ok :tada: https://x.test/:tada: done :skull:"
        val linkStart = text.indexOf("https://")
        val link = TextRange(linkStart, linkStart + "https://x.test/:tada:".length)
        assertEquals(listOf(":tada:", ":skull:"), scan(text, listOf(link)).map { it.first })
        assertEquals(listOf(3, 37), EmojiShortcodes.matches(text, listOf(link)).map { it.range.start })
    }

    @Test
    fun testNothingInEmptyText() {
        assertEquals(emptyList(), EmojiShortcodes.matches(""))
    }

    // MARK: - Replacing (the composer's edit; the renderer's marks follow `TextRange.moved`)

    @Test
    fun testReplacedSwapsTheMatchForItsGlyph() {
        val text = "hi :tada: all"
        val match = EmojiShortcodes.matches(text).single()
        assertEquals("hi 🎉 all", EmojiShortcodes.replaced(text, match))
    }

    // MARK: - The composer (`findCompletedShortcode`)

    @Test
    fun testCompletedFindsTheShortcodeWhoseClosingColonWasJustTyped() {
        val text = "hi :tada:"
        assertEquals(EmojiShortcodes.Match(TextRange(3, 9), "🎉"), EmojiShortcodes.completed(text, text.length))
    }

    @Test
    fun testCompletedHandlesPlusAndMinusAndCase() {
        assertEquals("👍", EmojiShortcodes.completed(":+1:", 4)?.glyph)
        assertEquals("🦴", EmojiShortcodes.completed(":Bone:", 6)?.glyph)
    }

    @Test
    fun testCompletedIsNullWithoutAClosingColonAtTheCaret() {
        assertNull(EmojiShortcodes.completed(":tada", 5))
        // The caret inside the token, or after a space, is not the closing moment.
        assertNull(EmojiShortcodes.completed(":tada: ", 7))
        assertNull(EmojiShortcodes.completed(":tada:", 3))
        assertNull(EmojiShortcodes.completed("", 0))
    }

    @Test
    fun testCompletedIsNullForAClockAMidWordColonRunOrAnUnknownName() {
        assertNull(EmojiShortcodes.completed("12:00:", 6))
        assertNull(EmojiShortcodes.completed("word:smile:", 11))
        assertNull(EmojiShortcodes.completed(":nope:", 6))
    }

    @Test
    fun testCompletedIsNullWhenANewlineFollowsTheShortcode() {
        // java.util.regex's `$` matches before a final line terminator; the pattern uses `\z` so a
        // newline typed after a literal `:tada:` (a restored line, a synced draft) doesn't swap the
        // line above.
        assertNull(EmojiShortcodes.completed(":tada:\n", 7))
        assertNull(EmojiShortcodes.completed("hi :tada:\n", 10))
    }

    @Test
    fun testCompletedOnlyLooksBeforeTheCaret() {
        // Text after the caret is someone else's problem — the conversion is for what was just typed.
        val text = ":tada: :skull:"
        assertEquals("🎉", EmojiShortcodes.completed(text, 6)?.glyph)
        assertNull(EmojiShortcodes.completed(text, 20))
    }

    // MARK: - A typed reaction (`reactionFromInput`)

    @Test
    fun testReactionResolvesAWholeShortcodeClosingColonOptional() {
        assertEquals("🎉", EmojiShortcodes.reaction(":tada:"))
        assertEquals("🎉", EmojiShortcodes.reaction(":tada"))
        assertEquals("👍", EmojiShortcodes.reaction(" :+1: "))
        assertEquals("👎", EmojiShortcodes.reaction(":-1"))
    }

    @Test
    fun testReactionResolvesOneLetterNamesLikeTheWeb() {
        // gemoji has one-letter names, so `:x` and `:o` are ❌ and ⭕ here as in the web's
        // `reactionFromInput` — parity, pinned so a change to it is a decision for all three clients.
        assertEquals("❌", EmojiShortcodes.reaction(":x"))
        assertEquals("⭕", EmojiShortcodes.reaction(":o"))
    }

    @Test
    fun testReactionSendsAnythingElseAsTyped() {
        assertEquals(":nope:", EmojiShortcodes.reaction(":nope:"))
        assertEquals(":D", EmojiShortcodes.reaction(":D"))
        assertEquals(":-D", EmojiShortcodes.reaction(":-D"))
        assertEquals(":-)", EmojiShortcodes.reaction(":-)"))
        assertEquals("lol", EmojiShortcodes.reaction("lol"))
        assertEquals("👍", EmojiShortcodes.reaction("👍"))
        assertEquals(":tada: :tada:", EmojiShortcodes.reaction(":tada: :tada:"))
        assertEquals("", EmojiShortcodes.reaction("  "))
    }
}
