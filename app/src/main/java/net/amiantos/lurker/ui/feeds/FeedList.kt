// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import net.amiantos.lurker.ui.theme.LurkerIcons
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.amiantos.lurker.ui.conversation.rememberDayClock
import net.amiantos.lurker.ui.message.CompactMetrics
import net.amiantos.lurker.ui.message.MessageListLayout
import net.amiantos.lurker.ui.message.MessageText
import net.amiantos.lurker.ui.message.MessageTextStyle
import net.amiantos.lurker.ui.message.compactTextStyle
import net.amiantos.lurker.ui.message.rememberCompactMetrics
import net.amiantos.lurker.ui.message.rememberMessageTextStyle
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.shell.StateView
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.session.ChatViewModel
import java.time.Instant

/**
 * Activity or Bookmarks, full screen — lurker-ios's `HighlightsViewController` /
 * `BookmarksViewController` sheet: the title, ✕ to close, and the feed.
 */
@Composable
internal fun HistoryFeedPage(model: ChatViewModel, state: HistoryFeedState, onClose: () -> Unit, onSelect: (HighlightItem) -> Unit) {
    DialogPage(title = state.kind.title, exit = PageExit.Close, onExit = onClose) { padding ->
        FeedList(
            model = model,
            state = state,
            words = state.kind::words,
            onSelect = onSelect,
            // Swipe to remove: Bookmarks only — Activity rows aren't yours to delete.
            onRemove = if (state.kind == HistoryFeed.Bookmarks) state::remove else null,
            modifier = Modifier.padding(padding),
        )
    }
    if (state.removeFailed) {
        // Said out loud rather than left as a swipe that visibly did nothing: a row springing back reads
        // as a missed touch, and the reader would just try again against the same dead socket.
        AlertDialog(
            onDismissRequest = { state.removeFailed = false },
            title = { Text("Not Connected") },
            text = { Text("This bookmark couldn't be removed right now. Try again once you're back online.") },
            confirmButton = { TextButton(onClick = { state.removeFailed = false }) { Text("OK") } },
        )
    }
}

/**
 * A cross-buffer feed's list: lines from elsewhere, newest first, grouped into channel+day runs under
 * sticky headers, each row jumping to its line. lurker-ios's `HistoryFeedViewController` table, shared by
 * Activity, Bookmarks and Search.
 *
 * On the message list's own canvas: a row is supposed to read as a slice of one, and on the dialog's
 * ground it read as a different surface quoting the conversation. No separators and no chevrons — the
 * section headers carry the structure, and an indicator would cost every monospaced row its width.
 *
 * Pages in as rows come on screen (iOS's `willDisplay`): a row composed within [FeedPager.PREFETCH] of
 * the end asks for the next page. Pull to refresh re-asks from the top.
 *
 * @param onRemove a row's swipe-to-remove, returning whether it went out; null where rows don't swipe.
 */
@Composable
internal fun FeedList(
    model: ChatViewModel,
    state: FeedPageState,
    words: (FeedPlaceholder) -> StateWords,
    onSelect: (HighlightItem) -> Unit,
    modifier: Modifier = Modifier,
    onRemove: ((HighlightItem) -> Boolean)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val snapshot = state.feed.snapshot
    val style = rememberMessageTextStyle()
    val context = LocalContext.current
    // "Today" and "Yesterday" move at midnight (and with the zone), so the headers are rebuilt on the
    // conversation's day clock — the rows themselves come from the cache, so that's a regroup only.
    val day by rememberDayClock()
    // Rendered once per row for the life of an answer: dropped when a new first page lands or the face
    // changes, the moments a row's rendering can change (`FeedRowCache`).
    val cache = remember(snapshot.epoch, style, day.zone) {
        FeedRowCache { item -> FeedModel.row(item, model.state, style, day.zone) }
    }
    // Built when the rows change, against the state as it stands then — see `FeedModel.sections`.
    val sections = remember(snapshot.items, cache, day) {
        FeedModel.sections(
            snapshot.items,
            state = model.state,
            style = style,
            now = Instant.now(),
            zone = day.zone,
            date = { instant, withYear -> shortDate(context, instant, withYear) },
            render = cache::row,
        )
    }
    FeedListContent(
        sections = sections,
        snapshot = snapshot,
        words = words,
        onSelect = onSelect,
        onRefresh = { state.feed.reload(byPull = true) },
        onShown = state.feed::scrolledTo,
        onRetry = state.feed::retry,
        onRemove = onRemove,
        listState = listState,
        modifier = modifier,
    )
    val notice = state.closedNotice
    if (notice != null) {
        // No offer to reopen it: reopening is a real decision (rejoining a channel, starting a DM), not
        // a side effect of tapping something to read.
        AlertDialog(
            onDismissRequest = { state.closedNotice = null },
            title = { Text("Buffer Closed") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { state.closedNotice = null }) { Text("OK") } },
        )
    }
}

/** The list itself, stateless — for previews. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun FeedListContent(
    sections: List<FeedSection>,
    snapshot: FeedSnapshot,
    words: (FeedPlaceholder) -> StateWords,
    onSelect: (HighlightItem) -> Unit,
    onRefresh: () -> Unit,
    onShown: (Int) -> Unit,
    onRemove: ((HighlightItem) -> Boolean)?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val colors = LurkerTheme.colors
    PullToRefreshBox(
        isRefreshing = snapshot.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().background(colors.bg),
    ) {
        val currentOnShown by rememberUpdatedState(onShown)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            for (section in sections) {
                // Floats, as a plain table's section header does on iOS: a long run keeps saying where it's from.
                stickyHeader(key = "h${section.offset}", contentType = "header") { FeedSectionHeader(section) }
                section.rows.forEachIndexed { position, row ->
                    val index = section.offset + position
                    item(key = row.key, contentType = "row") {
                        // Keyed by position too, so a row a refresh moved counts as shown again — iOS's
                        // `reloadData` re-displays the cells on screen.
                        LaunchedEffect(row.key, index) { currentOnShown(index) }
                        if (onRemove == null) {
                            FeedRowView(row, onClick = { onSelect(row.item) })
                        } else {
                            SwipeToRemove(row, onSelect = onSelect, onRemove = onRemove)
                        }
                    }
                }
            }
            // A failed page-in under rows already shown. Paging fires as rows come on screen, and at the
            // bottom none ever will again — so the way to ask again is said, and tapped, here.
            if (snapshot.pageInFailed && snapshot.items.isNotEmpty()) {
                item(key = "retry", contentType = "retry") { RetryRow(onRetry) }
            }
        }
        // Loading, the fetch's failure, or an empty answer — said in place of rows. A pull shows its own
        // spinner, so the page's stays away while one is out.
        val placeholder = snapshot.placeholder
        if (placeholder != null && !(placeholder == FeedPlaceholder.Loading && snapshot.refreshing)) {
            val said = words(placeholder)
            StateView(title = said.title, subtitle = said.subtitle, isLoading = placeholder == FeedPlaceholder.Loading)
        }
    }
}

/** "Couldn't load more" and the button that asks again, at the foot of the list. */
@Composable
private fun RetryRow(onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CompactMetrics.side, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Couldn't load more.",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = LurkerTheme.colors.fgMuted,
        )
        TextButton(onClick = onRetry) { Text("Try Again", style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * `Network/#channel` on the leading edge, the day on the trailing edge, on one baseline — iMessage
 * search's per-group header. The location truncates before the day does. A heading to TalkBack, so a
 * reader can move run to run.
 */
@Composable
private fun FeedSectionHeader(section: FeedSection) {
    val colors = LurkerTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(start = CompactMetrics.side, end = CompactMetrics.side, top = 16.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            section.location,
            modifier = Modifier.weight(1f).alignByBaseline(),
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(section.day, modifier = Modifier.alignByBaseline(), style = style, color = colors.fgMuted, maxLines = 1)
    }
}

/**
 * One feed row: the compact row's shape (`MessageRow`'s `CompactRow`) as its own block — a header with
 * the time, a reply's quote, the body — and the whole row one tap target, its jump. The ripple is the
 * only thing marking a row tappable, with the chevron gone.
 *
 * One TalkBack element: the quote, the name, the line, the time; the tap reads as "show in
 * conversation", and [removeAction] — a bookmark's swipe — is a custom action beside it.
 */
@Composable
internal fun FeedRowView(row: FeedRow, onClick: () -> Unit, removeAction: (() -> Unit)? = null) {
    val style = rememberMessageTextStyle()
    val colors = style.colors
    val textStyle = compactTextStyle()
    val metrics = rememberCompactMetrics(textStyle)
    val header = row.header
    val reply = row.reply
    // Named apart from the semantics' own `onClick`, which the block below also calls.
    val activate = onClick
    val label = remember(row) {
        val spoken = MessageListLayout.spokenRow(header, AnnotatedString(row.spokenBody)).text
        if (reply == null) spoken else "${reply.spoken}, $spoken"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .clickable(onClickLabel = "show in conversation", role = Role.Button, onClick = onClick)
            // ⚠ Replaces the clickable's semantics too, so the button and its tap are said again here —
            // without them TalkBack reads the row and can't activate it.
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = "show in conversation") {
                    activate()
                    true
                }
                if (removeAction != null) {
                    customActions = listOf(
                        CustomAccessibilityAction("Remove") {
                            removeAction()
                            true
                        },
                    )
                }
            }
            .padding(bottom = metrics.blockGap - metrics.wash * 2),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CompactMetrics.side)
                .padding(top = metrics.wash, bottom = metrics.wash + CompactMetrics.washBottomNudge),
        ) {
            if (header != null) FeedHeaderLine(row, style, textStyle)
            if (reply != null) {
                Text(
                    reply.shown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (header != null) CompactMetrics.headerGap else 0.dp)
                        .alpha(0.45f),
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.body.isNotEmpty()) {
                Text(
                    row.body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (header != null && reply == null) CompactMetrics.headerGap else 0.dp),
                    style = textStyle,
                )
            }
        }
    }
}

/** The name (its colour, a relay source after it) leading, the time trailing; the name truncates first. */
@Composable
private fun FeedHeaderLine(row: FeedRow, style: MessageTextStyle, textStyle: TextStyle) {
    val header = row.header ?: return
    val name = remember(header, style) { MessageText.headerName(header, style) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, modifier = Modifier.weight(1f).alignByBaseline(), style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val time = header.time
        if (time != null) Text(time, modifier = Modifier.alignByBaseline(), style = textStyle, color = style.colors.fgMuted, maxLines = 1)
    }
}

/**
 * A bookmark row with iOS's trailing swipe: a full swipe from the end removes it. Half the width is the
 * line, as the buffer list's swipe draws it — far enough that a scroll drifting sideways doesn't
 * unsave anything. When the verb couldn't go out the row springs back (and the page says why).
 *
 * The state is `remember`ed, not saved: a row that comes back after a pull must not come back already
 * swiped away.
 */
@Composable
private fun SwipeToRemove(row: FeedRow, onSelect: (HighlightItem) -> Unit, onRemove: (HighlightItem) -> Boolean) {
    val state = remember {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { distance -> distance * 0.5f })
    }
    val scope = rememberCoroutineScope()
    val currentRemove by rememberUpdatedState(onRemove)
    // Once per swipe: the box re-runs its dismiss callback whenever it recomposes still dismissed, and
    // the row stays composed until the list drops it.
    var fired by remember { mutableStateOf(false) }
    val remove = {
        if (!currentRemove(row.item)) {
            scope.launch {
                state.reset()
                fired = false
            }
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = {
            if (!fired) {
                fired = true
                remove()
            }
        },
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(end = CompactMetrics.side),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LurkerIcons.BookmarkRemove, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                // Titled for the bookmark, not the "Save" verb: this is a row in the Bookmarks list.
                Text("Remove", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onError)
            }
        },
    ) {
        FeedRowView(row, onClick = { onSelect(row.item) }, removeAction = { remove() })
    }
}

/**
 * A day as a header's trailing stamp — "Sep 21", with the year only when it isn't this one — through
 * `DateUtils`, the device's locale and order. iOS's `MMMd` / `MMMdyyyy` templates.
 */
private fun shortDate(context: Context, instant: Instant, withYear: Boolean): String =
    DateUtils.formatDateTime(
        context,
        instant.toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
            if (withYear) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR,
    )
