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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
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
import kotlin.math.ceil

/*
 * One row of the compact message list, as lurker-ios's `CompactCell` and `MessageListMarker` draw it:
 *
 *     alice                      14:42
 *      morning — did the deploy land?
 *      and is the changelog updated?
 *     bob
 *      yeah, ten minutes ago
 *
 * The PWA's mobile compact mode, with one deliberate difference: the timestamp sits on the author line
 * rather than floating at the end of whichever body line starts a new minute. Same rule about *when*
 * it appears; it lands somewhere structural instead of somewhere incidental.
 *
 * What to draw is `MessageListLayout.plan`'s decision; this file only draws it.
 */

/**
 * The compact face: the monospaced system font at the app's one text size — a fixed-width log, the
 * way irssi and weechat look. `bodyMedium`, the buffer list's size (`rosterTextStyle`), so the list
 * and the log beside it read as one surface. lurker-ios's `MessageRenderer.compactFont`.
 */
@Composable
fun compactTextStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = LurkerTheme.colors.fg)

/**
 * The renderer's inputs for this screen: the scheme's colours, and the compact face measured — one
 * character of it is the indent everything hangs by, and it moves with the font scale, so it is
 * measured rather than assumed. iOS's `compactMetrics`, cached there per content size category;
 * remembered here per style and density.
 */
@Composable
fun rememberMessageTextStyle(): MessageTextStyle {
    val colors = LurkerTheme.colors
    val textStyle = compactTextStyle()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(colors, textStyle, density) {
        val width = measurer.measure(" ", textStyle).size.width
        with(density) {
            MessageTextStyle(colors = colors, fontSizeSp = textStyle.fontSize.value, indentSp = width.toSp().value)
        }
    }
}

/**
 * The vertical rhythm, point for dp from `CompactCell` and `MessageRenderer`'s compact constants.
 *
 * The line gap iOS spends at each end of a row (`compactLineGap / 2`) is already inside a Compose
 * text's line height — `bodyMedium`'s 20sp centres its glyphs with the leading split above and below
 * — so two stacked rows are exactly one wrapped line apart, which is the rhythm that constant exists
 * to keep. What's left to add is what iOS adds on top of it.
 */
@Immutable
internal data class CompactMetrics(
    /** The gap after the last row of an author block: three quarters of a line (`compactBlockGap`). */
    val blockGap: Dp,
) {
    /** The share of [blockGap] a block keeps inside its own wash, at each end (`compactWashPadding`). */
    val wash: Dp get() = blockGap / 3

    companion object {
        /** A row's own side margins, so a cutout or a rail can't swallow a timestamp (`useOwnSideMargins`). */
        val side: Dp = 20.dp

        /**
         * What a header's gap adds over a body line's (`compactHeaderGap` 4 against the line gap 3):
         * a name and the words under it are less alike than two body lines are.
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

        /** A marker's breathing room — iOS's default cell margins around one line. */
        val markerVertical: Dp = 10.dp
    }
}

@Composable
internal fun rememberCompactMetrics(textStyle: TextStyle = compactTextStyle()): CompactMetrics {
    val density = LocalDensity.current
    return remember(textStyle, density) {
        val line = with(density) { textStyle.lineHeight.toDp() }
        CompactMetrics(blockGap = ceil(line.value * 0.75f).dp)
    }
}

/**
 * The row at [index] of the stream, drawn. lurker-ios's `MessageListRenderer.cell`.
 */
@Composable
fun MessageListRow(row: MessageRow, index: Int, context: MessageListContext, modifier: Modifier = Modifier) {
    when (val plan = MessageListLayout.plan(row, index, context)) {
        is RowPlan.Marker -> MarkerRow(plan, modifier)
        is RowPlan.Compact -> CompactRow(plan, context, modifier)
    }
}

/**
 * A centred marker row. Set in the system face, not the log's: it isn't a line of the log.
 *
 * ⚠ One font size, the app's rule — `bodyMedium`, where iOS draws these a size down (`caption1`).
 */
@Composable
private fun MarkerRow(plan: RowPlan.Marker, modifier: Modifier = Modifier) {
    val colors = LurkerTheme.colors
    Text(
        plan.text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CompactMetrics.side, vertical = CompactMetrics.markerVertical),
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
    val textStyle = compactTextStyle()
    val metrics = rememberCompactMetrics(textStyle)
    val content = plan.content
    val message = (content as? RowPlan.Content.Of)?.message
    val revealed = message?.let(context.revealedSpoilers) ?: emptySet()
    val body: AnnotatedString = when (content) {
        is RowPlan.Content.Of -> remember(content.message, revealed, context.highlighter, context.settings, style) {
            MessageText.renderCompactBody(
                content.message,
                style = style,
                settings = context.settings,
                highlighter = context.highlighter,
                revealed = revealed,
                // U8: the addresses a preview stands in for (`PreviewPlan.hidden`).
                onToggleSpoiler = { ordinal -> context.onToggleSpoiler(content.message, ordinal) },
            )
        }
        is RowPlan.Content.Summary -> remember(content.summary, style) {
            MessageText.renderCompactConsolidation(content.summary, style)
        }
        is RowPlan.Content.Typists -> remember(content.nicks, style) {
            MessageText.renderCompactTyping(content.nicks, style) ?: AnnotatedString("")
        }
    }
    val spokenBody = remember(body) { MessageText.spokenAnnotated(body) }
    val label = MessageListLayout.spokenRow(plan.header, spokenBody)
    val ordinals = remember(body) { MessageText.hiddenSpoilerOrdinals(body) }
    // The label goes on the body, or on the header when a body has no text left (U8: every URL
    // hidden behind its picture).
    val semantics = Modifier.clearAndSetSemantics {
        text = label
        val actions = mutableListOf<CustomAccessibilityAction>()
        // The long press, for TalkBack — which has no long press on an element it reads whole.
        val press = context.onLongPress
        if (message != null && press != null) {
            actions += CustomAccessibilityAction("Message actions") {
                press(RowPress.Line(message))
                true
            }
        }
        if (message != null && ordinals.isNotEmpty()) {
            // One action per hidden box, named by position, since the whole point is that their
            // contents can't be read out to tell them apart.
            actions += ordinals.mapIndexed { position, ordinal ->
                val name = if (ordinals.size == 1) "Reveal spoiler" else "Reveal spoiler ${position + 1} of ${ordinals.size}"
                CustomAccessibilityAction(name) {
                    context.onToggleSpoiler(message, ordinal)
                    true
                }
            }
        }
        if (actions.isNotEmpty()) customActions = actions
    }
    val hasBody = body.isNotEmpty()
    val onLongPress = context.onLongPress
    val targets = remember { PressTargets() }
    val pressable = if (onLongPress == null) {
        Modifier
    } else {
        Modifier
            .onPlaced { targets.row = it }
            .longPressAnywhere { position -> targets.resolve(position, message)?.let(onLongPress) }
    }

    Column(
        modifier
            .then(pressable)
            .fillMaxWidth()
            .padding(bottom = if (plan.endsBlock) metrics.blockGap - metrics.wash * 2 else 0.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // No zebra: the author header marks where a block starts. Only a matched rule paints.
                .background(if (plan.highlighted) colors.highlightBubble else Color.Transparent)
                .padding(horizontal = CompactMetrics.side)
                .padding(
                    top = if (plan.startsBlock) metrics.wash else 0.dp,
                    bottom = if (plan.endsBlock) metrics.wash + CompactMetrics.washBottomNudge else 0.dp,
                ),
        ) {
            val header = plan.header
            if (header != null) {
                HeaderLine(header, style, textStyle, if (hasBody) Modifier.clearAndSetSemantics {} else semantics)
            }
            val reply = plan.reply
            if (reply != null) {
                ReplyQuoteLine(
                    reply,
                    indented = plan.indentsBody,
                    style = style,
                    textStyle = textStyle,
                    modifier = Modifier.padding(top = if (header != null) CompactMetrics.headerGap else 0.dp),
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
            // U8: link previews and inline media (`MessageAttachmentsView`), indented under the body.
            val chips = plan.reactions
            if (chips != null && message != null) {
                ReactionChipRow(
                    chips = chips,
                    style = style,
                    textStyle = textStyle,
                    onToggle = { value -> context.reactions?.onToggle?.invoke(message, value) },
                    onOpen = context.reactions?.let { reactions -> { reactions.onOpen(message) } },
                    // Under the body, one character in, as the body sits under its author. Placed
                    // before the padding, so a long press in the padding still counts as on the chips.
                    modifier = Modifier.onPlaced { targets.chips = it }.padding(
                        start = with(LocalDensity.current) { style.indentSp.sp.toDp() },
                        top = CompactMetrics.reactionTop,
                        bottom = CompactMetrics.reactionBottom,
                    ),
                )
            }
        }
    }
}

/**
 * The author line: the name (with its rank glyph and relay source) on the leading edge, the minute on
 * the trailing one. The name truncates before the clock does: a long nick is recoverable from context,
 * a half-drawn time is just wrong.
 */
@Composable
private fun HeaderLine(header: CompactHeader, style: MessageTextStyle, textStyle: TextStyle, modifier: Modifier) {
    val name = remember(header, style) { MessageText.headerName(header, style) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            name,
            modifier = Modifier.weight(1f).alignByBaseline(),
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val time = header.time
        if (time != null) {
            Text(time, modifier = Modifier.alignByBaseline(), style = textStyle, color = style.colors.fgMuted, maxLines = 1)
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
) {
    val quote = reply.quote
    val jump = reply.onJump
    val text = remember(quote, indented, style) { MessageText.renderReplyQuote(quote, indented, style) }
    val spoken = MessageText.spokenReplyQuote(quote)
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

/** The typing line's keyboard glyph, sized like the text beside it and muted like it. */
@Composable
private fun typingGlyph(tint: Color): Map<String, InlineTextContent> =
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

/** A conversation's worth of rows: a day, a run with a minute change, a /me, churn, a spoiler, a reply. */
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
                canToggle = { true },
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
