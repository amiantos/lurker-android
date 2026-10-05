// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.store.ChatState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadMarkersTest {
    private fun state(target: String, lastReadId: Long, known: Boolean = true, unread: Int = 0, bufferId: Int? = null): ChatState {
        val key = BufferKey(1, target)
        return ChatState(
            buffers = mapOf(
                key.id to Buffer(
                    networkId = 1,
                    target = target,
                    kind = BufferKind.of(1, target),
                    unread = unread,
                    lastReadId = lastReadId,
                    readStateKnown = known,
                    bufferId = bufferId,
                ),
            ),
        )
    }

    @Test
    fun aPointerAtOrPastTheMessageIsARead() {
        // "Mark all as read", or the buffer read here or anywhere: the pointer reaches the message.
        assertTrue(ReadMarkers.readPast(state("#lurker", lastReadId = 42), 1, "#lurker", 42))
        assertTrue(ReadMarkers.readPast(state("#lurker", lastReadId = 50), 1, "#lurker", 42))
    }

    @Test
    fun readingPastAMentionIsARead() {
        // Later lines still unread: the count never reaches 0, but the mention the push was about is read.
        assertTrue(ReadMarkers.readPast(state("#lurker", lastReadId = 45, unread = 12), 1, "#lurker", 42))
    }

    @Test
    fun aPointerBehindTheMessageIsNot() {
        // Also the stale-state case: a launch's leftover pointer predates the push, so it can't clear it.
        assertFalse(ReadMarkers.readPast(state("#lurker", lastReadId = 41), 1, "#lurker", 42))
    }

    @Test
    fun aPointerTheServerNeverStatedIsNot() {
        // A defaulted 0 — or anything not from a backlog or read-state frame — says nothing.
        assertFalse(ReadMarkers.readPast(state("#lurker", lastReadId = 99, known = false), 1, "#lurker", 42))
    }

    @Test
    fun aBufferTheStoreDoesNotHoldIsNot() {
        // A DM from someone new, pushed before this device has heard of the buffer.
        assertFalse(ReadMarkers.readPast(state("#lurker", lastReadId = 99), 1, "bob", 42))
        assertFalse(ReadMarkers.readPast(state("#lurker", lastReadId = 99), 2, "#lurker", 42))
    }

    @Test
    fun aRenamedBufferIsFoundByItsRowId() {
        // The push named `bob`; bob became `bobby` and the buffer moved to that key. The row id didn't.
        assertTrue(ReadMarkers.readPast(state("bobby", lastReadId = 42, bufferId = 9), 1, "bob", 42, bufferId = 9))
        assertFalse(ReadMarkers.readPast(state("bobby", lastReadId = 42, bufferId = 9), 1, "bob", 42))
    }

    @Test
    fun theTargetMatchesWhateverCaseTheServerUsed() {
        assertTrue(ReadMarkers.readPast(state("#lurker", lastReadId = 42), 1, "#Lurker", 42))
    }

    @Test
    fun aPushChecksItsOwnMessage() {
        val push = PushMessage.parse(
            mapOf("title" to "bob in #lurker", "tag" to "1::#lurker", "networkId" to "1", "target" to "#lurker", "messageId" to "42"),
        )!!
        assertTrue(push.readIn(state("#lurker", lastReadId = 42)))
        assertFalse(push.readIn(state("#lurker", lastReadId = 41)))
        // A came-online names no message, so nothing reads it.
        assertFalse(PushMessage.parse(mapOf("title" to "bob came online", "tag" to "1::bob::presence", "networkId" to "1", "target" to "bob"))!!.readIn(state("bob", lastReadId = 99)))
    }
}
