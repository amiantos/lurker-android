// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.amiantos.lurker.client.ServerFrame
import net.amiantos.lurker.model.Buffer
import net.amiantos.lurker.model.BufferKey
import net.amiantos.lurker.model.BufferKind
import net.amiantos.lurker.model.Member
import net.amiantos.lurker.model.Message
import net.amiantos.lurker.model.Network

/** Immutable snapshot of everything the chat UI renders. Maps are keyed by [BufferKey.id]. */
data class ChatState(
    val connected: Boolean = false,
    val networks: Map<Int, Network> = emptyMap(),
    val buffers: Map<String, Buffer> = emptyMap(),
    val messages: Map<String, List<Message>> = emptyMap(),
    val members: Map<String, List<Member>> = emptyMap(),
    val error: String? = null,
)

/**
 * Holds the domain state and folds [ServerFrame]s into it. Pure state — no I/O,
 * no Android, fully unit-testable. Updates are atomic via [MutableStateFlow.update],
 * so frames arriving on OkHttp threads are safe without marshalling.
 */
class LurkerStore {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    fun reset() {
        _state.value = ChatState()
    }

    fun apply(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Networks -> applyNetworks(frame)
            is ServerFrame.Snapshot -> applySnapshot(frame)
            is ServerFrame.Backlog -> applyBacklog(frame)
            is ServerFrame.Live -> applyLive(frame)
            is ServerFrame.ServerError -> _state.update { it.copy(error = frame.text) }
            is ServerFrame.SendResult ->
                _state.update { if (frame.ok) it else it.copy(error = frame.error ?: "Send failed") }
            ServerFrame.SocketOpen -> _state.update { it.copy(connected = true, error = null) }
            is ServerFrame.SocketClosed -> _state.update { it.copy(connected = false) }
            ServerFrame.Ignored -> Unit
        }
    }

    private fun applyNetworks(frame: ServerFrame.Networks) = _state.update { s ->
        // Merge REST names in without clobbering any live state the snapshot set.
        val merged = frame.networks.associate { n ->
            n.id to (s.networks[n.id]?.copy(name = n.name) ?: n)
        }
        s.copy(networks = s.networks + merged)
    }

    private fun applySnapshot(frame: ServerFrame.Snapshot) = _state.update { s ->
        val networks = s.networks.toMutableMap()
        val buffers = s.buffers.toMutableMap()
        val members = s.members.toMutableMap()
        for (ns in frame.networks) {
            networks[ns.id] = networks[ns.id]?.copy(state = ns.state, nick = ns.nick)
                ?: Network(ns.id, name = "network", state = ns.state, nick = ns.nick)
            for (channel in ns.channels) {
                val key = BufferKey(ns.id, channel.name).id
                buffers[key] = (buffers[key] ?: Buffer(ns.id, channel.name, BufferKind.Channel))
                    .copy(joined = true)
                members[key] = channel.members
            }
        }
        s.copy(networks = networks, buffers = buffers, members = members)
    }

    private fun applyBacklog(frame: ServerFrame.Backlog) = _state.update { s ->
        val key = frame.buffer.key.id
        // Never un-hydrate: a later shell for an already-read buffer keeps its history.
        val alreadyHydrated = s.buffers[key]?.hydrated == true
        val buffer = frame.buffer.copy(hydrated = frame.hydrated || alreadyHydrated)
        val messages = when {
            frame.hydrated -> s.messages + (key to frame.messages) // hydrated frame replaces
            s.messages.containsKey(key) -> s.messages // shell: keep what we have
            else -> s.messages + (key to emptyList())
        }
        s.copy(buffers = s.buffers + (key to buffer), messages = messages)
    }

    private fun applyLive(frame: ServerFrame.Live) = _state.update { s ->
        val key = BufferKey(frame.networkId, frame.target).id
        val existing = s.messages[key] ?: emptyList()
        // De-dupe against backlog/live overlap by persisted id; id 0 is ephemeral
        // and always appended.
        if (frame.message.id != 0L && existing.any { it.id == frame.message.id }) return@update s
        s.copy(messages = s.messages + (key to existing + frame.message))
    }
}
