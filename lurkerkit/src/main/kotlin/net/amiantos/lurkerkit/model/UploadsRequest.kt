// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.percentEncodedQuery
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What the uploads browser is currently narrowed to (lurker-ios#138).
 *
 * ⚠⚠ `favoritesOnly` **composes with** `kind` rather than replacing it — "my starred gifs" is a
 * view somebody actually wants, so it is its own flag and not a fifth kind. That is why the UI
 * puts it beside the kind choices rather than among them: a row of mutually-exclusive options
 * with one member that isn't would be lying about what tapping it does.
 *
 * Port note: `var` properties in LurkerKit, with no `mutating` method — an immutable
 * `data class` here, and a change of filter is a `copy(…)` (PORTING.md, structs that mutate,
 * case 1).
 */
data class UploadsFilter(
    /**
     * Filename search. Server-side by necessity, not by preference: this client only holds the
     * pages it has scrolled through, and the entire point of the search is finding one it
     * hasn't. The house rule is that a filter is a render-time thing over what the client
     * already holds; this is the documented exception, and the reason is delivery, not taste.
     */
    val query: String = "",
    val kind: UploadKind? = null,
    val favoritesOnly: Boolean = false,
) {
    /**
     * Is this narrowed at all? Decides whether an empty list says "nothing matches" or "you
     * haven't uploaded anything".
     */
    val isNarrowed: Boolean get() = query.isNotEmpty() || kind != null || favoritesOnly

    /**
     * What this filter is narrowed to, as a NOUN PHRASE — `uploads`, `starred uploads`,
     * `image uploads`, `starred video uploads`.
     *
     * ⚠⚠ Always ends in the head noun, and the narrowings are adjectives in front of it. Built as
     * a bare list of what was set, it produced "Nothing in your uploads matches starred." — which
     * is not a sentence. Anything that gets dropped into running prose has to be a phrase that
     * can survive being dropped into running prose, and the empty state is the one place a
     * reader meets these words at all.
     *
     * ⚠ The kind contributes its raw name (`video`) rather than its chip label (`Video`), because
     * the labels are plural where the grammar wants a modifier: "No videos uploads" is the other
     * way to get this wrong.
     *
     * ⚠ `starred` must be named as well as the kind. "No image uploads match" sent the reader
     * hunting for the wrong thing when the empty view was really the starred one.
     */
    val scope: String
        get() {
            val narrowings = listOfNotNull(if (favoritesOnly) "starred" else null, kind?.rawValue)
            return (narrowings + listOf("uploads")).joinToString(" ")
        }

    /**
     * The line under an empty grid when a search found nothing — `No starred image uploads match
     * “march”.` Only for the case where something was actually typed; a filter with no query has
     * nothing to report *not matching*, and reads as "you have none of these" instead.
     */
    val noMatchesLine: String
        get() = "No $scope match “$query”."
}

/**
 * The URL for one page of `GET /api/uploads`, and the two paging rules that go with it.
 *
 * Split out from the fetch for the same reason `SearchRequest` is: every rule here fails
 * SILENTLY when it is wrong. A mis-encoded filename returns the wrong rows rather than an error,
 * and a browse that quietly answers a different question than the one asked is close to
 * impossible to notice from a grid of thumbnails.
 */
object UploadsRequest {

    /** How many rows a scrolled page asks for. */
    const val pageSize = 50

    /**
     * How many the starred view asks for in its single request.
     *
     * ⚠ Mirrors the server's own ceiling. Hitting it is DISCLOSED rather than silently
     * truncated — a list that stops at 200 with nothing said reads as "these are all of them",
     * which would be a lie. See `isTruncated`.
     */
    const val favoritesLimit = 200

    /** The limit this filter's first request should carry. */
    fun limit(filter: UploadsFilter): Int =
        if (filter.favoritesOnly) favoritesLimit else pageSize

    /**
     * `null` when the base URL will not parse, which is the caller's cue to give up.
     *
     * ⚠⚠ Only what the user actually filtered on goes on the wire. `q=` with an empty value is
     * not "unfiltered" — it is a filename search for the empty string, and the route reads the
     * two differently.
     *
     * ⚠⚠ `before` is DROPPED for the starred view. The server orders that view by when each row
     * was starred, and an id cursor against that ordering pages the wrong rows — so the route
     * ignores the parameter, and sending one anyway would encode a paging model this view does
     * not have. It comes back whole instead; see `favoritesLimit`.
     *
     * Port note: built with `okhttp3.HttpUrl` where LurkerKit uses `URLComponents`. Three
     * consequences, checked against the Swift. A base that is not http(s) — or is empty, or has
     * no scheme — is null here and a URL there; nothing signs in with one (`ServerAddress`).
     * `HttpUrl` writes the base in canonical form (scheme and host lowercased, a default port
     * dropped), where `URLComponents` keeps it as typed. The query itself is encoded by
     * `support.percentEncodedQuery`, to `URLComponents`' own rule.
     */
    fun url(
        base: String,
        filter: UploadsFilter,
        before: Int?,
        limit: Int,
    ): HttpUrl? {
        val components = (base + "/api/uploads").toHttpUrlOrNull()?.newBuilder() ?: return null
        val items = mutableListOf<Pair<String, String>>()
        items.add("limit" to limit.toString())
        if (before != null && !filter.favoritesOnly) items.add("before" to before.toString())
        if (filter.query.isNotEmpty()) items.add("q" to filter.query)
        val kind = filter.kind
        if (kind != null) items.add("kind" to kind.rawValue)
        if (filter.favoritesOnly) items.add("favorites" to "1")

        // ⚠⚠ A typed `+` has to reach the server as `%2B`, exactly as in `SearchRequest`, and a
        // filename search is where it bites hardest: `C++.png` and `notes+drafts.txt` are
        // ordinary names, and a `+` the server reads as a space quietly returns nothing.
        // `percentEncodedQuery` is where that is done, for every request.
        //
        // Port note: `encodedQuery` replaces whatever query the string had, as
        // `URLComponents.queryItems = …` does.
        components.encodedQuery(percentEncodedQuery(items))
        return components.build()
    }

    /**
     * Is there another page behind this one?
     *
     * A full page means "ask again" and a short one means "that's everything" — the route has no
     * `nextBefore` to say so itself. The starred view is the exception in both directions: it
     * arrives whole, so there is never a next page to fetch even when it came back full.
     */
    fun hasMore(filter: UploadsFilter, received: Int, limit: Int): Boolean =
        !filter.favoritesOnly && received >= limit

    /** The starred view came back at the ceiling, so there may be more the server didn't send. */
    fun isTruncated(filter: UploadsFilter, received: Int): Boolean =
        filter.favoritesOnly && received >= favoritesLimit

    /**
     * What a response status means for a list request.
     *
     * ⚠ Deliberately has no "that's an empty page" case, unlike `SearchRequest.outcome`. That
     * one exists because the search route answers 404 for exactly one thing (an unowned
     * network); this route takes no such parameter, so a 404 here is a response nobody
     * predicted and the only honest reading of one is that we failed to ask.
     */
    enum class Outcome {
        Page,
        Unauthorized,
        Failed,
    }

    fun outcome(status: Int): Outcome = when (status) {
        in 200..<300 -> Outcome.Page
        401 -> Outcome.Unauthorized
        else -> Outcome.Failed
    }
}
