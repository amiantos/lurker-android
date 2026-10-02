// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/** One nick marked as a relay/bridge bot on a network (lurker#277). */
data class RelayBot(
    /**
     * The nick in its stored casing. Lookups fold case, so this is only what to *show* — in
     * `/relay list`, and in the "via" line on a re-attributed message's action sheet.
     */
    val nick: String,
    /**
     * The custom envelope template, or `""` for "use the built-in formats"
     * (`RelayEnvelope.defaultPatterns`), which is what the server stores for a bare mark.
     */
    val pattern: String = "",
)

/**
 * The account's relay-bot marks, per network (lurker#277).
 *
 * Marking a nick says "this is a bridge: the lines it posts are other people speaking". The
 * client then parses each of its messages and shows the embedded speaker as the author — see
 * `reattributing(messages, networkId)`, which is the whole of what a mark *does*.
 *
 * Network-scoped, because the same nick on two networks may be unrelated bots — the same keying
 * the server uses, and the same one nick notes and ignores use.
 *
 * Server-authoritative, exactly like `IgnoreSet`: `/relay` here (and the web's `/relay`, and its
 * user-profile toggle) *ask*, and the mark exists once the server fans a `relay-bot-updated`
 * back to every device. **Nothing writes to this type but a frame** — it is replaced whole by
 * `snapshot`/`relay-bot-updated`, never mutated in place by the command that caused the change,
 * so a mark the server refuses simply never appears.
 *
 * **Because it is only ever replaced, `===` is a valid test for "the marks changed."** On iOS
 * that's what lets `ChatViewController`'s `removeDuplicates` predicate compare it with a pointer
 * test rather than walking every mark on every frame the socket delivers.
 *
 * Port note: a reference type in LurkerKit (a `final class`, not a struct) with no `==` of its
 * own. Here it sits in published state, where a `StateFlow` and Compose compare with `equals`,
 * so it is immutable and compares ALL its marks: two sets holding the same marks are `==`.
 * `===` remains a valid — and cheaper — "did the marks change" test for a caller that wants one,
 * for the reason above.
 */
class RelayBotSet(byNetwork: Map<Int, List<RelayBot>> = emptyMap()) {
    /**
     * networkId → folded nick → mark. Presence of a key IS the mark; the value carries only what
     * the mark is *for* (display casing and the template).
     *
     * Port note: folded with `lowercase()`, which gives the string LurkerKit's `lowercased()`
     * does except for the final-sigma rule (see `BufferKey.id`): a mark on `ΟΔΟΣ` answers for
     * `οδοσ` on iOS and for `οδος` here. Checked against the Swift over a corpus; nothing else
     * in the fold differs.
     */
    private val byNetwork: Map<Int, Map<String, RelayBot>> = buildMap {
        for ((networkId, entries) in byNetwork) {
            val bots = entries.filter { it.nick.isNotEmpty() }
            if (bots.isEmpty()) continue
            put(networkId, bots.associateBy { it.nick.lowercase() })
        }
    }

    /**
     * Whether `nick` is marked on `networkId`. A null network is the app-scoped system buffer,
     * where there are no relays to speak of.
     */
    fun isRelay(networkId: Int?, nick: String?): Boolean =
        bot(networkId = networkId, nick = nick) != null

    /** The mark for `nick` on `networkId`, or null when there isn't one. */
    fun bot(networkId: Int?, nick: String?): RelayBot? {
        if (networkId == null || nick == null || nick.isEmpty()) return null
        return byNetwork[networkId]?.get(nick.lowercase())
    }

    /**
     * The marks on `networkId`, for `/relay list`. Sorted by folded nick so the numbering a user
     * reads off one listing is the numbering they get from the next — a map's order is
     * not, and the list is an inventory people re-read.
     *
     * Port note: the folded nicks compare by UTF-16 unit, where Swift's `<` compares by
     * Unicode scalar over canonically equivalent forms — the same difference, at the same two
     * edges, as `MemberPrefix.sorted`'s.
     */
    fun listing(networkId: Int?): List<RelayBot> {
        if (networkId == null) return emptyList()
        val bots = byNetwork[networkId] ?: return emptyList()
        return bots.values.sortedWith { lhs, rhs -> lhs.nick.lowercase().compareTo(rhs.nick.lowercase()) }
    }

    /**
     * This set with one mark set or cleared — how a `relay-bot-updated` frame folds in.
     *
     * The frame carries one nick, not a network's whole list, so this patches rather than
     * replaces a bucket (which is where it differs from `IgnoreSet.replacing`, whose frame ships
     * a scope at a time). `nick` is the server's canonical casing on a mark; on a clear it's
     * whatever was asked for, and casing is moot once the key is gone.
     */
    fun applying(networkId: Int, nick: String, marked: Boolean, pattern: String): RelayBotSet {
        if (nick.isEmpty()) return this
        val bots = (byNetwork[networkId]?.values?.toMutableList() ?: mutableListOf())
        bots.removeAll { it.nick.lowercase() == nick.lowercase() }
        if (marked) bots.add(RelayBot(nick = nick, pattern = pattern))
        val next = byNetwork.mapValues { it.value.values.toList() }.toMutableMap()
        next[networkId] = bots
        return RelayBotSet(byNetwork = next)
    }

    /**
     * `messages` with every line from a marked bot re-attributed to the speaker its envelope
     * names. Lines from unmarked nicks, and lines from a marked bot whose envelope doesn't parse,
     * come back untouched.
     *
     * **Display-only, and applied at render time rather than on the way into the store.** The
     * stored row keeps the bot's nick and its full text, so unmarking restores the raw view with
     * no refetch — the same property that makes ignore rules retroactive in both directions, and
     * the reason this is a transform over the list the screen is about to draw rather than a
     * rewrite of what arrived.
     *
     * Restricted to plain messages: relays bridge speech as PRIVMSG, and re-attributing an action
     * or a notice would tangle with the special body rendering those get. Self lines are excluded
     * because you are not a bridge — and if you were, the envelope would be one you typed.
     *
     * Highlights and ignores have already run against the raw line by the time this does, which
     * is correct in both directions: the bot's full text is a superset of the embedded text, so a
     * ping inside a relayed message still fires, and an ignore on the bot still hides all of it.
     */
    fun reattributing(messages: List<Message>, networkId: Int?): List<Message> {
        if (networkId == null) return messages
        val bots = byNetwork[networkId]
        if (bots == null || bots.isEmpty()) return messages
        // Compiled once per call and shared by every row, which is the point of doing this over a
        // list instead of per message: a busy relay channel is dozens of rows off one template.
        val compiled = mutableMapOf<String, List<RelayTemplate>>()
        return messages.map { message ->
            if (message.type != EventType.Message || message.isSelf) return@map message
            val nick = message.nick ?: return@map message
            val bot = bots[nick.lowercase()] ?: return@map message
            fun templates(pattern: String): List<RelayTemplate> {
                compiled[pattern]?.let { return it }
                val built = RelayEnvelope.templates(pattern)
                compiled[pattern] = built
                return built
            }
            // Chained bridges (lurker#801): keep unwrapping while the speaker a hop reveals is
            // itself marked, so a relay of a relay lands on the person who spoke rather than on
            // the bridge in between. `via` stays the OUTER bot — that's the only real IRC entity
            // in the chain, and it's what View Profile and the sheet's "via" line need.
            val parsed = RelayEnvelope.parseChain(
                message.text,
                templates = templates(bot.pattern),
                nextHop = { revealed -> bots[revealed.lowercase()]?.let { templates(it.pattern) } },
            ) ?: return@map message
            message.relayed(
                speaker = parsed.nick, text = parsed.text, bot = bot.nick, source = parsed.source,
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is RelayBotSet && byNetwork == other.byNetwork)

    override fun hashCode(): Int = byNetwork.hashCode()

    override fun toString(): String = "RelayBotSet(byNetwork=$byNetwork)"

    companion object {
        /**
         * No marks at all — a fresh session, a signed-out one, and every buffer on a client whose
         * user has never marked anything, which is nearly all of them.
         */
        val empty = RelayBotSet()
    }
}
