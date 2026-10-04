// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import net.amiantos.lurker.ui.shell.StateSymbol
import androidx.compose.runtime.saveable.SaverScope
import net.amiantos.lurkerkit.model.FeedPaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the views live per layout, and the feed dialog's saved request. */
class FeedSheetsTest {

    private val scope = SaverScope { true }

    @Test
    fun onOnePaneBothScreensCarryEveryView() {
        // The list: search in its bar, the rest in its menu.
        assertTrue(ViewsLayout.listSearch(sideBySide = false))
        assertEquals(listOf(AppView.Activity, AppView.Bookmarks, AppView.Uploads), ViewsLayout.listMenu(sideBySide = false))
        // The conversation: all four behind its ⋮, in iOS's order.
        assertEquals(emptyList<AppView>(), ViewsLayout.conversationButtons(sideBySide = false))
        assertEquals(
            listOf(AppView.Search, AppView.Activity, AppView.Bookmarks, AppView.Uploads),
            ViewsLayout.conversationMenu(sideBySide = false),
        )
    }

    @Test
    fun sideBySideTheConversationCarriesThemAndNothingAppearsTwice() {
        assertTrue(!ViewsLayout.listSearch(sideBySide = true))
        assertEquals(emptyList<AppView>(), ViewsLayout.listMenu(sideBySide = true))
        val conversation = ViewsLayout.conversationButtons(sideBySide = true) + ViewsLayout.conversationMenu(sideBySide = true)
        assertEquals(AppView.entries.toSet(), conversation.toSet())
        assertEquals(conversation.size, conversation.toSet().size)
        assertEquals(listOf(AppView.Search), ViewsLayout.conversationButtons(sideBySide = true))
    }

    @Test
    fun aRequestSurvivesBeingSavedSeedAndAll() {
        val request = FeedRequest(AppView.Search, "token", "in:#c++ on:Libera ")
        val saved = with(FeedRequest.Saver) { scope.save(request) }!!
        assertEquals(request, FeedRequest.Saver.restore(saved))
        val none = with(FeedRequest.Saver) { scope.save(null) }!!
        assertNull(FeedRequest.Saver.restore(none))
    }

    @Test
    fun theHistoryFeedsSayWhatTheyAre() {
        assertEquals("Activity", HistoryFeed.Activity.title)
        assertEquals("No recent activity", HistoryFeed.Activity.words(FeedPaging.Placeholder.Empty).title)
        assertEquals("Press and hold a message, then Save Message, to keep it here.", HistoryFeed.Bookmarks.words(FeedPaging.Placeholder.Empty).subtitle)
        assertEquals("Couldn't load bookmarks", HistoryFeed.Bookmarks.words(FeedPaging.Placeholder.Error).title)
    }

    /** iOS's symbols: `at` for Activity's empty, `bookmark` for Bookmarks', the warning for a failure, none while loading. */
    @Test
    fun theHistoryFeedsCarryIosGlyphs() {
        assertEquals(StateSymbol.Mention, HistoryFeed.Activity.words(FeedPaging.Placeholder.Empty).symbol)
        assertEquals(StateSymbol.Bookmark, HistoryFeed.Bookmarks.words(FeedPaging.Placeholder.Empty).symbol)
        for (feed in HistoryFeed.entries) {
            assertEquals(StateSymbol.Warning, feed.words(FeedPaging.Placeholder.Error).symbol)
            assertNull(feed.words(FeedPaging.Placeholder.Loading).symbol)
        }
    }
}
