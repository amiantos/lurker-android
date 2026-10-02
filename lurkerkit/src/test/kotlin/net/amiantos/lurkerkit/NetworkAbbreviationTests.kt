// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.NetworkAbbreviation
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkAbbreviationTests {
    @Test
    fun testOneNetworkAbbreviatesToASingleCharacter() {
        assertEquals(mapOf(1 to "l"), NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "libera")))
    }

    @Test
    fun testDistinctFirstLettersStopAtOne() {
        val out = NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "libera", 2 to "mansionNET"))
        assertEquals(mapOf(1 to "l", 2 to "m"), out)
    }

    /** The reason this isn't just `first`: a shared first letter has to grow until it separates. */
    @Test
    fun testSharedPrefixGrowsUntilUnique() {
        val out = NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "libera", 2 to "lurkernet"))
        assertEquals(mapOf(1 to "li", 2 to "lu"), out)
    }

    /**
     * Unique against every *other* name, not just distinct from the other labels: `lunar`
     * can't stop at `lu` even though no other label is `lu`, because `lurker` starts with it.
     */
    @Test
    fun testGrowsPastTwoWhenNeeded() {
        val out = NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "lurker", 2 to "lurknet", 3 to "lunar"))
        assertEquals(mapOf(1 to "lurke", 2 to "lurkn", 3 to "lun"), out)
    }

    /**
     * One name being a strict prefix of another is the case that can't be separated from the
     * short side: every prefix of "irc" is also a prefix of "ircnet", so "irc" takes its whole
     * name while "ircnet" needs one more character.
     */
    @Test
    fun testNameThatIsAPrefixOfAnotherTakesItsFullName() {
        val out = NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "irc", 2 to "ircnet"))
        assertEquals(mapOf(1 to "irc", 2 to "ircn"), out)
    }

    @Test
    fun testLowercasesRegardlessOfHowTheNetworkIsNamed() {
        val out = NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "MansionNET", 2 to "Libera"))
        assertEquals(mapOf(1 to "m", 2 to "l"), out)
    }

    /**
     * Identical names can't be separated at all. Both get the full name rather than a
     * tiebreaker the web client doesn't have — the ambiguity is real and saying so is honest.
     */
    @Test
    fun testIdenticalNamesBothTakeTheFullName() {
        val out = NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "libera", 2 to "libera"))
        assertEquals(mapOf(1 to "libera", 2 to "libera"), out)
    }

    @Test
    fun testEmptyInputsDoNotTrap() {
        assertEquals(emptyMap(), NetworkAbbreviation.shortestUniquePrefixes(emptyMap()))
        assertEquals(mapOf(1 to ""), NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "")))
        assertEquals(
            mapOf(1 to "", 2 to "l"),
            NetworkAbbreviation.shortestUniquePrefixes(mapOf(1 to "", 2 to "libera")),
        )
    }

    /**
     * The result must depend only on the set of names, never on map iteration order —
     * otherwise a chip's label could change across a rebuild with nothing else moving.
     *
     * A fresh map built from SHUFFLED pairs each pass, not one instance called repeatedly: a
     * given map enumerates the same way every time you ask it, so re-reading one instance in a
     * loop would pass against an implementation that only compared each name to the ones it had
     * already visited.
     *
     * Port note: this bites harder here than in Swift. A Kotlin map iterates in insertion
     * order, so the shuffle is the only thing that varies the order at all.
     */
    @Test
    fun testIsIndependentOfInsertionOrder() {
        val pairs = listOf(1 to "libera", 2 to "mansionNET", 3 to "lurkernet")
        val expected = mapOf(1 to "li", 2 to "m", 3 to "lu")
        repeat(50) {
            val names = mutableMapOf<Int, String>()
            for ((id, name) in pairs.shuffled()) names[id] = name
            assertEquals(expected, NetworkAbbreviation.shortestUniquePrefixes(names))
        }
    }
}
