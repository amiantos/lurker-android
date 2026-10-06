// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.amiantos.lurkerkit.push.DeviceKeys
import net.amiantos.lurkerkit.push.PushRoute
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.PersistedSession
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.SettingsCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session side of relayed push (lurker-dev/RELAY_PLAN.md §6.2): what the server's push config
 * decides, filing the relay endpoint, and taking it back off at sign-out.
 */
class RelayPushSessionTests {

    /** Answers by path; records method, path and body. */
    private class Server(val answers: Map<String, Pair<Int, String>>) {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val http: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() } ?: ""
                requests.add("${request.method} ${request.url.encodedPath} $body".trimEnd())
                val (status, text) = answers[request.url.encodedPath] ?: (200 to """{"ok":true}""")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("canned")
                    .body(text.toResponseBody(null))
                    .build()
            }
            .build()
    }

    private class Harness(val server: Server) : AutoCloseable {
        val sessions = SessionStore(InMemorySecureStorage())
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(main + SupervisorJob())
        lateinit var model: ChatViewModel

        suspend fun signedIn() = onMain {
            sessions.save(PersistedSession(server = "https://lurker.test", token = "tok"))
            model = ChatViewModel(
                scope = scope,
                sessions = sessions,
                settingsCache = SettingsCache(InMemoryDefaultsStorage()),
                oauthClients = OAuthClients(InMemoryDefaultsStorage()),
                formatExpiry = { it.toString() },
                httpClient = server.http,
                startsInForeground = false,
            )
        }

        suspend fun <T> onMain(block: suspend () -> T): T = withContext(main) { block() }

        override fun close() {
            scope.cancel()
            main.close()
        }
    }

    private val key = "BKey"

    private fun config(transports: String, relay: String?) =
        "/api/push/config" to (200 to """{"publicKey":"$key","transports":$transports${relay?.let { ",\"relay\":\"$it\"" } ?: ""}}""")

    @Test
    fun testTheRouteFollowsTheServersConfig() = runBlocking {
        Harness(Server(mapOf(config("""["webpush"]""", "https://push.lurker.chat")))).use { h ->
            h.signedIn()
            assertEquals(PushRoute.Relay("https://push.lurker.chat", key), h.onMain { h.model.pushRoute(false) })
            assertFalse(h.model.serverHasNoAppPush.value)
        }
        Harness(Server(mapOf(config("""["webpush"]""", null)))).use { h ->
            h.signedIn()
            assertEquals(PushRoute.None, h.onMain { h.model.pushRoute(false) })
            // Settings says so.
            assertTrue(h.model.serverHasNoAppPush.value)
        }
        Harness(Server(mapOf("/api/push/config" to (503 to "")))).use { h ->
            h.signedIn()
            // Couldn't ask: unknown, not "no push".
            assertEquals(null, h.onMain { h.model.pushRoute(false) })
            assertFalse(h.model.serverHasNoAppPush.value)
        }
    }

    @Test
    fun testOnlyADirectAnswerIsCached() = runBlocking {
        Harness(Server(mapOf(config("""["webpush","fcm"]""", null)))).use { h ->
            h.signedIn()
            repeat(3) { assertEquals(PushRoute.Native, h.onMain { h.model.pushRoute(false) }) }
            assertEquals(1, h.server.requests.count { it.startsWith("GET /api/push/config") })
        }
        // A self-hosted server is asked each time: its admin can turn the relay on while the app runs.
        Harness(Server(mapOf(config("""["webpush"]""", null)))).use { h ->
            h.signedIn()
            repeat(3) { h.onMain { h.model.pushRoute(false) } }
            assertEquals(3, h.server.requests.count { it.startsWith("GET /api/push/config") })
        }
    }

    @Test
    fun testFilingTheRelayEndpoint() = runBlocking {
        val keys = DeviceKeys.generate()
        val endpoint = "https://push.lurker.chat/relay-to/fcm/tok123/$key"
        Harness(Server(mapOf("/api/push/subscriptions" to (201 to "{}")))).use { h ->
            h.signedIn()
            assertEquals(ChatViewModel.RelayRegistration.Registered, h.onMain { h.model.registerRelaySubscription(endpoint, keys) })
            val sent = h.server.requests.single { it.startsWith("POST /api/push/subscriptions") }
            assertTrue(sent.contains("\"endpoint\":\"$endpoint\""), sent)
            assertTrue(sent.contains("\"p256dh\":\"${keys.p256dh}\""), sent)
            assertTrue(sent.contains("\"auth\":\"${keys.auth}\""), sent)
            assertTrue(sent.contains("\"userAgent\":\"Lurker Android\""), sent)
        }
        Harness(Server(mapOf("/api/push/subscriptions" to (403 to "{}")))).use { h ->
            h.signedIn()
            // The admin turned the relay off since the config was read: no push, not a failure.
            assertEquals(ChatViewModel.RelayRegistration.RelayOff, h.onMain { h.model.registerRelaySubscription(endpoint, keys) })
            assertTrue(h.model.serverHasNoAppPush.value)
        }
        Harness(Server(mapOf("/api/push/subscriptions" to (500 to "{}")))).use { h ->
            h.signedIn()
            assertEquals(ChatViewModel.RelayRegistration.Failed, h.onMain { h.model.registerRelaySubscription(endpoint, keys) })
        }
    }

    @Test
    fun testSignOutTakesTheRelayEndpointOffBeforeTheRevoke() = runBlocking {
        val endpoint = "https://push.lurker.chat/relay-to/fcm/tok123/$key"
        Harness(Server(mapOf("/api/push/subscriptions" to (201 to "{}")))).use { h ->
            h.signedIn()
            h.onMain { h.model.registerRelaySubscription(endpoint, DeviceKeys.generate()) }
            h.onMain { h.model.logout() }
            withTimeout(5_000) { while (h.server.requests.none { it.startsWith("POST /api/auth/logout") }) delay(20) }
            val paths = h.server.requests.map { it.substringBefore(" {").substringBefore(" ") + " " + it.split(" ")[1] }
            val delete = paths.indexOf("DELETE /api/push/subscriptions")
            val revoke = paths.indexOf("POST /api/auth/logout")
            assertTrue(delete in 0 until revoke, paths.toString())
            assertTrue(h.server.requests[delete].contains("\"endpoint\":\"$endpoint\""))
            assertFalse(h.model.serverHasNoAppPush.value)
        }
    }

    @Test
    fun testSignOutWithoutARelayEndpointSendsNoDelete() = runBlocking {
        Harness(Server(emptyMap())).use { h ->
            h.signedIn()
            h.onMain { h.model.logout() }
            withTimeout(5_000) { while (h.server.requests.none { it.startsWith("POST /api/auth/logout") }) delay(20) }
            assertTrue(h.server.requests.none { it.startsWith("DELETE /api/push/subscriptions") })
        }
    }
}
