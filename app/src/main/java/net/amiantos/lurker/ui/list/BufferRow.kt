// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import net.amiantos.lurker.ui.shell.StatusDot
import net.amiantos.lurker.ui.theme.LurkerColors
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.theme.monoTextStyle
import kotlin.math.roundToInt

/*
 * The buffer list's three kinds of item: a buffer's row, a group's header, and the dashed break
 * between a network's pinned buffers and the rest. lurker-ios's `BufferRowCell.swift`.
 *
 * The list is a tree, drawn the way the web sidebar draws it: an uppercase header per group and a
 * `├─`/`└─` guide beside every row under it, in the compact log's monospaced face, on the roster's
 * ground. No cards and no separators: the guides say which rows belong together, which is the job
 * iOS's inset-grouped cards and chip grids did before at several times the height.
 *
 * These are drawing only. What a touch does — open, the menu, a swipe, a drag — is the screen's,
 * handed in as [Modifier]s, so a row can't grow a gesture its section shouldn't have.
 */

/**
 * One set of measurements for every item, so a header's text, the tree's spine and a row's name
 * line up by construction rather than by matching constants in three places. lurker-ios's
 * `RosterMetrics`, point for dp.
 *
 * Horizontal measurements run from the pane's start edge inside its insets — iOS measures from the
 * safe area for the same reason: a display cutout or a rail must not swallow a count.
 */
internal object RosterMetrics {
    /** Edge to a header's text, and a count to the trailing edge. */
    val inset: Dp = 20.dp

    /** [inset] to the tree's vertical line. */
    val spine: Dp = 6.dp

    /** [inset] to a row's name: past the spine and its arm, with a gap after the arm. */
    val name: Dp = 24.dp

    /** The arm's length: the `─` of `├─`. */
    val arm: Dp = 9.dp

    /**
     * A row's floor. Under Material's 48dp — a deliberate trade, kept from iOS (under its 44pt
     * guideline there), for a list you scan; the whole width of the row is the target, and it grows
     * with the font scale.
     */
    val row: Dp = 32.dp
    val header: Dp = 30.dp

    /**
     * The space between a group's last row and the rule over the next group, and between that rule
     * and the next header.
     */
    val groupGap: Dp = 6.dp

    /** The open row's accent edge. */
    val edge: Dp = 2.dp
    val pinBreak: Dp = 10.dp

    /** A network header's status dot. */
    val dot: Dp = 7.dp

    /**
     * [inset] to a dotted header's content: the dot centred on the spine, so the tree reads as
     * hanging from it. The spine is a 1dp line, so its centre is half a dp in.
     */
    val dotLead: Dp = spine + 0.5.dp - dot / 2

    /**
     * The text's breathing room above and below. iOS asks 7pt around a 15pt face whose line is
     * ~18pt, landing on its 32pt row; `bodyMedium`'s line is 20sp, so 6dp lands on the same 32.
     */
    val textPadding: Dp = 6.dp
}

/**
 * The one face this list is set in: the compact message log's, so the list and the log beside it
 * read as one surface. lurker-ios's `MessageRenderer.compactFont` — a monospaced face at the app's
 * one text size (`.subheadline` there; `bodyMedium` is the size U0 sets all of its text in).
 * Italic for an offline peer (lurker-ios#167) is applied per row.
 *
 * "Denser" is spacing, not type size — one font size for rows and headers alike. The hierarchy is
 * case, colour and the tree.
 */
@Composable
internal fun rosterTextStyle(): TextStyle = monoTextStyle()

/** The colour a count (and an unread name) wears. */
private fun LurkerColors.signal(signal: UnreadSignal?): Color? =
    when (signal) {
        null -> null
        UnreadSignal.Unread -> accent
        UnreadSignal.Mentioned -> warn
    }

/**
 * One buffer: its name, an optional draft pencil and network hint, and its unread count, beside a
 * tree guide.
 *
 * Unread is colour, the web's rule: the name turns the accent and the count is plain text in the
 * same colour; a highlight makes both gold. There is no pill — a capsule is a card in miniature,
 * and this list has none.
 *
 * [gestures] carries the row's click, long-press or drag handle; it sits inside the ground, so a
 * press's ripple lands on the row rather than under it. [isOpen] is drawn only when the screen says
 * the list is beside the conversation. [lifted] is a row being dragged.
 */
@Composable
internal fun BufferRow(
    row: Row,
    isOpen: Boolean,
    modifier: Modifier = Modifier,
    gestures: Modifier = Modifier,
    lifted: Boolean = false,
) {
    val colors = LurkerTheme.colors
    val style = rosterTextStyle()
    val signal = BufferListModel.unreadSignal(row.displayUnread, row.buffer.highlights)
    val presence = row.presence
    // Away or offline mutes the name even with something waiting, as the web's `peer-away`
    // outranks its `unread`; the count keeps its colour, so what's waiting still shows.
    val nameColor = if (presence?.dimsName == true) colors.fgMuted else colors.signal(signal) ?: colors.fg
    // A channel we're not in: the row's text at half strength. Not the guide, unlike the web's
    // whole-row opacity — a dimmed piece of spine reads as a break in the tree.
    val textAlpha = if (row.parted) 0.5f else 1f
    val description = BufferListModel.rowDescription(row)
    Box(
        modifier
            .fillMaxWidth()
            .then(if (lifted) Modifier.shadow(6.dp) else Modifier)
            // ⚠ Opaque, swiped or lifted: a row sliding over its Leave/Close action shows whatever
            // is behind it, and behind a clear row is the red action, through the text.
            .background(colors.rosterGround)
            .then(gestures)
            .heightIn(min = RosterMetrics.row)
            .drawBehind {
                if (isOpen) drawOpenMark(colors)
                drawGuide(row.guide, colors.rosterGuide)
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .padding(start = RosterMetrics.inset + RosterMetrics.name, end = RosterMetrics.inset)
                .padding(vertical = RosterMetrics.textPadding)
                // One element: the description above says it all, in order.
                .clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The hint holds and the NAME truncates: two long names that collide are exactly the
            // pair the hint exists to tell apart, and a tail ellipsis would eat it first.
            Row(Modifier.weight(1f).alpha(textAlpha), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.buffer.displayName(),
                    modifier = Modifier.weight(1f, fill = false),
                    style = style,
                    color = nameColor,
                    // An offline person's name is italic (lurker-ios#167).
                    fontStyle = if (presence?.italicizesName == true) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.hasDraft) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        LurkerIcons.Pencil,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = colors.fgMuted,
                    )
                }
                val hint = row.networkHint
                if (hint != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(hint, style = style, color = colors.fgMuted, maxLines = 1)
                }
            }
            if (row.displayUnread > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    row.displayUnread.toString(),
                    modifier = Modifier.alpha(textAlpha),
                    style = style,
                    color = colors.signal(signal) ?: colors.accent,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A group's header: FRIENDS, FAVORITES, or a network.
 *
 * A network's header is also its server log, the web sidebar's shape — tapping it opens the log,
 * and it carries the log's unread count and the open mark when that's the conversation beside the
 * list. It shows the network's state as a dot, and in words when it isn't connected.
 *
 * Every header after the first draws the rule that separates it from the group above. [gestures]
 * covers the header's own row and not the rule, and only a header with a log is handed any: the
 * Friends and Favorites headers lead nowhere, and a press that lights up and does nothing reads as
 * broken.
 */
@Composable
internal fun RosterHeader(
    header: Header,
    isOpen: Boolean,
    modifier: Modifier = Modifier,
    gestures: Modifier = Modifier,
) {
    val colors = LurkerTheme.colors
    val style = rosterTextStyle()
    val log = header.log
    val unread = log?.displayUnread ?: 0
    val signal = BufferListModel.unreadSignal(unread, log?.buffer?.highlights ?: 0)
    val description = BufferListModel.headerDescription(header)
    Column(modifier.fillMaxWidth().background(colors.rosterGround)) {
        if (header.ruleAbove) {
            Spacer(Modifier.height(RosterMetrics.groupGap))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rosterGuide))
            Spacer(Modifier.height(RosterMetrics.groupGap))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(gestures)
                .heightIn(min = RosterMetrics.header)
                .drawBehind { if (isOpen) drawOpenMark(colors) }
                .semantics {
                    heading()
                    contentDescription = description
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val light = header.light
            Row(
                Modifier
                    // A dot sits on the spine below it; a header without one starts at the inset.
                    .padding(
                        start = RosterMetrics.inset + if (light == null) 0.dp else RosterMetrics.dotLead,
                        end = RosterMetrics.inset,
                    )
                    .padding(vertical = RosterMetrics.textPadding)
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (light != null) {
                    StatusDot(light, Modifier.size(RosterMetrics.dot))
                    Spacer(Modifier.width(10.dp))
                }
                // Uppercase and tracked, at the one font size: the web's `.net-head`, which demotes
                // itself with colour and case rather than a smaller face.
                Text(
                    header.title.uppercase(),
                    modifier = Modifier.weight(1f),
                    style = style.copy(letterSpacing = 0.04.em),
                    color = colors.fgMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The state word replaces the count rather than sitting beside it: "offline 3" reads
                // as one phrase, and the log's unread isn't what matters about a network that's
                // down. The word stays even though the dot says the same — the dot is colour alone.
                val state = header.state
                if (state != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(state, style = style, color = colors.fgMuted, maxLines = 1)
                } else if (unread > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text(unread.toString(), style = style, color = colors.signal(signal) ?: colors.accent, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The break between a network's pinned buffers and the rest: the spine carried through, and a
 * dashed rule across. The web's `.pin-divider` — a phantom row that says "section break" without
 * spending a header on it. Silent to TalkBack: it has nothing to say that the rows don't.
 */
@Composable
internal fun PinBreak(modifier: Modifier = Modifier) {
    val colors = LurkerTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(RosterMetrics.pinBreak)
            .background(colors.rosterGround)
            .drawBehind {
                drawGuide(TreeGuide.Spine, colors.rosterGuide)
                val line = 1.dp.toPx()
                val start = (RosterMetrics.inset + RosterMetrics.spine).toPx()
                val end = size.width - RosterMetrics.inset.toPx()
                val middle = (size.height / 2).roundToInt() + line / 2
                val dash = 3.dp.toPx()
                drawLine(
                    color = colors.rosterGuide,
                    start = Offset(mirrored(start), middle),
                    end = Offset(mirrored(maxOf(start, end)), middle),
                    strokeWidth = line,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                )
            },
    )
}

/**
 * The `├─`, `└─` or bare `│` beside a row: a 1dp stem down from the top, through the whole row or
 * — for the last one — stopping at the middle, and a 1dp arm out to the name. Drawn top to bottom,
 * so one row's stem meets the next row's with no gap between items.
 */
private fun DrawScope.drawGuide(shape: TreeGuide, color: Color) {
    val line = 1.dp.toPx()
    val x = (RosterMetrics.inset + RosterMetrics.spine).toPx()
    val middle = (size.height / 2).roundToInt().toFloat()
    val stemHeight = if (shape == TreeGuide.Elbow) middle + line else size.height
    drawRect(color, topLeft = Offset(mirrored(x, line), 0f), size = Size(line, stemHeight))
    if (shape != TreeGuide.Spine) {
        val arm = RosterMetrics.arm.toPx()
        drawRect(color, topLeft = Offset(mirrored(x, arm), middle), size = Size(arm, line))
    }
}

/** The open row's band (a wash of the foreground) and its accent edge on the leading side. */
private fun DrawScope.drawOpenMark(colors: LurkerColors) {
    drawRect(colors.rosterRaised)
    val edge = RosterMetrics.edge.toPx()
    drawRect(colors.accent, topLeft = Offset(mirrored(0f, edge), 0f), size = Size(edge, size.height))
}

/**
 * A leading-edge x for something [width] wide, flipped for a right-to-left layout, where the tree
 * hangs from the right.
 */
private fun DrawScope.mirrored(x: Float, width: Float = 0f): Float =
    if (layoutDirection == LayoutDirection.Rtl) size.width - x - width else x
