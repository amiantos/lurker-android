// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.JoinNotice
import net.amiantos.lurkerkit.model.PendingJoins
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What became of a join this device asked for (lurker-ios#57).
 *
 * Before this, nothing tracked a join between asking and hearing back: a refusal said nothing, a
 * join that never landed said nothing, and of the four ways to join, only the composer's `/join`
 * waited for the server before moving the user.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Pending joins"), where each case carries a
 * display name. The method names are kept and the display name is the comment above each.
 */
class PendingJoinsTests {

    private val chan = BufferKey(networkId = 1, target = "#lurker")
    private val t0 = Instant.ofEpochSecond(1_000_000)

    /** Port note: `Date.addingTimeInterval`, so the instants below read as they do in LurkerKit's suite. */
    private fun Instant.addingTimeInterval(seconds: Long): Instant = plusSeconds(seconds)

    /** Port note: `PendingJoins.timeout` as the whole seconds LurkerKit's `TimeInterval` holds. */
    private val timeout: Long = PendingJoins.timeout.seconds

    /** a channel-joined for a join we asked for settles it, carrying whether to open */
    @Test
    fun joinedSettlesTheRequest() {
        val joins = PendingJoins()
        joins.request(chan, opens = true, now = t0)
        assertEquals(PendingJoins.Outcome.Joined(chan, opens = true), joins.joined(chan))
        assertEquals(0, joins.pendingCount)
    }

    /** a newer ask to go somewhere stands an opening join down, and its refusal is still told */
    @Test
    fun stopOpeningKeepsTheJoinTrackedButGoesNowhere() {
        // ⚠ `/join #slow`, then Send Message to bob: bob landed, then #slow's answer yanked the
        // user away from him (lurker-ios#201).
        val joins = PendingJoins()
        joins.request(chan, opens = true, now = t0)
        joins.stopOpening()
        assertEquals(PendingJoins.Outcome.Joined(chan, opens = false), joins.joined(chan))
        joins.request(chan, opens = true, now = t0)
        joins.stopOpening()
        assertEquals(PendingJoins.Outcome.Refused(chan, reason = "No."), joins.refused(chan, reason = "No."))
    }

    /** a join nobody here asked for moves nobody */
    @Test
    fun unaskedJoinsAreIgnored() {
        // A reconnect's rejoin, or a join made on another device, must not switch the screen or
        // toast a refusal this device never saw coming.
        val joins = PendingJoins()
        assertNull(joins.joined(chan))
        assertNull(joins.refused(chan, reason = "This channel is invite-only."))
    }

    /** the server's spelling answers the typed one */
    @Test
    fun matchingFoldsCase() {
        val joins = PendingJoins()
        joins.request(BufferKey(networkId = 1, target = "#Lurker"), opens = true, now = t0)
        assertEquals(PendingJoins.Outcome.Joined(chan, opens = true), joins.joined(chan))
    }

    /** a refusal carries the server's reason, and is forgotten */
    @Test
    fun refusalCarriesTheReason() {
        val joins = PendingJoins()
        joins.request(chan, opens = true, now = t0)
        assertEquals(
            PendingJoins.Outcome.Refused(chan, reason = "This channel is invite-only."),
            joins.refused(chan, reason = "This channel is invite-only."),
        )
        assertEquals(0, joins.pendingCount)
    }

    /** a forward's part settles the join without a word */
    @Test
    fun forwardIsQuiet() {
        // A 470 answers under another name. Left pending, it would time out into a "No response"
        // for a join that was answered.
        val joins = PendingJoins()
        joins.request(chan, opens = true, now = t0)
        joins.parted(chan)
        assertEquals(emptyList(), joins.expire(now = t0.addingTimeInterval(timeout)))
    }

    /** a join still unanswered at the deadline times out, once */
    @Test
    fun timeoutFiresOnce() {
        val joins = PendingJoins()
        joins.request(chan, opens = false, now = t0)
        assertEquals(emptyList(), joins.expire(now = t0.addingTimeInterval(timeout - 1)))
        assertEquals(
            listOf<PendingJoins.Outcome>(PendingJoins.Outcome.TimedOut(chan)),
            joins.expire(now = t0.addingTimeInterval(timeout)),
        )
        assertEquals(emptyList(), joins.expire(now = t0.addingTimeInterval(timeout + 1)))
    }

    /** asking again opens if either request wanted to, and restarts the clock */
    @Test
    fun repeatRequestMerges() {
        val joins = PendingJoins()
        joins.request(chan, opens = false, now = t0)
        joins.request(chan, opens = true, now = t0.addingTimeInterval(8))
        assertEquals(
            emptyList(),
            joins.expire(now = t0.addingTimeInterval(timeout)),
            "the second request restarted the clock",
        )
        assertEquals(PendingJoins.Outcome.Joined(chan, opens = true), joins.joined(chan))

        // …and the other way round: a parted row's Join (which doesn't open) landing after a typed
        // `/join` must not cancel the switch the `/join` asked for.
        joins.request(chan, opens = true, now = t0)
        joins.request(chan, opens = false, now = t0)
        assertEquals(PendingJoins.Outcome.Joined(chan, opens = true), joins.joined(chan))
    }

    /** a list of channels is tracked one channel at a time */
    @Test
    fun aListSplitsIntoChannels() {
        // `/join #a,#b` goes out as one JOIN, but the server answers `#a` and `#b` on their own.
        // Waiting on "#a,#b" waited on a name nothing answers, and timed out into a false
        // "No response" for two joins that worked.
        assertEquals(listOf("#a", "#b"), PendingJoins.channels("#a,#b"))
        assertEquals(listOf("#a", "#b"), PendingJoins.channels("#a, #b,"))
        assertEquals(listOf("#lurker"), PendingJoins.channels("#lurker"))
    }

    /** a dropped socket forgets every join */
    @Test
    fun removeAllForgets() {
        val joins = PendingJoins()
        joins.request(chan, opens = true, now = t0)
        joins.removeAll()
        assertEquals(0, joins.pendingCount)
        assertNull(joins.joined(chan))
    }

    /** a notice says what happened and names the channel */
    @Test
    fun noticeCopy() {
        assertEquals(
            "Couldn't join #secret: This channel is invite-only.",
            JoinNotice.Refused(channel = "#secret", reason = "This channel is invite-only.").message,
        )
        assertEquals("No response joining #secret", JoinNotice.NoResponse(channel = "#secret").message)
        assertEquals(
            "Can't join #secret while Libera is offline",
            JoinNotice.NotConnected(channel = "#secret", network = "Libera").message,
        )
    }
}
