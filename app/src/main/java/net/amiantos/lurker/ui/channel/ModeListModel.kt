// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.channel

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ChannelAccess
import net.amiantos.lurkerkit.model.ChannelModeForm
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeListEntry
import net.amiantos.lurkerkit.model.ModeListResult
import net.amiantos.lurkerkit.model.channelAccess
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.support.isSwiftWhitespace
import java.time.Instant

/** What the mode list reads from the store (iOS's `Slice`). */
data class ModeListSlice(
    val access: ChannelAccess,
    val linkUp: Boolean,
    /**
     * The network's vocabulary has arrived and says this letter is a list. ⚠ Part of readiness, not
     * just of editing: a snapshot with a null `modeSpec` is a connected, joined channel whose fetch
     * can still fail — and when the spec lands, that is the edge that pays the owed fetch.
     */
    val listed: Boolean,
) {
    val ready: Boolean get() = linkUp && access.joined && listed

    /** Add and Remove are offered to an op, for a letter that is a list here. */
    val canEdit: Boolean get() = access.canEditModes && listed

    companion object {
        fun of(state: ChatState, key: BufferKey, letter: String): ModeListSlice {
            val access = state.channelAccess(key)
            return ModeListSlice(
                access = access,
                linkUp = key.networkId?.let { state.networks[it] }?.state == ConnectionState.Connected,
                listed = access.spec?.list?.contains(letter) == true,
            )
        }
    }
}

/** Where the list's fetch stands. */
sealed interface ModeListStatus {
    data object Loading : ModeListStatus

    data class Ready(val entries: List<ModeListEntry>) : ModeListStatus

    data class Failed(val message: String) : ModeListStatus
}

/**
 * When the list fetches — iOS's `fetchOwed`/`lastSlice`/`fetch` bookkeeping in `ModeListViewController`,
 * pure and testable.
 *
 * ⚠⚠ Fetched ONCE, then kept current by patching it from live `MODE ±letter` rows — another op's ban or
 * our own. Never refetched after an add or a remove: a fetch on the wire claims a 482 aimed at the MODE
 * just sent (`server/services/modeList.ts`), and the list would read "Only channel operators can see
 * this list" because a ban was refused. Pull to refresh is the user's own ask, and a reconnect
 * refetches, since the gap arrives as backlog, not live rows.
 *
 * A fetch is owed once the link is up: a new socket resynced while the network wasn't ready, or the last
 * fetch found it down. ⚠ Paid on the link's RISING edge — connected and in the channel, after not being.
 * Never straight off a resync whose network is still registering (after a server restart): that fetch
 * is refused `not-connected` and nothing would ask again. The edge also stops a retry loop while the
 * store says connected and the server says otherwise.
 */
class ModeListFetches {
    private var owed = false
    private var last: ModeListSlice? = null

    /** The latest fetch. A refresh while one is out starts a newer one, which owns the page. */
    var generation = 0
        private set

    val lastSlice: ModeListSlice? get() = last

    /** Start a fetch; returns its generation. */
    fun start(): Int {
        generation += 1
        return generation
    }

    /** The store moved. True when an owed fetch should go out now. */
    fun linkMoved(slice: ModeListSlice): Boolean {
        val wasReady = last?.ready ?: false
        last = slice
        if (owed && slice.ready && !wasReady) {
            owed = false
            return true
        }
        return false
    }

    /**
     * A new socket's snapshot. The store's word on the link is this socket's now: true to ask at once
     * because it's up, else the fetch waits for it to come up.
     */
    fun resynced(): Boolean {
        if (last?.ready == true) return true
        owed = true
        return false
    }

    /**
     * A fetch answered. Null when a newer fetch owns the page; else the status to show, having noted
     * whether to ask again when the link is next ready.
     */
    fun answered(generation: Int, result: ModeListResult): ModeListStatus? {
        if (generation != this.generation) return null
        return when (result) {
            is ModeListResult.Entries -> ModeListStatus.Ready(result.entries)
            ModeListResult.Offline -> {
                // Asked again when the link comes up.
                owed = true
                ModeListStatus.Failed("Not connected.")
            }
            is ModeListResult.Failed -> {
                // Asked before the link was ready (the vocabulary hadn't arrived, say): ask again when
                // it is. A refusal from a ready link stands until the user refreshes.
                if (last?.ready != true) owed = true
                ModeListStatus.Failed(result.message)
            }
        }
    }
}

/** The mode list's copy and checks — the parts of `ModeListViewController` that aren't a table. */
object ModeListModel {
    const val MASK_PLACEHOLDER = "nick!user@host"
    const val EMPTY = "Nothing here."

    fun addTitle(name: String): String = "Add to $name"

    /**
     * Why a `±letter mask` can't go out, or null when it can. Checked when it's SENT, not when the
     * button was drawn: a reconnect can bring a vocabulary in which this letter isn't a list — or is
     * an owner grant (`q`) — and an Add dialog or a menu can outlive a deop or a part, and sending from
     * one would ask for a 482.
     */
    fun refusal(listedNow: Boolean, canEditNow: Boolean, mask: String): String? {
        if (!listedNow) return "This network doesn't have this list right now."
        if (!canEditNow) return "Only channel operators can change this list."
        // One IRC parameter: the server refuses a mask with a space in it, so say so here.
        if (mask.any { it.isSwiftWhitespace() }) return "A mask can't contain spaces."
        return null
    }

    /** What the list shows: the fetched entries, patched by every live row since. */
    fun shown(status: ModeListStatus, rowsSinceFetch: List<Message>, letter: String): List<ModeListEntry> =
        when (status) {
            is ModeListStatus.Ready -> ChannelModeForm.patch(status.entries, rows = rowsSinceFetch, letter = letter)
            ModeListStatus.Loading, is ModeListStatus.Failed -> emptyList()
        }

    /** "by alice · 1 Sep 2026, 10:00", from what the server knew; null when it knew neither. */
    fun meta(entry: ModeListEntry, dateTime: (Instant) -> String): String? {
        val parts = listOfNotNull(entry.setBy?.let { "by ${ChannelModeForm.setterNick(it)}" }, entry.setAt?.let(dateTime))
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
