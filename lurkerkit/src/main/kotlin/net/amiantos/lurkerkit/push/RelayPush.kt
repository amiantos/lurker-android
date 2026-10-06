// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What `GET /api/push/config` says (lurker#490, lurker-dev/RELAY_PLAN.md §5a): the server's VAPID
 * public key, the transports it can deliver on, and — only while its admin has opted in —
 * push.lurker.chat's origin.
 */
data class PushConfig(val publicKey: String?, val transports: List<String>, val relay: String?)

/** How this install gets push from a given server. */
sealed interface PushRoute {
    /** The server holds our FCM key (the hosted service): register the token directly. */
    data object Native : PushRoute

    /** Through push.lurker.chat: register [endpoint] as a Web Push subscription. */
    data class Relay(val origin: String, val publicKey: String) : PushRoute {
        /** RELAY_PLAN.md §6.2: the FCM token as-is, then the server's key verbatim. */
        fun endpoint(fcmToken: String): String = "$origin/relay-to/fcm/$fcmToken/$publicKey"
    }

    /** Neither: the server's admin hasn't turned on push for the apps (no `relay`). */
    data object None : PushRoute

    /**
     * The server names a relay this app won't use: not push.lurker.chat (in a release build), or
     * without the key the endpoint needs. No push either, but not for the reason [None] gives.
     */
    data object Unsupported : PushRoute
}

/** Why this server can't push to the app, for Settings to say. */
enum class AppPushUnavailable {
    /** No FCM key, and the admin hasn't turned on push.lurker.chat. */
    NotTurnedOn,

    /** The server advertises a relay this app can't use (see [PushRoute.Unsupported]). */
    RelayUnsupported,
}

object RelayPush {
    /** The only relay a release build talks to. */
    const val OFFICIAL_RELAY = "https://push.lurker.chat"

    /**
     * The route for [config]. The relay only when the server can't deliver FCM itself AND names a
     * relay (the admin opted in) AND that relay is one we trust: push.lurker.chat, or — in a debug
     * build, for developing the relay against a local server (`LURKER_PUSH_RELAY_URL`) — any https
     * origin. A server can't point a release build at an arbitrary URL with this phone's token.
     */
    fun route(config: PushConfig, allowAnyHttpsRelay: Boolean): PushRoute {
        if ("fcm" in config.transports) return PushRoute.Native
        val relay = config.relay ?: return PushRoute.None
        val origin = allowedOrigin(relay, allowAnyHttpsRelay) ?: return PushRoute.Unsupported
        val key = config.publicKey?.takeIf { it.isNotBlank() } ?: return PushRoute.Unsupported
        return PushRoute.Relay(origin, key)
    }

    private fun allowedOrigin(relay: String, allowAnyHttps: Boolean): String? {
        val url = relay.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        // An origin, nothing more: a path, query or credentials means it isn't the relay's origin.
        if (url.encodedPath != "/" || url.query != null || url.username.isNotEmpty()) return null
        // Normalized: host case-folded by HttpUrl, the default port dropped, so
        // `https://PUSH.lurker.chat:443/` is push.lurker.chat.
        // HttpUrl gives an IPv6 host without its brackets; an origin needs them back.
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val origin = if (url.port == 443) "https://$host" else "https://$host:${url.port}"
        return if (allowAnyHttps || origin == OFFICIAL_RELAY) origin else null
    }

    /**
     * A decrypted relay push as the string map a direct FCM push arrives with, so `PushMessage.parse`
     * reads both. The server builds that map from the same body (`fcmSender.buildFcmMessage`): every
     * value through JavaScript's `String()`, null left out — and a number or boolean prints as its
     * JSON text, the way `String()` would.
     *
     * The body is flat (`pushBody()`), so only top-level scalars are taken; an array or object is
     * dropped rather than walked. Null if [plaintext] isn't a JSON object, or can't be parsed at all —
     * including nesting deep enough to exhaust the stack, which is caught here, not left to escape
     * the FCM callback.
     */
    fun flatten(plaintext: String): Map<String, String>? {
        val body = try {
            Json.parseToJsonElement(plaintext) as? JsonObject
        } catch (_: Throwable) {
            null
        } ?: return null
        val data = LinkedHashMap<String, String>()
        for ((key, value) in body) {
            if (value is JsonPrimitive && value !is JsonNull) data[key] = value.content
        }
        return data
    }
}
