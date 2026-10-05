// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform.push

import net.amiantos.lurkerkit.store.ChatState
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One push, read off an FCM message's data map (lurker-android#16): what the notification says,
 * which channel it goes on, what it replaces, and where a tap goes.
 *
 * The server sends data-only messages whose keys are the Web Push body's, every value a string
 * (lurker's `buildFcmMessage`): the routing keys `NotificationTap` reads, plus the copy the server
 * composed — `title`, `body`, and `tag`, the per-buffer key a later push replaces this one by. The
 * app composes nothing; the wording is the server's, as it is on iOS and in the service worker.
 *
 * Pure, so the JVM tests reach it; [PushNotifier] turns it into a notification.
 */
data class PushMessage(
    val kind: Kind,
    val title: String,
    val body: String,
    val tag: String,
    /** When the message was sent, for the notification's timestamp; null when unreadable. */
    val sentAt: Long?,
    /** The server's buffer row id, which survives a rename (see [ReadMarkers]). */
    val bufferId: Int?,
    /**
     * The keys a tap needs, copied onto the tap's intent as string extras. The same keys a
     * system-drawn FCM notification puts on its launch intent, so `MainActivity` reads either.
     */
    val tap: Map<String, String>,
) {
    /**
     * The server's `kind`, each with its own notification channel, so the user can silence one kind
     * (say, a busy always-notify channel) in system settings without losing DMs.
     */
    enum class Kind(val wire: String?, val channelId: String, val channelName: String, val urgent: Boolean) {
        DM("dm", "dm", "Direct messages", urgent = true),
        HIGHLIGHT("highlight", "highlight", "Mentions", urgent = true),
        KICKED("kicked", "kicked", "Kicks", urgent = true),
        ALWAYS_NOTIFY("always_notify", "always_notify", "Channel activity", urgent = false),
        FRIEND_ONLINE("friend_online", "friend_online", "Friends online", urgent = false),

        /** A kind a newer server sends that this build doesn't know. Still shown: the server decided it was worth a push. */
        OTHER(null, "other", "Other notifications", urgent = false),
        ;

        companion object {
            fun of(wire: String?): Kind = entries.firstOrNull { it.wire != null && it.wire == wire } ?: OTHER
        }
    }

    /** Whether [state] says the message this is about has been read (see [ReadMarkers]). */
    fun readIn(state: ChatState): Boolean {
        val networkId = tap["networkId"]?.toIntOrNull() ?: return false
        val target = tap["target"] ?: return false
        val messageId = tap["messageId"]?.toLongOrNull() ?: return false
        return ReadMarkers.readPast(state, networkId, target, messageId, bufferId)
    }

    companion object {
        /** The keys `NotificationTap.parse` reads. */
        val TAP_KEYS = listOf("networkId", "target", "messageId")

        /**
         * Null when there is nothing to show: no `title` means a server older than lurker#1046, which
         * sent a `notification` block the system draws itself while the app is in the background —
         * the only time this is asked.
         */
        fun parse(data: Map<String, String>): PushMessage? {
            val title = data["title"]?.takeIf { it.isNotEmpty() } ?: return null
            val tag = data["tag"]?.takeIf { it.isNotEmpty() } ?: return null
            return PushMessage(
                kind = Kind.of(data["kind"]),
                title = title,
                body = data["body"].orEmpty(),
                tag = tag,
                sentAt = data["time"]?.let(::epochMillis),
                bufferId = data["bufferId"]?.toIntOrNull(),
                tap = TAP_KEYS.mapNotNull { key -> data[key]?.let { key to it } }.toMap(),
            )
        }

        private fun epochMillis(iso: String): Long? =
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
