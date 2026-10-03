// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

/**
 * The glyph a [StateModel] leads with — lurker-ios's SF Symbol names, by what they mean. Plain
 * Kotlin, so the pure screen models that decide a state can say which glyph it carries, and their
 * tests can pin it; `StateView` draws each as the Material glyph Android users already read.
 */
enum class StateSymbol {
    /** `exclamationmark.triangle` — a load that failed. */
    Warning,

    /** `exclamationmark.circle` — a notice that something went wrong. */
    Notice,

    /** `magnifyingglass` — search, and a search that matched nothing. */
    Search,

    /** `ellipsis` — keep typing. */
    Ellipsis,

    /** `bookmark` — no bookmarks. */
    Bookmark,

    /** `at` — no activity. */
    Mention,

    /** `text.bubble` — a conversation with nothing in it yet. */
    Conversation,

    /** `server.rack` — a server log with nothing in it yet. */
    Server,

    /** `sparkles` — the system buffer's welcome. */
    Welcome,

    /** `bubble.left.and.bubble.right` — an empty buffer list. */
    Buffers,

    /** `network` — no networks yet. */
    Network,

    /** `network.slash` — no networks on offer. */
    NoNetworks,

    /** `star` — nothing starred. */
    Star,

    /** `line.3.horizontal.decrease.circle` — nothing under a filter. */
    Filter,

    /** `photo.on.rectangle` — no uploads yet. */
    Uploads,
}

/**
 * What a `StateView` says — iOS's `StateView.Model`: a glyph *or* a spinner, a title, an optional
 * subtitle and an optional way out. For a screen with several states: build one of these in a `when`
 * and draw it through ONE `StateView` call, so moving between the states is a change to one live
 * region rather than a node swapped for another (see `StateView`). The action's handler stays with
 * the call, as iOS keeps it off its `Equatable` model.
 */
data class StateModel(
    val title: String,
    val symbol: StateSymbol? = null,
    val subtitle: String? = null,
    val isLoading: Boolean = false,
    val actionTitle: String? = null,
)
