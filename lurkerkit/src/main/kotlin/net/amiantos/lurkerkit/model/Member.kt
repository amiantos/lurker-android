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

/**
 * The entry for `nick`, folding case — the one place "which of these is me?" is asked
 * (`ChatState.channelAccess`, the composer's prompt). Null for an empty nick, and before
 * NAMES lands.
 *
 * Runs on every state frame (the composer's prompt), so the exact spelling is tried first:
 * the server lists us as it knows us, which is how `Network.nick` has it too, and that
 * match costs no allocation. The fold is the fallback, not the path.
 *
 * ⚠ No length shortcut in front of the fold. Lowercasing can change a nick's length (`İ` is
 * one UTF-16 unit, its fold two), so one would turn away the very member it's looking for.
 */
fun List<Member>.member(named: String): Member? {
    if (named.isEmpty()) return null
    firstOrNull { it.nick == named }?.let { return it }
    val folded = named.lowercase()
    return firstOrNull { it.nick.lowercase() == folded }
}
