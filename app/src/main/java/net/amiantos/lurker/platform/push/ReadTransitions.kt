// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import net.amiantos.lurkerkit.store.ChatState

/**
 * Which buffers were just read, so their notifications can go (lurker-android#16).
 *
 * Android has no app-icon number to set: a launcher badges from the app's notifications. So a
 * notification left in the shade after its buffer is read — here, on another device, or with "mark
 * all as read" — keeps the icon counting something nobody has left to read. iOS clears the number
 * from the count itself (`AppBadge`); here the notifications have to follow the reading.
 *
 * Only the TRANSITION from unread to read counts, never "read now": the state a launch or a reconnect
 * starts from can be a leftover from before the push arrived, and reading "0 unread" there would
 * clear a notification for a message the store hasn't heard of yet. And only between settled states
 * ([ChatState.rosterSettled]), so a snapshot half-applied can't look like a buffer read.
 */
class ReadTransitions {
    private var unread: Map<String, Int>? = null

    /** The ids (`BufferKey.id`) of buffers that went from unread to read since the last settled state. */
    fun observe(state: ChatState): Set<String> {
        if (!state.rosterSettled) return emptySet()
        val now = state.buffers.mapValues { (_, buffer) -> buffer.unread }
        val before = unread
        unread = now
        if (before == null) return emptySet()
        return before.filter { (id, count) -> count > 0 && now[id] == 0 }.keys
    }

    /** Sign-out: the next session starts from nothing. */
    fun reset() {
        unread = null
    }
}
