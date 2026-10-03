// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.search

import net.amiantos.lurker.ui.feeds.StateWords
import net.amiantos.lurkerkit.model.SearchQuery

/**
 * What the search list is showing. Decided when a query is committed and then HELD, rather than
 * recomputed wherever it's needed: a page arriving from the wire has to be read as an answer to what
 * was asked, not to whatever the field says by the time it lands.
 */
enum class SearchShowing {
    /**
     * Nothing typed — the landing view: your recent highlights. ⚠⚠ Never "no results": opening search
     * always lands on something you can act on, and the empty state here explains the screen.
     */
    Landing,

    /** Something typed, but not yet enough to be worth asking the server. */
    TooShort,

    /** A real query, dispatched. */
    Results,
    ;

    companion object {
        /**
         * What a parsed query puts on screen. The two rules it reads — "is there anything to search on"
         * and "is it enough to be worth asking" — are the query's own (`SearchQuery`).
         */
        fun of(query: SearchQuery): SearchShowing =
            when {
                query.isEmpty -> Landing
                query.needsMoreText -> TooShort
                else -> Results
            }
    }
}

/**
 * The query half of lurker-ios's `MessageSearchViewController`: which question the list answers, and
 * when a keystroke is worth a new one. Pure; `SearchState` runs the debounce and the fetches.
 *
 * Typing is NOT a filter over the landing view: that's the landing surface, not the corpus, and
 * narrowing to it would make the most useful search in the app — everything you have ever seen — the
 * one you couldn't run from here.
 *
 * @param seed the text the field starts with — an `in:#chan on:net ` scope for "Search This
 *   Conversation". Adopted as the query before the first load, so a scoped open fetches its search
 *   directly rather than spending a landing round trip on a screen never going to show it.
 */
class SearchQueryLedger(seed: String = "") {
    /** The raw text the shown list answers, for the "no matches" copy. */
    var query: String = seed
        private set

    /**
     * …and its parsed form, which is what decides whether a keystroke is worth a round trip: two raw
     * strings that parse the same ask the server the same question.
     */
    var parsed: SearchQuery = SearchQuery.parse(seed)
        private set

    var showing: SearchShowing = SearchShowing.of(parsed)
        private set

    /** Whether [text] differs from the committed query — a keystroke worth scheduling a commit for. */
    fun isNew(text: String): Boolean = text != query

    /**
     * Adopt [text] as the query; true when the list must reload. Reads the PARSED query, so "empty"
     * means what it means to the server: a field of spaces is empty, and one holding only `in:#dev` is
     * not. Deleting back to nothing returns to the landing view — the field is a filter you can back out
     * of, not a mode you entered.
     *
     * Nothing the server would answer differently — a keystroke that moved only whitespace, or a field
     * still below the floor — reloads nothing: a space typed between two words used to cost a full
     * round trip for the same question.
     */
    fun commit(text: String): Boolean {
        val next = SearchQuery.parse(text)
        val nextShowing = SearchShowing.of(next)
        query = text
        if (next == parsed && nextShowing == showing) return false
        parsed = next
        showing = nextShowing
        return true
    }

    /** The landing view's answer expires — highlights accumulate — so a pull or reopen re-asks; see `SearchState`. */
    val isLanding: Boolean get() = showing == SearchShowing.Landing

    companion object {
        /**
         * Longer than the web client's 200ms on purpose. A search is the most expensive thing this client
         * can ask for — the FTS query runs on the server's event loop, the one servicing every IRC
         * connection on the cell — so the debounce has to outlast the gap between keystrokes or it
         * coalesces nothing. Thumb-typing lands around 150–300ms; 350 sits above that band while staying
         * below the point where the field feels laggy.
         */
        const val DEBOUNCE_MS = 350L
    }
}

/** The search screen's placeholders, in iOS's words. */
object SearchWords {
    fun loading(showing: SearchShowing): StateWords =
        StateWords(if (showing == SearchShowing.Landing) "Loading highlights…" else "Searching…")

    /**
     * Three different empties, and telling them apart is most of this placeholder's job. The landing
     * one matters most: a quiet account has no highlights, so the landing surface is empty for exactly
     * the people who most need telling what this screen does — so it says both, that you can search
     * (and the grammar), and what the list would otherwise have held.
     */
    fun empty(showing: SearchShowing, query: String): StateWords =
        when (showing) {
            SearchShowing.Landing -> StateWords(
                "Search your history",
                "Type to search every network — narrow it with from:nick, in:#channel, or on:network. " +
                    "Messages that match your highlight rules show up here too.",
            )
            // Says the rule rather than just withholding results, so a field that has visibly stopped
            // responding is explained instead of looking broken.
            SearchShowing.TooShort -> StateWords("Keep typing", "Searches start at two characters.")
            SearchShowing.Results -> StateWords("No matches", "Nothing in your history matches $query.")
        }

    /** Both halves are ordinary REST reads, so both get the ordinary advice. */
    fun error(showing: SearchShowing): StateWords =
        if (showing == SearchShowing.Landing) {
            StateWords("Couldn't load highlights", "Pull to try again.")
        } else {
            StateWords("Couldn't search", "Pull to try again, or edit your search.")
        }
}
