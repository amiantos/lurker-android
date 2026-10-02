// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.store.SocketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The worded connection banner: which message the top of the chat screen shows, and the
 * one rule that matters most — no network path beats whatever the socket claims.
 */
class ConnectionBannerStateTests {

    @Test
    fun testConnectedAndReachableShowsNothing() {
        assertEquals(ConnectionBannerState.Hidden, ConnectionBannerState.of(reachable = true, connection = SocketStatus.Connected))
    }

    @Test
    fun testNoPathIsOfflineRegardlessOfSocket() {
        // The socket can't say "no internet" — it only ever reports connecting/connected/
        // reconnecting — so a stale socket state must not hide the offline truth.
        assertEquals(ConnectionBannerState.Offline, ConnectionBannerState.of(reachable = false, connection = SocketStatus.Connected))
        assertEquals(
            ConnectionBannerState.Offline,
            ConnectionBannerState.of(reachable = false, connection = SocketStatus.Reconnecting),
        )
        assertEquals(ConnectionBannerState.Offline, ConnectionBannerState.of(reachable = false, connection = SocketStatus.Connecting))
    }

    @Test
    fun testConnectingAndReconnectingAreDistinctWhenReachable() {
        // Two different stories: "we've never connected this session" vs. "we had it and
        // dropped". Both reachable, so both are ours to fix and both spin.
        assertEquals(
            ConnectionBannerState.Connecting,
            ConnectionBannerState.of(reachable = true, connection = SocketStatus.Connecting),
        )
        assertEquals(
            ConnectionBannerState.Reconnecting,
            ConnectionBannerState.of(reachable = true, connection = SocketStatus.Reconnecting),
        )
    }

    @Test
    fun testAServerThatCantTakeThisBuildOutranksEverything() {
        // No path included: coming back online won't fix it, and the banner is the one place
        // that says which side needs the update (lurker-ios#17).
        for (reachable in listOf(true, false)) {
            assertEquals(
                ConnectionBannerState.Incompatible(Incompatibility.AppTooOld),
                ConnectionBannerState.of(reachable = reachable, connection = SocketStatus.Incompatible(Incompatibility.AppTooOld)),
            )
            assertEquals(
                ConnectionBannerState.Incompatible(Incompatibility.ServerTooOld),
                ConnectionBannerState.of(
                    reachable = reachable,
                    connection = SocketStatus.Incompatible(Incompatibility.ServerTooOld),
                ),
            )
        }
    }

    @Test
    fun testOnlyConnectingAndReconnectingSpin() {
        // Offline has nothing to spin about — there's no attempt in flight until a path
        // comes back — so it reads as a settled, user-actionable state, not a busy one.
        // Neither does a server that can't take this build: nothing is retrying.
        assertTrue(ConnectionBannerState.Connecting.isWorking)
        assertTrue(ConnectionBannerState.Reconnecting.isWorking)
        assertFalse(ConnectionBannerState.Offline.isWorking)
        assertFalse(ConnectionBannerState.Incompatible(Incompatibility.AppTooOld).isWorking)
        assertFalse(ConnectionBannerState.Hidden.isWorking)
    }
}
