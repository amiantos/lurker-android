// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

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
) {
    /**
     * This member's `nick!user@host`, when the server sent both halves — the form an ignore
     * rule's mask is matched against.
     *
     * Null unless both are present: a half-mask would be matched against a rule's `user` and
     * `host` globs as if the missing half were empty, quietly failing a rule that would have
     * matched. Absent is the honest answer, and the matcher already knows what to do with it.
     * Same rule the web applies inline in its nicklist filter (`MemberList.vue:173`).
     */
    val userhost: String?
        get() {
            if (user == null || host == null || user.isEmpty() || host.isEmpty()) return null
            return "$nick!$user@$host"
        }
}
