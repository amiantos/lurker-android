// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionWordsTest {

    @Test
    fun everyStateHasIosWords() {
        assertEquals("Connected", connectionWords(ConnectionBannerState.Hidden))
        assertEquals("Connecting…", connectionWords(ConnectionBannerState.Connecting))
        assertEquals("Reconnecting…", connectionWords(ConnectionBannerState.Reconnecting))
        assertEquals("No internet connection", connectionWords(ConnectionBannerState.Offline))
        assertEquals(
            "Update the app to connect",
            connectionWords(ConnectionBannerState.Incompatible(Incompatibility.AppTooOld)),
        )
        assertEquals(
            "The server needs an update",
            connectionWords(ConnectionBannerState.Incompatible(Incompatibility.ServerTooOld)),
        )
    }

    @Test
    fun noPathOutranksTheSocket() {
        assertEquals(
            "No internet connection",
            connectionWords(ConnectionBannerState.of(reachable = false, connection = SocketStatus.Reconnecting)),
        )
    }
}
