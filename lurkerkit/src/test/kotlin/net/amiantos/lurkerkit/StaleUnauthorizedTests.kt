// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.VerbReply
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A 401 ends the session only when it answered the token in use now. */
class StaleUnauthorizedTests {

    private val frames = mutableListOf<ServerFrame>()

    private fun signedIn(token: String): LurkerClient {
        val client = LurkerClient(scope = TestScope(), onFrame = { frame -> frames.add(frame) })
        client.restore(server = "https://app.lurker.chat", token = token)
        return client
    }

    /** Signed out and back in while a request made with the old token was still out. */
    @Test
    fun testALateAnswerToTheOldTokenLeavesTheNewSessionAlone() {
        val client = signedIn("old")
        client.close()
        client.restore(server = "https://app.lurker.chat", token = "new")
        client.reportUnauthorized(sentWith = "old")
        assertEquals(emptyList(), frames)
    }

    @Test
    fun testA401ForTheCurrentTokenEndsTheSession() {
        val client = signedIn("current")
        client.reportUnauthorized(sentWith = "current")
        assertEquals(listOf<ServerFrame>(ServerFrame.Unauthorized), frames)
    }

    /** Signed out with nothing new yet, so there's no session left to end. */
    @Test
    fun testA401AfterSignOutIsIgnored() {
        val client = signedIn("old")
        client.close()
        client.reportUnauthorized(sentWith = "old")
        assertEquals(emptyList(), frames)
    }

    // Port-only: the same rule reached through a request, answered by an interceptor in place
    // of a server — the 401 is reported against the token the request was sent with.

    @Test
    fun testA401FromARestCallEndsTheSessionItWasSentWith() = runTest {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("canned")
                    .body("""{"error":"unauthorized"}""".toResponseBody(null))
                    .build()
            }
            .build()
        val client = LurkerClient(scope = this, onFrame = { frames.add(it) }, httpClient = http)
        client.restore(server = "https://app.lurker.chat", token = "current")
        assertNull(client.networkConfigs())
        assertEquals("Signed out.", client.deleteNetwork(id = 1))
        assertEquals(listOf<ServerFrame>(ServerFrame.Unauthorized, ServerFrame.Unauthorized), frames)
    }

    /** With no socket, an acked verb is answered at once: it never went out. */
    @Test
    fun testAnAckedVerbWithNoSocketIsNotSent() = runTest {
        val client = LurkerClient(scope = this, onFrame = { frames.add(it) })
        client.restore(server = "https://app.lurker.chat", token = "current")
        assertEquals(VerbReply.notSent, client.setTopic(networkId = 1, channel = "#a", topic = "t"))
        assertEquals(emptyList(), frames)
    }
}
