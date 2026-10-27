// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.rendering.NickColor
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * One nick's IRCv3 metadata as the server shows it (lurker#607, Mobile lurker#1203): the
 * `userMetadata` record in a network snapshot, and the `values` of a `user-metadata` event.
 *
 * Every field is text another user controls. The rules for showing it are in
 * `ChatState.displayName(networkId, nick)` and the plan; this type only carries it.
 */
data class UserMetadata(
    /**
     * The `display-name` key, raw — mIRC codes and all. The head renders them; plain surfaces
     * strip them (`UserMetadata.shown(raw)`).
     */
    val displayName: String? = null,
    /**
     * ⚠ Server-minted and opaque: the media proxy route or a CDN URL, never the origin the user
     * set. Null when the server can't serve one (previews off), which means "draw the generated
     * tile" — the same as having no avatar at all, so null and absent collapse here on purpose.
     */
    val avatarUrl: String? = null,
    val pronouns: String? = null,
    /** Already filtered to http(s) by the server (`ircMetadata.ts`), so a value here is a link. */
    val homepage: String? = null,
    val status: String? = null,
) {
    /**
     * A record with nothing to show. The server sends `null` to forget a nick rather than an
     * empty record, but a record that is all nulls is the same statement.
     */
    val isEmpty: Boolean
        get() = displayName == null && avatarUrl == null && pronouns == null && homepage == null && status == null

    companion object {
        /**
         * The display name as a plain surface prints it: mIRC codes stripped, whitespace trimmed,
         * null when nothing is left. A name that is all colour codes is not a name — the web's
         * `useDisplayNames` applies the same test before it shows anything.
         */
        fun shown(raw: String?): String? {
            if (raw == null) return null
            val plain = IRCFormatting.strip(raw).trimmingWhitespacesAndNewlines()
            return if (plain.isEmpty()) null else plain
        }
    }
}

/**
 * What a network said it can carry (`draft/metadata-2`'s cap values), from the snapshot's
 * `metadataLimits`. Null on `Network` means the network has no metadata at all — an older
 * server, or one whose IRC server never advertised the cap. Edit Profile (lurker#1204) gates on
 * this; nothing in the display half reads it.
 */
data class MetadataLimits(
    val maxSubs: Int?,
    /** `max-keys`. ⚠ Zero is soju saying "I relay nothing" — present, but nothing can be set. */
    val maxKeys: Int?,
    val maxValueBytes: Int?,
    val beforeConnect: Boolean,
)

/**
 * Every nick's metadata the server holds, per network (lurker#1203).
 *
 * Keyed `networkId → folded nick → record`, the way the server keys its own map, and folded the
 * way the web's `toLowerCase()` folds (`NickColor.lowercasedLikeTheWeb`), so a snapshot key the
 * server lowered and a lookup this client lowers meet on a Greek nick too.
 *
 * Immutable and only ever replaced — by the snapshot wholesale, by `user-metadata` one nick at
 * a time — so `===` is a valid test for "the metadata changed", which is what lets the chat
 * screen's `removeDuplicates` compare it with a pointer test on every frame. Same contract as
 * `RelayBotSet`.
 *
 * A rename needs nothing here: the server sends the old nick's `null`, then the new nick's
 * record (CLIENT_PROTOCOL.md, `user-metadata`).
 *
 * Port note: a reference type in LurkerKit (a `final class`) with no `==` of its own. Here it
 * sits in published state, where a `StateFlow` and Compose compare with `equals`, so it is
 * immutable and compares ALL its records: two sets holding the same records are `==`. `===`
 * remains a valid — and cheaper — "did the metadata change" test for a caller that wants one,
 * for the reason above (see `RelayBotSet`'s note).
 */
class UserMetadataSet private constructor(
    byNetwork: Map<Int, Map<String, UserMetadata>>,
    /**
     * Port note: selects between LurkerKit's two initialisers, which share a parameter type and
     * so can't both be constructors here — `init(byNetwork:)` folds (`false`), the private
     * `init(folded:)` takes the map as it stands (`true`, reached through [folded]).
     */
    alreadyFolded: Boolean,
) {
    private val byNetwork: Map<Int, Map<String, UserMetadata>> =
        if (alreadyFolded) {
            byNetwork
        } else {
            buildMap {
                for ((networkId, records) in byNetwork) {
                    val folded = LinkedHashMap<String, UserMetadata>()
                    for ((nick, record) in records) {
                        if (nick.isEmpty()) continue
                        folded[fold(nick)] = record
                    }
                    if (folded.isEmpty()) continue
                    put(networkId, folded)
                }
            }
        }

    /** `byNetwork`'s nick keys are folded here, so a caller may pass them as the wire had them. */
    constructor(byNetwork: Map<Int, Map<String, UserMetadata>> = emptyMap()) : this(byNetwork, alreadyFolded = false)

    /**
     * The record for `nick` on `networkId`, or null. A null network is the app-scoped system
     * buffer, where nobody has metadata.
     */
    fun record(networkId: Int?, nick: String?): UserMetadata? {
        if (networkId == null || nick == null || nick.isEmpty()) return null
        return byNetwork[networkId]?.get(fold(nick))
    }

    /**
     * This set with one nick's record replaced whole, or forgotten on null — how a
     * `user-metadata` event folds in.
     */
    fun applying(networkId: Int, nick: String, values: UserMetadata?): UserMetadataSet {
        if (nick.isEmpty()) return this
        val next = LinkedHashMap(byNetwork)
        val bucket = LinkedHashMap(next[networkId] ?: emptyMap())
        if (values != null) {
            bucket[fold(nick)] = values
        } else {
            bucket.remove(fold(nick))
        }
        if (bucket.isEmpty()) next.remove(networkId) else next[networkId] = bucket
        return folded(next)
    }

    /** This set without `networkId` — the network was deleted. */
    fun removing(networkId: Int): UserMetadataSet {
        if (byNetwork[networkId] == null) return this
        val next = LinkedHashMap(byNetwork)
        next.remove(networkId)
        return folded(next)
    }

    /**
     * How many nicks have a record on `networkId` — for tests and `/metadata list`-style
     * inventories, not the hot path.
     */
    fun count(networkId: Int): Int = byNetwork[networkId]?.size ?: 0

    override fun equals(other: Any?): Boolean =
        this === other || (other is UserMetadataSet && byNetwork == other.byNetwork)

    override fun hashCode(): Int = byNetwork.hashCode()

    override fun toString(): String = "UserMetadataSet(byNetwork=$byNetwork)"

    companion object {
        val empty = UserMetadataSet()

        internal fun fold(nick: String): String = NickColor.lowercasedLikeTheWeb(nick)

        /**
         * An already-folded map, for the patch paths: the set holds a record for every nick the
         * server knows across every network, and a join on a busy network arrives as one event per
         * nick — re-folding every key on each would be quadratic across the burst.
         */
        private fun folded(folded: Map<Int, Map<String, UserMetadata>>): UserMetadataSet =
            UserMetadataSet(folded, alreadyFolded = true)
    }
}
