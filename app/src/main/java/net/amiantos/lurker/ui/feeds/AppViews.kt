// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import net.amiantos.lurker.ui.theme.LurkerIcons

/**
 * The app's views — the surfaces you *look at*, as against the buffer you're in: what the menus list
 * and, where a bar has room, what its buttons open. One title and one glyph each, so a menu row and a
 * bar button for the same view can't drift apart between the two screens that carry them.
 * lurker-ios's `AppView`, minus the Lurker buffer (the list's own menu row) and Uploads (U8).
 */
enum class AppView(val title: String) {
    Search("Search"),

    /**
     * "Activity" since reactions to your lines joined highlights there (lurker-ios#183), the web's name
     * for the same feed. Highlights by any other name: the endpoint and the kit keep the old one.
     */
    Activity("Activity"),
    Bookmarks("Bookmarks"),
    // U8: Uploads.
    ;

    /** Its glyph — iOS's `magnifyingglass`, `at`, `bookmark`. */
    val icon: ImageVector
        get() = when (this) {
            Search -> LurkerIcons.Search
            Activity -> FeedIcons.AlternateEmail
            Bookmarks -> FeedIcons.Bookmark
        }
}

/**
 * Which screen carries which view, per layout — lurker-ios's two `applyBarLayout`s, mirrored with the
 * scaffold's `sideBySide` flag so nothing appears twice on screen.
 *
 * **One pane** (a phone): the list is a screen of its own, so it carries the views — Search as a
 * magnifier in its bar (Android's search action, where iOS puts a field in the bottom toolbar), and
 * Activity and Bookmarks in its ⋮ menu. The conversation, the only thing on screen once you're in it,
 * carries all three behind its own ⋮, as iOS's chat bar does.
 *
 * **Side by side**: the conversation column has the room, so it carries them — Search as a button at
 * the bar's trailing edge (iOS's column field), the rest in its menu — and the sidebar sheds its copies,
 * keeping its ⋮ to the app-wide entries (the Lurker buffer, Mark All as Read, Settings).
 */
object ViewsLayout {
    /** Whether the buffer list's bar carries the search action. */
    fun listSearch(sideBySide: Boolean): Boolean = !sideBySide

    /** The views in the buffer list's ⋮ menu. Search isn't there: it's the bar's magnifier. */
    fun listMenu(sideBySide: Boolean): List<AppView> = if (sideBySide) emptyList() else listOf(AppView.Activity, AppView.Bookmarks)

    /** The views the conversation's bar shows as buttons of their own. */
    fun conversationButtons(sideBySide: Boolean): List<AppView> = if (sideBySide) listOf(AppView.Search) else emptyList()

    /** The views behind the conversation's ⋮, in iOS's order. */
    fun conversationMenu(sideBySide: Boolean): List<AppView> =
        AppView.entries.filter { it !in conversationButtons(sideBySide) }
}

/**
 * The conversation bar's views — buttons where the layout has room for them, then the ⋮ menu holding the
 * rest, trailing-most (lurker-ios's `overflowItem`). A ⋮ rather than iOS's "…": Android's overflow.
 * Nothing in it varies by buffer: every entry is app-scoped, so the menu is the same menu everywhere.
 */
@Composable
fun ConversationViewsActions(sideBySide: Boolean, onOpenView: (AppView) -> Unit) {
    for (view in ViewsLayout.conversationButtons(sideBySide)) {
        IconButton(onClick = { onOpenView(view) }) { Icon(view.icon, contentDescription = view.title) }
    }
    val menu = ViewsLayout.conversationMenu(sideBySide)
    if (menu.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(LurkerIcons.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (view in menu) {
                DropdownMenuItem(
                    text = { Text(view.title) },
                    leadingIcon = { Icon(view.icon, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onOpenView(view)
                    },
                )
            }
        }
    }
}
