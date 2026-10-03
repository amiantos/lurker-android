// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.members

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.MemberPrefix
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * Exactly the part of `ChatState` the member list draws from — lurker-ios's `removeDuplicates` on
 * `members[key]` and `ignores`, made a type.
 *
 * ⚠⚠ Compared by IDENTITY ([same]), never by value: a channel's member list is large and the store
 * replaces it only when it changes, so a value comparison would walk hundreds of members on every
 * frame of every buffer to find out nothing happened. A plain class, so Compose's own comparison of
 * two of these is identity too, and [same] is the one real comparison.
 *
 * The ignore set decides who's *listed*, not just who's in the room, so a rule arriving from another
 * device has to wake this screen the same way a join does (`===` is the right test — see
 * `IgnoreSet`). Our own nick is here because `visibleMembers` always lists you, and a `/nick` would
 * otherwise leave the old you filtered by a hostmask rule.
 */
class MemberListInputs private constructor(
    val members: List<Member>?,
    val ignores: IgnoreSet,
    val ownNick: String?,
    private val key: BufferKey,
) {
    /** The members a reader sees: the kit's `visibleMembers`, over exactly what was compared. */
    val visible: List<Member> by lazy {
        val networkId = key.networkId
        ChatState(
            members = members?.let { mapOf(key.id to it) }.orEmpty(),
            ignores = ignores,
            networks = if (networkId != null && ownNick != null) mapOf(networkId to Network(id = networkId, name = null, nick = ownNick)) else emptyMap(),
        ).visibleMembers(key)
    }

    companion object {
        fun of(state: ChatState, key: BufferKey): MemberListInputs =
            MemberListInputs(
                members = state.members[key.id],
                ignores = state.ignores,
                ownNick = key.networkId?.let { state.networks[it]?.nick },
                key = key,
            )

        fun same(old: MemberListInputs, new: MemberListInputs): Boolean =
            old.members === new.members && old.ignores === new.ignores && old.ownNick == new.ownNick
    }
}

/** One member as the list draws it: the rank glyph apart from the nick, so only the glyph is coloured. */
data class MemberRow(
    val nick: String,
    /** `MemberPrefix.of` — "" for a member holding no mode. */
    val prefix: String,
    val away: Boolean,
) {
    /** The list's key: a nick appears once per channel, and folding keeps a case-flip from re-keying it. */
    val id: String get() = nick.lowercase()
}

/**
 * The member list's rules — lurker-ios's `MemberListViewController`, minus the table. Pure, so the
 * sorting, the filter and which empty sentence shows are tested rather than looked at.
 */
object MemberListModel {
    /**
     * Below this, the field is clutter: every nick already fits on a screen or two, and scrolling
     * finds them faster than typing does. Above it, scanning stops working.
     */
    const val SEARCH_THRESHOLD = 20

    const val FILTER_PLACEHOLDER = "Filter members"

    /** Everyone listed, ranked — the kit's order (`MemberPrefix.sorted`): by rank, then by nick. */
    fun rows(visible: List<Member>): List<MemberRow> =
        MemberPrefix.sorted(visible).map { MemberRow(nick = it.nick, prefix = MemberPrefix.of(it.modes), away = it.away) }

    fun title(count: Int): String = if (count == 0) "Members" else "Members ($count)"

    /**
     * The field appears and disappears with the channel's size, so a room that empties out below the
     * threshold stops offering one.
     */
    fun wantsSearch(count: Int): Boolean = count >= SEARCH_THRESHOLD

    /**
     * The query the list filters by. ⚠⚠ Nothing once the field is gone: a netsplit that drops a
     * filtered channel under the threshold takes the field away, and a query left standing would
     * keep the list filtered with nothing on screen to clear it — possibly to "No members match."
     * over a populated channel.
     */
    fun effectiveQuery(query: String, count: Int): String = if (wantsSearch(count)) query else ""

    /**
     * Narrow to what the field asks for. Case-insensitive substring rather than prefix: you rarely
     * remember which end of a nick you know, and a nicklist is short enough that the looser match
     * costs nothing.
     *
     * On the nick only, not on the rank glyph: `@` is a fact about the row, not part of the name, and
     * matching it would make searching for a literal `@` return every operator.
     */
    fun filter(rows: List<MemberRow>, query: String): List<MemberRow> {
        val folded = query.trimmingWhitespacesAndNewlines().lowercase()
        if (folded.isEmpty()) return rows
        return rows.filter { it.nick.lowercase().contains(folded) }
    }

    /**
     * Says which of the reasons the list is empty, because they need different things from the
     * reader: a filter that matched nothing is "type less", a channel with no members is "wait", and
     * a DM has nobody to list and never will.
     */
    fun emptyText(kind: BufferKind, searching: Boolean): String {
        if (searching) return "No members match."
        return when (kind) {
            BufferKind.Channel -> "No members yet."
            BufferKind.Dm -> "Direct messages have no member list."
            BufferKind.Dcc -> "A DCC chat has no member list."
            BufferKind.Server, BufferKind.System -> "This buffer has no member list."
        }
    }

    /**
     * What TalkBack reads for a row — the rank in words rather than "at alice", and "away" because
     * the dimming that says it on screen says nothing aloud. iOS reads the glyph; a screen reader
     * pronouncing `@` and `%` is noise, so this says what they mean.
     */
    fun accessibilityLabel(row: MemberRow): String {
        val rank = when (row.prefix) {
            "~" -> "owner"
            "&" -> "admin"
            "@" -> "operator"
            "%" -> "half-operator"
            "+" -> "voiced"
            else -> null
        }
        return buildString {
            append(row.nick)
            if (rank != null) append(", ").append(rank)
            if (row.away) append(", away")
        }
    }

    /** The long-press menu's one item — iOS's, which a friend being a favorited DM decides. */
    fun friendActionTitle(isFriend: Boolean): String = if (isFriend) "Remove from Friends" else "Add to Friends"
}
