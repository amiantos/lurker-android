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
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.PersistedSession
import net.amiantos.lurkerkit.session.SessionCodec
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.SettingsCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * lurker-ios#218, ported: a sign-out made offline must still reach the server. Until it does the
 * token stays live there (OAuth tokens never expire), and so do the push registrations made through
 * it.
 *
 * Port note: LurkerKit answers over a loopback socket; here an interceptor answers, as
 * `SocketCloseTests` does for REST. It runs on OkHttp's own threads, as a server's reply would.
 */
class PendingRevokeTests {

    // MARK: - The verdict

    @Test
    fun testOnlyLurkersOwnAnswerEndsTheRetries() {
        val ok = """{"ok":true}""".encodeUtf8()
        assertEquals(LurkerClient.RevokeOutcome.Done, LurkerClient.revokeOutcome(200, ok))
        // Not Lurker's: a captive portal's 200, a gateway's 401, a WAF, a maintenance page.
        for ((status, body) in listOf(
            200 to "<html>".encodeUtf8(),
            200 to """{"ok":false}""".encodeUtf8(),
            401 to null,
            403 to null,
            404 to null,
            429 to null,
            503 to null,
        )) {
            assertEquals(LurkerClient.RevokeOutcome.Retry, LurkerClient.revokeOutcome(status, body), "$status")
        }
        assertEquals(LurkerClient.RevokeOutcome.Retry, LurkerClient.revokeOutcome(null, null))
    }

    // MARK: - The queue

    @Test
    fun testTheQueueOutlivesTheStoreThatWroteIt() {
        val storage = InMemorySecureStorage()
        val a = PersistedSession(server = "https://a.example", token = "ta")
        val b = PersistedSession(server = "https://b.example", token = "tb")
        SessionStore(storage).addPendingRevoke(a)
        SessionStore(storage).addPendingRevoke(b)
        SessionStore(storage).addPendingRevoke(a)
        assertEquals(listOf("ta", "tb"), SessionStore(storage).pendingRevokes().map { it.token })
        SessionStore(storage).removePendingRevoke("ta")
        assertEquals(listOf("https://b.example"), SessionStore(storage).pendingRevokes().map { it.server })
    }

    @Test
    fun testTheQueueIsSeparateFromTheLiveSession() {
        val store = SessionStore(InMemorySecureStorage())
        store.save(PersistedSession(server = "https://a.example", token = "live"))
        store.addPendingRevoke(PersistedSession(server = "https://a.example", token = "old"))
        store.clear()
        assertEquals(null, store.load())
        assertEquals(listOf("old"), store.pendingRevokes().map { it.token })
    }

    @Test
    fun testAnUnreadableQueueIsEmptyNotACrash() {
        assertEquals(emptyList(), SessionCodec.decodeList("garbage".encodeUtf8()))
        val mixed = """[{"server":"https://a","token":"t","since":0},{"server":"","token":"x","since":0}]"""
        assertEquals(listOf("t"), SessionCodec.decodeList(mixed.encodeUtf8()).map { it.token })
    }

    // MARK: - Against a server that answers

    /** Answers every request with one status and body (or no answer at all), recording each. */
    private class Answering(val status: Int?, val body: String = "", val delayMs: Long = 0) {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val http: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests.add("${request.method} ${request.url.encodedPath} ${request.header("Authorization")}")
                if (delayMs > 0) Thread.sleep(delayMs)
                if (status == null) throw IOException("no answer")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("canned")
                    .body(body.toResponseBody(null))
                    .build()
            }
            .build()
    }

    /** A view model on a thread of its own, standing in for main. */
    private class Harness(val server: Answering, val storage: InMemorySecureStorage = InMemorySecureStorage()) : AutoCloseable {
        val sessions = SessionStore(storage)
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(main + SupervisorJob())
        lateinit var model: ChatViewModel

        /** Build the view model (which runs the launch retry). Held in the background, so a restored
         *  session doesn't connect and only the revoke traffic reaches the server. */
        suspend fun launch() = onMain {
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

        suspend fun waitUntil(condition: suspend () -> Boolean) {
            withTimeout(5_000) { while (!onMain { condition() }) delay(20) }
        }

        override fun close() {
            scope.cancel()
            main.close()
        }
    }

    @Test
    fun testALaunchRetriesAndAFinalAnswerEndsIt() = runBlocking {
        Harness(Answering(200, """{"ok":true}""")).use { h ->
            h.sessions.addPendingRevoke(PersistedSession(server = "https://lurker.test", token = "old"))
            h.launch()
            h.waitUntil { h.sessions.pendingRevokes().isEmpty() }
            assertEquals("POST /api/auth/logout Bearer old", h.server.requests.first())
        }
    }

    @Test
    fun testATemporaryAnswerKeepsItOwedAndReachabilityAndForegroundAskAgain() = runBlocking {
        Harness(Answering(503)).use { h ->
            h.sessions.addPendingRevoke(PersistedSession(server = "https://lurker.test", token = "old"))
            h.launch()
            h.waitUntil { h.server.requests.size == 1 && h.model.revoking.isEmpty() }
            assertEquals(listOf("old"), h.sessions.pendingRevokes().map { it.token })

            h.onMain { h.model.setReachable(false); h.model.setReachable(true) }
            h.waitUntil { h.server.requests.size == 2 && h.model.revoking.isEmpty() }
            assertEquals(listOf("old"), h.sessions.pendingRevokes().map { it.token })

            h.onMain { h.model.enterForeground() }
            h.waitUntil { h.server.requests.size == 3 }
        }
    }

    @Test
    fun testATriggerDuringARequestIsNotLost() = runBlocking {
        Harness(Answering(503, delayMs = 400)).use { h ->
            h.sessions.addPendingRevoke(PersistedSession(server = "https://lurker.test", token = "old"))
            h.launch()
            h.waitUntil { h.server.requests.size == 1 }
            assertEquals(setOf("old"), h.onMain { h.model.revoking })
            h.onMain { h.model.setReachable(false); h.model.setReachable(true) }
            // The first is still out, so nothing new yet; once it fails, the replay goes.
            h.waitUntil { h.server.requests.size == 2 && h.model.revoking.isEmpty() }
            delay(600)
            assertEquals(2, h.server.requests.size)
        }
    }

    @Test
    fun testAnOwedRevokeExpires() = runBlocking {
        Harness(Answering(503)).use { h ->
            val longAgo = Instant.now().minus(ChatViewModel.revokeRetryWindow).minusSeconds(60)
            h.sessions.addPendingRevoke(PersistedSession(server = "https://lurker.test", token = "ancient"), longAgo)
            h.sessions.addPendingRevoke(PersistedSession(server = "https://lurker.test", token = "recent"))
            h.launch()
            h.waitUntil { h.server.requests.size == 1 && h.model.revoking.isEmpty() }
            assertEquals(listOf("recent"), h.sessions.pendingRevokes().map { it.token })
            assertTrue(h.server.requests.first().endsWith("Bearer recent"))
        }
    }

    @Test
    fun testASignOutTheServerNeverHeardStaysOwed() = runBlocking {
        Harness(Answering(status = null)).use { h ->
            h.sessions.save(PersistedSession(server = "https://lurker.test", token = "departing"))
            h.launch()
            h.onMain { h.model.logout() }
            // Owed the moment the session ends, before the request has gone anywhere.
            assertEquals(null, h.sessions.load())
            assertEquals(listOf("departing"), h.sessions.pendingRevokes().map { it.token })
            // And still owed once the request has failed.
            h.waitUntil { h.server.requests.any { it.endsWith("Bearer departing") } && h.model.revoking.isEmpty() }
            assertEquals(listOf("departing"), h.sessions.pendingRevokes().map { it.token })
        }
    }
}
