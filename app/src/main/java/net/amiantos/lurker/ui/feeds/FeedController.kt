// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage

/**
 * Runs a [FeedPager]'s fetches in [scope] and publishes what it draws as one [snapshot].
 *
 * Kept in the dialog's flow (`FeedFlow`), so a rotation keeps the list, its cursor and a fetch in
 * flight; the flow's scope is cancelled when the dialog closes, which cancels the request itself — the
 * "user left" case, at least as common as "user typed another character", and for search the one worth
 * not running: the FTS query runs on the event loop that services every IRC connection on the cell.
 *
 * @param fetch one page, newest first: `cursor` is the previous page's `next`, null for the first. Null
 *   means the fetch failed (a 401 has already bounced the session).
 */
class FeedController(
    private val scope: CoroutineScope,
    supersedes: Boolean,
    visible: (List<HighlightItem>) -> List<HighlightItem>,
    private val fetch: suspend (FeedCursor?) -> HighlightsPage?,
) {
    private val pager = FeedPager(supersedes, visible)

    var snapshot by mutableStateOf(pager.snapshot())
        private set

    /**
     * The fetch in flight, held so a superseding reload can CANCEL it rather than merely out-generation
     * it. The generation check stays the correctness mechanism — cancellation is cooperative, and an
     * answer already returned can't be recalled — but this is the difference between the server doing
     * the work and throwing it away, and never doing it. Only ever one: the pager keeps a page-in and a
     * non-superseding reload from overlapping.
     */
    private var job: Job? = null

    /** (Re)fetch from the newest page — see [FeedPager.reload]. */
    fun reload(byPull: Boolean = false) {
        val first = pager.reload(byPull)
        if (first == null) {
            publish()
            return
        }
        job?.cancel()
        publish()
        run(first)
    }

    /** The next older page, if one is due — a scroll drew row [index]. */
    fun scrolledTo(index: Int) {
        if (!pager.wantsMore(index)) return
        pager.loadMore()?.let(::run)
    }

    /** Drop a row the reader removed (a bookmark) — see [FeedPager.remove]. */
    fun remove(messageId: Long) {
        val more = pager.remove(messageId)
        publish()
        more?.let(::run)
    }

    private fun run(next: FeedFetch) {
        job = scope.launch {
            // Re-checked once the request actually starts, not only when its answer lands: a feed whose
            // question can change has maybe moved on by now, and a superseded page would still cost a
            // full search on the server.
            if (!pager.isCurrent(next)) return@launch
            val page = fetch((next as? FeedFetch.More)?.cursor)
            // A cancelled fetch reports null, which would otherwise read as a failure and put an error
            // in front of someone who simply typed on.
            ensureActive()
            val more = when (next) {
                is FeedFetch.First -> pager.firstPage(next, page)
                is FeedFetch.More -> pager.appendPage(next, page)
            }
            publish()
            more?.let(::run)
        }
    }

    private fun publish() {
        val next = pager.snapshot()
        if (next != snapshot) snapshot = next
    }
}
