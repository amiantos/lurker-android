// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.rendering.IRCFormatting
import net.amiantos.lurkerkit.store.ChatState
import java.time.Instant
import java.util.UUID

/**
 * An in-app notification: a live line the server says to alert about, while the app is open.
 * The foreground half of the same intent push serves in the background — the server holds push
 * back while a client is visible, so without this an open app says nothing at all.
 *
 * A port of the web's `useHighlightNotifier` decision: trust the server's `notify` verdict, pick
 * the kind (kicked > DM > highlight > always-notify), and gate on that kind's
 * `notifications.<kind>.enabled` master toggle.
 *
 * Port note: LurkerKit compares two notifications by `id` alone, a `UUID` minted per instance. The
 * data class compares every field, `id` included, which comes to the same thing: two instances are
 * never equal, and an instance is equal to itself.
 */
data class StatusNotification(
    val kind: Kind,
    val key: BufferKey,
    val nick: String?,
    /** Plain text, formatting stripped — a glance, not the message view. */
    val text: String,
    val messageId: Long,
    val date: Instant,
    val id: UUID = UUID.randomUUID(),
) {
    enum class Kind(val rawValue: String) {
        Kicked("kicked"),
        Dm("dm"),
        Highlight("highlight"),
        AlwaysNotify("always_notify"),

        /** A friend — the peer of a favorited DM — came online. No line behind it. */
        FriendOnline("friend_online");

        companion object {
            fun fromRawValue(raw: String): Kind? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /**
     * Which bundled sound this kind plays, or null when its sound is off. The web's
     * `notifications.<kind>.sound.enabled` and `.choice`, with the registry's defaults for the
     * moment before bootstrap — always-notify and kick sound by default, the rest don't, and each
     * kind has its own default sound so they can be told apart by ear. A volume of 0 is silence.
     */
    fun sound(settings: Settings): String? = sound(kind, settings)

    companion object {
        /**
         * The notification for [message], or null when it isn't one: not flagged, our own line, or
         * its kind switched off.
         */
        fun make(
            networkId: Int?,
            target: String,
            message: Message,
            settings: Settings,
            now: Instant = Instant.now(),
        ): StatusNotification? {
            if (!message.notify || message.isSelf || networkId == null) return null
            val kind = when {
                message.selfKicked -> Kind.Kicked
                message.dm -> Kind.Dm
                message.matched -> Kind.Highlight
                message.notifyAlways -> Kind.AlwaysNotify
                else -> return null
            }
            // Every kind defaults on in the registry.
            if (!settings.bool("notifications.${kind.rawValue}.enabled", default = true)) return null
            return StatusNotification(
                kind = kind,
                key = BufferKey(networkId = networkId, target = target),
                nick = message.nick,
                text = IRCFormatting.strip(message.text ?: ""),
                messageId = message.id,
                date = message.date ?: now,
            )
        }

        /**
         * A friend coming online, from a `peer-presence` frame read against the state BEFORE it
         * applies (the web's came-online toast). Only a witnessed offline→online flip counts: the
         * server also states a peer's current presence when MONITOR is first seeded and whenever a
         * nick is added to the watch, and an `online` there is not someone arriving.
         */
        internal fun cameOnline(frame: ServerFrame, before: ChatState, now: Instant = Instant.now()): StatusNotification? {
            if (frame !is ServerFrame.PeerPresence || frame.state != PresenceState.Online) return null
            if (before.peerPresence[frame.networkId]?.get(frame.nick.lowercase()) != PresenceState.Offline) return null
            val key = BufferKey(networkId = frame.networkId, target = frame.nick)
            if (!before.isFavorite(key) ||
                !before.settings.bool("notifications.${Kind.FriendOnline.rawValue}.enabled", default = true)
            ) {
                return null
            }
            return StatusNotification(kind = Kind.FriendOnline, key = key, nick = frame.nick, text = "", messageId = 0, date = now)
        }

        /**
         * [sound] for a kind — what the settings screen shows as the sound in force, so the row
         * reads exactly what will play.
         */
        fun sound(kind: Kind, settings: Settings): String? {
            val (enabled, choice) = when (kind) {
                Kind.Highlight -> false to "ping"
                Kind.Dm -> false to "chime"
                Kind.FriendOnline -> false to "knock"
                Kind.AlwaysNotify -> true to "plink"
                Kind.Kicked -> true to "beep"
            }
            val prefix = "notifications.${kind.rawValue}.sound"
            if (!settings.bool("$prefix.enabled", default = enabled) || settings.int("$prefix.volume", default = 60) <= 0) {
                return null
            }
            val picked = settings.string("$prefix.choice", default = choice)
            return if (picked in sounds) picked else choice
        }

        /** The bundled sounds, the registry's `sound.choice` enum. */
        val sounds: Set<String> = setOf("ping", "chime", "pop", "beep", "knock", "plink")
    }
}
