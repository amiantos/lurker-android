// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind

/**
 * Inline media and link preview cards, stacked under a message body. lurker-ios's
 * `MessageAttachmentsView`; what goes where is `AttachmentLayout`'s decision.
 *
 * Every box is sized from the preview's metadata before its picture loads (see `AttachmentLayout`),
 * so a picture landing is paint, never layout: in the reverse-layout list a late growth would move
 * every row above it under the reader's thumb.
 *
 * Video and audio show their poster (or a glyph) and hand off on tap: playing media inline is a
 * player per row and a memory problem, and the viewer is one tap away. An image that moves shows a
 * play badge once its bytes say so, and plays in the viewer — never inline (see `PreviewImageLoader`).
 *
 * @param onOpenGallery the viewer, over the message's viewable media and positioned on the tapped
 *   one (iOS's `onOpenGallery`). ⚠ The WHOLE message's, which is what makes the mosaic's cropping
 *   safe: every cropped tile is reachable uncropped from there. Null where nothing can present — a
 *   tap then opens the address.
 * @param rowActions the row's TalkBack actions, for a row with nothing else to carry them — every
 *   address hidden behind its picture, and no author line above (U2a's custom actions would otherwise
 *   be on no element at all).
 * @param onLinkActions an attachment's link actions — Copy, Open, Share of its address — the long
 *   press's answer (`RowPress.Link`), offered to TalkBack as a custom action, since the long press
 *   itself is the row's pointer detector and TalkBack never sees it. Null where there are none.
 * @param onPlaced where each attachment landed, by the address it stands for — so the row can resolve
 *   a long press on a tile or a card to that address's link actions, as on a link in the text. Every
 *   attachment is keyed by its address, so one re-planned onto a different preview is a new element and
 *   the old one's placement goes stale with it.
 */
@Composable
fun MessageAttachments(
    previews: List<LinkPreview>,
    media: MediaSource,
    onOpenGallery: ((List<LinkPreview>, Int) -> Unit)?,
    modifier: Modifier = Modifier,
    rowActions: List<CustomAccessibilityAction> = emptyList(),
    onLinkActions: ((url: String) -> Unit)? = null,
    onPlaced: (url: String, coordinates: LayoutCoordinates) -> Unit = { _, _ -> },
) {
    if (previews.isEmpty()) return
    val blocks = remember(previews) { AttachmentLayout.blocks(previews) }
    val gallery = remember(previews) { AttachmentLayout.gallery(previews) }
    val uriHandler = LocalUriHandler.current
    val actions = AttachmentActions(rowActions, onLinkActions)
    // Tap → the viewer, positioned on this picture; or the address, if nothing can present it.
    val onMedia: (String) -> Unit = { url ->
        when (val tap = AttachmentLayout.tap(url, gallery, canPresent = onOpenGallery != null)) {
            is AttachmentLayout.Tap.Viewer -> onOpenGallery?.invoke(tap.previews, tap.index)
            is AttachmentLayout.Tap.Open -> uriHandler.openUri(tap.url)
        }
    }
    // A card is a deliberate tap on a link: the address itself, straight to the origin.
    val onCard: (String) -> Unit = { url -> uriHandler.openUri(url) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AttachmentLayout.SPACING.dp)) {
        for (block in blocks) {
            key(block.key) {
                when (block) {
                    is AttachmentLayout.Block.Mosaic -> Mosaic(block.rows, media, onMedia, actions, onPlaced)
                    is AttachmentLayout.Block.Media -> MediaBox(block.preview, media, onMedia, actions, onPlaced)
                    is AttachmentLayout.Block.Card -> Card(block.preview, media, onCard, actions, onPlaced)
                }
            }
        }
    }
}

/**
 * What TalkBack can do on an attachment besides open it: its link actions, then the row's own when the
 * row has nothing else to carry them.
 */
private class AttachmentActions(
    private val row: List<CustomAccessibilityAction>,
    private val onLinkActions: ((String) -> Unit)?,
) {
    fun forUrl(url: String): List<CustomAccessibilityAction> = buildList {
        onLinkActions?.let { open ->
            add(
                CustomAccessibilityAction("Link actions") {
                    open(url)
                    true
                },
            )
        }
        addAll(row)
    }
}

/** The ground a picture sits on before it lands — iOS's `secondarySystemFill`. */
@Composable
private fun boxFill() = LurkerTheme.colors.bgSoft

/**
 * An attachment's TalkBack element: one label, one activation, and the row's actions when it has to
 * carry them. Before the clickable, so it replaces the clickable's own semantics.
 */
private fun Modifier.attachmentSemantics(
    label: String,
    actionLabel: String,
    onActivate: () -> Unit,
    actions: List<CustomAccessibilityAction>,
): Modifier = clearAndSetSemantics {
    contentDescription = label
    role = Role.Button
    onClick(label = actionLabel) {
        onActivate()
        true
    }
    if (actions.isNotEmpty()) customActions = actions
}

/**
 * A height decided by the width the box is given — a lone media box's shape, from the descriptor
 * (`AttachmentLayout.mediaHeight`), before any bytes.
 */
private fun Modifier.heightFromWidth(height: (widthDp: Float) -> Float): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val heightPx = constraints.constrainHeight(height(width.toDp().value).dp.roundToPx())
    val placeable = measurable.measure(Constraints.fixed(width, heightPx))
    layout(width, heightPx) { placeable.place(0, 0) }
}

// MARK: - The mosaic

/**
 * Two or more images as a grid whose height is a function of the COUNT alone — laid out correctly
 * before a single byte arrives. Cropping is what buys that, and the viewer is what makes it safe.
 */
@Composable
private fun Mosaic(
    rows: List<AttachmentLayout.MosaicRow>,
    media: MediaSource,
    onTap: (String) -> Unit,
    actions: AttachmentActions,
    onPlaced: (String, LayoutCoordinates) -> Unit,
) {
    val gap = AttachmentLayout.MOSAIC_GAP.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        // Keyed, so a re-planned mosaic never hands one picture's tile (and its decoded still) to another.
        for (row in rows) key(row.key) {
            when (row) {
                is AttachmentLayout.MosaicRow.ThreeUp -> Row(
                    Modifier.fillMaxWidth().height(AttachmentLayout.MOSAIC_THREE_UP_HEIGHT.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Tile(row.tall, media, onTap, actions, onPlaced, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        Tile(row.top, media, onTap, actions, onPlaced, Modifier.weight(1f).fillMaxWidth())
                        Tile(row.bottom, media, onTap, actions, onPlaced, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                is AttachmentLayout.MosaicRow.Pair -> Row(
                    Modifier.fillMaxWidth().height(AttachmentLayout.MOSAIC_ROW_HEIGHT.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Tile(row.left, media, onTap, actions, onPlaced, Modifier.weight(1f).fillMaxHeight())
                    Tile(row.right, media, onTap, actions, onPlaced, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** One cell of the grid. Fills and crops: every cell is the same size whatever the picture is. */
@Composable
private fun Tile(
    preview: LinkPreview,
    media: MediaSource,
    onTap: (String) -> Unit,
    actions: AttachmentActions,
    onPlaced: (String, LayoutCoordinates) -> Unit,
    modifier: Modifier,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val still by rememberPreviewStill(preview.src, media, box, DecodeSize.Mode.Fill)
    val animated = still?.animated == true
    Box(
        modifier
            .onPlaced { onPlaced(preview.url, it) }
            .onSizeChanged { box = it }
            .clip(RoundedCornerShape(AttachmentLayout.CORNER.dp))
            .background(boxFill())
            .attachmentSemantics(AttachmentLayout.mediaLabel(PreviewKind.Image, animated), "open", { onTap(preview.url) }, actions.forUrl(preview.url))
            .clickable { onTap(preview.url) },
        contentAlignment = Alignment.Center,
    ) {
        PreviewStillImage(still, ContentScale.Crop)
        if (animated) OverlayGlyph(AttachmentLayout.Glyph.Play.vector(), 40.dp)
    }
}

// MARK: - Direct media

/**
 * An image, clip or track, alone. No card, no chrome — just the thing: a frame around an image is
 * furniture around content.
 *
 * The picture is the kit's `inlinePicture` — `src` for an image, the decoded POSTER for a clip, cover
 * art for a track — every one of them a proxy path, so drawing it unasked tells no origin anything.
 */
@Composable
private fun MediaBox(
    preview: LinkPreview,
    media: MediaSource,
    onTap: (String) -> Unit,
    actions: AttachmentActions,
    onPlaced: (String, LayoutCoordinates) -> Unit,
) {
    val picture = preview.inlinePicture
    var box by remember { mutableStateOf(IntSize.Zero) }
    val still by rememberPreviewStill(picture, media, box, DecodeSize.Mode.Fill)
    val isImage = preview.kind == PreviewKind.Image
    val animated = isImage && still?.animated == true
    Box(
        Modifier
            .fillMaxWidth()
            .heightFromWidth { width -> AttachmentLayout.mediaHeight(preview, width) }
            .onPlaced { onPlaced(preview.url, it) }
            .onSizeChanged { box = it }
            .clip(RoundedCornerShape(AttachmentLayout.CORNER.dp))
            .background(boxFill())
            .attachmentSemantics(AttachmentLayout.mediaLabel(preview.kind, animated), "open", { onTap(preview.url) }, actions.forUrl(preview.url))
            .clickable { onTap(preview.url) },
        contentAlignment = Alignment.Center,
    ) {
        PreviewStillImage(still, ContentScale.Crop)
        if (isImage && picture != null) {
            // The badge says "this moves", nothing more — it appears when the still lands.
            if (animated) OverlayGlyph(AttachmentLayout.Glyph.Play.vector(), 40.dp)
        } else {
            // A glyph that says what this is. WHAT IT ENDS UP SITTING ON decides how it looks: over a
            // poster it is paint on a picture and takes the overlay treatment; on an empty box, the
            // muted tint of a placeholder. ⚠⚠ Promoted WHEN THE BYTES LAND, not on the descriptor's
            // promise: a poster has no origin to re-fetch from and can 404 (the server calls a
            // posterless render a supported state), and styling from the promise painted a white
            // glyph on a pale empty box, in light mode, for good.
            val glyph = AttachmentLayout.glyph(preview.kind).vector()
            if (still != null) {
                OverlayGlyph(glyph, 44.dp)
            } else {
                Icon(glyph, contentDescription = null, tint = LurkerTheme.colors.fgMuted, modifier = Modifier.size(44.dp))
            }
        }
    }
}

// MARK: - Cards

/**
 * A page, or a video page: a byline, a heading and a description beside a chip or over a hero band,
 * with the Slack signature down the left — a thin rule, deliberately a neutral colour rather than a
 * per-site accent, which would be a lot of machinery whose only effect is a louder timeline.
 *
 * ⚠ One font size, the app's rule — iOS sets these three lines a size apart (caption, subheadline,
 * footnote); here weight and colour carry the difference.
 */
@Composable
private fun Card(
    preview: LinkPreview,
    media: MediaSource,
    onOpen: (String) -> Unit,
    actions: AttachmentActions,
    onPlaced: (String, LayoutCoordinates) -> Unit,
) {
    val colors = LurkerTheme.colors
    val rule = colors.border
    val shape = AttachmentLayout.cardShape(preview)
    val thumb = preview.thumb
    Box(
        Modifier
            .fillMaxWidth()
            .onPlaced { onPlaced(preview.url, it) }
            .attachmentSemantics(AttachmentLayout.cardLabel(preview), "open link", { onOpen(preview.url) }, actions.forUrl(preview.url))
            .clickable { onOpen(preview.url) }
            .drawBehind {
                val width = 3.dp.toPx()
                drawRoundRect(rule, size = Size(width, size.height), cornerRadius = CornerRadius(width / 2))
            }
            // The rule's 3, then 12 of air; 6 above and below, so the first line doesn't sit hard
            // against the top of its own accent.
            .padding(start = 15.dp, top = 6.dp, bottom = 6.dp),
    ) {
        if (AttachmentLayout.stacksVertically(preview)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ⚠ Trailing inset on the TEXT, not the card: the picture keeps the full width.
                CardText(preview, Modifier.padding(end = 8.dp))
                if (thumb != null) {
                    if (preview.kind == PreviewKind.VideoEmbed) VideoFacade(thumb, media) else HeroBand(thumb, media)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                CardText(preview, Modifier.weight(1f).padding(end = 8.dp))
                if (shape == AttachmentLayout.CardShape.Chip && thumb != null) Chip(thumb, media)
            }
        }
    }
}

/** A card's words: byline, heading, description — four dp of air between, so three weights don't read as one block. */
@Composable
private fun CardText(preview: LinkPreview, modifier: Modifier) {
    val colors = LurkerTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AttachmentLayout.byline(preview)?.let {
            Text(it, style = style, color = colors.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AttachmentLayout.heading(preview)?.let {
            Text(it, style = style, color = colors.fg, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        preview.description?.let {
            Text(it, style = style, color = colors.fgMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A near-square picture as a 64dp chip beside the words. */
@Composable
private fun Chip(path: String, media: MediaSource) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val still by rememberPreviewStill(path, media, box, DecodeSize.Mode.Fill)
    Box(
        Modifier
            .size(AttachmentLayout.THUMB_SIDE.dp)
            .onSizeChanged { box = it }
            .clip(RoundedCornerShape(AttachmentLayout.CHIP_CORNER.dp))
            .background(boxFill()),
    ) { PreviewStillImage(still, ContentScale.Crop) }
}

/**
 * A card's picture as a full-width band under its words — Discord's large embed. ⚠ Aspect FIT, not
 * fill: 1200x630 is a convention, so a 4:3 or a square picture routinely lands here, and filling would
 * crop the subject out of exactly the pictures whose declared shape put them here by default.
 */
@Composable
private fun HeroBand(path: String, media: MediaSource) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val still by rememberPreviewStill(path, media, box, DecodeSize.Mode.Fit)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(AttachmentLayout.HERO_ASPECT)
            .onSizeChanged { box = it }
            .clip(RoundedCornerShape(AttachmentLayout.CORNER.dp))
            .background(boxFill()),
    ) { PreviewStillImage(still, ContentScale.Fit) }
}

/**
 * The play facade for a video page — a 64dp square is pointless for a video, so it gets 16:9 with a
 * badge. The thumbnail is proxied like every other preview picture, so not even it reaches the video
 * host: fifty YouTube links in scrollback would otherwise hand Google fifty impressions of the reader.
 * A tap opens the page, which Android hands to the app that claims it — deliberately not a web view
 * embedded in a list row.
 */
@Composable
private fun VideoFacade(path: String, media: MediaSource) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val still by rememberPreviewStill(path, media, box, DecodeSize.Mode.Fill)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(AttachmentLayout.VIDEO_ASPECT)
            .onSizeChanged { box = it }
            .clip(RoundedCornerShape(AttachmentLayout.CORNER.dp))
            .background(boxFill()),
        contentAlignment = Alignment.Center,
    ) {
        PreviewStillImage(still, ContentScale.Crop)
        OverlayGlyph(AttachmentLayout.Glyph.Play.vector(), 48.dp)
    }
}

// MARK: - Previews

/** A message's worth of every attachment shape — nothing behind them, so the boxes draw as they do before their bytes. */
internal fun previewAttachments(): List<LinkPreview> {
    fun image(n: Int, w: Int? = null, h: Int? = null) = LinkPreview(
        url = "https://example.com/$n.png", status = LinkPreview.Status.Ok, kind = PreviewKind.Image,
        src = "/api/media/$n", thumbWidth = w, thumbHeight = h,
    )
    return listOf(
        image(1), image(2), image(3),
        LinkPreview(
            url = "https://example.com/clip.mp4", status = LinkPreview.Status.Ok, kind = PreviewKind.Video,
            thumb = "/api/media/poster", thumbWidth = 1080, thumbHeight = 1920,
        ),
        LinkPreview(url = "https://example.com/song.mp3", status = LinkPreview.Status.Ok, kind = PreviewKind.Audio),
        LinkPreview(
            url = "https://github.com/amiantos/lurker", status = LinkPreview.Status.Ok, kind = PreviewKind.Page,
            title = "amiantos/lurker: a modern IRC client", siteName = "GitHub",
            description = "Lurker is a self-hosted IRC client and bouncer with a web, iOS and Android app.",
            thumb = "/api/media/og", thumbWidth = 1200, thumbHeight = 600,
        ),
        LinkPreview(
            url = "https://www.reddit.com/r/irc", status = LinkPreview.Status.Ok, kind = PreviewKind.Page,
            title = "r/irc", siteName = "reddit", author = "u/someone", thumb = "/api/media/logo", thumbWidth = 256, thumbHeight = 256,
        ),
        LinkPreview(
            url = "https://www.youtube.com/watch?v=x", status = LinkPreview.Status.Ok, kind = PreviewKind.VideoEmbed,
            title = "A talk about IRC", siteName = "YouTube", thumb = "/api/media/yt",
        ),
    )
}

@Composable
private fun AttachmentsPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Column(Modifier.background(LurkerTheme.colors.bg).padding(16.dp)) {
            MessageAttachments(previewAttachments(), MediaSource.None, onOpenGallery = { _, _ -> })
        }
    }
}

@Preview(name = "Attachments — light", widthDp = 360, heightDp = 1400)
@Composable
private fun AttachmentsPreviewLight() = AttachmentsPreview(dark = false)

@Preview(name = "Attachments — dark", widthDp = 360, heightDp = 1400)
@Composable
private fun AttachmentsPreviewDark() = AttachmentsPreview(dark = true)
