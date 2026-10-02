// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.SpoilerMarkup
import net.amiantos.lurkerkit.model.AttributedBody
import net.amiantos.lurkerkit.model.PreviewHiding
import net.amiantos.lurkerkit.model.PreviewSelection
import net.amiantos.lurkerkit.model.PreviewText
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.rendering.URLMatcher
import net.amiantos.lurkerkit.support.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Mirrors the `hideableUrls` half of `vue_client/src/utils/previewUrls.test.ts`.
 *
 * When a message's picture is on screen, the address that produced it is a duplicate of what
 * the reader is already looking at. When it is mid-sentence, it is part of something somebody
 * wrote. Telling those apart is the whole of this rule.
 *
 * Port note: a Swift Testing suite in LurkerKit ("PreviewHiding").
 */
class PreviewHidingTests {

    private val a = "https://e.test/a.png"
    private val b = "https://e.test/b.png"
    private val c = "https://e.test/c.png"
    private val page = "https://news.example/article"

    private fun hidden(text: String?, vararg candidates: String): List<String> =
        PreviewHiding.hideableUrls(text, candidates = candidates.toSet()).sorted()

    /** hides a URL that is the whole message */
    @Test
    fun wholeMessage() {
        assertEquals(listOf(a), hidden(a, a))
    }

    /** hides a URL the message begins or ends with */
    @Test
    fun atAnEdge() {
        assertEquals(listOf(a), hidden("$a look at this", a))
        assertEquals(listOf(a), hidden("look at this $a", a))
    }

    /** KEEPS a URL with prose on both sides */
    @Test
    fun proseOnBothSides() {
        // The rule's whole point. Mid-sentence the address is part of something somebody wrote —
        // "I read $URL and then..." — and deleting it leaves a sentence with a hole in it.
        assertTrue(hidden("I read $a this morning", a).isEmpty())
    }

    /** hides every URL in a message that is nothing but URLs */
    @Test
    fun peelsThroughTheMiddle() {
        // ⚠⚠ The reason this is a peel rather than a per-URL edge test. `b` touches neither end
        // of the message; it becomes an edge only once `a` has been taken.
        assertEquals(listOf(a, b, c).sorted(), hidden("$a $b $c", a, b, c))
    }

    /** peels forward through a run of URLs when only the leading end is free */
    @Test
    fun leadingPeelAdvancesOnItsOwn() {
        // ⚠⚠ This case exists because the one above could not see the bug it names. With prose
        // at neither end, the TRAILING peel reaches every URL by itself — so replacing the
        // leading peel's cursor with a fixed "is it at position zero" test left that suite green
        // while the rule was broken. Adding a tail parks the trailing peel at the first
        // character and leaves the leading peel to do the work alone, which is the only
        // arrangement that can tell a peel from an edge test.
        assertEquals(listOf(a, b).sorted(), hidden("$a $b tail", a, b))
        // ...and its mirror, so neither end is guarded only by the other.
        assertEquals(listOf(a, b).sorted(), hidden("lead $a $b", a, b))
    }

    /** stops peeling at a URL that is NOT a candidate */
    @Test
    fun stopsAtANonCandidate() {
        // A page link renders a card and KEEPS its address, so it is not something the peel may
        // step over: anything behind it is still in the middle of the line. Without the
        // candidate test the peel would consume the page as though it were hidden — and, worse,
        // report it as hidden, taking the address off a card that is about to render one.
        assertTrue(hidden("$page $b tail", b).isEmpty())
        assertTrue(hidden("lead $b $page", b).isEmpty())
    }

    /** hides a trailing image even when a card precedes it */
    @Test
    fun trailingImageBehindACard() {
        // The flip side of the above, and the reason the peel is per-end: the message still ENDS
        // with the picture, so its address is still a duplicate of what the reader can see.
        assertEquals(listOf(b), hidden("$page $b", b))
    }

    /** peels from both ends independently */
    @Test
    fun bothEnds() {
        assertEquals(listOf(a, c).sorted(), hidden("$a some words $b more words $c", a, b, c))
    }

    /** does not count a spoiler run as whitespace */
    @Test
    fun spoilerTextOccupiesTheLine() {
        // ⚠ Spoiler runs contribute no URLs — a hidden link must never be resolved — but their
        // TEXT still occupies the line. Ignoring it entirely would make a URL that follows a
        // spoiler look like the start of the message and take its address out from under the
        // reveal box.
        assertEquals(listOf(a), hidden("${SpoilerMarkup.apply("||psst||")} $a", a))
        assertTrue(hidden("${SpoilerMarkup.apply("||psst||")} $a tail", a).isEmpty())
    }

    /** trailing sentence punctuation does not decide the verdict */
    @Test
    fun trimmedPunctuationDoesNotBlockThePeel() {
        // ⚠⚠ The span's end was measured to the end of the TRIMMED address, so the `.` the
        // trimmer had just discarded sat between the span and the end of the message and failed
        // the "nothing but whitespace after it" test. The same URL at the FRONT hid regardless,
        // because the leading check is vacuously true at offset zero — so identical punctuation
        // produced opposite verdicts depending only on which end the URL sat at.
        assertEquals(listOf(a), hidden("look at this $a.", a))
        assertEquals(listOf(a), hidden("look at this $a!", a))
        assertEquals(listOf(a), hidden("look at this $a?!", a))
        assertEquals(listOf(a), hidden("look at this $a,", a))
        // ...and prose still wins over punctuation, which is the rule the fix must not soften.
        assertTrue(hidden("I read $a. this morning", a).isEmpty())
    }

    /** will not absorb a delimiter whose partner is in the prose */
    @Test
    fun pairedDelimitersAreNotAbsorbed() {
        // ⚠⚠ This is lurker-ios#126, and the assertion here used to say the opposite: `($a)` was
        // expected to hide. The span measured to the end of the raw MATCH, which swallowed the
        // `)` — so the URL looked flush against the end of the message, and the renderer, deleting
        // the same span, took the closer and left `look at this (` painted above the picture. A
        // lone bracket is not whitespace, so the blank-body collapse never fired either.
        //
        // The rule now stops at anything PAIRED. Its partner sits in the prose, and taking half a
        // pair is the same orphan this whole rule exists to prevent — merely moved to the other
        // end. So a wrapped URL is simply not hideable: its address stays on screen beside its own
        // preview, which is redundant rather than broken.
        assertTrue(hidden("look at this ($a)", a).isEmpty())
        assertTrue(hidden("look at this [$a]", a).isEmpty())
        assertTrue(hidden("he said \"check $a\"", a).isEmpty())
        assertTrue(hidden("he said 'check $a'", a).isEmpty())

        // ⚠ The same address with nothing wrapping it still hides, so the assertions above are
        // about the delimiters rather than about the fixture.
        assertEquals(listOf(a), hidden("look at this $a", a))
    }

    /** a formatting code between the address and its full stop changes nothing */
    @Test
    fun punctuationAcrossAFormattingBoundary() {
        // ⚠⚠ A common bot-output shape: the URL is bolded and the sentence's full stop is not, so
        // the regex match stops at the run boundary and the `.` sits outside it. The web had to
        // reach for its VISIBLE body to see this; iOS gets it free, because `urlSpans` already
        // scans what `MessageRenderer` assembled — but only while the absorption is measured
        // there too, which is what this guards.
        assertEquals(listOf(a), hidden("look at this \u0002$a\u0002.", a))
        // And the paired case stays paired across the boundary for the same reason.
        assertTrue(hidden("look at this (\u0002$a\u0002)", a).isEmpty())
    }

    /** hides nothing when there are no candidates */
    @Test
    fun noCandidates() {
        assertTrue(PreviewHiding.hideableUrls("$a $b", candidates = emptySet()).isEmpty())
        assertTrue(hidden(null, a).isEmpty())
    }

    /** a bracketed URL never becomes a candidate, so its text is never hidden */
    @Test
    fun bracketedIsNeverACandidate() {
        // ⚠⚠ Written first as `hideableUrls("<a>", candidates: [a])`, which passed against every
        // implementation — including one with the bracket test deleted — because `<` and `>` are
        // not whitespace, so a bracketed URL can never sit at a blank edge in the first place.
        // That assertion was about the punctuation, not about the rule.
        //
        // What actually keeps a bracketed URL's address on screen is upstream: it is never
        // resolved, so it is never in `candidates`. That is the property worth guarding, and it
        // spans the two modules, so the test does too.
        val text = "<$a>"
        val candidates = PreviewSelection.urls(text, inlineMedia = true, linkPreviews = true).toSet()
        assertTrue(candidates.isEmpty(), "nothing was resolved, so nothing can stand in for it")
        assertTrue(PreviewHiding.hideableUrls(text, candidates = candidates).isEmpty())

        // And the same message without the brackets does hide, so the assertion above is about
        // the brackets rather than about the fixture.
        val bare = PreviewSelection.urls(a, inlineMedia = true, linkPreviews = true).toSet()
        assertEquals(setOf(a), PreviewHiding.hideableUrls(a, candidates = bare))
    }
}

/**
 * The other half of the hiding rule: what actually comes out of the body.
 *
 * ⚠⚠ These drive `PreviewText.stripHiddenUrls` — the deletion itself — and that is the point.
 * On iOS the deletion used to live in `MessageRenderer`, in the app target, which has no test
 * bundle; what could be reached from here was `absorbing`, and a test comparing it against
 * `UrlSpan.end` is TAUTOLOGICAL, because `end` is computed by calling it. Such a test passes
 * against a renderer that deletes an entirely different range — which is precisely what
 * lurker-ios#126 and the orphaned full stop before it both were. Making the deletion reachable
 * is what turned this suite into a guard.
 *
 * Port note: a Swift Testing suite in LurkerKit ("PreviewHiding/absorption").
 */
class PreviewAbsorptionTests {

    private val a = "https://e.test/a.png"

    /**
     * What the URL at the head of `text` takes with it, beyond its own address.
     *
     * ⚠⚠ Fails rather than returning `""` when nothing matched. Every assertion about
     * a delimiter below is `.isEmpty()`, so a helper that answers `""` for "no URL here" satisfies
     * them whether the absorption correctly stopped at the `)` or the matcher simply found
     * nothing — and those are the exact assertions pinning lurker-ios#126. A test that cannot
     * fail is not a test; make the absence loud.
     */
    private fun absorbed(text: String): String {
        val match = URLMatcher.matches(text).firstOrNull()
            ?: fail("no URL matched in $text — the fixture, not the rule, is wrong")
        val span = PreviewText.absorbing(match.range, text)
        return text.substring(match.range.end, span.end)
    }

    /** takes sentence punctuation, which has no partner */
    @Test
    fun takesSentencePunctuation() {
        assertEquals(".", absorbed("look at this $a."))
        assertEquals("?!", absorbed("look at this $a?!"))
        assertEquals("...", absorbed("look at this $a..."))
        assertEquals(",", absorbed("look at this $a,"))
        assertEquals(";", absorbed("look at this $a; and more"))
    }

    /** leaves a closing delimiter alone, because its partner is not the URL's to take */
    @Test
    fun leavesPairedDelimiters() {
        // ⚠⚠ Every one of these is trimmed OFF the address by `URLMatcher.trimTrailingPunctuation`
        // — that is why reaching for "everything the trimmer dropped" looked like the fix and was
        // not. The trimmer answers "where does the address end"; this answers "what may the
        // address take with it", and they are different questions with different answers.
        assertTrue(absorbed("look at this ($a)").isEmpty())
        assertTrue(absorbed("look at this [$a]").isEmpty())
        assertTrue(absorbed("he said \"check $a\"").isEmpty())
        assertTrue(absorbed("he said 'check $a'").isEmpty())
    }

    /** takes nothing when the address runs to the end, or into whitespace */
    @Test
    fun nothingToAbsorb() {
        assertTrue(absorbed(a).isEmpty())
        assertTrue(absorbed("look at this $a and more").isEmpty())
    }

    /** what the rule measured is what the renderer deletes */
    @Test
    fun theTwoSidesAgree() {
        // ⚠⚠ Written first as a comparison of `absorbing(match.range)` against `span.end` — which
        // is TAUTOLOGICAL, because `span.end` is computed by calling `absorbing` on the same
        // range. It would have passed against a renderer that deleted `match.range` and orphaned
        // every full stop, which is the bug it claimed to guard. The deletion had to become
        // reachable before it could be pinned; that is why `stripHiddenUrls` moved into LurkerKit.
        //
        // So: assert the BODY, the thing the reader ends up looking at.
        val a = "https://e.test/a.png"
        assertEquals("look at this", stripped("look at this $a.", hiding = a))
        assertEquals("look at this", stripped("look at this $a?!", hiding = a))
        assertEquals("look at this", stripped("$a. look at this", hiding = a))
        assertEquals("look at this", stripped("look at this \u0002$a\u0002.", hiding = a))
    }

    /** a body that was only a link, and its punctuation, comes out empty */
    @Test
    fun onlyALinkCollapses() {
        // ⚠⚠ The symptom that made lurker#774 visible: the address went and the full stop stayed,
        // so the body was not empty, so the whitespace-only end-trim left it — and a line holding
        // a lone `.` was painted above the picture. An empty body is what lets the caller drop
        // the label.
        val a = "https://e.test/shot.png"
        assertTrue(stripped(a, hiding = a).isEmpty())
        assertTrue(stripped("$a.", hiding = a).isEmpty())
        assertTrue(stripped("  $a!  ", hiding = a).isEmpty())
        assertTrue(stripped("$a…", hiding = a).isEmpty())
    }

    /** a wrapped URL keeps its address rather than losing half a pair */
    @Test
    fun wrappedUrlIsLeftIntact() {
        // ⚠⚠ lurker-ios#126 itself, asserted where it was actually seen. The rule declines to hide
        // these, so nothing should be deleted at all — the old renderer took the `)` and left
        // `look at this (` above the picture. Driving the deletion (rather than the rule) is what
        // makes this real: it fails if either side reaches for the untrimmed match again.
        val a = "https://e.test/a.png"
        for (body in listOf("look at this ($a)", "look at this [$a]", "he said \"check $a\"")) {
            assertEquals(
                body,
                stripped(body, hiding = PreviewHiding.hideableUrls(body, candidates = setOf(a))),
                "nothing was hideable, so nothing may be deleted: $body",
            )
        }
    }

    /** a spoilered copy of a hidden address keeps its characters */
    @Test
    fun spoilerIsNotShrunkByATwin() {
        // ⚠⚠ Hiding is decided by URL STRING, not by span identity — `PreviewHiding` says so and
        // declines to fix it, reasonably. The cost lands here: post the same image bare and again
        // inside a spoiler, and the bare one being hideable marked BOTH for deletion. The second
        // deletion takes characters out of a box the reader never opened.
        //
        // ⚠ `stripHiddenUrls` is given the spoiler ranges for exactly this. The delimiter branch
        // beside it has always guarded them; the hiding branch had not.
        val a = "https://e.test/a.png"
        val body = "$a then $a"
        val spoiler = TextRange.of(location = "$a then ".length, length = a.length)

        val out = TestBody(body)
        PreviewText.stripHiddenUrls(out, hidden = setOf(a), spoilered = listOf(spoiler))
        assertEquals("then $a", out.string, "the spoilered twin must survive intact")
    }

    /** the end-trim keeps whitespace that paints something */
    @Test
    fun inkedWhitespaceSurvivesTheTrim() {
        // A block of background-painted (or reversed) spaces beside a hidden link is part of the
        // picture — ASCII art is made of them — so it stops the trim the way a letter would.
        // Plain whitespace between it and the link still goes.
        val a = "https://e.test/a.png"
        val out = TestBody("  $a  ")
        out.append("   ", ink = true)
        out.append(" \n")
        out.insert("  ", ink = true, at = 0)
        PreviewText.stripHiddenUrls(out, hidden = setOf(a), spoilered = emptyList())
        assertEquals(
            " ".repeat(2 + 4 + 3), out.string,
            "both inked blocks and what lies between them, nothing outside",
        )

        val bare = TestBody(" $a ")
        bare.append(" ", ink = true)
        PreviewText.stripHiddenUrls(bare, hidden = setOf(a), spoilered = emptyList())
        assertEquals(" ", bare.string, "a lone inked space is still a body")
    }

    /** painted punctuation after a hidden address is not absorbed into it */
    @Test
    fun inkedPunctuationIsNotAbsorbed() {
        // The web's `withoutAbsorbedPunctuation` leaves a decorated segment alone: a reversed or
        // coloured row of dots beside the picture is art, not the sentence's full stop.
        val a = "https://e.test/a.png"
        val out = TestBody(a)
        out.append("...", ink = true)
        PreviewText.stripHiddenUrls(out, hidden = setOf(a), spoilered = emptyList())
        assertEquals("...", out.string)

        // Plain punctuation is still absorbed, up to the first painted character.
        val mixed = TestBody("$a.!")
        mixed.append("..", ink = true)
        PreviewText.stripHiddenUrls(mixed, hidden = setOf(a), spoilered = emptyList())
        assertEquals("..", mixed.string)
    }

    /** The body a reader is left with, after `hidden`'s addresses are taken out. */
    private fun stripped(body: String, hiding: Set<String>): String {
        val out = TestBody(IRCFormatting.parse(body).joinToString("") { it.text })
        PreviewText.stripHiddenUrls(out, hidden = hiding, spoilered = emptyList())
        return out.string
    }

    private fun stripped(body: String, hiding: String): String =
        stripped(body, hiding = setOf(hiding))
}

/**
 * Port-only: what stands in for `NSMutableAttributedString` in these tests — a body whose
 * every character carries one flag, `ink`, which moves with it when its neighbours are deleted.
 * The app's renderer supplies the real [AttributedBody].
 */
private class TestBody(string: String) : AttributedBody {
    private val text = StringBuilder(string)
    private val inked = MutableList(string.length) { false }

    override val string: String get() = text.toString()

    override fun deleteCharacters(range: TextRange) {
        // As strict as the type it stands in for, which raises on a range past the end.
        require(range.end <= text.length) { "$range is out of bounds of ${text.length}" }
        text.delete(range.start, range.end)
        inked.subList(range.start, range.end).clear()
    }

    override fun ink(index: Int): Boolean = inked[index]

    fun append(string: String, ink: Boolean = false) {
        insert(string, ink = ink, at = text.length)
    }

    fun insert(string: String, ink: Boolean, at: Int) {
        text.insert(at, string)
        inked.addAll(at, List(string.length) { ink })
    }
}
