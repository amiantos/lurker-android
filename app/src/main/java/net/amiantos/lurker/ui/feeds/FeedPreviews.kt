// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import net.amiantos.lurker.ui.message.rememberMessageTextStyle
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedReaction
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.ChatState
import java.time.Instant
import java.time.ZoneOffset

/** An activity feed's worth: a mention, a `/me`, a reaction to your line, a notice from last year. */
internal fun previewFeedItems(now: Instant): List<HighlightItem> {
    fun message(id: Long, type: EventType, nick: String?, text: String, at: Instant) =
        Message(id = id, type = type, nick = nick, text = text, date = at, matched = true, msgid = "p$id")
    return listOf(
        HighlightItem(message(40, EventType.Message, "alice", "amiantos: the deploy landed — https://lurker.chat/changelog", now.minusSeconds(300)), 1, "#lurker", "Libera"),
        HighlightItem(message(39, EventType.Action, "bob", "pokes amiantos about the \u0002review\u0002", now.minusSeconds(900)), 1, "#lurker", "Libera"),
        HighlightItem(
            message(38, EventType.Message, "carol", "👍", now.minusSeconds(90_000)),
            1,
            "#swift",
            "Libera",
            reaction = FeedReaction(reactionId = 7, value = "👍", lineText = "shipped the \u000304fix\u0003"),
        ),
        HighlightItem(message(12, EventType.Notice, "NickServ", "amiantos is now identified", now.minusSeconds(400L * 86_400)), 2, "dave", "OFTC"),
    )
}

@Composable
private fun FeedPreview(dark: Boolean, kind: HistoryFeed, placeholder: FeedPlaceholder? = null) {
    LurkerTheme(darkTheme = dark) {
        val style = rememberMessageTextStyle()
        val now = Instant.parse("2026-07-25T14:41:00Z")
        val items = if (placeholder == null) previewFeedItems(now) else emptyList()
        val state = ChatState(
            networks = mapOf(
                1 to Network(id = 1, name = "Libera", position = 0, nick = "amiantos"),
                2 to Network(id = 2, name = "OFTC", position = 1, nick = "amiantos"),
            ),
        )
        val sections = FeedModel.sections(
            items,
            state,
            style,
            now,
            ZoneOffset.UTC,
            date = { _, withYear -> if (withYear) "Jun 20, 2025" else "Jul 23" },
        )
        DialogPage(title = kind.title, exit = PageExit.Close, onExit = {}) { padding ->
            FeedListContent(
                sections = sections,
                snapshot = FeedSnapshot(items = items, placeholder = placeholder),
                words = kind::words,
                onSelect = {},
                onRefresh = {},
                onShown = {},
                onRemove = if (kind == HistoryFeed.Bookmarks) ({ true }) else null,
                listState = rememberLazyListState(),
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Preview(name = "Activity — light", heightDp = 640)
@Composable
private fun ActivityPreviewLight() = FeedPreview(dark = false, kind = HistoryFeed.Activity)

@Preview(name = "Activity — dark", heightDp = 640)
@Composable
private fun ActivityPreviewDark() = FeedPreview(dark = true, kind = HistoryFeed.Activity)

@Preview(name = "Bookmarks — light", heightDp = 640)
@Composable
private fun BookmarksPreviewLight() = FeedPreview(dark = false, kind = HistoryFeed.Bookmarks)

@Preview(name = "Bookmarks — dark", heightDp = 640)
@Composable
private fun BookmarksPreviewDark() = FeedPreview(dark = true, kind = HistoryFeed.Bookmarks)

@Preview(name = "Activity loading — light", heightDp = 400)
@Composable
private fun LoadingPreviewLight() = FeedPreview(dark = false, kind = HistoryFeed.Activity, placeholder = FeedPlaceholder.Loading)

@Preview(name = "Activity loading — dark", heightDp = 400)
@Composable
private fun LoadingPreviewDark() = FeedPreview(dark = true, kind = HistoryFeed.Activity, placeholder = FeedPlaceholder.Loading)

@Preview(name = "Bookmarks empty — light", heightDp = 400)
@Composable
private fun EmptyPreviewLight() = FeedPreview(dark = false, kind = HistoryFeed.Bookmarks, placeholder = FeedPlaceholder.Empty)

@Preview(name = "Bookmarks empty — dark", heightDp = 400)
@Composable
private fun EmptyPreviewDark() = FeedPreview(dark = true, kind = HistoryFeed.Bookmarks, placeholder = FeedPlaceholder.Empty)

@Preview(name = "Activity failed — light", heightDp = 400)
@Composable
private fun ErrorPreviewLight() = FeedPreview(dark = false, kind = HistoryFeed.Activity, placeholder = FeedPlaceholder.Error)

@Preview(name = "Activity failed — dark", heightDp = 400)
@Composable
private fun ErrorPreviewDark() = FeedPreview(dark = true, kind = HistoryFeed.Activity, placeholder = FeedPlaceholder.Error)
