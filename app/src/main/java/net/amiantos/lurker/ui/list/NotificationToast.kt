// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.amiantos.lurker.ui.composer.toastText
import net.amiantos.lurker.ui.message.rememberMessageTextStyle
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.theme.monoTextStyle
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.StatusNotification
import net.amiantos.lurkerkit.model.StatusToast
import java.time.Instant

/**
 * The buffer list's in-app notification (lurker#1098): a capsule along the bottom saying what the
 * chat screen's status row would — "bob: are you around?" in the message list's face — and going to
 * the line when tapped. The list has no composer to carry a status row, so it floats instead.
 * lurker-ios's `NotificationToastView`.
 *
 * Unlike the connection banner it takes a touch, since going there is the point of it; it's up for a
 * few seconds at a time (`StatusToastPresenter`), and the row it covers is still a scroll away. Drawn
 * after the list in its `Box`, so it's on top and the tap is its own, not the row's underneath.
 *
 * Shows [toast], or slides away at null; the words stay through the exit.
 */
@Composable
internal fun NotificationToast(toast: StatusToast?, onTap: () -> Unit, modifier: Modifier = Modifier) {
    // What it last said, kept through the exit so the words don't vanish before the capsule does.
    var lastShown by remember { mutableStateOf(toast) }
    SideEffect { if (toast != null) lastShown = toast }
    val words = toast ?: lastShown
    // Rises into place from just below, rather than settling in place like the banner up top.
    val rise = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(
        visible = toast != null,
        modifier = modifier,
        enter = fadeIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { rise },
        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { rise },
    ) {
        if (words != null) ToastCapsule(words, onTap)
    }
}

@Composable
private fun ToastCapsule(toast: StatusToast, onTap: () -> Unit) {
    val style = rememberMessageTextStyle()
    val text = toastText(toast, style)
    Box(
        Modifier
            .shadow(3.dp, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            // The capsule appearing isn't announced on its own.
            .clearAndSetSemantics {
                contentDescription = text.text
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
                onClick(label = "Open the conversation") { onTap(); true }
            },
    ) {
        Text(text, style = monoTextStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Previews

private val previewToast = StatusToast.Notification(
    StatusNotification(
        kind = StatusNotification.Kind.Highlight, key = BufferKey(networkId = 1, target = "#android"), nick = "bob",
        text = "are you around?", messageId = 1, date = Instant.EPOCH,
    ),
)
private val previewOnline = StatusToast.Notification(
    StatusNotification(
        kind = StatusNotification.Kind.FriendOnline, key = BufferKey(networkId = 1, target = "alice"), nick = "alice",
        text = "", messageId = 0, date = Instant.EPOCH,
    ),
)

@Composable
private fun ToastPreview(dark: Boolean) {
    LurkerTheme(darkTheme = dark) {
        Column(Modifier.background(LurkerTheme.colors.bg).padding(16.dp)) {
            ToastCapsule(previewToast, onTap = {})
            Box(Modifier.padding(top = 12.dp)) { ToastCapsule(previewOnline, onTap = {}) }
        }
    }
}

@Preview(name = "List toast — light")
@Composable
private fun ToastPreviewLight() = ToastPreview(dark = false)

@Preview(name = "List toast — dark")
@Composable
private fun ToastPreviewDark() = ToastPreview(dark = true)
