// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.ColorMarkup
import net.amiantos.lurkerkit.commands.ColorSpan
import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.commands.SpoilerMarkup
import net.amiantos.lurkerkit.rendering.IRCFormatting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ColorMarkupTests {

    /** What a line reads as: the parser's runs, adjacent equal runs merged, slot 99 as no colour. */
    private fun reads(line: String): List<ColorSpan> =
        ColorMarkup.decode(line) ?: emptyList()

    /**
     * What a channel receives for a typed plain-text line: the composer's line through the send
     * path's chat body, which is where spoilers are made.
     */
    private fun sent(spans: List<ColorSpan>): String =
        ColorMarkup.chatBody(ColorMarkup.encode(spans))

    private fun assertRoundTrips(spans: List<ColorSpan>) {
        val wire = ColorMarkup.encode(spans)
        assertEquals(spans.filter { it.text.isNotEmpty() }, reads(wire), "wire: ${wire.map { it.code }}")
        assertEquals(spans.joinToString("") { it.text }, IRCFormatting.strip(wire))
    }

    @Test
    fun testPlainTextIsUntouched() {
        assertEquals("hello", ColorMarkup.encode(listOf(ColorSpan("hello"))))
        assertEquals("", ColorMarkup.encode(emptyList()))
    }

    @Test
    fun testWritesTwoDigitSlots() {
        assertEquals("\u000304hi", ColorMarkup.encode(listOf(ColorSpan("hi", fg = 4))))
        assertEquals("\u000304,01hi", ColorMarkup.encode(listOf(ColorSpan("hi", fg = 4, bg = 1))))
        assertEquals("\u000399,12hi", ColorMarkup.encode(listOf(ColorSpan("hi", bg = 12))))
    }

    @Test
    fun testClosesBackToPlain() {
        assertEquals(
            "\u000304red\u0003 plain",
            ColorMarkup.encode(listOf(ColorSpan("red", fg = 4), ColorSpan(" plain"))),
        )
    }

    /** The digit trap: a bare close or a one-digit slot followed by a digit swallows it. */
    @Test
    fun testDigitsAfterACodeSurvive() {
        assertRoundTrips(listOf(ColorSpan("x", fg = 4), ColorSpan("5 stars")))
        assertRoundTrips(listOf(ColorSpan("2 cats", fg = 4)))
        assertRoundTrips(listOf(ColorSpan("12", fg = 3, bg = 1), ColorSpan("34")))
    }

    /** A keycap is one non-ASCII Character that opens with an ASCII digit scalar. */
    @Test
    fun testAKeycapAfterACodeSurvives() {
        assertRoundTrips(listOf(ColorSpan("x", fg = 4), ColorSpan("1️⃣ first")))
        assertRoundTrips(listOf(ColorSpan("1️⃣", fg = 4)))
    }

    /** `\x0304` then `,5` is red on blue. */
    @Test
    fun testACommaDigitAfterAForegroundSurvives() {
        assertRoundTrips(listOf(ColorSpan(",5 cats", fg = 4)))
        assertRoundTrips(listOf(ColorSpan("a"), ColorSpan(",9", fg = 7)))
    }

    /** A bare foreground keeps the background in effect, so dropping one must be said. */
    @Test
    fun testDroppingTheBackgroundIsWritten() {
        assertRoundTrips(listOf(ColorSpan("box", fg = 0, bg = 1), ColorSpan(" red", fg = 4)))
    }

    @Test
    fun testMixedRoundTrips() {
        assertRoundTrips(
            listOf(
                ColorSpan("plain "), ColorSpan("red", fg = 4), ColorSpan(" on blue", fg = 4, bg = 2),
                ColorSpan(" hilite", bg = 8), ColorSpan(" done"),
            )
        )
    }

    // MARK: - Commands

    @Test
    fun testCommandHeadsStayBare() {
        assertEquals("/me \u000304waves", ColorMarkup.encode(listOf(ColorSpan("/me waves", fg = 4))))
        assertEquals("/SHRUG \u000304idk", ColorMarkup.encode(listOf(ColorSpan("/SHRUG idk", fg = 4))))
        assertEquals("/msg bob \u000304hi there", ColorMarkup.encode(listOf(ColorSpan("/msg bob hi there", fg = 4))))
        assertEquals("/notice #c  \u000304psst", ColorMarkup.encode(listOf(ColorSpan("/notice #c  psst", fg = 4))))
        assertEquals("/query bob", ColorMarkup.encode(listOf(ColorSpan("/query bob", fg = 4))))
        // The escape: both slashes stay bare, and the text after them keeps its colour.
        assertEquals("//\u000304hi", ColorMarkup.encode(listOf(ColorSpan("//hi", fg = 4))))
    }

    /**
     * Commands whose text is a topic or a reason: the optional channel, the nick and the flags
     * are words of the command.
     */
    @Test
    fun testReasonsAndTopicsTakeColour() {
        assertEquals("/topic \u000304Welcome", ColorMarkup.encode(listOf(ColorSpan("/topic Welcome", fg = 4))))
        assertEquals("/topic #c \u000304Welcome", ColorMarkup.encode(listOf(ColorSpan("/topic #c Welcome", fg = 4))))
        assertEquals("/part \u000304bye", ColorMarkup.encode(listOf(ColorSpan("/part bye", fg = 4))))
        assertEquals("/kick bob \u000304out", ColorMarkup.encode(listOf(ColorSpan("/kick bob out", fg = 4))))
        assertEquals("/kick #c bob \u000304out", ColorMarkup.encode(listOf(ColorSpan("/kick #c bob out", fg = 4))))
        assertEquals("/away -all \u000304lunch", ColorMarkup.encode(listOf(ColorSpan("/away -all lunch", fg = 4))))
        // Only `-all` and `-one` are flags; anything else is the message.
        assertEquals("/away \u000304-_- brb", ColorMarkup.encode(listOf(ColorSpan("/away -_- brb", fg = 4))))
    }

    /** Any other command has no chat body, so it goes out exactly as typed. */
    @Test
    fun testOtherCommandsTakeNoColour() {
        assertEquals("/join #chan", ColorMarkup.encode(listOf(ColorSpan("/join #chan", fg = 4))))
        assertEquals(
            "/ns identify pw",
            ColorMarkup.encode(listOf(ColorSpan("/ns identify ", fg = 4), ColorSpan("pw", bg = 2))),
        )
        assertEquals("/away", ColorMarkup.encode(listOf(ColorSpan("/away", fg = 4))))
    }

    // MARK: - Spoilers

    /**
     * The colour stops at the box and resumes after it, and the box hides what's inside whatever
     * colour it was.
     */
    @Test
    fun testBuildsSpoilersInsideColour() {
        assertEquals(
            listOf(ColorSpan("red ", fg = 4), ColorSpan("secret", fg = 14, bg = 14), ColorSpan(" more", fg = 4)),
            reads(sent(listOf(ColorSpan("red ||secret|| more", fg = 4)))),
        )
        assertEquals(
            listOf(ColorSpan("a "), ColorSpan("x", fg = 14, bg = 14), ColorSpan(" 5")),
            reads(sent(listOf(ColorSpan("a ||"), ColorSpan("x", fg = 4), ColorSpan("|| 5")))),
        )
    }

    /** Two touching spoilers are two boxes, as the plain rewrite makes them. */
    @Test
    fun testTouchingSpoilersStaySeparate() {
        val wire = sent(listOf(ColorSpan("||a||||b||", fg = 4)))
        assertEquals("\u000314,14a\u0003\u000314,14b", wire)
        assertEquals(IRCFormatting.strip(SpoilerMarkup.apply("||a||||b||")), IRCFormatting.strip(wire))
    }

    /** The draft keeps `||` as typed — spoilers are made on the way out, not in the field. */
    @Test
    fun testTheLineKeepsDelimitersAsTyped() {
        assertEquals("\u000304a ||b||", ColorMarkup.encode(listOf(ColorSpan("a ||b||", fg = 4))))
        val spans = listOf(ColorSpan("a "), ColorSpan("||b||", fg = 4))
        assertEquals(spans, ColorMarkup.decode(ColorMarkup.encode(spans)))
    }

    /**
     * Pairs and escapes are read from the characters, so a colour change between the two `|`
     * changes nothing about them.
     */
    @Test
    fun testDelimitersSplitByColourStillPair() {
        assertEquals("x", IRCFormatting.strip(sent(listOf(ColorSpan("|", fg = 4), ColorSpan("|x||", fg = 2)))))
        assertEquals("x||y", IRCFormatting.strip(sent(listOf(ColorSpan("x\\", fg = 4), ColorSpan("||y", fg = 2)))))
        assertEquals("||x", IRCFormatting.strip(sent(listOf(ColorSpan("|", fg = 4), ColorSpan("|x", fg = 2)))))
    }

    /** A plain body is the old rewrite, byte for byte. */
    @Test
    fun testAPlainBodyIsTheOldRewrite() {
        for (body in listOf("||a||", "a || b", "x \\||y||", "||a||5")) {
            assertEquals(SpoilerMarkup.apply(body), ColorMarkup.chatBody(body))
        }
    }

    // MARK: - Line breaks

    @Test
    fun testRecolorsEachLine() {
        val wire = ColorMarkup.encode(listOf(ColorSpan("one\ntwo", fg = 2)))
        assertEquals("\u000302one\u0003\n\u000302two", wire)
        for (line in wire.split("\n").filter { it.isNotEmpty() }) {
            assertEquals(2, reads(line).firstOrNull()?.fg)
        }
    }

    /** A plain line after a coloured one has to read as plain in the joined text too. */
    @Test
    fun testAPlainLineAfterAColouredOneStaysPlain() {
        assertRoundTrips(listOf(ColorSpan("red", fg = 4), ColorSpan("\nplain")))
        assertRoundTrips(listOf(ColorSpan("box", fg = 0, bg = 1), ColorSpan("\n"), ColorSpan("fg only", fg = 4)))
    }

    @Test
    fun testCRLFAndCRAreLineBreaks() {
        assertEquals("\u000302one\u0003\r\n\u000302two", ColorMarkup.encode(listOf(ColorSpan("one\r\ntwo", fg = 2))))
        assertEquals("\u000302one\u0003\r\u000302two", ColorMarkup.encode(listOf(ColorSpan("one\rtwo", fg = 2))))
    }

    // MARK: - Decoding

    @Test
    fun testDecodesPaletteColors() {
        assertEquals(
            listOf(ColorSpan("a"), ColorSpan("red", fg = 4), ColorSpan(" b")),
            ColorMarkup.decode("a\u00034red\u0003 b"),
        )
        assertEquals(listOf(ColorSpan("x", bg = 5)), ColorMarkup.decode("\u000399,05x"))
        assertEquals(listOf(ColorSpan("plain")), ColorMarkup.decode("plain"))
        assertEquals(emptyList(), ColorMarkup.decode(""))
    }

    /** A reset with nothing to reset reads as the plain text it shows. */
    @Test
    fun testDecodesAResetOnlyLineAsPlain() {
        assertEquals(listOf(ColorSpan("hello")), ColorMarkup.decode("\u000399hello"))
    }

    /** Anything beyond palette colour stays raw, so nothing is lost. */
    @Test
    fun testDeclinesWhatItCannotHold() {
        assertNull(ColorMarkup.decode("\u0002bold\u0002"))
        assertNull(ColorMarkup.decode("\u001Dit"))
        assertNull(ColorMarkup.decode("\u0004FF0000red"))
        assertNull(ColorMarkup.decode("\u000342odd"))
        // No text follows it, so it makes no run — but it's still the colour in effect.
        assertNull(ColorMarkup.decode("\u000304red\u000342"))
        assertNull(ColorMarkup.decode("\u000304,42red"))
    }

    /** Shown without its code, each of these would send as a different line. */
    @Test
    fun testDeclinesACodeInTheHead() {
        assertNull(ColorMarkup.decode("\u000304/me waves"))
        assertNull(ColorMarkup.decode("/j\u000304oin #x"))
        assertNull(ColorMarkup.decode("/msg \u000304bob hi"))
        assertNotNull(ColorMarkup.decode("/me \u000304waves"))
        assertNotNull(ColorMarkup.decode("/msg bob \u000304hi"))
    }

    /** A command with no chat body can't keep colour, so a coloured one stays raw. */
    @Test
    fun testDeclinesColourOnACommandWithoutABody() {
        assertNull(ColorMarkup.decode("/join \u000304#x"))
        // A reset changes no colour, but in front of the slash it's what makes the line text.
        assertNull(ColorMarkup.decode("\u0003/join #x"))
        assertNull(ColorMarkup.decode("\u000F/quit bye"))
        assertNull(ColorMarkup.decode("/j\u0003oin #x"))
        assertEquals(listOf(ColorSpan("/join #x")), ColorMarkup.decode("/join #x"))
    }

    @Test
    fun testResetReadsAsAColourReset() {
        assertEquals(
            listOf(ColorSpan("red", fg = 4), ColorSpan("plain")),
            ColorMarkup.decode("\u000304red\u000Fplain"),
        )
    }

    @Test
    fun testIsColored() {
        assertFalse(ColorMarkup.isColored(listOf(ColorSpan("a"))))
        assertFalse(ColorMarkup.isColored(listOf(ColorSpan("", fg = 3))))
        assertTrue(ColorMarkup.isColored(listOf(ColorSpan("a", bg = 3))))
    }

    /** The send path makes the spoilers: the parser runs the coloured body through `chatBody`. */
    @Test
    fun testTheParserMakesSpoilersInsideColour() {
        val line = ColorMarkup.encode(listOf(ColorSpan("/me hides ||it|| ok", fg = 4)))
        val effects = (CommandParser.parse(line, networkId = 1, target = "#c", formatted = { it.toString() })
            as? ParsedInput.Command)?.effects
        val text = (effects?.firstOrNull() as? CommandEffect.Action)?.text ?: fail("expected an action")
        assertEquals(
            listOf(ColorSpan("hides ", fg = 4), ColorSpan("it", fg = 14, bg = 14), ColorSpan(" ok", fg = 4)),
            reads(text),
        )
    }

    // Port-only: the units each half counts in.

    /**
     * The head is counted in grapheme clusters, as LurkerKit counts `Character`s: a space wearing
     * a combining mark is one whitespace character, and the code goes after the mark, never
     * between the two.
     */
    @Test
    fun testTheHeadIsCountedInClusters() {
        assertEquals(
            "/me ́\u000304waves",
            ColorMarkup.encode(listOf(ColorSpan("/me ́waves", fg = 4))),
        )
        // A cluster the line opens with is never split by the first code either.
        assertEquals(
            "\u000304🇫🇷",
            ColorMarkup.encode(listOf(ColorSpan("🇫🇷", fg = 4))),
        )
    }

    /**
     * `chatBody` reads pairs in the units `SpoilerMarkup.apply` reads them in (UTF-16), so a
     * combining mark after a `|` makes the same spoiler coloured as plain.
     */
    @Test
    fun testAColouredBodyPairsInTheUnitsApplyPairsIn() {
        val typed = "||́x|| y"
        assertEquals(
            IRCFormatting.strip(SpoilerMarkup.apply(typed)),
            IRCFormatting.strip(sent(listOf(ColorSpan(typed, fg = 4)))),
        )
        assertEquals(
            listOf(ColorSpan("́x", fg = 14, bg = 14), ColorSpan(" y", fg = 4)),
            reads(sent(listOf(ColorSpan(typed, fg = 4)))),
        )
    }

    /** A CRLF a coloured body splits into two cells is still one break, reset once. */
    @Test
    fun testAColouredBodyKeepsCRLFWhole() {
        assertEquals(
            "\u000304a\u000314,14b\u0003\r\n\u000304c",
            sent(listOf(ColorSpan("a||b||\r\nc", fg = 4))),
        )
    }
}
