// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.model.BufferKey

/**
 * What the buffer list hands the detail pane: a buffer's address, as the list–detail navigator's
 * content key.
 *
 * `Serializable` because the navigator saves its destination history in a Bundle, so the buffer
 * you were reading survives rotation and process death; a `data class` of two primitives needs no
 * Parcelize plugin to get there.
 *
 * Its parts, never a `BufferKey.id`: `id` lower-cases the target, and this is what the
 * conversation is opened from — before any frame has arrived to correct the case, the same reason
 * `UiPreferences.lastOpenBufferKey` stores parts. Compare two routes by [key]'s `id`.
 */
data class BufferRoute(
    /** Null only for the system buffer. */
    val networkId: Int?,
    val target: String,
) : java.io.Serializable {
    val key: BufferKey get() = BufferKey(networkId = networkId, target = target)

    companion object {
        private const val serialVersionUID = 1L

        fun of(key: BufferKey): BufferRoute = BufferRoute(networkId = key.networkId, target = key.target)
    }
}
