// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.ISOTime
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.LurkerStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Your own away state (lurker-ios#68), from the wire to the store: the snapshot seed, the live
 * `away-state` patch, and the two ways it can be *cleared* — which are the parts worth
 * pinning, because a stale away leaves a permanent marker in every buffer with nothing able
 * to retract it.
 */
class AwayStateTests {

    // MARK: - Parser

    @Test
    fun testSnapshotCarriesTheAwayBlob() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[],"away":{"active":false,"since":"2026-07-20T12:00:00Z","message":"lunch","autoSet":true,"backAt":"2026-07-20T13:00:00Z"}}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        val away = frame.networks.firstOrNull()?.away
        assertEquals(false, away?.active)
        assertEquals("lunch", away?.message)
        assertEquals(true, away?.autoSet)
        assertEquals(ISOTime.parse("2026-07-20T12:00:00Z"), away?.since)
        assertEquals(ISOTime.parse("2026-07-20T13:00:00Z"), away?.backAt)
    }

    @Test
    fun testASnapshotWithNoAwayParsesAsNil() {
        // The server sends `away: null` for an account with nothing on record. Null, not a
        // default-constructed state: inventing `active: false` would claim the user *returned*
        // rather than that we were never told anything.
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[],"away":null}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        assertNull(frame.networks.firstOrNull()?.away)
    }

    @Test
    fun testABlobWithNoReadableSinceIsRefused() {
        // `since` is what both markers are placed from, and the server treats it as the
        // existence test too (`away = a.since ? {…} : null`). So a blob we can't read one out of
        // is a blob no marker can be placed from — reading it as an away with no beginning would
        // put a value in the store that nothing can use and nothing can retract.
        for (blob in listOf("""{"active":true}""", """{"active":true,"since":null}""", """{"since":"not a date"}""")) {
            val frame = FrameParser.parseWs(
                """{"kind":"irc","networkId":2,"target":":server:2","type":"away-state","away":""" + blob + "}",
            )
            if (frame !is ServerFrame.AwayState) fail("expected awayState, got $frame")
            assertNull(frame.away, "$blob has no beginning to anchor from")
        }
    }

    @Test
    fun testAwayStateRidesIrcWithServerTarget() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":":server:2","type":"away-state","away":{"active":true,"since":"2026-07-20T12:00:00Z","message":"brb","autoSet":false,"backAt":null}}""",
        )
        if (frame !is ServerFrame.AwayState) fail("expected awayState, got $frame")
        val (networkId, away) = frame
        assertEquals(2, networkId)
        assertEquals(true, away?.active)
        assertEquals("brb", away?.message)
        assertNull(away?.backAt, "still away")
    }

    @Test
    fun testANullAwayIsAValueNotADroppedFrame() {
        // This is how "cleared" arrives, so it has to reach the store as a frame carrying null
        // rather than being refused as unparseable.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":":server:2","type":"away-state","away":null}""",
        )
        if (frame !is ServerFrame.AwayState) fail("expected awayState, got $frame")
        val (networkId, away) = frame
        assertEquals(2, networkId)
        assertNull(away)
    }

    @Test
    fun testAwayStateWithoutANetworkIsIgnored() {
        // Every real one carries a networkId; one without has no network to patch.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","target":":server:2","type":"away-state","away":{"active":true}}""",
        )
        if (frame !is ServerFrame.Ignored) fail("expected ignored, got $frame")
    }

    @Test
    fun testAwayStateIsNotReadAsAMessage() {
        // The regression this case exists to prevent: left to `parseEvent` it becomes an
        // `Other` Message appended to the `:server:` buffer, carrying its payload in no field
        // anything reads.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":":server:2","type":"away-state","away":null}""",
        )
        if (frame is ServerFrame.Live) fail("away-state is state, not a line")
    }

    // MARK: - Store

    private fun snapshot(away: AwayState?): ServerFrame =
        ServerFrame.Snapshot(
            listOf(
                NetworkSnapshot(id = 2, state = ConnectionState.Connected, nick = "me", channels = emptyList(), away = away),
            ),
            globalIgnores = emptyList(), maxUploadBytes = null,
        )

    private val wentAway = AwayState(
        active = true, message = "brb", since = Instant.ofEpochSecond(1_784_548_800),
    )

    @Test
    fun testTheSnapshotSeedsTheNetworksAwayState() {
        val store = LurkerStore()
        store.apply(snapshot(wentAway))
        assertEquals(wentAway, store.state.networks[2]?.away)
    }

    @Test
    fun testALiveFramePatchesIt() {
        val store = LurkerStore()
        store.apply(snapshot(null))
        store.apply(ServerFrame.AwayState(networkId = 2, away = wentAway))
        assertEquals("brb", store.state.networks[2]?.away?.message)

        val cameBack = AwayState(
            active = false, message = "brb", since = wentAway.since,
            backAt = Instant.ofEpochSecond(1_784_552_400),
        )
        store.apply(ServerFrame.AwayState(networkId = 2, away = cameBack))
        assertEquals(cameBack.backAt, store.state.networks[2]?.away?.backAt)
        assertEquals(
            wentAway.since, store.state.networks[2]?.away?.since,
            "since survives /back — the completed pair is what the dividers render",
        )
    }

    @Test
    fun testANullFrameClearsIt() {
        val store = LurkerStore()
        store.apply(snapshot(wentAway))
        store.apply(ServerFrame.AwayState(networkId = 2, away = null))
        assertNull(store.state.networks[2]?.away)
    }

    @Test
    fun testASnapshotWithNoAwayClearsAStaleOne() {
        // A reconnect after coming back on another device. The snapshot is this network's whole
        // live state, so keeping the old value would strand an "away" marker in every buffer.
        val store = LurkerStore()
        store.apply(snapshot(wentAway))
        store.apply(snapshot(null))
        assertNull(store.state.networks[2]?.away)
    }

    @Test
    fun testAFrameForAnUnknownNetworkMaterializesNothing() {
        // The away stream is broadcast from a live connection, so its network is in the
        // snapshot by definition. Creating a row here would invent a network with no name and
        // no state, which the roster would then render.
        val store = LurkerStore()
        store.apply(ServerFrame.AwayState(networkId = 99, away = wentAway))
        assertNull(store.state.networks[99])
    }

    @Test
    fun testTheRestRosterDoesNotClobberIt() {
        // `GET /api/networks` carries no live state — it merges a name in. An away lost to it
        // would vanish every time the roster refreshed.
        val store = LurkerStore()
        store.apply(snapshot(wentAway))
        store.apply(ServerFrame.Networks(listOf(Network(id = 2, name = "Libera"))))
        assertEquals("Libera", store.state.networks[2]?.name)
        assertEquals(wentAway, store.state.networks[2]?.away)
    }
}
