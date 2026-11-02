// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.Message
import kotlin.math.abs
import kotlin.math.max

/**
 * Swipe a line toward the trailing edge to reply to it (lurker#1248) — what the conversation hands
 * its rows. [offers] is the actions menu's own Reply rule (`MessageActions.replyAction`), asked when a
 * swipe takes hold: a line the menu offers no Reply on doesn't move. [reply] is the composer's Reply,
 * given the line as the list shows it — a relayed line as the person inside it.
 */
class SwipeReply(val offers: (Message) -> Boolean, val reply: (Message) -> Unit)

/**
 * The gesture: drag the row toward the trailing edge, and a release past [SWIPE_REACH] replies.
 * Discord's and Telegram's, and iOS's `SwipeToReplyRecognizer` — toward the trailing edge because the
 * leading one is the system's back gesture on both platforms.
 *
 * ⚠ Hand-rolled, not `SwipeToDismissBox` or `draggable`. The buffer list's swipe-to-part was one of
 * those, and people parted channels scrolling past a row (lurker#1102): Compose's horizontal drag
 * claims a touch the moment it moves a slop sideways, however far it also moved down, and a flung
 * box commits on velocity alone. This one decides ONCE, at the slop: the first move a slop away from
 * the touch-down is either a drag toward the trailing edge, steeper than two-to-three against its
 * vertical drift, or it is let go for good. And nothing fires until the finger lifts past the reach;
 * a cancelled touch replies to nothing however far the row had gone.
 *
 * Watched on the Initial pass, ahead of the list, as [longPressAnywhere] is, and for the same
 * reasons: a consumed down is the list's (a touch that stops a fling), and a move the list consumed on
 * the Final pass is a scroll. Once the drag is locked every change is consumed on the Initial pass, so
 * the list never starts a scroll under a row that's sliding, and the link or chip under the finger
 * doesn't take the release as a tap.
 */
@Composable
internal fun Modifier.swipeToReply(offers: () -> Boolean, onReply: () -> Unit): Modifier {
    val currentOffers by rememberUpdatedState(offers)
    val currentOnReply by rememberUpdatedState(onReply)
    val haptics = LocalHapticFeedback.current
    val colors = LurkerTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val arrow = rememberVectorPainter(LurkerIcons.Reply)
    val density = LocalDensity.current
    val reach = with(density) { SWIPE_REACH.toPx() }
    val arrowSize = with(density) { ARROW_SIZE.toPx() }
    var dragging by remember { mutableStateOf(false) }
    // How far the content sits toward the trailing edge, in px: the finger's travel up to the reach,
    // a third of it past — the row has already said yes.
    var offset by remember { mutableFloatStateOf(0f) }
    // Past the reach: the arrow turns accent as the haptic fires, and stays so while it springs home.
    var crossed by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(
        targetValue = if (dragging) offset else 0f,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "swipeToReply",
    )
    return this
        .drawBehind {
            if (shown <= 0f) return@drawBehind
            // In the strip the content left, centred, faint and small at first and full at the
            // reach — the same moment the haptic marks, so the eye and the thumb get the news together.
            val progress = (shown / reach).coerceIn(0f, 1f)
            val drawn = arrowSize * (0.7f + 0.3f * progress)
            val centerX = if (rtl) shown / 2 else size.width - shown / 2
            translate(centerX - drawn / 2, size.height / 2 - drawn / 2) {
                with(arrow) {
                    draw(Size(drawn, drawn), alpha = progress, colorFilter = ColorFilter.tint(if (crossed) colors.accent else colors.fgMuted))
                }
            }
        }
        .graphicsLayer { translationX = if (rtl) shown else -shown }
        .pointerInput(rtl) {
            val slop = viewConfiguration.touchSlop
            val sign = if (rtl) 1f else -1f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed) return@awaitEachGesture
                // Decide once, at the slop.
                var locked = false
                while (!locked) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || event.changes.size > 1) return@awaitEachGesture
                    val delta = change.position - down.position
                    if (max(abs(delta.x), abs(delta.y)) >= slop) {
                        val toward = delta.x * sign
                        if (toward > 0f && toward >= abs(delta.y) * 1.5f) locked = true else return@awaitEachGesture
                    }
                    if (!locked && awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return@awaitEachGesture
                }
                if (!currentOffers()) return@awaitEachGesture
                dragging = true
                crossed = false
                offset = 0f
                var replies = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        // A lift that arrives already consumed is Compose's synthesized cancel (the
                        // system took the touch: a call, a window change), not a release — it
                        // replies to nothing, however far the row had gone. Read before this
                        // consumes it.
                        val lifted = change != null && !change.pressed
                        val cancelled = lifted && change.isConsumed
                        event.changes.forEach { it.consume() }
                        if (change == null) break
                        if (lifted) {
                            replies = crossed && !cancelled
                            break
                        }
                        val toward = ((change.position.x - down.position.x) * sign).coerceAtLeast(0f)
                        offset = if (toward <= reach) toward else reach + (toward - reach) / 3f
                        val past = toward >= reach
                        if (past != crossed) {
                            crossed = past
                            if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    }
                } finally {
                    // A reset pointer stream (the row left composition) lands here too: the row
                    // springs home, and `replies` stays false.
                    dragging = false
                }
                if (replies) currentOnReply()
            }
        }
}

/** How far the row travels before a release replies: about a thumb's width, Discord's reach. */
private val SWIPE_REACH = 64.dp
private val ARROW_SIZE = 24.dp
