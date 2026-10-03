// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.bufferinfo

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.DccChat

/** The store rekeyed a buffer: [from] is now [to] (`AppEvent.BufferRenamed`). */
data class BufferRename(val from: BufferKey, val to: BufferKey)

/**
 * What a rename means for an open buffer dialog — which of its pages, and its saved request, now
 * describe a different name. Pure, so the rules (keys fold case, a DM's rename is its peer's nick
 * change, another network's rename touches nothing) are tested rather than looked at.
 */
internal object DialogRenames {
    /** A page about a buffer, by key: the new key when it's the renamed buffer, else null. */
    fun key(key: BufferKey, rename: BufferRename): BufferKey? = if (key.id == rename.from.id) rename.to else null

    /**
     * A page about a person (a profile, a note): the new nick when the rename is that person's DM or DCC
     * chat changing name — their nick change — else null. A channel's rename is no one's nick.
     */
    fun nick(networkId: Int, nick: String, rename: BufferRename): String? {
        val from = rename.from
        if (from.networkId != networkId) return null
        when (BufferKind.of(networkId = from.networkId, target = from.target)) {
            BufferKind.Dm, BufferKind.Dcc -> Unit
            BufferKind.Channel, BufferKind.Server, BufferKind.System -> return null
        }
        if (DccChat.peer(from.target).lowercase() != nick.lowercase()) return null
        return DccChat.peer(rename.to.target)
    }

    /** The dialog's saved request, followed: null when the rename isn't about what it was opened for. */
    fun request(request: BufferSheetRequest, rename: BufferRename): BufferSheetRequest? =
        when (request.start) {
            BufferSheetStart.Profile -> {
                val networkId = request.networkId ?: return null
                nick(networkId, request.target, rename)?.let { request.copy(target = it) }
            }
            BufferSheetStart.Members, BufferSheetStart.Info ->
                key(request.key, rename)?.let { request.copy(networkId = it.networkId, target = it.target) }
        }
}
