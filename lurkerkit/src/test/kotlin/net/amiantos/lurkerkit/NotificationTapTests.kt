// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.NotificationTap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tap routing (lurker-ios#15). The payloads here are the ones the server actually builds — see
 * buildApnsRequest in the lurker repo — rather than shapes invented to match the parser,
 * which would only prove the parser agrees with itself.
 */
class NotificationTapTests {

    /**
     * A DM push, exactly as `buildApnsRequest` composes it: `aps` for what iOS renders,
     * routing keys beside it for where a tap goes.
     */
    private fun dmPayload(): MutableMap<String, Any?> =
        mutableMapOf(
            "aps" to mapOf(
                "alert" to mapOf("title" to "bob (Libera)", "body" to "hey there"),
                "badge" to 3,
                "sound" to "default",
                "thread-id" to "7::bob",
            ),
            "networkId" to 7,
            "target" to "bob",
            "messageId" to 42,
            "kind" to "dm",
        )

    @Test
    fun testParsesADMPush() {
        val tap = NotificationTap.parse(dmPayload())
        assertEquals(NotificationTap(networkId = 7, target = "bob", messageId = 42), tap)
    }

    @Test
    fun testParsesAChannelHighlight() {
        val payload = dmPayload()
        payload["target"] = "#lurker"
        payload["kind"] = "highlight"
        assertEquals(
            NotificationTap(networkId = 7, target = "#lurker", messageId = 42),
            NotificationTap.parse(payload),
        )
    }

    @Test
    fun testParsesTheMessageIdForTheJumpTarget() {
        // The server stamps `messageId: decorated.id`; the tap carries it so it can land on
        // the exact line (lurker-ios#42), and it survives arriving as a number of another
        // width (an `NSNumber` off real JSON, on iOS).
        assertEquals(42L, NotificationTap.parse(dmPayload())?.messageId)
        val wideNumberPayload = dmPayload()
        wideNumberPayload["messageId"] = 99L
        assertEquals(99L, NotificationTap.parse(wideNumberPayload)?.messageId)
        // FCM's all-strings shape.
        val stringPayload = dmPayload()
        stringPayload["messageId"] = "1234"
        assertEquals(1234L, NotificationTap.parse(stringPayload)?.messageId)
    }

    @Test
    fun testAMissingMessageIdIsNilNotAFailure() {
        // A friend-online push names a buffer but no message; the tap still routes, without a
        // jump target.
        val payload = dmPayload()
        payload.remove("messageId")
        val tap = NotificationTap.parse(payload)
        assertNotNull(tap)
        assertNull(tap.messageId)
    }

    @Test
    fun testParsesAFriendOnlinePush() {
        // Carries no messageId — the tap still has to route, to the friend's DM.
        val payload = mapOf<String, Any?>(
            "aps" to mapOf("alert" to mapOf("title" to "Amiantos came online (Libera)", "body" to "")),
            "networkId" to 3,
            "target" to "nostimo",
            "kind" to "friend_online",
        )
        assertEquals(NotificationTap(networkId = 3, target = "nostimo"), NotificationTap.parse(payload))
    }

    /**
     * On iOS a real APNs payload arrives via JSON, so networkId is an `NSNumber` rather than a
     * Swift `Int`. This pins the CONTRACT (a number of any boxed width routes) rather than any
     * particular arm of the `when`.
     */
    @Test
    fun testParsesNetworkIdArrivingAsNSNumber() {
        val payload = dmPayload()
        payload["networkId"] = 7L
        assertEquals(7, NotificationTap.parse(payload)?.networkId)
    }

    @Test
    fun testParsesNetworkIdArrivingAsString() {
        // FCM's data map is all-strings — which on this platform is the shape every payload
        // takes; see the port-only test below.
        val payload = dmPayload()
        payload["networkId"] = "7"
        assertEquals(7, NotificationTap.parse(payload)?.networkId)
    }

    @Test
    fun testPreservesTargetCasing() {
        // The tap carries whatever case the server sent. Folding happens at lookup time
        // (BufferKey.id), not here — the original casing is what gets echoed back.
        val payload = dmPayload()
        payload["target"] = "#Lurker"
        assertEquals("#Lurker", NotificationTap.parse(payload)?.target)
    }

    // MARK: - Payloads that name no buffer

    @Test
    fun testRejectsAPayloadWithNoRoutingKeys() {
        // A notification with nothing but `aps` should open the app, not crash it and not
        // guess at a destination.
        assertNull(NotificationTap.parse(mapOf("aps" to mapOf("alert" to "hi"))))
    }

    @Test
    fun testRejectsAMissingTarget() {
        val payload = dmPayload()
        payload.remove("target")
        assertNull(NotificationTap.parse(payload))
    }

    @Test
    fun testRejectsAnEmptyTarget() {
        // An empty string is a buffer key that matches nothing; routing to it would land
        // on a blank screen, which is worse than staying put.
        val payload = dmPayload()
        payload["target"] = ""
        assertNull(NotificationTap.parse(payload))
    }

    @Test
    fun testRejectsAMissingNetworkId() {
        val payload = dmPayload()
        payload.remove("networkId")
        assertNull(NotificationTap.parse(payload))
    }

    @Test
    fun testRejectsANonNumericNetworkId() {
        val payload = dmPayload()
        payload["networkId"] = "not-a-number"
        assertNull(NotificationTap.parse(payload))
    }

    @Test
    fun testRejectsAnEmptyPayload() {
        assertNull(NotificationTap.parse(emptyMap()))
    }

    // Not ported: testParsesAPayloadDecodedFromRealJSON. It pins what `JSONSerialization` makes
    // of an APNs payload; a push never reaches this platform as decoded JSON. The shape it
    // does arrive in is the one below.

    // Port-only:

    /**
     * The `data` map of an FCM message, exactly as the server's `buildFcmMessage` composes it:
     * every value a string, because FCM rejects anything else.
     */
    private fun fcmData(): MutableMap<String, Any?> =
        mutableMapOf(
            "kind" to "dm",
            "networkId" to "7",
            "target" to "bob",
            "messageId" to "42",
            "bufferId" to "19",
            "badge" to "3",
        )

    @Test
    fun testParsesTheDataMapOfAnFCMMessage() {
        assertEquals(
            NotificationTap(networkId = 7, target = "bob", messageId = 42),
            NotificationTap.parse(fcmData()),
        )
    }

    @Test
    fun testAnFCMFriendOnlinePushHasNoMessageId() {
        val data = fcmData()
        data["kind"] = "friend_online"
        data.remove("messageId")
        assertEquals(NotificationTap(networkId = 7, target = "bob"), NotificationTap.parse(data))
    }

    @Test
    fun testANumberThatIsNotWholeOrAStringThatIsNotAllDigitsIsNotAnId() {
        for (bad in listOf<Any?>(7.5, "7.5", "7 ", " 7", "٧", "", true, null)) {
            val payload = dmPayload()
            payload["networkId"] = bad
            assertNull(NotificationTap.parse(payload), "networkId = $bad")
        }
        // A networkId past 32 bits is unparseable, not wrapped.
        val payload = dmPayload()
        payload["networkId"] = "4294967303"
        assertNull(NotificationTap.parse(payload))
    }
}
