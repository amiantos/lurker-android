// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.client.ProtocolVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The protocol version handshake (lurker-ios#17): what this build announces on the socket, and
 * what it makes of a server's answer, from `/api/config` or from a refused upgrade.
 */
class ProtocolVersionTests {

    // MARK: - Comparing versions

    @Test
    fun testAServerThatNoLongerServesThisBuildNeedsAnAppUpdate() {
        assertEquals(
            Incompatibility.AppTooOld,
            ProtocolVersion.incompatibility(serverVersion = 2, serverMinimum = 2, spoken = 1),
        )
    }

    @Test
    fun testAServerOlderThanThisBuildSupportsNeedsAServerUpdate() {
        assertEquals(
            Incompatibility.ServerTooOld,
            ProtocolVersion.incompatibility(serverVersion = 1, serverMinimum = 1, spoken = 2, oldestServer = 2),
        )
    }

    @Test
    fun testANewerServerThatStillServesThisBuildIsFine() {
        // Additive-only: a server ahead of the app keeps serving it until it raises its minimum.
        assertNull(ProtocolVersion.incompatibility(serverVersion = 3, serverMinimum = 1, spoken = 1))
    }

    @Test
    fun testTheShippedBuildTalksToTodaysServer() {
        // lurker's server/protocol.ts advertises 1 and 1.
        assertNull(ProtocolVersion.incompatibility(serverVersion = 1, serverMinimum = 1))
    }

    @Test
    fun testAMissingFieldIsNotAVersion() {
        // Read as 0, an absent `protocolVersion` would call every server too old the day this
        // build's minimum is raised.
        assertNull(ProtocolVersion.incompatibility(serverVersion = null, serverMinimum = null, oldestServer = 2))
        assertEquals(
            Incompatibility.AppTooOld,
            ProtocolVersion.incompatibility(serverVersion = null, serverMinimum = 2, spoken = 1),
            "each field says its own thing",
        )
    }

    // Waiting on LurkerClient (`parseConfig`, `socketURL`, `closeFrame`):
    // testTheConfigCarriesTheServersVersions, testAConfigThatRaisedItsMinimumRefusesThisBuild,
    // testAConfigWithoutVersionsStatesNone, testEverySocketAnnouncesTheVersion,
    // testARefusedUpgradeWith426MeansThisBuildIsTooOld, testOtherEndingsKeepTheirMeaning
}
