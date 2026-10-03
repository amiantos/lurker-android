// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import net.amiantos.lurkerkit.model.BufferKey

/**
 * What the kit asks of the screen, on its way from `LurkerApp` (which wires the view model's
 * callbacks, and outlives every activity) to `MainScaffold` (which owns the navigator, and lives in
 * composition). lurker-ios's `SceneDelegate` calls straight into its navigation; here the two ends
 * don't share a lifetime, so the events queue between them.
 */
sealed interface AppEvent {
    /** A join or a DCC chat this device asked for has its buffer — go there (lurker-ios#57). */
    data class OpenBuffer(val key: BufferKey) : AppEvent

    /** A one-line notice over whatever is on screen — a join that didn't happen says why. */
    data class Notice(val message: String) : AppEvent

    /**
     * The store rekeyed a buffer (a nick change in a DM, a channel rename). Whatever the navigator
     * holds under [from] has to follow it to [to].
     */
    data class BufferRenamed(val from: BufferKey, val to: BufferKey) : AppEvent
}

/**
 * The queue itself. A channel rather than a shared flow because nothing may be dropped for want of a
 * collector: a rename that lands while the activity is being recreated still has to reach the
 * navigator, and a join's notice still has to be read. One collector at a time — `MainScaffold`.
 */
class AppEvents {
    private val channel = Channel<AppEvent>(Channel.UNLIMITED)

    val events: Flow<AppEvent> = channel.receiveAsFlow()

    fun send(event: AppEvent) {
        channel.trySend(event)
    }

    /**
     * Sign-out: whatever is still queued belongs to the previous session, and must not open a
     * buffer or read out a notice in the next one's.
     */
    fun drain() {
        while (channel.tryReceive().isSuccess) Unit
    }
}
