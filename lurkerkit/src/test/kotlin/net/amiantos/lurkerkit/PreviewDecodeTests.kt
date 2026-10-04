// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.decodeEach
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * One unrecognised element must not discard the batch it arrived in.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Preview response decoding"). The method names
 * are kept and each display name is the comment above it.
 */
class PreviewDecodeTests {

    /**
     * ⚠⚠ The SHIPPED function, not a rebuilt envelope. The first version of this suite decoded
     * its own `[FailableDecodable<LinkPreview>]`, which passed happily against a client that had
     * gone back to an all-or-nothing array decode — it was asserting a fact about
     * `FailableDecodable`, not about what the client does with it. The drill caught it.
     */
    private fun decodeShipped(json: String): List<LinkPreview> = LurkerClient.decodePreviews(json.encodeUtf8())

    // "a descriptor with an unknown kind costs its own row and nothing else"
    @Test
    fun unknownKindDoesNotDiscardTheBatch() {
        // ⚠⚠ `kind` and `status` are non-optional raw-value enums with no unknown case, so a
        // plain `List<LinkPreview>` decode is all-or-nothing: one descriptor from a newer instance
        // throws and takes all twenty with it. One layer up that is indistinguishable from a
        // transport failure, so the store arms the whole batch for retry — and because a decode
        // failure is deterministic, every retry fails identically. Nineteen good previews never
        // render and all twenty poll to the ceiling forever.
        //
        // It cannot fire against today's server, whose union matches the enum exactly. It is
        // pinned because this is a self-hosted product: operators upgrade on their own schedule
        // and store builds lag, and the repo treats that skew as normal everywhere else.
        val got = decodeShipped(
            """
            {"previews":[
              {"url":"https://e.test/a","status":"ok","kind":"image"},
              {"url":"https://e.test/b","status":"ok","kind":"hologram"},
              {"url":"https://e.test/c","status":"ok","kind":"page"}
            ]}
            """,
        )
        assertEquals(listOf("https://e.test/a", "https://e.test/c"), got.map { it.url })
    }

    // "an unknown status is contained the same way"
    @Test
    fun unknownStatusDoesNotDiscardTheBatch() {
        val got = decodeShipped(
            """
            {"previews":[
              {"url":"https://e.test/a","status":"quarantined","kind":"page"},
              {"url":"https://e.test/b","status":"ok","kind":"page"}
            ]}
            """,
        )
        assertEquals(listOf("https://e.test/b"), got.map { it.url })
    }

    // "a well-formed batch is unaffected"
    @Test
    fun happyPathIsUnchanged() {
        val got = decodeShipped(
            """
            {"previews":[
              {"url":"https://e.test/a","status":"ok","kind":"image","thumbWidth":1200},
              {"url":"https://e.test/b","status":"unavailable","kind":"page"}
            ]}
            """,
        )
        assertEquals(2, got.size)
        assertEquals(1200, got[0].thumbWidth)
    }

    // MARK: - Feature flags

    // "a failed or malformed answer is UNKNOWN, not 'the server says no'"
    @Test
    fun failureIsNotAVerdict() {
        // ⚠⚠ These collapsed into an all-off value, so one 502 or DNS hiccup on the single
        // /api/config call at cold launch silently disabled link previews for the whole app
        // session on an instance that has them on — with no retry and nothing to notice. A
        // default is not a statement.
        assertNull(LurkerClient.parseConfig("{}".encodeUtf8(), code = 502))
        assertNull(LurkerClient.parseConfig("not json".encodeUtf8(), code = 200))
        assertNull(LurkerClient.parseConfig("[]".encodeUtf8(), code = 200))
    }

    // "an absent features object IS an answer — an older instance without the feature"
    @Test
    fun absentFeaturesIsAVerdict() {
        assertEquals(false, LurkerClient.parseConfig("{}".encodeUtf8(), code = 200)?.features?.linkPreviews)
        assertEquals(
            false,
            LurkerClient.parseConfig("{\"features\":{}}".encodeUtf8(), code = 200)?.features?.linkPreviews,
        )
    }

    // "the config request carries the session token, or hosted can't route it"
    @Test
    fun configRequestIsAuthenticated() {
        // ⚠⚠ The server documents this endpoint as public and unauthenticated, and that is true
        // of a self-hosted instance and false of a hosted one: on lurker.chat the control plane
        // proxies /api/… to a CELL and works out which one from the caller's session. Anonymous,
        // it answers 401 "not routable" — read (correctly) as "no answer", which left link
        // previews off forever on the deployment most people use, with the settings rows hidden
        // so nothing on screen suggested anything was wrong. The browser never noticed: its
        // fetch carries the session cookie.
        val request = LurkerClient.configRequest(baseURL = "https://app.lurker.chat", token = "t0k")
        assertEquals("https://app.lurker.chat/api/config", request?.url?.toString())
        assertEquals("Bearer t0k", request?.header("Authorization"))
    }

    // "and asks anonymously before there is a session, which self-hosted allows"
    @Test
    fun configRequestWithoutATokenIsStillValid() {
        val request = LurkerClient.configRequest(baseURL = "https://irc.example", token = null)
        assertNotNull(request)
        assertNull(request.header("Authorization"))
    }

    // "reads the flag when the server sets it"
    @Test
    fun flagIsRead() {
        val on = "{\"features\":{\"linkPreviews\":true}}".encodeUtf8()
        assertEquals(true, LurkerClient.parseConfig(on, code = 200)?.features?.linkPreviews)
        val off = "{\"features\":{\"linkPreviews\":false}}".encodeUtf8()
        assertEquals(false, LurkerClient.parseConfig(off, code = 200)?.features?.linkPreviews)
    }

    // Port-only: what one `LinkPreview` makes of a descriptor, which Swift's synthesised
    // `Codable` settles without a line of code and kotlinx settles by configuration. Each
    // expectation is `JSONDecoder`'s own answer for the same text, except where a comment says
    // the two part company.

    /** As the client will have to configure it: `JSONDecoder` ignores keys it does not know. */
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(text: String): LinkPreview = json.decodeFromString(LinkPreview.serializer(), text)

    private fun refuses(text: String) {
        assertFailsWith<IllegalArgumentException>(text) { decode(text) }
    }

    /** every field arrives under its own name */
    @Test
    fun aFullDescriptorDecodes() {
        val preview = decode(
            """
            {"url":"https://e.test/a","status":"ok","kind":"video-embed","title":"T","description":"D",
             "siteName":"S","author":"A","src":"/s","thumb":"/t","thumbWidth":10,"thumbHeight":20,
             "embedUrl":"https://www.youtube-nocookie.com/embed/x","mime":"text/html",
             "expiresAt":"2026-09-11T00:00:00.000Z"}
            """,
        )
        assertEquals(
            LinkPreview(
                url = "https://e.test/a", status = LinkPreview.Status.Ok, kind = PreviewKind.VideoEmbed,
                title = "T", description = "D", siteName = "S", author = "A", src = "/s", thumb = "/t",
                thumbWidth = 10, thumbHeight = 20, embedUrl = "https://www.youtube-nocookie.com/embed/x",
                mime = "text/html", expiresAt = "2026-09-11T00:00:00.000Z",
            ),
            preview,
        )
        assertEquals(Instant.parse("2026-09-11T00:00:00Z"), preview.expiry)
    }

    /** absent and null both read as nothing, for every optional field */
    @Test
    fun optionalFieldsMayBeAbsentOrNull() {
        val bare = LinkPreview(url = "https://e.test/a", status = LinkPreview.Status.Unavailable, kind = PreviewKind.Page)
        assertEquals(bare, decode("""{"url":"https://e.test/a","status":"unavailable","kind":"page"}"""))
        assertEquals(
            bare,
            decode(
                """
                {"url":"https://e.test/a","status":"unavailable","kind":"page","title":null,
                 "description":null,"siteName":null,"author":null,"src":null,"thumb":null,
                 "thumbWidth":null,"thumbHeight":null,"embedUrl":null,"mime":null,"expiresAt":null}
                """,
            ),
        )
        assertNull(bare.expiry)
    }

    /** a key this client has never heard of costs nothing */
    @Test
    fun unknownKeysAreIgnored() {
        // ⚠ Only under `ignoreUnknownKeys`. With the default `Json` this throws, and every field
        // a newer server adds would take the descriptor with it.
        assertEquals(
            LinkPreview(url = "https://e.test/a", status = LinkPreview.Status.Ok, kind = PreviewKind.Page),
            decode("""{"url":"https://e.test/a","status":"ok","kind":"page","unknown":1,"nested":{"a":[1,{"b":null}]}}"""),
        )
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(
                LinkPreview.serializer(),
                """{"url":"https://e.test/a","status":"ok","kind":"page","unknown":1}""",
            )
        }
    }

    /** the three required fields are required */
    @Test
    fun aMissingRequiredFieldThrows() {
        refuses("""{"status":"ok","kind":"page"}""")
        refuses("""{"url":"https://e.test/a","kind":"page"}""")
        refuses("""{"url":"https://e.test/a","status":"ok"}""")
        refuses("""{"url":null,"status":"ok","kind":"page"}""")
        refuses("""{"url":"https://e.test/a","status":null,"kind":"page"}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":null}""")
        refuses("{}")
    }

    /** a kind or status outside the enum throws, which is what `decodeEach` exists to contain */
    @Test
    fun anUnknownCaseThrows() {
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"hologram"}""")
        refuses("""{"url":"https://e.test/a","status":"quarantined","kind":"page"}""")
        // The raw value, not the case's name, and exactly as cased.
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"videoEmbed"}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"VIDEO"}""")
        refuses("""{"url":"https://e.test/a","status":"OK","kind":"video"}""")
    }

    /** a value of the wrong JSON type throws rather than being coerced */
    @Test
    fun aValueOfTheWrongTypeThrows() {
        refuses("""{"url":1,"status":"ok","kind":"page"}""")
        refuses("""{"url":"https://e.test/a","status":1,"kind":"page"}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":true}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","title":5}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","title":true}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","title":["x"]}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","expiresAt":1790000000}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","thumbWidth":true}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","thumbWidth":1200.5}""")
        refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","thumbHeight":[]}""")
    }

    /** where a number is read differently from iOS */
    @Test
    fun numbersAtTheEdges() {
        fun width(literal: String): Int? =
            decode("""{"url":"https://e.test/a","status":"ok","kind":"page","thumbWidth":$literal}""").thumbWidth
        fun refusesWidth(literal: String) =
            refuses("""{"url":"https://e.test/a","status":"ok","kind":"page","thumbWidth":$literal}""")
        // As on iOS.
        assertEquals(1200, width("1200"))
        assertEquals(-1, width("-1"))
        assertEquals(1200, width("12e2"))
        assertEquals(1, width("100e-2"))
        assertEquals(Int.MAX_VALUE, width("2147483647"))
        refusesWidth("1e30")
        // iOS decodes these two (1200): a whole number written with a fraction.
        refusesWidth("1200.0")
        refusesWidth("1.2e3")
        // iOS decodes this (its `Int` is 64-bit). No picture is this wide.
        refusesWidth("2147483648")
        // iOS throws on this: a number in quotes is a string there.
        assertEquals(1200, width("\"1200\""))
    }

    /** the envelope's elements are decoded one by one, so a bad one costs only itself */
    @Test
    fun decodeEachContainsABadDescriptor() {
        // The shape `LurkerClient.decodePreviews` has, built from its parts; the suite above
        // pins the shipped function.
        val envelope = Json.parseToJsonElement(
            """
            {"previews":[
              {"url":"https://e.test/a","status":"ok","kind":"image"},
              {"url":"https://e.test/b","status":"ok","kind":"hologram"},
              {"url":"https://e.test/c","status":"ok","kind":"page","new":true}
            ]}
            """,
        ) as JsonObject
        val got = json.decodeEach(LinkPreview.serializer(), envelope["previews"] as JsonArray)
        assertEquals(listOf("https://e.test/a", "https://e.test/c"), got.map { it.url })
    }

    /** encoding writes the raw values back, and leaves out what is absent */
    @Test
    fun encodesAsLurkerKitDoes() {
        val preview = LinkPreview(
            url = "https://e.test/a", status = LinkPreview.Status.Ok, kind = PreviewKind.VideoEmbed, thumbWidth = 10,
        )
        assertEquals(
            Json.parseToJsonElement("""{"url":"https://e.test/a","status":"ok","kind":"video-embed","thumbWidth":10}"""),
            Json.encodeToJsonElement(LinkPreview.serializer(), preview),
        )
    }

    /**
     * `fetchConfig`, sent: the bearer goes with it, its own 10 s timeout is the one in force
     * (LurkerKit's `timeoutInterval`, carried here as a request tag), and an answer to a session
     * that has since ended is no answer.
     */
    @Test
    fun fetchConfigSendsTheTokenWithItsOwnTimeout() = runTest {
        val seen = mutableListOf<Pair<String?, Int>>()
        var client: LurkerClient? = null
        var signOutMidFlight = false
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                seen.add(chain.request().header("Authorization") to chain.readTimeoutMillis())
                // On OkHttp's thread, while the test's coroutine is suspended waiting for this
                // answer — so nothing else touches the client meanwhile.
                if (signOutMidFlight) client?.close()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body("""{"features":{"linkPreviews":true},"protocolVersion":1}""".toResponseBody(null))
                    .build()
            }
            .build()
        client = LurkerClient(scope = this, onFrame = {}, httpClient = http)
        client.restore(server = "https://app.lurker.chat", token = "t0k")
        assertEquals(true, client.fetchConfig()?.features?.linkPreviews)
        assertEquals(listOf<Pair<String?, Int>>("Bearer t0k" to 10_000), seen)

        signOutMidFlight = true
        assertNull(client.fetchConfig())
    }

    // Port-only: LurkerKit's `loneSurrogateDoesNotDiscardTheBatch` over the same document.

    /**
     * a description capped mid-emoji costs nothing: the batch survives on both sides, and the
     * description ends in U+FFFD on iOS (`JSONTextRepair`) and in the lone high half the server
     * sent here
     */
    @Test
    fun loneSurrogateDoesNotDiscardTheBatchAndKeepsTheLoneHalf() {
        // A JSON escape, as six characters of JSON text.
        val loneHigh = "\\" + "u" + "d83d"
        val got = decodeShipped(
            """
            {"previews":[
              {"url":"https://e.test/a","status":"ok","kind":"page","description":"fun $loneHigh"},
              {"url":"https://e.test/b","status":"ok","kind":"page"}
            ]}
            """,
        )
        assertEquals(listOf("https://e.test/a", "https://e.test/b"), got.map { it.url })
        assertEquals("fun ${0xD83D.toChar()}", got.firstOrNull()?.description)
    }

    // Not ported: loneSurrogateDoesNotDiscardTheBatch — it pins `JSONTextRepair`'s output (the
    // lone half read as U+FFFD), a workaround for `JSONDecoder` that kotlinx does not need;
    // `loneSurrogateDoesNotDiscardTheBatchAndKeepsTheLoneHalf` pins what is read here instead.
}
