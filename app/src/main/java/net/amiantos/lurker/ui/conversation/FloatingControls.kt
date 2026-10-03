// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme

/**
 * The shared body of the controls that float over the conversation — the jump-to-latest pill in
 * the bottom-trailing corner (lurker-ios#42), the unread banner at the top (#45). lurker-ios's
 * `FloatingGlassControl`: a raised capsule (Android has no glass; the connection banner's surface
 * and shadow, since the banner shares a slot with it), plus the one behaviour they all have to get
 * right — visibility and the touch target move TOGETHER, so a control fading out never eats a tap
 * meant for the messages scrolling under it. `AnimatedVisibility` keeps the content composed for
 * the length of its exit, so the click is disabled the moment it's asked to go, not when it's gone.
 *
 * Shape and content are the caller's: a round icon pill and a wide labelled banner are one family
 * in everything but silhouette.
 *
 * @param content the control, told whether it's [visible] — its click goes with that.
 * @param enter how it arrives: the corner pill scales in place, the banner drops down from
 *   under the bar the way the connection banner does from the same slot.
 */
@Composable
internal fun FloatingControl(
    visible: Boolean,
    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
    content: @Composable (enabled: Boolean) -> Unit,
) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = enter, exit = exit) {
        content(visible)
    }
}

/** The capsule the two controls share. */
private fun Modifier.floatingCapsule(surface: Color): Modifier =
    this
        .shadow(3.dp, CircleShape)
        .clip(CircleShape)
        .background(surface, CircleShape)

/** iOS's `.spring(damping 0.8)` settle, the connection banner's curve. */
private fun <T> settle() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/**
 * The way back UP: a capsule that drops down from under the bar to say there are unread messages
 * above the reader, and jumps to the first of them when tapped (lurker-ios#45). lurker-ios's
 * `UnreadBanner`.
 *
 * At the TOP because that's where it's taking you — each control sits on the edge it goes to, and
 * neither covers the newest message, which every compact row runs the full width under. It shares
 * the slot with the connection banner, which wins it: the wire being down is the more urgent thing
 * to say, and the unreads will still be there afterwards.
 *
 * No count. Opening a buffer marks it read, so the live unread count is zero by the first frame;
 * what keeps this on screen is the latched divider being above the viewport. A number here would
 * read 0, or stale.
 */
@Composable
internal fun UnreadBanner(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tuck = with(LocalDensity.current) { 12.dp.roundToPx() }
    FloatingControl(
        visible = visible,
        enter = fadeIn(settle()) + slideInVertically(settle()) { -tuck },
        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { -tuck },
        modifier = modifier,
    ) { enabled ->
        UnreadCapsule(enabled = enabled, onClick = onClick)
    }
}

@Composable
private fun UnreadCapsule(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .floatingCapsule(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            // The words are the label; TalkBack gets the action instead, since tapping is the whole
            // point and "Unread messages" alone doesn't say a tap does anything.
            .clearAndSetSemantics {
                contentDescription = "Jump to first unread"
                role = Role.Button
                if (enabled) onClick { onClick(); true }
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The surface's own text colour and the connection banner's weight: the two capsules take
        // turns in one slot, so they have to be the same object in different words.
        Icon(
            LurkerIcons.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "Unread messages",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The way back DOWN: a round pill that returns the conversation to its newest message, shown while
 * the reader is up in history or anywhere on a detached slice. The badge counts what has arrived
 * below since they scrolled away — the down-arrow-with-a-count pill Messages, Slack and Discord
 * float over their logs. lurker-ios's `JumpToLatestButton` (on `GlassPillButton`).
 */
@Composable
internal fun JumpToLatestButton(visible: Boolean, newCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingControl(
        visible = visible,
        enter = fadeIn(settle()) + scaleIn(settle(), initialScale = 0.85f),
        exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.85f),
        modifier = modifier,
    ) { enabled ->
        LatestPill(newCount = newCount, enabled = enabled, onClick = onClick)
    }
}

/** The pill's diameter. iOS matches its composer's send button; U3: match the composer's. */
private val PILL = 44.dp

/** The badge's height — a tab bar's badge. */
private val BADGE = 18.dp

@Composable
private fun LatestPill(newCount: Int, enabled: Boolean, onClick: () -> Unit) {
    val spoken = newMessagesValue(newCount)
    Box(
        Modifier.clearAndSetSemantics {
            contentDescription = "Jump to latest"
            // The count speaks through the button's value; the badge isn't a stop of its own.
            spoken?.let { stateDescription = it }
            role = Role.Button
            if (enabled) onClick { onClick(); true }
        },
    ) {
        Box(
            Modifier
                .padding(top = 6.dp, end = 6.dp)
                .size(PILL)
                .floatingCapsule(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(LurkerIcons.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        // On the pill's top-trailing shoulder, half on and half off, the way a tab bar badges its
        // items. Accent-filled: the one place the accent is right — the app saying "there's more",
        // not a sender's content.
        if (newCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = BADGE, minHeight = BADGE)
                    .background(LurkerTheme.colors.accent, CircleShape)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badgeText(newCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/** The badge's words: a count, capped so it never outgrows the pill. */
internal fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()

/** What TalkBack hears beside "Jump to latest" — iOS's `accessibilityValue` — or null for none. */
internal fun newMessagesValue(count: Int): String? =
    if (count > 0) "$count new message${if (count == 1) "" else "s"}" else null

// MARK: - Previews

@Composable
private fun ControlsPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Column(
            Modifier.background(LurkerTheme.colors.bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UnreadCapsule(enabled = true, onClick = {})
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LatestPill(newCount = 0, enabled = true, onClick = {})
                LatestPill(newCount = 3, enabled = true, onClick = {})
                LatestPill(newCount = 140, enabled = true, onClick = {})
            }
        }
    }
}

@Preview(name = "Floating controls — light")
@Composable
private fun ControlsPreviewLight() = ControlsPreview(dark = false)

@Preview(name = "Floating controls — dark")
@Composable
private fun ControlsPreviewDark() = ControlsPreview(dark = true)
