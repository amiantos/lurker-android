// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.client

import net.amiantos.lurker.model.Buffer
import net.amiantos.lurker.model.ConnectionState
import net.amiantos.lurker.model.Member
import net.amiantos.lurker.model.Message
import net.amiantos.lurker.model.Network

/**
 * A parsed, typed update from the server — REST or WS — for the store to fold in.
 * Parsing the raw JSON into these here keeps the JSON library an implementation
 * detail the store and UI never see.
 */
sealed interface ServerFrame {
    /** REST `GET /api/networks`: the roster (names live here, not in the snapshot). */
    data class Networks(val networks: List<Network>) : ServerFrame

    /** WS `snapshot`: live per-network state + joined channels and their members. */
    data class Snapshot(val networks: List<NetworkSnapshot>) : ServerFrame

    /**
     * WS `backlog`: a buffer, plus its history when [hydrated]. A shell arrives
     * unhydrated with no events (the "fetch on open" marker).
     *
     * [append] distinguishes a `?since=` resume slice that carries only the gap
     * (`reset:false` → append it) from a full/latest backlog or an oversized-gap
     * reset (`reset:true` or no `reset` field → replace wholesale). Getting this
     * wrong silently wipes pre-gap history the moment resume (#5) starts sending
     * `?since`.
     */
    data class Backlog(
        val buffer: Buffer,
        val messages: List<Message>,
        val hydrated: Boolean,
        val append: Boolean = false,
    ) : ServerFrame

    /** WS `irc`: one live event, its fields spread flat on the frame. */
    data class Live(
        val networkId: Int?,
        val target: String,
        val message: Message,
    ) : ServerFrame

    /** WS `send-result`: ack for a send/action/notice, keyed by the client's clientId. */
    data class SendResult(
        val clientId: String?,
        val ok: Boolean,
        val error: String?,
    ) : ServerFrame

    /** WS `error`. */
    data class ServerError(val text: String) : ServerFrame

    /**
     * A 401 mid-session (REST or the WS upgrade): the token expired or was revoked
     * from another device. The owner drops to sign-in rather than a dead-end.
     */
    data object Unauthorized : ServerFrame

    /** Socket opened. Reconnect/resume is #5. */
    data object SocketOpen : ServerFrame

    /** Socket closed or failed. */
    data class SocketClosed(val reason: String?, val code: Int?) : ServerFrame

    /** A frame we parse but the 1.0 foundation doesn't act on yet. */
    data object Ignored : ServerFrame
}

/** The per-network live view from the WS `snapshot` (no `name` — see [ServerFrame.Networks]). */
data class NetworkSnapshot(
    val id: Int,
    val state: ConnectionState,
    val nick: String,
    val channels: List<ChannelSnapshot>,
)

data class ChannelSnapshot(
    val name: String,
    val topic: String?,
    val members: List<Member>,
)
