// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.UploadKind
import net.amiantos.lurkerkit.model.UploadsFilter
import net.amiantos.lurkerkit.model.UploadsRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Building the `GET /api/uploads` URL, and the paging rules that go with it (lurker-ios#138).
 *
 * ⚠⚠ Same reason `SearchRequestTests` exists: every rule here fails SILENTLY. The route answers a
 * mis-encoded filename with the wrong rows rather than an error, and a grid of thumbnails gives a
 * person nothing to notice that from.
 *
 * Port note: a Swift Testing suite in LurkerKit ("Uploads request"), where each case carries a
 * display name. The method names are kept and the display name is the comment above each.
 */
class UploadsRequestTests {

    private fun url(
        filter: UploadsFilter = UploadsFilter(),
        before: Int? = null,
        limit: Int? = null,
    ): String =
        UploadsRequest.url(
            base = "https://lurker.test",
            filter = filter,
            before = before,
            limit = limit ?: UploadsRequest.limit(filter),
        )?.toString() ?: "<nil>"

    /** an unfiltered browse asks for nothing but a limit */
    @Test
    fun unfiltered() {
        // ⚠ No `q=`, no `kind=`, no `favorites=`. An empty `q` is not "unfiltered" to the route —
        // it is a filename search for the empty string.
        assertEquals("https://lurker.test/api/uploads?limit=50", url())
    }

    /** a literal + survives, because the server reads a bare one as a space */
    @Test
    fun plusIsEncoded() {
        // ⚠⚠ The trap `SearchRequest` documents, and filenames are where it actually bites:
        // `C++.png` and `notes+drafts.txt` are names people really have. On iOS `URLComponents`
        // leaves `+` alone (legal in a query); the route parses with form-urlencoded semantics
        // where `+` MEANS SPACE, so the search quietly answers a different question and finds
        // nothing.
        assertTrue(url(UploadsFilter(query = "C++.png")).contains("q=C%2B%2B.png"))
        // ...and a real space is still a space, which is what makes the blanket replace safe
        // there: URLComponents encodes it as %20 and never as +. (`HttpUrl` writes the same
        // two answers without the replace; this is what holds it to them.)
        assertTrue(url(UploadsFilter(query = "screen shot")).contains("q=screen%20shot"))
    }

    /** a filename's other punctuation is encoded too */
    @Test
    fun punctuationIsEncoded() {
        // `&` would end the parameter and invent a new one; `#` would start a fragment and take
        // the rest of the query with it.
        assertTrue(url(UploadsFilter(query = "a&b#c")).contains("q=a%26b%23c"))
    }

    /** starred composes with a kind rather than replacing it */
    @Test
    fun starredComposesWithKind() {
        // ⚠⚠ The rule the whole filter UI is shaped around: "my starred gifs" is one request, not
        // a choice between two views.
        val both = url(UploadsFilter(kind = UploadKind.Image, favoritesOnly = true))
        assertTrue(both.contains("kind=image"))
        assertTrue(both.contains("favorites=1"))
    }

    /** the starred view asks for the whole set, not a page */
    @Test
    fun starredAsksForTheCeiling() {
        assertEquals(200, UploadsRequest.limit(UploadsFilter(favoritesOnly = true)))
        assertEquals(50, UploadsRequest.limit(UploadsFilter()))
    }

    /** a cursor is dropped for the starred view, and carried for every other */
    @Test
    fun cursorIsDroppedForStarred() {
        // ⚠⚠ The server orders the starred view by when each row was STARRED, so an id cursor
        // against that ordering pages the wrong rows — the route ignores `before` for exactly
        // that reason, and sending one would encode a paging model this view does not have.
        assertFalse(url(UploadsFilter(favoritesOnly = true), before = 90).contains("before"))
        assertTrue(url(before = 90).contains("before=90"))
    }

    /** a full page means there is more; the starred view never has more */
    @Test
    fun hasMore() {
        val plain = UploadsFilter()
        assertTrue(UploadsRequest.hasMore(filter = plain, received = 50, limit = 50))
        assertFalse(UploadsRequest.hasMore(filter = plain, received = 49, limit = 50))
        // ⚠ Even at a full 200 rows: there is no cursor that can page this view, so "more" would
        // be a promise nothing can keep. It is disclosed as truncation instead.
        val starred = UploadsFilter(favoritesOnly = true)
        assertFalse(UploadsRequest.hasMore(filter = starred, received = 200, limit = 200))
        assertTrue(UploadsRequest.isTruncated(filter = starred, received = 200))
        assertFalse(UploadsRequest.isTruncated(filter = starred, received = 199))
        // ...and an ordinary full page is not "truncated" — it just has a next page.
        assertFalse(UploadsRequest.isTruncated(filter = plain, received = 50))
    }

    /** a 404 is a failure here, never an empty page */
    @Test
    fun unpredictedStatusIsAFailure() {
        // ⚠ Unlike search, which reads a SCOPED 404 as "that network holds nothing of yours".
        // This route takes no such parameter, so nothing can be legitimately unknown and the only
        // honest reading of a 404 is that we failed to ask. Saying "no uploads" instead would be
        // unfalsifiable from the outside, where an error at least offers a retry.
        assertEquals(UploadsRequest.Outcome.Failed, UploadsRequest.outcome(status = 404))
        assertEquals(UploadsRequest.Outcome.Unauthorized, UploadsRequest.outcome(status = 401))
        assertEquals(UploadsRequest.Outcome.Page, UploadsRequest.outcome(status = 200))
        assertEquals(UploadsRequest.Outcome.Failed, UploadsRequest.outcome(status = 500))
    }

    /** the scope is a noun phrase, so it survives being dropped into a sentence */
    @Test
    fun scopeIsANounPhrase() {
        // ⚠⚠ The regression this exists for: built as a bare list of what was set, a starred-only
        // filter described itself as "starred", and the empty state read "Nothing in your uploads
        // matches starred." — which is not a sentence. Every one of these has to end in the head
        // noun with the narrowings in front of it.
        assertEquals("starred uploads", UploadsFilter(favoritesOnly = true).scope)
        assertEquals("image uploads", UploadsFilter(kind = UploadKind.Image).scope)
        assertEquals("starred image uploads", UploadsFilter(kind = UploadKind.Image, favoritesOnly = true).scope)
        assertEquals("uploads", UploadsFilter().scope)
        // ⚠ The RAW name, not the chip label: the labels are plural where the grammar wants a
        // modifier, and "No videos uploads" is the other way to get this wrong.
        assertEquals("video uploads", UploadsFilter(kind = UploadKind.Video).scope)
    }

    /** the no-matches line names what was searched as well as what for */
    @Test
    fun noMatchesLineReadsAsASentence() {
        assertEquals(
            "No starred video uploads match “march”.",
            UploadsFilter(query = "march", kind = UploadKind.Video, favoritesOnly = true).noMatchesLine,
        )
        assertEquals("No uploads match “march”.", UploadsFilter(query = "march").noMatchesLine)
    }

    /** a filter with nothing set is not narrowed */
    @Test
    fun narrowing() {
        assertFalse(UploadsFilter().isNarrowed)
        assertTrue(UploadsFilter(favoritesOnly = true).isNarrowed)
        assertTrue(UploadsFilter(kind = UploadKind.Text).isNarrowed)
        assertTrue(UploadsFilter(query = "a").isNarrowed)
    }

    // Port-only: the URL is an `okhttp3.HttpUrl` here and a Foundation `URL` in LurkerKit, and
    // the two disagree about what "will not parse" covers. The answers marked iOS are the
    // Swift's, for the same inputs.

    /** a base that is not http(s) is no URL at all */
    @Test
    fun aBaseThatIsNotHttpIsNoUrl() {
        fun url(base: String): String? =
            UploadsRequest.url(base = base, filter = UploadsFilter(), before = null, limit = 50)?.toString()
        // iOS agrees on this one: a space in the host is not a URL to either parser.
        assertNull(url("https://lurker test"))
        // iOS builds a URL out of each of these (`/api/uploads?limit=50`,
        // `lurker.test/api/uploads?limit=50`, `ftp://x/api/uploads?limit=50`) — none of which
        // it could fetch either. `ServerAddress` never lets one become a base.
        assertNull(url(""))
        assertNull(url("lurker.test"))
        assertNull(url("ftp://x"))
        // And a base it does take comes out in canonical form, where iOS keeps it as typed
        // (`HTTPS://LURKER.test:443/api/uploads?limit=50`). The same request either way.
        assertEquals("https://lurker.test/api/uploads?limit=50", url("HTTPS://LURKER.test:443"))
        assertEquals("http://localhost:8010/api/uploads?limit=50", url("http://localhost:8010"))
    }

    /** the punctuation HttpUrl escapes and URLComponents does not still reads back the same */
    @Test
    fun theExtraEscapesDecodeToWhatWasTyped() {
        // iOS: `q=~!@$%5E*()_-.,:;'/?` — the same value, with only the `^` escaped.
        val typed = "~!@$^*()_-.,:;'/?"
        val built = UploadsRequest.url(
            base = "https://lurker.test", filter = UploadsFilter(query = typed), before = null, limit = 50,
        )
        assertEquals(typed, built?.queryParameter("q"))
        assertEquals(
            "https://lurker.test/api/uploads?limit=50&q=%7E%21%40%24%5E*%28%29_-.%2C%3A%3B%27%2F%3F",
            built?.toString(),
        )
    }
}
