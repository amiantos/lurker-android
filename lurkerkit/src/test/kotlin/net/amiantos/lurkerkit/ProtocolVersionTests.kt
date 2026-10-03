// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.ProtocolVersion
import net.amiantos.lurkerkit.client.ServerFrame
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    // MARK: - /api/config

    @Test
    fun testTheConfigCarriesTheServersVersions() {
        val body = """{"edition":"node","protocolVersion":1,"minProtocolVersion":1,"features":{}}""".encodeUtf8()
        val config = LurkerClient.parseConfig(body, code = 200)
        assertEquals(1, config?.protocolVersion)
        assertEquals(1, config?.minProtocolVersion)
        assertNull(config?.incompatibility)
    }

    @Test
    fun testAConfigThatRaisedItsMinimumRefusesThisBuild() {
        val body = """{"protocolVersion":2,"minProtocolVersion":2}""".encodeUtf8()
        assertEquals(Incompatibility.AppTooOld, LurkerClient.parseConfig(body, code = 200)?.incompatibility)
    }

    @Test
    fun testAConfigWithoutVersionsStatesNone() {
        val config = LurkerClient.parseConfig("{}".encodeUtf8(), code = 200)
        assertNotNull(config, "still an answer")
        assertNull(config.protocolVersion)
        assertNull(config.minProtocolVersion)
        assertNull(config.incompatibility)
    }

    // MARK: - The socket

    @Test
    fun testEverySocketAnnouncesTheVersion() {
        // The server treats a missing `?v` as current, so a build that left it off could never
        // be told it's too old.
        assertEquals(
            "wss://app.lurker.chat/ws?v=1",
            LurkerClient.socketURL(baseURL = "https://app.lurker.chat", since = 0),
        )
        assertEquals(
            "ws://192.168.1.5:3000/ws?v=1&since=42",
            LurkerClient.socketURL(baseURL = "http://192.168.1.5:3000", since = 42),
        )
    }

    @Test
    fun testARefusedUpgradeWith426MeansThisBuildIsTooOld() {
        // Measured on iOS against wsHub's bytes (a bare status line, then a destroyed socket):
        // URLSessionWebSocketTask reports 426 in `task.response`, the same way it reports a 401.
        // Here it is `onFailure`'s response.
        assertEquals(
            ServerFrame.Incompatible(Incompatibility.AppTooOld),
            LurkerClient.closeFrame(status = 426, closeCode = 0, reason = "refused"),
        )
    }

    @Test
    fun testOtherEndingsKeepTheirMeaning() {
        assertEquals(ServerFrame.Unauthorized, LurkerClient.closeFrame(status = 401, closeCode = 0, reason = "refused"))
        assertEquals(ServerFrame.Unauthorized, LurkerClient.closeFrame(status = 101, closeCode = 4001, reason = "revoked"))
        assertEquals(
            ServerFrame.SocketClosed(reason = "dropped", code = 101),
            LurkerClient.closeFrame(status = 101, closeCode = 1001, reason = "dropped"),
        )
    }

    // Port-only:

    /**
     * `HttpUrl` cannot hold a ws(s) address, so `socketURL` is a `String` OkHttp's request
     * builder reads — and null for any base that would not make one, where `URL(string:)`
     * takes any scheme and OkHttp would throw.
     */
    @Test
    fun testASocketAddressOkHttpCannotOpenIsNull() {
        assertNull(LurkerClient.socketURL(baseURL = "ftp://h", since = 0))
        assertNull(LurkerClient.socketURL(baseURL = "", since = 0))
        // A cursor of 0 or less is a fresh connect, not a resume.
        assertEquals("wss://h/ws?v=1", LurkerClient.socketURL(baseURL = "https://h", since = -1))
    }
}
