// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.ProfileStatus
import net.amiantos.lurkerkit.model.WhoisResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile's four "is this person there" answers (lurker-ios#12).
 *
 * Ported from the web's `UserProfileModal.test.ts` rather than invented. lurker#818 was a
 * blank profile caused by answering two of these with one predicate, and it took two fixes to
 * settle because the naive correction over-reached in the opposite direction — so the cases
 * that fail in opposite directions are both here, and marked.
 */
class ProfileStatusTests {

    private fun resolve(
        peer: FriendPresence = FriendPresence.Unknown,
        whois: WhoisResult? = null,
        isLookingUp: Boolean = false,
        isSelf: Boolean = false,
    ): ProfileStatus =
        ProfileStatus.resolve(peer = peer, whois = whois, isLookingUp = isLookingUp, isSelf = isSelf)

    private val identity = WhoisResult(nick = "alice", ident = "~alice", hostname = "example.org")
    private val miss = WhoisResult(nick = "ghost", error = "not_found")

    // MARK: - The dot

    @Test
    fun testMonitorWinsWhereItHasAnOpinion() {
        assertEquals(FriendPresence.Online, resolve(peer = FriendPresence.Online).presence)
        assertEquals(FriendPresence.Away, resolve(peer = FriendPresence.Away).presence)
        assertEquals(FriendPresence.Offline, resolve(peer = FriendPresence.Offline).presence)
    }

    @Test
    fun testWithNoMonitorDataTheReplySettlesTheDot() {
        // The no-MONITOR case is the common one — most networks don't give us presence at all.
        assertEquals(FriendPresence.Online, resolve(peer = FriendPresence.Unknown, whois = identity).presence)
        assertEquals(FriendPresence.Unknown, resolve(peer = FriendPresence.Unknown).presence)
    }

    @Test
    fun testANickNobodyIsUsingReadsOfflineNotUnknown() {
        // ⚠⚠ "Unknown" is the one status a not-found lets us rule out. Rendering it there is
        // half of what made lurker#818 look like a profile we simply had no details for.
        assertEquals(FriendPresence.Offline, resolve(peer = FriendPresence.Unknown, whois = miss).presence)
    }

    @Test
    fun testAnAwayReasonInTheReplyShowsAwayEvenWithoutMonitor() {
        val away = WhoisResult(nick = "alice", hostname = "example.org", away = "back later")
        assertEquals(FriendPresence.Away, resolve(peer = FriendPresence.Unknown, whois = away).presence)
        assertEquals("back later", resolve(peer = FriendPresence.Unknown, whois = away).awayMessage)
    }

    @Test
    fun testAPeerWhoCameBackIsNotPinnedAwayByAStaleReply() {
        // ⚠⚠ The reply is a snapshot from whenever it was asked, and iOS keeps no live away
        // *message* — `FrameParser` flattens the peer-presence blob to the state alone. So
        // after alice `/away lunch`, you open her profile, and she comes back, MONITOR says
        // `Online` while `whois.away` still holds "lunch". Testing the reply first would pin
        // the dot to "Away — lunch" for someone we have been told is back.
        val stale = WhoisResult(nick = "alice", hostname = "example.org", away = "lunch")
        val status = resolve(peer = FriendPresence.Online, whois = stale)
        assertEquals(FriendPresence.Online, status.presence)
        // And the reason goes with it: a stale "lunch" under an "Online" heading is worse
        // than no reason at all.
        assertNull(status.awayMessage)
    }

    @Test
    fun testMonitorOfflineAlsoBeatsAStaleAwayReason() {
        val stale = WhoisResult(nick = "alice", hostname = "example.org", away = "lunch")
        assertEquals(FriendPresence.Offline, resolve(peer = FriendPresence.Offline, whois = stale).presence)
        assertNull(resolve(peer = FriendPresence.Offline, whois = stale).awayMessage)
    }

    @Test
    fun testBeingAwayWithNoReasonIsStillBeingAway() {
        // ⚠ Deliberately NOT the web's `awayLabel`, which returns nothing when away is active
        // with no reason — an indicator that vanishes for the most common spelling of `/away`
        // is worse than none. The dot stands on its own; only the reason is absent.
        val status = resolve(peer = FriendPresence.Away)
        assertEquals(FriendPresence.Away, status.presence)
        assertNull(status.awayMessage)
    }

    // MARK: - The status line

    @Test
    fun testAnAnsweredMissSaysSo() {
        assertEquals(
            ProfileStatus.StatusLine.NotFound,
            resolve(peer = FriendPresence.Unknown, whois = miss).statusLine,
        )
    }

    @Test
    fun testACachedMissIsDemotedToWaitingWhileARefreshIsOut() {
        // ⚠⚠ A cached miss is stale the instant a refresh goes out. Without this, reopening a
        // profile seconds after that nick connected asserts they aren't on the network for a
        // whole round trip.
        assertEquals(
            ProfileStatus.StatusLine.Waiting,
            resolve(peer = FriendPresence.Unknown, whois = miss, isLookingUp = true).statusLine,
        )
    }

    @Test
    fun testHavingDetailsMeansTheLineHasNothingToAdd() {
        assertNull(resolve(peer = FriendPresence.Unknown, whois = identity).statusLine)
        assertNull(resolve(peer = FriendPresence.Online, whois = identity, isLookingUp = true).statusLine)
    }

    @Test
    fun testKnowingNothingYetSaysWaiting() {
        assertEquals(ProfileStatus.StatusLine.Waiting, resolve(peer = FriendPresence.Unknown).statusLine)
        assertEquals(ProfileStatus.StatusLine.Waiting, resolve(peer = FriendPresence.Online).statusLine)
    }

    @Test
    fun testAPeerMonitorCallsOfflineGetsNoSpinner() {
        // The dot already carries it; a spinner under it would claim we're unsure when we
        // aren't. This is the branch that must read MONITOR's verdict specifically.
        assertNull(resolve(peer = FriendPresence.Offline).statusLine)
    }

    // MARK: - The two directions lurker#818 failed in

    @Test
    fun testAMissBeingRecheckedDoesNotGoDownTheQuietPath() {
        // ⚠⚠ DIRECTION 1 — the original bug. If the last branch folded a not-found in with
        // MONITOR's verdict, a miss we are re-checking would return null here and the body would
        // render nothing at all: no details, no explanation. It must say `Waiting`.
        assertEquals(
            ProfileStatus.StatusLine.Waiting,
            resolve(peer = FriendPresence.Unknown, whois = miss, isLookingUp = true).statusLine,
        )
    }

    @Test
    fun testAMonitorOfflinePeerIsNotToldToWaitJustBecauseALookupIsOut() {
        // ⚠⚠ DIRECTION 2 — the over-correction, caught in review on the web's PR lurker#819.
        // Using "a lookup is in flight" as the escape hatch gives EVERY MONITOR-offline peer
        // "Waiting for whois reply…", because a lookup is always out on open.
        assertNull(resolve(peer = FriendPresence.Offline, isLookingUp = true).statusLine)
    }

    // MARK: - Send DM

    @Test
    fun testSendDmIsHiddenForYourself() {
        assertFalse(resolve(peer = FriendPresence.Online, whois = identity, isSelf = true).canSendDirectMessage)
    }

    @Test
    fun testSendDmIsHiddenWhenADmWouldBounce() {
        // Here the two verdicts ARE rightly folded together: a DM bounces whether MONITOR says
        // they're gone or the lookup found nobody. This is the question `isOffline` was for.
        assertFalse(resolve(peer = FriendPresence.Offline).canSendDirectMessage)
        assertFalse(resolve(peer = FriendPresence.Unknown, whois = miss).canSendDirectMessage)
    }

    @Test
    fun testSendDmIsOfferedToSomeoneWhoMightBeThere() {
        assertTrue(resolve(peer = FriendPresence.Online, whois = identity).canSendDirectMessage)
        // Unknown is "potentially online" — the no-MONITOR case, which is most networks. A DM
        // there is exactly what the user is trying to do.
        assertTrue(resolve(peer = FriendPresence.Unknown).canSendDirectMessage)
        assertTrue(resolve(peer = FriendPresence.Away, whois = identity).canSendDirectMessage)
    }

    // MARK: - hasDetails

    @Test
    fun testAMissIsAReplyWithNothingInIt() {
        // ⚠ Treating "there is a reply" as "there are details" would suppress the very line
        // that has to explain the miss.
        assertFalse(ProfileStatus.hasDetails(miss))
        assertFalse(ProfileStatus.hasDetails(null))
        assertTrue(ProfileStatus.hasDetails(identity))
    }

    @Test
    fun testChannelsAloneCountAsDetails() {
        // A whois can come back with nothing but a channel list on a locked-down network.
        assertTrue(ProfileStatus.hasDetails(WhoisResult(nick = "alice", channelsLine = "#foo")))
        // But a channels line that names no channel does not.
        assertFalse(ProfileStatus.hasDetails(WhoisResult(nick = "alice", channelsLine = "@")))
    }
}
