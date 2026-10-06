// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.PendingSignIn
import net.amiantos.lurkerkit.session.PersistedSession
import net.amiantos.lurkerkit.session.SessionCodec
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.SettingsCache
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port-only: the whole file. LurkerKit has no `PendingSignIn` — iOS's browser sheet runs inside the
// app, so a process that dies takes the sheet with it. On Android the system can kill Lurker while the
// Custom Tab is up, and the redirect then starts a process that never saw the attempt.
class SignInResumeTests {

    private val server = "https://lurker.test"

    /**
     * Answers the OAuth endpoints, and 404s everything else (the config and networks fetches, the
     * socket) so a session that signs in goes nowhere. Records each request with its body.
     */
    private class OAuthServer(val tokenStatus: Int = 200) {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val http: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                requests.add("${request.method} ${request.url.encodedPath} $body")
                val (status, reply) = when (request.url.encodedPath) {
                    "/api/oauth/register" -> 201 to """{"client_id":"cid"}"""
                    "/api/oauth/token" ->
                        if (tokenStatus == 200) 200 to """{"access_token":"tok"}""" else tokenStatus to """{"error":"invalid_grant"}"""
                    else -> 404 to ""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("canned")
                    .body(reply.toResponseBody(null))
                    .build()
            }
            .build()

        val tokenRequests: List<String> get() = requests.filter { it.startsWith("POST /api/oauth/token") }
    }

    /** One process: a view model on a thread of its own, standing in for main, over [storage]. */
    private class Process(val server: OAuthServer, val storage: InMemorySecureStorage) : AutoCloseable {
        val sessions = SessionStore(storage)
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(main + SupervisorJob())
        lateinit var model: ChatViewModel

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

        suspend fun status(): String? = onMain { model.statusPublisher.replayCache.lastOrNull() }

        override fun close() {
            scope.cancel()
            main.close()
        }
    }

    private fun pendingIn(storage: InMemorySecureStorage): PendingSignIn? =
        storage.stored[SessionStore.pendingSignInAccount]?.let { SessionCodec.decodePendingSignIn(it) }

    @Test
    fun testTheAttemptIsKeptOnlyWhileThePageIsUp() = runBlocking {
        val storage = InMemorySecureStorage()
        Process(OAuthServer(), storage).use { p ->
            p.launch()
            var whileUp: PendingSignIn? = null
            var page: HttpUrl? = null
            val signedIn = p.onMain {
                p.model.signIn(server = server, appName = "Lurker") { shown ->
                    page = shown
                    whileUp = pendingIn(storage)
                    null // the user cancelled
                }
            }
            assertFalse(signedIn)
            val kept = assertNotNull(whileUp)
            assertEquals(server, kept.server)
            assertEquals("cid", kept.clientId)
            assertEquals(page?.queryParameter("state"), kept.state)
            assertNull(storage.stored[SessionStore.pendingSignInAccount])
            assertEquals(ChatViewModel.SessionState.LoggedOut, p.onMain { p.model.session })
        }
    }

    @Test
    fun testARedirectAfterTheProcessDiedStillSignsIn() = runBlocking {
        val storage = InMemorySecureStorage()
        val oauth = OAuthServer()
        // The first process opens the page, and is killed while it's up.
        var state: String? = null
        Process(oauth, storage).use { first ->
            first.launch()
            val shown = CompletableDeferred<Unit>()
            first.scope.launch {
                first.model.signIn(server = server, appName = "Lurker") { page ->
                    state = page.queryParameter("state")
                    shown.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(5_000) { shown.await() }
        }
        val verifier = assertNotNull(pendingIn(storage)).verifier

        // The redirect starts a second one.
        Process(oauth, storage).use { second ->
            second.launch()
            val signedIn = second.onMain { second.model.resumeSignIn("chat.lurker:/oauth?code=c1&state=$state") }
            assertEquals(server, signedIn)
            assertEquals(ChatViewModel.SessionState.LoggedIn, second.onMain { second.model.session })
            assertEquals(PersistedSession(server = server, token = "tok"), second.sessions.load())
            assertNull(storage.stored[SessionStore.pendingSignInAccount])
            val exchange = oauth.tokenRequests.single()
            assertTrue(exchange.contains("\"code\":\"c1\""), exchange)
            assertTrue(exchange.contains("\"code_verifier\":\"$verifier\""), exchange)
            assertTrue(exchange.contains("\"client_id\":\"cid\""), exchange)
        }
    }

    @Test
    fun testANewAttemptVoidsOneLeftInTheBrowser() = runBlocking {
        val storage = InMemorySecureStorage()
        Process(OAuthServer(), storage).use { p ->
            p.sessions.savePendingSignIn(PendingSignIn(server = server, clientId = "cid", state = "s", verifier = "v"))
            p.launch()
            // A replacement that fails before it opens a page (here, the transport policy) still voids it.
            assertFalse(p.onMain { p.model.signIn(server = "http://lurker.test", appName = "Lurker") { null } })
            assertNotNull(p.status())
            assertNull(storage.stored[SessionStore.pendingSignInAccount])
        }
    }

    @Test
    fun testARedirectWithNothingSavedSaysSo() = runBlocking {
        val oauth = OAuthServer()
        Process(oauth, InMemorySecureStorage()).use { p ->
            p.launch()
            assertNull(p.onMain { p.model.resumeSignIn("chat.lurker:/oauth?code=c1&state=s") })
            assertEquals(ChatViewModel.SessionState.LoggedOut, p.onMain { p.model.session })
            assertEquals("That sign-in has already ended. Try again.", p.status())
            assertTrue(oauth.tokenRequests.isEmpty())
        }
    }

    @Test
    fun testAnAnswerToAnotherAttemptSpendsNothing() = runBlocking {
        val storage = InMemorySecureStorage()
        val oauth = OAuthServer()
        Process(oauth, storage).use { p ->
            p.sessions.savePendingSignIn(PendingSignIn(server = server, clientId = "cid", state = "ours", verifier = "v"))
            p.launch()
            // Any app or page can open the scheme; a redirect that isn't this attempt's is refused…
            assertNull(p.onMain { p.model.resumeSignIn("chat.lurker:/oauth?code=c1&state=theirs") })
            assertEquals("Sign-in didn't finish. Try again.", p.status())
            assertTrue(oauth.tokenRequests.isEmpty())
            // …and leaves the attempt for the real one.
            assertEquals(server, p.onMain { p.model.resumeSignIn("chat.lurker:/oauth?code=c2&state=ours") })
            assertTrue(oauth.tokenRequests.single().contains("\"code\":\"c2\""))
            assertNull(storage.stored[SessionStore.pendingSignInAccount])
        }
    }

    @Test
    fun testADuplicateRedirectKeepsTheRealReason() = runBlocking {
        val oauth = OAuthServer(tokenStatus = 500)
        Process(oauth, InMemorySecureStorage()).use { p ->
            p.sessions.savePendingSignIn(PendingSignIn(server = server, clientId = "cid", state = "s", verifier = "v"))
            p.launch()
            assertNull(p.onMain { p.model.resumeSignIn("chat.lurker:/oauth?code=c1&state=s") })
            assertEquals("Sign-in failed (HTTP 500).", p.status())
            // The browser dispatches it again: nothing is saved now, and the reason stands.
            assertNull(p.onMain { p.model.resumeSignIn("chat.lurker:/oauth?code=c1&state=s") })
            assertEquals("Sign-in failed (HTTP 500).", p.status())
            assertEquals(1, oauth.tokenRequests.size)
        }
    }

    @Test
    fun testSignOutForgetsAnAttemptLeftInTheBrowser() = runBlocking {
        val storage = InMemorySecureStorage()
        Process(OAuthServer(), storage).use { p ->
            p.sessions.save(PersistedSession(server = server, token = "live"))
            p.sessions.savePendingSignIn(PendingSignIn(server = server, clientId = "cid", state = "s", verifier = "v"))
            p.launch()
            p.onMain { p.model.logout() }
            assertNull(storage.stored[SessionStore.pendingSignInAccount])
        }
    }

    @Test
    fun testASpentCodeEndsTheAttempt() = runBlocking {
        val storage = InMemorySecureStorage()
        val oauth = OAuthServer(tokenStatus = 400)
        Process(oauth, storage).use { p ->
            p.sessions.savePendingSignIn(PendingSignIn(server = server, clientId = "cid", state = "s", verifier = "v"))
            p.launch()
            assertNull(p.onMain { p.model.resumeSignIn("chat.lurker:/oauth?code=c1&state=s") })
            assertEquals(ChatViewModel.SessionState.LoggedOut, p.onMain { p.model.session })
            assertEquals(1, oauth.tokenRequests.size)
            assertNull(p.sessions.load())
        }
    }

    @Test
    fun testAStrayRedirectNeverTouchesALiveSession() = runBlocking {
        val storage = InMemorySecureStorage()
        val oauth = OAuthServer()
        Process(oauth, storage).use { p ->
            p.sessions.save(PersistedSession(server = server, token = "live"))
            p.sessions.savePendingSignIn(PendingSignIn(server = server, clientId = "cid", state = "s", verifier = "v"))
            p.launch()
            assertNull(p.onMain { p.model.resumeSignIn("chat.lurker:/oauth?code=c1&state=s") })
            assertEquals(ChatViewModel.SessionState.LoggedIn, p.onMain { p.model.session })
            assertEquals("live", p.sessions.load()?.token)
            assertTrue(oauth.tokenRequests.isEmpty())
        }
    }

    @Test
    fun testAnUnreadableAttemptIsNone() {
        assertNull(SessionCodec.decodePendingSignIn("garbage".encodeUtf8()))
        assertNull(SessionCodec.decodePendingSignIn("""{"server":"https://a","clientId":"c","state":"s"}""".encodeUtf8()))
        assertNull(SessionCodec.decodePendingSignIn("""{"server":"https://a","clientId":"","state":"s","verifier":"v"}""".encodeUtf8()))
        val pending = PendingSignIn(server = "https://a", clientId = "c", state = "s", verifier = "v")
        assertEquals(pending, SessionCodec.decodePendingSignIn(SessionCodec.encodePendingSignIn(pending)))
    }
}
