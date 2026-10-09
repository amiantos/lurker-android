// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurkerkit.model.ReactionGroup

/**
 * A line's reactions as a row of chips under its text (lurker-ios#183) — the web's `ReactionRow`,
 * lurker-ios's `ReactionRowView`.
 *
 * One chip per value with its count: a soft fill with a faint edge, ours tinted in the accent.
 * Square-ish on purpose — round pills were tried on the web and read as "not lurker-y". Wraps onto
 * further lines rather than scrolling, so every chip is reachable without a gesture the message list
 * would fight over.
 *
 * Tapping a chip adds our reaction or takes it back, when [ReactionChips.Chip.canToggle] says that can
 * go out right now — and the two can differ: irc.so takes a reaction but not a take-back (lurker#1101),
 * so there our own chip can't toggle while anyone else's can. When it can't, the tap opens the reaction
 * sheet instead of sending something the server would refuse in silence, and the sheet says why. The trailing add chip — always there while the line has reactions,
 * as Slack does, unless `showsAdd` is off (a notice, an encrypted line) — opens the sheet too, which is
 * also where a touch screen sees who gave what. A long press on the row of chips opens it as well
 * (`RowPress.Reactions`, resolved by the row).
 */
@Composable
internal fun ReactionChipRow(
    chips: ReactionChips,
    style: MessageTextStyle,
    textStyle: TextStyle,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The reaction sheet, or null where there is none — then no add chip, and a dead chip stays dead. */
    onOpen: (() -> Unit)? = null,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        for (chip in chips.chips) {
            ReactionChip(
                chip.group, canToggle = chip.canToggle, style = style, textStyle = textStyle, onToggle = onToggle, onOpen = onOpen,
            )
        }
        if (chips.showsAdd && onOpen != null) AddChip(style = style, textStyle = textStyle, onOpen = onOpen)
    }
}

@Composable
private fun ReactionChip(
    group: ReactionGroup,
    canToggle: Boolean,
    style: MessageTextStyle,
    textStyle: TextStyle,
    onToggle: (String) -> Unit,
    onOpen: (() -> Unit)?,
) {
    val colors = style.colors
    val shape = RoundedCornerShape(4.dp)
    val ink = if (group.mine) colors.accent else colors.fgMuted
    val fill = if (group.mine) colors.accent.copy(alpha = 0.15f) else colors.bgSoft
    val edge = if (group.mine) colors.accent.copy(alpha = 0.3f) else colors.border
    val spoken = MessageText.spokenReaction(group)
    // What a tap does: toggles ours when one can go out, else shows the sheet (when there is one).
    val tap: (() -> Unit)? = when {
        canToggle -> ({ onToggle(group.value) })
        onOpen != null -> onOpen
        else -> null
    }
    // What TalkBack says a tap does — iOS's hint.
    val action = when {
        !canToggle -> "show reactions"
        group.mine -> "take your reaction back"
        else -> "add your reaction"
    }
    Text(
        "${MessageText.chipValue(group.value)} ${group.nicks.size}",
        modifier = Modifier
            // Outermost, so it replaces the clickable's own semantics rather than sitting beside them.
            .clearAndSetSemantics {
                contentDescription = spoken
                selected = group.mine
                if (tap != null) {
                    role = Role.Button
                    onClick(label = action) {
                        tap()
                        true
                    }
                }
            }
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .then(if (tap != null) Modifier.clickable(role = Role.Button, onClickLabel = action, onClick = tap) else Modifier)
            // A pixel more below than above: an emoji's glyph sits low in the line, and even padding
            // left it touching the bottom edge while its top floated (the web's correction too).
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 5.dp),
        style = textStyle,
        color = ink,
        maxLines = 1,
    )
}

/**
 * The add chip: a placeholder, not a reaction, so it's faded well below the chips beside it — the web
 * found a placeholder-strength glyph still read as one more reaction. The same box as a chip (a line
 * of text tall, the same padding), so a row of them lines up.
 */
@Composable
private fun AddChip(style: MessageTextStyle, textStyle: TextStyle, onOpen: () -> Unit) {
    val colors = style.colors
    val shape = RoundedCornerShape(4.dp)
    val density = LocalDensity.current
    val line = with(density) { textStyle.lineHeight.toDp() }
    val glyph = with(density) { (textStyle.fontSize * 0.95f).toDp() }
    Box(
        modifier = Modifier
            .clearAndSetSemantics {
                // iOS's label and hint.
                contentDescription = "Reactions"
                role = Role.Button
                onClick(label = "show who reacted, and add yours") {
                    onOpen()
                    true
                }
            }
            .clip(shape)
            .border(1.dp, colors.border.copy(alpha = colors.border.alpha * 0.6f), shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 5.dp)
            .height(line),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            LurkerIcons.Smile,
            contentDescription = null,
            modifier = Modifier.size(glyph),
            tint = colors.fgMuted.copy(alpha = colors.fgMuted.alpha * 0.55f),
        )
    }
}

private val CHIP_GAP = 4.dp
