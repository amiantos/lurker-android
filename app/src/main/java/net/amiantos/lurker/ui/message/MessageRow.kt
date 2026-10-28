// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.amiantos.lurker.ui.media.MessageAttachments
import net.amiantos.lurker.ui.media.PreviewPlan
import net.amiantos.lurker.ui.media.PreviewUpdates
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.CompactDensity
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.MessageRows
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.rendering.NickHighlighter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * One row of the compact message list, as lurker-ios's `CompactCell` and `MessageListMarker` draw it:
 *
 *     alice 14:42
 *      morning — did the deploy land?
 *      and is the changelog updated?
 *     bob joined 14:43
 *     bob 14:43
 *      yeah, ten minutes ago
 *
 * The PWA's compact layout: a header with the nick and then its time opens each author run, and the
 * run's later lines carry no time. A run lasts while the same author keeps talking within
 * `look.message.collapse_authors_window`, whatever the minute. A header-less row — a `/me`, a join, a
 * collapsed run — carries its time after its text. Nothing sits at the trailing edge (lurker#1237).
 * How far apart it all sits is `look.message.compact.*` (lurker#1224), on `MessageTextStyle.density`.
 *
 * What to draw is `MessageListLayout.plan`'s decision; this file only draws it.
 */

/**
 * The compact face: the monospaced system font at the app's one text size — a fixed-width log, the
 * way irssi and weechat look. `bodyMedium`, the buffer list's size (`rosterTextStyle`), so the list
 * and the log beside it read as one surface. lurker-ios's `MessageRenderer.compactFont`.
 *
 * Its line height is `look.message.compact.line_height`, a percentage of the font size as the web's
 * CSS reads it — the leading Compose splits above and below each line, which is the gap iOS spends
 * between wrapped lines and around each row (`CompactSpacing.lineGap`).
 */
@Composable
fun compactTextStyle(density: CompactDensity = CompactDensity.standard): TextStyle {
    val base = MaterialTheme.typography.bodyMedium
    return base.copy(
        fontFamily = FontFamily.Monospace,
        color = LurkerTheme.colors.fg,
        lineHeight = (base.fontSize.value * density.lineHeightPercent / 100f).sp,
    )
}

/**
 * The renderer's inputs for this screen: the scheme's colours, the compact face measured — one
 * character of it is the indent everything hangs by, and it moves with the font scale, so it is
 * measured rather than assumed — and the density the screen's settings give ([compact], the
 * registry's for a surface they don't reach). iOS's `compactMetrics`, cached there per content size
 * category; remembered here per style and density.
 */
@Composable
fun rememberMessageTextStyle(compact: CompactDensity = CompactDensity.standard): MessageTextStyle {
    val colors = LurkerTheme.colors
    val textStyle = compactTextStyle(compact)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(colors, textStyle, density, compact) {
        val width = measurer.measure(" ", textStyle).size.width
        with(density) {
            MessageTextStyle(
                colors = colors,
                fontSizeSp = textStyle.fontSize.value,
                indentSp = width.toSp().value,
                density = compact,
                indentEm = width / textStyle.fontSize.toPx(),
            )
        }
    }
}

/**
 * The vertical rhythm, point for dp from `CompactCell` and `MessageRenderer.CompactSpacing`.
 *
 * The line gap iOS spends at each end of a row (`lineGap / 2`) is already inside a Compose text's
 * line height — [compactTextStyle] centres its glyphs with the leading split above and below — so two
 * stacked rows are exactly one wrapped line apart, which is the rhythm that gap exists to keep.
 * What's left to add is what iOS adds on top of it.
 */
@Immutable
internal data class CompactMetrics(
    /**
     * The gap after the last row of an author block: `look.message.compact.group_gap`, in sp so it
     * scales with the font as iOS's scales with Dynamic Type (`CompactSpacing.blockGap`).
     */
    val blockGap: Dp,
) {
    /** The share of [blockGap] a block keeps inside its own wash, at each end (`compactWashPadding`). */
    val wash: Dp get() = blockGap / 3

    /**
     * The gap a marker carries above its text: the wash a block opens with ([MarkerRow]). Part of the
     * space between two rows, not of the marker, so it doesn't count toward the marker being on screen.
     */
    val markerGapAbove: Dp get() = wash

    /** The gap a marker carries below its text: the rest of the block gap and the nudge ([MarkerRow]). */
    val markerGapBelow: Dp get() = blockGap - wash + washBottomNudge

    companion object {
        /** `look.message.compact.group_gap` at [density]'s font scale. */
        fun of(compact: CompactDensity, density: Density): CompactMetrics =
            CompactMetrics(blockGap = with(density) { compact.groupGap.sp.toDp() })

        /** A row's own side margins, so a cutout or a rail can't swallow a timestamp (`useOwnSideMargins`). */
        val side: Dp = 20.dp

        /**
         * What a header's gap adds over a body line's (`CompactSpacing.headerGap`, the line gap plus
         * one): a name and the words under it are less alike than two body lines are.
         */
        val headerGap: Dp = 1.dp

        /**
         * An optical correction on the bottom of a block's wash: a line box carries its descender
         * space below the baseline, so equal padding looks tighter underneath.
         */
        val washBottomNudge: Dp = 1.dp

        /** Air above a reaction row, so chips don't sit flush against a matched line's wash. */
        val reactionTop: Dp = 6.dp

        /** Air under a reaction row, inside the block (`padding + 2` on iOS, less the line gap). */
        val reactionBottom: Dp = 2.dp

        /** Air above and below a message's attachments (`CompactCell.showAttachments`). */
        val attachmentsTop: Dp = 6.dp
        val attachmentsBottom: Dp = 8.dp

        /**
         * The air a marker keeps above and below its text: the web's 6px divider padding, in sp so it
         * scales with the font like [blockGap]. Fixed, not a density: the setting moves the gap
         * *around* a marker (lurker#1238), never the marker itself.
         */
        val markerPadding: TextUnit = 6.sp
    }
}

@Composable
internal fun rememberCompactMetrics(compact: CompactDensity = CompactDensity.standard): CompactMetrics {
    val density = LocalDensity.current
    return remember(compact, density) {
        CompactMetrics.of(compact, density)
    }
}

/**
 * The row at [index] of the stream, drawn. lurker-ios's `MessageListRenderer.cell`.
 */
@Composable
fun MessageListRow(row: MessageRow, index: Int, context: MessageListContext, modifier: Modifier = Modifier) {
    when (val plan = MessageListLayout.plan(row, index, context)) {
        is RowPlan.Marker -> MarkerRow(plan, rememberCompactMetrics(context.style.density), modifier)
        is RowPlan.Compact -> CompactRow(plan, context, modifier)
    }
}

/**
 * A centred marker row. Set in the system face, not the log's: it isn't a line of the log.
 *
 * ⚠ One font size, the app's rule — `bodyMedium`, where iOS draws these a size down (`caption1`).
 *
 * A marker is a block of its own: a cluster boundary, as the web's compact layout treats a divider
 * (lurker#1238). So it takes the wash a block opens with above its text, and below it the rest of the
 * block gap and the nudge, the way any block's last row does ([CompactRow]). The row above a marker always ends its
 * block and the row below always starts one (a divider breaks a run), so the marker sits one gap from
 * each neighbour, and two markers in a row sit one gap apart, not two.
 */
@Composable
private fun MarkerRow(plan: RowPlan.Marker, metrics: CompactMetrics, modifier: Modifier = Modifier) {
    val colors = LurkerTheme.colors
    val padding = with(LocalDensity.current) { CompactMetrics.markerPadding.toDp() }
    Text(
        plan.text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CompactMetrics.side)
            .padding(
                top = padding + metrics.markerGapAbove,
                bottom = padding + metrics.markerGapBelow,
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = when (plan.tone) {
            MarkerTone.Unread -> colors.badText
            MarkerTone.Muted -> colors.fgMuted
            MarkerTone.Faint -> colors.fgFaint
        },
        fontWeight = if (plan.tone == MarkerTone.Unread) FontWeight.Bold else null,
        textAlign = TextAlign.Center,
    )
}

/**
 * A compact row: the header when the row opens a block, a reply's quote, the body, and the reaction
 * chips, inside the matched-rule wash when a highlight rule matched.
 *
 * `startsBlock` and `endsBlock` put a third of the block gap inside the wash at each end, so a matched
 * block's fill is a band its text sits inside rather than a highlighter dragged across it, and the
 * rest of the gap after the block outside it, as plain background — a *bottom* margin, so the newest
 * block is pushed clear of whatever sits under the list.
 *
 * One TalkBack element for the header and body together — "alice, morning, 14:42" — with the quote
 * its own element before it, as iOS reads them.
 *
 * A long press anywhere on the row is resolved to what it landed on — the chips, a link, or the line
 * (`PressTargets`) — and handed to the screen (`MessageListContext.onLongPress`); TalkBack reaches the
 * line's actions as a custom action instead.
 */
@Composable
private fun CompactRow(plan: RowPlan.Compact, context: MessageListContext, modifier: Modifier = Modifier) {
    val style = context.style
    val colors = style.colors
    val textStyle = compactTextStyle(style.density)
    val metrics = rememberCompactMetrics(style.density)
    val content = plan.content
    val message = (content as? RowPlan.Content.Of)?.message
    val revealed = message?.let(context.revealedSpoilers) ?: emptySet()
    // What this row's previews mean for it — which addresses the body drops and what draws under it —
    // once, for both halves: the text losing a URL and the picture appearing are the same event.
    // ⚠ Re-planned when one of ITS URLs moves: reading their versions here subscribes this row, and
    // only this row, to them — a batch resolving elsewhere recomposes nothing on this line.
    val previews = context.previews
    val previewUrls = remember(message, previews?.toggles) {
        if (message == null || previews == null) emptyList() else previews.urls(message)
    }
    val moved = previewUrls.map(PreviewUpdates::version)
    val previewPlan = remember(message, previews?.store, previews?.toggles, moved) {
        if (message == null || previews == null) PreviewPlan.None else previews.plan(message)
    }
    val body: AnnotatedString = when (content) {
        is RowPlan.Content.Of -> remember(content.message, revealed, context.highlighter, context.settings, style, previewPlan.hidden, plan.trailingTime, context.namesKey) {
            MessageText.renderCompactBody(
                content.message,
                style = style,
                trailingTime = plan.trailingTime,
                settings = context.settings,
                highlighter = context.highlighter,
                revealed = revealed,
                hiddenUrls = previewPlan.hidden,
                onToggleSpoiler = { ordinal -> context.onToggleSpoiler(content.message, ordinal) },
                names = context.plainName,
            )
        }
        is RowPlan.Content.Summary -> remember(content.summary, style, plan.trailingTime, context.namesKey) {
            MessageText.renderCompactConsolidation(content.summary, style, plan.trailingTime, context.plainName)
        }
        is RowPlan.Content.Typists -> remember(content.nicks, style) {
            MessageText.renderCompactTyping(content.nicks, style) ?: AnnotatedString("")
        }
    }
    val spokenBody = remember(body) { MessageText.spokenAnnotated(body) }
    val label = MessageListLayout.spokenRow(plan.header, spokenBody)
    val ordinals = remember(body) { MessageText.hiddenSpoilerOrdinals(body) }
    val actions = buildList {
        // The long press, for TalkBack — which has no long press on an element it reads whole.
        val press = context.onLongPress
        if (message != null && press != null) {
            add(
                CustomAccessibilityAction("Message actions") {
                    press(RowPress.Line(message))
                    true
                },
            )
        }
        if (message != null && ordinals.isNotEmpty()) {
            // One action per hidden box, named by position, since the whole point is that their
            // contents can't be read out to tell them apart.
            ordinals.forEachIndexed { position, ordinal ->
                val name = if (ordinals.size == 1) "Reveal spoiler" else "Reveal spoiler ${position + 1} of ${ordinals.size}"
                add(
                    CustomAccessibilityAction(name) {
                        context.onToggleSpoiler(message, ordinal)
                        true
                    },
                )
            }
        }
    }
    // The label goes on the body, or on the header when a body has no text left (every URL hidden
    // behind its picture) — and when there's no header either, the actions ride the attachments.
    val semantics = Modifier.clearAndSetSemantics {
        text = label
        if (actions.isNotEmpty()) customActions = actions
    }
    val hasBody = body.isNotEmpty()
    // What the attachments and chips line up with: the body's setting under a header, the narration's
    // own one-character hang on a header-less line (a `/me`), whose words start flush and which the
    // setting leaves alone.
    val attachedIndent = with(LocalDensity.current) {
        // From the font's real size, as `bodyIndent`'s em is, so the two agree at any font scale.
        if (plan.indentsBody) (textStyle.fontSize.toPx() * style.indentEm * style.density.indent).toDp() else style.indentSp.sp.toDp()
    }
    val onLongPress = context.onLongPress
    val targets = remember { PressTargets() }
    val pressable = if (onLongPress == null) {
        Modifier
    } else {
        Modifier
            .onPlaced { targets.row = it }
            .longPressAnywhere { position ->
                // Resolved first: a press on nothing to act on is let go, not swallowed.
                val press = targets.resolve(position, message) ?: return@longPressAnywhere false
                onLongPress(press)
                true
            }
    }

    // The avatar column (lurker#607). Every row takes the gutter while avatars are on — continuations
    // and narration too, so bodies stay on one edge — and only a run's first row draws a tile, centred
    // on its head and first body line, the two lines a short message's wash covers. The tile at the
    // side margin where the text otherwise starts, the web's `--space-4`, a hairline, then `--space-5`
    // before the text. The hairline runs the row's whole height — the block gap it ends with included,
    // so the rows abutting make it one line — and is drawn after the row's content, so a washed row
    // doesn't cut it. On the row rather than the list, as the web's `.line::before` is.
    val avatars = context.avatars
    val gutter = if (avatars != null) AvatarColumn.gutter(avatars.size) else 0.dp
    val hairline = colors.border
    val lineHeightDp = with(LocalDensity.current) { textStyle.lineHeight.toDp() }
    Column(
        modifier
            .then(pressable)
            .fillMaxWidth()
            .then(
                if (avatars == null) {
                    Modifier
                } else {
                    // Before the padding, so the line covers the gap under the block too.
                    Modifier.drawWithContent {
                        drawContent()
                        val x = (CompactMetrics.side + AvatarColumn.hairline(avatars.size)).toPx()
                        drawLine(hairline, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                },
            )
            .padding(bottom = if (plan.endsBlock) metrics.blockGap - metrics.wash * 2 else 0.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // No zebra: the author header marks where a block starts. Only a matched rule paints.
                .background(if (plan.highlighted) colors.highlightBubble else Color.Transparent),
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = CompactMetrics.side + gutter, end = CompactMetrics.side)
                .padding(
                    top = if (plan.startsBlock) metrics.wash else 0.dp,
                    bottom = if (plan.endsBlock) metrics.wash + CompactMetrics.washBottomNudge else 0.dp,
                ),
        ) {
            val header = plan.header
            if (header != null) {
                HeaderLine(header, style, textStyle, if (hasBody) Modifier.clearAndSetSemantics {} else semantics, emphasized = avatars != null)
            }
            val reply = plan.reply
            if (reply != null) {
                ReplyQuoteLine(
                    reply,
                    indented = plan.indentsBody,
                    style = style,
                    textStyle = textStyle,
                    modifier = Modifier.padding(top = if (header != null) CompactMetrics.headerGap else 0.dp),
                    namesKey = context.namesKey,
                )
            }
            if (hasBody) {
                Text(
                    body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (header != null && reply == null) CompactMetrics.headerGap else 0.dp)
                        .onPlaced { targets.body = it }
                        .then(semantics),
                    onTextLayout = { targets.bodyLayout = it },
                    style = textStyle,
                    inlineContent = if (content is RowPlan.Content.Typists) typingGlyph(colors.fgMuted) else emptyMap(),
                )
            }
            // Link previews and inline media (`MessageAttachmentsView`), lined up with the words above
            // them (`attachedIndent`). Air above and below, so a picture doesn't read as belonging to
            // the next author's line; inside the wash, since it belongs to this message.
            if (previews != null && previewPlan.resolved.isNotEmpty()) {
                MessageAttachments(
                    previews = previewPlan.resolved,
                    media = previews.media,
                    onOpenGallery = context.onOpenMedia,
                    rowActions = if (!hasBody && plan.header == null) actions else emptyList(),
                    onLinkActions = onLongPress?.let { press -> { url -> press(RowPress.Link(url)) } },
                    onPlaced = { url, coordinates -> targets.attachments[url] = coordinates },
                    modifier = Modifier.padding(
                        start = attachedIndent,
                        top = CompactMetrics.attachmentsTop,
                        bottom = CompactMetrics.attachmentsBottom,
                    ),
                )
            }
            val chips = plan.reactions
            if (chips != null && message != null) {
                ReactionChipRow(
                    chips = chips,
                    style = style,
                    // The face's own line height, not the density's: the setting spaces lines of the
                    // log, and a chip sized from it would balloon at 250%.
                    textStyle = textStyle.copy(lineHeight = MaterialTheme.typography.bodyMedium.lineHeight),
                    onToggle = { value -> context.reactions?.onToggle?.invoke(message, value) },
                    onOpen = context.reactions?.let { reactions -> { reactions.onOpen(message) } },
                    // Under the body, lined up with its words. Placed before the padding, so a long
                    // press in the padding still counts as on the chips.
                    modifier = Modifier.onPlaced { targets.chips = it }.padding(
                        start = attachedIndent,
                        top = CompactMetrics.reactionTop,
                        bottom = CompactMetrics.reactionBottom,
                    ),
                )
            }
        }
        val tile = plan.avatar
        if (avatars != null && tile != null) {
            // Centred on the head and the first body line: past the wash, each line is one line height
            // and the two sit a header gap apart; the tile is never taller than the two together.
            val headAndFirstLine = lineHeightDp * 2 + CompactMetrics.headerGap
            val top = (if (plan.startsBlock) metrics.wash else 0.dp) + ((headAndFirstLine - avatars.size) / 2).coerceAtLeast(0.dp)
            NickAvatarTile(
                spec = tile,
                size = avatars.size,
                media = avatars.media,
                // A placeholder tile — a notice's megaphone, the server's rack — isn't a person, so a
                // tap on it falls through to the row as the web lets it.
                onTap = if (tile.isPerson && message != null) ({ avatars.onTap(message) }) else null,
                modifier = Modifier.padding(start = CompactMetrics.side, top = top),
            )
        }
        }
    }
}

/**
 * The author line: the name (with its rank glyph and relay source), then its time, as Slack and
 * Discord place it — nothing at the trailing edge (lurker#1237). The feed draws its rows' headers
 * with this too.
 *
 * The name truncates before the clock does: a long nick is recoverable from context, a half-drawn time
 * is just wrong. A `Row` measures the unweighted time first, so it always gets its width; the name,
 * weighted without filling, takes what's left and no more. The time may wrap only when it alone is
 * wider than the row — an older line's `2026-10-20 14:42` at the largest font scales — which beats
 * running off the edge. Three quarters of a character apart, the web's gap.
 */
@Composable
internal fun HeaderLine(
    header: CompactHeader,
    style: MessageTextStyle,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    /** Bold, beside the avatar gutter (lurker#607) — see `MessageText.headerName`. */
    emphasized: Boolean = false,
) {
    val name = remember(header, style, emphasized) { MessageText.headerName(header, style, emphasized) }
    val gap = with(LocalDensity.current) { (textStyle.fontSize.toPx() * style.indentEm * 0.75f).toDp() }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        // None when there's no name to show (a reaction to a `/me` in the feed), so the gap doesn't
        // push the time off the leading edge.
        if (name.isNotEmpty()) {
            Text(
                name,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val time = header.time
        if (time != null) {
            Text(time, modifier = Modifier.alignByBaseline(), style = textStyle, color = style.colors.fgMuted)
        }
    }
}

/**
 * A reply's quote, as the body's first line — part of the message, so a reply doesn't break its
 * author's run (lurker memory: the quote lives INSIDE the body). Faded as a whole (the web's opacity
 * 0.45), so the quoted nick keeps its colour under the fade. Tappable only when the screen gives it
 * somewhere to go — the conversation's jump (`ConversationScroll.jumpTo`).
 */
@Composable
private fun ReplyQuoteLine(
    reply: ReplyLine,
    indented: Boolean,
    style: MessageTextStyle,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    /** `MessageListContext.namesKey`: what the quote's rendering is cached on, since `reply.names` is a new lambda per plan. */
    namesKey: Any? = null,
) {
    val quote = reply.quote
    val jump = reply.onJump
    val text = remember(quote, indented, style, namesKey) { MessageText.renderReplyQuote(quote, indented, style, reply.names) }
    val spoken = MessageText.spokenReplyQuote(quote, reply.names)
    val tap = if (quote != null && jump != null) ({ jump(quote) }) else null
    Text(
        text,
        modifier = modifier
            // Before the clickable, so it replaces the clickable's own semantics.
            .clearAndSetSemantics {
                contentDescription = spoken
                if (tap != null) {
                    role = Role.Button
                    onClick(label = "jump to that message") {
                        tap()
                        true
                    }
                }
            }
            .fillMaxWidth()
            .alpha(0.45f)
            .then(if (tap != null) Modifier.clickable(onClick = tap) else Modifier),
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The typing line's keyboard glyph, sized like the text beside it and muted like it. The composer's status row draws it too. */
@Composable
internal fun typingGlyph(tint: Color): Map<String, InlineTextContent> =
    remember(tint) {
        mapOf(
            MessageText.TYPING_GLYPH to InlineTextContent(
                Placeholder(
                    width = MessageTextStyle.TYPING_GLYPH_EM.em,
                    height = 1.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Icon(LurkerIcons.Keyboard, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = tint)
            },
        )
    }

// MARK: - Previews

/** A conversation's worth of rows: a day, a run across a minute, a /me, churn, a spoiler, a reply. */
internal fun previewMessageRows(): List<MessageRow> {
    val base = Instant.parse("2026-07-25T14:41:00Z")
    var id = 100L
    fun line(
        type: EventType,
        nick: String?,
        text: String? = null,
        seconds: Long,
        isSelf: Boolean = false,
        matched: Boolean = false,
        reactions: List<MessageReaction>? = null,
    ) = Message(
        id = ++id, type = type, nick = nick, text = text, isSelf = isSelf,
        date = base.plusSeconds(seconds), matched = matched, msgid = "m$id", reactions = reactions,
    )
    val messages = listOf(
        line(EventType.Join, "carol", seconds = 0),
        line(EventType.Join, "dave", seconds = 5),
        line(EventType.Message, "alice", "morning — did the deploy land? https://lurker.chat/changelog", seconds = 20),
        line(EventType.Message, "alice", "and is the \u0002changelog\u0002 updated?", seconds = 40),
        line(EventType.Message, "alice", "bob: the ending is \u000301,01he was a ghost\u0003 by the way", seconds = 70),
        line(EventType.Message, "bob", "yeah, ten minutes ago", seconds = 90, matched = true),
        line(EventType.Action, "bob", "waves at \u000304alice\u0003", seconds = 100),
        line(EventType.Message, "me", "thanks both", seconds = 120, isSelf = true),
    )
    return MessageRows.build(messages, dividerAfterId = null, hasMoreOlder = false, typists = listOf("alice", "bob"), zone = ZoneOffset.UTC)
}

@Composable
private fun RowsPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        val style = rememberMessageTextStyle()
        val rows = previewMessageRows()
        val context = MessageListContext.over(
            rows,
            style = style,
            highlighter = NickHighlighter(listOf("alice", "bob")),
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 7, 25),
        )
        Column(Modifier.background(LurkerTheme.colors.bg)) {
            rows.forEachIndexed { index, row -> MessageListRow(row, index, context) }
        }
    }
}

@Preview(name = "Rows — light", widthDp = 360)
@Composable
private fun RowsPreviewLight() = RowsPreview(dark = false)

@Preview(name = "Rows — dark", widthDp = 360)
@Composable
private fun RowsPreviewDark() = RowsPreview(dark = true)

@Composable
private fun MarkersPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        val style = rememberMessageTextStyle()
        val away = Instant.parse("2026-07-25T12:00:00Z")
        val rows = listOf(
            MessageRow.StartOfHistory,
            MessageRow.DateDivider(away),
            MessageRow.ClearedDivider(away),
            MessageRow.AwayDivider(away, "lunch"),
            MessageRow.BackDivider(away, away.plusSeconds(3_900)),
            MessageRow.UnreadDivider,
            MessageRow.Consolidated(
                ConsolidationSummary(
                    groups = listOf(
                        ConsolidationSummary.IdentityGroup(
                            ConsolidationSummary.IdentityGroup.Kind.Joined,
                            visible = listOf(ConsolidationSummary.Entry.Nick("carol"), ConsolidationSummary.Entry.Nick("dave")),
                            hidden = 3,
                        ),
                    ),
                    date = away, firstId = 1, lastId = 5,
                ),
            ),
        )
        val context = MessageListContext.over(rows, style = style, zone = ZoneOffset.UTC, today = LocalDate.of(2026, 7, 25))
        Column(Modifier.background(LurkerTheme.colors.bg)) {
            rows.forEachIndexed { index, row -> MessageListRow(row, index, context) }
        }
    }
}

@Preview(name = "Markers — light", widthDp = 360)
@Composable
private fun MarkersPreviewLight() = MarkersPreview(dark = false)

@Preview(name = "Markers — dark", widthDp = 360)
@Composable
private fun MarkersPreviewDark() = MarkersPreview(dark = true)

@Composable
private fun ReactionsPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        val style = rememberMessageTextStyle()
        val message = Message(
            id = 7, type = EventType.Message, nick = "alice", text = "shipped it", msgid = "x",
            date = Instant.parse("2026-07-25T14:41:00Z"),
            reactions = listOf(
                MessageReaction("bob", "👍", isSelf = false),
                MessageReaction("me", "👍", isSelf = true),
                MessageReaction("carol", "🎉", isSelf = false),
                MessageReaction("dave", "a very long text reaction", isSelf = false),
            ),
        )
        val rows = listOf(MessageRow.Bubble(message, net.amiantos.lurkerkit.model.RunPosition.solo))
        val context = MessageListContext.over(
            rows,
            style = style,
            reactions = ReactionContext(
                groups = { Reactions.groups(it.reactions.orEmpty()) },
                canToggle = { { true } },
                showsAdd = { true },
                onToggle = { _, _ -> },
                onOpen = {},
            ),
            zone = ZoneOffset.UTC,
        )
        Column(Modifier.background(LurkerTheme.colors.bg)) { MessageListRow(rows[0], 0, context) }
    }
}

@Preview(name = "Reactions — light", widthDp = 360)
@Composable
private fun ReactionsPreviewLight() = ReactionsPreview(dark = false)

@Preview(name = "Reactions — dark", widthDp = 360)
@Composable
private fun ReactionsPreviewDark() = ReactionsPreview(dark = true)
