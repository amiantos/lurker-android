// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.commands.SpoilerMarkup
import net.amiantos.lurkerkit.rendering.FormattingRun
import net.amiantos.lurkerkit.rendering.IRCColor
import net.amiantos.lurkerkit.rendering.IRCFormatting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/**
 * Which commands rewrite `||` and which must not. The exclusions are the point: `/ns` and `/cs`
 * carry `identify <password>`, and a password containing `||` that arrives at NickServ as
 * control codes fails a login for reasons nobody will diagnose. The web left this to a comment
 * and a convention; here it's asserted.
 */
class SpoilerCommandCoverageTests {
    private val open = "\u000314,14"

    // Port note: `CommandParser.parse` takes the expiry's formatter here (see
    // `IgnoreRule.summary`); nothing in this class reaches a line that uses it.
    private fun parse(input: String): ParsedInput =
        CommandParser.parse(input, networkId = 1, target = "#chan", formatted = { it.toString() })

    /** Text bodies the user authored: these DO get rewritten. */
    @Test
    fun testRewritesUserAuthoredChatBodies() {
        val plain = (parse("say ||secret||") as? ParsedInput.Message)?.text
            ?: fail("plain text should be a message")
        assertTrue(plain.contains(open), "plain send")

        val escaped = (parse("//not a command ||secret||") as? ParsedInput.Message)?.text
            ?: fail("//-escaped should be a message")
        assertTrue(escaped.contains(open), "//-escaped send")

        val me = (parse("/me hides ||something||") as? ParsedInput.Command)?.effects
        val meText = (me?.firstOrNull() as? CommandEffect.Action)?.text
            ?: fail("/me should produce an action")
        assertTrue(meText.contains(open), "/me")

        val msg = (parse("/msg bob ||secret||") as? ParsedInput.Command)?.effects
        val msgText = (msg?.firstOrNull() as? CommandEffect.Send)?.text
            ?: fail("/msg should produce a send")
        assertTrue(msgText.contains(open), "/msg")

        val notice = (parse("/notice bob ||secret||") as? ParsedInput.Command)?.effects
        val noticeText = (notice?.firstOrNull() as? CommandEffect.Notice)?.text
            ?: fail("/notice should produce a notice")
        assertTrue(noticeText.contains(open), "/notice")
    }

    /**
     * ⚠⚠ Service and raw verbs must reach the wire byte-for-byte as typed. A password is the
     * realistic case, and `||` is a plausible character in one.
     *
     * ⚠ Every fixture holds a MATCHED pair. An earlier version used `hunter||2` — a single
     * unmatched `||`, which `SpoilerMarkup` leaves alone regardless — so the test passed with
     * the exclusion deliberately broken. Verified by mutation: routing `/ns` through `chatBody`
     * now fails this, and did not before.
     */
    @Test
    fun testLeavesServiceAndRawVerbsUntouched() {
        for (input in listOf(
            "/ns identify ||hunter2||",
            "/cs identify #chan ||hunter2||",
            "/raw PRIVMSG bob :||literal||",
            "/quote PRIVMSG bob :||literal||",
        )) {
            val effects = (parse(input) as? ParsedInput.Command)?.effects
            val line = (effects?.firstOrNull() as? CommandEffect.Raw)?.line
                ?: fail("$input should produce a raw line")
            assertTrue(line.contains("||"), "$input must keep its literal pipes")
            assertFalse(line.contains("\u0003"), "$input must carry no colour codes")
        }
    }

    /**
     * `/slap`'s body is generated rather than typed, so there is nothing in it to spoiler — and
     * a nick is not a place a `||` should be interpreted.
     */
    @Test
    fun testDoesNotRewriteGeneratedBodies() {
        val effects = (parse("/slap bo||b") as? ParsedInput.Command)?.effects
        val text = (effects?.firstOrNull() as? CommandEffect.Action)?.text
            ?: fail("/slap should produce an action")
        assertFalse(text.contains("\u0003"))
    }
}

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

    /** A keycap is not an ASCII character, but its first scalar is the digit the parser reads. */
    @Test
    fun testAKeycapIsACollision() {
        val wire = SpoilerMarkup.apply("||a||1\uFE0F\u20E3")
        assertTrue(wire.endsWith("a\u000399,991\uFE0F\u20E3"), wire.map { it.code }.toString())
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
