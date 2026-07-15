// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.model

/**
 * An IRC network the account is configured on. `name` comes from REST
 * (`GET /api/networks`); the live `state`/`nick` come from the WS `snapshot` and
 * are merged in — the snapshot itself carries no name.
 */
data class Network(
    val id: Int,
    val name: String,
    val state: ConnectionState = ConnectionState.Disconnected,
    val nick: String = "",
)

/** Mirrors the server's per-network `state` string. */
enum class ConnectionState {
    Connecting,
    Connected,
    Reconnecting,
    Disconnected;

    companion object {
        fun from(raw: String?): ConnectionState =
            when (raw) {
                "connecting" -> Connecting
                "connected" -> Connected
                "reconnecting" -> Reconnecting
                else -> Disconnected
            }
    }
}
