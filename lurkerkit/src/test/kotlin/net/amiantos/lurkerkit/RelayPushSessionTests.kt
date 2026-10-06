// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.push.AppPushUnavailable
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The session side of relayed push (lurker-dev/RELAY_PLAN.md §6.2): what the server's push config
 * decides, filing the relay endpoint, and taking it back off at sign-out.
 */
class RelayPushSessionTests {

    /** Answers by path; records method, path and body. A path with a [gates] latch waits on it. */
    private class Server(
        val answers: Map<String, Pair<Int, String>>,
        val gates: Map<String, java.util.concurrent.CountDownLatch> = emptyMap(),
    ) {
        private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** A snapshot: sign-out keeps adding on OkHttp's threads while a test reads. */
        val requests: List<String> get() = synchronized(log) { log.toList() }
        val http: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() } ?: ""
                log.add("${request.method} ${request.url.encodedPath} $body".trimEnd())
                gates[request.url.encodedPath]?.await(5, java.util.concurrent.TimeUnit.SECONDS)
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
            assertEquals(null, h.model.appPushUnavailable.value)
        }
        Harness(Server(mapOf(config("""["webpush"]""", null)))).use { h ->
            h.signedIn()
            assertEquals(PushRoute.None, h.onMain { h.model.pushRoute(false) })
            // Settings says so.
            assertEquals(AppPushUnavailable.NotTurnedOn, h.model.appPushUnavailable.value)
        }
        // A relay this app won't use is its own reason, with its own words in Settings.
        Harness(Server(mapOf(config("""["webpush"]""", "https://relay.elsewhere.example")))).use { h ->
            h.signedIn()
            assertEquals(PushRoute.Unsupported, h.onMain { h.model.pushRoute(false) })
            assertEquals(AppPushUnavailable.RelayUnsupported, h.model.appPushUnavailable.value)
        }
        Harness(Server(mapOf("/api/push/config" to (503 to "")))).use { h ->
            h.signedIn()
            // Couldn't ask: unknown, not "no push".
            assertEquals(null, h.onMain { h.model.pushRoute(false) })
            assertEquals(null, h.model.appPushUnavailable.value)
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
            assertEquals(AppPushUnavailable.NotTurnedOn, h.model.appPushUnavailable.value)
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
            assertEquals(null, h.model.appPushUnavailable.value)
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

    @Test
    fun testARegistrationWhoseAnswerWasLostIsStillTakenOffAtSignOut() = runBlocking {
        val endpoint = "https://push.lurker.chat/relay-to/fcm/tok123/$key"
        // No answer: the request may have landed all the same.
        Harness(Server(mapOf("/api/push/subscriptions" to (502 to "")))).use { h ->
            h.signedIn()
            assertEquals(ChatViewModel.RelayRegistration.Failed, h.onMain { h.model.registerRelaySubscription(endpoint, DeviceKeys.generate()) })
            h.onMain { h.model.logout() }
            withTimeout(5_000) { while (h.server.requests.none { it.startsWith("POST /api/auth/logout") }) delay(20) }
            assertTrue(h.server.requests.any { it.startsWith("DELETE /api/push/subscriptions") && it.contains(endpoint) })
        }
    }

    @Test
    fun testA401LeavesThePushStateASignOutWould() = runBlocking {
        val endpoint = "https://push.lurker.chat/relay-to/fcm/tok123/$key"
        Harness(Server(mapOf(config("""["webpush"]""", null), "/api/push/subscriptions" to (201 to "{}")))).use { h ->
            h.signedIn()
            h.onMain { h.model.pushRoute(false) }
            h.onMain { h.model.registerRelaySubscription(endpoint, DeviceKeys.generate()) }
            h.onMain { h.model.pushRoute(false) }
            assertEquals(endpoint, h.onMain { h.model.filedRelayEndpoint })
            assertEquals(AppPushUnavailable.NotTurnedOn, h.model.appPushUnavailable.value)
            h.onMain { h.model.handle(ServerFrame.Unauthorized) }
            assertEquals(ChatViewModel.SessionState.LoggedOut, h.model.session)
            assertEquals(null, h.onMain { h.model.filedRelayEndpoint })
            assertEquals(null, h.model.appPushUnavailable.value)
        }
        // And the cached direct answer goes too: the next sign-in may be on another server.
        Harness(Server(mapOf(config("""["webpush","fcm"]""", null)))).use { h ->
            h.signedIn()
            assertEquals(PushRoute.Native, h.onMain { h.model.pushRoute(false) })
            h.onMain { h.model.handle(ServerFrame.Unauthorized) }
            // Signed out, there's no one to ask; what there mustn't be is the old server's "direct".
            assertEquals(null, h.onMain { h.model.pushRoute(false) })
        }
    }

    // MARK: - A relay turned off and on unseen

    @Test
    fun testTheServerSaysWhetherTheEndpointIsStillFiled() = runBlocking {
        val endpoint = "https://push.lurker.chat/relay-to/fcm/tok123/$key"
        for ((answer, expected) in listOf(
            (200 to """{"ok":true,"present":true}""") to true,
            // Deleted when the admin turned the relay off: file it again.
            (200 to """{"ok":true,"present":false}""") to false,
            // An older server without the route.
            (404 to "") to false,
            // No real answer: says nothing either way.
            (503 to "") to null,
        )) {
            Harness(Server(mapOf("/api/push/heartbeat" to answer))).use { h ->
                h.signedIn()
                assertEquals(expected, h.onMain { h.model.relaySubscriptionPresent(endpoint) }, answer.toString())
                val sent = h.server.requests.single { it.startsWith("POST /api/push/heartbeat") }
                assertTrue(sent.contains("\"endpoint\":\"$endpoint\""), sent)
            }
        }
    }

    // MARK: - Switching routes

    @Test
    fun testMovingToTheRelayTakesTheDirectTokenOff() = runBlocking {
        Harness(Server(mapOf("/api/push/subscriptions" to (201 to "{}")))).use { h ->
            h.signedIn()
            h.onMain { h.model.registerPushDevice("fcm-token-1") }
            h.onMain { h.model.registerRelaySubscription("https://push.lurker.chat/relay-to/fcm/fcm-token-1/$key", DeviceKeys.generate()) }
            val delete = h.server.requests.indexOfFirst { it.startsWith("DELETE /api/push/devices") }
            val file = h.server.requests.indexOfFirst { it.startsWith("POST /api/push/subscriptions") }
            assertTrue(delete >= 0 && h.server.requests[delete].contains("fcm-token-1"), h.server.requests.toString())
            assertTrue(delete < file)
            // And sign-out doesn't try to take the token off again.
            h.onMain { h.model.logout() }
            withTimeout(5_000) { while (h.server.requests.none { it.startsWith("POST /api/auth/logout") }) delay(20) }
            assertEquals(1, h.server.requests.count { it.startsWith("DELETE /api/push/devices") })
        }
    }

    @Test
    fun testMovingToDirectTakesTheRelayEndpointOff() = runBlocking {
        val endpoint = "https://push.lurker.chat/relay-to/fcm/fcm-token-1/$key"
        Harness(Server(mapOf("/api/push/subscriptions" to (201 to "{}")))).use { h ->
            h.signedIn()
            h.onMain { h.model.registerRelaySubscription(endpoint, DeviceKeys.generate()) }
            h.onMain { h.model.registerPushDevice("fcm-token-1") }
            val delete = h.server.requests.indexOfFirst { it.startsWith("DELETE /api/push/subscriptions") }
            val file = h.server.requests.indexOfFirst { it.startsWith("POST /api/push/devices") }
            assertTrue(delete >= 0 && h.server.requests[delete].contains(endpoint), h.server.requests.toString())
            assertTrue(delete < file)
            h.onMain { h.model.logout() }
            withTimeout(5_000) { while (h.server.requests.none { it.startsWith("POST /api/auth/logout") }) delay(20) }
            assertEquals(1, h.server.requests.count { it.startsWith("DELETE /api/push/subscriptions") })
        }
    }

    // MARK: - Account isolation

    @Test
    fun testEitherTeardownRotatesTheRelayKeys() = runBlocking {
        for (teardown in listOf("logout", "401")) {
            Harness(Server(emptyMap())).use { h ->
                h.signedIn()
                val keys = net.amiantos.lurkerkit.push.RelayPushKeys(InMemorySecureStorage())
                h.model.onPushStateReset = keys::forget
                val old = assertNotNull(keys.loadOrCreate())
                // The last account's push, still queued at FCM when the next one signs in.
                val queued = encryptForTest("""{"title":"t","tag":"x"}""".toByteArray(), old)
                h.onMain { if (teardown == "logout") h.model.logout() else h.model.handle(ServerFrame.Unauthorized) }
                val next = assertNotNull(keys.loadOrCreate())
                assertEquals(null, net.amiantos.lurkerkit.push.WebPushDecrypt.decrypt(queued, next), teardown)
            }
        }
    }

    @Test
    fun testAReplyFromAnEndedSessionSetsNothing() = runBlocking {
        val endpoint = "https://push.lurker.chat/relay-to/fcm/tok123/$key"
        val gate = java.util.concurrent.CountDownLatch(1)
        Harness(Server(mapOf("/api/push/subscriptions" to (403 to "{}")), gates = mapOf("/api/push/subscriptions" to gate))).use { h ->
            h.signedIn()
            val pending = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                h.onMain { h.model.registerRelaySubscription(endpoint, DeviceKeys.generate()) }
            }
            withTimeout(5_000) { while (h.server.requests.none { it.startsWith("POST /api/push/subscriptions") }) delay(20) }
            // The account ends while the 403 is on its way.
            h.onMain { h.model.handle(ServerFrame.Unauthorized) }
            gate.countDown()
            assertEquals(ChatViewModel.RelayRegistration.Failed, pending.await())
            // "Relay off" was about the last session's server: nothing for Settings to say now.
            assertEquals(null, h.model.appPushUnavailable.value)
        }
    }

    @Test
    fun testAConfigFromAnEndedSessionSetsNothing() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        Harness(Server(mapOf(config("""["webpush"]""", null)), gates = mapOf("/api/push/config" to gate))).use { h ->
            h.signedIn()
            val pending = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                h.onMain { h.model.pushRoute(false) }
            }
            withTimeout(5_000) { while (h.server.requests.none { it.startsWith("GET /api/push/config") }) delay(20) }
            h.onMain { h.model.handle(ServerFrame.Unauthorized) }
            gate.countDown()
            assertEquals(null, pending.await())
            assertEquals(null, h.model.appPushUnavailable.value)
        }
    }
}
