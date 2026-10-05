// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.store.ChatState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadTransitionsTest {
    private val lurker = BufferKey(1, "#Lurker")
    private val bob = BufferKey(1, "bob")

    private fun state(vararg unread: Pair<BufferKey, Int>, settled: Boolean = true) = ChatState(
        buffers = unread.associate { (key, count) ->
            key.id to Buffer(networkId = key.networkId, target = key.target, kind = BufferKind.of(key.networkId, key.target), unread = count)
        },
        backlogComplete = settled,
    )

    @Test
    fun markAllAsReadClearsEveryBufferThatHadSomething() {
        // The reported bug: "mark all as read" left 12 on the icon, because nothing took the
        // notifications down.
        val transitions = ReadTransitions()
        transitions.observe(state(lurker to 4, bob to 8))
        assertEquals(setOf(lurker.id, bob.id), transitions.observe(state(lurker to 0, bob to 0)))
    }

    @Test
    fun onlyTheBufferThatWasReadClears() {
        val transitions = ReadTransitions()
        transitions.observe(state(lurker to 4, bob to 8))
        assertEquals(setOf(bob.id), transitions.observe(state(lurker to 5, bob to 0)))
    }

    @Test
    fun theFirstStateClearsNothing() {
        // A launch starts from whatever the store holds, which can predate the push that's in the
        // shade: "0 unread" there is not a read.
        assertEquals(emptySet<String>(), ReadTransitions().observe(state(lurker to 0, bob to 0)))
    }

    @Test
    fun anUnsettledStateIsNotEvidence() {
        // Mid-snapshot, a buffer can read 0 before its counts land.
        val transitions = ReadTransitions()
        transitions.observe(state(lurker to 4))
        assertEquals(emptySet<String>(), transitions.observe(state(lurker to 0, settled = false)))
        // ...and the settled state after it is compared with the last SETTLED one.
        assertEquals(setOf(lurker.id), transitions.observe(state(lurker to 0)))
    }

    @Test
    fun aBufferThatLeftTheRosterIsNotARead() {
        val transitions = ReadTransitions()
        transitions.observe(state(lurker to 4, bob to 2))
        assertEquals(emptySet<String>(), transitions.observe(state(lurker to 4)))
    }

    @Test
    fun signOutForgetsTheLastSession() {
        val transitions = ReadTransitions()
        transitions.observe(state(lurker to 4))
        transitions.reset()
        assertEquals(emptySet<String>(), transitions.observe(state(lurker to 0)))
    }
}
