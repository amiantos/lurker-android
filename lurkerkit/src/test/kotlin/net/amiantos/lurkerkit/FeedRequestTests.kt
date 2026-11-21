// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.amiantos.lurkerkit.model.FeedAnswer
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.PersistedSession
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.SettingsCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The requests themselves, against a canned server: what a 503 from each feed read comes back
 * as (lurker#1308), and which route each way of removing an upload asks for (lurker#1295). The
 * rules these calls apply are tested where they live; this is that the calls apply them.
 *
 * Port note: LurkerKit answers over a loopback socket (`RoutedServer`); an interceptor answers
 * here, on OkHttp's own threads, as in `RelayPushSessionTests`. A view model on a thread of its
 * own stands in for main.
 */
class FeedRequestTests {

    /** Answers by "METHOD /path" (no query string), recording each; any other request is a 404. */
    private class Server(val routes: Map<String, Pair<Int, String>>) {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val http: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val key = "${request.method} ${request.url.encodedPath}"
                requests.add(key)
                val (status, text) = routes[key] ?: (404 to """{"error":"not found"}""")
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
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(main + SupervisorJob())
        lateinit var model: ChatViewModel

        suspend fun <T> onMain(block: suspend () -> T): T = withContext(main) { block() }

        override fun close() {
            scope.cancel()
            main.close()
        }

        suspend fun signIn() = onMain {
            val sessions = SessionStore(InMemorySecureStorage())
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
            assertEquals(ChatViewModel.SessionState.LoggedIn, model.session)
        }
    }

    private fun signedIn(vararg routes: Pair<String, Pair<Int, String>>, body: suspend (Harness) -> Unit) {
        runBlocking {
            Harness(Server(mapOf(*routes))).use { h ->
                h.signIn()
                body(h)
            }
        }
    }

    private fun asked(server: Server, key: String): Int = synchronized(server.requests) { server.requests.count { it == key } }

    // MARK: - 503 (lurker#1308)

    @Test
    fun testA503FromSearchCarriesTheServersReason() =
        signedIn("GET /api/search" to (503 to """{"error":"search took too long"}""")) { h ->
            val answer = h.onMain { h.model.searchMessages("needle") }
            assertEquals(FeedAnswer.Unavailable(reason = "search took too long"), answer)
            assertEquals(ChatViewModel.SessionState.LoggedIn, h.onMain { h.model.session }, "a busy search is not a lost session")
        }

    @Test
    fun testA503FromHighlightsCarriesTheServersReason() =
        signedIn("GET /api/highlights" to (503 to """{"error":"too many searches are waiting"}""")) { h ->
            val answer = h.onMain { h.model.fetchHighlights() }
            assertEquals(FeedAnswer.Unavailable(reason = "too many searches are waiting"), answer)
        }

    @Test
    fun testA503FromActivityCarriesTheServersReason() =
        signedIn("GET /api/activity" to (503 to """{"error":"search superseded"}""")) { h ->
            val answer = h.onMain { h.model.fetchActivity() }
            assertEquals(FeedAnswer.Unavailable(reason = "search superseded"), answer)
            assertEquals(0, asked(h.server, "GET /api/highlights"), "only a 404 falls back to the older feed")
        }

    @Test
    fun testA503FromTheOlderFeedBehindActivityIsCarriedToo() =
        // A server without `/api/activity` answers 404 and the read falls back to highlights,
        // which can be the one that is busy.
        signedIn("GET /api/highlights" to (503 to """{"error":"search took too long"}""")) { h ->
            val answer = h.onMain { h.model.fetchActivity() }
            assertEquals(FeedAnswer.Unavailable(reason = "search took too long"), answer)
        }

    @Test
    fun testAnyOtherFailureIsPlainFailure() =
        signedIn("GET /api/search" to (500 to """{"error":"boom"}""")) { h ->
            val answer = h.onMain { h.model.searchMessages("needle") }
            assertEquals(FeedAnswer.Failed, answer)
        }

    @Test
    fun testAPageStillReadsAsAPage() =
        signedIn("GET /api/search" to (200 to """{"items":[],"nextBefore":null}""")) { h ->
            val answer = h.onMain { h.model.searchMessages("needle") }
            assertEquals(FeedAnswer.Page(HighlightsPage(items = emptyList(), nextBefore = null)), answer)
        }

    // MARK: - Removing an upload (lurker#1295)

    @Test
    fun testForgettingAsksTheRecordRouteAndNeverTheDeleteRoute() =
        signedIn(
            "DELETE /api/uploads/7/record" to (200 to """{"ok":true}"""),
            "DELETE /api/uploads/7" to (200 to """{"ok":true}"""),
        ) { h ->
            val refusal = h.onMain { h.model.forgetUpload(id = 7) }
            assertNull(refusal)
            assertEquals(1, asked(h.server, "DELETE /api/uploads/7/record"))
            assertEquals(0, asked(h.server, "DELETE /api/uploads/7"), "that one destroys the file")
        }

    @Test
    fun testDeletingAsksTheDeleteRoute() =
        signedIn(
            "DELETE /api/uploads/7/record" to (200 to """{"ok":true}"""),
            "DELETE /api/uploads/7" to (200 to """{"ok":true}"""),
        ) { h ->
            val refusal = h.onMain { h.model.deleteUpload(id = 7) }
            assertNull(refusal)
            assertEquals(1, asked(h.server, "DELETE /api/uploads/7"))
            assertEquals(0, asked(h.server, "DELETE /api/uploads/7/record"))
        }

    @Test
    fun testARefusedRemovalComesBackInTheServersWords() =
        signedIn("DELETE /api/uploads/7/record" to (503 to """{"error":"try again shortly"}""")) { h ->
            val refusal = h.onMain { h.model.forgetUpload(id = 7) }
            assertEquals("try again shortly", refusal)
        }

    @Test
    fun testDeletingARowThatIsAlreadyGoneIsDone() =
        // Removed on another device while this list was up.
        signedIn("DELETE /api/uploads/7" to (404 to """{"error":"not found"}""")) { h ->
            val refusal = h.onMain { h.model.deleteUpload(id = 7) }
            assertNull(refusal)
        }

    @Test
    fun testForgettingOnAServerWithoutTheRouteIsNotReadAsDone() =
        // Before lurker 2.7.0 the route itself is a 404; the row is still there.
        signedIn { h ->
            val refusal = h.onMain { h.model.forgetUpload(id = 7) }
            assertNotNull(refusal)
            assertEquals(1, asked(h.server, "DELETE /api/uploads/7/record"))
        }
}
