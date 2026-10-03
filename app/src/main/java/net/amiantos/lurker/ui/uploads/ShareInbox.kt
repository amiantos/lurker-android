// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * What another app shared to Lurker through the system share sheet (`ACTION_SEND`,
 * `ACTION_SEND_MULTIPLE`): files to upload, text for the composer, or both — a photo with a caption.
 *
 * @property streams the shared files' `content://` addresses, as `AttachmentSource.Content` takes them.
 * @property text the shared text (`EXTRA_TEXT`) — a link from a browser, a quote — or null.
 */
data class SharePayload(val streams: List<String>, val text: String?) {
    /** Nothing worth asking about: no file, and no text but whitespace. */
    val isEmpty: Boolean get() = streams.isEmpty() && text.isNullOrEmpty()

    companion object {
        /** Built from the intent's parts; blank text is no text. */
        fun of(streams: List<String>, text: String?): SharePayload =
            SharePayload(streams.distinct(), text?.takeIf { it.trimmingWhitespacesAndNewlines().isNotEmpty() })
    }
}

/**
 * The share waiting for the reader to say which conversation it goes to. App-long, so a share that
 * arrives while signed out **waits for the sign-in** rather than being dropped: the scaffold asks as
 * soon as it's up. A sign-out drops it — the next account isn't who the share was meant for.
 *
 * One at a time: a second share replaces the first, which is what the newest intent from the system
 * means.
 */
class ShareInbox {
    private val pending = MutableStateFlow<SharePayload?>(null)

    val share: StateFlow<SharePayload?> = pending.asStateFlow()

    fun receive(payload: SharePayload) {
        if (!payload.isEmpty) pending.value = payload
    }

    /** The reader picked a conversation (or dismissed the picker): it's handled. */
    fun take(): SharePayload? = pending.value.also { pending.value = null }

    fun clear() {
        pending.value = null
    }
}

/**
 * Which buffers take uploads at all: the conversations — channels, DMs and DCC chats. Not a server log
 * or the Lurker buffer, which take commands, not files. One rule for every place it's asked — the share
 * picker's list, the paperclip, where a finished link may land, and Add to Message — so a buffer can't
 * be offered as a destination by one and refused by another.
 */
object UploadTargets {
    fun takes(kind: BufferKind): Boolean = kind == BufferKind.Channel || kind == BufferKind.Dm || kind == BufferKind.Dcc

    fun takes(key: BufferKey): Boolean = takes(BufferKind.of(networkId = key.networkId, target = key.target))
}

/**
 * The share picker's list: every conversation a share can land in ([UploadTargets]), under its network.
 *
 * Only what the picker draws — names and keys, never unread counts — so a list built from every state
 * frame compares equal until a conversation actually comes or goes.
 */
object ShareTargets {
    /** One conversation: what it opens, and what it's called. */
    data class Target(val key: BufferKey, val name: String)

    data class Section(val title: String, val targets: List<Target>)

    fun sections(networks: Map<Int, Network>, buffers: Collection<Buffer>): List<Section> {
        val takers = buffers.filter { UploadTargets.takes(it.kind) }
        return takers
            .groupBy { it.networkId }
            .entries
            // The user's own network order, as the buffer list keeps it; a network the roster hasn't
            // described sorts last (its position is `Int.MAX_VALUE`), then by id for a stable order.
            .sortedWith(compareBy({ it.key?.let { id -> networks[id]?.position } ?: Int.MAX_VALUE }, { it.key ?: Int.MAX_VALUE }))
            .map { (networkId, members) ->
                val title = networkId?.let { networks[it]?.displayName } ?: Network.unnamedDisplayName
                val targets = members
                    .sortedWith(compareBy({ kindOrder(it.kind) }, { it.target.lowercase() }))
                    .map { Target(it.key, it.displayName()) }
                Section(title, targets)
            }
    }

    /** Channels, then the people you talk to. */
    private fun kindOrder(kind: BufferKind): Int =
        when (kind) {
            BufferKind.Channel -> 0
            BufferKind.Dm -> 1
            else -> 2
        }
}
