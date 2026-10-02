// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import net.amiantos.lurkerkit.client.decodeEach
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * One unrecognised element must not discard the batch it arrived in.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Preview response decoding"). Every case in it
 * goes through `LurkerClient` — `decodePreviews`, `parseConfig`, `configRequest` — and the suite
 * says why it must: it is "the SHIPPED function, not a rebuilt envelope" that is under test. So
 * none of it is ported until `LurkerClient` is, and what is here meanwhile is port-only.
 */
class PreviewDecodeTests {

    // Waiting on LurkerClient (`decodePreviews`): unknownKindDoesNotDiscardTheBatch,
    // unknownStatusDoesNotDiscardTheBatch, happyPathIsUnchanged
    //
    // MARK: - Feature flags
    //
    // Waiting on LurkerClient (`parseConfig`, `configRequest`): failureIsNotAVerdict,
    // absentFeaturesIsAVerdict, configRequestIsAuthenticated, configRequestWithoutATokenIsStillValid,
    // flagIsRead

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
        // The shape `LurkerClient.decodePreviews` will have, built from the ported parts; the
        // suite above pins the shipped function once there is one.
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
}
