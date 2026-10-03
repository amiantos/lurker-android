// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.dcc

import net.amiantos.lurkerkit.model.DccChatOffer

/** One offer as the prompt words it — the offer, and the network's name if it has one. */
data class DccOfferPrompt(val offer: DccChatOffer, val networkName: String?) {
    /** "DCC chat from bob" — iOS's alert title. */
    val title: String get() = "DCC chat from ${offer.nick}"

    /**
     * "bob on Libera wants to chat directly." — and, for a passive offer, what accepting would make
     * your server do. iOS's alert message, word for word.
     */
    val message: String
        get() {
            val base = networkName?.let { "${offer.nick} on $it wants to chat directly." }
                ?: "${offer.nick} wants to chat directly."
            return if (offer.passive) {
                "$base They're behind a firewall, so your Lurker server would listen for them."
            } else {
                base
            }
        }
}

/**
 * Which DCC chat offer to ask about, if any (lurker#270, lurker-android#38) — the bookkeeping half of
 * lurker-ios's `DccOfferPrompt`.
 *
 * Each offer is asked about ONCE, by `DccChatOffer.id`: a reconnect re-lists a pending offer and
 * keeps its id, so it doesn't ask again; a peer offering again mints a new one, so it does. Not Now
 * leaves the offer standing — `/dcc chat bob` can still accept it until it expires.
 *
 * ⚠ Offers first learned from a snapshot are asked about too, which the web deliberately doesn't. A
 * phone's socket sleeps in the background and an offer sends no push, so the snapshot on the way back
 * in is usually the only way it hears. Skipping those would skip most of them.
 *
 * Asked = answered, here, where iOS marks it at presentation: the prompt is composition, which a
 * configuration change rebuilds, and an offer marked asked by a dialog that was then torn down would
 * never be put up again. One offer at a time, oldest first.
 *
 * ⚠⚠ An answer counts only once the prompt for THAT offer has been on screen for [SETTLE_MS]
 * ([shown], [answer]). With two offers queued, answering the first puts the second up in the same
 * spot, and a quick second tap — or a double tap — would answer an offer nobody read. Accepting makes
 * the server dial an address the peer chose; it has to be a deliberate act about the offer named.
 */
class DccOfferQueue {
    private var asked: Set<Int> = emptySet()

    /** The offer whose prompt is on screen, and since when (a monotonic clock, in ms). */
    private var shownSince: Pair<Int, Long>? = null
    private var offers: List<DccChatOffer> = emptyList()
    private var names: Map<Int, String?> = emptyMap()

    /** The offer to ask about now, or null. */
    var current: DccOfferPrompt? = null
        private set

    /**
     * The store's offers changed (or a network's name). The question is over when its offer is —
     * accepted elsewhere, declined on the web, expired, signed out of: left up, Accept would send the
     * peer a FRESH offer instead, a different act from the one the prompt names. Returns [current].
     */
    fun update(offers: List<DccChatOffer>, networkNames: Map<Int, String?>): DccOfferPrompt? {
        this.offers = offers
        names = networkNames
        // Trimmed to the ones still pending, so it stays as small as the list.
        asked = asked.intersect(offers.map { it.id }.toSet())
        return recompute()
    }

    /**
     * The prompt for [id] is on screen as of [now] — the moment its settle runs from. Once per offer:
     * a recomposition of the same dialog doesn't restart it.
     */
    fun shown(id: Int, now: Long) {
        if (current?.offer?.id == id && shownSince?.first != id) shownSince = id to now
    }

    /**
     * The prompt for [id] was answered at [now] — Accept, Decline or Not Now (Back is Not Now). False,
     * and nothing changes, unless it's the offer being asked about and its prompt has settled.
     */
    fun answer(id: Int, now: Long): Boolean {
        val since = shownSince
        if (current?.offer?.id != id || since == null || since.first != id || now - since.second < SETTLE_MS) return false
        asked = asked + id
        recompute()
        return true
    }

    private fun recompute(): DccOfferPrompt? {
        val offer = offers.firstOrNull { it.id !in asked }
        current = offer?.let { DccOfferPrompt(it, names[it.networkId]) }
        if (shownSince?.first != offer?.id) shownSince = null
        return current
    }

    companion object {
        /** How long a prompt is on screen before a tap on it counts. Longer than a double tap. */
        const val SETTLE_MS = 500L
    }
}
