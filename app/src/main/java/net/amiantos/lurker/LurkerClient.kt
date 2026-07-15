// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Prototype client for Lurker's WS + REST contract. Proves the bearer-auth work
 * end to end from a real Android app against BOTH backends:
 *   - self-hosted: mint a token at the cell (`POST /api/auth/login/token`,
 *     lurker#489 / PR #570),
 *   - hosted lurker.chat: mint at the control plane (`POST /_cp/auth/app/login`,
 *     CP#58) — the CP verifies the account and hands back the same signed claim,
 *     which its reverse proxy accepts as a Bearer and routes to the account's
 *     cell. Everything after login is byte-identical: the same Bearer opens the
 *     WebSocket and authenticates REST, because the proxy resolves it and injects
 *     the real cell session transparently.
 *
 * Deliberately NOT the shape a real app should take — no ViewModel, no local
 * store, no reconnect/resume, no `?since=` gap handling. State lives in Compose
 * snapshot state and dies with the process. It exists to answer one question:
 * does the contract work from a native client?
 */
data class Buffer(
    /** null for the app-scoped system buffer, which is read-only. */
    val networkId: Int?,
    val target: String,
    val networkName: String,
) {
    val key: String get() = "${networkId ?: "sys"}::$target"
}

data class Msg(
    val id: Long,
    val type: String,
    val nick: String,
    val text: String,
    val self: Boolean,
)

// The event types worth rendering in a prototype. The server sends far more
// (join/part/quit/mode/names/typing/presence/...); a real client renders those
// as inline system lines, but they'd just be noise here.
private val RENDERABLE = setOf("message", "action", "notice", "error")

class LurkerClient {
    var status by mutableStateOf<String?>(null)
        private set
    var loggedIn by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set

    val buffers = mutableStateListOf<Buffer>()

    /** bufferKey -> messages. Replaced wholesale on backlog, appended on live events. */
    val messagesByBuffer = mutableStateMapOf<String, List<Msg>>()

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // a WS read never "times out"
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var token: String? = null
    private var baseUrl: String = ""
    private val networkNames = mutableMapOf<Int, String>()

    private fun post(block: () -> Unit) = main.post(block)

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * Mint a session token from a password.
     *
     * - hosted=false → `POST /api/auth/login/token` on the cell (PR #570):
     *   `{username, password}` in, `{token}` out.
     * - hosted=true → `POST /_cp/auth/app/login` on the control plane (CP#58):
     *   `{email, password}` in, `{token}` out. The token is a CP claim the proxy
     *   accepts as a Bearer; from here on the flow is identical to self-hosted.
     *
     * Blocking; call from a background thread.
     */
    fun login(rawBase: String, username: String, password: String, hosted: Boolean = false) {
        val base = rawBase.trim().trimEnd('/')
        baseUrl = base
        post { status = "Signing in…" }
        try {
            val loginPath = if (hosted) "/_cp/auth/app/login" else "/api/auth/login/token"
            val body = JSONObject()
                .put(if (hosted) "email" else "username", username)
                .put("password", password)
                .toString()
                .toRequestBody(json)
            val req = Request.Builder().url("$base$loginPath").post(body).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    post { status = "Sign-in failed (HTTP ${res.code})" }
                    return
                }
                val obj = JSONObject(res.body?.string().orEmpty())
                token = obj.getString("token")
            }
            fetchNetworkNames()
            post {
                status = null
                loggedIn = true
            }
            openSocket()
        } catch (e: Exception) {
            post { status = "Sign-in failed: ${e.message}" }
        }
    }

    /**
     * The WS snapshot identifies networks by id only — names live on the REST
     * side. Doubles as proof that the same bearer authenticates plain REST calls,
     * not just the socket.
     */
    private fun fetchNetworkNames() {
        val req = Request.Builder()
            .url("$baseUrl/api/networks")
            .header("Authorization", "Bearer ${token!!}")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return
            val arr = JSONObject(res.body?.string().orEmpty()).getJSONArray("networks")
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                networkNames[n.getInt("id")] = n.optString("name", "network")
            }
        }
    }

    /**
     * The whole point: a native client CAN set headers on the upgrade, so the
     * session token rides as a bearer where a browser would have to use a cookie.
     */
    private fun openSocket() {
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/ws"
        val req = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer ${token!!}")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                post { connected = true; status = null }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return
                }
                post { handleFrame(frame) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A refused upgrade surfaces here with the HTTP response attached —
                // a 401 means the bearer never resolved to a session.
                val code = response?.code
                post {
                    connected = false
                    status = if (code != null) "WebSocket refused (HTTP $code)" else "WebSocket error: ${t.message}"
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post { connected = false }
            }
        })
    }

    private fun handleFrame(frame: JSONObject) {
        when (frame.optString("kind")) {
            // One backlog frame per buffer arrives on connect. Channel/DM buffers
            // come as SHELLS (events: []) — the server doesn't read their history
            // until the client actually opens one. So this populates the buffer
            // list, and open() is what fills a buffer in.
            "backlog" -> {
                val networkId = if (frame.isNull("networkId")) null else frame.getInt("networkId")
                val target = frame.optString("target")
                if (target.isEmpty()) return
                val buffer = Buffer(
                    networkId = networkId,
                    target = target,
                    networkName = networkId?.let { networkNames[it] } ?: "system",
                )
                if (buffers.none { it.key == buffer.key }) buffers.add(buffer)
                messagesByBuffer[buffer.key] = parseEvents(frame.optJSONArray("events"))
            }

            // Live traffic — including the echo of our OWN sends (self=true), which
            // is why this prototype needs no optimistic-bubble bookkeeping.
            "irc" -> {
                val networkId = if (frame.isNull("networkId")) null else frame.getInt("networkId")
                val target = frame.optString("target")
                if (target.isEmpty()) return
                val key = "${networkId ?: "sys"}::$target"
                val msg = parseEvent(frame) ?: return
                messagesByBuffer[key] = (messagesByBuffer[key] ?: emptyList()) + msg
            }

            "error" -> status = frame.optString("text")
        }
    }

    private fun parseEvents(arr: JSONArray?): List<Msg> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Msg>()
        for (i in 0 until arr.length()) {
            parseEvent(arr.getJSONObject(i))?.let(out::add)
        }
        return out
    }

    private fun parseEvent(e: JSONObject): Msg? {
        val type = e.optString("type")
        if (type !in RENDERABLE) return null
        return Msg(
            id = e.optLong("id"),
            type = type,
            nick = e.optString("nick", "*"),
            text = e.optString("text"),
            self = e.optBoolean("self", false),
        )
    }

    /**
     * Ask the server to hydrate a buffer. Shells arrive empty, so without this a
     * tapped channel would render blank. The server replies with a real `backlog`
     * frame, which handleFrame swaps in over the shell.
     */
    fun open(buffer: Buffer) {
        val networkId = buffer.networkId ?: return // system buffer is already full
        ws?.send(
            JSONObject()
                .put("type", "open-buffer")
                .put("networkId", networkId)
                .put("target", buffer.target)
                .toString(),
        )
    }

    fun send(buffer: Buffer, text: String) {
        val networkId = buffer.networkId ?: return
        ws?.send(
            JSONObject()
                .put("type", "send")
                .put("networkId", networkId)
                .put("target", buffer.target)
                .put("text", text)
                .toString(),
        )
    }
}
