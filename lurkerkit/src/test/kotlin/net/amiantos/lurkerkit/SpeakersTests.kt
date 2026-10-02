// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Speaker
import net.amiantos.lurkerkit.model.SpeakerMap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-buffer "who has spoken here lately" map (lurker-ios#63).
 *
 * Pinned because every way it can be wrong is quiet: an entry that never lands, or lands under
 * the wrong casing, or gets walked backwards by a replay, shows up as a join/part line the
 * reader simply never sees — indistinguishable from the filter working.
 */
class SpeakersTests {

    private companion object {
        val t0: Instant = Instant.ofEpochSecond(1_784_548_800)

        fun at(minutes: Double): Instant = t0.plusMillis(Math.round(minutes * 60 * 1000))
    }

    /**
     * Nicks fold case, like every other nick lookup in this client — servers echo whatever
     * casing they feel like, and an event's nick rarely matches the seed's letter for letter.
     */
    @Test
    fun testLookupFoldsCase() {
        val map = SpeakerMap(listOf(Speaker(nick = "AliCe", lastSpoke = t0)))
        assertEquals(t0, map["alice"])
        assertEquals(t0, map["ALICE"])
        assertEquals(setOf("alice"), map.nicks)
    }

    @Test
    fun testAnEmptyMapKnowsNobody() {
        assertTrue(SpeakerMap().isEmpty)
        assertNull(SpeakerMap()["alice"])
    }

    /**
     * Only forward. A backlog replay, or a server list computed before the last live message,
     * must not make a current speaker look stale.
     */
    @Test
    fun testRecordingOnlyMovesTimeForward() {
        var map = SpeakerMap()
        map = map.record(nick = "alice", date = at(10.0))
        map = map.record(nick = "alice", date = at(1.0))
        assertEquals(at(10.0), map["alice"])
        map = map.record(nick = "alice", date = at(20.0))
        assertEquals(at(20.0), map["alice"])
    }

    @Test
    fun testAnEmptyNickIsNotAnEntry() {
        var map = SpeakerMap()
        map = map.record(nick = "", date = t0)
        assertTrue(map.isEmpty)
    }

    @Test
    fun testRenameCarriesTheEntry() {
        var map = SpeakerMap(listOf(Speaker(nick = "alice", lastSpoke = t0)))
        map = map.rename(old = "Alice", new = "alice_afk")
        assertEquals(setOf("alice_afk"), map.nicks)
        assertEquals(t0, map["alice_afk"])
    }

    /**
     * A rename onto a nick that already spoke keeps the later of the two: somebody reclaiming
     * a nick they used earlier shouldn't have their recency rolled back to the old entry.
     */
    @Test
    fun testRenameOntoAKnownNickKeepsTheNewerTime() {
        var map = SpeakerMap(
            listOf(
                Speaker(nick = "alice", lastSpoke = at(0.0)),
                Speaker(nick = "alice_afk", lastSpoke = at(10.0)),
            )
        )
        map = map.rename(old = "alice", new = "alice_afk")
        assertEquals(at(10.0), map["alice_afk"])
        assertNull(map["alice"])
    }

    @Test
    fun testRenamingAnUnknownNickIsANoOp() {
        var map = SpeakerMap(listOf(Speaker(nick = "alice", lastSpoke = t0)))
        map = map.rename(old = "bob", new = "bob_")
        assertEquals(setOf("alice"), map.nicks)
    }

    /**
     * The map only ever grows from live traffic, so a channel left open for a day would
     * otherwise accumulate every nick that ever said anything. Past the cap the least-recent
     * speaker goes — the one whose absence the filter is least likely to notice.
     */
    @Test
    fun testPastTheCapTheLeastRecentSpeakerIsEvicted() {
        var map = SpeakerMap()
        map = map.record(nick = "ancient", date = at(-1.0))
        for (index in 0 until SpeakerMap.cap) {
            map = map.record(nick = "user$index", date = at(index.toDouble()))
        }
        assertEquals(SpeakerMap.cap, map.nicks.size)
        assertNull(map["ancient"], "the oldest entry is the one that goes")
        assertNotNull(map["user0"])
    }

    /** Well clear of the server's own list of 20, so a seed can never immediately evict itself. */
    @Test
    fun testTheCapLeavesRoomForAFullServerSeed() {
        assertTrue(SpeakerMap.cap > 20)
    }
}
