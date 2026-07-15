// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.client

import net.amiantos.lurker.model.Backend
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The one client that owns Lurker's REST + WebSocket contract. Self-hosted and
 * hosted differ only by [Backend] (base URL + where the token is minted); there
 * is deliberately no transport-adapter seam (#3).
 *
 * I/O only: it parses server bytes into [ServerFrame]s and hands them to
 * [onFrame], and exposes verbs to send. It holds NO domain state — that lives in
 * the store. Callbacks arrive on OkHttp threads; the store is thread-safe, so
 * nothing here marshals to the main thread.
 *
 * Not yet here (later foundation issues): persisted tokens and 401 handling (#4),
 * reconnect/`?since=` resume/background-foreground (#5), history pagination (#7).
 */
class LurkerClient(private val onFrame: (ServerFrame) -> Unit) {

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // a WS read never "times out"
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private var baseUrl: String = ""
    private var token: String? = null
    private var socket: WebSocket? = null

    sealed interface LoginResult {
        data class Success(val token: String) : LoginResult
        data class Failure(val message: String) : LoginResult
    }

    /**
     * Exchange a password for a session token against [backend]. Blocking — call
     * off the main thread.
     */
    fun login(backend: Backend, server: String, identifier: String, password: String): LoginResult {
        baseUrl = server.trim().trimEnd('/')
        val body = JSONObject()
            .put(backend.identifierField, identifier)
            .put("password", password)
            .toString()
            .toRequestBody(jsonType)
        val req = Request.Builder().url("$baseUrl${backend.loginPath}").post(body).build()
        return try {
            http.newCall(req).execute().use { res ->
                when {
                    res.code == 401 ->
                        // Bad credentials — OR a passkey-only account, since the mint
                        // endpoint is password-only and can't tell the two apart. Name
                        // the caveat rather than flatly claiming "wrong password".
                        LoginResult.Failure(
                            "Sign-in failed — check your password. Passkey-only accounts " +
                                "can't sign in from the app yet (login is password-only).",
                        )
                    !res.isSuccessful -> LoginResult.Failure("Sign-in failed (HTTP ${res.code})")
                    else -> {
                        val minted = JSONObject(res.body?.string().orEmpty()).optString("token").ifEmpty { null }
                        if (minted == null) {
                            LoginResult.Failure("Sign-in failed: no token in response")
                        } else {
                            token = minted
                            LoginResult.Success(minted)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LoginResult.Failure("Sign-in failed: ${e.message}")
        }
    }

    /**
     * Re-arm from a persisted session (no password round-trip). Follow with [start].
     * If the token is stale, [start]'s first authenticated call surfaces a 401 as
     * [ServerFrame.Unauthorized].
     */
    fun restore(server: String, sessionToken: String) {
        baseUrl = server.trim().trimEnd('/')
        token = sessionToken
    }

    /** After login/restore: fetch the network roster, then open the socket. Blocking. */
    fun start() {
        // If the roster fetch already saw a 401, the token is dead — don't bother
        // attempting the WS upgrade with it (it would just 401 again).
        if (fetchNetworks()) openSocket()
    }

    /**
     * Reopen the socket after a drop, resuming from [sinceId] so the server ships
     * only the gap (as `reset`/append backlog slices) rather than the whole world.
     * Skips the roster re-fetch — names don't change, and the reconnect snapshot
     * re-sends live network state anyway.
     */
    fun reconnect(sinceId: Long) {
        openSocket(sinceId)
    }

    /** Returns false only when the token was rejected (401); true otherwise, including
     *  transient errors where the socket is still worth trying. */
    private fun fetchNetworks(): Boolean {
        val t = token ?: return true
        val req = Request.Builder()
            .url("$baseUrl/api/networks")
            .header("Authorization", "Bearer $t")
            .build()
        return try {
            http.newCall(req).execute().use { res ->
                when {
                    // A revoked/expired token trips here first (before the WS upgrade).
                    res.code == 401 -> {
                        onFrame(ServerFrame.Unauthorized)
                        false
                    }
                    else -> {
                        if (res.isSuccessful) {
                            onFrame(FrameParser.parseNetworks(res.body?.string().orEmpty()))
                        }
                        true
                    }
                }
            }
        } catch (_: Exception) {
            true // a network hiccup, not an auth failure — let the socket try
        }
    }

    /**
     * A native client CAN set headers on the WS upgrade, so the session token
     * rides as a bearer where a browser would need a cookie.
     */
    private fun openSocket(sinceId: Long = 0) {
        val t = token ?: return
        // Replace any prior socket so a reconnect can't leave two live.
        socket?.close(1000, null)
        val since = if (sinceId > 0) "?since=$sinceId" else ""
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/ws" + since
        val req = Request.Builder().url(wsUrl).header("Authorization", "Bearer $t").build()
        socket = http.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) =
                    onFrame(ServerFrame.SocketOpen)

                override fun onMessage(webSocket: WebSocket, text: String) =
                    onFrame(FrameParser.parseWs(text))

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    // A refused upgrade with 401 means the bearer never resolved — the
                    // session is gone, not merely a dropped connection.
                    if (response?.code == 401) {
                        onFrame(ServerFrame.Unauthorized)
                    } else {
                        onFrame(ServerFrame.SocketClosed(t.message, response?.code))
                    }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    onFrame(ServerFrame.SocketClosed(reason, code))
            },
        )
    }

    // --- verbs ------------------------------------------------------------------

    /**
     * Ask the server to hydrate a buffer. Channels/DMs arrive as shells, so without
     * this a tapped buffer renders blank. No-op for the system buffer (already full).
     */
    fun openBuffer(networkId: Int?, target: String) {
        networkId ?: return
        send(JSONObject().put("type", "open-buffer").put("networkId", networkId).put("target", target))
    }

    fun sendMessage(networkId: Int?, target: String, text: String) {
        networkId ?: return
        send(
            JSONObject()
                .put("type", "send")
                .put("networkId", networkId)
                .put("target", target)
                .put("text", text),
        )
    }

    private fun send(verb: JSONObject) {
        socket?.send(verb.toString())
    }

    /**
     * Drop the socket and forget the token without revoking server-side. For
     * teardown (ViewModel cleared) or a dead token (expired/revoked) — [logout] is
     * the deliberate sign-out that also revokes.
     */
    fun close() {
        socket?.close(1000, null)
        socket = null
        token = null
    }

    /**
     * Revoke server-side, then drop the socket. Blocking. On hosted the token is a
     * stateless CP claim, so this revokes the cell session but not the claim itself
     * (a CP#58 tradeoff) — full sign-out semantics are #4.
     */
    fun logout() {
        val t = token
        socket?.close(1000, null)
        socket = null
        if (t != null) {
            val req = Request.Builder()
                .url("$baseUrl/api/auth/logout")
                .header("Authorization", "Bearer $t")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            runCatching { http.newCall(req).execute().close() }
        }
        token = null
    }
}
