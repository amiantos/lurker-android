// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.AppEvents
import net.amiantos.lurker.platform.LocalToastCenter
import net.amiantos.lurker.platform.ToastCenter
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.StatusNotification
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * A screen that shows in-app notifications (lurker#1098): registered with the app's `ToastCenter`
 * for as long as it's composed, and asked only while [visible] — the conversation's status row and
 * the buffer list's capsule share this, so the two can't drift on how a surface comes and goes.
 *
 * @param visible whether a toast shown here could be seen right now — the screen is the destination,
 *   started, with nothing over it. Read at each ask, not captured.
 * @param showsBuffer whether this surface is showing [BufferKey] in plain view — what keeps a
 *   notification for it from toasting anywhere. The list shows no buffer.
 * @param show put the notification up.
 * @return whether the screen is between started and stopped, for the caller's own gates.
 */
@Composable
internal fun rememberToastSurface(
    visible: () -> Boolean,
    showsBuffer: (BufferKey) -> Boolean = { false },
    show: (StatusNotification) -> Unit,
): Boolean {
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }
    val currentVisible by rememberUpdatedState(visible)
    val currentShowsBuffer by rememberUpdatedState(showsBuffer)
    val currentShow by rememberUpdatedState(show)
    val center = LocalToastCenter.current
    DisposableEffect(center) {
        val unregister = center?.register(
            object : ToastCenter.Surface {
                override fun showsBuffer(key: BufferKey): Boolean = currentShowsBuffer(key)
                override fun take(notification: StatusNotification): Boolean {
                    // Not into a row or a list a dialog is covering: it would expire there unseen.
                    if (!currentVisible()) return false
                    currentShow(notification)
                    return true
                }
            },
        )
        onDispose { unregister?.invoke() }
    }
    return started
}

/**
 * Where an in-app notification goes when tapped: its line, or for a friend coming online, the
 * conversation with them — through `MainScaffold.open`, like every other way in (lurker-ios's
 * `showNotification`).
 *
 * ⚠ A friend's DM is often not a materialized buffer: a DM closed server-side has no row, and a
 * conversation opened on a settled roster without one backs straight out to the list. So, as the
 * Friends row does, an absent row is asked for with `open-buffer` and the landing is the navigation
 * (`AppEvent.OpenBuffer` from the kit, lurker-ios#201). A line's buffer always has its row.
 */
internal fun openNotification(model: ChatViewModel, events: AppEvents?, notification: StatusNotification) {
    val key = notification.key
    if (model.state.buffers[key.id] == null) {
        model.openAndShow(key)
    } else {
        events?.send(AppEvent.OpenBuffer(key, jumpTo = notification.messageId.takeIf { it > 0 }))
    }
}
