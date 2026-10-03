// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

// How a `LinkPreview`'s `src`/`thumb` becomes a request.
//
// ⚠⚠ The value is OPAQUE — the server mints it and a client never constructs or parses one.
// It arrives as either a proxy path on this instance or, when the instance has a bucket-backed
// preview byte cache turned on, an absolute URL on that bucket's public CDN. Both come through
// the same field with nothing to distinguish them, so the one place that interprets it has to
// handle both, and neither case is visible from `fetchProxiedMedia` — it only ever answers
// `MediaFetch`, so a wrong URL and an offline server look identical.

package net.amiantos.lurkerkit

import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.model.MediaFetch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewMediaRequestTests {
    private val base = "https://chat.example.com"
    private val token = "session-token-abc"

    private fun request(path: String, token: String? = "session-token-abc"): Request? =
        LurkerClient.mediaRequest(path = path, baseURL = base, token = token)

    @Test
    fun testProxyPathIsResolvedAgainstTheInstanceAndCarriesTheBearerToken() {
        val request = request("/api/link-preview/media/abc123")
        assertEquals(
            "https://chat.example.com/api/link-preview/media/abc123",
            request?.url?.toString(),
        )
        // The proxy is authenticated and native auth is a Bearer header, not a cookie.
        assertEquals(
            "Bearer session-token-abc",
            request?.header("Authorization"),
        )
    }

    @Test
    fun testAbsoluteUrlIsUsedAsIsRatherThanConcatenated() {
        // ⚠⚠ The regression this file exists for. Concatenating unconditionally produced
        // "https://chat.example.comhttps://cdn.example.com/..." — which the URL parser rejects,
        // so the fetch returned nothing and the image loader latched the path into `failed`.
        // Every cached preview went permanently blank for the rest of the session, with no
        // error surfaced anywhere and nothing in the UI to retry.
        val cdn = "https://cdn.example.com/previews/9f86d081884c7d659a2feaa0c55ad015"
        val request = request(cdn)
        assertEquals(cdn, request?.url?.toString())
    }

    @Test
    fun testAbsoluteUrlNeverCarriesTheSessionToken() {
        // ⚠⚠ A cached object lives on a host we do not control, and the HTTP client sends
        // whatever headers it is handed. A Bearer header here would put the user's session token
        // into a CDN operator's access log on every single image fetch — for a request that does
        // not need it, since the object is public by construction.
        val request = request("https://cdn.example.com/previews/9f86d081884c7d659a2feaa0c55ad015")
        assertNull(request?.header("Authorization"))
    }

    /**
     * ⚠⚠ The one branch that attaches the Bearer token, so the one branch where a malformed
     * path is a credential leak rather than a broken image. `baseURL + "api/media/x"` is
     * `https://chat.exampleapi/media/x` — host `chat.exampleapi`, a name someone else can
     * register — and the HTTP client forwards a manually-set header to whatever host it is
     * given. Unreachable via anything the current server mints, which is why it is worth
     * pinning: it turns on what the OTHER end of the wire does.
     */
    @Test
    fun testRelativePathWithoutALeadingSlashIsRefusedRatherThanChangingHost() {
        assertNull(
            LurkerClient.mediaRequest(path = "api/link-preview/media/abc", baseURL = "https://chat.example", token = "t"),
        )
    }

    @Test
    fun testNonHttpSchemesAreRefused() {
        // The value is server-minted, so this is not a threat so much as a floor: if a scheme
        // ever appears that is not http(s), fetching it is not the safe reading. `file:` in
        // particular would have the client read the device's own filesystem.
        for (path in listOf("file:///etc/passwd", "data:image/png;base64,AAAA", "ftp://example.com/x")) {
            assertNull(request(path), "should refuse $path")
        }
    }

    @Test
    fun testProxyPathStillNeedsATokenButAnAbsoluteUrlDoesNot() {
        // A signed-out client cannot fetch from the proxy — there is no header to send — but a
        // public CDN object needs nothing, so it must not be gated on the session.
        assertNull(request("/api/link-preview/media/abc123", token = null))
        assertEquals(
            "https://cdn.example.com/previews/abc",
            request("https://cdn.example.com/previews/abc", token = null)?.url?.toString(),
        )
    }

    // Port-only: the requests above, sent. An interceptor answers in place of a server.

    private fun answering(code: Int, body: String = "", seen: MutableList<Request>? = null): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                seen?.add(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(body.toResponseBody(null))
                    .build()
            }
            .build()

    /** A 503 (and any 5xx, and a 429) means come back; a 404 is an answer. */
    @Test
    fun testFetchProxiedMediaTellsRetryableFromPermanent() = runTest {
        fun client(code: Int): LurkerClient =
            LurkerClient(scope = this, onFrame = {}, httpClient = answering(code, "bytes")).also {
                it.restore(server = base, token = token)
            }
        assertEquals(MediaFetch.Success("bytes".encodeUtf8()), client(200).fetchProxiedMedia("/api/link-preview/media/a"))
        assertEquals(MediaFetch.Retryable, client(503).fetchProxiedMedia("/api/link-preview/media/a"))
        assertEquals(MediaFetch.Retryable, client(429).fetchProxiedMedia("/api/link-preview/media/a"))
        assertEquals(MediaFetch.Retryable, client(502).fetchProxiedMedia("/api/link-preview/media/a"))
        assertEquals(MediaFetch.Permanent, client(404).fetchProxiedMedia("/api/link-preview/media/a"))
        assertEquals(MediaFetch.Permanent, client(200).fetchProxiedMedia("api/link-preview/media/a"))
    }

    /** What goes out for each kind of value: the token to this instance only. */
    @Test
    fun testFetchProxiedMediaSendsTheTokenOnlyToTheInstance() = runTest {
        val seen = mutableListOf<Request>()
        val client = LurkerClient(scope = this, onFrame = {}, httpClient = answering(200, "x", seen))
        client.restore(server = base, token = token)
        client.fetchProxiedMedia("/api/link-preview/media/a")
        client.fetchProxiedMedia("https://cdn.example.com/previews/b")
        assertEquals("Bearer session-token-abc", seen[0].header("Authorization"))
        assertNull(seen[1].header("Authorization"))
    }
}

/**
 * The address `playableMediaURL` hands to the media player.
 *
 * ⚠⚠ `LinkPreview.isViewable` is the gate — nothing reaches a player page without passing it —
 * and this is the backstop, asked of the same `LocalNetworking`. It is pinned because the
 * failure it prevents is invisible from here: a public cleartext address is handed over, the
 * platform's cleartext policy refuses it several layers down (on iOS, App Transport Security),
 * and the page shows a sentence about the file rather than about the policy that stopped it.
 */
class PlayableMediaURLTests {

    private suspend fun playable(path: String, client: LurkerClient): String? =
        client.playableMediaURL(path = path, mime = "video/mp4")

    @Test
    fun testHttpsIsHandedOverUnchanged() = runTest {
        val url = playable("https://cdn.example.com/a.mp4", LurkerClient(scope = this, onFrame = {}))
        assertEquals("https://cdn.example.com/a.mp4", url)
    }

    // Left out: testUppercaseSchemeIsStillRecognisedAsAbsolute. It expects the address back
    // exactly as written ("HTTPS://…"), which is `URL`'s doing on iOS. Here the address is
    // handed over as `HttpUrl` writes it, because the player must get the host `isViewable`
    // checked (LEDGER, T2); that writes the scheme lowercase. What the test is about — a scheme
    // in capitals is still recognised as absolute — is pinned port-only below.

    @Test
    fun testLocalNetworkCleartextIsHandedOver() = runTest {
        val url = playable("http://box.local/a.mp4", LurkerClient(scope = this, onFrame = {}))
        assertEquals("http://box.local/a.mp4", url)
    }

    @Test
    fun testPublicCleartextIsRefusedRatherThanLeftToATS() = runTest {
        val url = playable("http://cdn.example.com/a.mp4", LurkerClient(scope = this, onFrame = {}))
        assertNull(url)
    }

    @Test
    fun testCleartextWithNoHostIsRefused() = runTest {
        val url = playable("http:///a.mp4", LurkerClient(scope = this, onFrame = {}))
        assertNull(url)
    }

    // Port-only:

    /**
     * A scheme in capitals is recognised as absolute — not refused, and not sent to the proxy
     * branch — and handed over as `HttpUrl` writes it.
     */
    @Test
    fun testUppercaseSchemeIsRecognisedAndHandedOverAsHttpUrlWritesIt() = runTest {
        val url = playable("HTTPS://cdn.example.com/a.mp4", LurkerClient(scope = this, onFrame = {}))
        assertEquals("https://cdn.example.com/a.mp4", url)
    }

    /**
     * A proxy path is streamed to a file named from the path, and the file is handed over as a
     * `file:` URI; a second ask finds it and fetches nothing.
     */
    @Test
    fun testAProxyPathIsStagedToAFileAndReused() = runTest {
        var fetches = 0
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                fetches += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body("clip".toResponseBody(null))
                    .build()
            }
            .build()
        val client = LurkerClient(scope = this, onFrame = {}, httpClient = http)
        client.restore(server = "https://chat.example.com", token = "t")
        val path = "/api/link-preview/media/port-only-${System.nanoTime()}"
        try {
            val first = assertNotNull(client.playableMediaURL(path = path, mime = "video/mp4"))
            val file = File(URI(first))
            assertTrue(file.name.endsWith(".mp4"))
            assertEquals("clip", file.readText())
            assertEquals(first, client.playableMediaURL(path = path, mime = "video/mp4"))
            assertEquals(1, fetches)
        } finally {
            client.clearStagedMedia()
        }
    }
}
