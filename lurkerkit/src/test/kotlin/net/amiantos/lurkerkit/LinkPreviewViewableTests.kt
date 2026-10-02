// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the media viewer will agree to present.
 *
 * ⚠⚠ These exist because the failure they guard against is SILENT. When the server stopped
 * minting `src` for video and audio, a flat `src != nil` admission test dropped every clip out
 * of the gallery on iOS — the tap fell through to its "nothing to present" branch and opened
 * Safari. No crash, no error, nothing in a log: the player, its scrubbing, PiP and AirPlay were
 * all still compiled in and simply stopped being reachable. A feature can be deleted by a
 * predicate.
 */
class LinkPreviewViewableTests {
    private fun preview(
        kind: PreviewKind,
        url: String = "https://cdn.example.com/a.mp4",
        src: String? = null,
        thumb: String? = null,
    ): LinkPreview =
        LinkPreview(url = url, status = LinkPreview.Status.Ok, kind = kind, src = src, thumb = thumb)

    // MARK: - Video and audio stream from the origin, so an address is all they need

    @Test
    fun testVideoIsViewableWithNoSrcAtAll() {
        assertTrue(preview(kind = PreviewKind.Video).isViewable)
    }

    @Test
    fun testAudioIsViewableWithNoSrcAtAll() {
        assertTrue(preview(kind = PreviewKind.Audio, url = "https://cdn.example.com/a.mp3").isViewable)
    }

    /**
     * ⚠⚠ The line is PUBLIC cleartext, not cleartext. This test used to admit both, justified by
     * "a self-hosted instance on a LAN is not required to be https" — true of the `.local` host
     * it named, which `NSAllowsLocalNetworking` permits on iOS, and not true of the public
     * address it also let through, which App Transport Security refuses inside AVFoundation.
     * Admitting that one bought a failure the reader can't act on in place of the browser
     * hand-off, which works. Both halves are asserted here so neither can drift back.
     */
    @Test
    fun testHttpIsViewableOnlyWhereTheAppCanActuallyLoadIt() {
        assertTrue(preview(kind = PreviewKind.Video, url = "http://box.local/a.mp4").isViewable)
        assertTrue(preview(kind = PreviewKind.Video, url = "http://192.168.1.9:8080/a.mp4").isViewable)
        assertFalse(preview(kind = PreviewKind.Video, url = "http://cdn.example.com/a.mp4").isViewable)
    }

    @Test
    fun testSchemeMatchIsCaseInsensitive() {
        assertTrue(preview(kind = PreviewKind.Video, url = "HTTPS://cdn.example.com/a.mp4").isViewable)
    }

    /**
     * ⚠ The one that matters for safety. A non-http scheme reaching the player is an address
     * we never vetted pointing at something that is not a media fetch.
     */
    @Test
    fun testNonHttpSchemesAreRefused() {
        for (url in listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:video/mp4;base64,AAAA",
            "ftp://example.com/a.mp4",
        )) {
            assertFalse(preview(kind = PreviewKind.Video, url = url).isViewable, "should refuse $url")
        }
    }

    @Test
    fun testUnparseableUrlIsRefused() {
        assertFalse(preview(kind = PreviewKind.Video, url = "").isViewable)
    }

    /**
     * ⚠ A stale `src` must not change the answer. Video is admitted on its ADDRESS, and the
     * player deliberately ignores `src` even when a descriptor minted before the server change
     * still carries one — that token now answers 404, so honouring it would be the branch that
     * reliably fails.
     */
    @Test
    fun testVideoWithLegacySrcIsStillAdmittedOnItsOrigin() {
        assertTrue(preview(kind = PreviewKind.Video, src = "/api/link-preview/media/stale").isViewable)
    }

    // MARK: - Images still come from our proxy, so they still need bytes

    @Test
    fun testImageNeedsSrc() {
        assertFalse(preview(kind = PreviewKind.Image, url = "https://cdn.example.com/a.png").isViewable)
        assertTrue(
            preview(kind = PreviewKind.Image, url = "https://cdn.example.com/a.png", src = "/api/x").isViewable,
        )
    }

    // MARK: - Cards are not viewer pages

    @Test
    fun testPagesAreNeverViewable() {
        for (kind in listOf(PreviewKind.Page, PreviewKind.VideoEmbed)) {
            assertFalse(preview(kind = kind, url = "https://example.com/post").isViewable)
            assertFalse(preview(kind = kind, src = "/api/x").isViewable)
        }
    }

    // MARK: - What a row DRAWS, which is a different question from what the viewer plays

    /**
     * The whole point of the poster: a clip stops being a grey box in the timeline. This is the
     * same shape of predicate as `isViewable` above, so it gets the same guard — the failure
     * mode is once again silent, a picture that simply never appears.
     */
    @Test
    fun testVideoDrawsItsPoster() {
        assertEquals(
            "/api/link-preview/poster/tok",
            preview(kind = PreviewKind.Video, thumb = "/api/link-preview/poster/tok").inlinePicture,
        )
    }

    @Test
    fun testAudioDrawsItsPoster() {
        assertEquals(
            "/api/p",
            preview(kind = PreviewKind.Audio, url = "https://cdn.example.com/a.mp3", thumb = "/api/p").inlinePicture,
        )
    }

    /**
     * A posterless clip has nothing to draw and says so, rather than reaching for the field that
     * happens to be populated. The card falls back to its glyph.
     */
    @Test
    fun testPosterlessClipDrawsNothing() {
        assertNull(preview(kind = PreviewKind.Video).inlinePicture)
    }

    /**
     * ⚠⚠ The stale-`src` trap again, on the drawing side this time. A descriptor minted before
     * the server stopped relaying video can still be in a running client's store; drawing from
     * its token means a 404 and a permanently-empty box, so the clip branch must never look at
     * `src` — not even when there is no poster and it is the only thing there.
     */
    @Test
    fun testClipNeverDrawsFromALegacySrc() {
        assertNull(preview(kind = PreviewKind.Video, src = "/api/link-preview/media/stale").inlinePicture)
    }

    @Test
    fun testImageDrawsFromItsProxiedBytes() {
        assertEquals(
            "/api/x",
            preview(
                kind = PreviewKind.Image, url = "https://cdn.example.com/a.png", src = "/api/x",
                thumb = "/api/never",
            ).inlinePicture,
        )
    }

    /**
     * ⚠ A card draws its picture itself, beside its text and with its own shape rule, so it is
     * not one of these. This is the test that stops the property being read as "any picture".
     */
    @Test
    fun testACardsThumbnailIsNotAnInlinePicture() {
        for (kind in listOf(PreviewKind.Page, PreviewKind.VideoEmbed)) {
            assertNull(
                preview(kind = kind, url = "https://example.com/post", thumb = "/api/x").inlinePicture,
                "a ${kind.rawValue} card draws its own thumbnail",
            )
        }
    }

    // MARK: - Whether the thing on screen IS what the address points to

    /**
     * ⚠⚠ The invariant: nothing is hidden without something rendered in its place. Every case
     * below is a message whose text would silently lose a URL if this answered wrong.
     */
    @Test
    fun testAPosterStandsInForItsVideo() {
        assertTrue(preview(kind = PreviewKind.Video, thumb = "/api/link-preview/poster/tok").standsInForItsURL)
    }

    @Test
    fun testAPosterlessClipStandsInForNothing() {
        assertFalse(preview(kind = PreviewKind.Video).standsInForItsURL)
        assertFalse(preview(kind = PreviewKind.Video, src = "/api/link-preview/media/stale").standsInForItsURL)
    }

    /**
     * ⚠⚠ Cover art is a picture ABOUT the track, not the track — the one place this parts
     * company with `inlinePicture`, which happily draws it. Hiding an mp3's address left album
     * art and a waveform glyph, with no filename and nothing to copy.
     */
    @Test
    fun testCoverArtDoesNotStandInForItsAudio() {
        val mp3 = preview(kind = PreviewKind.Audio, url = "https://cdn.example.com/a.mp3", thumb = "/api/p")
        assertFalse(mp3.standsInForItsURL)
        assertEquals("/api/p", mp3.inlinePicture, "it still DRAWS the art")
    }

    @Test
    fun testAnImageStandsInForItselfOnlyOnceItHasBytes() {
        val url = "https://cdn.example.com/a.png"
        assertFalse(preview(kind = PreviewKind.Image, url = url).standsInForItsURL)
        assertTrue(preview(kind = PreviewKind.Image, url = url, src = "/api/x").standsInForItsURL)
    }

    @Test
    fun testACardNeverTakesItsLinkAway() {
        for (kind in listOf(PreviewKind.Page, PreviewKind.VideoEmbed)) {
            assertFalse(
                preview(kind = kind, url = "https://example.com/post", src = "/api/x", thumb = "/api/y")
                    .standsInForItsURL,
                "a ${kind.rawValue} card keeps its URL",
            )
        }
    }

    // Port-only: where `okhttp3.HttpUrl` and Foundation's `URL(string:)` read an address
    // differently, and so where this answer and LurkerKit's part company. The iOS answer in
    // each comment is the Swift's own, taken by running LurkerKit over the same addresses.

    private fun viewable(url: String): Boolean = preview(kind = PreviewKind.Video, url = url).isViewable

    @Test
    fun testOrdinaryAddressesAgreeWithLurkerKit() {
        // The cases that are not edges: every one of these is the Swift's answer too.
        for (url in listOf(
            "https://cdn.example.com/a.mp4", "hTtPs://cdn.example.com/a.mp4", "https://example.com:8443",
            "https://user:pw@example.com/a", "https://example.com/a?b=c#d", "https://[::1]/a",
            "https://cdn.example.com/a b.mp4", "https://例え.jp/a",
            "http://localhost/a.mp4", "http://LOCALHOST/a", "http://localhost:8010/uploads/a.mp4",
            "http://127.0.0.1/a", "http://10.0.0.5/a", "http://172.16.0.1/a", "http://172.31.255.255/a",
            "http://169.254.1.1/a", "http://192.168.1.9/a.mp4?x=1#t=10", "http://box/a", "http://box:8080",
            "HTTP://BOX.LOCAL/a", "http://user:pw@box.local/a", "http://[::1]/a", "http://[::1]:80/a",
            "http://[fd00::1]:8080/a", "http://[FE80::1]/a", "http://bücher.local/a",
        )) {
            assertTrue(viewable(url), "should admit $url")
        }
        for (url in listOf(
            "http://cdn.example.com/a.mp4", "http://1.2.3.4/a", "http://172.32.0.1/a", "http://box.lan/a",
            "http://localhost.evil.com/a", "http://a.local.com/a", "http://box.local@evil.com/a",
            "http://evil.com/.local", "http://evil.com#.local", "http://evil.com?.local",
            "http://[2001:db8::1]/a", "http://[::ffff:8.8.8.8]/a", "http://256.1.1.1/a", "http://例え.jp/a",
            "//box.local/a", "box.local/a", "http//box.local", "wss://example.com/a",
            "https://exa mple.com/", "https ://example.com", "https://example.com:abc/a", "https://[::1",
        )) {
            assertFalse(viewable(url), "should refuse $url")
        }
    }

    @Test
    fun testHttpsWithNothingToDialIsRefused() {
        // iOS: viewable, every one — its test is the scheme alone. Here the address also has to
        // be one OkHttp could load, so these go to the browser hand-off instead of to a player
        // that cannot open them.
        for (url in listOf(
            "https:", "https://", "HTTPS:", "https:///", "https://?", "https://#", "https://@/a",
            "https://:443/a", "https://host:99999/a", "https://example.com:65536/a",
            "https://example.com:0/a", "https://a%20b/", "https://[zz]/", "https://../",
        )) {
            assertFalse(viewable(url), "should refuse $url")
        }
        // iOS: viewable. Local hosts OkHttp cannot dial as written: a bad port, an empty label,
        // an IPv6 zone id, brackets around something that is not IPv6.
        for (url in listOf(
            "http://box.local:99999/a", "http://box.local:0/a", "http://.local/a", "http://a..local/a",
            "http://[fe80::1%25en0]/a", "http://[fe80::1%en0]/a", "http://[box.local]/a",
            "http://[192.168.1.1]/a",
        )) {
            assertFalse(viewable(url), "should refuse $url")
        }
    }

    @Test
    fun testALooselyWrittenLocalAddressIsAdmitted() {
        // iOS: refused, every one — Foundation finds no scheme behind leading whitespace, and no
        // host where the slashes are missing, extra or backwards. `HttpUrl` reads through all of
        // it to the same local host.
        for (url in listOf(
            " http://box.local/a.mp4", "\thttp://box.local/a.mp4", " https://cdn.example.com/a.mp4",
            "http:/box/a.mp4", "http:box.local", "http:///box/a", "http:////box/a",
            "http:\\\\box.local\\a", "http:\\/box/a",
        )) {
            assertTrue(viewable(url), "should admit $url")
        }
        // iOS: refused — it compares the host's spelling, and these are loopback and a private
        // IPv4 address written the long way. `HttpUrl` hands `LocalNetworking` the canonical
        // form (`::1`, `192.168.1.1`).
        assertTrue(viewable("http://[0:0:0:0:0:0:0:1]/a"))
        assertTrue(viewable("http://[::ffff:192.168.1.1]/a"))
    }

    @Test
    fun testABackslashEndsTheHost() {
        // ⚠ The two parsers find DIFFERENT hosts here. To `HttpUrl` a backslash is a slash, so
        // the host is what stands before it; to Foundation it is part of the userinfo, so the
        // host is what follows the `@`. Each judges the host it found — which is only safe if
        // the address then DIALLED is the same parse, not the raw string.
        // iOS: refused (it sees `evil.com`).
        assertTrue(viewable("http://box.local\\@evil.com/a"))
        assertTrue(viewable("http://192.168.1.1\\@evil.com/"))
        // iOS: viewable (it sees `192.168.1.1`).
        assertFalse(viewable("http://evil.com\\@192.168.1.1/"))
    }
}
