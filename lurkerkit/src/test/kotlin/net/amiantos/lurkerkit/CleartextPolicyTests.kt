// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.CleartextPolicy
import net.amiantos.lurkerkit.client.LurkerClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Port-only: the interceptor that refuses cleartext to a public host on every client the kit
// builds. On iOS App Transport Security does this; on Android the network security config permits
// cleartext wholesale (it cannot say "private addresses only"), so this is the policy.

class CleartextPolicyTests {

    @Test
    fun testHTTPSIsPermittedAnywhere() {
        assertTrue(CleartextPolicy.permits("https://app.lurker.chat/api/x".toHttpUrl()))
        assertTrue(CleartextPolicy.permits("https://203.0.113.5/api/x".toHttpUrl()))
    }

    @Test
    fun testPlainHTTPIsPermittedOnlyToTheLocalNetwork() {
        for (url in listOf("http://192.168.1.2/x", "http://10.0.0.5:8010/x", "http://box.local/x", "http://localhost:8010/x", "http://[::1]/x")) {
            assertTrue(CleartextPolicy.permits(url.toHttpUrl()), url)
        }
        for (url in listOf("http://cdn.example.com/x", "http://8.8.8.8/x", "http://203.0.113.5:8010/x", "http://[2001:db8::1]/x")) {
            assertFalse(CleartextPolicy.permits(url.toHttpUrl()), url)
        }
    }

    /**
     * ⚠⚠ On the network layer too, or a redirect escapes it: OkHttp builds a follow-up below the
     * application interceptors, so an `https://` reply of `302 Location: http://cdn.example/…`
     * would be followed in the clear. A network interceptor sees every hop. (Pinned structurally:
     * a hop needs a real connection, which these tests don't make.)
     */
    @Test
    fun testTheKitsClientRefusesPublicCleartextOnEveryHop() {
        val client = LurkerClient.bearerOnlyConfiguration()
        assertTrue(client.interceptors.contains(CleartextPolicy))
        assertTrue(client.networkInterceptors.contains(CleartextPolicy))
        // And a client derived from it keeps both.
        val derived = client.newBuilder().build()
        assertTrue(derived.interceptors.contains(CleartextPolicy))
        assertTrue(derived.networkInterceptors.contains(CleartextPolicy))
    }

    /**
     * ⚠⚠ The one call neither layer covers: OkHttp runs no network interceptors for a WebSocket,
     * and the application interceptors see only its first request. So the socket's client follows
     * no redirect, and a `/ws` answered with `302 Location: http://…` ends as that status rather
     * than as an upgrade in the clear. (Structural, as above: an upgrade needs a real server.)
     */
    @Test
    fun testTheSocketsClientFollowsNoRedirects() {
        val socket = LurkerClient.socketConfiguration(LurkerClient.bearerOnlyConfiguration())
        assertFalse(socket.followRedirects)
        assertFalse(socket.followSslRedirects)
        // Everything else about the base client, the policy included, carries over.
        assertTrue(socket.interceptors.contains(CleartextPolicy))
    }

    /**
     * Installed on `bearerOnlyConfiguration`, ahead of anything added later — so a client built
     * from it by `newBuilder()` refuses a public cleartext address before it reaches the network
     * (here, the canned answer), and lets a private one through to it.
     */
    @Test
    fun testTheKitsClientRefusesPublicCleartextBeforeTheNetwork() {
        val reached = mutableListOf<String>()
        val client: OkHttpClient = LurkerClient.bearerOnlyConfiguration().newBuilder()
            .addInterceptor { chain ->
                reached += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body("ok".toResponseBody(null))
                    .build()
            }
            .build()

        val refused = assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("http://203.0.113.5/api/session").build()).execute()
        }
        assertEquals("Plain http is only allowed to a private address (203.0.113.5).", refused.message)
        assertTrue(reached.isEmpty())

        client.newCall(Request.Builder().url("http://192.168.1.2/api/session").build()).execute().use { assertEquals(200, it.code) }
        client.newCall(Request.Builder().url("https://203.0.113.5/api/session").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(listOf("http://192.168.1.2/api/session", "https://203.0.113.5/api/session"), reached)
    }
}
