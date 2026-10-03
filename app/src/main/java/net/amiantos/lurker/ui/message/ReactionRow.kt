// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
 * Tapping a chip adds our reaction or takes it back, when [ReactionChips.canToggle] says one can go
 * out right now. When it can't, iOS opens the reaction sheet instead; that sheet is U6, so until then
 * such a chip is drawn and not tappable — a press that lights up and does nothing reads as broken.
 *
 * U6: the trailing add chip (always there while the line has reactions, as Slack does, unless
 * `showsAdd` is off) opens the picker, and a long press on a chip opens the sheet. Both arrive with
 * the picker; until then there's no add chip at all, for the same reason.
 */
@Composable
internal fun ReactionChipRow(
    chips: ReactionChips,
    style: MessageTextStyle,
    textStyle: TextStyle,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        for (group in chips.groups) {
            ReactionChip(group, canToggle = chips.canToggle, style = style, textStyle = textStyle, onToggle = onToggle)
        }
    }
}

@Composable
private fun ReactionChip(
    group: ReactionGroup,
    canToggle: Boolean,
    style: MessageTextStyle,
    textStyle: TextStyle,
    onToggle: (String) -> Unit,
) {
    val colors = style.colors
    val shape = RoundedCornerShape(4.dp)
    val ink = if (group.mine) colors.accent else colors.fgMuted
    val fill = if (group.mine) colors.accent.copy(alpha = 0.15f) else colors.bgSoft
    val edge = if (group.mine) colors.accent.copy(alpha = 0.3f) else colors.border
    val spoken = MessageText.spokenReaction(group)
    // What TalkBack says a tap does — iOS's hint.
    val action = if (group.mine) "take your reaction back" else "add your reaction"
    Text(
        "${MessageText.chipValue(group.value)} ${group.nicks.size}",
        modifier = Modifier
            // Outermost, so it replaces the clickable's own semantics rather than sitting beside them.
            .clearAndSetSemantics {
                contentDescription = spoken
                selected = group.mine
                if (canToggle) {
                    role = Role.Button
                    onClick(label = action) {
                        onToggle(group.value)
                        true
                    }
                }
            }
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .then(
                if (canToggle) Modifier.clickable(role = Role.Button, onClickLabel = action) { onToggle(group.value) } else Modifier,
            )
            // A pixel more below than above: an emoji's glyph sits low in the line, and even padding
            // left it touching the bottom edge while its top floated (the web's correction too).
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 5.dp),
        style = textStyle,
        color = ink,
        maxLines = 1,
    )
}

private val CHIP_GAP = 4.dp
