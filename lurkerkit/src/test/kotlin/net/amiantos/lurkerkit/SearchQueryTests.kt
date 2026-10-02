// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the `from:`/`in:`/`on:` filter grammar against the web client's, which is the point
 * of porting it rather than inventing one: the same string is typed by the same person on
 * both clients, and a scope seeded here has to mean what it means there.
 *
 * The first three cases are `vue_client/src/utils/searchQuery.test.ts` verbatim — including
 * the forgiving one, where a bare `from:` and an unknown `word:` stay in the free text. The
 * rest cover ground the web's suite doesn't state but its parser also has.
 */
class SearchQueryTests {

    @Test
    fun testPeelsStructuredFiltersOffTheFreeText() {
        val query = SearchQuery.parse("from:alice in:#dev on:libera hello world")
        assertEquals("hello world", query.text)
        assertEquals(listOf("alice"), query.from)
        assertEquals("#dev", query.target)
        assertEquals("libera", query.network)
    }

    @Test
    fun testCollectsMultipleFromIntoAnArray() {
        val query = SearchQuery.parse("from:eren from:nostimo from:twomoon needle")
        assertEquals(listOf("eren", "nostimo", "twomoon"), query.from)
        assertEquals("needle", query.text)
    }

    @Test
    fun testBareAndUnknownPrefixesStayFreeText() {
        val query = SearchQuery.parse("from: word: just text")
        assertEquals(emptyList(), query.from)
        assertEquals("from: word: just text", query.text)
    }

    @Test
    fun testKeysAreCaseInsensitiveButValuesArePreserved() {
        val query = SearchQuery.parse("FROM:Alice IN:#Dev ON:Libera")
        assertEquals(listOf("Alice"), query.from)
        assertEquals("#Dev", query.target)
        assertEquals("Libera", query.network)
    }

    /**
     * `in:` and `on:` are single-valued, so the last one typed wins — a user correcting a
     * mistyped channel shouldn't end up filtered to neither.
     */
    @Test
    fun testLastInAndOnWin() {
        val query = SearchQuery.parse("in:#one in:#two on:a on:b")
        assertEquals("#two", query.target)
        assertEquals("b", query.network)
    }

    /**
     * Split on the FIRST colon only, so a value containing one survives — `#c++` is fine
     * either way, but a `time:` in the search text after a filter is the real case.
     */
    @Test
    fun testValueMayContainAColon() {
        val query = SearchQuery.parse("in:#chan:extra rest")
        assertEquals("#chan:extra", query.target)
        assertEquals("rest", query.text)
    }

    @Test
    fun testWhitespaceIsCollapsedAndEmptyInputIsEmpty() {
        assertEquals("a b", SearchQuery.parse("   a    b  ").text)
        assertTrue(SearchQuery.parse("   ").isEmpty)
        assertTrue(SearchQuery.parse("").isEmpty)
    }

    /**
     * A filter with no free text is a legal search — "everything in this channel" is exactly
     * what the scoped entry point opens with, before a word has been typed.
     */
    @Test
    fun testFilterOnlyQueryIsNotEmpty() {
        assertFalse(SearchQuery.parse("in:#dev").isEmpty)
        assertFalse(SearchQuery.parse("from:alice").isEmpty)
        assertFalse(SearchQuery.parse("on:libera").isEmpty)
    }

    // MARK: - Dispatch floor

    /**
     * A lone ASCII letter is both the most expensive thing to answer (`a` and `i` are among
     * the commonest tokens in the index) and the least likely to be what anyone meant.
     */
    @Test
    fun testSingleAsciiCharacterNeedsMoreText() {
        assertTrue(SearchQuery.parse("a").needsMoreText)
        assertTrue(SearchQuery.parse("I").needsMoreText)
        assertTrue(SearchQuery.parse("7").needsMoreText)
    }

    @Test
    fun testTwoCharactersIsEnough() {
        assertFalse(SearchQuery.parse("hi").needsMoreText)
        assertFalse(SearchQuery.parse("ok").needsMoreText)
        assertFalse(SearchQuery.parse("hello").needsMoreText)
    }

    /**
     * The floor is on the FREE TEXT. A finished filter is a complete question that runs no
     * full-text pass at all, so gating it would be charging for something that's nearly free.
     */
    @Test
    fun testFilterOnlyQueryNeverNeedsMoreText() {
        assertFalse(SearchQuery.parse("in:#dev").needsMoreText)
        assertFalse(SearchQuery.parse("from:alice").needsMoreText)
        assertFalse(SearchQuery.parse("from:alice in:#dev on:libera").needsMoreText)
    }

    /** …but free text alongside a filter is still free text, and still has to clear the floor. */
    @Test
    fun testShortTextWithAFilterStillNeedsMoreText() {
        assertTrue(SearchQuery.parse("in:#dev a").needsMoreText)
    }

    /**
     * One CJK character is routinely a whole word, so the floor must not apply to it — a rule
     * that counted characters blindly would lock those users out of searching for it.
     */
    @Test
    fun testSingleNonAsciiCharacterIsEnough() {
        assertFalse(SearchQuery.parse("日").needsMoreText)
        assertFalse(SearchQuery.parse("б").needsMoreText)
    }

    /**
     * An empty query isn't "too short" — it's `isEmpty`, which the caller answers with its
     * landing view rather than a "keep typing" hint. Distinct states, distinct screens.
     */
    @Test
    fun testEmptyQueryDoesNotNeedMoreText() {
        assertFalse(SearchQuery.parse("").needsMoreText)
        assertFalse(SearchQuery.parse("   ").needsMoreText)
    }

    // MARK: - Scope

    @Test
    fun testScopeSeedsInAndOnWithATrailingSpace() {
        val channel = Buffer(networkId = 1, target = "#dev", kind = BufferKind.Channel)
        assertEquals("in:#dev on:libera ", SearchQuery.scope(channel, networkName = "libera"))
    }

    /** A round trip, since that's the actual contract: what `scope` writes, `parse` must read. */
    @Test
    fun testScopeRoundTripsThroughParse() {
        val dm = Buffer(networkId = 1, target = "alice", kind = BufferKind.Dm)
        val seed = SearchQuery.scope(dm, networkName = "libera")
        val query = SearchQuery.parse(seed ?: "")
        assertEquals("alice", query.target)
        assertEquals("libera", query.network)
        assertEquals("", query.text)
    }

    /**
     * Tokens split on spaces, so a network name with one could not round-trip. Dropping `on:`
     * leaves a scope that's slightly wider than asked for, which beats one that silently means
     * something else.
     */
    @Test
    fun testScopeDropsOnForANetworkNameWithWhitespace() {
        val channel = Buffer(networkId = 1, target = "#dev", kind = BufferKind.Channel)
        assertEquals("in:#dev ", SearchQuery.scope(channel, networkName = "My Server"))
        assertEquals("in:#dev ", SearchQuery.scope(channel, networkName = null))
    }

    /** Neither is a conversation, so neither has a scope to search within. */
    @Test
    fun testNoScopeForServerLogOrSystemBuffer() {
        val log = Buffer(networkId = 1, target = ":server:1", kind = BufferKind.Server)
        assertNull(SearchQuery.scope(log, networkName = "libera"))
        assertNull(SearchQuery.scope(Buffer.system, networkName = null))
    }
}
