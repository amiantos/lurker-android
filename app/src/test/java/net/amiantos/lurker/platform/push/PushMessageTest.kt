// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import net.amiantos.lurkerkit.client.NotificationTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushMessageTest {
    /**
     * The data map lurker's `buildFcmMessage` sends for a DM (its wireFormat test's "carries the Web
     * Push body, key for key"): the Web Push body, every value a string.
     */
    private val dm = mapOf(
        "kind" to "dm",
        "networkId" to "7",
        "networkName" to "Libera",
        "target" to "bob",
        "bufferId" to "9",
        "nick" to "bob",
        "text" to "hey there",
        "time" to "2026-10-05T12:00:00.000Z",
        "messageId" to "42",
        "displayName" to "bob",
        "badge" to "3",
        "title" to "bob (Libera)",
        "body" to "hey there",
        "tag" to "7::bob",
    )

    @Test
    fun readsTheServersComposedCopy() {
        val message = PushMessage.parse(dm)!!
        assertEquals(PushMessage.Kind.DM, message.kind)
        assertEquals("bob (Libera)", message.title)
        assertEquals("hey there", message.body)
        assertEquals("7::bob", message.tag)
        assertEquals(1_791_201_600_000L, message.sentAt)
        assertEquals(3, message.badge)
    }

    @Test
    fun aTapCarriesWhatNotificationTapReads() {
        // The extras go through MainActivity into the kit's parser: a tap must land on the buffer
        // and the message, not merely open the app.
        val tap = NotificationTap.parse(PushMessage.parse(dm)!!.tap)
        assertEquals(NotificationTap(networkId = 7, target = "bob", messageId = 42), tap)
        // Only the routing keys ride the intent; the message text stays out of it.
        assertEquals(setOf("networkId", "target", "messageId"), PushMessage.parse(dm)!!.tap.keys)
    }

    @Test
    fun eachKindHasItsOwnChannel() {
        val channels = PushMessage.Kind.entries.map { it.channelId }
        assertEquals(channels.size, channels.toSet().size)
        assertEquals(PushMessage.Kind.KICKED, PushMessage.parse(dm + ("kind" to "kicked"))!!.kind)
        assertEquals(PushMessage.Kind.FRIEND_ONLINE, PushMessage.parse(dm + ("kind" to "friend_online"))!!.kind)
    }

    @Test
    fun aKindThisBuildDoesNotKnowIsStillShown() {
        // A newer server decided it was worth a push; dropping it would be the silent failure.
        assertEquals(PushMessage.Kind.OTHER, PushMessage.parse(dm + ("kind" to "reaction"))!!.kind)
    }

    @Test
    fun aCameOnlineHasNoBody() {
        val online = dm + mapOf("kind" to "friend_online", "body" to "", "tag" to "7::bob::presence") - "text" - "messageId"
        val message = PushMessage.parse(online)!!
        assertEquals("", message.body)
        assertEquals(setOf("networkId", "target"), message.tap.keys)
    }

    @Test
    fun nothingToShowWithoutTheComposedCopy() {
        // A server older than lurker#1046 sends a `notification` block instead, which the system
        // draws itself; its data has no title or tag.
        assertNull(PushMessage.parse(dm - "title"))
        assertNull(PushMessage.parse(dm - "tag"))
        assertNull(PushMessage.parse(dm + ("title" to "")))
    }

    @Test
    fun anUnreadableTimeOrBadgeIsLeftOut() {
        val message = PushMessage.parse(dm + mapOf("time" to "yesterday", "badge" to "-1"))!!
        assertNull(message.sentAt)
        assertNull(message.badge)
    }
}
