// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.rendering.URLMatcher
import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.isInWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.substring
import kotlin.math.max
import kotlin.math.min

/**
 * The rendered body `PreviewText.stripHiddenUrls` edits: the assembled text of one message, with
 * whatever styling the renderer has laid over it.
 *
 * Port note: LurkerKit takes an `NSMutableAttributedString` there, and marks painted text with
 * an attribute key of its own (`NSAttributedString.Key.ink`, `"chat.lurker.ink"`). This module
 * cannot depend on Android, so the three things the function asks of that string are an
 * interface here, implemented in `:app` over whatever the renderer assembles. ⚠ The contract
 * the implementation must keep is the one an attributed string keeps for free: the styling of
 * the text that survives a deletion moves with it, so [ink] always answers for the text as it
 * stands NOW.
 */
interface AttributedBody {
    /** The assembled text as it stands now, deletions so far included. */
    val string: String

    /** Take `range` — UTF-16 offsets into [string] — out of the body. */
    fun deleteCharacters(range: TextRange)

    /**
     * Whether the character at `index` is marked as text that paints something even where it is
     * only whitespace — a background (which includes a spoiler's box and every reversed run), an
     * underline, a strike. Stamped by the renderer and honoured by `PreviewText.stripHiddenUrls`,
     * whose end-trim must not eat it: a run of background-painted spaces beside a hidden link is
     * part of the picture (ASCII art is made of them), not padding. The web's `isTrimmableText`
     * is the same rule.
     */
    fun ink(index: Int): Boolean
}

/**
 * Where the URLs in a message body are — the single scan both preview modules read.
 *
 * ⚠⚠ **Matched over the ASSEMBLED body, never per formatting run**, and that is the whole
 * reason this type exists. On iOS `MessageRenderer` builds its attributed string run by run and
 * then linkifies `attributed.string` — the assembled text — so a run-by-run scan here disagrees
 * with the tappable link the reader actually gets, because `IRCFormatting.parse` flushes a run at
 * every control code and a code inside a URL therefore splits it:
 *
 *     "http://ex\u00034ample.com/page"   per run → "http://ex"   assembled → the real address
 *     "\u0002https://\u0002e.test/page"   per run → nothing        assembled → a live link
 *
 * The first sends the resolver a host that does not exist, and negative-caches the 404 for an
 * hour under a string appearing nowhere in the message; the second renders a link with no
 * preview and, once the views land, would look up hideable URLs by strings the renderer's own
 * link ranges do not contain.
 *
 * This is the two-parsers defect one level up from the one `URLMatcher.rawRanges` fixed: that
 * round shared the PATTERN between the selector and the linkifier, and it was not enough,
 * because they were still being handed different INPUT. Sharing the pattern is not sharing the
 * parse.
 *
 * ⚠ The web client does not have this bug and its structure is not the fix to copy: its
 * renderer splits per run too, so its selector and renderer agree by construction. iOS
 * assembles first, so iOS has to scan what it assembled.
 */
object PreviewText {

    /** A URL and where it sits in what the reader actually sees. */
    data class UrlSpan(
        /** The address, trailing punctuation trimmed — what gets resolved. */
        val url: String,
        /** UTF-16 offset of the match in the visible body. */
        val start: Int,
        /**
         * UTF-16 offset of the end of `url` PLUS the sentence punctuation that reads as
         * belonging to it — see `PreviewText.absorbing(range, text)`.
         *
         * ⚠⚠ Not `start + url.length`. Measuring to the end of the trimmed address leaves
         * the punctuation the trimmer just discarded sitting between the span and the end of the
         * message, so `look at this https://e.test/a.png.` failed the "nothing but whitespace
         * after it" test and kept its address, while the same URL at the FRONT hid — the
         * leading check being vacuously true at offset zero. Identical punctuation, opposite
         * verdicts, decided by which end the URL sat at.
         *
         * ⚠⚠ And not the end of the raw MATCH either, which is where the first version of this
         * went. The trimmer also discards a closing delimiter whose partner sits BEFORE the
         * address, so absorbing everything it dropped made `look at this (https://e.test/a.png)`
         * hideable — and the renderer, deleting the same span, left a line holding a lone `(`
         * above the picture. The same orphan this rule exists to remove, moved to the other end.
         */
        val end: Int,
    )

    /**
     * What [urlSpans] answers: the visible body, and every resolvable URL in it.
     *
     * Port note: the Swift returns a named tuple, `(visible: String, spans: [UrlSpan])`.
     */
    data class UrlSpans(val visible: String, val spans: List<UrlSpan>)

    /**
     * The visible body — formatting codes removed, spoiler text kept — and every resolvable
     * URL in it.
     *
     * ⚠⚠ Spoiler runs contribute their TEXT but none of their URLs, and both halves matter. A
     * hidden link must never be resolved (unfurling one renders the target full-size beside the
     * reveal box, defeating the spoiler); and the run's characters still occupy the line, so a
     * URL following a spoiler is not at the start of the message and must not be peeled as
     * though it were. Carried as RANGES rather than by skipping runs, because the scan now runs
     * over the assembled text and no longer knows where a run ended.
     */
    fun urlSpans(text: String): UrlSpans {
        val visible = StringBuilder()
        val spoilers = mutableListOf<TextRange>()
        for (run in IRCFormatting.parse(text)) {
            val base = visible.length
            visible.append(run.text)
            if (run.hidesText) {
                spoilers.add(TextRange.of(location = base, length = run.text.length))
            }
        }

        val body = visible.toString()
        val out = mutableListOf<UrlSpan>()
        for (range in URLMatcher.rawRanges(body)) {
            // Any overlap at all disqualifies it. A URL straddling the edge of a spoiler is
            // partly hidden, and resolving the visible half is both wrong and a leak.
            if (spoilers.any { intersects(it, range) }) continue

            val raw = body.substring(range)
            // The shared pattern also matches bare `www.` hosts and email addresses. Neither is
            // fetchable as written, and we are emphatically not resolving somebody's email.
            //
            // Port note: a prefix test by UTF-16 unit, where Swift's `hasPrefix` compares
            // grapheme clusters. They differ only for a match whose scheme is directly followed
            // by a combining mark (`http://` + U+0301 …): Swift does not see the prefix there
            // and skips the URL, where this resolves it.
            val lower = raw.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) continue

            // ⚠ `<https://example.com>` is the author saying "link, but don't unfurl it" — the
            // only per-link control there is, since the two settings are all-or-nothing.
            if (URLMatcher.isBracketedUrl(body, range)) continue

            val url = URLMatcher.trimTrailingPunctuation(raw)
            if (url.isEmpty()) continue
            val trimmed = TextRange.of(location = range.start, length = url.length)
            out.add(
                UrlSpan(
                    url = url, start = range.start,
                    end = absorbing(trimmed, body).end,
                )
            )
        }
        return UrlSpans(body, out)
    }

    /**
     * The punctuation a URL may absorb.
     *
     * ⚠⚠ Narrower than `URLMatcher.trimTrailingPunctuation`'s class, deliberately, and the two
     * characters left out are the whole point: `'` and `"` are PAIRED, as are the brackets that
     * function handles by balance. A closing delimiter has a partner sitting before the address,
     * so absorbing it deletes half a pair and strands the other half — `he said "check <url>"`
     * became `he said "check`. Sentence punctuation has no partner, so taking it with the
     * address is safe and reads correctly: a message ending `…shot.png.` ends with the picture.
     *
     * The cost of the narrowness is that a wrapped URL is simply not hideable — its address
     * stays on screen beside its own preview, which is redundant rather than broken. That is the
     * right way to be wrong here.
     *
     * ⚠ `…` is here for the same reason it is in `URLMatcher.trimTrailingPunctuation`'s set, and
     * the two must move together: that one decides where the address ends, this one decides what
     * the address may take with it. A character dropped there and not deletable here is left
     * orphaned on screen — which is the whole of lurker-ios#126, in the other direction.
     */
    val absorbable: Set<Char> = setOf('.', ',', ';', ':', '!', '?', '…')

    /**
     * `range` — a URL's TRIMMED span in `text` — extended across the sentence punctuation that
     * reads as part of the address.
     *
     * ⚠⚠ The one definition, called by both sides on purpose. `UrlSpan.end` counts this
     * punctuation as part of the address when it decides whether the URL sits against an edge,
     * and the renderer deletes what that decision measured. Two answers here means one of
     * them orphans something: measure wide and delete narrow and the full stop is left behind
     * (`look at this .`); measure narrow and delete wide and the peel never fires while the
     * deletion eats a character it was not licensed to.
     *
     * ⚠ Measured on the ASSEMBLED body, not on the regex match, and that closes a case the match
     * cannot see. `look at this \u0002https://e.test/a.png\u0002.` puts the full stop outside the
     * match — a common bot-output shape — and adjacency in the assembled text is adjacency ON
     * SCREEN, which is the domain this rule is about. (iOS gets this for free where the web had
     * to reach for it: `urlSpans` already scans what `MessageRenderer` assembled.)
     */
    fun absorbing(range: TextRange, text: String): TextRange {
        var end = range.end
        while (end < text.length && text[end] in absorbable) {
            end += 1
        }
        return TextRange.of(location = range.start, length = end - range.start)
    }

    /**
     * Take the hidden URLs' addresses out of a rendered body, and close up the gap they leave.
     *
     * Moved out of `MessageRenderer` on iOS so it can be tested: the renderer is in the app
     * target, which has no test bundle, and the range it chooses to delete IS the defect class
     * here (lurker-ios#126, and the orphaned full stop before it). A property test that only
     * re-derives `UrlSpan.end` from `absorbing` cannot see a renderer that reaches for a
     * different range — which is exactly what both bugs were.
     *
     * ⚠⚠ Back to front, one pass. The caller's own bookkeeping (colour runs, spoiler ranges,
     * link ranges) is plain arrays of offsets into the assembled string and does NOT move when
     * characters do, so deleting from the front silently mis-styles everything after it. The
     * attributed body's own styling survives a deletion; the arrays beside it do not.
     *
     * Two jobs:
     *
     * 1. `<https://example.com>` renders WITHOUT its brackets — RFC 3986 Appendix C's delimiter
     *    convention, which Discord borrowed as "link, but no unfurl". `PreviewSelection` already
     *    declines to resolve one; this is the other half, and without it the convention is
     *    visible punctuation that appears to do nothing. ⚠ This runs for EVERY message, not only
     *    previewed ones — the two settings are off by default and this is the app-wide
     *    linkifier. Deliberate, and it matches the web.
     *
     * 2. Addresses whose picture is about to stand in for them come out entirely.
     */
    fun stripHiddenUrls(
        attributed: AttributedBody,
        hidden: Set<String>,
        spoilered: List<TextRange>,
    ) {
        // ⚠ One snapshot, taken before the first deletion, and every range below is an offset
        // into it — the matches already are, and the absorption has to be too. Asking the live
        // string mid-loop measures against a document the earlier (higher-offset) deletions have
        // already shortened, which is not the one `urlSpans` judged.
        val source = attributed.string
        for (match in URLMatcher.matches(source).reversed()) {
            val inSpoiler = spoilered.any { intersects(it, match.range) }
            if (match.href in hidden) {
                // ⚠⚠ Never inside a spoiler, even though the address matches. Hiding is decided
                // by URL STRING, not by span identity (see `PreviewHiding`), so a message posting
                // the same image bare at one end and again inside a spoiler marks BOTH — and
                // deleting the second one takes characters out of a box the reader never revealed,
                // shrinking it to fit a secret it no longer holds. A spoilered URL is never
                // resolved and so never has a picture standing in for it; there is nothing there
                // to be redundant with. The delimiter branch below has always guarded this.
                if (inSpoiler) continue
                // ⚠⚠ Exactly what the hiding rule MEASURED — `absorbing`, the same call
                // `UrlSpan.end` makes — and neither of the two obvious ranges beside it.
                //
                // Deleting the trimmed `match.range` orphans the punctuation the span counted as
                // part of the address: `look at this https://e.test/a.png.` became `look at this
                // .`, and a message that was ONLY `https://e.test/shot.png.` collapsed to a body
                // of one full stop — not empty, so the end-trim below (whitespace only) left it
                // and a line holding a lone `.` was painted above the picture.
                //
                // Deleting the untrimmed match is the same orphan at the other end. The trimmer
                // discards a closing delimiter whose partner sits in the PROSE, so taking the
                // whole match ate the `)` and left `look at this (` (lurker-ios#126). The span
                // stops at anything paired, so such a URL is not hideable and never reaches this
                // line — but only while both sides ask the same function.
                //
                // ⚠ …short of any of that punctuation which is `ink`: a painted run of dots is
                // part of the picture, not the sentence's full stop — the web's
                // `withoutAbsorbedPunctuation` leaves a decorated segment alone for the same
                // reason. Read off the live string, which is safe here: the deletions so far all
                // sit at higher offsets than this match's absorbed tail.
                val delimiters = match.delimiters
                if (delimiters != null) {
                    attributed.deleteCharacters(delimiters)
                    continue
                }
                val absorbed = absorbing(match.range, source)
                var end = match.range.end
                while (end < absorbed.end && !attributed.ink(end)) {
                    end += 1
                }
                attributed.deleteCharacters(
                    TextRange.of(location = match.range.start, length = end - match.range.start)
                )
                continue
            }
            val delimiters = match.delimiters
            if (delimiters == null || inSpoiler) continue
            attributed.deleteCharacters(
                TextRange.of(location = delimiters.start + delimiters.length - 1, length = 1)
            )
            attributed.deleteCharacters(TextRange.of(location = delimiters.start, length = 1))
        }
        // Trim the ends, which is what makes a body that lost a URL read as a sentence rather
        // than as one with a hole in it: dropping the address from "look at this: <url>" leaves
        // a colon and a trailing space, and dropping it from a message that WAS only a link
        // leaves pure whitespace, which still paints a blank line above the picture.
        //
        // ⚠ Whitespace that is `ink` is not padding, and stops the trim like a letter would.
        if (hidden.isEmpty()) return
        val text = attributed.string
        val whole = TextRange.of(location = 0, length = text.length)
        val firstText = text.indexOfFirst { !it.isInWhitespacesAndNewlines() }
        val lastText = text.indexOfLast { !it.isInWhitespacesAndNewlines() }
        var head = if (firstText == -1) text.length else firstText
        var tail = if (lastText == -1) 0 else lastText + 1
        // Port note: asked per character, where the Swift enumerates the attribute's runs.
        // Either way `head` is pulled back to the first inked character and `tail` pushed out
        // past the last.
        for (index in whole.start until whole.end) {
            if (!attributed.ink(index)) continue
            head = min(head, index)
            tail = max(tail, index + 1)
        }
        if (head >= tail) {
            attributed.deleteCharacters(whole)
            return
        }
        if (tail < text.length) {
            attributed.deleteCharacters(TextRange.of(location = tail, length = text.length - tail))
        }
        if (head > 0) {
            attributed.deleteCharacters(TextRange.of(location = 0, length = head))
        }
    }

    /** `NSIntersectionRange(a, b).length > 0`: whether the two ranges share any character. */
    private fun intersects(a: TextRange, b: TextRange): Boolean =
        max(a.start, b.start) < min(a.end, b.end)
}
