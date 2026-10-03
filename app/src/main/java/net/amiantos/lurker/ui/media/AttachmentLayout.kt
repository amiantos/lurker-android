// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The decisions behind a message's attachment block — which pictures form the mosaic, how tall each
 * box is before a byte has arrived, which shape a card's picture takes, what TalkBack hears, and
 * where a tap goes — decided without drawing anything, so they can be tested. The drawing is
 * `MessageAttachments`; this is the half of lurker-ios's `MessageAttachmentsView` that isn't views.
 *
 * Slack's treatment rather than Discord's — a thin accent rule and restrained text, not a large
 * colourful engagement card. The message list is a dense log, and a preview should read as a guest
 * in it.
 *
 * **Fixed heights, deliberately.** Every attachment has a height known the moment the *metadata*
 * arrives, before any image bytes do: a lone image's box is shaped from the server's dimensions and
 * clamped, a mosaic is a whole number of fixed rows, a card's chip is a fixed square and its hero
 * band a fixed ratio, a video facade is 16:9. That is a real constraint on the design, and it buys
 * the thing that matters most in a scrolling list: an image finishing its download never changes a
 * row's height, so the content under the reader's thumb never jumps. Reflowing on image load is the
 * standard way this feature ships badly — and in a reverse-layout list a late growth moves every row
 * above it.
 *
 * The row still changes height once, when the metadata lands and an attachment appears at all —
 * `LinkPreviewStore.onUpdate`, which the conversation turns into a recomposition of what's on screen.
 */
object AttachmentLayout {

    /** Gap between stacked attachments (iOS's stack spacing). */
    const val SPACING = 6f

    /**
     * Full-width media with nothing to shape it by. Tall enough to be worth showing, short enough
     * that one screenshot doesn't evict the conversation from the screen.
     */
    const val MEDIA_HEIGHT = 200f

    /**
     * Bounds on a lone image's box, which is shaped by the server's dimensions rather than fixed — a
     * portrait screenshot cropped into a landscape letterbox is unreadable.
     */
    const val LONE_MIN_HEIGHT = 120f
    const val LONE_MAX_HEIGHT = 280f

    /** A card's chip-shaped picture. */
    const val THUMB_SIDE = 64f
    const val CORNER = 8f
    const val CHIP_CORNER = 4f

    /**
     * One mosaic row. Every group is a whole number of these, so a message's attachment block has a
     * height known from the COUNT alone — nothing here reads an image's dimensions.
     */
    const val MOSAIC_ROW_HEIGHT = 160f
    const val MOSAIC_GAP = 4f

    /** The three-up block: two rows and the gap between them. */
    const val MOSAIC_THREE_UP_HEIGHT = MOSAIC_ROW_HEIGHT * 2 + MOSAIC_GAP

    /**
     * The band a hero card's picture sits in, as a constant.
     *
     * ⚠⚠ 1200/630 is a CONVENTION, and this is deliberately not computed from the declared pair.
     * Those are numbers a stranger asserted about a file we have not measured; sizing the box from
     * them puts row height at the mercy of someone else's markup, and measuring the bytes instead
     * would make layout depend on the download. The declared shape only picks BETWEEN layouts, so a
     * wrong pick is a letterboxed logo rather than a broken row.
     */
    const val HERO_ASPECT = 1200f / 630f

    /** A video facade's picture. */
    const val VIDEO_ASPECT = 16f / 9f

    /**
     * Below this ratio a card's picture takes the 64dp chip instead of the hero band.
     *
     * ⚠⚠ Measured against live markup, twice. reddit (256x256) and Ars Technica (512x512) are LOGOS
     * and want the chip; Wikipedia (869x1200) is portrait and a hero band would crop its subject out;
     * GitHub (1200x600) and most editorial sites want the band. The threshold sits just under 4:3, so
     * a conventional photo is a hero and anything approaching square is not.
     *
     * ⚠⚠ HERO IS THE DEFAULT when nothing is declared — that population is mostly editorial. The cost
     * is a letterboxed logo, which aspect-fit makes survivable.
     *
     * ⚠⚠ `twitter:card` is NOT consulted and must not be. It is stated INTENT and it disagrees with
     * the author's own picture in exactly the direction that produces the bad crop: Ars Technica
     * declares `summary_large_image` beside that 512x512 logo.
     */
    const val CHIP_MAX_RATIO = 1.3f

    /**
     * One piece of a message's attachment block, in the order it's drawn. [key] names it by the
     * addresses it draws, so a block re-planned onto different previews is a different element.
     */
    sealed interface Block {
        val key: String

        /** Two or more images as a grid. There's only ever one per message. */
        data class Mosaic(val rows: List<MosaicRow>) : Block {
            override val key: String get() = "mosaic"
        }

        /** An image, a clip or a track, alone and full width. */
        data class Media(val preview: LinkPreview) : Block {
            override val key: String get() = "media ${preview.url}"
        }

        /** A page or a video page: text, with its picture beside or under it. */
        data class Card(val preview: LinkPreview) : Block {
            override val key: String get() = "card ${preview.url}"
        }
    }

    /** One row of the mosaic, keyed by the addresses in it. */
    sealed interface MosaicRow {
        val key: String

        /** A full-height picture beside two stacked ones — Discord's arrangement for three. */
        data class ThreeUp(val tall: LinkPreview, val top: LinkPreview, val bottom: LinkPreview) : MosaicRow {
            override val key: String get() = "${tall.url} ${top.url} ${bottom.url}"
        }

        data class Pair(val left: LinkPreview, val right: LinkPreview) : MosaicRow {
            override val key: String get() = "${left.url} ${right.url}"
        }
    }

    /**
     * What a message's previews draw, in order: the mosaic first when there are two or more images,
     * then everything else as it was posted.
     *
     * ⚠ IMAGES only go in the mosaic. A player cropped into a grid cell is not a player — its controls
     * are the part that gets cut — so video and audio stack full width, where their transport has room.
     */
    fun blocks(previews: List<LinkPreview>): List<Block> {
        if (previews.isEmpty()) return emptyList()
        val images = previews.filter { it.kind == PreviewKind.Image }
        val out = mutableListOf<Block>()
        val inMosaic = if (images.size > 1) {
            out += Block.Mosaic(mosaicRows(images))
            images.map { it.url }.toSet()
        } else {
            emptySet()
        }
        for (preview in previews) {
            if (preview.url in inMosaic) continue
            out += when (preview.kind) {
                PreviewKind.Image, PreviewKind.Video, PreviewKind.Audio -> Block.Media(preview)
                PreviewKind.Page, PreviewKind.VideoEmbed -> Block.Card(preview)
            }
        }
        return out
    }

    /**
     * Two or more images as a two-column grid.
     *
     * ⚠⚠ Not a horizontally-scrolling filmstrip, and the reason is the interaction rather than the
     * look: a sideways scroller inside a vertically-scrolling list is a gesture conflict, a diagonal
     * drag has to be arbitrated, and whichever way it goes is the way the reader didn't mean.
     *
     * The shape rule, for any count: **even → all pairs; odd → the three-up block first, then pairs.**
     * So 3 is a full-height picture beside two stacked, 4 is 2x2, 5 is the three-up plus a pair. One
     * rule, no holes at any count, and the odd block LEADS — a trailing full-width tile crops a
     * landscape photo hard and makes the last picture the subject of the message.
     *
     * ⚠⚠ Uncapped, with no `+N` badge: `PreviewSelection.maxMediaPerMessage` already bounds how much
     * one message can take over, and every picture is reachable uncropped from the viewer.
     */
    fun mosaicRows(images: List<LinkPreview>): List<MosaicRow> {
        val rows = mutableListOf<MosaicRow>()
        var index = 0
        if (images.size % 2 != 0 && images.size >= 3) {
            rows += MosaicRow.ThreeUp(images[0], images[1], images[2])
            index = 3
        }
        // Whatever the three-up block didn't take is even by construction.
        while (index + 1 < images.size) {
            rows += MosaicRow.Pair(images[index], images[index + 1])
            index += 2
        }
        return rows
    }

    /** A mosaic's height in dp, from its rows alone. */
    fun mosaicHeight(rows: List<MosaicRow>): Float {
        if (rows.isEmpty()) return 0f
        val sum = rows.sumOf {
            when (it) {
                is MosaicRow.ThreeUp -> MOSAIC_THREE_UP_HEIGHT.toDouble()
                is MosaicRow.Pair -> MOSAIC_ROW_HEIGHT.toDouble()
            }
        }
        return (sum + MOSAIC_GAP * (rows.size - 1)).toFloat()
    }

    /**
     * A lone media box's height in dp at [width] dp — known before the bytes, always.
     *
     * The height comes from the server's dimensions or from a fixed fallback — never from the image,
     * which would mean every picture growing its row at decode time, under the reader's thumb. A lone
     * image is shaped rather than fixed because it is the one case where a flat height is actively
     * wrong: a portrait screenshot cropped into a landscape letterbox is unreadable.
     *
     * ⚠ A VIDEO POSTER is shaped by the same rule, and the case for it is stronger: the declared pair
     * describes the frame the server decoded, and phone video is overwhelmingly portrait. A clip with
     * NO poster keeps the fixed height — the server withholds the dimensions and the picture together.
     *
     * ⚠⚠ NOT AUDIO, whose picture is cover art rather than a frame of the content. Album art is square,
     * so shaping would only turn a flat band into a 280dp slab that is STILL cropped. See
     * `LinkPreview.standsInForItsURL` — the same line, drawn once, for this and the message's text.
     */
    fun mediaHeight(preview: LinkPreview, width: Float): Float {
        val w = preview.thumbWidth
        val h = preview.thumbHeight
        if (preview.kind == PreviewKind.Audio || w == null || h == null || w <= 0 || h <= 0 || width <= 0f) {
            return MEDIA_HEIGHT
        }
        val ratio = w.toFloat() / h.toFloat()
        return (width / ratio).coerceIn(LONE_MIN_HEIGHT, LONE_MAX_HEIGHT)
    }

    /** Which shape a card's picture takes — or that it has none. See [CHIP_MAX_RATIO]. */
    enum class CardShape { None, Chip, Hero }

    fun cardShape(preview: LinkPreview): CardShape {
        if (preview.thumb == null) return CardShape.None
        val w = preview.thumbWidth
        val h = preview.thumbHeight
        if (w == null || h == null || w <= 0 || h <= 0) return CardShape.Hero
        return if (w.toFloat() / h.toFloat() < CHIP_MAX_RATIO) CardShape.Chip else CardShape.Hero
    }

    /**
     * Whether a card's picture goes UNDER its text, full width — a hero band, or a video facade —
     * rather than beside it as a chip. That is the whole structural difference between the cards.
     */
    fun stacksVertically(preview: LinkPreview): Boolean =
        preview.kind == PreviewKind.VideoEmbed || cardShape(preview) == CardShape.Hero

    /** The muted line above a card's heading: the site, and the author when there is one. */
    fun byline(preview: LinkPreview): String? {
        val site = preview.siteName ?: return null
        return preview.author?.let { "$site · $it" } ?: site
    }

    /**
     * A card's heading: title → siteName → hostname.
     *
     * ⚠⚠ The fallback is load-bearing rather than defensive. The server returns `ok` on a title OR an
     * image, so titleless cards are ordinary — an og:image with no og:title, a description-only page,
     * the deliberately-degraded video record. Gating on `title` drew each of them with no anchor at
     * all: a preview that goes nowhere and reads to TalkBack as an empty box. `siteName` is the right
     * rung because the server guarantees it (providerName || og:site_name || hostname).
     */
    fun heading(preview: LinkPreview): String? =
        preview.title ?: preview.siteName ?: preview.url.toHttpUrlOrNull()?.host

    /** A card as TalkBack hears it — iOS's label: the site, the title, the description. */
    fun cardLabel(preview: LinkPreview): String =
        listOfNotNull(preview.siteName, preview.title, preview.description).joinToString(", ")

    /**
     * A media box as TalkBack hears it. Per KIND; "Animation" once the bytes turn out to move, which
     * is known only after the still has decoded.
     */
    fun mediaLabel(kind: PreviewKind, animated: Boolean = false): String =
        when (kind) {
            PreviewKind.Video -> "Video"
            PreviewKind.Audio -> "Audio"
            PreviewKind.Image, PreviewKind.Page, PreviewKind.VideoEmbed -> if (animated) "Animation" else "Image"
        }

    /** The glyph a media box carries when it isn't a drawable still. */
    enum class Glyph { Play, Waveform, Photo }

    /**
     * ⚠ Per KIND, with no default. A still we can't draw is a missing picture, not something to press
     * play on, and a play button over it advertises a viewer that `isViewable` will refuse.
     */
    fun glyph(kind: PreviewKind): Glyph =
        when (kind) {
            PreviewKind.Video -> Glyph.Play
            PreviewKind.Audio -> Glyph.Waveform
            PreviewKind.Image, PreviewKind.Page, PreviewKind.VideoEmbed -> Glyph.Photo
        }

    /**
     * Every DIRECT MEDIA item in the message the viewer can present, in the order posted — pictures,
     * video and audio alike, not just the images the mosaic drew. They share a viewer because they
     * share a question ("show me this properly"), and a message mixing a screenshot and a clip should
     * let the reader swipe between them.
     *
     * ⚠⚠ The admission test is the kit's `LinkPreview.isViewable`, which answers per kind because
     * the two get their bytes from different places.
     */
    fun gallery(previews: List<LinkPreview>): List<LinkPreview> = previews.filter { it.isViewable }

    /** Where a tap on a media box goes. */
    sealed interface Tap {
        /** The viewer, positioned on this picture. */
        data class Viewer(val previews: List<LinkPreview>, val index: Int) : Tap

        /** The address, opened directly — a deliberate tap, so it goes to the origin, not the proxy. */
        data class Open(val url: String) : Tap
    }

    /**
     * A tap on the media for [url]: the viewer, over the message's [gallery], when there's one to
     * present and the item is in it; the address otherwise — a still the server couldn't draw, a clip
     * the platform can't load, or a screen with no viewer to present from.
     */
    fun tap(url: String, gallery: List<LinkPreview>, canPresent: Boolean): Tap {
        if (canPresent) {
            val at = gallery.indexOfFirst { it.url == url }
            if (at >= 0) return Tap.Viewer(gallery, at)
        }
        return Tap.Open(url)
    }
}
