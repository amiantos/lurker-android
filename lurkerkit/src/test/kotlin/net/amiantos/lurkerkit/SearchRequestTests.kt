// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.SearchQuery
import net.amiantos.lurkerkit.model.SearchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Building the `GET /api/search` URL (lurker-ios#123).
 *
 * ⚠⚠ Every rule here fails silently when it is wrong: the server answers a mis-encoded query with
 * the wrong ROWS rather than with an error, and "these results look a bit off" is not something a
 * person can debug from the outside. That is the whole reason the URL is built somewhere testable
 * instead of inline in the fetch.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Search request"), where each case carries a
 * display name. The method names are kept and the display name is the comment above each.
 */
class SearchRequestTests {

    private fun url(raw: String, networkId: Int? = null, before: Long? = null, limit: Int = 50): String =
        SearchRequest.url(
            base = "https://lurker.test",
            query = SearchQuery.parse(raw),
            networkId = networkId,
            before = before,
            limit = limit,
        )?.toString() ?: "<nil>"

    /** a plain query carries just the text and the limit */
    @Test
    fun plainQuery() {
        assertEquals("https://lurker.test/api/search?q=hello&limit=50", url("hello"))
    }

    /** a literal + survives, because the server reads a bare one as a space */
    @Test
    fun plusIsEncoded() {
        // ⚠⚠ The regression this whole file exists for. On iOS `URLComponents` leaves `+` alone
        // (it is legal in a query), the server parses with form-urlencoded semantics where `+`
        // MEANS SPACE, and so a search for `C++` silently became a search for `C  `. The WS verb
        // could not get this wrong — JSON carries a `+` literally — so it is new with the
        // migration. (`HttpUrl.encodedQuery` leaves a `+` alone in just the same way.)
        assertEquals("https://lurker.test/api/search?q=C%2B%2B&limit=50", url("C++"))
        // ...and a real space is still a space, which is what makes the blanket replace safe:
        // it is encoded as %20 and never as +.
        assertEquals("https://lurker.test/api/search?q=a%20b&limit=50", url("a b"))
    }

    /** a channel target is encoded, so the # cannot start a fragment */
    @Test
    fun channelTargetIsEncoded() {
        assertEquals("https://lurker.test/api/search?q=hi&target=%23dev&limit=50", url("in:#dev hi"))
    }

    /** every channel sigil survives, not just # */
    @Test
    fun allSigilsSurvive() {
        // ⚠⚠ `&chan` is the one that bites: unencoded it would end the parameter and invent a new
        // one. The other three are ordinary characters here, but a channel is `#&+!` and testing
        // only `#` is how that gets forgotten.
        assertTrue(url("in:&chan").contains("target=%26chan"))
        assertTrue(url("in:+chan").contains("target=%2Bchan"))
        assertTrue(url("in:!chan").contains("target=!chan"))
    }

    /** from: repeats the parameter, because that is how the route OR-matches */
    @Test
    fun nicksRepeat() {
        val out = url("from:bob from:alice hi")
        assertTrue(out.contains("nick=bob"))
        assertTrue(out.contains("nick=alice"))
        // ⚠ Not comma-joined — the route would read that as one nick containing a comma.
        assertFalse(out.contains("nick=bob,alice"))
    }

    /** an unused filter is absent, never empty */
    @Test
    fun unusedFiltersAreOmitted() {
        // ⚠⚠ The server reads an absent key as unfiltered and an EMPTY one as a filter matching
        // nothing, so `q=` would return no rows for every search. Inherited from the WS verb,
        // where the same rule applied to the JSON keys.
        val out = url("in:#dev")
        assertFalse(out.contains("q="))
        assertFalse(out.contains("nick="))
        assertTrue(out.contains("target=%23dev"))
    }

    /** the cursor and the network scope ride along when present */
    @Test
    fun cursorAndScope() {
        val out = url("hi", networkId = 3, before = 412)
        assertTrue(out.contains("networkId=3"))
        assertTrue(out.contains("before=412"))
    }

    // MARK: - Reading the response status

    /** an unscoped 404 is a FAILURE — there was no network for it to be about */
    @Test
    fun unscoped404Fails() {
        // ⚠⚠ The route answers 404 for exactly one thing, an unowned network, and this first read
        // EVERY 404 as the empty page on that basis. Without a networkId there is no network to
        // be unknown, so such a response is one nobody predicted — and reporting an unpredicted
        // response as "no matches" is a lie rather than an error. It is unfalsifiable from the
        // outside; an error at least offers a retry.
        assertEquals(SearchRequest.Outcome.Failed, SearchRequest.outcome(status = 404, scoped = false))
    }

    /** a scoped 404 is a question that was answered, with nothing in it */
    @Test
    fun scoped404IsEmpty() {
        // The case the route actually documents: narrowed to a network holding nothing of yours.
        // An error here would put a retry in front of a question that was answered.
        assertEquals(SearchRequest.Outcome.EmptyPage, SearchRequest.outcome(status = 404, scoped = true))
    }

    /** the ordinary statuses read the way they look */
    @Test
    fun ordinaryStatuses() {
        assertEquals(SearchRequest.Outcome.Page, SearchRequest.outcome(status = 200, scoped = false))
        assertEquals(SearchRequest.Outcome.Page, SearchRequest.outcome(status = 204, scoped = true))
        assertEquals(SearchRequest.Outcome.Unauthorized, SearchRequest.outcome(status = 401, scoped = false))
        // ⚠ 400 is the route's answer to a malformed filter, and 500 to a broken server. Both are
        // failures rather than empty pages: "nothing matched" is the one wrong thing to say when
        // the truth is that nobody looked.
        assertEquals(SearchRequest.Outcome.Failed, SearchRequest.outcome(status = 400, scoped = true))
        assertEquals(SearchRequest.Outcome.Failed, SearchRequest.outcome(status = 500, scoped = true))
    }

    // Port-only:

    /**
     * LurkerKit leaves the percent-encoding to `URLComponents`; this port does it by hand, so the
     * rule is pinned to what `URLComponents` answered for the same strings (LurkerKit's own
     * `SearchRequest`, compiled and run on a Mac).
     */
    @Test
    fun percentEncodingIsURLComponents() {
        // What it leaves as typed. ⚠ All but the `'`: `HttpUrl` writes that one as `%27` whoever
        // encoded the rest, where iOS sends it literally. The server reads the same value.
        assertEquals(
            "https://lurker.test/api/search?q=-._~!$%27()*,/:;?@&limit=50",
            url("-._~!$'()*,/:;?@"),
        )
        // What it escapes — each of these would end the value, start a fragment, or be read as
        // an escape itself.
        assertEquals(
            "https://lurker.test/api/search?q=%22%23%25%26%3C%3D%3E%5B%5C%5D%5E%60%7B%7C%7D&limit=50",
            url("\"#%&<=>[\\]^`{|}"),
        )
        // Anything past ASCII goes out as its UTF-8 bytes.
        assertEquals(
            "https://lurker.test/api/search?q=%C3%A9%E6%97%A5%F0%9F%98%80&limit=50",
            url("é日😀"),
        )
    }

    /** A message id is 64-bit here, and the cursor has to carry all of it. */
    @Test
    fun cursorPastThirtyTwoBits() {
        assertTrue(url("hi", before = 9_007_199_254_740_991).contains("before=9007199254740991"))
    }
}
