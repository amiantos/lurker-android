// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.amiantos.lurkerkit.support.removingPercentEncoding
import net.amiantos.lurkerkit.support.utf8OrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Sign-in through the server's OAuth approval page, the one way this app gets a token
 * (lurker docs/OAUTH.md). The app registers itself with a server once, opens
 * `/oauth/authorize` in a browser sheet where the member signs in and approves, and trades
 * the code the page sends back for a token that lasts until it's revoked. lurker.chat
 * answers at the same paths as a self-hosted server, so nothing here branches on which.
 *
 * Requests and replies only, so they're tested without a server. `LurkerClient` sends them,
 * and `ChatViewModel.signIn` runs the steps in order.
 *
 * Port note: a request is an `okhttp3.Request`, and an address an `HttpUrl` — so, as in
 * `SearchRequest`, a server that is not http(s) (or is empty, or has no scheme) makes no
 * request here where `URL(string:)` would make one there. Nothing signs in with one
 * (`ServerAddress`).
 */
object OAuth {
    /**
     * The redirect's scheme: lurker.chat reversed (RFC 8252 §7.1). The approval page shows
     * it ("Opens chat.lurker:"), so it names the product rather than a bundle ID.
     */
    const val callbackScheme = "chat.lurker"
    const val redirectURI = "$callbackScheme:/oauth"

    /** The approval page shows this address's host as where the app says it's from. */
    internal const val clientURI = "https://lurker.chat"

    // MARK: - Register

    internal sealed interface Registration {
        data class Registered(val clientId: String) : Registration
        data class Failure(val message: String) : Registration
    }

    /** `POST /api/oauth/register` (RFC 7591). */
    internal fun registrationRequest(server: String, clientName: String): Request? {
        val url = (server + "/api/oauth/register").toHttpUrlOrNull() ?: return null
        return jsonPost(
            url,
            buildJsonObject {
                put("client_name", clientName)
                put("client_uri", clientURI)
                putJsonArray("redirect_uris") { add(JsonPrimitive(redirectURI)) }
            },
        )
    }

    internal fun registration(status: Int, data: ByteString): Registration {
        if (status in 200..<300) {
            val id = json(data)?.get("client_id").asString()
            if (id != null && id.isNotEmpty()) return Registration.Registered(clientId = id)
        }
        return when (status) {
            404 ->
                Registration.Failure("This server doesn't support app sign-in. It needs Lurker 2.3.0 or newer.")
            429 ->
                // Both of the server's 429s: this address registered too often, or too many
                // registrations are waiting for approval.
                Registration.Failure("Too many sign-in attempts right now. Try again in a few minutes.")
            else ->
                Registration.Failure("Sign-in failed (HTTP $status).")
        }
    }

    // MARK: - Authorize

    /**
     * The approval page for one attempt. Values are escaped down to RFC 3986's unreserved
     * characters: `URLComponents` leaves `+` alone, and the server reads it as a space.
     */
    internal fun authorizeURL(server: String, clientId: String, challenge: String, state: String): HttpUrl? {
        val query = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectURI,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
            .joinToString(separator = "&") { (name, value) -> "$name=${escape(value)}" }
        return (server + "/oauth/authorize?" + query).toHttpUrlOrNull()
    }

    internal sealed interface Callback {
        data class Code(val code: String) : Callback

        /** The member chose Deny. */
        data object Denied : Callback

        /** Not an answer to this attempt: another attempt's `state`, or no code. */
        data object Invalid : Callback
    }

    /**
     * Where the approval page sent the browser. `access_denied` is the only error it ever
     * sends; anything else that goes wrong is shown on the page itself.
     *
     * Port note: `url` is the redirect as a `String` — a `chat.lurker:` address, which `HttpUrl`
     * cannot hold. Its query is read by hand to `URLComponents.queryItems`' rule: `&`-separated
     * items, each split at its first `=`, percent-decoded, and a `+` left as a `+`. (`HttpUrl`'s
     * own reader would make a `+` a space.)
     */
    internal fun callback(url: String, state: String): Callback {
        val items = queryItems(url)
        fun value(name: String): String? = items.firstOrNull { it.first == name }?.second
        if (value("state") != state) return Callback.Invalid
        if (value("error") == "access_denied") return Callback.Denied
        val code = value("code")
        if (code == null || code.isEmpty()) return Callback.Invalid
        return Callback.Code(code)
    }

    // MARK: - Exchange

    internal sealed interface TokenGrant {
        data class Token(val token: String) : TokenGrant

        /** The server doesn't know the `client_id`, so the app has to register again. */
        data object UnknownClient : TokenGrant
        data class Failure(val message: String) : TokenGrant
    }

    /** `POST /api/oauth/token`, as JSON, which the server takes as well as a form. */
    internal fun tokenRequest(server: String, clientId: String, code: String, verifier: String): Request? {
        val url = (server + "/api/oauth/token").toHttpUrlOrNull() ?: return null
        return jsonPost(
            url,
            buildJsonObject {
                put("grant_type", "authorization_code")
                put("client_id", clientId)
                put("code", code)
                put("redirect_uri", redirectURI)
                put("code_verifier", verifier)
            },
        )
    }

    internal fun tokenGrant(status: Int, data: ByteString): TokenGrant {
        val body = json(data)
        if (status in 200..<300) {
            val token = body?.get("access_token").asString()
            if (token != null && token.isNotEmpty()) return TokenGrant.Token(token)
        }
        // A 401 from this endpoint refuses the client, never the member.
        if (status == 401 && body?.get("error").asString() == "invalid_client") return TokenGrant.UnknownClient
        // `invalid_grant` spends the code, so the only way on is a new attempt.
        if (status == 400) return TokenGrant.Failure("Sign-in didn't finish. Try again.")
        return TokenGrant.Failure("Sign-in failed (HTTP $status).")
    }

    // MARK: - Check a saved registration

    /**
     * `POST /api/oauth/revoke` with a token that can't exist. RFC 7009 has the server answer
     * 200 for any client it knows, token or not, and `invalid_client` for one it doesn't, so
     * this is the one unauthenticated way to ask whether a `client_id` still exists. It revokes
     * nothing: the token is fresh random bytes, with no `<cell>~` prefix to route on.
     */
    internal fun clientCheckRequest(server: String, clientId: String): Request? {
        val url = (server + "/api/oauth/revoke").toHttpUrlOrNull() ?: return null
        return jsonPost(
            url,
            buildJsonObject {
                put("client_id", clientId)
                put("token", randomString())
            },
        )
    }

    /**
     * Whether the server still knows the client. A 404 counts as no: the server has no OAuth
     * routes (older than 2.3.0, or downgraded), and registering again is what says so. Null when
     * the answer says neither (a network failure, a 429, a 5xx), which is no reason to throw a
     * registration away.
     */
    internal fun clientKnown(status: Int, data: ByteString): Boolean? {
        if (status in 200..<300) return true
        if (status == 404) return false
        if (status == 401 && json(data)?.get("error").asString() == "invalid_client") return false
        return null
    }

    // MARK: - Helpers

    /**
     * 32 random bytes as base64url: 43 characters, fit for a PKCE verifier or a `state`.
     * Drawn from `SecureRandom`, the platform's CSPRNG (on iOS, `UInt8.random` draws from the
     * system generator, which is one on Apple platforms).
     */
    internal fun randomString(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64URL(bytes.toByteString())
    }

    private val random = SecureRandom()

    /**
     * Port note: written out as LurkerKit writes it — standard base64, then `+`→`-`, `/`→`_`,
     * and the padding dropped. Okio's `base64Url()` keeps the padding.
     */
    internal fun base64URL(data: ByteString): String =
        data.base64()
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")

    private const val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /**
     * `addingPercentEncoding(withAllowedCharacters:)`: every UTF-8 byte outside the set as
     * `%XX`, uppercase hex.
     *
     * Port note: Foundation answers nil for a string holding a lone surrogate, and the Swift then
     * sends the value unescaped; here the UTF-8 encoder writes a `?` for it, escaped as `%3F`.
     */
    private fun escape(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val unit = byte.toInt() and 0xFF
            if (unit < 0x80 && unreserved.indexOf(unit.toChar()) >= 0) {
                out.append(unit.toChar())
            } else {
                out.append('%').append(HEX[unit shr 4]).append(HEX[unit and 0x0F])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    /**
     * Port note: the body is written as bytes with the media type exactly `application/json`;
     * OkHttp's `String.toRequestBody` would append `; charset=utf-8` to it. Encoding a
     * `JsonObject` cannot fail, so LurkerKit's nil for an unencodable body has nothing to
     * answer here.
     */
    private fun jsonPost(url: HttpUrl, body: JsonObject): Request {
        val payload = Json.encodeToString(JsonObject.serializer(), body).encodeToByteArray()
        return Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Port note: a body that is not UTF-8 is no object here; `JSONSerialization` would also try
     * UTF-16 and UTF-32. The JSON is read by `FrameParser`, as every body is (PORTING.md, JSON).
     */
    private fun json(data: ByteString): JsonObject? = data.utf8OrNull()?.let { FrameParser.jsonObject(it) }

    /**
     * `URLComponents(url:resolvingAgainstBaseURL:)?.queryItems`, by hand: the query is what lies
     * between the first `?` and the fragment, and a `?` inside the fragment opens nothing. Empty
     * when there is none.
     */
    private fun queryItems(url: String): List<Pair<String, String?>> {
        val end = url.indexOf('#').let { if (it < 0) url.length else it }
        val start = url.indexOf('?')
        if (start < 0 || start > end) return emptyList()
        val query = url.substring(start + 1, end)
        return query.split("&").map { item ->
            val equals = item.indexOf('=')
            if (equals < 0) {
                (removingPercentEncoding(item) ?: item) to null
            } else {
                (removingPercentEncoding(item.substring(0, equals)) ?: item.substring(0, equals)) to
                    removingPercentEncoding(item.substring(equals + 1))
            }
        }
    }
}

/**
 * One attempt's PKCE pair (RFC 7636, S256). The verifier stays on the device until the
 * exchange, so a code intercepted on its way back to the app is useless on its own.
 */
internal data class PKCE(val verifier: String = OAuth.randomString()) {
    val challenge: String = OAuth.base64URL(
        MessageDigest.getInstance("SHA-256").digest(verifier.encodeUtf8().toByteArray()).toByteString(),
    )
}
