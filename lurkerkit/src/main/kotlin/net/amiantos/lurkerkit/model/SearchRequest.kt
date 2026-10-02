// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * The URL for one page of `GET /api/search` (lurker-ios#123).
 *
 * Split out from the fetch so it can be tested: the mapping is small but every one of its rules
 * fails SILENTLY when it is wrong — a mis-encoded parameter returns the wrong rows rather than an
 * error, and a search that quietly answers a different question than the one typed is close to
 * impossible to notice from the results.
 */
object SearchRequest {

    /**
     * `null` when the base URL will not parse, which is the caller's cue to give up.
     *
     * ⚠⚠ Only what the user actually filtered on goes on the wire. The server reads an absent key
     * as unfiltered and an empty string as a filter matching nothing, so sending `q=` for an
     * unused filter returns no rows for every search. This is inherited from the WS verb, where
     * the same rule applied to the JSON keys.
     *
     * Port note: `before` is a message id, so it is a `Long` here (PORTING.md, Types).
     *
     * Port note: an `okhttp3.HttpUrl` where LurkerKit builds with `URLComponents`, as in
     * `UploadsRequest` — so, as there, a base that is not http(s) (or is empty, or has no scheme)
     * is null here and a URL there, and the base comes back in `HttpUrl`'s canonical form (scheme
     * and host lowercased, a default port dropped) where `URLComponents` keeps it as typed.
     *
     * Port note: ⚠ UNLIKE `UploadsRequest`, the query is percent-encoded here by hand, to
     * `URLComponents`' own rule ([percentEncoded]), and handed to `HttpUrl` already encoded. Left
     * to `addQueryParameter`, a `!` goes out as `%21` — the same value to the server, but
     * `SearchRequestTests.allSigilsSurvive` holds the URL to `target=!chan`, and a test's
     * expectation is not altered to suit the port. The one character the two still spell
     * differently is `'`, which `HttpUrl` re-encodes to `%27` whoever encoded the rest.
     */
    fun url(
        base: String,
        query: SearchQuery,
        networkId: Int?,
        before: Long?,
        limit: Int,
    ): HttpUrl? {
        val components = (base + "/api/search").toHttpUrlOrNull()?.newBuilder() ?: return null
        val items = mutableListOf<Pair<String, String>>()
        if (query.text.isNotEmpty()) items.add("q" to query.text)
        // ⚠ Repeated, not comma-joined: `nick=a&nick=b` is how the route OR-matches a friend's
        // alts, and it is the one field of the WS verb (`nicks: [a, b]`) that did not map to a
        // single param. A comma-joined value would be read as one nick containing a comma.
        for (nick in query.from) items.add("nick" to nick)
        if (query.target.isNotEmpty()) items.add("target" to query.target)
        if (networkId != null) items.add("networkId" to networkId.toString())
        if (before != null) items.add("before" to before.toString())
        items.add("limit" to limit.toString())
        val percentEncodedQuery = items.joinToString("&") { (name, value) ->
            percentEncoded(name) + "=" + percentEncoded(value)
        }

        // ⚠⚠ `+` has to be percent-encoded BY HAND, and this is the one thing about moving search
        // onto a URL that the WS verb could not get wrong. `URLComponents` leaves a literal `+`
        // alone — it is legal in a query — but the server parses query strings with
        // form-urlencoded semantics, where `+` MEANS SPACE. So `C++` arrives as `C  ` and the
        // search quietly answers a different question. JSON over the socket had no such reading.
        //
        // ⚠ Safe as a blanket replacement because `URLComponents` encodes a space as `%20` and
        // never as `+`, so every `+` left in the output is one the user typed. Everything else it
        // already handles: `#dev` → `%23dev`, `&` → `%26`.
        //
        // Port note: all of that is as true of `percentEncoded` as of `URLComponents`, which it
        // copies. ⚠ And `encodedQuery` — like `addEncodedQueryParameter` — passes a `+` through
        // untouched, so the by-hand step is as load-bearing here as it is on iOS.
        //
        // Port note: `encodedQuery` replaces whatever query the string had, as
        // `URLComponents.queryItems = …` does.
        components.encodedQuery(percentEncodedQuery.replace("+", "%2B"))
        return components.build()
    }

    /**
     * Port note: what `URLComponents` does to a query item's name or value, enumerated from
     * Foundation on a Mac: the UTF-8 bytes, each written as `%XX` unless it is an ASCII letter
     * or digit or one of `- . _ ~ ! $ ' ( ) * + , / : ; ? @`. So a space is `%20`, and `& = #
     * %` are all escaped. `SearchRequestTests` pins the ones that matter.
     */
    private fun percentEncoded(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val unit = byte.toInt() and 0xFF
            val character = unit.toChar()
            if (unit < 0x80 && (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                    literalInQuery.indexOf(character) >= 0)
            ) {
                out.append(character)
            } else {
                out.append(String.format(Locale.ROOT, "%%%02X", unit))
            }
        }
        return out.toString()
    }

    private const val literalInQuery = "-._~!$'()*+,/:;?@"

    /** What a response status means for a search. */
    enum class Outcome {
        /** Read the body as a page of matches. */
        Page,

        /**
         * A question that was answered, with nothing in it. Distinct from `Failed`: the caller
         * says "no matches" rather than putting an error and a retry in front of the user.
         */
        EmptyPage,

        /** The session is gone. */
        Unauthorized,

        /** We could not ask, or could not read the answer. NEVER rendered as "no matches". */
        Failed,
    }

    /**
     * ⚠⚠ `scoped` — whether the request carried a `networkId` — is what makes a 404 readable.
     *
     * The route answers 404 for exactly one thing: an unowned or unknown network. So a SCOPED
     * 404 is the empty page — the user narrowed to a network holding nothing of theirs, and "no
     * matches" answers the question. An UNSCOPED 404 is not that answer, because there was no
     * network to be unknown; it is a response nobody predicted, and the only honest reading of
     * one is that we failed to ask.
     *
     * ⚠ The distinction is a line of code and buys the difference between an error and a lie.
     * "Nothing matched" is the one wrong thing to say whenever the truth is that nobody looked —
     * it is unfalsifiable from the outside, where an error at least offers a retry. Cheap
     * honesty about an unexpected status, not compatibility machinery: this client tracks the
     * server it ships with, and does not negotiate.
     */
    fun outcome(status: Int, scoped: Boolean): Outcome =
        when (status) {
            in 200..<300 -> Outcome.Page
            401 -> Outcome.Unauthorized
            404 -> if (scoped) Outcome.EmptyPage else Outcome.Failed
            else -> Outcome.Failed
        }
}
