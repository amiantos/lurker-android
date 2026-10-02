// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.RelayEnvelope
import net.amiantos.lurkerkit.model.RelayParse
import net.amiantos.lurkerkit.model.RelayTemplate
import net.amiantos.lurkerkit.rendering.IRCFormatting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The envelope parser (lurker#277), ported alongside `shared/parseRelay.ts` — and with that
 * file's tests ported too, so the clients are held to the same reading of the same bot output.
 * Cases beyond the web's are marked where they appear; they cover the two things this port does
 * differently (a native regex engine instead of a JS `RegExp`, and UTF-16 index mapping instead
 * of JS string slicing).
 */
class RelayEnvelopeTests {

    // MARK: - Default formats

    @Test
    fun testParsesTheBracketedSourceForm() {
        assertEquals(
            RelayParse(source = "Discord", nick = "alice", text = "hello there"),
            RelayEnvelope.parse("[Discord] <alice> hello there"),
        )
    }

    @Test
    fun testParsesTheBareNickForm() {
        assertEquals(
            RelayParse(source = null, nick = "bob", text = "hey everyone"),
            RelayEnvelope.parse("<bob> hey everyone"),
        )
    }

    @Test
    fun testKeepsBracketsAndAnglesInsideTheBody() {
        assertEquals(
            RelayParse(source = "Telegram", nick = "carol", text = "2 < 3 and [maybe] > nope"),
            RelayEnvelope.parse("[Telegram] <carol> 2 < 3 and [maybe] > nope"),
        )
    }

    @Test
    fun testPreservesAnEmptyRelayedMessage() {
        assertEquals(
            RelayParse(source = "IRC", nick = "dave", text = ""),
            RelayEnvelope.parse("[IRC] <dave> "),
        )
    }

    @Test
    fun testDoesNotMatchAPlainLine() {
        assertNull(RelayEnvelope.parse("just a normal line from the bot"))
        assertNull(RelayEnvelope.parse("lunch < later"))
    }

    @Test
    fun testReturnsNilForEmptyBodies() {
        assertNull(RelayEnvelope.parse(""))
        assertNull(RelayEnvelope.parse(null))
    }

    @Test
    fun testHandlesASourceTagContainingSpaces() {
        assertEquals(
            RelayParse(source = "Game Chat", nick = "eve", text = "gg"),
            RelayEnvelope.parse("[Game Chat] <eve> gg"),
        )
    }

    // MARK: - Membership prefixes

    @Test
    fun testDropsMembershipPrefixesFromTheNick() {
        assertEquals(
            RelayParse(source = null, nick = "bob", text = "hi"),
            RelayEnvelope.parse("<+bob> hi"),
        )
        assertEquals(
            RelayParse(source = "net", nick = "carol", text = "yo"),
            RelayEnvelope.parse("[net] <@+carol> yo"),
        )
        assertEquals(
            RelayParse(source = null, nick = "dave", text = "hey"),
            RelayEnvelope.parse("<dave> hey"),
        )
    }

    /**
     * Beyond the web's suite. A nick that is *only* prefix glyphs strips to nothing, and an
     * envelope with no speaker in it isn't a re-attribution — it has to fall through to the next
     * template (and here, to no match at all) rather than produce a nameless author.
     */
    @Test
    fun testANickOfNothingButPrefixesDoesNotMatch() {
        assertNull(RelayEnvelope.parse("<@@> hi"))
    }

    // MARK: - Custom templates

    @Test
    fun testHonorsACustomTemplate() {
        assertEquals(
            RelayParse(source = null, nick = "frank", text = "yo"),
            RelayEnvelope.parse("frank: yo", pattern = "{nick}: {message}"),
        )
        assertEquals(
            RelayParse(source = "Matrix", nick = "grace", text = "hi"),
            RelayEnvelope.parse("(Matrix) grace » hi", pattern = "({source}) {nick} » {message}"),
        )
    }

    @Test
    fun testFallsBackToTheDefaultsWhenTheCustomPatternIsBlank() {
        assertEquals(
            RelayParse(source = "Slack", nick = "heidi", text = "ok"),
            RelayEnvelope.parse("[Slack] <heidi> ok", pattern = "   "),
        )
    }

    @Test
    fun testReturnsNilWhenTheTemplateLacksRequiredPlaceholders() {
        assertNull(RelayEnvelope.parse("whatever", pattern = "no placeholders here"))
        assertNull(RelayEnvelope.parse("<x> y", pattern = "<{nick}> no-message-placeholder"))
    }

    // MARK: - Reversed layout (nick before source)

    /**
     * A real ##videogames bot that posts `<nick> [source] message` — the reverse of the default —
     * plus a stray colour code and fancy unicode/emoji in the body.
     */
    private val reversed = "\u000303<EyeSeeYou> [Discord] Present: 𝔅𝔢𝔩𝔦𝔞𝔩 ChatGAYTB 🌈🏳\uFE0F\u200D🌈 syrius"

    @Test
    fun testAMatchingCustomTemplateExtractsTheReversedLayout() {
        assertEquals(
            RelayParse(
                source = "Discord", nick = "EyeSeeYou",
                text = "Present: 𝔅𝔢𝔩𝔦𝔞𝔩 ChatGAYTB 🌈🏳\uFE0F\u200D🌈 syrius",
            ),
            RelayEnvelope.parse(reversed, pattern = "<{nick}> [{source}] {message}"),
        )
    }

    @Test
    fun testTheDefaultsStillAttributeTheReversedLayoutButLeaveTheSourceInline() {
        // The bare `<nick> message` default catches it, so re-attribution works, but the reversed
        // [source] tag isn't recognized — it stays in the body. This is the behavior that
        // motivates the custom template above.
        assertEquals(
            RelayParse(
                source = null, nick = "EyeSeeYou",
                text = "[Discord] Present: 𝔅𝔢𝔩𝔦𝔞𝔩 ChatGAYTB 🌈🏳\uFE0F\u200D🌈 syrius",
            ),
            RelayEnvelope.parse(reversed),
        )
    }

    // MARK: - mIRC formatting

    @Test
    fun testStripsColourCodesAndTheVoicePrefixOffTheNick() {
        // The bot colours `+FAST` (voiced on efnet) with mIRC colour 13 and resets before `>`. We
        // want a clean `FAST` so colouring, Reply and Copy all target the nick.
        assertEquals(
            RelayParse(source = "efnet", nick = "FAST", text = "ultros: bet"),
            RelayEnvelope.parse("[efnet] <\u000313+FAST\u0003> ultros: bet"),
        )
    }

    @Test
    fun testStripsColourCodesWrappingTheSourceTag() {
        assertEquals(
            RelayParse(source = "efnet", nick = "FAST", text = "hey there"),
            RelayEnvelope.parse("\u000304[efnet]\u0003 <\u000313FAST\u0003> hey there"),
        )
    }

    @Test
    fun testStripsBoldAndUnderlineTogglesAroundTheEnvelope() {
        assertEquals(
            RelayParse(source = "Discord", nick = "underlined", text = "hello"),
            RelayEnvelope.parse("[\u0002Discord\u0002] <\u001funderlined\u001f> hello"),
        )
    }

    @Test
    fun testPreservesTheMessagesOwnFormatting() {
        // The nick is coloured (stripped for matching), but the message is bold — and that bold
        // has to survive into the re-attributed line.
        assertEquals(
            RelayParse(source = "efnet", nick = "FAST", text = "\u0002bold msg\u0002"),
            RelayEnvelope.parse("[efnet] <\u000313FAST\u0003> \u0002bold msg\u0002"),
        )
        assertEquals(
            RelayParse(
                source = null, nick = "relaybot",
                text = "\u000304red\u0003 and \u000309green\u0003",
            ),
            RelayEnvelope.parse("<\u000307relaybot\u0003> \u000304red\u0003 and \u000309green\u0003"),
        )
    }

    /**
     * Beyond the web's suite, and the reason `rawIndex` counts UTF-16 units rather than
     * characters: an astral-plane glyph *before* the message is two units and one Character, so a
     * character-counting map would slice two units short and behead the body.
     */
    @Test
    fun testRecoversFormattingPastAnAstralGlyphInTheEnvelope() {
        assertEquals(
            RelayParse(source = "🌈net", nick = "FAST", text = "\u0002bold\u0002"),
            RelayEnvelope.parse("[🌈net] <\u000313FAST\u0003> \u0002bold\u0002"),
        )
    }

    // MARK: - Template compilation

    @Test
    fun testCompilesTheBuiltInDefaults() {
        for (pattern in RelayEnvelope.defaultPatterns) {
            assertNotNull(RelayEnvelope.compile(pattern), pattern)
        }
    }

    @Test
    fun testRecordsSlotOrder() {
        assertEquals(
            listOf(RelayTemplate.Slot.Source, RelayTemplate.Slot.Nick, RelayTemplate.Slot.Message),
            RelayEnvelope.compile("[{source}] <{nick}> {message}")?.slots,
        )
    }

    /**
     * ⚠ `{message}` being the last *placeholder* is not the same as it being the end of the
     * template. Slicing the raw body to its end on a template with a trailing literal put that
     * literal back into what the person said.
     */
    @Test
    fun testATrailingLiteralIsNotPartOfTheMessage() {
        assertEquals(
            RelayParse(source = null, nick = "alice", text = "hi there"),
            RelayEnvelope.parse("<alice> hi there (via bridge)", pattern = "<{nick}> {message} (via bridge)"),
        )
        // The message's own formatting still survives the bounded slice — but a control code
        // sitting exactly ON the end boundary falls to the envelope's side, because `rawIndex`
        // answers where the next VISIBLE character begins and a code contributes none. So the
        // closing `\u0002` here is dropped and the run is left open.
        //
        // Deliberately not chased. A toggle at the end of a string closes nothing: the renderer
        // parses each message on its own, so `\u0002bold` and `\u0002bold\u0002` draw identically,
        // and no formatting can leak past a row. Buying the byte back would mean a third scanner
        // over the same control codes — the thing `testRawIndexAgreesWithStrip` exists to keep
        // this file from accumulating.
        assertEquals(
            RelayParse(source = null, nick = "alice", text = "\u0002bold"),
            RelayEnvelope.parse(
                "<alice> \u0002bold\u0002 -- end", pattern = "<{nick}> {message} -- end",
            ),
        )
    }

    /**
     * The counterpart: with nothing after `{message}`, the slice runs to the end of the body so a
     * closing code stays attached to the run it closes. (Covered above too, but stated here as
     * the invariant `endsWithMessage` exists to keep.)
     */
    @Test
    fun testATemplateEndingInMessageKeepsTrailingFormatCodes() {
        assertEquals("\u0002bold\u0002", RelayEnvelope.parse("<alice> \u0002bold\u0002")?.text)
        assertTrue(RelayEnvelope.compile("<{nick}> {message}")?.endsWithMessage == true)
        assertTrue(RelayEnvelope.compile("<{nick}> {message} x")?.endsWithMessage == false)
    }

    @Test
    fun testRejectsATemplateMissingNickOrMessage() {
        assertNull(RelayEnvelope.compile("<{nick}> static"))
        assertNull(RelayEnvelope.compile("{message} only"))
    }

    @Test
    fun testTreatsRegexMetacharactersInTheTemplateAsLiterals() {
        // The `.` and `*` are literal here, so a real `.*` in the body must match them verbatim
        // rather than acting as a wildcard.
        assertEquals(
            RelayParse(source = "a", nick = "ivan", text = "done"),
            RelayEnvelope.parse("a.*b ivan done", pattern = "{source}.*b {nick} {message}"),
        )
        assertNull(RelayEnvelope.parse("aXXb ivan done", pattern = "{source}.*b {nick} {message}"))
    }

    /**
     * ⚠ The property the escaping exists for, stated as a test rather than left to the comment on
     * `RelayEnvelope`. A template is user input; reaching the regex compiler unescaped it would
     * be a live regex running against every line a marked bot ever said. An unbalanced `(` is the
     * cheapest proof: as a pattern it doesn't compile at all, so a template that still parses a
     * body containing a literal `(` can only have been escaped.
     */
    @Test
    fun testAUserTemplateCannotInjectRegex() {
        assertEquals(
            RelayParse(source = null, nick = "jan", text = "hi"),
            RelayEnvelope.parse("( jan hi", pattern = "( {nick} {message}"),
        )
        // And an alternation in a template is text, not a choice: `a|b` matches only `a|b`.
        assertNull(RelayEnvelope.parse("a kim hi", pattern = "a|b {nick} {message}"))
        assertEquals(
            RelayParse(source = null, nick = "kim", text = "hi"),
            RelayEnvelope.parse("a|b kim hi", pattern = "a|b {nick} {message}"),
        )
    }

    /**
     * A body with a trailing newline must not match, which is where ICU's `$` and JavaScript's
     * disagree — hence the `\A`/`\z` anchors. Without them the two clients would attribute the
     * same line differently.
     */
    @Test
    fun testATrailingNewlineDoesNotSatisfyTheAnchor() {
        assertNull(RelayEnvelope.parse("<bob> hi\n"))
    }

    // MARK: - The two scanners agree

    /**
     * ⚠ `IRCFormatting.rawIndex` walks control codes in a second scanner, separate from the one
     * `parse` uses. This is what pins them together: for every visible offset in a corpus of
     * awkward bodies, slicing the raw text at the mapped index and stripping it must equal
     * stripping first and slicing the result. A disagreement lands a slice mid-code, and the
     * symptom in the app is a relayed message that opens with stray colour digits.
     */
    @Test
    fun testRawIndexAgreesWithStrip() {
        val corpus = listOf(
            "plain text with no codes at all",
            "\u000313+FAST\u0003 ultros: bet",
            "\u000304,12both halves\u0003 then \u00039 one",
            "\u000304,not-a-background — the comma is text",
            "\u0003 bare reset, \u000399 out of palette, \u000f full reset",
            "\u0004ff8800truecolor\u000400ff00,0000ff pair\u0004nothex",
            "\u0004abcde short hex\u0004ABCDEF,12345 short bg\u0004abcdef,zzzzzz stray comma\u0004",
            "\u0004123456,\u0004654321,abcdef\u0016\u0004",
            "\u0002bold\u0002 \u001ditalic\u001d \u001funder\u001f \u001estrike\u001e \u0011mono\u0016rev",
            "🌈🏳\uFE0F\u200D🌈 astral \u000303and colour\u0003 𝔅𝔢𝔩𝔦𝔞𝔩",
            "trailing code at the very end\u0003",
            "\u00031",
        )
        for (raw in corpus) {
            val stripped = IRCFormatting.strip(raw)
            val strippedUnits = stripped.length
            for (offset in 0..strippedUnits) {
                val rawStart = IRCFormatting.rawIndex(raw, visibleOffset = offset)
                assertEquals(
                    stripped.substring(offset),
                    IRCFormatting.strip(raw.substring(rawStart)),
                    "offset $offset of $raw",
                )
            }
        }
    }

    @Test
    fun testRawIndexClampsOutOfRangeOffsets() {
        assertEquals(0, IRCFormatting.rawIndex("abc", visibleOffset = -1))
        assertEquals(3, IRCFormatting.rawIndex("abc", visibleOffset = 99))
        assertEquals(0, IRCFormatting.rawIndex("", visibleOffset = 3))
    }

    // Port-only: the two places this side spells the regex differently from LurkerKit, each
    // pinned to the answer the Swift gives for the same input.

    /**
     * The literals are quoted with `\Q…\E` here, where `NSRegularExpression.escapedPattern`
     * backslashes each metacharacter. A template that itself contains the quote markers is the
     * input that could tell the two apart, and must still be read as plain text.
     */
    @Test
    fun testATemplateContainingQuoteMarkersIsStillLiteral() {
        val bob = RelayParse(source = null, nick = "bob", text = "hi")
        assertEquals(bob, RelayEnvelope.parse("\\Ebob\\Q hi", pattern = "\\E{nick}\\Q {message}"))
        assertEquals(bob, RelayEnvelope.parse("\\Qbob\\E hi", pattern = "\\Q{nick}\\E {message}"))
        assertEquals(bob, RelayEnvelope.parse("\\Ebob\\E hi\\E", pattern = "\\E{nick}\\E {message}\\E"))
        assertEquals(
            bob,
            RelayEnvelope.parse("\$^bob.*+?()[]|\\/ hi", pattern = "\$^{nick}.*+?()[]|\\/ {message}"),
        )
        // A class escape in a template is two characters of text, not a digit.
        assertEquals(bob, RelayEnvelope.parse("bob\\dhi", pattern = "{nick}\\d{message}"))
        assertNull(RelayEnvelope.parse("bob5hi", pattern = "{nick}\\d{message}"))
        // And an inline flag is text too.
        assertNull(RelayEnvelope.parse("bob hi", pattern = "(?i){nick} {message}"))
    }

    /**
     * `{source}` and `{message}` stop at ICU's line terminators, which include the vertical tab
     * and the form feed that OpenJDK's `.` would let through — see `RelayTemplate.Slot.group`.
     * `{nick}` stops at Unicode white space, which a zero-width space is not.
     */
    @Test
    fun testTheCaptureGroupsStopWhereICUsDo() {
        assertNull(RelayEnvelope.parse("[s\u000Bt] <ab> hi"))
        assertNull(RelayEnvelope.parse("[s\u000Ct] <ab> hi"))
        assertNull(RelayEnvelope.parse("<ab> h\u000Bi"))
        assertNull(RelayEnvelope.parse("<ab> h\u000Ci"))
        assertEquals(
            RelayParse(source = "s\tt", nick = "ab", text = "hi"),
            RelayEnvelope.parse("[s\tt] <ab> hi"),
        )
        assertEquals(
            RelayParse(source = null, nick = "ab", text = "h\u00A0i"),
            RelayEnvelope.parse("<ab> h\u00A0i"),
        )

        assertNull(RelayEnvelope.parse("<a\u000Bb> hi"))
        assertNull(RelayEnvelope.parse("<a\u0085b> hi"))
        assertNull(RelayEnvelope.parse("<a\u00A0b> hi"))
        assertEquals(
            RelayParse(source = null, nick = "a\u200Bb", text = "hi"),
            RelayEnvelope.parse("<a\u200Bb> hi"),
        )
    }
}
