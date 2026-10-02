// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.rendering.FormattingRun
import net.amiantos.lurkerkit.rendering.IRCColor
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.rendering.IRCPalette
import net.amiantos.lurkerkit.rendering.NickColor
import net.amiantos.lurkerkit.rendering.URLMatcher
import net.amiantos.lurkerkit.support.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure rendering pieces: the mIRC control-code parser, the per-nick color hash, and
 * URL extraction. The styled-text assembly lives in the app; this locks the logic
 * the web client, iOS and Android must agree on.
 */
class RenderingTests {

    // MARK: - mIRC formatting

    /**
     * What a line reads as, for showing a message somewhere that can't render its formatting —
     * the actions sheet's header (lurker-ios#60). The control byte is invisible but its color
     * digits are not, so an unstripped header shows a different string from the line that was
     * pressed.
     */
    @Test
    fun testStripRemovesControlCodesAndKeepsText() {
        assertEquals("ALERT disk full", IRCFormatting.strip("\u000304ALERT\u0003 disk full"))
        assertEquals("bold and italic", IRCFormatting.strip("\u0002bold\u0002 and \u001Ditalic\u001D"))
        assertEquals("warned again", IRCFormatting.strip("\u000304,08warned\u000F again"))
    }

    /**
     * Text with nothing to strip comes back untouched — including a bare digit after a word,
     * which must not be mistaken for a color argument.
     */
    @Test
    fun testStripLeavesPlainTextAlone() {
        assertEquals("just a message", IRCFormatting.strip("just a message"))
        assertEquals("route 66 is long", IRCFormatting.strip("route 66 is long"))
        assertEquals("", IRCFormatting.strip(""))
    }

    @Test
    fun testBoldTogglesRuns() {
        val runs = IRCFormatting.parse("a\u0002b\u0002c")
        assertEquals(listOf("a", "b", "c"), runs.map { it.text })
        assertEquals(listOf(false, true, false), runs.map { it.bold })
    }

    @Test
    fun testColorParsesForegroundAndBackground() {
        val runs = IRCFormatting.parse("\u000304,08red")
        assertEquals(1, runs.size)
        assertEquals("red", runs[0].text)
        assertEquals(IRCColor.Slot(4), runs[0].fg)
        assertEquals(IRCColor.Slot(8), runs[0].bg)
    }

    @Test
    fun testForegroundOnlyLeavesNoBackground() {
        val runs = IRCFormatting.parse("\u000304red")
        assertEquals(IRCColor.Slot(4), runs[0].fg)
        assertNull(runs[0].bg)
        assertEquals("red", runs[0].text)
    }

    @Test
    fun testResetClearsFormatting() {
        val runs = IRCFormatting.parse("\u0002\u000304loud\u000Fplain")
        assertEquals("plain", runs.lastOrNull()?.text)
        assertEquals(false, runs.lastOrNull()?.bold)
        assertNull(runs.lastOrNull()?.fg)
    }

    @Test
    fun testMonospaceIsConsumed() {
        val runs = IRCFormatting.parse("a\u0011b")
        assertEquals("ab", runs.joinToString("") { it.text })
    }

    /**
     * Reverse is a toggle on the run, cleared by a reset — and the colours stay as sent, because
     * the swap is the renderer's (an unset side swaps with the theme, which only it knows).
     */
    @Test
    fun testReverseTogglesAndResets() {
        val runs = IRCFormatting.parse("a\u0016\u000304b\u0016c\u0016d\u000Fe")
        assertEquals(listOf("a", "b", "c", "d", "e"), runs.map { it.text })
        assertEquals(listOf(false, true, false, true, false), runs.map { it.reverse })
        assertEquals(IRCColor.Slot(4), runs[1].fg)
        assertNull(runs[1].bg)
    }

    // MARK: - Truecolour (\x04)

    @Test
    fun testTruecolourParsesForegroundAndBackground() {
        val runs = IRCFormatting.parse("\u0004FF8800,00ff00art")
        assertEquals(1, runs.size)
        assertEquals("art", runs[0].text)
        assertEquals(IRCColor.Rgb(0xFF8800u), runs[0].fg)
        assertEquals(IRCColor.Rgb(0x00FF00u), runs[0].bg)
    }

    /**
     * A foreground alone keeps the background in effect, whichever code set it — and a bare
     * \x04 resets both, like a bare \x03.
     */
    @Test
    fun testTruecolourForegroundKeepsBackgroundAndBareResets() {
        val runs = IRCFormatting.parse("\u000304,08a\u0004123456b\u0004c")
        assertEquals(listOf("a", "b", "c"), runs.map { it.text })
        assertEquals(IRCColor.Rgb(0x123456u), runs[1].fg)
        assertEquals(IRCColor.Slot(8), runs[1].bg)
        assertNull(runs[2].fg)
        assertNull(runs[2].bg)
    }

    /**
     * Exactly six hex per colour, as the web and the server's `FORMAT_RE` take it. Short hex is
     * no colour: the \x04 is bare and its would-be digits are text. A comma without a full
     * colour after it is text too.
     */
    @Test
    fun testTruecolourNeedsSixHexAndLeavesAStrayComma() {
        assertEquals("abcde!", IRCFormatting.strip("\u0004abcde!"))
        assertEquals(",12345 x", IRCFormatting.strip("\u0004abcdef,12345 x"))
        assertEquals(",nothex", IRCFormatting.strip("\u0004abcdef,nothex"))
        assertEquals("abc", IRCFormatting.strip("\u0004abcdefabc"))
        val runs = IRCFormatting.parse("\u000304,08a\u0004abcdef,zz")
        assertEquals(",zz", runs.lastOrNull()?.text)
        assertEquals(
            IRCColor.Slot(8), runs.lastOrNull()?.bg,
            "a failed background must not touch the one in effect",
        )
    }

    /** The spoiler test: an equal PAINTABLE pair, hex included. Slots past 15 paint nothing. */
    @Test
    fun testHidesTextNeedsAPaintableEqualPair() {
        assertTrue(IRCFormatting.parse("\u000301,01x")[0].hidesText)
        assertTrue(IRCFormatting.parse("\u0004AABBCC,aabbcc x")[0].hidesText)
        assertFalse(IRCFormatting.parse("\u000399,99x")[0].hidesText)
        assertFalse(IRCFormatting.parse("\u0004aabbcc,aabbcd x")[0].hidesText)
        assertFalse(IRCFormatting.parse("\u000301x")[0].hidesText)
        // The parser never yields a negative slot, but `IRCColor` is public and none is paintable.
        assertFalse(
            FormattingRun(
                text = "x", bold = false, italic = false, underline = false, strike = false,
                reverse = false, fg = IRCColor.Slot(-1), bg = IRCColor.Slot(-1),
            ).hidesText
        )
    }

    /**
     * The reverse swap, with strings standing in for colours. An unset or unpaintable side is
     * the theme's own; a spoiler is not swapped.
     */
    @Test
    fun testPaintSwapsUnderReverse() {
        fun paint(raw: String, fg: String?, bg: String?): List<String?> {
            val pair = IRCFormatting.parse(raw)[0].paint(fg = fg, bg = bg, text = "text", canvas = "canvas")
            return listOf(pair.ink, pair.fill)
        }
        assertEquals(listOf("text", null), paint("x", fg = null, bg = null))
        assertEquals(listOf("red", "yellow"), paint("\u000304,08x", fg = "red", bg = "yellow"))
        assertEquals(listOf("canvas", "text"), paint("\u0016x", fg = null, bg = null))
        assertEquals(listOf("yellow", "red"), paint("\u0016\u000304,08x", fg = "red", bg = "yellow"))
        assertEquals(listOf("canvas", "red"), paint("\u0016\u000304x", fg = "red", bg = null))
        // 99 is a slot the caller can't paint, so it resolves to null and swaps as unset.
        assertEquals(listOf("yellow", "text"), paint("\u0016\u000399,08x", fg = null, bg = "yellow"))
        assertEquals(listOf("black", "black"), paint("\u0016\u000301,01x", fg = "black", bg = "black"))
    }

    @Test
    fun testPlainTextIsASingleRun() {
        val runs = IRCFormatting.parse("hello world")
        assertEquals(
            listOf(
                FormattingRun(
                    text = "hello world", bold = false, italic = false, underline = false,
                    strike = false, reverse = false, fg = null, bg = null,
                )
            ),
            runs,
        )
    }

    // MARK: - Nick colors

    @Test
    fun testNickColorIsDeterministic() {
        assertEquals(NickColor.index("alice"), NickColor.index("alice"))
    }

    @Test
    fun testNickColorTrimsStopChars() {
        // Away/alt suffixes must not change the color.
        assertEquals(NickColor.index("amiantos"), NickColor.index("amiantos__"))
        assertEquals(NickColor.index("amiantos"), NickColor.index("amiantos|"))
    }

    @Test
    fun testNickColorIsCaseInsensitive() {
        assertEquals(NickColor.index("alice"), NickColor.index("Alice"))
    }

    @Test
    fun testNickColorIndexInRange() {
        for (nick in listOf("a", "somebody", "🙂user", "___", "z9")) {
            val index = NickColor.index(nick)
            assertTrue(index >= 0 && index < IRCPalette.nick.size, "$nick → $index")
        }
    }

    // MARK: - URLs

    @Test
    fun testMatchesAnHttpUrl() {
        val matches = URLMatcher.matches("see https://example.com/x now")
        assertEquals(1, matches.size)
        assertEquals("https://example.com/x", matches[0].href)
    }

    @Test
    fun testWwwGetsAnHttpScheme() {
        assertEquals("http://www.example.com", URLMatcher.matches("www.example.com").firstOrNull()?.href)
    }

    @Test
    fun testTrailingPunctuationIsTrimmed() {
        assertEquals("https://example.com", URLMatcher.matches("go to https://example.com.").firstOrNull()?.href)
    }

    @Test
    fun testUnbalancedClosingParenIsTrimmed() {
        assertEquals("https://example.com", URLMatcher.matches("(see https://example.com)").firstOrNull()?.href)
    }

    /**
     * ⚠⚠ The reason the trim COUNTS brackets rather than stripping closers outright, and the
     * property the running-balance rewrite had to preserve: a pair that closes what it opened
     * belongs to the address. Getting this wrong resolves the URL one character short, the card
     * silently never appears, and the 404 is cached for an hour under a string appearing nowhere
     * in the message.
     */
    @Test
    fun testABalancedPairBelongsToTheAddress() {
        assertEquals(
            "https://e.test/wiki/Rust_(programming_language)",
            URLMatcher.matches("https://e.test/wiki/Rust_(programming_language)").firstOrNull()?.href,
        )
        // ...and it is still balanced when the whole thing is wrapped in prose brackets, where the
        // surplus comes from the closer the sentence added rather than from the address.
        assertEquals(
            "https://e.test/wiki/Rust_(programming_language)",
            URLMatcher.matches("(see https://e.test/wiki/Rust_(programming_language))").firstOrNull()
                ?.href,
        )
    }

    /**
     * ⚠ The shape the balance tally exists to keep cheap. Recomputing the count per character
     * makes this quadratic in the app-wide linkifier; the web's copy of the function carries a
     * note that it cost ~0.5ms per render. Asserted for CORRECTNESS at length — a timing
     * assertion would just be a flake — so a future rewrite that drops the running tally still
     * has to produce the right answer here.
     */
    @Test
    fun testALongRunOfClosersIsTrimmedToTheAddress() {
        val url = "https://e.test/a.png"
        assertEquals(url, URLMatcher.matches(url + ")".repeat(100)).firstOrNull()?.href)
    }

    @Test
    fun testBareEmailGetsMailto() {
        assertEquals("mailto:me@example.com", URLMatcher.matches("ping me@example.com").firstOrNull()?.href)
    }

    /**
     * `<https://example.com>` is RFC 3986 Appendix C's delimiter convention, which Discord
     * borrowed as "link, but no unfurl". `PreviewSelection` declines to resolve one; the
     * renderer deletes the brackets, so the report is the range they occupy.
     */
    @Test
    fun testAngleBracketsAreReportedSoTheRendererCanDropThem() {
        val matches = URLMatcher.matches("see <https://example.com> now")
        assertEquals(1, matches.size)
        assertEquals("https://example.com", matches[0].href)
        // The `<` through the `>`, so deleting it takes both delimiters and nothing else.
        assertEquals(TextRange.of(location = 4, length = 21), matches[0].delimiters)
    }

    /**
     * ⚠⚠ Inside brackets the URL is NOT trailing-punctuation trimmed — the author has stated
     * where the address ends, which is the whole reason the convention exists. Trimming here
     * would also mean the closing `>` no longer sits where the bracket test looks for it, so
     * the convention would stop being recognised on exactly the ambiguous URLs it is for.
     */
    @Test
    fun testABracketedUrlKeepsItsTrailingPunctuation() {
        val matches = URLMatcher.matches("<https://en.wikipedia.org/wiki/Foo.>")
        assertEquals("https://en.wikipedia.org/wiki/Foo.", matches.firstOrNull()?.href)
    }

    /**
     * ⚠⚠ The convention delimits a URI. A bare `foo@bar.com` is not one — we merely GUESS
     * `mailto:` for it — and a guess is not grounds for rewriting what somebody typed. The
     * renderer deletes whatever `delimiters` reports, so reporting it here rewrote
     * `Co-Authored-By: Claude <noreply@anthropic.com>` into
     * `Co-Authored-By: Claude noreply@anthropic.com`, in every message, for every user, with
     * both preview settings off. RFC 5322 angle-addr is ordinary IRC traffic.
     */
    @Test
    fun testAngleBracketsAroundABareEmailAreLeftAlone() {
        val matches = URLMatcher.matches("Co-Authored-By: Claude <noreply@anthropic.com>")
        assertEquals(1, matches.size)
        assertEquals("mailto:noreply@anthropic.com", matches[0].href)
        assertNull(matches[0].delimiters, "a bare email is not a URI the convention delimits")
    }

    /**
     * ...while a written scheme still is one, including the `www.` case `isBracketedUrl`'s own
     * note calls out.
     */
    @Test
    fun testAngleBracketsStillStripForAWrittenScheme() {
        assertNotNull(URLMatcher.matches("<https://example.com>").firstOrNull()?.delimiters)
        assertNotNull(URLMatcher.matches("<www.example.com>").firstOrNull()?.delimiters)
        assertNotNull(URLMatcher.matches("<mailto:a@b.co>").firstOrNull()?.delimiters)
    }

    /**
     * ⚠⚠ `range` is the TRIMMED address and it is the only span on offer — the untrimmed match
     * is deliberately no longer returned beside it (lurker-ios#126). It used to be, for the
     * hidden-URL deletion, and having both within reach is what let that deletion take a closing
     * delimiter whose partner sat in the prose: `look at this (<url>)` rendered as
     * `look at this (`. Reaching past the address for the punctuation it may absorb is
     * `PreviewText.absorbing`'s job now, because that question has an answer this trimmer does
     * not know.
     */
    @Test
    fun testRangeIsTheTrimmedAddressAndNothingElse() {
        val text = "look at this https://e.test/a.png."
        val match = URLMatcher.matches(text).firstOrNull()
        assertEquals("https://e.test/a.png", match?.href)
        assertEquals("https://e.test/a.png".length, match?.range?.length)
    }

    /**
     * ⚠⚠ The two trims INTERLEAVE, so one pass each is not enough. The sentence pass used to run
     * once and hand over to the bracket pass: `(…/a.png.)` stopped it dead on the `)`, the bracket
     * pass then exposed a `.`, and nothing looked again. The address kept a full stop nobody
     * typed, the tappable link 404'd, and `PreviewSelection` sent the resolver a string appearing
     * nowhere in the message — which is then negative-cached for an hour.
     */
    @Test
    fun testTrimsToAFixpointWhenThePunctuationInterleaves() {
        assertEquals(
            "https://e.test/a.png", URLMatcher.matches("(https://e.test/a.png.)").firstOrNull()?.href,
        )
        assertEquals(
            "https://e.test/a.png",
            URLMatcher.matches("[see https://e.test/a.png!]").firstOrNull()?.href,
        )
    }

    /**
     * ⚠ macOS substitutes `…` for `...` as you type, so it arrives in pasted text constantly.
     * Without it in the trim set it rode into the href: the link broke, and `looksLikeMedia` saw
     * a path not ending in `.png` and charged the URL to the tight CARD budget instead of the
     * generous media one.
     */
    @Test
    fun testAnEllipsisIsSentencePunctuationLikeAnyOther() {
        assertEquals(
            "https://e.test/a.png",
            URLMatcher.matches("look at this https://e.test/a.png…").firstOrNull()?.href,
        )
    }

    @Test
    fun testAnOrdinaryUrlReportsNoDelimiters() {
        assertNull(URLMatcher.matches("see https://example.com now").firstOrNull()?.delimiters)
        // A half-open bracket is ordinary prose, not the convention.
        assertNull(URLMatcher.matches("<https://example.com").firstOrNull()?.delimiters)
    }

    // Port-only: the hash itself, pinned to the values LurkerKit's `NickColor.djb2` returns for
    // the same strings (taken from the Swift, compiled and run). Swift gets the arithmetic from
    // `UInt32` and the unit from `unicodeScalars`; here both are choices, and a wrong one still
    // passes every test above — they only compare this hash with itself.
    @Test
    fun testDjb2MatchesLurkerKitsValues() {
        assertEquals(5381u, NickColor.djb2(""))
        assertEquals(3409177982u, NickColor.djb2("alice"))
        assertEquals(1872691423u, NickColor.djb2("amiantos"))
        // An astral code point is one term of the hash, not two surrogates.
        assertEquals(3416923356u, NickColor.djb2("🙂user"))
        // Long enough to wrap 32 bits many times over.
        assertEquals(
            3872202070u,
            NickColor.djb2("averyveryveryveryveryverylongnicknamethatoverflowsthirtytwobits_"),
        )
        assertEquals(2, NickColor.index("alice"))
        assertEquals(9, NickColor.index("amiantos"))
        assertEquals(7, NickColor.index("🙂user"))
    }
}
