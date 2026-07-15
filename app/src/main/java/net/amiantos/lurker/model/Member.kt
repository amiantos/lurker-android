// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.model

/**
 * A channel member. `modes` are prefix-mode letters (q a o h v), highest first —
 * NOT the sigil symbols (~ & @ % +). Matches the server's ChannelMember.
 */
data class Member(
    val nick: String,
    val modes: List<String> = emptyList(),
    val away: Boolean = false,
    val user: String? = null,
    val host: String? = null,
)
