// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import net.amiantos.lurker.ui.networks.PagedDialog
import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurker.ui.networks.PagedFlow
import net.amiantos.lurker.ui.networks.rememberPagedFlow
import net.amiantos.lurker.ui.search.SearchPage
import net.amiantos.lurker.ui.search.SearchState
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.FeedPaging
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.session.ChatViewModel
import java.util.UUID

/**
 * Which view a feed dialog shows, under which token, and — for search — the text its field starts with
 * (`SearchQuery.scope`, from "Search This Conversation"). Saved, so a rotation keeps the dialog up; the
 * flow itself lives in its store (see [PagedFlow]).
 */
internal data class FeedRequest(val view: AppView, val token: String, val seed: String) {
    companion object {
        /** The CTCP delimiter, which no channel name, nick or network name can contain. */
        private val SEPARATOR = Char(1)

        val Saver: Saver<FeedRequest?, String> = Saver(
            save = { request -> request?.let { listOf(it.view.name, it.token, it.seed).joinToString(SEPARATOR.toString()) } ?: "" },
            restore = { saved ->
                val parts = saved.split(SEPARATOR, limit = 3)
                val view = AppView.entries.firstOrNull { it.name == parts.getOrNull(0) }
                if (parts.size < 3 || view == null) null else FeedRequest(view, parts[1], parts[2])
            },
        )
    }
}

/**
 * The cross-buffer feeds — Activity, Bookmarks and Search — and which one is up. The buffer list and
 * the conversation's bar open them; [FeedSheetsHost] draws them.
 *
 * Hosted by `MainScaffold`, as the other dialogs are: on a phone the list leaves composition when a
 * conversation is shown, and the conversation is rebuilt under a new key on a rename — a dialog hosted
 * in either would close under the reader. App-scoped, not buffer-scoped (they span every network), so
 * both screens that reach them get the same ones from here — lurker-ios's `showHighlights` and friends.
 *
 * One at a time — the dialog covers everything that could open another.
 */
@Stable
class FeedSheets internal constructor(private val open: MutableState<FeedRequest?>) {
    /** Open [view]; [seed] prefills a search's field. */
    fun show(view: AppView, seed: String = "") {
        open.value = FeedRequest(view, UUID.randomUUID().toString(), seed)
    }

    /**
     * Search pre-scoped to one buffer: an `in:`/`on:` prefix in the same field, which the reader can edit
     * or delete — there's no separate scoped mode, the reason the filter grammar is a grammar.
     */
    fun showSearch(seed: String) = show(AppView.Search, seed)

    /** Close whatever is open — iOS's `dismissPresented`, run before `land(on:)`. */
    fun dismiss() {
        open.value = null
    }

    internal val current: FeedRequest? get() = open.value
}

/** The feeds' open/closed state, saved so a rotation keeps the dialog up. */
@Composable
fun rememberFeedSheets(): FeedSheets {
    val open = rememberSaveable(stateSaver = FeedRequest.Saver) { mutableStateOf<FeedRequest?>(null) }
    return remember(open) { FeedSheets(open) }
}

/** What a feed's rows ask of whoever holds the list, past what [FeedController] does itself. */
interface FeedPageState {
    val feed: FeedController

    /**
     * The Buffer Closed alert's message, while it's up — a row pointed into a buffer that isn't open.
     * Said in the dialog rather than after closing it: the dialog is where the reader is, and staying
     * lets them pick another row.
     */
    var closedNotice: String?
}

/**
 * Activity or Bookmarks: one page source and its words. The two are the same screen — the server builds
 * both from one query, so the row shape, the cursor contract and the grouping are identical.
 */
enum class HistoryFeed(val title: String, val loading: StateModel, val empty: StateModel, val error: StateModel) {
    /**
     * Every line a highlight rule matched — a reply to one of your lines counts — and everyone's
     * reactions to your lines, newest first, across every buffer (lurker-ios#183). A read surface, not
     * a picker: the row shows the match itself, so you catch up without opening each channel.
     */
    Activity(
        title = "Activity",
        loading = StateModel("Loading activity…", isLoading = true),
        empty = StateModel(
            "No recent activity",
            StateSymbol.Mention,
            "Mentions, replies to you and reactions to your messages show up here.",
        ),
        error = StateModel("Couldn't load activity", StateSymbol.Warning, "Pull to try again."),
    ),

    /**
     * The lines you kept. Called "Bookmarks" here and in the menus that reach it, matching the web,
     * while the action on a message stays "Save Message" — the noun names a place you go, the verb what
     * you do to one line. Ordered by when the line was said, not when it was saved: the server pages on
     * message id, so a fresh bookmark from last spring files under last spring.
     */
    Bookmarks(
        title = "Bookmarks",
        loading = StateModel("Loading bookmarks…", isLoading = true),
        // Names the action exactly as the message's actions do, since that's what the reader has to go
        // and find.
        empty = StateModel("No bookmarks", StateSymbol.Bookmark, "Press and hold a message, then Save Message, to keep it here."),
        error = StateModel("Couldn't load bookmarks", StateSymbol.Warning, "Pull to try again."),
    ),
    ;

    fun words(placeholder: FeedPaging.Placeholder): StateModel =
        when (placeholder) {
            FeedPaging.Placeholder.Loading -> loading
            FeedPaging.Placeholder.Empty -> empty
            FeedPaging.Placeholder.Error -> error
        }
}

/**
 * Activity or Bookmarks, held for the life of its dialog: the paged list, and the two alerts.
 * ⚠ Every fetch is a READ (an HTTP GET); [remove] is the one write.
 */
class HistoryFeedState(private val model: ChatViewModel, val kind: HistoryFeed, scope: CoroutineScope) : FeedPageState {
    override val feed = FeedController(
        scope = scope,
        supersedes = false,
        visible = { items -> FeedModel.visible(items, model.state.ignores) },
        fetch = { cursor -> fetch(cursor) },
    )

    override var closedNotice by mutableStateOf<String?>(null)

    /** The swipe couldn't be delivered, so the row stayed — the Not Connected alert is up. */
    var removeFailed by mutableStateOf(false)

    init {
        feed.reload()
    }

    private suspend fun fetch(cursor: FeedCursor?): HighlightsPage? =
        when (kind) {
            // ⚠⚠ The activity feed merges two sources and pages each on its own cursor (lurker#990):
            // the whole `FeedCursor`, passed back exactly as it came.
            HistoryFeed.Activity -> model.fetchActivity(cursor)
            // Single-source: the message id half.
            HistoryFeed.Bookmarks -> model.fetchBookmarks(cursor?.beforeMessage)
        }

    /**
     * Unsave a bookmark — the row goes as soon as the verb is on the wire, rather than on the echo. The
     * opposite of what the message's own action does, deliberately: saving can be refused (silently) by
     * the server, removing can't — it deletes by (user, message) and always fans out — so there's no
     * failure for the row to spring back from.
     *
     * Delivery is the separate question, and why this checks: nothing queues a verb behind a dead socket,
     * so offline `setBookmark` writes nowhere and says so. Removing the row anyway would be the one
     * dishonest outcome — it reappears next time with no account of where it went.
     *
     * An explicit direction, never a toggle read from the store: every row here is saved by definition,
     * but the store's id set only knows lines this session has loaded.
     *
     * ⚠ A WRITE: the bookmark goes on every device. Returns whether it went out.
     */
    fun remove(item: HighlightItem): Boolean {
        if (!model.setBookmark(messageId = item.message.id, saved = false)) {
            removeFailed = true
            return false
        }
        feed.remove(item.message.id)
        return true
    }
}

/** One page of a feed dialog — there's only ever the one; the flow is `PagedFlow` for its lifetime rules. */
internal sealed interface FeedPage {
    class History(val state: HistoryFeedState) : FeedPage

    class Search(val state: SearchState) : FeedPage
}

/**
 * One open feed dialog's state — a [PagedFlow], kept in its store across a configuration change, so a
 * rotation keeps the list, its cursor, a half-typed query and a fetch in flight; its scope is cancelled
 * when the dialog closes, which cancels the request (see [FeedController]).
 */
internal class FeedFlow(model: ChatViewModel, request: FeedRequest) : PagedFlow<FeedPage>() {
    init {
        pages.add(
            when (request.view) {
                AppView.Search -> FeedPage.Search(SearchState(model, request.seed, scope))
                AppView.Activity -> FeedPage.History(HistoryFeedState(model, HistoryFeed.Activity, scope))
                AppView.Bookmarks -> FeedPage.History(HistoryFeedState(model, HistoryFeed.Bookmarks, scope))
                // Never opened here: `MainScaffold` routes Uploads to the uploads browser (`UploadsSheets`).
                AppView.Uploads -> error("Uploads is the uploads browser's, not a feed")
            },
        )
    }
}

/**
 * Draws whichever feed [sheets] has open.
 *
 * @param onJump go to a row's message: the buffer opens at that line (`MainScaffold.open(key, jumpTo)`,
 *   the one way in) — even when it's the buffer already on screen, since the point is to move to that
 *   message. The dialog has closed by the time this runs: a screen arriving under a dialog still on its
 *   way out is an animation fighting itself. Never a modal preview: a jump detaches the buffer.
 */
@Composable
fun FeedSheetsHost(sheets: FeedSheets, model: ChatViewModel, onJump: (BufferKey, Long) -> Unit) {
    val request = sheets.current ?: return
    val flow = rememberPagedFlow(request.token, isOpen = { sheets.current?.token == request.token }) { FeedFlow(model, request) }
    PagedDialog(flow = flow, onDismiss = sheets::dismiss, label = "feed page") { _, page ->
        val state: FeedPageState = when (page) {
            is FeedPage.History -> page.state
            is FeedPage.Search -> page.state
        }
        val onSelect: (HighlightItem) -> Unit = { item ->
            val now = model.state
            if (FeedModel.pointsIntoClosedBuffer(item, now)) {
                state.closedNotice = FeedModel.closedBufferMessage(item, now)
            } else {
                sheets.dismiss()
                onJump(item.bufferKey, item.message.id)
            }
        }
        when (page) {
            is FeedPage.History -> HistoryFeedPage(model = model, state = page.state, onClose = sheets::dismiss, onSelect = onSelect)
            is FeedPage.Search -> SearchPage(model = model, state = page.state, onClose = sheets::dismiss, onSelect = onSelect)
        }
    }
}
