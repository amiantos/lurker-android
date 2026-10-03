// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.hexValue
import net.amiantos.lurkerkit.support.removingPercentEncoding

/**
 * Which URLs in a message body are worth asking the server about.
 *
 * The direct counterpart of the web client's `utils/previewUrls.ts`, deliberately kept in
 * step with it: both clients ask the same server the same questions, and a message that
 * sprouts two previews on the web and three on the phone would be a bug nobody could
 * explain. The port is of the *rule*, not the code — see `feedback_ios_not_bound_to_web`;
 * where a native client should diverge (rendering, tap targets, playback) it does, but "which
 * links count" is a question with one right answer.
 */
object PreviewSelection {

    /**
     * Cap on CARDS per message.
     *
     * Slack allows five, halloy defaults to one. Three is enough for a message genuinely
     * sharing a few links, and short of enough for one message to take over a screen. Each
     * card costs real vertical space, so this one stays tight.
     */
    const val maxCardsPerMessage = 3

    /**
     * Cap on MEDIA per message — deliberately generous.
     *
     * Media doesn't cost vertical space the way a card does: images render as a two-column
     * grid, so a fifth image adds half a row rather than a whole picture.
     *
     * A limit still exists, because a message carrying fifty image URLs is spam and each one
     * is an outbound fetch on the server's behalf. Set high enough not to bind on anything a
     * person would actually post. Matches the web client.
     *
     * ⚠ This is now the ONLY bound on how many pictures one message can draw. The grid has no
     * cell cap and no `+N` badge — see the mosaic section of `LINK_PREVIEWS_PR_PLAN.md` — so
     * lowering this is the only lever if a message ever does take over the screen.
     */
    const val maxMediaPerMessage = 20

    /**
     * Whether an event is a kind that can carry a preview at all.
     *
     * ⚠⚠ Read by BOTH the priming path and the render path, and that is the point of it being
     * here rather than expressed twice. On iOS, priming used to run over every event a frame
     * carried while only some rows could ever draw an attachment — and part/quit publish their
     * reason as `text`, topic publishes the topic, and a history page ships the whole contiguous
     * range with the noise included. So joining a channel whose topic is a URL, or scrolling past
     * `Quit: HexChat https://hexchat.github.io`, made the server fetch a page on the reader's
     * behalf that nothing would ever display.
     *
     * Speech minus `notice`: a notice is usually a service or a bot announcing something, and
     * unfurling ChanServ is not a feature anybody asked for. Matches the web client, which
     * mounts its attachments for `message` and `action` only.
     */
    fun isPreviewable(type: EventType): Boolean =
        type == EventType.Message || type == EventType.Action

    /**
     * Extensions that mean "this URL IS a file", governing which setting applies.
     *
     * ⚠ A HINT, not a verdict. The server answers authoritatively from `Content-Type`, and
     * `LinkPreview.isAllowed` re-checks that answer. Guessing wrong here costs one wasted
     * resolve, never a render the user switched off.
     */
    private val mediaExtensions: Set<String> = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp",
        "mp4", "mov", "m4v", "webm",
        "mp3", "m4a", "ogg", "oga", "wav", "flac",
    )

    /**
     * Port note: the path is read by [path], which answers as Foundation's `URL(string:)` and
     * `URL.path` do — see there. The two tests on it compare UTF-16 units, where Swift's
     * `hasSuffix` and `contains` compare grapheme clusters: an extension directly followed by a
     * combining mark inside the path (`/a.png` + U+0301 + `/b`) is media here and not on iOS,
     * as is one whose `.` has been swallowed into the cluster before it (`/a` + U+0600 +
     * `.png`).
     */
    private fun looksLikeMedia(url: String): Boolean {
        val parsed = path(url) ?: return false
        val path = parsed.lowercase()
        return mediaExtensions.any { path.endsWith(".$it") || path.contains(".$it/") }
    }

    /**
     * The URLs to resolve for a buffer's worth of events — the whole priming policy, in one
     * testable call.
     *
     * Its own function rather than a loop inside `ChatViewModel.primePreviews`, and for the
     * same reason the web keeps `previewEvents.ts` separate from its socket layer: this is pure
     * policy about what the server may be asked to fetch on the reader's behalf, and nothing in
     * the frame-routing layer is reachable from a test. What is left at the call site is a
     * `when` that names the frames priming listens to, which is plumbing.
     *
     * Two filters, and both of them stop the SERVER making an outbound request for something
     * nobody will ever see — see `isPreviewable` for the type half, and the ignore half below.
     */
    fun urls(
        messages: List<Message>,
        networkId: Int?,
        target: String,
        ignores: IgnoreSet,
        inlineMedia: Boolean,
        linkPreviews: Boolean,
    ): List<String> {
        if (!inlineMedia && !linkPreviews) return emptyList()
        val out = mutableListOf<String>()
        for (message in messages) {
            if (!isPreviewable(message.type)) continue

            // ⚠⚠ Ignoring somebody is a veto on the FETCH, not just on the row. Ignores are
            // client-side and applied when the store hands rows to the list, which is long after
            // priming runs — so on iOS this happily asked the server to go and fetch every link
            // posted by someone the user had explicitly silenced. The row was then dropped and
            // the preview never seen, which is exactly what kept it invisible.
            //
            // `isMessageHidden` rather than a hand-built matcher input: it already derives
            // DM-ness through `ChannelName.isChannelTarget` (all four sigils), and re-deriving
            // that is how the two tiers drifted apart the last time.
            if (ignores.isMessageHidden(networkId = networkId, message = message, target = target)) {
                continue
            }

            out.addAll(
                urls(message.text, inlineMedia = inlineMedia, linkPreviews = linkPreviews)
            )
        }
        return out
    }

    /**
     * The URLs to resolve for one message body.
     *
     * With both toggles off this returns empty without touching anything — that's what
     * makes the features genuinely free when disabled, rather than merely invisible.
     *
     * ⚠⚠ Reads `PreviewText.urlSpans`, which scans the ASSEMBLED body rather than each
     * formatting run — see that type for why scanning per run disagreed with the tappable link
     * the renderer produces. Spoilered and `<bracketed>` URLs are already excluded there.
     *
     * Port note: a repeated link is recognised by its UTF-16 units, where Swift's `Set<String>`
     * holds canonically equivalent strings as one: the same address written once precomposed
     * and once with combining marks is one URL on iOS and two here.
     */
    fun urls(text: String?, inlineMedia: Boolean, linkPreviews: Boolean): List<String> {
        if (!inlineMedia && !linkPreviews) return emptyList()
        if (text == null || text.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var mediaCount = 0
        var cardCount = 0

        for (span in PreviewText.urlSpans(text).spans) {
            val url = span.url
            if (seen.contains(url)) continue

            // ⚠⚠ A non-media URL is wanted when EITHER toggle is on, and that asymmetry is
            // load-bearing. `looksLikeMedia` is false both for "definitely a page" and for
            // "no extension to judge by", and requiring `linkPreviews` for the second case
            // meant an extensionless image host — imgur, twimg, the common case on IRC —
            // could never render for someone who enabled ONLY inline media. Permanently,
            // because priming is ingest-driven and nothing revisits a message. Unknowns are
            // charged to the CARD budget, which is the tight one, so honouring them can't
            // turn a link-heavy message into twenty speculative fetches.
            val isMedia = looksLikeMedia(url)
            if (!(if (isMedia) inlineMedia else (linkPreviews || inlineMedia))) continue
            // Counted separately: one class filling up must not consume the other's budget.
            if (!(if (isMedia) mediaCount < maxMediaPerMessage else cardCount < maxCardsPerMessage)) continue

            if (isMedia) mediaCount += 1 else cardCount += 1
            seen.add(url)
            out.add(url)
        }
        return out
    }

    /**
     * Port-only. The path of an http(s) address as Foundation reads it — `URL(string:)?.path` —
     * or null where `URL(string:)` gives no URL at all.
     *
     * Hand-rolled, like `ServerAddress`'s, because nothing on this side reads a path the way
     * Foundation does, and "is this a file" is asked of that path. `okhttp3.HttpUrl` resolves
     * `.` and `..` segments (`/a.png/..` is `/` to it and still names a `.png` to Foundation),
     * finds a host in `https:///a.png`, takes a backslash for a slash, refuses a port past
     * 65535, and decodes a malformed escape to U+FFFD where Foundation gives up on the whole
     * path. The rules below were enumerated from Foundation on a Mac (macOS 27), and
     * `PreviewSelectionTests` pins them:
     *
     * - The authority runs to the first `/`, `?` or `#`, and the path from there to the first
     *   `?` or `#`.
     * - No URL at all when the host — what follows the last `@`, up to a `:` — holds a control
     *   character, a space, or any of `" < > [ \ ] ^ ` { | }`, or a `%` that does not open an
     *   escape; when a bracketed host is not closed, or is followed by anything but a port; or
     *   when the port is not all digits. The userinfo may hold anything.
     * - A path made only of the characters RFC 3986 allows there (letters, digits,
     *   `-._~!$&'()*+,;=:@/` and well-formed `%XX` escapes) is percent-decoded, as UTF-8. Bytes
     *   that are not UTF-8 make the whole path empty.
     * - ⚠ A path holding anything else — a non-ASCII letter, a quote, a `%` that opens no
     *   escape — is taken exactly as written, its well-formed escapes included: Foundation
     *   percent-encodes such a path whole before reading it, `%` and all, so `/é%2Epng` does
     *   not end in `.png`.
     * - Trailing slashes come off, after the decoding (`/a.png%2F` is `/a.png`).
     *
     * Only ever handed an address `PreviewText.urlSpans` produced, which opens `http://` or
     * `https://`; nothing else has been checked against the Swift.
     *
     * One known difference: Foundation validates a non-ASCII host as an IDN and refuses some —
     * a disallowed character, or an empty label beside a non-ASCII one — which is then no URL
     * and so not media on iOS. Here it is a host like any other. Checked against the Swift over
     * a generated corpus of addresses, this and the two grapheme edges noted at `looksLikeMedia`
     * were the only disagreements about what is media.
     */
    private fun path(url: String): String? {
        val scheme = url.indexOf("://")
        if (scheme < 0) return null
        val authorityStart = scheme + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
        if (!isAuthority(url.substring(authorityStart, authorityEnd))) return null
        val pathEnd = url.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) url.length else it }
        val written = url.substring(authorityEnd, pathEnd)
        val path = if (isEncodedPath(written)) removingPercentEncoding(written) ?: "" else written
        if (path.length <= 1) return path
        return path.trimEnd('/').ifEmpty { "/" }
    }

    /** Port-only. Whether Foundation makes a URL of an address with this authority. */
    private fun isAuthority(authority: String): Boolean {
        val hostPort = authority.substringAfterLast('@')
        val port: String
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return false
            val after = hostPort.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return false
            port = after.removePrefix(":")
        } else {
            val colon = hostPort.indexOf(':')
            val host = if (colon < 0) hostPort else hostPort.substring(0, colon)
            port = if (colon < 0) "" else hostPort.substring(colon + 1)
            for ((index, character) in host.withIndex()) {
                if (character.code <= 0x20 || character.code == 0x7F || character in "\"<>[\\]^`{|}") return false
                if (character == '%' && !isEscape(host, index)) return false
            }
        }
        return port.all { it in '0'..'9' }
    }

    /**
     * Port-only. Whether a path is already valid as RFC 3986 writes one, which is when
     * Foundation percent-decodes it rather than taking it as written.
     */
    private fun isEncodedPath(path: String): Boolean {
        for ((index, character) in path.withIndex()) {
            if (character == '%') {
                if (!isEscape(path, index)) return false
            } else if (!(character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~!$&'()*+,;=:@/")) {
                return false
            }
        }
        return true
    }

    /** Whether the `%` at `index` opens a `%XX` escape. */
    private fun isEscape(text: String, index: Int): Boolean =
        index + 2 < text.length && hexValue(text[index + 1]) >= 0 && hexValue(text[index + 2]) >= 0
}
