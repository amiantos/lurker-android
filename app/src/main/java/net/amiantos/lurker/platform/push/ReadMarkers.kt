// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.store.ChatState

/**
 * Whether the message a push was about has been read (lurker-android#16).
 *
 * Android has no app-icon number to set: a launcher badges from the app's notifications. So a
 * notification left in the shade after its message is read — here, on another device, by "mark all as
 * read" — keeps the icon counting something nobody has left to read. iOS sets the number from the
 * count itself (`AppBadge`); here each notification has to come down once its message is read.
 *
 * Asked of the buffer's read pointer, not its unread count: a push names its message, and the pointer
 * passing it is the read. That holds whenever the store learned it — a read on another device while
 * this phone was offline lands with the next connect's state — and reading past a mention clears it
 * even with later lines still unread. And the pointer only moves forward, so a stale state (the one a
 * launch or a reconnect starts from) can only leave a notification up, never take a fresh one down.
 */
object ReadMarkers {
    fun readPast(state: ChatState, networkId: Int, target: String, messageId: Long): Boolean {
        val buffer = state.buffers[BufferKey(networkId, target).id] ?: return false
        // A pointer the server never stated reads 0, which is "nothing read", not a read.
        return buffer.readStateKnown && buffer.lastReadId >= messageId
    }
}
