// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import java.time.Instant

/**
 * What a resolved URL turned out to be.
 *
 * Decided by the *server*, from the response's `Content-Type` — never from the file
 * extension. The extension only ever decides which of the two settings would cover a URL,
 * and therefore whether to bother asking; see `PreviewSelection`.
 */
@Serializable
enum class PreviewKind(val rawValue: String) {
    @SerialName("image")
    Image("image"),

    @SerialName("video")
    Video("video"),

    @SerialName("audio")
    Audio("audio"),

    @SerialName("page")
    Page("page"),

    /** A page we know how to build a privacy-preserving player URL for (YouTube, Vimeo). */
    @SerialName("video-embed")
    VideoEmbed("video-embed");

    /**
     * Whether this kind is governed by `chat.inline_media.enabled` rather than
     * `chat.link_previews.enabled`.
     */
    val isDirectMedia: Boolean
        get() = when (this) {
            Image, Video, Audio -> true
            Page, VideoEmbed -> false
        }

    companion object {
        fun fromRawValue(raw: String): PreviewKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The server's answer about one URL.
 *
 * Byte URLs (`src`, `thumb`) are *paths on our own server*, minted and signed by it. The
 * client never constructs one, and never contacts the origin — that's the whole privacy
 * property of the feature, and it's why these are opaque strings rather than the original
 * URL plus a rule for building a proxy path.
 *
 * Port note: `Codable` in LurkerKit, with the keys and the required/optional split the
 * compiler synthesises there: `url`, `status` and `kind` must be present and readable, and
 * every other field may be absent or null. ⚠ Decode it with a `Json` that has
 * `ignoreUnknownKeys = true` — `JSONDecoder` ignores keys it does not know, and the default
 * `Json` throws on one, which would turn every field a newer server adds into a descriptor
 * that never renders.
 *
 * Port note: the two decoders read a number differently at the edges, which only `thumbWidth`
 * and `thumbHeight` can meet (checked against the Swift). Stricter here: a whole number written
 * with a fraction (`1200.0`, `1.2e3`) decodes on iOS and throws here, as does one past 32 bits.
 * Looser here: a quoted number (`"1200"`) throws on iOS and decodes here. The server's
 * `JSON.stringify` writes none of those shapes for a pixel size. Everything else — a missing or
 * null required field, a string where a number belongs or the reverse, an unknown `kind` or
 * `status` — throws on both.
 */
@Serializable
data class LinkPreview(
    val url: String,
    val status: Status,
    val kind: PreviewKind,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    val author: String? = null,
    /** Proxy path for direct media — the content itself. */
    val src: String? = null,
    /** Proxy path for a card thumbnail — decoration on a page. */
    val thumb: String? = null,
    val thumbWidth: Int? = null,
    val thumbHeight: Int? = null,
    /**
     * Player URL for `videoEmbed`, already privacy-scoped by the server
     * (`youtube-nocookie.com`). Only ever opened on an explicit tap.
     */
    val embedUrl: String? = null,
    val mime: String? = null,
    /**
     * When the server's answer stops being the answer, ISO-8601 as it arrives on the wire.
     *
     * ⚠⚠ This is how the server says "come back" — and it went unread for the whole life of the
     * reference branch. Some failures are deliberately NOT cached: pool saturation, a resolve
     * deadline, the instance simply being busy. Those get a ~15 second `expiresAt` instead of
     * the one-hour failure TTL, precisely so a client asks again. Ignoring the field made a
     * momentary hiccup indistinguishable from a dead link, and the row stayed blank for the
     * life of the app session — the exact outcome the server-side transient/verdict split was
     * built to avoid.
     *
     * ⚠ Kept as the raw string rather than an `Instant` so the model stays a faithful decode
     * of the frame; `expiry` is the parsed view.
     */
    val expiresAt: String? = null,
) {
    @Serializable
    enum class Status(val rawValue: String) {
        @SerialName("ok")
        Ok("ok"),

        /**
         * A real, cacheable answer: dead link, timeout, refused by the SSRF guard, blocked
         * by the origin, or a page with nothing worth showing. Never rendered.
         */
        @SerialName("unavailable")
        Unavailable("unavailable");

        companion object {
            fun fromRawValue(raw: String): Status? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /**
     * `expiresAt` as an `Instant`, or null if absent or unparseable.
     *
     * ⚠ Unparseable reads as "no expiry stated", which means the answer is treated as a verdict
     * and never re-asked. That is the safe direction: the alternative — treating a timestamp we
     * could not read as already lapsed — turns one bad field into an unbounded re-ask loop.
     */
    val expiry: Instant? get() = ISOTime.parse(expiresAt)

    /**
     * Whether this preview may be rendered under the current settings.
     *
     * Re-checked against the server's answer rather than trusting the guess that prompted
     * the request. The two can disagree — an extensionless URL that turns out to be a PNG,
     * a `.jpg` that redirects to an HTML login page — and when they do, the setting that
     * governs is the one covering what the thing actually *is*. Otherwise "link previews
     * off" could still be talked into drawing a card.
     */
    fun isAllowed(inlineMedia: Boolean, linkPreviews: Boolean): Boolean {
        if (status != Status.Ok) return false
        return if (kind.isDirectMedia) inlineMedia else linkPreviews
    }

    // Port note: the three properties below are an `extension LinkPreview` in LurkerKit, written
    // above the struct. Members here, so a caller needs no import to reach them.

    /**
     * Whether the media viewer can present this, and it answers per KIND because the two get
     * their bytes from different places.
     *
     * An image is drawn from bytes our own proxy serves, so it needs a `src`. Video and audio
     * are STREAMED FROM THE ORIGIN and have no `src` at all — the server stopped minting one,
     * because a card that renders by itself must not report the reader to a stranger's host,
     * while pressing play is a deliberate act that an address could not be hidden from anyway.
     * So what those need is an address the player can open. A page has nothing to show either
     * way.
     *
     * ⚠⚠ This replaces a flat `src != nil` test, which was correct while everything came from
     * the proxy and became a silent feature deletion on iOS the moment the server changed:
     * every clip fell out of the gallery, the tap took its "nothing to present" branch, and the
     * reader was handed to Safari. The player and its scrubbing, PiP and AirPlay were all still
     * there and simply stopped being reachable — no crash, no error, nothing to notice in a log.
     *
     * Port note: the address is read with `okhttp3.HttpUrl` where LurkerKit reads it with
     * Foundation's `URL(string:)`. `HttpUrl` knows no scheme but http(s), which is the refusal
     * this wants anyway, and on every well-formed address the two agree. At the edges they do
     * not parse alike (checked against the Swift; `LinkPreviewViewableTests` pins these):
     *
     * - STRICTER here: iOS admits anything whose scheme reads `https`, host or no host. This
     *   refuses what OkHttp could not load — no host at all (`https:`, `https://`), a port of 0
     *   or past 65535, an empty label in the host (`a..local`), a zone id in an IPv6 literal
     *   (`[fe80::1%25en0]`).
     * - LOOSER here: `HttpUrl` trims leading whitespace and finds a host where the slashes after
     *   the scheme are missing, extra or backwards (`http:/box/a.mp4`, `http:box.local`,
     *   `http:\\box\a`), all of which Foundation reads as having no host. It also writes an
     *   IPv6 host in canonical form before `LocalNetworking` sees it, so `[0:0:0:0:0:0:0:1]` is
     *   loopback here and an unrecognised spelling on iOS.
     * - ⚠ DIFFERENT HOST: a backslash ends the authority for `HttpUrl` and is part of the
     *   userinfo for Foundation, so `http://box.local\@evil.example/` is host `box.local` here
     *   and `evil.example` there (and the reverse spelling flips both). Whichever host `HttpUrl`
     *   finds is the one judged, so what is handed to the player must be this same parse
     *   (`HttpUrl.toString()`), never the raw string — or the host judged and the host dialled
     *   can differ.
     */
    val isViewable: Boolean
        get() {
            when (kind) {
                PreviewKind.Image ->
                    return src != null
                PreviewKind.Video, PreviewKind.Audio -> {
                    // ⚠⚠ The test is what this app can actually LOAD, not what the player would
                    // accept. On iOS, App Transport Security blocks cleartext to a public host
                    // regardless, so admitting one promised a player and delivered a failure
                    // deep inside AVFoundation — a dead end the reader can't act on, and one
                    // that reads as a broken app rather than as a policy. Refused here, it
                    // falls through to the browser hand-off, which works. The alternative there
                    // was `NSAllowsArbitraryLoadsForMedia`, which buys one rare clip by
                    // weakening every load the app makes.
                    //
                    // ⚠⚠ But only the loads ATS really refuses. The iOS app's `Info.plist` sets
                    // `NSAllowsLocalNetworking`, so cleartext to the local network is permitted
                    // — and a self-hosted instance on a plain-http LAN posts its OWN uploads as
                    // `http://box.local/…`, which played in the viewer before and still should.
                    // See `LocalNetworking`, which mirrors that key so the refusal here and the
                    // app's actual capability can't drift apart.
                    val address = url.toHttpUrlOrNull() ?: return false
                    // Port note: `HttpUrl.scheme` is already lowercase, and is only ever one
                    // of these two.
                    val scheme = address.scheme
                    if (scheme == "https") return true
                    if (scheme != "http") return false
                    return LocalNetworking.permitsCleartext(host = address.host)
                }
                PreviewKind.Page, PreviewKind.VideoEmbed ->
                    return false
            }
        }

    /**
     * The picture drawn for this preview inline in the timeline, or null for a box with none.
     *
     * It comes from a different field per kind, and the split is the media policy in one line.
     * An image IS its bytes, which our own proxy serves as `src`. A clip's bytes are never
     * relayed — what it gets is `thumb`: a POSTER this instance decoded from a couple of ranges
     * of the file, so it exists on our server and asking for it tells the origin nothing. Both
     * are therefore safe to render unasked, which is the test every auto-rendering preview image
     * has to pass; the clip's actual bytes never are, and are fetched only on a deliberate tap.
     *
     * ⚠⚠ `src` IS NEVER USED FOR A CLIP, even when one is present — the same trap the iOS
     * app's `MediaPlayerPageCell` documents. A descriptor minted before the server stopped
     * relaying video can still be sitting in a running client's store, and its token now
     * answers 404, so the defensive-looking "prefer src if we have it" spelling is the one that
     * reliably fails.
     *
     * ⚠ A CARD'S PICTURE IS NOT ONE OF THESE, though a page's `thumb` is a perfectly real image.
     * A card is a note ABOUT something and draws its picture as decoration beside its text —
     * on iOS `MessageAttachmentsView.cardView` reads `thumb` itself, and has its own chip/hero
     * rule for what shape to give it.
     */
    val inlinePicture: String?
        get() = when (kind) {
            PreviewKind.Image -> src
            PreviewKind.Video, PreviewKind.Audio -> thumb
            PreviewKind.Page, PreviewKind.VideoEmbed -> null
        }

    /**
     * Whether what is on screen IS the thing linked, so the address may be dropped from the
     * message body and the box may take the picture's own shape.
     *
     * A stricter question than `inlinePicture`, and the gap between them is entirely audio.
     * Hiding a URL is only honest when the reader is looking at what the address points to: an
     * image IS the message, and a video's poster is a frame OF the video, so in both cases the
     * address is a machine-readable duplicate of something already on screen.
     *
     * ⚠⚠ AUDIO IS NOT. Its "poster" is the same `-frames:v 1` decode landing on the file's
     * attached COVER ART — a picture about the track rather than the track, which is the
     * definition of a card's thumbnail and not of inline media. Taking the address away left a
     * square of album art and a waveform glyph with no filename, nothing to copy, and nothing on
     * screen that is the thing linked. It also has no business dictating the box's shape: the
     * case for shaping is that phone video is portrait and a landscape letterbox destroys it,
     * while cover art is square and shaping only turns a flat band into a cropped slab.
     *
     * ⚠ This is where iOS parts company with the web client's `rendersInline`, which draws the
     * line one kind further out and hides an mp3's address too. Deliberate, and one kind wide.
     */
    val standsInForItsURL: Boolean
        get() = when (kind) {
            PreviewKind.Image -> src != null
            PreviewKind.Video -> thumb != null
            PreviewKind.Audio, PreviewKind.Page, PreviewKind.VideoEmbed -> false
        }
}

/**
 * What came back from the byte proxy — and crucially, whether it is worth asking again.
 *
 * ⚠⚠ Three cases rather than a nullable `ByteString`, because a caller that caches "this
 * failed" has to know which failures are verdicts. The proxy maps a transient origin refusal to
 * 503 + `Retry-After` and keeps 404 for a refused content type, precisely so a client can tell
 * them apart; folding them together turns a minute of upstream throttling into images that stay
 * blank for the rest of the session with no way to repair them.
 */
sealed interface MediaFetch {
    data class Success(val data: ByteString) : MediaFetch

    /** Worth another go later — a throttled origin, a 5xx, a dropped connection. */
    data object Retryable : MediaFetch

    /** A real answer: gone, refused, or something we will never be able to draw. */
    data object Permanent : MediaFetch
}
