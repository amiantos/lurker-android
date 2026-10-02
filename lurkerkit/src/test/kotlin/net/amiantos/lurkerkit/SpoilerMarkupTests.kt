// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.SpoilerMarkup
import net.amiantos.lurkerkit.rendering.FormattingRun
import net.amiantos.lurkerkit.rendering.IRCColor
import net.amiantos.lurkerkit.rendering.IRCFormatting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ported case-for-case from the web client's `spoilerMarkup.test.ts`. The implementations
 * have to turn the same typed text into the same bytes, so they get the same tests — a
 * divergence here is a message that reads differently depending on which client sent it.
 */
class SpoilerMarkupTests {
    private val open = "\u000314,14"
    private val close = "\u0003"

    @Test
    fun testLeavesTextWithNoDoublePipesUntouched() {
        assertEquals("hello world", SpoilerMarkup.apply("hello world"))
        assertEquals("", SpoilerMarkup.apply(""))
        assertEquals("a | b", SpoilerMarkup.apply("a | b"))
    }

    @Test
    fun testRewritesABasicSpoiler() {
        assertEquals("${open}secret$close", SpoilerMarkup.apply("||secret||"))
    }

    @Test
    fun testPreservesTextAroundASpoiler() {
        assertEquals(
            "the answer is ${open}42$close ok?",
            SpoilerMarkup.apply("the answer is ||42|| ok?"),
        )
    }

    @Test
    fun testRewritesMultipleSpoilers() {
        assertEquals(
            "${open}a$close and ${open}b$close",
            SpoilerMarkup.apply("||a|| and ||b||"),
        )
    }

    /**
     * `||a||b||c||` is a spoiler, a literal `b`, then another spoiler — the nearest closing `||`
     * wins, matching Discord, which is where the expectation comes from.
     */
    @Test
    fun testPairsNonGreedily() {
        assertEquals(
            "${open}a${close}b${open}c$close",
            SpoilerMarkup.apply("||a||b||c||"),
        )
    }

    @Test
    fun testLeavesAnUnmatchedDelimiterLiteral() {
        assertEquals("||unclosed", SpoilerMarkup.apply("||unclosed"))
        assertEquals("trailing||", SpoilerMarkup.apply("trailing||"))
    }

    @Test
    fun testLeavesAnEmptyPairLiteral() {
        assertEquals("||||", SpoilerMarkup.apply("||||"))
    }

    @Test
    fun testTreatsBackslashPipePipeAsAnEscape() {
        assertEquals("exit code || 1", SpoilerMarkup.apply("""exit code \|| 1"""))
        assertEquals("||not a spoiler||", SpoilerMarkup.apply("""\||not a spoiler\||"""))
    }

    @Test
    fun testAllowsAnEscapedDelimiterInsideARealSpoiler() {
        assertEquals(
            "${open}has || inside$close",
            SpoilerMarkup.apply("""||has \|| inside||"""),
        )
    }

    @Test
    fun testLeavesALoneBackslashLiteral() {
        assertEquals("""a \ b""", SpoilerMarkup.apply("""a \ b"""))
        assertEquals("""path\to\file""", SpoilerMarkup.apply("""path\to\file"""))
    }

    /**
     * A trailing `\|` must not read as the start of an escape and eat past the end of the string.
     * This indexes the string with bounds that throw, rather than JS's forgiving string
     * subscript, so this is the case where a missing bounds check would throw rather than
     * quietly return undefined.
     */
    @Test
    fun testHandlesATruncatedEscapeAtTheEnd() {
        assertEquals("""trailing \|""", SpoilerMarkup.apply("""trailing \|"""))
        assertEquals("""\|""", SpoilerMarkup.apply("""\|"""))
        assertEquals("|", SpoilerMarkup.apply("|"))
    }
}

// Waiting on CommandParser — the whole `SpoilerCommandCoverageTests` class:
// testRewritesUserAuthoredChatBodies, testLeavesServiceAndRawVerbsUntouched,
// testDoesNotRewriteGeneratedBodies

/**
 * The half that matters at runtime: what we emit has to come back through our own parser as a
 * spoiler. The two live in different files and are free to drift — a pair the parser didn't
 * recognise would ship as visible plaintext, which for a spoiler is the entire failure.
 */
class SpoilerRoundTripTests {
    private fun runs(text: String): List<FormattingRun> = IRCFormatting.parse(text)

    @Test
    fun testEmittedSpoilerParsesBackAsAMatchingColourPair() {
        val parsed = runs(SpoilerMarkup.apply("||secret||"))
        assertEquals(1, parsed.size)
        assertEquals("secret", parsed.firstOrNull()?.text)
        assertEquals(IRCColor.Slot(14), parsed.firstOrNull()?.fg)
        assertEquals(IRCColor.Slot(14), parsed.firstOrNull()?.bg)
    }

    /**
     * The colour code is two digits and so is the hidden text here: `\u000314,14` followed by
     * `42` must read as grey-on-grey plus the text "42", not as colour 14 on 1442.
     */
    @Test
    fun testDoesNotSwallowLeadingDigitsOfTheHiddenText() {
        val parsed = runs(SpoilerMarkup.apply("the answer is ||42||"))
        assertEquals(listOf("the answer is ", "42"), parsed.map { it.text })
        assertEquals(IRCColor.Slot(14), parsed.lastOrNull()?.fg)
        assertEquals(IRCColor.Slot(14), parsed.lastOrNull()?.bg)
    }

    /**
     * Spoilers from clients that use the older black-on-black convention still have to read as
     * spoilers — the rule is "fg == bg", not "fg == 14".
     */
    @Test
    fun testRecognisesAnIncomingBlackOnBlackSpoiler() {
        val parsed = runs("\u000301,01secret\u0003")
        assertEquals(IRCColor.Slot(1), parsed.firstOrNull()?.fg)
        assertEquals(IRCColor.Slot(1), parsed.firstOrNull()?.bg)
    }

    /**
     * ⚠⚠ The close is the dangerous end. A bare `\u0003` followed by a digit is a COLOUR CODE,
     * so the digit is eaten: `||spoiler||5 stars` used to reach the channel as " stars" in
     * colour 5, the "5" simply deleted, and `||code||1234` lost two characters. Silent, on the
     * wire, unrecoverable.
     *
     * These assert the round trip rather than the bytes: what matters is that every character
     * the user typed after the spoiler survives to the other side, and that the spoiler's
     * background doesn't bleed onto it.
     */
    @Test
    fun testTextAfterASpoilerSurvivesEvenWhenItStartsWithADigit() {
        for ((input, hidden, after) in listOf(
            Triple("||spoiler||5 stars", "spoiler", "5 stars"),
            Triple("||secret||42 is the code", "secret", "42 is the code"),
            Triple("||a||0", "a", "0"),
            Triple("the code is ||1234||5678", "1234", "5678"),
        )) {
            val parsed = runs(SpoilerMarkup.apply(input))
            val isSpoiler = { run: FormattingRun -> run.fg == IRCColor.Slot(14) && run.bg == IRCColor.Slot(14) }
            val spoiler = parsed.firstOrNull(isSpoiler)
            assertEquals(hidden, spoiler?.text, "hidden half of $input")

            // Everything after the hidden run, concatenated, must equal what was typed after it.
            val index = parsed.indexOfFirst(isSpoiler)
            if (index < 0) fail("no spoiler run in $input")
            val tail = parsed.drop(index + 1)
            assertEquals(after, tail.joinToString("") { it.text }, "text after $input")
            // …and it must not still be sitting on the spoiler's grey box.
            for (run in tail) {
                assertNotEquals(IRCColor.Slot(14), run.bg, "background leaked past the spoiler in $input")
            }
        }
    }

    /** The common cases keep the cheap one-byte close; only the collision pays for the long one. */
    @Test
    fun testKeepsTheBareCloseWhenNothingCollides() {
        assertTrue(SpoilerMarkup.apply("||a|| ok").endsWith("\u0003 ok"))
        assertTrue(SpoilerMarkup.apply("||a||").endsWith("a\u0003"))
        // A comma is safe: `\u0003,` is not a colour code, only a digit can start one.
        assertTrue(SpoilerMarkup.apply("||a||,b").endsWith("\u0003,b"))
    }

    /**
     * ⚠ The trigger is ASCII `0`–`9`, because that is exactly what `IRCFormatting` reads after a
     * `\u0003`. `Char.isDigit` is true of some of these and none of them can start a colour
     * code, so treating them as collisions would spend the heavier close — and 99's
     * less-universal semantics — on text that never needed it, disproportionately non-Latin.
     */
    @Test
    fun testANonASCIINumeralIsNotACollision() {
        for (numeral in listOf("٣", "²", "②", "Ⅷ", "½", "一")) {
            val wire = SpoilerMarkup.apply("||a||$numeral")
            assertTrue(
                wire.endsWith("a\u0003$numeral"),
                "$numeral should take the bare close, got ${wire.map { it.code }}",
            )
            // And it must still survive the round trip, which is the point of the whole exercise.
            val parsed = runsFor(wire)
            assertEquals(numeral, parsed.lastOrNull()?.text, "$numeral survives")
        }
    }

    private fun runsFor(text: String): List<FormattingRun> = IRCFormatting.parse(text)
}
