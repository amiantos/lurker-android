// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.asString
import net.amiantos.lurkerkit.support.graphemeBoundaries
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import okio.ByteString

/**
 * What came back for one page of a cross-buffer feed: search, highlights, activity.
 *
 * Three answers rather than a page-or-null because one failure is not like the others. Since
 * lurker 2.7.0 these reads run in the server's search process, one statement at a time and
 * against a time limit, and each can answer `503 {error}`: the search took too long, too many
 * are waiting, or a newer one of ours displaced it (lurker#1308). That is "try again", said
 * in the server's words — not a dropped connection, a lost session, or a body we couldn't read.
 */
sealed interface FeedAnswer {
    data class Page(override val page: HighlightsPage) : FeedAnswer

    /**
     * `503`: the server could not answer this one just now. `reason` is its own `error` text,
     * null when the body carried none.
     */
    data class Unavailable(override val reason: String?) : FeedAnswer

    /** We could not ask, or could not read the answer. A 401 has already bounced the session. */
    data object Failed : FeedAnswer

    val page: HighlightsPage? get() = null

    /**
     * Why there is no page, when the server said: a 503's reason. Null for a page, for a plain
     * failure, and for a 503 that gave no reason.
     */
    val reason: String? get() = null

    companion object {
        /** Port note: LurkerKit's `init(_ page: HighlightsPage?)`, so it is still `FeedAnswer(page)`. */
        operator fun invoke(page: HighlightsPage?): FeedAnswer = if (page != null) Page(page) else Failed

        /**
         * The answer a `503` body gives. The body is `{error}`; anything else — an empty body, a
         * proxy's HTML page — is still "try again", with nothing of the server's to quote.
         *
         * Port note: the length is counted in grapheme clusters, as `text.count` counts it
         * (PORTING.md, Strings).
         */
        fun unavailable(body: ByteString): FeedAnswer {
            val text = FrameParser.jsonObject(body)?.get("error").asString()?.trimmingWhitespacesAndNewlines()
            // Bounded: it is shown under the list, and a body is whatever the far end sent.
            if (text == null || text.isEmpty() || graphemeBoundaries(text).size > maxReasonLength) {
                return Unavailable(reason = null)
            }
            return Unavailable(reason = text)
        }

        internal const val maxReasonLength = 200
    }
}
