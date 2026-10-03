// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.search

import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurkerkit.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The query half of lurker-ios's `MessageSearchViewController`. */
class SearchModelTest {

    private fun showing(raw: String) = SearchShowing.of(SearchQuery.parse(raw))

    @Test
    fun anEmptyFieldIsTheLandingViewNeverNoResults() {
        assertEquals(SearchShowing.Landing, showing(""))
        assertEquals(SearchShowing.Landing, showing("   "))
        assertEquals(SearchShowing.Landing, SearchQueryLedger().showing)
    }

    @Test
    fun oneLatinLetterWaitsButAFilterOrACjkCharacterDoesNot() {
        assertEquals(SearchShowing.TooShort, showing("h"))
        assertEquals(SearchShowing.Results, showing("hi"))
        assertEquals(SearchShowing.Results, showing("in:#dev"))
        assertEquals(SearchShowing.Results, showing("日"))
    }

    @Test
    fun aScopedOpenStartsOnItsSearchNotTheLandingView() {
        val ledger = SearchQueryLedger(seed = "in:#lurker on:Libera ")
        assertEquals(SearchShowing.Results, ledger.showing)
        assertEquals("#lurker", ledger.parsed.target)
        assertFalse(ledger.isNew("in:#lurker on:Libera "))
    }

    @Test
    fun onlyANewQuestionReloads() {
        val ledger = SearchQueryLedger()
        assertTrue(ledger.commit("deploy"))
        // A space typed after a word, or between two, asks the server nothing new.
        assertFalse(ledger.commit("deploy "))
        assertEquals("deploy ", ledger.query)
        assertTrue(ledger.commit("deploy log"))
        assertFalse(ledger.commit("deploy  log"))
        // Deleting back to nothing returns to the landing view.
        assertTrue(ledger.commit(""))
        assertEquals(SearchShowing.Landing, ledger.showing)
        // Whitespace alone is still the landing view.
        assertFalse(ledger.commit("  "))
    }

    @Test
    fun theFloorIsAnsweredLocallyButStillMovesTheMode() {
        val ledger = SearchQueryLedger()
        assertTrue(ledger.commit("h"))
        assertEquals(SearchShowing.TooShort, ledger.showing)
        assertTrue(ledger.commit("hi"))
        assertEquals(SearchShowing.Results, ledger.showing)
    }

    @Test
    fun theThreeEmptiesSayDifferentThings() {
        val landing = SearchWords.empty(SearchShowing.Landing, "")
        assertEquals("Search your history", landing.title)
        assertTrue(landing.subtitle!!.contains("from:nick, in:#channel, or on:network"))
        assertEquals("Keep typing", SearchWords.empty(SearchShowing.TooShort, "h").title)
        assertEquals("Nothing in your history matches zebra.", SearchWords.empty(SearchShowing.Results, "zebra").subtitle)
        assertEquals("Loading highlights…", SearchWords.loading(SearchShowing.Landing).title)
        assertEquals("Searching…", SearchWords.loading(SearchShowing.Results).title)
        assertEquals("Couldn't load highlights", SearchWords.error(SearchShowing.Landing).title)
        assertEquals("Pull to try again, or edit your search.", SearchWords.error(SearchShowing.Results).subtitle)
    }

    /** iOS's symbols: the magnifier for both "search" empties, the ellipsis for keep typing, the warning for a failure. */
    @Test
    fun theStatesCarryIosGlyphs() {
        assertEquals(StateSymbol.Search, SearchWords.empty(SearchShowing.Landing, "").symbol)
        assertEquals(StateSymbol.Ellipsis, SearchWords.empty(SearchShowing.TooShort, "h").symbol)
        assertEquals(StateSymbol.Search, SearchWords.empty(SearchShowing.Results, "zebra").symbol)
        assertEquals(StateSymbol.Warning, SearchWords.error(SearchShowing.Landing).symbol)
        assertEquals(StateSymbol.Warning, SearchWords.error(SearchShowing.Results).symbol)
        assertNull(SearchWords.loading(SearchShowing.Results).symbol)
    }
}
