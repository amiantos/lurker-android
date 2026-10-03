// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurkerkit.client.LinkPreviewStore
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MediaFetch
import net.amiantos.lurkerkit.model.PreviewHiding
import net.amiantos.lurkerkit.model.PreviewSelection
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * Which of the two preview features a screen draws, or null when it draws neither — lurker-ios's
 * `ChatViewController.previewContext` gate.
 *
 * Null rather than a value reporting both off, so the default case — both settings off — can't reach
 * any of the preview machinery at all, not even to be told no.
 */
data class PreviewToggles(val inlineMedia: Boolean, val linkPreviews: Boolean) {
    companion object {
        const val INLINE_MEDIA_KEY = "chat.inline_media.enabled"
        const val LINK_PREVIEWS_KEY = "chat.link_previews.enabled"

        /**
         * ⚠⚠ ANDed with the instance feature flag, exactly as the kit's priming is. These settings are
         * server-side and NOT device-split, so a `true` stored against an instance that has
         * `LURKER_LINK_PREVIEWS` on travels to one that doesn't — where the routes aren't even mounted
         * and the settings rows are hidden. Without the same test here the render path and the fetch
         * path disagree about whether the feature exists.
         *
         * Both default OFF (lurker memory: link previews are off by default).
         */
        fun resolve(instanceHasPreviews: Boolean, settings: Settings): PreviewToggles? {
            if (!instanceHasPreviews) return null
            val inlineMedia = settings.bool(INLINE_MEDIA_KEY, default = false)
            val linkPreviews = settings.bool(LINK_PREVIEWS_KEY, default = false)
            if (!inlineMedia && !linkPreviews) return null
            return PreviewToggles(inlineMedia, linkPreviews)
        }
    }
}

/**
 * What a row's previews mean for it: which addresses the body drops, and what to draw. lurker-ios's
 * `MessageListRenderer.PreviewPlan`.
 *
 * ⚠ Computed ONCE per row, and both halves come from it. The text losing a URL and the picture
 * appearing are the same event, so deriving them from two passes is the defect class this feature
 * keeps producing.
 */
data class PreviewPlan(val hidden: Set<String>, val resolved: List<LinkPreview>) {
    companion object {
        val None = PreviewPlan(emptySet(), emptyList())

        /**
         * The plan for [message], reading the store through [allSettled] and [preview].
         *
         * ⚠ Reads ONLY — this never asks for a preview. Requesting is done at message ingest (the kit's
         * `ChatViewModel.primePreviews`), so by the time a row is drawn its preview is usually already
         * known and its height is right on first measure. Asking from here is what made scrolling into
         * history grow rows under the reader on iOS.
         */
        fun of(
            message: Message,
            toggles: PreviewToggles,
            allSettled: (List<String>) -> Boolean,
            preview: (String) -> LinkPreview?,
        ): PreviewPlan {
            if (!PreviewSelection.isPreviewable(message.type)) return None
            val urls = PreviewSelection.urls(message.text, inlineMedia = toggles.inlineMedia, linkPreviews = toggles.linkPreviews)
            // ⚠⚠ ATOMIC REVEAL, and it gates BOTH halves. A message shows none of its attachments until
            // every URL in it has settled, because no layout may depend on when a sibling resolves —
            // three images with one already cached painted as a lone picture and then re-arranged.
            // ⚠⚠ ASKED, never timed, and the store's `allSettled` fails open in every branch.
            if (urls.isEmpty() || !allSettled(urls)) return None

            // Re-checked against the SERVER's answer, not the extension guess that prompted the
            // request: an extensionless URL that turns out to be a PNG is inline media, and a `.jpg`
            // that redirects to an HTML login page is not.
            val resolved = urls.mapNotNull(preview).filter {
                it.isAllowed(inlineMedia = toggles.inlineMedia, linkPreviews = toggles.linkPreviews)
            }

            // ⚠ Only MEDIA may take its address away — a card keeps its URL, whose heading is different
            // text from the address, and the address is what you copy. ⚠⚠ And only media that IS what
            // the address points to (`standsInForItsURL`, not the kind): nothing may be hidden without
            // something rendered in its place. Decided from the DESCRIPTOR, knowingly: deciding it from
            // whether the bytes arrived would make the message's TEXT depend on when a download
            // finished, re-flowing the row mid-scroll — the late growth the atomic reveal exists to kill.
            val media = resolved.filter { it.standsInForItsURL }.map { it.url }.toSet()
            val hidden = if (media.isEmpty()) emptySet() else PreviewHiding.hideableUrls(message.text, candidates = media)
            return PreviewPlan(hidden, resolved)
        }

        /**
         * Whether [message] mentions any of [urls] — the test that decides whether a batch of
         * resolutions has anything to do with what's on screen. Reads the addresses the same way the
         * plan does, through `PreviewSelection`, so the two can't disagree about what a row mentions.
         */
        fun mentionsAny(message: Message, urls: Set<String>, toggles: PreviewToggles): Boolean {
            if (urls.isEmpty() || !PreviewSelection.isPreviewable(message.type)) return false
            return PreviewSelection.urls(message.text, inlineMedia = toggles.inlineMedia, linkPreviews = toggles.linkPreviews)
                .any { it in urls }
        }
    }
}

/**
 * Where preview bytes come from: the server's proxy for a picture (`ChatViewModel.proxiedMedia`), and
 * an address the player can open for a clip (`ChatViewModel.playableMediaURL`). Functions rather than
 * the view model itself, so a `@Preview` can draw the boxes with nothing behind them.
 *
 * ⚠⚠ The media policy in two lines: proxy the INVOLUNTARY, not the deliberate. Everything drawn inline
 * — a still, a poster, a card's thumbnail — is a server-minted proxy path, so rendering it unasked
 * tells no stranger's host anything. A clip's bytes stream from its origin, and only on a tap.
 */
class MediaSource(
    val fetch: suspend (path: String) -> MediaFetch,
    val playable: suspend (path: String, mime: String?) -> String?,
) {
    companion object {
        fun of(model: ChatViewModel): MediaSource =
            MediaSource(fetch = { model.proxiedMedia(it) }, playable = { path, mime -> model.playableMediaURL(path, mime) })

        /** Nothing behind it — every fetch is refused. For previews and for screens without a model. */
        val None = MediaSource(fetch = { MediaFetch.Permanent }, playable = { _, _ -> null })
    }
}

/**
 * What a row needs to draw link previews — lurker-ios's `PreviewContext`. Bundled so
 * `MessageListContext` grows by one nullable field rather than four; null on the screens that don't
 * show previews (the feeds), and whenever both settings are off.
 *
 * @param revision bumped by the screen when preview state moved for something it shows, so rows
 *   re-plan — the store itself isn't observable state.
 */
class PreviewContext(
    val store: LinkPreviewStore,
    val media: MediaSource,
    val toggles: PreviewToggles,
    val revision: Int,
) {
    fun plan(message: Message): PreviewPlan =
        PreviewPlan.of(message, toggles, allSettled = store::allSettled, preview = store::preview)
}
