// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.SpoilerMarkup
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.PreviewKind
import net.amiantos.lurkerkit.model.PreviewSelection
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.rendering.URLMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors `vue_client/src/utils/previewUrls.test.ts` case for case.
 *
 * Both clients ask the same server the same questions, and a message that sprouts two
 * previews on the web and three on the phone would be a bug nobody could explain. Porting
 * the reference's suite is how that stays true — the divergences between the clients are
 * deliberate and elsewhere (rendering, tap targets, playback), not here.
 *
 * Port note: a Swift Testing suite in LurkerKit ("PreviewSelection").
 */
class PreviewSelectionTests {

    private fun urls(text: String?, media: Boolean = true, pages: Boolean = true): List<String> =
        PreviewSelection.urls(text, inlineMedia = media, linkPreviews = pages)

    // MARK: - The toggles

    /** asks for nothing at all when both settings are off */
    @Test
    fun bothOff() {
        // The load-bearing property of default-off: no work, not even a request that gets
        // thrown away.
        assertTrue(
            urls("https://e.test/a.png and https://e.test/page", media = false, pages = false)
                .isEmpty()
        )
    }

    /** gates the two classes asymmetrically, because only one of them is knowable */
    @Test
    fun asymmetricGating() {
        // `looksLikeMedia` recognises MEDIA extensions and is false for everything else — a
        // page, a bare host, an extensionless image alike. So "this is media" is a verdict the
        // client can act on, and "this is not media" never is. Link-previews-off therefore drops
        // a .png outright, while inline-media-off cannot drop an unknown without breaking the
        // extensionless image case below.
        assertTrue(urls("https://e.test/a.png", media = false, pages = true).isEmpty())
        assertEquals(listOf("https://e.test/a.png"), urls("https://e.test/a.png", media = true, pages = false))
    }

    /** inline media still asks about an EXTENSIONLESS link, because it cannot tell */
    @Test
    fun extensionlessUnderMediaOnly() {
        // ⚠ The deliberate trade, and it costs a fetch: with only inline media on, an
        // extensionless URL is asked about even though most turn out to be pages. The
        // alternative is worse — imgur, twimg and every CDN serve images from extensionless
        // paths, so treating "no extension" as "definitely a page" made inline media
        // permanently unable to render the majority of real image links, and permanently is the
        // right word: priming is ingest-driven and never revisits a message. Bounded by the CARD
        // cap (3), not the media cap, so a link-heavy message can't become twenty speculative
        // fetches. A page that comes back is still not RENDERED — `isAllowed` re-checks the
        // server's answer.
        assertEquals(
            listOf("https://i.imgur.com/aBcDeF"),
            urls("https://i.imgur.com/aBcDeF", media = true, pages = false),
        )
        val many = (0 until 9).joinToString(" ") { "https://e.test/p$it" }
        assertEquals(PreviewSelection.maxCardsPerMessage, urls(many, media = true, pages = false).size)
    }

    /** link previews selects pages and ignores file links */
    @Test
    fun pagesOnly() {
        assertEquals(
            listOf("https://e.test/article"),
            urls("https://e.test/a.png https://e.test/article", media = false, pages = true),
        )
    }

    /** both on selects both */
    @Test
    fun bothOn() {
        assertEquals(
            listOf("https://e.test/a.png", "https://e.test/article"),
            urls("https://e.test/a.png https://e.test/article"),
        )
    }

    /** treats video and audio links as inline media, not as pages */
    @Test
    fun videoAndAudioAreMedia() {
        assertEquals(
            listOf("https://e.test/clip.mp4", "https://e.test/song.mp3"),
            urls("https://e.test/clip.mp4 https://e.test/song.mp3", media = true, pages = false),
        )
        assertTrue(urls("https://e.test/clip.mp4", media = false, pages = true).isEmpty())
    }

    // MARK: - What counts as a URL

    /** ignores bare www hosts, which are not fetchable as written */
    @Test
    fun ignoresBareWww() {
        assertTrue(urls("see www.example.com for more").isEmpty())
    }

    /** never resolves an email address */
    @Test
    fun ignoresEmail() {
        // The shared URL pattern matches these; resolving one would be both useless and a
        // small privacy insult.
        assertTrue(urls("mail me at bob@example.com").isEmpty())
        assertTrue(urls("mailto:bob@example.com").isEmpty())
    }

    /** strips trailing sentence punctuation */
    @Test
    fun stripsPunctuation() {
        assertEquals(listOf("https://e.test/page"), urls("go to https://e.test/page."))
        assertEquals(listOf("https://e.test/x"), urls("really? https://e.test/x!"))
        assertEquals(listOf("https://e.test/y"), urls("(https://e.test/y)"))
    }

    /** keeps a path that legitimately contains punctuation */
    @Test
    fun keepsInnerPunctuation() {
        assertEquals(listOf("https://e.test/a.b.c/d"), urls("https://e.test/a.b.c/d"))
    }

    /** ends a URL where the LINKIFIER ends it, brackets and all */
    @Test
    fun agreesWithTheLinkifier() {
        // ⚠ Two parsers disagreeing about where a URL stops is the bug. The tappable link is
        // built by `URLMatcher`'s balance-aware trimmer, so stripping ')' unconditionally here
        // resolved a DIFFERENT address than the one in the message: the real page 200s, the
        // clipped one 404s, and that 404 is cached for an hour under a string that appears
        // nowhere in the text. Same helper for both, so they cannot drift.
        val wiki = "https://en.wikipedia.org/wiki/Rust_(programming_language)"
        assertEquals(listOf(wiki), urls("see $wiki"))
        // ...while a URL merely wrapped in brackets still loses them.
        assertEquals(listOf("https://e.test/y"), urls("(https://e.test/y)"))
    }

    /** never resolves a link hidden behind a spoiler */
    @Test
    fun spoileredLinkIsNeverResolved() {
        // ⚠⚠ The renderer declines to linkify inside a spoiler run precisely so a link cannot
        // leak the hidden content. Resolving one anyway renders the target full-size as a
        // SIBLING of the click-to-reveal box — the spoiler is defeated by the preview, and for
        // an image the payload is on screen before anyone chooses to reveal it. Only inline
        // media need be on.
        val hidden = "\u000301,01https://secret.example/leak.png\u0003"
        assertTrue(urls(hidden).isEmpty())
        assertTrue(urls(hidden, media = true, pages = false).isEmpty())
        // A visible link in the same message is unaffected.
        assertEquals(listOf("https://e.test/fine.png"), urls("ok https://e.test/fine.png $hidden"))
        // A truecolour pair hides its text just the same.
        assertTrue(urls("\u0004112233,112233https://secret.example/leak.png\u0004").isEmpty())
    }

    /** still resolves a link in an unrenderable matched pair, which is not a spoiler */
    @Test
    fun unrenderableColourPairIsNotASpoiler() {
        // The other side of the same test: a run whose matched pair is a slot the palette can't
        // paint is NOT hidden, so its links are ordinary links.
        //
        // ⚠ Load-bearing rather than academic. `SpoilerMarkup` closes a spoiler with `\u000399,99`
        // when a digit follows it, so the tail of those messages IS a 99,99 run — and skipping it
        // here would silently drop the preview for any URL after such a spoiler. A missing
        // preview traced back to a colour code is not a debugging session anyone should have.
        assertEquals(listOf("https://e.test/fine.png"), urls("\u000399,99https://e.test/fine.png"))
        assertEquals(
            listOf("https://e.test/fine.png"),
            urls(SpoilerMarkup.apply("||x||5 then https://e.test/fine.png")),
        )
    }

    /** strips formatting codes out of the URL rather than resolving them */
    @Test
    fun formattingCodesAreNotPartOfTheAddress() {
        // A colour reset immediately after a link put \u0003 INSIDE the matched token, so the
        // resolver was handed an address with a control character on the end.
        assertEquals(listOf("https://e.test/red.png"), urls("\u000304https://e.test/red.png\u0003 done"))
    }

    /** resolves the URL the RENDERER linkifies, even when a code splits it */
    @Test
    fun agreesWithTheRendererOnASplitUrl() {
        // ⚠⚠ The two-parsers defect one level up from the trimmer. On iOS `MessageRenderer`
        // assembles its attributed string from runs and then linkifies the ASSEMBLED string,
        // while this scanned each run — and `IRCFormatting.parse` flushes a run at every control
        // code, so a code inside a URL split it for one and not the other.
        //
        // Left unfixed, the first case resolves a host that does not exist and negative-caches
        // that 404 for an hour under a string appearing nowhere in the message; the second
        // renders a live, tappable link that can never have a preview at all. Sharing the
        // PATTERN was not enough — they were being handed different input.
        val coloured = "http://ex\u00034ample.com/page"
        assertEquals(listOf("http://example.com/page"), urls(coloured))
        assertEquals(
            URLMatcher.matches(IRCFormatting.strip(coloured)).map { it.href }, urls(coloured),
            "selection and the linkifier must agree, address for address",
        )

        val bolded = "\u0002https://\u0002e.test/page"
        assertEquals(listOf("https://e.test/page"), urls(bolded))
        assertEquals(URLMatcher.matches(IRCFormatting.strip(bolded)).map { it.href }, urls(bolded))
    }

    /** a URL straddling the edge of a spoiler is not half-resolved */
    @Test
    fun partiallySpoileredUrlIsRefused() {
        // Scanning the assembled body means a match can now overlap a spoiler rather than sit
        // inside a run, so the test is an intersection. Resolving the visible half would be both
        // wrong and a leak — the hidden half is the part the author meant to hide.
        assertTrue(urls("https://e.test/\u000301,01secret\u0003").isEmpty())
    }

    // MARK: - <angle brackets> suppress a preview

    /** refuses to resolve a URL the author wrapped in brackets */
    @Test
    fun bracketsSuppress() {
        // RFC 3986 Appendix C's delimiter convention, borrowed from Discord as "link, but no
        // unfurl". It is the only per-link control there is — the two settings are
        // all-or-nothing — so a person sharing a URL they don't want unfolded has exactly this
        // and nothing else.
        assertTrue(urls("<https://e.test/a.png>").isEmpty())
        assertTrue(urls("see <https://e.test/article> for more").isEmpty())
    }

    /** leaves an unbracketed URL in the same message alone */
    @Test
    fun bracketsArePerOccurrence() {
        assertEquals(
            listOf("https://e.test/b.png"),
            urls("<https://e.test/a.png> https://e.test/b.png"),
        )
    }

    /** needs BOTH brackets, so a stray one is not a suppression */
    @Test
    fun halfOpenBracketIsNotTheConvention() {
        // A `<` in prose is ordinary. Treating a half-open bracket as the convention would
        // silently eat previews in messages that never asked for it.
        assertEquals(listOf("https://e.test/a.png"), urls("<https://e.test/a.png"))
        assertEquals(listOf("https://e.test/a.png"), urls("https://e.test/a.png>"))
    }

    /** recognises the brackets even when the URL ends in punctuation */
    @Test
    fun bracketsMeasureTheUntrimmedMatch() {
        // ⚠⚠ The end test measures from the UNTRIMMED match. `trimTrailingPunctuation` eats the
        // `.` here, so a check against the trimmed length lands on `.` instead of `>` and the
        // brackets stop working on exactly the URLs whose ends are ambiguous — which is the case
        // the convention exists for.
        assertTrue(urls("<https://e.test/wiki/Foo.>").isEmpty())
    }

    /** keeps a query string intact */
    @Test
    fun keepsQuery() {
        assertEquals(listOf("https://e.test/s?q=1&r=2"), urls("https://e.test/s?q=1&r=2"))
    }

    /** handles a message that is nothing but a URL */
    @Test
    fun bareUrl() {
        assertEquals(listOf("https://e.test/only"), urls("https://e.test/only"))
    }

    /** is fine with empty and nil text */
    @Test
    fun emptyInput() {
        assertTrue(urls("").isEmpty())
        assertTrue(urls(null).isEmpty())
    }

    // MARK: - Limits

    /** resolves a repeated link only once */
    @Test
    fun dedupes() {
        assertEquals(listOf("https://e.test/a"), urls("https://e.test/a https://e.test/a https://e.test/a"))
    }

    /** caps CARDS tightly, because each one costs vertical space */
    @Test
    fun capsCards() {
        val text = (0 until 12).joinToString(" ") { "https://e.test/$it" }
        assertEquals(PreviewSelection.maxCardsPerMessage, urls(text).size)
    }

    /** lets many images through, because a grid cell costs less than a card */
    @Test
    fun manyImages() {
        // Media renders as a two-column grid, so a fifth image adds half a row rather than a
        // whole picture — nothing like the vertical space a fourth card would want.
        val text = (0 until 12).joinToString(" ") { "https://e.test/$it.png" }
        assertEquals(12, urls(text).size)
    }

    /** still bounds media, so a spam message is not fifty outbound fetches */
    @Test
    fun mediaBounded() {
        val text = (0 until 40).joinToString(" ") { "https://e.test/$it.png" }
        assertEquals(PreviewSelection.maxMediaPerMessage, urls(text).size)
    }

    /** counts the two caps independently */
    @Test
    fun independentCaps() {
        // One class filling up must not consume the other's budget.
        val pages = (0 until 5).map { "https://e.test/page$it" }
        val images = (0 until 5).map { "https://e.test/img$it.png" }
        val got = urls((pages + images).joinToString(" "))
        assertEquals(5, got.count { it.endsWith(".png") })
        assertEquals(PreviewSelection.maxCardsPerMessage, got.count { !it.endsWith(".png") })
    }

    /** counts the cap after deduping, not before */
    @Test
    fun capCountsAfterDedupe() {
        // Four mentions of one link plus two others should yield three previews, not one —
        // otherwise a message quoting the same URL twice silently loses its other links.
        val text = "https://e.test/a https://e.test/a https://e.test/b https://e.test/c"
        assertEquals(listOf("https://e.test/a", "https://e.test/b", "https://e.test/c"), urls(text))
    }

    // Port-only: which addresses read as a file. LurkerKit asks Foundation for the path
    // (`URL(string:)?.path`) and this side reads it by hand, so the rules are pinned here. Every
    // answer is the Swift's own, taken from LurkerKit compiled on a Mac, and asked the only way
    // the question can be reached: with link previews on and inline media off, a URL is dropped
    // exactly when it reads as media.

    private fun readsAsMedia(url: String): Boolean {
        assertEquals(listOf(url), urls(url, media = true, pages = false), "the fixture is one whole URL")
        return urls(url, media = false, pages = true).isEmpty()
    }

    @Test
    fun testAPathIsReadTheWayFoundationReadsIt() {
        for (url in listOf(
            "https://e.test/a.png",
            "HTTPS://E.TEST/A.WEBM",
            // The query and the fragment are not the path…
            "https://e.test/a.PNG?x=1",
            "https://u:p@e.test/a.jpeg#frag",
            // …a file can be a directory along the way, and trailing slashes come off.
            "https://e.test/a.mp4/b",
            "https://e.test/a.png//x",
            "https://e.test/a.png/",
            "https://e.test/a.m4a/",
            // Dot segments are not resolved: this still names a `.png`.
            "https://e.test/a.png/../b",
            "https://e.test/a.png/./b",
            // A well-formed path is percent-decoded before it is judged, slashes included.
            "https://e.test/a%2Epng",
            "https://e.test/a.p%6Eg",
            "https://e.test/a.png%2F",
            "https://e.test/a.png%2f%2F",
            "https://e.test/%2e%2e/a.ogg",
            "https://e.test/%C3%A9.gif",
            // A path that is not well-formed is taken as written.
            "https://e.test/a%.png",
            "https://e.test/a{b.png",
            "https://e.test/\u00E9.png",
            // What Foundation makes a URL of, however little OkHttp would: no host, an empty or
            // an oversized port, anything at all in the userinfo, an escape in the host.
            "https:///a.png",
            "https://e.test:/a.bmp",
            "https://e.test:99999/a.png",
            "https://u\"@e.test/a.png",
            "https://e%41.test/a.wav",
            "https://[::1]:80/a.png",
        )) {
            assertTrue(readsAsMedia(url), url)
        }
    }

    @Test
    fun testWhatIsNotAPathIsNotAFile() {
        for (url in listOf(
            "https://e.test/apng",
            "https://e.test/a.png.txt",
            "https://e.test/a.png;x",
            // In the query, the fragment or the host, an extension names nothing.
            "https://e.test/x?y=a.png",
            "https://e.test/x#a.png",
            "https://e.test?a.png",
            "https://a.png",
            // ⚠ Taken as written means its well-formed escapes too: one character Foundation has
            // to encode and it encodes the whole path, `%` and all.
            "https://e.test/a%2Epng%",
            "https://e.test/a%2Epng/%zz",
            "https://e.test/a.p%6eg{",
            "https://e.test/\u00E9%2Epng",
            // Decoded bytes that are not UTF-8 leave no path at all, and a decoded NUL is still
            // a character after the extension.
            "https://e.test/%FF.png",
            "https://e.test/a.gif%00",
            // No URL at all to Foundation, so nothing to read a path from.
            "https://e.test:x/a.png",
            "https://e.test:80:1/a.bmp",
            "https://e\"x.test/a.png",
            "https://a@b\"c/a.png",
            "https://e{x/a.png",
            "https://e%4.test/a.wav",
            "https://e.test\\a.png",
            "https://[::1/a.png",
        )) {
            assertFalse(readsAsMedia(url), url)
        }
    }
}

/**
 * The PRIMING policy — which events the server may be asked to fetch on the reader's behalf.
 *
 * Mirrors `vue_client/src/utils/previewEvents.test.ts`. Both filters exist to stop an outbound
 * request being made for something nobody will ever see, and both were missing on the iOS
 * reference branch.
 *
 * Port note: a Swift Testing suite in LurkerKit ("PreviewSelection — priming policy").
 */
class PreviewPrimingTests {

    private fun message(
        text: String,
        type: EventType = EventType.Message,
        nick: String = "alice",
    ): Message =
        Message(id = 1, type = type, nick = nick, text = text, isSelf = false, userhost = "$nick!u@h")

    private fun urls(
        messages: List<Message>,
        networkId: Int? = 1,
        target: String = "#chan",
        ignores: IgnoreSet = IgnoreSet.empty,
    ): List<String> =
        PreviewSelection.urls(
            messages, networkId = networkId, target = target, ignores = ignores,
            inlineMedia = true, linkPreviews = true,
        )

    /** primes the kinds that can actually draw an attachment */
    @Test
    fun onlySpeech() {
        assertEquals(listOf("https://e.test/a"), urls(listOf(message("https://e.test/a"))))
        assertEquals(listOf("https://e.test/a"), urls(listOf(message("https://e.test/a", type = EventType.Action))))
    }

    /** never fetches a quit reason, a topic, or any other narration */
    @Test
    fun noNarration() {
        // ⚠ Part and quit publish their reason as `text`, and topic publishes the topic — so
        // joining a channel whose topic is a URL, or scrolling past
        // `Quit: HexChat https://hexchat.github.io`, made the server fetch a page no render path
        // can display. A history page ships the whole contiguous range with the noise included,
        // which is what made this fire in bulk rather than occasionally.
        for (type in listOf(
            EventType.Quit, EventType.Part, EventType.Join, EventType.Topic, EventType.Notice,
            EventType.System, EventType.Motd, EventType.Kick,
        )) {
            assertTrue(
                urls(listOf(message("https://e.test/a", type = type))).isEmpty(),
                "${type.rawValue} can't render an attachment, so it must not prime one",
            )
        }
    }

    /** ignoring somebody is a veto on the FETCH, not just on the row */
    @Test
    fun ignoredSenderIsNotPrimed() {
        // ⚠⚠ Ignores are applied when the store hands rows to the list, long after priming — so
        // this fetched every link posted by someone the user had explicitly silenced, and the
        // row was then dropped, which is precisely what kept it invisible.
        val ignores = IgnoreSet(global = listOf(IgnoreRule(mask = "spammer", levels = listOf("ALL"))))
        assertTrue(urls(listOf(message("https://e.test/a", nick = "spammer")), ignores = ignores).isEmpty())
        assertEquals(
            listOf("https://e.test/a"),
            urls(listOf(message("https://e.test/a", nick = "alice")), ignores = ignores),
        )
    }

    /** honours a rule scoped to one network, and leaves the others alone */
    @Test
    fun ignoreScopeIsRespected() {
        val ignores = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(mask = "bob", levels = listOf("ALL")))))
        val line = listOf(message("https://e.test/a", nick = "bob"))
        assertTrue(urls(line, networkId = 1, ignores = ignores).isEmpty())
        assertEquals(listOf("https://e.test/a"), urls(line, networkId = 2, ignores = ignores))
    }

    /** classifies a DM target by all four channel sigils, not just # */
    @Test
    fun dmDerivationUsesEverySigil() {
        // ⚠⚠ The one matcher input derived on the client rather than received on the wire, and
        // therefore the one place a client can disagree with the server about what a rule covers —
        // which iOS did, until lurker-ios#98. A PUBLIC-level rule covers `&local` because `&` is
        // a channel; reading it as a DM would leave the fetch un-vetoed here while the row
        // stayed hidden.
        val ignores = IgnoreSet(global = listOf(IgnoreRule(mask = "bob", levels = listOf("PUBLIC"))))
        val line = listOf(message("https://e.test/a", nick = "bob"))
        for (channel in listOf("#chan", "&local", "+modeless", "!12345chan")) {
            assertTrue(
                urls(line, target = channel, ignores = ignores).isEmpty(),
                "$channel is a channel, so a PUBLIC rule vetoes the fetch",
            )
        }
        // ...and a real DM is MSGS, which that rule does not cover.
        assertEquals(listOf("https://e.test/a"), urls(line, target = "bob", ignores = ignores))
    }

    /** asks for nothing when both settings are off, whatever the events say */
    @Test
    fun bothOffPrimesNothing() {
        assertTrue(
            PreviewSelection.urls(
                listOf(message("https://e.test/a.png")), networkId = 1, target = "#chan",
                ignores = IgnoreSet.empty, inlineMedia = false, linkPreviews = false,
            ).isEmpty()
        )
    }
}

/** Port note: a Swift Testing suite in LurkerKit ("LinkPreview gating"). */
class LinkPreviewGatingTests {

    private fun preview(kind: PreviewKind, status: LinkPreview.Status = LinkPreview.Status.Ok): LinkPreview =
        LinkPreview(url = "https://e.test/x", status = status, kind = kind)

    /** direct media follows the inline-media setting */
    @Test
    fun mediaFollowsMediaSetting() {
        for (kind in listOf(PreviewKind.Image, PreviewKind.Video, PreviewKind.Audio)) {
            assertTrue(preview(kind).isAllowed(inlineMedia = true, linkPreviews = false))
            assertFalse(preview(kind).isAllowed(inlineMedia = false, linkPreviews = true))
        }
    }

    /** pages follow the link-previews setting */
    @Test
    fun pagesFollowPageSetting() {
        for (kind in listOf(PreviewKind.Page, PreviewKind.VideoEmbed)) {
            assertTrue(preview(kind).isAllowed(inlineMedia = false, linkPreviews = true))
            assertFalse(preview(kind).isAllowed(inlineMedia = true, linkPreviews = false))
        }
    }

    /** an unavailable preview is never rendered, whatever the settings */
    @Test
    fun unavailableNeverRenders() {
        assertFalse(preview(PreviewKind.Image, status = LinkPreview.Status.Unavailable).isAllowed(inlineMedia = true, linkPreviews = true))
        assertFalse(preview(PreviewKind.Page, status = LinkPreview.Status.Unavailable).isAllowed(inlineMedia = true, linkPreviews = true))
    }

    /** the server's answer governs, not the extension that prompted the ask */
    @Test
    fun serverAnswerGoverns() {
        // Asked as a page because the URL had no extension; came back an image. With link
        // previews on and inline media OFF, that must NOT render — otherwise "no inline
        // media" could be talked into showing one.
        val surprise = LinkPreview(url = "https://e.test/no-extension", status = LinkPreview.Status.Ok, kind = PreviewKind.Image)
        assertFalse(surprise.isAllowed(inlineMedia = false, linkPreviews = true))
        assertTrue(surprise.isAllowed(inlineMedia = true, linkPreviews = false))
    }
}
