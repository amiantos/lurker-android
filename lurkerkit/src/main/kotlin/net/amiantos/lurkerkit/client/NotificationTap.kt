// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

/**
 * Where a tapped push notification points (lurker-ios#15).
 *
 * Mirrors the custom keys the server puts BESIDE `aps` on iOS — Apple reserves `aps` itself,
 * so the routing keys ride at the top level of the payload. See the server's apnsSender.ts;
 * if these names drift apart, every notification tap silently lands on the wrong screen
 * with nothing failing.
 *
 * Port note: the same keys ride in the `data` map of an FCM message (the server's
 * fcmSender.ts, `buildFcmMessage`), where every value is a string.
 */
data class NotificationTap(
    val networkId: Int,
    val target: String,
    /**
     * The message that triggered the push, when it names one — a message/highlight/DM push
     * carries it (server stamps `messageId: decorated.id`), a friend-online push doesn't. Lets
     * a tap land on that exact line (lurker-ios#42), not just the buffer bottom.
     */
    val messageId: Long? = null,
) {
    companion object {
        /**
         * Read the routing keys off a push payload.
         *
         * Lives here rather than in the app code that receives it: it's pure logic over a
         * map, and it is the host-tested module — so parked next to the receiver it
         * would be the one piece of the push path nothing could check. `Map<String, Any?>` is
         * the standard library, not Android, so the kit can hold it without learning about the
         * OS.
         *
         * `null` for anything that doesn't name a buffer. A malformed payload should open the
         * app on whatever screen it would have shown anyway — never crash, and never guess at
         * a destination the user didn't ask for.
         *
         * Port note: `[AnyHashable: Any]` in LurkerKit. A key that isn't a string can't match
         * one of these names there either, so nothing is lost by the narrower key type.
         */
        fun parse(userInfo: Map<String, Any?>): NotificationTap? {
            val networkId = intField(userInfo["networkId"]) ?: return null
            val target = userInfo["target"] as? String ?: return null
            if (target.isEmpty()) return null
            // `messageId` reads the same way — absent or unparseable → null, and the tap simply
            // opens the buffer at its bottom rather than jumping.
            return NotificationTap(networkId = networkId, target = target, messageId = longField(userInfo["messageId"]))
        }

        /**
         * An id field off a push payload, coping with either shape it can arrive in: APNs sends a
         * JSON number (a boxed number here, of whatever width the decoder chose), FCM's data map
         * sends a string. The String arm is NOT redundant — the two payloads are one mistake
         * apart, and routing is the wrong place to be strict about which of our own servers sent
         * this.
         *
         * Port note: LurkerKit has one `intField`, because a Swift `Int` is 64-bit. This is the
         * read for `messageId`, which is a `Long` on this side; [intField] narrows it for
         * `networkId`, and a value that doesn't fit an `Int` is unparseable rather than wrapped.
         *
         * Port note: the two arms are written out to match what the Swift ones accept.
         * - A number is read the way an `NSNumber` bridges to `Int`: only when it is exactly a
         *   whole number, so `7.0` is 7 and `7.5` is nothing. A `Boolean` is NOT a number here,
         *   and not on iOS either since lurker-ios#229: JSON `true` arrives there as an
         *   `NSNumber` that `as? Int` would read as 1, and LurkerKit refuses it by type ("it isn't
         *   an id").
         *
         * Port note: LurkerKit's `intField` is internal, shared with `RelayNotification`, which
         * reads the same keys out of a relayed push. That file isn't ported (lurker-android#80
         * hands a relayed push to the app as FCM's string map instead), so this stays private.
         * - A string is read the way `Int(String)` reads it: an optional sign and ASCII digits,
         *   nothing else. `toLongOrNull` alone would also take other scripts' digits (`"٧"`).
         */
        private fun longField(value: Any?): Long? =
            when (value) {
                is Number -> {
                    val whole = value.toLong()
                    // Compared as doubles so a fraction or an out-of-range value is refused
                    // rather than truncated or clamped. 2^63 itself clamps to a value that
                    // compares equal, hence the explicit bound.
                    val asDouble = value.toDouble()
                    if (asDouble == whole.toDouble() && asDouble < 9.223372036854775807E18) whole else null
                }
                is String ->
                    if (value.all { it in '0'..'9' || it == '+' || it == '-' }) value.toLongOrNull() else null
                else -> null
            }

        private fun intField(value: Any?): Int? =
            longField(value)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    }
}
