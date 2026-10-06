// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import java.io.File
import java.io.IOException
import java.net.UnknownServiceException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.CertificateExport
import net.amiantos.lurkerkit.model.CertificateResult
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.ClientCertificatePEM
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.model.HistoryCountBy
import net.amiantos.lurkerkit.model.ISOTime
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.LocalNetworking
import net.amiantos.lurkerkit.model.MediaFetch
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkPresets
import net.amiantos.lurkerkit.model.NetworkSaveResult
import net.amiantos.lurkerkit.model.OutgoingModeChange
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.SearchQuery
import net.amiantos.lurkerkit.model.SearchRequest
import net.amiantos.lurkerkit.model.ServerAddress
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.TypingSignal
import net.amiantos.lurkerkit.model.UploadsFilter
import net.amiantos.lurkerkit.model.UploadsPage
import net.amiantos.lurkerkit.model.UploadsRequest
import net.amiantos.lurkerkit.session.PersistedSession
import net.amiantos.lurkerkit.support.percentEncodedQuery
import net.amiantos.lurkerkit.support.task
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.unicodeRegex
import net.amiantos.lurkerkit.support.utf8OrNull
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.buffer
import okio.sink

/**
 * The one client that owns Lurker's REST + WebSocket contract. Self-hosted and hosted
 * differ only by base URL; there is deliberately no transport-adapter seam.
 *
 * Confined to the main thread: reconnect (lurker-ios#4) means the socket is opened, replaced,
 * and torn down from several places, so confining all of that state to the main thread makes
 * it race-free without locks. Network I/O still runs off the main thread — OkHttp's calls
 * suspend rather than block, and the socket's callbacks hop back to main. The client parses
 * server bytes into `ServerFrame`s and hands them to `onFrame`; it holds no domain state (that
 * lives in the store).
 *
 * Port note — the platform seams:
 *  - `URLSession` is an `OkHttpClient`, [bearerOnlyConfiguration] by default; every REST call
 *    goes through one suspending [await] over `enqueue`, which cancels the call when the
 *    caller is cancelled. A transport failure is an `IOException`, caught wherever LurkerKit
 *    catches its `URLError`; cancellation is not caught, so a cancelled caller ends rather than
 *    reading the "no answer" LurkerKit's `catch` returns.
 *  - `Task { … }` is a launch in [scope] (see `task`, which keeps a Swift `Task`'s promise
 *    that none of its body runs before the statement that started it returns). The app passes
 *    a scope on `Dispatchers.Main.immediate`; tests pass a `TestScope`. LurkerKit's
 *    `[weak self]`s are dropped: a launched coroutine holds the client strongly, and what ends
 *    its work is [scope] — cancelled, it runs nothing more, socket callbacks included.
 *  - `URLSessionWebSocketTask` is an `okhttp3.WebSocket`, read through a listener whose
 *    callbacks arrive on OkHttp's thread and hop to main through [scope] — see `SocketListener`.
 */
internal class LurkerClient(
    private val scope: CoroutineScope,
    private val onFrame: (ServerFrame) -> Unit,
    httpClient: OkHttpClient = bearerOnlyConfiguration(),
    /**
     * Where preview bytes are cached on disk, or null for no disk cache. A platform fact (on
     * Android, under the app's cache directory), so the app says where.
     */
    mediaCacheDirectory: File? = null,
    /**
     * Where the disk work that must not run on the caller's thread goes — the upload's multipart
     * copy. Port-only: a parameter so a test can see the hop; the app takes the default.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val session: OkHttpClient = httpClient

    /** Preview bytes only, so its cache can be purged on sign-out without touching API state. */
    private val mediaSession: OkHttpClient
    private var baseURL = ""
    private var token: String? = null
    private var socket: WebSocket? = null

    /**
     * The socket has ended, and the reconnect hasn't replaced it yet. ⚠⚠ The socket is kept —
     * `dropSocket` and the `task !== socket` guards still need it — but nothing can be written to
     * it, so `send` answers false. Without this, every write made while "Reconnecting…" showed
     * reported true and went nowhere, which is the window the callers' Boolean exists for.
     */
    private var socketEnded = false

    /**
     * Which socket this is, counting from the first — so a caller can tie something it learned
     * from a frame to the socket that sent it. Bumped the moment a socket is made, before it
     * opens, because writes start going to it then.
     */
    var socketGeneration = 0
        private set

    /**
     * Reset per socket; gates the "socket really opened" signal to the first frame that
     * actually arrives, rather than optimistically when the socket is made.
     */
    private var hasEmittedOpen = false

    /**
     * Last reported visibility, re-asserted on every new socket. The server starts each
     * socket at `visible: false` and waits for us to say otherwise, so this has to be
     * per-socket state rather than something sent once — see `setPresence`.
     *
     * Starts false because that's the only value we've been TOLD. Claiming visible before
     * the app has said so is an assumption that happens to hold today (nothing launches
     * this app without it becoming active), and it suppresses push when it's wrong —
     * `enterForeground` reports the truth within milliseconds of the app actually
     * appearing, so the assumption buys nothing.
     */
    private var presenceVisible = false

    /**
     * Uploads currently in flight, by the `progressToken` each one put in its multipart body
     * — the sink for that upload's server-side progress (lurker-ios#47).
     *
     * This registry is also the filter. `upload-progress` fans out to every socket the user
     * has open, so the phone gets frames for an upload the *browser* is running; matching on
     * a token we ourselves minted is what keeps someone else's bytes off this readout. An
     * unrecognized token is dropped, silently and correctly.
     */
    private val uploadProgressSinks = mutableMapOf<String, (UploadServerProgress) -> Unit>()

    /**
     * Callers waiting on an acked verb's `send-result`, by the `clientId` each one minted — see
     * `request`. Every entry is settled exactly once: by its reply, its timeout, or the socket it
     * went down ending (`abandonReplies`), whichever comes first.
     */
    private val pendingReplies = mutableMapOf<String, CancellableContinuation<VerbReply>>()

    /** Each waiter's timeout, cancelled when it's settled any other way. */
    private val replyTimeouts = mutableMapOf<String, Job>()
    private var replySequence = 0

    init {
        // ⚠⚠ Preview BYTES get their own client, and the separation is not tidiness.
        //
        // The cache has to be big: on iOS, `URLCache` refuses to store any single response over
        // ~5% of its capacity, so against the shared 10 MB disk budget the ceiling is ~512 KB —
        // under a typical full-width preview image, which meant the proxy's `max-age=86400,
        // immutable` bought nothing and every image was re-fetched on every launch. 200 MB puts
        // the per-response ceiling around 10 MB, above the proxy's own 8 MB cap.
        //
        // But that cache was installed on the ONE session behind every authenticated endpoint —
        // login, settings, highlights, bookmarks, push, uploads, the socket upgrade — and
        // nothing purged it on sign-out. So while sign-out carefully cleared the preview
        // METADATA on the grounds that it is "the previous account's reading history", the
        // largest and most identifying artefact of that history, the pictures themselves, stayed
        // in the cache directory for whoever signed in next. On its own client the purge is a
        // one-liner (`clearMediaCache`), and a 200 MB byte budget stops competing with API JSON
        // for the same eviction.
        //
        // Port note: OkHttp's `Cache` is disk only, with no per-response ceiling, so the 200 MB
        // is kept and LurkerKit's 16 MB memory capacity has no counterpart. It is built from
        // `httpClient`, sharing its connection pool — but with a `Dispatcher` of its own, as the
        // two `URLSession`s have their own per-host limits: twenty slow image bodies on the
        // shared dispatcher's five-per-host cap would otherwise queue every REST call and the
        // socket upgrade behind them.
        mediaSession = httpClient.newBuilder()
            .dispatcher(Dispatcher())
            .cache(mediaCacheDirectory?.let { Cache(it, 200L * 1024 * 1024) })
            .build()
    }

    /**
     * Drop every cached preview image. Sign-out only — see the media client's own note.
     *
     * Port note: `Cache.evictAll()` deletes files, on the calling thread, as LurkerKit's
     * `removeAllCachedResponses` does.
     */
    fun clearMediaCache() {
        try {
            mediaSession.cache?.evictAll()
        } catch (e: IOException) {
            // `removeAllCachedResponses` cannot fail; a cache directory that cannot be cleared
            // must not end the sign-out that asked.
            logger.warning("Media cache purge failed: ${e.message}")
        }
    }

    // MARK: - Sign-in (OAuth)

    /** Register this app with `server`. `OAuthClients` decides when that's needed. */
    suspend fun registerApp(server: String, name: String): OAuth.Registration {
        val request = OAuth.registrationRequest(server = server, clientName = name)
            ?: return OAuth.Registration.Failure("That server URL doesn't look right.")
        return try {
            val (status, data) = session.await(request)
            OAuth.registration(status = status, data = data)
        } catch (error: IOException) {
            OAuth.Registration.Failure(signInFailure(error))
        }
    }

    /** Trade the approval page's code for a token. */
    suspend fun exchangeCode(server: String, clientId: String, code: String, verifier: String): OAuth.TokenGrant {
        val request = OAuth.tokenRequest(server = server, clientId = clientId, code = code, verifier = verifier)
            ?: return OAuth.TokenGrant.Failure("That server URL doesn't look right.")
        return try {
            val (status, data) = session.await(request)
            OAuth.tokenGrant(status = status, data = data)
        } catch (error: IOException) {
            OAuth.TokenGrant.Failure(signInFailure(error))
        }
    }

    /** Whether `server` still knows a saved `client_id`, or null when it couldn't say. */
    suspend fun isClientKnown(server: String, clientId: String): Boolean? {
        val request = OAuth.clientCheckRequest(server = server, clientId = clientId) ?: return null
        val (status, data) = try {
            session.await(request)
        } catch (_: IOException) {
            return null
        }
        return OAuth.clientKnown(status = status, data = data)
    }

    // MARK: - Lifecycle

    /**
     * Arm with a token, from a sign-in or a persisted session; follow with `start()`.
     * If the token was revoked, `start()`'s first authenticated call surfaces the 401 as
     * `Unauthorized`.
     */
    fun restore(server: String, token: String) {
        baseURL = ServerAddress.normalize(server)
        this.token = token
    }

    /**
     * After login/restore: fetch the network roster (proves the bearer authenticates
     * plain REST, and supplies the names the snapshot omits), then open the socket. If
     * the roster fetch already saw a 401 the token is dead — skip the upgrade.
     */
    suspend fun start() {
        if (!fetchNetworks()) return
        // Detached, so it genuinely cannot hold up the socket. `session` has the default 60s
        // request timeout, so a server that accepts the connection but stalls on this path — a
        // slow read, an overloaded cell, a proxy black-holing it — would otherwise leave the
        // app with no socket, no buffers and no messages for up to a minute on every launch.
        //
        // Ordering costs nothing now that values are cached across launches
        // (`SettingsCache`): the rules are already in force when the first backlog renders, so
        // there's nothing to wait for and nothing to reflow.
        scope.task { fetchSettings() }
        openSocket()
    }

    /**
     * Reopen the socket after a drop, resuming from `since` so the server ships only the
     * gap (`?since=N`) rather than re-sending everything.
     *
     * Settings and the network roster are both re-read here, and for the same reason: they
     * are the state with no resume path. `settings` frames are live fan-out only and are
     * never replayed (the resume slice carries messages), and the roster is REST-only —
     * nothing on the socket carries a network's name or its position.
     *
     * ⚠⚠ The roster read used to be skipped here, on the grounds that "names don't change".
     * True, and twice beside the point. The *set* of networks changes (lurker-ios#136 — a
     * network added elsewhere arrives nameless and stays that way), and so does their
     * **order**: a drag-reorder on the web writes `position` with no frame of any kind, unlike
     * pins, which at least have `pins-changed`. Skipped, the phone kept yesterday's order until
     * the app was relaunched — on a screen whose whole point is that the two clients agree.
     *
     * Not awaited: a slow or failing roster read must not hold the socket down. It lands as
     * a `networks` frame whenever it arrives, and the list re-sorts then.
     *
     * ⚠⚠ It reports a 401, because the socket can't be counted on to. A refused upgrade only
     * reads as a 401 here if everything between the phone and the server passes the failed
     * upgrade's status through, while a REST 401 always arrives. A revoked app sat on
     * "Reconnecting…" for good before this, its every reconnect refused and none of the
     * refusals read as a 401. A phone suspended through the revoke never hears the 4001 close.
     */
    fun reconnect(since: Long) {
        scope.task { fetchSettings() }
        scope.task { fetchNetworks() }
        openSocket(since = since)
    }

    /**
     * Which roster read is the current one.
     *
     * ⚠⚠ Needed because `applyNetworks` is authoritative over membership in both directions
     * — a network absent from a response is deleted, buffers and pins with it — and several
     * reads can now be in flight at once (every reconnect attempt starts one, alongside the
     * ones a create, a delete or a nameless network start). Land them out of order and the
     * older answer wins: a network deleted a moment ago reappears in the buffer list, the
     * join menu and `on:` search, or one added on the web is dropped along with its buffers.
     * The networks screen keeps the same guard over its own fetches for the same reason.
     */
    private var rosterGeneration = 0

    /**
     * Returns false only when the token was rejected (401) *and* the caller asked to hear
     * about it; true otherwise, including transient errors where the socket is still worth
     * trying.
     *
     * `reportingUnauthorized` is true for the connect-time and reconnect reads, which are the
     * token checks — see `refreshNetworks` for why every other caller wants it off.
     */
    internal suspend fun fetchNetworks(reportingUnauthorized: Boolean = true): Boolean {
        val token = token ?: return true
        val url = (baseURL + "/api/networks").toHttpUrlOrNull() ?: return true
        rosterGeneration += 1
        val generation = rosterGeneration
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        try {
            val (code, data) = session.await(request)
            if (code == 401 && reportingUnauthorized) {
                reportUnauthorized(sentWith = token)
                return false
            }
            // A read that a newer one has already superseded is dropped rather than applied:
            // see `rosterGeneration`. Checked here rather than earlier so a 401 on the token
            // check still reports, superseded or not.
            if (generation != rosterGeneration) return true
            val text = data.utf8OrNull()
            if (code in 200..<300 && text != null) {
                deliver(FrameParser.parseNetworks(text), sentWith = token)
            }
            return true
        } catch (_: IOException) {
            return true // a network hiccup, not an auth failure — let the socket try
        }
    }

    /**
     * `GET /api/settings/bootstrap` → the registry + the user's stored values (lurker-ios#65).
     *
     * Deliberately does NOT report a 401 the way `fetchNetworks` does. That call is the token
     * check and has already run and passed by the time we get here; a 401 on this one would
     * mean the token died in the intervening milliseconds, and treating it as an auth failure
     * would bounce the user to sign-in over a settings fetch. The socket upgrade is the next
     * thing to run and it will find out for itself.
     *
     * Internal for tests.
     */
    internal suspend fun fetchSettings() {
        val token = token ?: return
        val url = (baseURL + "/api/settings/bootstrap").toHttpUrlOrNull() ?: return
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        try {
            val (code, data) = session.await(request)
            val text = data.utf8OrNull()
            if (code !in 200..<300 || text == null) return
            deliver(FrameParser.parseSettingsBootstrap(text), sentWith = token)
        } catch (_: IOException) {
            // Registry defaults carry the app until the next launch.
        }
    }

    /**
     * `PATCH /api/settings` (lurker-ios#65). The server validates against the registry and fans
     * a `settings` frame back out to every device — including this one — so the store is
     * updated by the echo rather than optimistically here. That keeps one path for "a setting
     * changed" whether it came from this phone, the browser, or another device, and means a
     * rejected write simply never takes effect rather than needing a rollback.
     *
     * Returns the server's error message on failure, null on success. Also null, with nothing
     * applied, when the session that asked ended while the write was out: its screen is gone, and its
     * reply must not reach the next session.
     *
     * Port note: encoding a `JsonObject` cannot fail, so LurkerKit's "Couldn't encode that
     * setting." has nothing to answer here.
     */
    suspend fun updateSettings(changes: Map<String, SettingValue>): String? {
        val token = token ?: return "Not signed in."
        val url = (baseURL + "/api/settings").toHttpUrlOrNull() ?: return "Not signed in."
        val body = JsonObject(changes.mapValues { (_, value) -> value.jsonValue })
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .patch(jsonBody(JsonObject(mapOf("changes" to body))))
            .build()
        try {
            val (code, data) = session.await(request)
            // A session that ended while this was out hears nothing of it — its screen is gone, and
            // nothing of the departing account may land in the next one's store (see `deliver`).
            if (this.token != token) return null
            if (code == 401) {
                reportUnauthorized(sentWith = token)
                return "Signed out."
            }
            if (code in 200..<300) {
                // Apply the server's own response rather than waiting for the WS echo.
                //
                // Both arrive, on separate connections, racing each other. When the HTTP reply
                // wins — which it routinely does — a caller that rebuilt its UI at that moment
                // would read the OLD store value and visibly snap the control back before the
                // frame flipped it forward again. Worse, with the socket down (reconnect
                // backoff can run to tens of seconds, and `settings` frames are never
                // replayed) the echo may not arrive at all, leaving a write that succeeded
                // looking like one that failed.
                //
                // `values` is the full stored set; only the keys this write sent are taken from it
                // (`Settings.applyStored`), so it's idempotent with the echo that follows and can't
                // undo another write that answered first.
                val text = data.utf8OrNull()
                if (text != null) {
                    deliver(
                        ServerFrame.SettingsValues(
                            FrameParser.parseSettingValues(FrameParser.jsonObject(text)?.get("values")),
                            keys = changes.keys,
                        ),
                        sentWith = token,
                    )
                }
                return null
            }
            // The server explains itself on a 400 (`{error, key}`); prefer its wording.
            val text = data.utf8OrNull() ?: ""
            return FrameParser.errorMessage(text) ?: "Couldn't save that setting."
        } catch (_: IOException) {
            if (this.token != token) return null
            return "Couldn't reach the server."
        }
    }

    // MARK: - Networks (lurker-ios#11)

    /**
     * Re-read the network roster: after a network is created or deleted from this app, and
     * when a `snapshot` names one we hold no name for (lurker-ios#136). Reconnects read it as
     * well, but through `fetchNetworks` directly, because there a 401 has to end the session.
     *
     * ⚠⚠ Does NOT report a 401, unlike the connect-time and reconnect reads, which are the
     * token checks. This one runs beside a live socket, and a live socket hears a revoke as
     * its 4001 close.
     */
    suspend fun refreshNetworks() {
        fetchNetworks(reportingUnauthorized = false)
    }

    /**
     * The token check a reconnect makes — the roster read, reporting a 401 — without the socket.
     *
     * For a server this build can't talk to (lurker-ios#17), where nothing else will notice a
     * revoke: the upgrade answers 426 before it reads the token, and `/api/config` never reads it.
     */
    suspend fun checkToken() {
        fetchNetworks()
    }

    /**
     * `GET /api/networks`, read as editable configuration rather than as the roster.
     *
     * Null means no answer — unauthenticated, offline, unreadable — never "no networks",
     * which is an empty list and a legitimate state for a fresh account. The screen has to
     * tell those apart: one is an error, the other is the empty state that invites you to
     * add your first network.
     */
    suspend fun networkConfigs(): List<NetworkConfig>? =
        when (val result = rest("GET", "/api/networks")) {
            is RestResult.Ok -> FrameParser.parseNetworkConfigs(result.text) // null if unreadable
            is RestResult.Failure -> null
        }

    /**
     * `GET /api/network-presets` — what this instance recommends, and whether anything else
     * is allowed.
     *
     * Null is "we couldn't ask". The picker falls back to the bundled catalogue on null rather
     * than showing an error: the builtins are already on the device, and a failed request
     * for the instance's *extra* suggestions is no reason to refuse to add a network.
     */
    suspend fun networkPresets(): NetworkPresets? =
        when (val result = rest("GET", "/api/network-presets")) {
            is RestResult.Ok -> FrameParser.parseNetworkPresets(result.text)
            is RestResult.Failure -> null
        }

    /**
     * `POST /api/networks`. The server connects the new network immediately, regardless of
     * `autoconnect` — that flag governs cold start, not this.
     */
    suspend fun createNetwork(draft: NetworkDraft): NetworkSaveResult {
        draft.validationError?.let { problem -> return NetworkSaveResult.Failure(message = problem) }
        return save("POST", "/api/networks", body = draft.jsonBody(creating = true))
    }

    /**
     * `PATCH /api/networks/:id`. Takes effect on the next connection: the server updates the
     * row, and an established connection keeps whatever it registered with.
     */
    suspend fun updateNetwork(id: Int, draft: NetworkDraft): NetworkSaveResult {
        // ⚠ Checked here and not only on create: `PATCH` sets whatever keys it is given and
        // validates none of them, so this client is the only thing standing between an edit
        // and a network stored with no name. See `NetworkDraft.validationError`.
        draft.validationError?.let { problem -> return NetworkSaveResult.Failure(message = problem) }
        return save("PATCH", "/api/networks/$id", body = draft.jsonBody(creating = false))
    }

    private suspend fun save(method: String, path: String, body: JsonObject): NetworkSaveResult =
        when (val result = rest(method, path, body = body)) {
            is RestResult.Ok -> {
                val config = FrameParser.parseNetworkReply(result.text)
                if (config == null) {
                    // ⚠ A 2xx we can't read is a write that HAPPENED — its own case, so a caller
                    // can dismiss rather than invite the retry that creates the network twice.
                    // The roster is re-read here rather than in the caller's `Saved` branch:
                    // it's how a new network becomes visible at all, and nothing else fetches it
                    // before the next reconnect.
                    refreshNetworks()
                    NetworkSaveResult.SavedWithoutDetail
                } else {
                    NetworkSaveResult.Saved(config)
                }
            }
            is RestResult.Failure -> NetworkSaveResult.Failure(message = result.message)
        }

    /** Delete the network and everything under it. Null on success, a message otherwise. */
    suspend fun deleteNetwork(id: Int): String? = act("DELETE", "/api/networks/$id")

    /**
     * Start, stop, or restart the connection. Null on success, a message otherwise.
     *
     * None of these returns the resulting state: the server answers `{ok:true}` the moment
     * it has told the connection manager, and the transition itself arrives over the socket
     * as `state` events. So the caller's job is to report a refusal, not to update a light.
     */
    suspend fun connectNetwork(id: Int): String? = act("POST", "/api/networks/$id/connect")

    suspend fun disconnectNetwork(id: Int, reason: String? = null): String? =
        act("POST", "/api/networks/$id/disconnect", body = reason?.let { buildJsonObject { put("reason", it) } })

    suspend fun reconnectNetwork(id: Int): String? = act("POST", "/api/networks/$id/reconnect")

    // MARK: - DCC chat (lurker#270)

    /**
     * `POST /api/dcc/chat` — offer a DCC chat to `nick`, or accept the offer they already made.
     * Null on success, a message otherwise: "DCC is not enabled for this account" is the one a
     * hosted cell answers, where the feature is off.
     *
     * Success only means the offer is away. A DCC handshake takes as long as the peer takes to
     * answer, so the outcome arrives as notices in the `=nick` buffer instead.
     */
    suspend fun openDccChat(networkId: Int, nick: String, passive: Boolean): String? =
        act(
            "POST", "/api/dcc/chat",
            body = buildJsonObject {
                put("networkId", networkId)
                put("nick", nick)
                put("passive", passive)
            },
        )

    /**
     * `POST /api/dcc/chat/close` — end a live chat with `nick`, cancel our pending offer to
     * them, or decline theirs.
     */
    suspend fun closeDccChat(networkId: Int, nick: String): String? =
        act(
            "POST", "/api/dcc/chat/close",
            body = buildJsonObject {
                put("networkId", networkId)
                put("nick", nick)
            },
        )

    // MARK: - Client certificates (lurker#459)

    /**
     * `POST /api/networks/:id/certificate` — generate a pair, or import one, replacing any the
     * network had.
     *
     * Its own route, never the PATCH: the server parses and pair-checks the PEM before storing
     * it, because a malformed key reaching the TLS handshake throws. Like any network edit it
     * is used from the next connect.
     */
    suspend fun attachCertificate(networkId: Int, source: CertificateSource): CertificateResult {
        val body: JsonObject = when (source) {
            CertificateSource.Generate -> buildJsonObject { put("mode", "generate") }
            is CertificateSource.Imported -> buildJsonObject {
                put("mode", "import")
                put("cert", source.cert)
                put("key", source.key)
            }
        }
        return when (val result = rest("POST", "/api/networks/$networkId/certificate", body = body)) {
            is RestResult.Ok -> {
                // ⚠⚠ A 2xx attached something even when the reply doesn't say what. Reported as a
                // failure, the form kept offering Generate over the certificate that did land, and
                // the server's attach replaces without asking. So: attached, with no expiry until
                // the next read of the list fills it in.
                val described = FrameParser.parseNetworkReply(result.text)?.clientCertificate
                CertificateResult.Updated(described ?: ClientCertificate.Usable(expires = null))
            }
            is RestResult.Failure -> CertificateResult.Failure(message = result.message)
        }
    }

    /** `DELETE /api/networks/:id/certificate`. */
    suspend fun removeCertificate(networkId: Int): CertificateResult =
        when (val result = rest("DELETE", "/api/networks/$networkId/certificate")) {
            // A removal has one possible outcome, so the status says everything the reply would.
            is RestResult.Ok -> CertificateResult.Updated(null)
            is RestResult.Failure -> CertificateResult.Failure(message = result.message)
        }

    /**
     * `GET /api/networks/:id/certificate/export` — key and certificate as one PEM file. The
     * only route that returns the private key.
     */
    suspend fun exportCertificate(networkId: Int): CertificateExport =
        when (val result = rest("GET", "/api/networks/$networkId/certificate/export")) {
            is RestResult.Ok -> {
                // A 2xx that isn't a PEM pair (a captive portal's page, say) must not reach the
                // share sheet as someone's certificate file.
                if (ClientCertificatePEM.reading(result.text) !is ClientCertificatePEM.Reading.Ready) {
                    CertificateExport.Failure(message = "The server's reply wasn't a certificate.")
                } else {
                    CertificateExport.Pem(result.text)
                }
            }
            is RestResult.Failure -> CertificateExport.Failure(message = result.message)
        }

    private suspend fun act(method: String, path: String, body: JsonObject? = null): String? =
        when (val result = rest(method, path, body = body)) {
            is RestResult.Ok -> null
            is RestResult.Failure -> result.message
        }

    // MARK: - REST

    private sealed interface RestResult {
        data class Ok(val text: String) : RestResult
        data class Failure(val message: String) : RestResult
    }

    /**
     * One authenticated JSON round trip: bearer header, optional JSON body, and a reply that
     * is either the 2xx body text or a message fit to put in front of the user.
     *
     * Written for lurker-ios#11, which adds seven endpoints to a file that had been
     * hand-rolling the same request per call. It is not a migration — the existing calls keep
     * their own bodies until something needs to touch them anyway — but nothing new should be
     * adding an eighth copy of bearer-plus-status-code.
     *
     * ⚠ A 401 bounces the session, matching `fetchNetworks`. Everything routed through here
     * is a deliberate user action, so a dead token has to end the session rather than read
     * as "that network wouldn't save". (`fetchSettings` deliberately does the opposite and
     * keeps its own path — see its note for why.)
     *
     * Port note: encoding a `JsonObject` cannot fail, so LurkerKit's "Couldn't encode that
     * request." has nothing to answer here. A POST, PUT or PATCH with no body sends an empty
     * one, as `URLSession` does; OkHttp refuses those methods without a body.
     */
    private suspend fun rest(method: String, path: String, body: JsonObject? = null): RestResult {
        val token = token ?: return RestResult.Failure("Not signed in.")
        val url = (baseURL + path).toHttpUrlOrNull() ?: return RestResult.Failure("Not signed in.")
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        val requestBody: RequestBody? = if (body != null) {
            builder.header("Content-Type", "application/json")
            jsonBody(body)
        } else if (method == "POST" || method == "PUT" || method == "PATCH") {
            ByteArray(0).toRequestBody()
        } else {
            null
        }
        builder.method(method, requestBody)
        try {
            val (code, data) = session.await(builder.build())
            val text = data.utf8OrNull() ?: ""
            if (code == 401) {
                reportUnauthorized(sentWith = token)
                return RestResult.Failure("Signed out.")
            }
            if (code in 200..<300) return RestResult.Ok(text)
            // The server explains its own refusals — a host the admin's allowlist excludes, a
            // missing field, a paused account — and its wording is better than anything this
            // layer could infer from a status code.
            return RestResult.Failure(FrameParser.errorMessage(text) ?: "The server refused that ($code).")
        } catch (_: IOException) {
            return RestResult.Failure("Couldn't reach the server.")
        }
    }

    /**
     * A native client CAN set headers on the WS upgrade, so the session token rides as a
     * bearer where a browser would need a cookie. `since > 0` resumes from that event id.
     */
    private fun openSocket(since: Long = 0) {
        val token = token ?: return
        val url = socketURL(baseURL = baseURL, since = since) ?: return
        // Replace any prior socket so a reconnect can't leave two live; callbacks from the
        // old one are ignored via the `task === socket` guard below.
        socket?.close(GOING_AWAY, null)
        // Nothing sent down the old socket is answered on the new one.
        abandonReplies()
        hasEmittedOpen = false
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        // Port note: OkHttp starts connecting the moment the socket is made — there is no
        // separate `resume()`. Its callbacks reach this client only through `scope`, so none
        // can land before the two lines below have run.
        val task = session.newWebSocket(request, SocketListener())
        socket = task
        socketEnded = false
        socketGeneration += 1
    }

    /**
     * The socket's receive side.
     *
     * On iOS, `URLSessionWebSocketTask` has no stream — the receive is re-armed after each
     * frame, and because the next one is armed only at the end of `handleOpen`, receives are
     * strictly serialized, so ordering is preserved.
     *
     * Port note: OkHttp's listener is the receive loop. Its callbacks arrive in order on OkHttp's
     * reader thread and each hops to main with `scope.launch`, which keeps that order; unlike
     * iOS, the reader does not wait for main before reading the next frame. Only values cross
     * the hop: the text, and the codes `handleClose` needs.
     *
     * A socket's ending is reported once. OkHttp can call both `onClosing` and `onClosed` (or
     * `onFailure`) for one socket, where LurkerKit's receive fails exactly once, so the first of
     * them wins. What each reports, as LurkerKit reads it off the task:
     *  - `status` is the UPGRADE's: `onOpen`'s response code (101) once the socket has opened,
     *    or the refusal's (401, 426) from `onFailure`'s response, or null when no response came.
     *  - `closeCode` is the server's close frame code from `onClosing` (4001 for a revoke), and
     *    0 — LurkerKit's `.invalid` — for a transport failure with no close frame.
     *  - `reason` is the close frame's reason, or the failure's message, where LurkerKit passes
     *    the error's `localizedDescription`. Nothing reads it but a log.
     */
    private inner class SocketListener : WebSocketListener() {
        private val ended = AtomicBoolean(false)

        @Volatile
        private var upgradeStatus: Int? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            upgradeStatus = response.code
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleOpen(text = text, task = webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // A binary frame carries nothing this client reads, but it is still a frame through.
            scope.launch { handleOpen(text = null, task = webSocket) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Port note: `URLSessionWebSocketTask` answers the server's close frame itself;
            // OkHttp leaves it to the listener, and until it is answered the socket lingers.
            // 1000 rather than an echo of `code`, which OkHttp refuses for reserved values
            // (1005, "no status").
            webSocket.close(NORMAL_CLOSURE, null)
            end(webSocket, status = upgradeStatus, closeCode = code, reason = reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            end(webSocket, status = upgradeStatus, closeCode = code, reason = reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            end(webSocket, status = response?.code ?: upgradeStatus, closeCode = 0, reason = t.description())
        }

        private fun end(webSocket: WebSocket, status: Int?, closeCode: Int, reason: String) {
            if (!ended.compareAndSet(false, true)) return
            scope.launch { handleClose(code = status, closeCode = closeCode, reason = reason, task = webSocket) }
        }
    }

    private fun handleOpen(text: String?, task: WebSocket) {
        if (task !== socket) return // a socket we've since replaced — ignore
        if (!hasEmittedOpen) {
            // First byte through = the upgrade actually succeeded. A refused upgrade never
            // reaches here — it lands in handleClose — so we never falsely report open.
            hasEmittedOpen = true
            // Re-assert visibility: this is a NEW socket and the server starts every
            // socket at `visible: false`. Without this, a reconnect while the user is
            // reading would leave the server thinking nobody's home, and it would push a
            // DM to the phone in their hand.
            send(
                buildJsonObject {
                    put("type", "presence")
                    put("visible", presenceVisible)
                },
            )
            onFrame(ServerFrame.SocketOpen)
            // Whoever heard that may have closed this socket already (a server this build can't
            // talk to, lurker-ios#17), and nothing read off it may reach the store.
            if (task !== socket) return
        }
        if (text != null) {
            // Upload progress is a reply, not state: it answers about one thing this device
            // started, matched by the token that started it, and never travels on to the store.
            // Intercepted here rather than routed through `ChatViewModel` because request/reply
            // correlation is this layer's job — the store has no idea a question was asked.
            //
            // ⚠ It used to have company: a WS `search-result` was correlated the same way. Search
            // is a REST read now (lurker-ios#123), so this is the last frame on the socket that
            // answers a question rather than announcing something.
            val frame = FrameParser.parseWs(text)
            when {
                frame is ServerFrame.UploadProgress ->
                    uploadProgressSinks[frame.token]?.invoke(frame.progress)
                // An acked verb's answer, for the caller holding its `clientId`. Read a second
                // time, in full, because the frame keeps only what the store needs.
                frame is ServerFrame.SendResult && frame.clientId != null &&
                    pendingReplies[frame.clientId] != null ->
                    FrameParser.parseVerbReply(text)?.let { (id, reply) -> settleReply(id, reply) }
                else ->
                    onFrame(frame)
            }
        }
        // Port note: no re-arm. LurkerKit calls `listen(on:)` again here; the listener above
        // delivers the next frame by itself.
    }

    private fun handleClose(code: Int?, closeCode: Int, reason: String, task: WebSocket) {
        if (task !== socket) return
        socketEnded = true
        abandonReplies()
        onFrame(closeFrame(status = code, closeCode = closeCode, reason = reason))
    }

    // MARK: - Verbs

    /**
     * Ask the server to OPEN a buffer — reopen a closed row, mint a DM row for a bare nick,
     * or JOIN an unjoined channel. A WRITE, and one the user's other devices are now told
     * about, so it belongs to deliberate intent only (`/query alice`, tapping a friend whose
     * DM is closed server-side).
     *
     * **Not for hydration** — that's `loadLatest`. Filling in a shell with this verb is what
     * made merely *opening a screen* reopen a buffer on every device the user owns, and,
     * because the server's paused-account gate correctly classes writes as writes, made a
     * paused account unable to read its own history at all.
     *
     * Returns whether it was handed to a socket — see `send`.
     */
    fun openBuffer(networkId: Int?, target: String, countBy: HistoryCountBy): Boolean {
        if (networkId == null) return false
        return send(
            buildJsonObject {
                put("type", "open-buffer")
                put("networkId", networkId)
                put("target", target)
                put("countBy", countBy.rawValue)
            },
        )
    }

    /**
     * The wire form of a `send`/`action`/`notice`, with the ACK correlator attached.
     *
     * ⚠⚠ `clientId` is what makes a refused send visible AT ALL. `wsHub` emits `send-result`
     * only when the request carried one, so without this the frame is never sent — and every
     * failure the server declines to also announce as a bare `error` frame (`not-connected`,
     * notably, which is the ordinary outcome of any reconnect since lurker#809's writable-
     * connection gate) reached this client as silence: composer cleared, no self row fanned
     * back, message gone. `FrameParser`, `ServerFrame` and `LurkerStore` all handled
     * `send-result` correctly the whole time; nothing ever asked for one (lurker-ios#128).
     */
    private fun verb(
        type: String,
        networkId: Int,
        target: String,
        text: String,
        clientId: String?,
        replyTo: Long? = null,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("networkId", networkId)
        put("target", target)
        put("text", text)
        // Omitted rather than sent as null when absent: the server tests presence.
        if (clientId != null) put("clientId", clientId)
        // The stored line this answers (lurker-ios#184). The server resolves its msgid in the
        // same buffer and sends a plain line when it can't make a reply.
        if (replyTo != null) put("replyTo", replyTo)
    }

    fun sendMessage(
        networkId: Int?,
        target: String,
        text: String,
        clientId: String? = null,
        replyTo: Long? = null,
    ): Boolean {
        if (networkId == null) return false
        // The one write the user made deliberately, and the one with no resend behind it —
        // so a socket-level failure to deliver it is worth telling them about. A `send`
        // that reaches the server but is rejected comes back as a `send-result` instead;
        // this only covers never getting it onto the wire. The server splits on newlines and
        // byte-length, so the whole (possibly multi-line) body goes as one `send`.
        return send(
            verb("send", networkId = networkId, target = target, text = text, clientId = clientId, replyTo = replyTo),
            surfacesFailure = true,
        )
    }

    /**
     * CTCP ACTION — `/me` and `/slap`. Surfaces a socket-level failure like `send`: it's a
     * deliberate line the user typed, with nothing behind it to retry.
     */
    fun sendAction(
        networkId: Int?,
        target: String,
        text: String,
        clientId: String? = null,
        replyTo: Long? = null,
    ): Boolean {
        if (networkId == null) return false
        return send(
            verb("action", networkId = networkId, target = target, text = text, clientId = clientId, replyTo = replyTo),
            surfacesFailure = true,
        )
    }

    /** NOTICE — `/notice`. Same failure surfacing rationale as `send`. */
    fun sendNotice(networkId: Int?, target: String, text: String, clientId: String? = null): Boolean {
        if (networkId == null) return false
        return send(
            verb("notice", networkId = networkId, target = target, text = text, clientId = clientId),
            surfacesFailure = true,
        )
    }

    /**
     * Add (or, with `remove`, take back) our IRCv3 reaction on a stored line (lurker-ios#183).
     *
     * ⚠⚠ Never rendered optimistically, by design: nothing is echoed to this call, the network's
     * own echo arrives as a `reaction` frame, and a refusal is silence. A chip that lit up here
     * and then never got its echo would be a reaction nobody else can see.
     */
    fun react(messageId: Long, value: String, remove: Boolean): Boolean =
        send(
            buildJsonObject {
                put("type", "react")
                put("messageId", messageId)
                put("value", value)
                put("remove", remove)
            },
            surfacesFailure = true,
        )

    /**
     * Ask what reactions stand now on lines we already hold — after a resume, when changes made
     * while we were away reached no socket of ours. Answered to this socket as `reactions-sync`.
     */
    fun syncReactions(messageIds: List<Long>): Boolean {
        if (messageIds.isEmpty()) return false
        return send(
            buildJsonObject {
                put("type", "sync-reactions")
                putJsonArray("messageIds") { messageIds.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    /**
     * A raw IRC line — the escape hatch behind `/nick`, `/mode`, `/kick`, `/whois`, the
     * service messages, the server queries, and every unrecognized command.
     *
     * **Returns false when it went nowhere.** A typed `/quote` (and every command that goes out
     * raw) is handed back to the composer on false (sweep L02). The WHOIS behind the profile
     * screen needs it too: it claims an in-flight slot that only a reply can free, so a line
     * that never left the socket would wedge that nick's lookup for the session (see
     * `whoisPending`).
     */
    fun sendRaw(networkId: Int?, line: String): Boolean {
        if (networkId == null) return false
        return send(
            buildJsonObject {
                put("type", "raw")
                put("networkId", networkId)
                put("line", line)
            },
            surfacesFailure = true,
        )
    }

    /**
     * Write or clear the account's note about a nick (lurker-ios#12).
     *
     * Fire-and-ask, exactly like `setRelayBot` above: the server caps the note at 4 KB,
     * stores it, and fans a `nick-note-updated` back to every device — the note exists only
     * once that lands. Nothing is written locally, so a save the server refuses never appears.
     *
     * ⚠ **An empty `note` is the delete**, and that's the server's encoding rather than a
     * convention chosen here: `set_nick_note` drops the row for an empty string and echoes
     * back `note: ''`, so the clear and the write are one verb and one frame shape.
     * ⚠ Refuses a blank nick rather than sending one. The server trims and throws
     * `invalid_input`, and the WS handler swallows that throw (`wsHub.ts:3491`) — so no frame
     * comes back, and since nothing is written optimistically the editor would show a save
     * that silently did nothing. Same guard `requestWhois` makes, for the same reason.
     */
    fun setNickNote(networkId: Int, nick: String, note: String): Boolean {
        @Suppress("NAME_SHADOWING")
        val nick = nick.trimmingWhitespacesAndNewlines()
        if (nick.isEmpty()) return false
        return send(
            buildJsonObject {
                put("type", "set-nick-note")
                put("networkId", networkId)
                put("nick", nick)
                put("note", note)
            },
            surfacesFailure = true,
        )
    }

    /**
     * Part a channel with an optional reason. The buffer survives (dimmed); `/close` drops it.
     * False when it went nowhere — the composer hands a `/part` typed offline back (sweep L02).
     */
    fun part(networkId: Int?, channel: String, reason: String?): Boolean {
        if (networkId == null) return false
        return send(
            buildJsonObject {
                put("type", "part")
                put("networkId", networkId)
                put("channel", channel)
                if (reason != null) put("reason", reason)
            },
        )
    }

    /**
     * A CTCP request aimed at a target — `/ctcp`, `/ping`. `issuingTarget` is the buffer the
     * command was run in, so a reply can be routed back to it. False when it went nowhere.
     */
    fun sendCTCP(networkId: Int?, target: String, issuingTarget: String, ctcpType: String, args: String): Boolean {
        if (networkId == null) return false
        return send(
            buildJsonObject {
                put("type", "ctcp")
                put("networkId", networkId)
                put("target", target)
                put("issuingTarget", issuingTarget)
                put("ctcpType", ctcpType)
                put("args", args)
            },
        )
    }

    /**
     * Store an ignore rule (`/ignore`, lurker-ios#86).
     *
     * **A null `networkId` is a global rule — every network — not "no network."** That's the
     * opposite of the guard every conversation verb above takes, and the reason this one has
     * none: null is the *default* scope here, and dropping the frame for it would silently
     * swallow the commonest rule there is. JSON `null` rather than a missing key, so the scope
     * is stated rather than inferred (the server distinguishes a null id from a malformed one).
     *
     * Fire-and-ask: the server re-validates, and the rule only exists once it fans an
     * `ignore-list-updated` back. Surfaces a socket-level failure like the other deliberate
     * writes — there's no queue behind it.
     *
     * **Returns false when it went nowhere**, and the caller must check: the command that
     * sent this prints a receipt, and nothing else would contradict it. The window is real —
     * `start()` awaits a REST call before opening the socket, and the composer is live the
     * whole time.
     */
    fun addIgnore(networkId: Int?, rule: IgnoreRule): Boolean =
        send(
            buildJsonObject {
                put("type", "add-ignore")
                put("networkId", networkId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("rule", ruleJSON(rule))
            },
            surfacesFailure = true,
        )

    /**
     * Drop ignore rules (`/unignore`): by `id` — what a listed index resolves to — or by
     * `mask`, which clears every rule carrying it. `networkId` scopes it, null meaning the
     * global bucket; a by-mask removal on a network scope also clears matching globals, which
     * is the server's behavior and why it answers with both buckets refreshed.
     *
     * Returns false when it went nowhere, for the same reason `addIgnore` does — and it
     * matters more here, where the receipt the caller holds says something was *removed*.
     */
    fun removeIgnore(networkId: Int?, id: Int?, mask: String?): Boolean {
        // Naming neither is a frame the server reads and discards, and reporting it as sent
        // would put a "removed …" receipt under it. The reference refuses the same call
        // (`ignores.ts`'s `if (by.id == null && !by.mask) return`).
        if (id == null && mask == null) return false
        return send(
            buildJsonObject {
                put("type", "remove-ignore")
                put("networkId", networkId?.let { JsonPrimitive(it) } ?: JsonNull)
                if (id != null) put("id", id)
                if (mask != null) put("mask", mask)
            },
            surfacesFailure = true,
        )
    }

    /**
     * Mark or unmark a nick as a relay/bridge bot on a network (lurker#277) — what `/relay add`
     * and `/relay remove` send. `pattern` is the custom envelope template; empty means the
     * built-in formats, which is what a bare mark stores.
     *
     * Fire-and-ask, like the ignore verbs above: the server validates, stores, and fans a
     * `relay-bot-updated` back to every device, and the mark exists only once that lands. Nothing
     * is written locally, so a mark the server refuses never appears — and one made in a browser
     * arrives here by the identical route.
     *
     * **Returns false when it went nowhere**, and the caller must check, for the same reason
     * `addIgnore` does: `/relay` prints a receipt and nothing else would contradict it.
     */
    fun setRelayBot(networkId: Int, nick: String, marked: Boolean, pattern: String): Boolean =
        send(
            buildJsonObject {
                put("type", "set-relay-bot")
                put("networkId", networkId)
                put("nick", nick)
                put("marked", marked)
                put("pattern", pattern)
            },
            surfacesFailure = true,
        )

    /**
     * Set yourself away (`/away`), or clear it (`/back`, or `/away` with no message), on the
     * network named (lurker#994). The server widens it to every network for `all: true`, for
     * the `away.all_networks` setting when `all` is null, and when there's no network (the
     * system buffer). False when it went nowhere.
     */
    fun setAway(message: String, networkId: Int?, all: Boolean?): Boolean =
        send(awayFrame(type = "away", message = message, networkId = networkId, all = all))

    fun setBack(networkId: Int?, all: Boolean?): Boolean =
        send(awayFrame(type = "back", message = null, networkId = networkId, all = all))

    /**
     * Page older history for a buffer, back from `before` (exclusive message id). The
     * reply is a `history` frame (mode `before`) the store prepends. System-buffer paging
     * isn't wired for 1.0.
     */
    fun loadOlder(networkId: Int?, target: String, before: Long, countBy: HistoryCountBy, limit: Int = 100) {
        if (networkId == null) return
        send(
            buildJsonObject {
                put("type", "history")
                put("networkId", networkId)
                put("target", target)
                put("before", before)
                put("limit", limit)
                put("countBy", countBy.rawValue)
            },
        )
    }

    /**
     * Request a history slice centered on `anchorId` — the message to jump to (lurker-ios#42).
     * The reply is a `history` frame (mode `around`) with the anchor included in the middle,
     * which the store applies by replacing the buffer's slice. No `open-buffer` first: the
     * server serves this straight from the DB. `limit` is per side, so up to `2*limit + 1`
     * events.
     *
     * Carries `countBy` (sizing each side) because on this client a jump slice is not merely a
     * jump: `hydrateIfNeeded` returns early while a jump is pending, so for a buffer entered
     * from a push tap, a highlight, or jump-to-first-unread, THIS is the first screenful and
     * no other hydrate precedes it.
     */
    fun loadAround(networkId: Int?, target: String, anchorId: Long, countBy: HistoryCountBy, limit: Int = 100) {
        if (networkId == null) return
        send(
            buildJsonObject {
                put("type", "history")
                put("mode", "around")
                put("networkId", networkId)
                put("target", target)
                put("anchorId", anchorId)
                put("limit", limit)
                put("countBy", countBy.rawValue)
            },
        )
    }

    /**
     * Fetch a buffer's newest slice. Two callers, one request:
     *
     * 1. **Hydration** — filling in a shell when a screen opens on a buffer we hold but
     *    haven't fetched. This is a pure READ: it reopens nothing, mints no row, JOINs
     *    nothing, is invisible to the user's other devices, and is not blocked for a paused
     *    account. `open-buffer` is none of those things, which is why hydration doesn't use
     *    it. The server always answers, even for a buffer with no history at all, so a
     *    one-shot caller can't be stranded waiting on a reply that will never come.
     * 2. **Re-attaching** a detached buffer to the live tail (lurker-ios#42), after a jump left
     *    the screen parked on an older `around` window.
     *
     * The reply is a `history` frame (mode `latest`); the store replaces the slice, marks the
     * buffer hydrated, and clears `hasMoreNewer`. Carries `countBy` because for case 1 this
     * reply is our first screenful — see `HistoryCountBy`.
     */
    fun loadLatest(networkId: Int?, target: String, countBy: HistoryCountBy, limit: Int = 100) {
        if (networkId == null) return
        send(
            buildJsonObject {
                put("type", "history")
                put("mode", "latest")
                put("networkId", networkId)
                put("target", target)
                put("limit", limit)
                put("countBy", countBy.rawValue)
            },
        )
    }

    /**
     * Page NEWER history for a detached buffer (scroll-down), forward from `after` (exclusive
     * message id). The reply is a `history` frame (mode `after`) the store appends; once the
     * server signals `hasMoreNewer: false` the slice has reached the live tail and the buffer
     * re-attaches (lurker-ios#45). The mirror of `loadOlder`, and the read path that carries a
     * jump-to-first-unread back down to the present without the pill's full `latest` re-fetch.
     */
    fun loadNewer(networkId: Int?, target: String, after: Long, countBy: HistoryCountBy, limit: Int = 100) {
        if (networkId == null) return
        send(
            buildJsonObject {
                put("type", "history")
                put("mode", "after")
                put("networkId", networkId)
                put("target", target)
                put("afterId", after)
                put("limit", limit)
                put("countBy", countBy.rawValue)
            },
        )
    }

    /**
     * Mark a buffer read up to `messageId`. The server MAX-clamps, so re-sending a lower
     * id is a safe no-op. The system buffer sends `networkId: null` (hence JSON null, not a
     * dropped key), so this can't reuse the null-`networkId` shortcut.
     * False when it went nowhere, which `ChatViewModel.markRead` must not record as marked.
     */
    fun markRead(networkId: Int?, target: String, messageId: Long): Boolean {
        return send(
            buildJsonObject {
                put("type", "mark-read")
                put("networkId", networkId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("target", target)
                put("messageId", messageId)
            },
        )
    }

    /**
     * Save or unsave a message. The server answers with a `bookmark-updated` fan-out to
     * every socket on the account — including this one — so the caller doesn't (and
     * shouldn't) flip any local state itself.
     *
     * Saving is silently refused for a message the account doesn't own, and no echo comes
     * back for it. That's why the toggle isn't rendered optimistically: the honest failure
     * is a control that doesn't move, not one that moves and then springs back.
     * Returns false when the verb couldn't be sent at all (no socket — offline, or the app
     * has just resumed and the socket isn't back). Nothing queues it, so a caller that has
     * already taken a row off the screen has to know. `surfacesFailure` covers the rarer
     * case of a socket that accepts the write and then errors.
     */
    fun setBookmark(messageId: Long, saved: Boolean): Boolean =
        send(
            buildJsonObject {
                put("type", if (saved) "set-bookmark" else "unset-bookmark")
                put("messageId", messageId)
            },
            surfacesFailure = true,
        )

    /**
     * Fetch a page of bookmarks (`GET /api/bookmarks`). Same cursor contract and same
     * row shape as `fetchHighlights` — the server builds both from the same query so one
     * list renderer serves both — so it reuses `HighlightsPage` rather than cloning it.
     *
     * Ordering is by *message* id, i.e. by when the line was said, not when it was saved.
     */
    suspend fun fetchBookmarks(before: Long? = null, limit: Int = 50): HighlightsPage? {
        val token = token ?: return null
        val components = (baseURL + "/api/bookmarks").toHttpUrlOrNull()?.newBuilder() ?: return null
        val query = mutableListOf("limit" to limit.toString())
        if (before != null) query.add("before" to before.toString())
        components.encodedQuery(percentEncodedQuery(query))
        val request = Request.Builder().url(components.build()).header("Authorization", "Bearer $token").build()
        try {
            val (code, data) = session.await(request)
            if (code == 401) {
                reportUnauthorized(sentWith = token)
                return null
            }
            val text = data.utf8OrNull()
            if (code !in 200..<300 || text == null) return null
            return FrameParser.parseHighlights(text)
        } catch (_: IOException) {
            return null
        }
    }

    // MARK: - Search

    /**
     * Full-text search across every buffer the account owns — `GET /api/search`.
     *
     * ⚠⚠ A REST read since lurker#800, and the change is a DELETION: this used to be the one
     * read in this client that wasn't. As a WS verb it needed a monotonic token echoed by the
     * server, a pending-continuation map, a timeout to stop an unanswered call stranding its
     * spinner forever, and a sweep to fail everything in flight when the socket dropped. HTTP
     * gives every request its own reply, so all of that goes.
     *
     * ⚠⚠ And a superseded search now actually STOPS. Cancelling the enclosing coroutine cancels
     * the HTTP call, the route checks `req.destroyed` before spending the query, and the
     * server does no work for a question nobody is waiting on. There was no WS cancel frame — a
     * stale search ran to completion and the reply was discarded — which mattered because the
     * FTS query runs on the same event loop that services every IRC connection on the cell.
     *
     * `networkId` is the caller's resolution of the query's `on:` *name*, which only the roster
     * can turn into an id; the query itself carries the rest.
     *
     * Null means no answer, never "no matches" (that's an empty page): unauthenticated, offline,
     * or a response we couldn't read. All three leave the caller free to show an error rather
     * than an empty list — a search that silently reads as "nothing matched" is the one wrong
     * answer here.
     */
    suspend fun search(
        query: SearchQuery,
        networkId: Int?,
        before: Long? = null,
        limit: Int = 50,
    ): HighlightsPage? {
        val token = token ?: return null
        val url = SearchRequest.url(base = baseURL, query = query, networkId = networkId, before = before, limit = limit)
            ?: return null
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        try {
            val (code, data) = session.await(request)
            // ⚠ `scoped` is what makes a 404 readable here — see `SearchRequest.outcome`, where
            // the rule lives so it can be tested.
            when (SearchRequest.outcome(status = code, scoped = networkId != null)) {
                SearchRequest.Outcome.Unauthorized -> {
                    reportUnauthorized(sentWith = token)
                    return null
                }
                SearchRequest.Outcome.EmptyPage -> return HighlightsPage(items = emptyList(), nextBefore = null)
                SearchRequest.Outcome.Failed -> return null
                SearchRequest.Outcome.Page -> {}
            }
            val text = data.utf8OrNull() ?: return null
            // Same `{items, nextBefore}` envelope as highlights and bookmarks, and the same
            // decorated rows — the route and the WS verb call the same `search_messages`. The
            // cursor is now the server's rather than one synthesized from `hasMore` plus the last
            // row's id, which is the other half of the deletion.
            return FrameParser.parseHighlights(text)
        } catch (_: IOException) {
            return null
        }
    }

    // MARK: - Upload history

    /**
     * One page of the account's upload history — `GET /api/uploads` (lurker-ios#138).
     *
     * A plain REST read like bookmarks and search, and for the same reason: the client only
     * holds the pages it has scrolled through, so the filename search and the kind filter are
     * questions for the server rather than predicates over what happens to be in memory.
     *
     * Null means no answer, never "nothing matched" (that's an empty page): unauthenticated,
     * offline, or a response we couldn't read. All three leave the caller free to offer a retry
     * rather than claiming the browse came back empty.
     */
    suspend fun fetchUploads(
        filter: UploadsFilter,
        before: Int? = null,
        limit: Int,
    ): UploadsPage? {
        val token = token ?: return null
        val url = UploadsRequest.url(base = baseURL, filter = filter, before = before, limit = limit) ?: return null
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        try {
            val (code, data) = session.await(request)
            when (UploadsRequest.outcome(status = code)) {
                UploadsRequest.Outcome.Unauthorized -> {
                    reportUnauthorized(sentWith = token)
                    return null
                }
                UploadsRequest.Outcome.Failed -> return null
                UploadsRequest.Outcome.Page -> {}
            }
            val text = data.utf8OrNull() ?: return null
            return FrameParser.parseUploads(text)
        } catch (_: IOException) {
            return null
        }
    }

    /**
     * Star or unstar an upload. Null on success, else a sentence to put in front of the user.
     *
     * ⚠ Its own subpath rather than a field on a PATCH of the row, matching the route:
     * everything else about an upload is immutable once captured, and a general-purpose PATCH
     * would imply otherwise.
     */
    suspend fun setUploadFavorite(id: Int, favorite: Boolean): String? =
        act(if (favorite) "PUT" else "DELETE", "/api/uploads/$id/favorite")

    /**
     * Destroy an upload's bytes and drop its row. Null on success, else the server's own reason.
     *
     * ⚠⚠ Only ever called for a row whose `canDelete` is set. There is deliberately no
     * remove-the-record-but-leave-the-file path — the route answers 409 for a row whose bytes
     * can't be destroyed, so asking for one is a bug on this side, not a case to handle.
     */
    suspend fun deleteUpload(id: Int): String? = act("DELETE", "/api/uploads/$id")

    /**
     * Tell the network we're composing. Fire-and-forget: the server turns it into a
     * `+typing` TAGMSG and there's no ack, so a dropped one simply lapses on the peer's lease
     * rather than needing a retry.
     *
     * Guarded like `closeBuffer`, and for the same reason: this verb is meaningless without a
     * real conversation on the other end. The app-scoped system buffer has no network to send
     * over and a `:server:` log is a one-way feed, so both are dropped here rather than put on
     * the wire as a frame the server would have to reject. That's also why there's no
     * JSON-null branch — a null `networkId` never gets this far.
     *
     * A `=nick` DCC chat is dropped too. It has someone on the other end, but typing rides a
     * `TAGMSG` on the IRC wire, which a DCC socket has no equivalent of — the server refuses
     * the target rather than put `=bob` in one.
     */
    fun setTyping(networkId: Int?, target: String, signal: TypingSignal) {
        if (networkId == null || target.startsWith(":server:") || DccChat.isTarget(target)) return
        send(
            buildJsonObject {
                put("type", "typing")
                put("networkId", networkId)
                put("target", target)
                put("state", signal.rawValue)
            },
        )
    }

    fun markAllRead() {
        send(buildJsonObject { put("type", "mark-all-read") })
    }

    /**
     * Join a channel on a network, with an optional key for a `+k` channel. Its row arrives with
     * `channel-joined`, once the server has us in it.
     *
     * False when there is no socket to carry it: the verb went nowhere and nothing will answer,
     * which `ChatViewModel.requestJoin` tells the user.
     */
    fun joinChannel(networkId: Int, channel: String, key: String? = null): Boolean =
        send(
            buildJsonObject {
                put("type", "join")
                put("networkId", networkId)
                put("channel", channel)
                if (key != null) put("key", key)
            },
        )

    /**
     * Close a buffer: parts a channel and stops tracking a DM. The server pseudo-buffer
     * (`:server:`) can't be closed. No-op for the system buffer (networkId null).
     *
     * False when there was a verb to send and no socket to carry it. The two no-ops answer
     * true: nothing was meant to go out, so nothing went missing — a `/close` typed in the
     * server log mustn't come back to the composer as if the connection were down.
     */
    fun closeBuffer(networkId: Int?, target: String): Boolean {
        if (networkId == null || target.startsWith(":server:")) return true
        return send(
            buildJsonObject {
                put("type", "close-buffer")
                put("networkId", networkId)
                put("target", target)
            },
        )
    }

    /**
     * Save one buffer's composer draft (`draft-set`), or clear it (`draft-clear`) when there's
     * nothing in it — a reply with no text yet is a draft. The server fans `draft-updated` to
     * every OTHER socket on the account; this one already knows.
     *
     * `reply` always rides, `null` included: a `draft-set` without the key leaves the stored
     * reply as it was, which is for clients that don't know about replies. This one does, so
     * its silence would keep a reply the user cancelled.
     *
     * Returns false when there was no socket to write to. When there was one, `onComplete`
     * says whether the write made it out.
     *
     * Port note: `onComplete` is called on the main thread, before this returns, with whether
     * OkHttp queued the frame — see `send`. On iOS it is called off the main thread once the
     * write has completed.
     */
    fun saveDraft(
        networkId: Int,
        target: String,
        draft: ComposerDraft,
        onComplete: ((ok: Boolean) -> Unit)? = null,
    ): Boolean {
        if (draft.isEmpty) {
            return send(
                buildJsonObject {
                    put("type", "draft-clear")
                    put("networkId", networkId)
                    put("target", target)
                },
                onComplete = onComplete,
            )
        }
        return send(
            buildJsonObject {
                put("type", "draft-set")
                put("networkId", networkId)
                put("target", target)
                put("body", draft.body)
                put("reply", draftReplyRef(draft.reply))
            },
            onComplete = onComplete,
        )
    }

    /**
     * One buffer's draft for `flushDrafts` and `logout`: LurkerKit's
     * `(key: BufferKey, draft: ComposerDraft)` tuple.
     */
    data class KeyedDraft(val key: BufferKey, val draft: ComposerDraft)

    /**
     * Save drafts over HTTP (`POST /api/drafts/flush`) — the way out when the socket is gone and
     * the app is on its way to the background. An empty draft clears. True on a 2xx.
     */
    suspend fun flushDrafts(drafts: List<KeyedDraft>): Boolean {
        val token = token ?: return false
        val code = postDrafts(drafts, session = session, baseURL = baseURL, token = token)
        if (code == 401) reportUnauthorized(sentWith = token)
        return code in 200..<300
    }

    /**
     * Move or drop this buffer's `/clear` marker (lurker-ios#121) — `clear-buffer` /
     * `unclear-buffer`.
     *
     * The server anchors the boundary at the current tail and fans a `buffer-cleared` back to
     * every device, so the reply is what actually moves the marker here.
     *
     * ⚠ No-op for the app-scoped system buffer (networkId null), which has no server-side
     * buffer row to carry a marker — the same guard `closeBuffer` needs. The `:server:` log
     * IS clearable, unlike closing: a network's log is a real buffer with real read state,
     * and hiding a wall of connection noise is exactly what someone would want there.
     * False when it went nowhere.
     */
    fun clearBuffer(networkId: Int?, target: String, undo: Boolean): Boolean {
        if (networkId == null) return false
        return send(
            buildJsonObject {
                put("type", if (undo) "unclear-buffer" else "clear-buffer")
                put("networkId", networkId)
                put("target", target)
            },
        )
    }

    /**
     * Favorite a buffer (`favorite-buffer`). One flag, two UI surfaces: a channel lands
     * under Favorites, a DM under Friends. The server refuses pseudo-buffers and CLOSED
     * buffers, drops any pin the buffer held (one placement per buffer), and echoes the
     * full `favorites-changed` list to every device.
     */
    fun favoriteBuffer(networkId: Int, target: String) {
        send(
            buildJsonObject {
                put("type", "favorite-buffer")
                put("networkId", networkId)
                put("target", target)
            },
        )
    }

    /** Remove a favorite (`unfavorite-buffer`). Echoes `favorites-changed`. */
    fun unfavoriteBuffer(networkId: Int, target: String) {
        send(
            buildJsonObject {
                put("type", "unfavorite-buffer")
                put("networkId", networkId)
                put("target", target)
            },
        )
    }

    /**
     * Rewrite the global favorites order (`reorder-favorites`, id-form only — favorites
     * span networks, so names can't address them). Send the FULL permuted list; a subset
     * floats to the front and would demote everything unmentioned. The server echoes the
     * authoritative `favorites-changed` either way (a stale set snaps this device back).
     * False when it went nowhere: then no echo is coming to settle the order on screen.
     */
    fun reorderFavorites(bufferIds: List<Int>): Boolean {
        return send(
            buildJsonObject {
                put("type", "reorder-favorites")
                putJsonArray("bufferIds") { bufferIds.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    /**
     * Report whether the user can actually SEE the app (lurker#490).
     *
     * This is the gate the server's push decision hangs on: it suppresses push while any
     * of a user's clients is visible, and it only knows because we say so. An open socket
     * is deliberately NOT presence — a backgrounded app holds its socket and must still
     * receive push, which is exactly the case that makes push worth having.
     *
     * Granularity is per-user, not per-buffer: the server tracks "is any client visible",
     * never which buffer is focused. So this says nothing about *where* the user is
     * looking, and lurker-ios#15's "no push for a buffer you're actively looking at"
     * describes something the protocol can't express.
     *
     * Latched so a new socket can re-assert it (see `handleOpen`).
     *
     * `onFlush` fires when the frame is actually written. Backgrounding needs it: that's
     * the one frame sent while the OS is trying to suspend us, and losing it suppresses push
     * until the server's reaper notices (~60s).
     *
     * Port note: OkHttp reports only that the frame was queued, so `onFlush` fires then — see
     * `send`.
     */
    fun setPresence(visible: Boolean, onFlush: (() -> Unit)? = null) {
        presenceVisible = visible
        send(
            buildJsonObject {
                put("type", "presence")
                put("visible", visible)
            },
            onFlush = onFlush,
        )
    }

    // MARK: - Push

    /**
     * Which push transports this server can actually deliver on (lurker#490). A self-hosted
     * server holds no push key of its own and answers `["webpush"]` — knowing that BEFORE asking
     * for notification permission is the difference between "this server doesn't support push"
     * and a permission prompt followed by silence forever.
     *
     * `null` means we couldn't ask (offline, 401, unparseable); `[]` means the server
     * answered and named nothing. Deliberately distinct: collapsing both into `[]` makes a
     * wifi blip during launch indistinguishable from a permanent fact about the server's
     * configuration, and the log line that follows sends you auditing env vars on a box
     * that was fine.
     *
     * An older server (pre-lurker#490) has no `transports` key and correctly reads as `[]` —
     * it answered, and it has no native push.
     */
    suspend fun pushTransports(): List<String>? {
        val token = token ?: return null
        val url = (baseURL + "/api/push/config").toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val (code, data) = try {
            session.await(request)
        } catch (_: IOException) {
            return null
        }
        if (code !in 200..<300) return null
        val body = FrameParser.jsonObject(data) ?: return null
        return body.strings("transports") ?: emptyList()
    }

    /**
     * Fetch a page of recent highlights (`GET /api/highlights`). `before` is the cursor
     * from a prior page's `nextBefore` (null for the first, newest page); the server pages
     * backward by message id. Returns null on any failure so the caller can show an error
     * state — a 401 additionally bounces the session, matching `fetchNetworks`.
     */
    suspend fun fetchHighlights(before: Long? = null, limit: Int = 50): HighlightsPage? {
        val token = token ?: return null
        val components = (baseURL + "/api/highlights").toHttpUrlOrNull()?.newBuilder() ?: return null
        val query = mutableListOf("limit" to limit.toString())
        if (before != null) query.add("before" to before.toString())
        components.encodedQuery(percentEncodedQuery(query))
        val request = Request.Builder().url(components.build()).header("Authorization", "Bearer $token").build()
        try {
            val (code, data) = session.await(request)
            if (code == 401) {
                reportUnauthorized(sentWith = token)
                return null
            }
            val text = data.utf8OrNull()
            if (code !in 200..<300 || text == null) return null
            return FrameParser.parseHighlights(text)
        } catch (_: IOException) {
            return null
        }
    }

    /**
     * Fetch a page of the activity feed (`GET /api/activity`, lurker-ios#183): highlights plus
     * other people's reactions to your lines. `cursor` is the previous page's `next`, null for
     * the first.
     *
     * ⚠ A self-hosted server older than the feed answers 404; that falls back to the plain
     * highlights read, which is everything such a server has to give.
     */
    suspend fun fetchActivity(cursor: FeedCursor? = null, limit: Int = 50): HighlightsPage? {
        val token = token ?: return null
        val components = (baseURL + "/api/activity").toHttpUrlOrNull()?.newBuilder() ?: return null
        val query = mutableListOf("limit" to limit.toString())
        cursor?.beforeMessage?.let { before -> query.add("beforeMessage" to before.toString()) }
        cursor?.beforeReaction?.let { before -> query.add("beforeReaction" to before.toString()) }
        components.encodedQuery(percentEncodedQuery(query))
        val request = Request.Builder().url(components.build()).header("Authorization", "Bearer $token").build()
        try {
            val (code, data) = session.await(request)
            if (code == 401) {
                reportUnauthorized(sentWith = token)
                return null
            }
            if (code == 404) return fetchHighlights(before = cursor?.beforeMessage, limit = limit)
            val text = data.utf8OrNull()
            if (code !in 200..<300 || text == null) return null
            return FrameParser.parseActivity(text)
        } catch (_: IOException) {
            return null
        }
    }

    /**
     * File this install's push device token against the signed-in account.
     * Returns false when the server won't take it, so the caller can stop pretending
     * push works.
     *
     * Port note: the transport is `fcm`, where LurkerKit files an APNs token as `apns`. The
     * server checks the token's shape against the transport it is filed under (lurker's
     * `routes/push.ts`), so this is its rule, not a choice here.
     */
    suspend fun registerDevice(token: String): Boolean {
        val deviceToken = token
        val sessionToken = this.token ?: return false
        val url = (baseURL + "/api/push/devices").toHttpUrlOrNull() ?: return false
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $sessionToken")
            .header("Content-Type", "application/json")
            .post(
                jsonBody(
                    buildJsonObject {
                        put("token", deviceToken)
                        put("transport", "fcm")
                    },
                ),
            )
            .build()
        val (code, _) = try {
            session.await(request)
        } catch (_: IOException) {
            return false
        }
        return code in 200..<300
    }

    /**
     * `onFlush` fires once the frame is written (or has definitively failed). Only
     * `setPresence` uses it: the app needs to hold its background allowance open until the
     * write lands. Called on EVERY exit path — including the early return — because a caller
     * holding an OS resource against it must always get it back.
     *
     * `surfacesFailure` gates whether a socket-write failure becomes a user-facing error.
     * Only a message the user typed sets it: everything else here is machinery — presence,
     * open-buffer, history, mark-read, join — whose write can legitimately fail the instant
     * a backgrounded socket is torn down, and which the reconnect re-drives anyway. Routing
     * those failures to the alert is the "Send failed: Software caused connection abort"
     * modal that pops on foreground on iOS: the OS kills the socket while suspended without
     * firing its failure callback, so the first write on return (a presence re-assert) writes
     * into a dead socket and fails, over a connection the reconnect is about to replace.
     * Returns whether the verb was actually handed to a socket. **False means it went
     * nowhere** — there is no queue behind this, so a caller that shows the user a result
     * has to check. Most callers legitimately don't: a `typing` tag or a `mark-read` that
     * misses a dead socket is re-derived by the next connect, which is why this is
     * discardable. Anything that mutates what's on screen is not in that category.
     *
     * True is "written to a live socket", not "the server acted on it" — a send that fails
     * asynchronously still reports true here, and surfaces through `surfacesFailure`.
     *
     * Port note: OkHttp's `send` has no completion. It answers at once whether the frame was
     * queued, false once the socket is closing, closed or failed (or 16 MiB is already queued),
     * and a frame it queued that then fails to write ends the socket through `onFailure`
     * instead. So `onComplete` reports the queueing, before this returns; `onFlush` fires a
     * turn later, once the socket's queue has drained (`awaitWritten`) — the nearest thing to
     * "written" OkHttp exposes, and what a background-allowance release has to wait for. A
     * refused frame answers false — which LurkerKit can't: OkHttp knows at once that the frame
     * went nowhere — with `onComplete` false. So `surfacesFailure` has nothing to raise here:
     * the caller of a deliberate write hears the false and hands the line back or says so, and
     * a "Send failed" alert on top would report the one failure twice. On iOS the failure comes
     * only later, from the write's completion, which is what `surfacesFailure` is for there.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun send(
        verb: JsonObject,
        surfacesFailure: Boolean = false,
        onFlush: (() -> Unit)? = null,
        onComplete: ((ok: Boolean) -> Unit)? = null,
    ): Boolean {
        val socket = socket
        if (socket == null || socketEnded) {
            onFlush?.invoke()
            return false
        }
        val text = Json.encodeToString(JsonObject.serializer(), verb)
        val queued = socket.send(text)
        onComplete?.invoke(queued)
        scope.task {
            if (queued) awaitWritten(socket)
            onFlush?.invoke()
        }
        return queued
    }

    /**
     * The nearest OkHttp comes to "written to the wire": `queueSize` counts the bytes it has
     * accepted and not yet handed to the connection, and it reaches zero only after the writer
     * thread has sent the frame. Polled, because nothing signals it; bounded, because a socket
     * that has stopped writing will fail on its own and the caller's `onFlush` (the background
     * allowance release, the presence re-assert) must not wait on that.
     */
    private suspend fun awaitWritten(socket: WebSocket) {
        var waited = 0L
        while (socket.queueSize() > 0 && waited < WRITE_DRAIN_DEADLINE_MS) {
            delay(WRITE_DRAIN_POLL_MS)
            waited += WRITE_DRAIN_POLL_MS
        }
    }

    /**
     * Send a verb that answers on `send-result` with its result as `data` — the channel verbs
     * (§6) — and wait for that answer.
     *
     * The answer is correlated by a `clientId` minted here, and ONLY the socket that asked gets
     * it (`wsHub` sends it to that socket alone). So a socket that ends while the question is out
     * has taken the answer with it: `abandonReplies` settles every waiter as `connectionLost`
     * then, and a reconnect never inherits a question it can't be answered on. `timeout` is the
     * backstop for a live socket that simply never says — and has to outlast the server's own
     * wait for the IRC server, or a slow list reads as no answer while the server is still
     * collecting it.
     *
     * `notSent` when there was no socket to write to, or the write itself failed.
     *
     * Port note: mints `android-verb-N` where LurkerKit mints `ios-verb-N`, as
     * `UnsentCorrelator` mints `android-N`. A caller cancelled while waiting ends at once with
     * the cancellation; its entry is still settled — and ignored — by whichever of the three
     * comes first, as LurkerKit's would be.
     */
    suspend fun request(verb: JsonObject, timeout: Duration): VerbReply {
        replySequence += 1
        val clientId = "android-verb-$replySequence"
        val payload = JsonObject(verb + ("clientId" to JsonPrimitive(clientId)))
        return suspendCancellableCoroutine { continuation ->
            pendingReplies[clientId] = continuation
            val sent = send(
                payload,
                onComplete = onComplete@{ ok ->
                    if (ok) return@onComplete
                    scope.task { settleReply(clientId, VerbReply.notSent) }
                },
            )
            if (!sent) {
                settleReply(clientId, VerbReply.notSent)
                return@suspendCancellableCoroutine
            }
            replyTimeouts[clientId] = scope.task {
                delay(timeout.toKotlinDuration())
                settleReply(clientId, VerbReply.noAnswer)
            }
        }
    }

    // MARK: - Channel controls (lurker#727)

    /**
     * `set-topic`: `TOPIC #chan :topic`, an empty string clearing it. Unlike a `raw` TOPIC, the
     * answer says `not-connected` when the network is down rather than dropping the line.
     */
    suspend fun setTopic(networkId: Int, channel: String, topic: String): VerbReply =
        request(
            buildJsonObject {
                put("type", "set-topic")
                put("networkId", networkId)
                put("channel", channel)
                put("topic", topic)
            },
            timeout = verbReplyTimeout,
        )

    /**
     * `set-channel-modes`: validated against the network's `modeSpec` and sent as the fewest MODE
     * lines it allows. ⚠ The server's REFUSAL (482, 467, 478 …) is not on this answer — it
     * arrives as the channel's `error` row.
     */
    suspend fun setChannelModes(networkId: Int, channel: String, changes: List<OutgoingModeChange>): VerbReply {
        val wire = JsonArray(
            changes.map { change ->
                buildJsonObject {
                    put("sign", change.sign.toString())
                    put("letter", change.letter)
                    change.param?.let { put("param", it) }
                }
            },
        )
        return request(
            buildJsonObject {
                put("type", "set-channel-modes")
                put("networkId", networkId)
                put("channel", channel)
                put("changes", wire)
            },
            timeout = verbReplyTimeout,
        )
    }

    /**
     * `get-mode-list`: one list mode, fresh from the IRC server. The replies never reach the
     * server buffer — they come back here, on the answer.
     */
    suspend fun fetchModeList(networkId: Int, channel: String, letter: String): VerbReply =
        request(
            buildJsonObject {
                put("type", "get-mode-list")
                put("networkId", networkId)
                put("channel", channel)
                put("letter", letter)
            },
            timeout = listReplyTimeout,
        )

    /** Answer one waiter, once. Later answers for the same id find nothing and do nothing. */
    private fun settleReply(clientId: String, reply: VerbReply) {
        replyTimeouts.remove(clientId)?.cancel()
        pendingReplies.remove(clientId)?.let { resumeLater(it, reply) }
    }

    /** The socket a question went down is gone, so its answer is too. */
    private fun abandonReplies() {
        val waiting = pendingReplies.values.toList()
        pendingReplies.clear()
        for (timeout in replyTimeouts.values) timeout.cancel()
        replyTimeouts.clear()
        for (continuation in waiting) resumeLater(continuation, VerbReply.connectionLost)
    }

    /**
     * Port note: a `CheckedContinuation` resumed from the main actor runs its waiter on the NEXT
     * main-actor hop, never inline. A `CancellableContinuation` under `Dispatchers.Main.immediate`
     * runs it at once — in the middle of `openSocket`, `close` or `handleClose`, before `socket`
     * is reassigned or the `SocketClosed` frame is out, where a waiter that reacts by reconnecting
     * would nest a second `openSocket` inside the first and strand a socket. So every settlement
     * is deferred a turn, as `task` defers a `Task`.
     */
    private fun resumeLater(continuation: CancellableContinuation<VerbReply>, reply: VerbReply) {
        scope.task { continuation.resume(reply) }
    }

    /**
     * Hand a REST reply on as a frame, but only if it answers the token in use now. A read or a write
     * still out when the session ended (a sign-out, a 401) can answer after the store and the
     * settings cache were cleared, or after a new sign-in, and the departing account's roster,
     * settings or values must not land in them. Every REST reply that becomes a frame comes through
     * here — the settings bootstrap goes out at every start and reconnect, and the phone's time
     * zone write at every bootstrap, so the window is an ordinary one. [reportUnauthorized] is the
     * same rule for a 401.
     */
    private fun deliver(frame: ServerFrame, sentWith: String) {
        if (sentWith != token) return
        onFrame(frame)
    }

    /**
     * Report a 401 as the end of the session, but only if it answered the token in use now.
     * A request still out when the session ended (a sign-out, or a revoke another call already
     * reported) can answer after a new sign-in, and its 401 must not end that session.
     */
    fun reportUnauthorized(sentWith: String) {
        if (sentWith != token) return
        onFrame(ServerFrame.Unauthorized)
    }

    /**
     * Drop the socket and forget the token without revoking server-side. For teardown
     * and the dead-token case (a 401) where there's nothing left to revoke.
     */
    fun close() {
        socket?.close(GOING_AWAY, null)
        abandonReplies()
        socket = null
        token = null
        hasEmittedOpen = false
    }

    /**
     * Drop the socket but keep the token, and report it like any other closure. For a server
     * this build can't talk to (lurker-ios#17): the session is still good, and connects again
     * once one side is updated.
     */
    fun dropSocket() {
        val socket = socket ?: return
        socket.close(GOING_AWAY, null)
        abandonReplies()
        this.socket = null
        hasEmittedOpen = false
        onFrame(ServerFrame.SocketClosed(reason = null, code = null))
    }

    /**
     * The deliberate sign-out. Tears the local session down *immediately* (drops the
     * socket + token) and fires the server-side revoke in the background, so sign-out
     * feels instant even when the server is slow or unreachable. The revoke uses the
     * captured token and never touches this client again, so a subsequent sign-in that
     * mints a fresh session can't be clobbered by an in-flight revoke.
     *
     * `deviceToken` (when push is registered) is deregistered FIRST, in the same task and
     * against the same about-to-die session — a device deregistration needs a live
     * session to authenticate, so it cannot happen after the revoke. Best-effort: if it
     * fails (offline, crash, force-quit) the token stays filed against this account, and
     * the server's native rebind rule is what stops that stranding whoever signs in next
     * on this phone (lurker#490).
     *
     * `drafts` are saved first, over HTTP with the session being ended: a socket write queued
     * now would be cancelled by the `close()` below before it went out.
     *
     * [onRevoked] hears whether the server has given its final answer (lurker-ios#218): the caller
     * keeps the session owed a revoke until it has, and retries it with [revoke].
     *
     * Port note: the background work runs in [scope], so it ends early if the scope is
     * cancelled first; a LurkerKit `Task` runs to completion.
     */
    fun logout(
        deviceToken: String? = null,
        drafts: List<KeyedDraft> = emptyList(),
        onRevoked: ((RevokeOutcome) -> Unit)? = null,
    ) {
        val revokeToken = token
        val base = baseURL
        close()
        if (revokeToken == null) {
            // Nothing to revoke from here; the caller's owed entry, if any, is retried later.
            onRevoked?.invoke(RevokeOutcome.Retry)
            return
        }
        val session = session
        scope.task {
            if (drafts.isNotEmpty()) {
                postDrafts(drafts, session = session, baseURL = base, token = revokeToken)
            }
            if (deviceToken != null) {
                deregisterDevice(session = session, baseURL = base, sessionToken = revokeToken, deviceToken = deviceToken)
            }
            val outcome = revoke(session = session, baseURL = base, token = revokeToken)
            onRevoked?.invoke(outcome)
        }
    }

    /** Whether a revoke needs asking again (lurker-ios#218). */
    enum class RevokeOutcome {
        /**
         * Lurker answered — the token is gone, or was already — or the address isn't a URL at all, so
         * there is nothing that could ever be asked. Not "the server confirmed": don't hang anything
         * on it that needs that.
         */
        Done,

        /** Nothing final yet — no answer, or one from something in front of the server. */
        Retry,
    }

    /**
     * The session this client holds, if any — what a sign-out owes a revoke for (lurker-ios#218).
     * From the client rather than secure storage, whose writes are best-effort.
     */
    val currentSession: PersistedSession?
        get() = token?.let { PersistedSession(server = baseURL, token = it) }

    /**
     * Revoke a session this device signed out of, through this client's HTTP session. Retried by the
     * caller until it's [RevokeOutcome.Done] (lurker-ios#218).
     */
    suspend fun revoke(server: String, token: String): RevokeOutcome =
        revoke(session = session, baseURL = ServerAddress.normalize(server), token = token)

    /**
     * A URL the media player can actually open for a preview's `src`.
     *
     * ⚠⚠ On iOS, `AVURLAsset` cannot carry our Authorization header — the supported way to
     * inject one is an `AVAssetResourceLoaderDelegate`, and the header key that looks like a
     * shortcut is undocumented. So the two cases are handled honestly rather than papered over:
     *
     *   - **An ABSOLUTE url** hands straight to the player, which then gets real streaming and
     *     seeking for free. ⚠⚠ It is a THIRD-PARTY address, not ours: the only caller passes
     *     `preview.url`, because the server stopped minting a `src` for video and audio and the
     *     bytes now stream from the origin. It was the byte cache's own CDN url when this was
     *     written, which is the reading to be careful of — nothing here has been vetted, so it
     *     must never be given a credential, a cache policy, or any trust that belonged to the
     *     old branch. Carrying no Authorization header stopped being a nicety and became the
     *     requirement.
     *   - **A PROXY PATH** is bearer-gated, so the bytes are streamed through the authenticated
     *     path we already have and staged to a file. No seeking until it lands.
     *
     * ⚠ That staging used to be justified here as "bounded by the proxy's 8 MB cap". It is not:
     * 8 MB is the cap for IMAGES, and video and audio get `MAX_MEDIA_PROXY_BYTES` — sixty-four.
     * The reassurance was about a different limit than the branch it was written on.
     *
     * ⚠ The extension is carried over from the server's `mime`, because on iOS AVFoundation
     * sniffs the path: a temp file called `x.tmp` fails to open as an MP4 that plays perfectly
     * as `x.mp4`.
     *
     * Port note: the address comes back as a `String` — an absolute one as `HttpUrl` writes it,
     * or the staged file's `file:` URI — because the two kinds share no URL type here. The
     * absolute one is `HttpUrl.toString()`, never `path` itself: `LinkPreview.isViewable` reads
     * the address with `HttpUrl`, and the player must be handed the host that was checked
     * (LEDGER, T2). So a scheme written in capitals comes back lowercased, where LurkerKit hands
     * it over as written.
     */
    suspend fun playableMediaURL(path: String, mime: String?): String? {
        // ⚠ LOWERCASED, because on iOS `URL.scheme` keeps the case it was written in (measured:
        // `URL(string: "HTTPS://h/a.mp4")?.scheme` is `"HTTPS"`) and `isViewable` — the gate that
        // decides this is worth opening at all — folds case before it compares. Raw, the two
        // disagreed on exactly the addresses `LinkPreviewViewableTests.testSchemeMatchIs-
        // CaseInsensitive` admits: into the gallery, past the tap, missed here, refused by
        // `mediaRequest` as well, and the page dead-ended on "There's nothing to play here" for
        // an address that plays perfectly.
        //
        // Port note: `HttpUrl` reads only http(s), folding the scheme's case, and writes it
        // lowercase.
        val absolute = path.toHttpUrlOrNull()
        if (absolute != null) {
            // ⚠⚠ The same cleartext rule `LinkPreview.isViewable` gates on, asked of the same
            // `LocalNetworking`, because this is the function that hands an address to the
            // player. `isViewable` is the gate — nothing reaches a player page without
            // passing it — and this is the backstop: a second caller, or the two drifting, would
            // otherwise put back the cleartext dead end this change exists to remove. One
            // authority, asked twice, is the only version of that which can't disagree with
            // itself.
            if (absolute.scheme == "http") {
                // Strict about a missing host too, the way `isViewable` is: `http:///a.mp4` has
                // nothing to reach, so there is nothing to permit.
                if (!LocalNetworking.permitsCleartext(host = absolute.host)) return null
            }
            return absolute.toString()
        }
        val request = mediaRequest(path = path, baseURL = baseURL, token = token) ?: return null

        // ⚠⚠ STREAMED to disk, never buffered. This used to read the whole clip into memory
        // and then write a second copy, both on the main thread, justified by a comment claiming
        // "the proxy's 8 MB cap" — which is the cap for IMAGES. Video and audio get
        // MAX_MEDIA_PROXY_BYTES, sixty-four megabytes, so the reassurance was about a different
        // limit than the branch it was written on: 128 MB of main-thread allocation while the
        // viewer animates in.
        val staged = stagedMediaURL(path = path, mime = mime)
        // ⚠ Named from a STABLE digest. On iOS `String.hashValue` is reseeded every launch, so
        // the reuse check could never hit across cold starts: every clip was re-downloaded and
        // re-written while the previous launch's copies stayed on disk under names nothing would
        // ever look for again. (It also trapped on `Int.min` through `abs`.)
        if (staged.exists()) return staged.toURI().toString()
        try {
            val (code, temporary) = mediaSession.download(request)
            if (code !in 200..<300) {
                temporary.delete()
                return null
            }
            staged.delete()
            if (!temporary.renameTo(staged)) {
                // Port note: LurkerKit's failed move leaves the download for iOS to purge with
                // its temporary directory; Android trims its cache only under pressure.
                temporary.delete()
                return null
            }
            return staged.toURI().toString()
        } catch (_: IOException) {
            return null
        }
    }

    /**
     * Delete every staged clip. Sign-out only.
     *
     * ⚠⚠ Without this the departing account's VIDEOS sat on disk in the clear for whoever
     * signed in next — while the same teardown carefully cleared the metadata store, the
     * decoded-image cache and the media cache on the grounds that they were that person's
     * reading history. The heaviest and most identifying artefact was the one nobody deleted.
     * The `mediaSession` split closed this exact hole one layer up; this is the layer below it.
     */
    fun clearStagedMedia() {
        stagedMediaDirectory().deleteRecursively()
    }

    /**
     * The instance's config, or **null if we didn't get an answer**.
     *
     * ⚠⚠ Nullable deliberately. This used to collapse a transport error, a non-2xx, an
     * unparseable body and a genuinely-off flag into the same all-off value — so one 502 or DNS
     * hiccup on the single call at cold launch silently disabled link previews for the whole
     * app session on an instance that has them on, with nothing to notice and no retry. A
     * default is not a statement: the caller needs to tell "the server says no" from "the
     * server didn't say", because only one of those is worth acting on.
     *
     * ⚠ Short timeout, against the session's 60s default. Nothing waits on this to render, and
     * a flag that decides whether to DECORATE messages must never be in a position to delay
     * getting them.
     *
     * ⚠ Null too for an answer to a session that has since ended. The next one may be on another
     * server, and this reply says nothing about it — the rule `reportUnauthorized(sentWith)`
     * keeps for a late 401.
     */
    suspend fun fetchConfig(): InstanceConfig? {
        val sentWith = token
        val request = configRequest(baseURL = baseURL, token = sentWith) ?: return null
        try {
            val (code, data) = session.await(request)
            if (sentWith != token) return null
            return parseConfig(data, code = code)
        } catch (_: IOException) {
            return null
        }
    }

    // MARK: - Link previews

    /**
     * Resolve a batch of URLs to preview descriptors (`POST /api/link-preview/resolve`).
     *
     * Returns whatever the server could answer; a URL it couldn't resolve simply comes back
     * with `status: Unavailable`, and a failed request comes back as an empty list. Both
     * mean "draw nothing", which is the only outcome a message row cares about — a preview
     * is decoration, and an error state in the timeline would be worse than a missing card.
     */
    suspend fun resolveLinkPreviews(urls: List<String>): List<LinkPreview> {
        if (urls.isEmpty()) return emptyList()
        val token = token ?: return emptyList()
        val url = (baseURL + "/api/link-preview/resolve").toHttpUrlOrNull() ?: return emptyList()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(jsonBody(buildJsonObject { putJsonArray("urls") { urls.forEach { add(JsonPrimitive(it)) } } }))
            .build()
        try {
            val (code, data) = session.await(request)
            if (code == 401) {
                reportUnauthorized(sentWith = token)
                return emptyList()
            }
            if (code !in 200..<300) return emptyList()
            return decodePreviews(data)
        } catch (_: IOException) {
            return emptyList()
        }
    }

    /**
     * Fetch bytes for a preview image.
     *
     * `path` is a server-minted value out of a `LinkPreview` — never something built here.
     * A proxy path needs a request of our own rather than a plain image load because the
     * proxy is authenticated and native auth is a Bearer header, not a cookie.
     *
     * Caching is the HTTP cache's job — the server marks these `immutable` with a long
     * max-age, and the token is a pure function of the URL so it always denotes the same bytes.
     *
     * ⚠ That only works because the media cache is sized for it. On iOS, `URLCache.shared`
     * refuses to store any single response larger than ~5% of its capacity, which against the
     * default 10 MB disk budget is roughly 512 KB — under the size of a typical full-width
     * preview image. Left on the default, these were silently re-downloaded on every launch and
     * after every image-cache eviction, and the `max-age` bought nothing at all.
     */
    suspend fun fetchProxiedMedia(path: String): MediaFetch {
        val request = mediaRequest(path = path, baseURL = baseURL, token = token) ?: return MediaFetch.Permanent
        try {
            val (code, data) = mediaSession.await(request)
            if (code in 200..<300) return MediaFetch.Success(data)
            // ⚠⚠ 503 means COME BACK. The byte proxy maps a transient origin refusal — a 429,
            // a 5xx, and in particular opengraph.githubassets.com's 100-request budget, which a
            // channel with a run of GitHub links spends in one burst — to 503 + Retry-After, and
            // keeps 404 for a refused content type. Treating every non-2xx alike is the bug the
            // web had in the other direction: an <img> takes 404 as final and never re-asks, so
            // a minute of throttling became permanently blank images no reload could repair.
            return if (code == 503 || code == 429 || code in 500..<600) MediaFetch.Retryable else MediaFetch.Permanent
        } catch (_: IOException) {
            // A dropped connection says nothing about the file.
            return MediaFetch.Retryable
        }
    }

    // MARK: - Uploads

    /**
     * Upload a prepared file to `POST /api/uploads` and return the stored object's URL.
     * The body is streamed from a disk-backed multipart file (see `MultipartBody`), so the
     * phone never holds the whole payload; `onProgress` reports the device→server leg.
     *
     * `filename`/`mime` are advisory — the server re-derives both from the magic bytes — so
     * a wrong guess here at worst mislabels the history row, never routes around the image
     * pipeline or the metadata scrub. The caller is responsible for having already
     * compressed video to fit; a file over the instance cap comes back as `TooLarge`.
     *
     * Port note: throws `UploadError`, and whatever `MultipartBody.assemble` throws (an
     * `IOException`) as LurkerKit rethrows its error. `onProgress` is called on OkHttp's thread,
     * as LurkerKit's is on the session's delegate queue.
     *
     * Port note: LurkerKit assembles the multipart body on the main actor, under its own thread
     * model; Android moves that disk work — the read and copy of the whole file, and deleting the
     * body afterwards — onto [ioDispatcher], so a 200 MB video doesn't freeze the main thread for
     * the length of the copy. Everything else stays on the caller's thread, which is main: the
     * progress-sink map, the response handling and `reportUnauthorized`. The copy itself isn't
     * interruptible, so a cancel during it lands once it's done, and the body is deleted then —
     * owned by a `finally` from inside the hop, since `withContext` discards what it returns when
     * the caller was cancelled meanwhile.
     */
    suspend fun upload(
        fileURL: File,
        filename: String,
        mime: String,
        progressToken: String,
        onProgress: (Double) -> Unit,
        onServerProgress: (UploadServerProgress) -> Unit,
    ): UploadResponse {
        val token = token ?: throw UploadError.NotSignedIn
        val url = (baseURL + "/api/uploads").toHttpUrlOrNull() ?: throw UploadError.NotSignedIn

        // ⚠ The body is OWNED by the `finally` below from inside the hop, never by `withContext`'s
        // return value. A cancel during the copy is honoured when `withContext` resumes — it throws
        // there and discards the value it was returning — so a body handed back that way was a
        // 200 MB file in the temp directory that nothing would ever delete. Recorded the moment it
        // exists, it's deleted whichever way this ends. `NonCancellable` lets the copy finish
        // rather than leave a half-written file mid-write.
        var assembled: MultipartBody.Assembled? = null
        try {
            withContext(NonCancellable + ioDispatcher) {
                assembled = MultipartBody.assemble(
                    token = progressToken, fileURL = fileURL, filename = filename, mime = mime,
                )
            }
            val body = checkNotNull(assembled)
            currentCoroutineContext().ensureActive()
            // Registered before a byte goes out, and torn down on every exit — including the
            // throws below, which is why it's a `finally` rather than a line after the response.
            // A sink left behind would be a leak keyed on a token nothing will ever send again.
            uploadProgressSinks[progressToken] = onServerProgress
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", body.contentType)
                    // A large body plus the server's own processing (re-encode / scrub / forward
                    // to the provider) can outlast the 60 s default while the connection sits idle
                    // waiting for the response. Give an upload real headroom so a big-but-fine
                    // file isn't killed.
                    .tag(RequestTimeout::class.java, RequestTimeout(Duration.ofSeconds(300)))
                    .post(
                        UploadProgressBody(
                            body.fileURL.asRequestBody(body.contentType.toMediaType()),
                            onProgress = UploadProgressDelegate(onProgress = onProgress)::didSendBodyData,
                        ),
                    )
                    .build()

                val (code, data) = try {
                    session.await(request)
                } catch (error: IOException) {
                    // Port note: `java.util.logging` for `NSLog`, which reaches logcat on Android.
                    logger.warning("[upload] transport error type=${error.javaClass.name} desc=${error.description()}")
                    throw UploadError.Transport(error.description())
                }

                if (code == 401) {
                    // A dead session on an upload is the same fact `fetchNetworks` reports —
                    // bounce to sign-in — but also throw so the in-flight flow stops rather than
                    // "succeeding".
                    reportUnauthorized(sentWith = token)
                    throw UploadError.Unauthorized
                }
                if (code == 413) throw UploadError.TooLarge
                if (code !in 200..<300) {
                    val message = FrameParser.jsonObject(data)?.get("error").asString()
                    throw UploadError.Server(message ?: "Upload failed (HTTP $code)")
                }

                val unreadable = UploadError.Server("The server accepted the upload but its reply was unreadable.")
                val obj = FrameParser.jsonObject(data) ?: throw unreadable
                val id = obj.intOrNull("id") ?: throw unreadable
                val storedURL = obj["url"].asString() ?: throw unreadable
                return UploadResponse(
                    id = id,
                    url = storedURL,
                    mime = obj["mime"].asString(),
                    canDelete = obj.bool("can_delete"),
                    thumbnailUrl = obj["thumbnail_url"].asString(),
                )
            } finally {
                uploadProgressSinks.remove(progressToken)
            }
        } finally {
            assembled?.let { body -> withContext(NonCancellable + ioDispatcher) { body.fileURL.delete() } }
        }
    }

    internal companion object {
        /** OkHttp's close code for "going away" — LurkerKit's `.goingAway`. */
        private const val GOING_AWAY = 1001

        /** The reply to a server's close frame. */
        private const val NORMAL_CLOSURE = 1000

        private val logger: Logger = Logger.getLogger("LurkerClient")

        /**
         * Neither sends nor stores cookies, because this app authenticates with a Bearer token and
         * nothing else. A Lurker server takes a session cookie over a Bearer, and a socket opened
         * on a cookie isn't tied to the token, so revoking the app in Settings would leave it
         * connected.
         *
         * Port note: OkHttp's default jar is already `NO_COOKIES`; it is stated anyway. The
         * timeouts are `URLSessionConfiguration.default`'s 60 s, which the notes in this file
         * count on ("the session's 60s default"), where OkHttp's own default is 10 s.
         */
        internal fun bearerOnlyConfiguration(): OkHttpClient =
            OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(60))
                .build()

        /**
         * Backstop for the transport policy, which sign-in checks first: if the platform blocks
         * a load the check let through (the two definitions of "local" should never drift, but
         * the platform's can move), say what actually happened instead of surfacing the
         * exception's own prose.
         *
         * Port note: on Android the refusal is the network security config's, which OkHttp
         * reports as an `UnknownServiceException` ("CLEARTEXT communication … not permitted");
         * the sentence names Android where LurkerKit's names iOS (and App Transport Security).
         */
        private fun signInFailure(error: IOException): String {
            if (error is UnknownServiceException && error.message?.startsWith("CLEARTEXT") == true) {
                return "Android blocked the connection because this server isn't using HTTPS."
            }
            return "Sign-in failed: ${error.description()}"
        }

        /**
         * What a socket's ending tells the owner:
         *  - a 426 refused the upgrade over the `?v=` this build announced, so the server no
         *    longer serves it (lurker-ios#17) and every reconnect would be refused the same way.
         *    Measured on iOS: a refused upgrade's 426 reads through `task.response` exactly as a
         *    401 does (here, `onFailure`'s response);
         *  - a dead token (`closeEndsSession`) ends the session;
         *  - anything else is a drop, and the owner reconnects.
         */
        fun closeFrame(status: Int?, closeCode: Int, reason: String): ServerFrame {
            if (status == 426) return ServerFrame.Incompatible(Incompatibility.AppTooOld)
            return if (closeEndsSession(status = status, closeCode = closeCode)) {
                ServerFrame.Unauthorized
            } else {
                ServerFrame.SocketClosed(reason = reason, code = status)
            }
        }

        /**
         * Whether a socket ended because the token is dead rather than because the connection
         * dropped. Reconnecting after either of these can only be refused:
         *  - the upgrade was refused with 401, so the Bearer never resolved;
         *  - an open socket was closed with 4001, lurker's `WS_CLOSE_SESSION_REVOKED`
         *    (`shared/wsCloseCodes.ts`): the app was revoked in Settings, or the account
         *    recovered.
         *
         * ⚠ `status` is the UPGRADE's response, so an open socket's close still reads 101. The
         * close code is the only thing that tells a revoke from a drop.
         */
        fun closeEndsSession(status: Int?, closeCode: Int): Boolean =
            status == 401 || closeCode == 4001

        /**
         * A rule as `add-ignore` carries it. Unset dimensions are *omitted* rather than sent as
         * null: the server reads an absent field as "unconstrained", which is the same thing and
         * keeps the frame to what the rule actually says.
         *
         * In the companion so a test can assert the payload without a socket or the main thread
         * — it is pure, and the confinement this class needs is about the socket. It's the
         * encoder half of a shape `FrameParser.parseIgnoreRule` decodes, and the two are held
         * together only by agreeing on these key names, so there is a round-trip test.
         */
        fun ruleJSON(rule: IgnoreRule): JsonObject = buildJsonObject {
            putJsonArray("levels") { rule.levels.forEach { add(JsonPrimitive(it)) } }
            put("patternKind", rule.patternKind.rawValue)
            put("isExcept", rule.isExcept)
            rule.mask?.let { put("mask", it) }
            val channels = rule.channels
            if (channels != null && channels.isNotEmpty()) {
                putJsonArray("channels") { channels.forEach { add(JsonPrimitive(it)) } }
            }
            rule.pattern?.let { put("pattern", it) }
            rule.expiresAt?.let { put("expiresAt", ISOTime.string(date = it)) }
        }

        fun awayFrame(type: String, message: String?, networkId: Int?, all: Boolean?): JsonObject =
            buildJsonObject {
                put("type", type)
                message?.let { put("message", it) }
                networkId?.let { put("networkId", it) }
                all?.let { put("all", it) }
            }

        /**
         * The request itself, against an explicit client — sign-out sends it with the token it
         * is about to revoke. Returns the status code, 0 for no answer; 204 for nothing to send.
         */
        private suspend fun postDrafts(
            drafts: List<KeyedDraft>,
            session: OkHttpClient,
            baseURL: String,
            token: String,
        ): Int {
            val url = (baseURL + "/api/drafts/flush").toHttpUrlOrNull() ?: return 0
            val entries = drafts.mapNotNull { entry ->
                val networkId = entry.key.networkId ?: return@mapNotNull null
                buildJsonObject {
                    put("networkId", networkId)
                    put("target", entry.key.target)
                    put("body", entry.draft.body)
                    put("reply", draftReplyRef(entry.draft.reply))
                }
            }
            if (entries.isEmpty()) return 204
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                // The route reads a raw text body and parses it itself — it's built for
                // `sendBeacon`, which can't send JSON.
                .header("Content-Type", "text/plain;charset=UTF-8")
                .post(jsonBody(JsonObject(mapOf("drafts" to JsonArray(entries))), mediaType = "text/plain;charset=UTF-8"))
                .build()
            return try {
                session.await(request).first
            } catch (_: IOException) {
                0
            }
        }

        /**
         * What the server stores of a pending reply: the line, and whether the Reply put the
         * address in the text. It resolves the rest itself.
         */
        private fun draftReplyRef(reply: PendingReply?): JsonElement {
            if (reply == null) return JsonNull
            return buildJsonObject {
                put("messageId", reply.messageId)
                put("addressed", reply.addressed)
            }
        }

        /**
         * Drop this device's registration. Called BEFORE sign-out revokes the session, since
         * it needs that session to authenticate. Takes an explicit token+base so sign-out can
         * fire it against the session it is about to destroy.
         */
        suspend fun deregisterDevice(
            session: OkHttpClient,
            baseURL: String,
            sessionToken: String,
            deviceToken: String,
        ) {
            val url = (baseURL + "/api/push/devices").toHttpUrlOrNull() ?: return
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $sessionToken")
                .header("Content-Type", "application/json")
                .delete(jsonBody(buildJsonObject { put("token", deviceToken) }))
                .build()
            try {
                session.await(request)
            } catch (_: IOException) {
            }
        }

        /**
         * A revoke's verdict from the response, null status for none at all (offline, DNS, TLS).
         *
         * Only Lurker's own answer counts. `POST /api/auth/logout` takes any bearer and always says
         * `{"ok":true}` — it has no auth check, so a token already gone gets the same answer — which
         * makes every other response someone else's: a captive portal's 200, a WAF's 403, a
         * maintenance 404, an auth gateway's 401 in front of a self-hosted server. None of them says
         * anything about the token, so it's asked again (until `ChatViewModel.revokeRetryWindow`).
         */
        fun revokeOutcome(status: Int?, body: ByteString?): RevokeOutcome {
            if (status == null || status !in 200..<300 || body == null) return RevokeOutcome.Retry
            // `bool` never throws (an `ok` that's an object or an array is just not true) and reads as
            // LurkerKit's `as? Bool` does: an unquoted true, or 1.
            return if (FrameParser.jsonObject(body)?.bool("ok") == true) RevokeOutcome.Done else RevokeOutcome.Retry
        }

        /** The sign-out request, for both the revoke and the password-era session's end. */
        fun logoutRequest(baseURL: String, token: String): Request? {
            val url = (baseURL + "/api/auth/logout").toHttpUrlOrNull() ?: return null
            return Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody())
                .build()
        }

        private suspend fun revoke(session: OkHttpClient, baseURL: String, token: String): RevokeOutcome {
            // An address that isn't a URL can never be asked.
            val request = logoutRequest(baseURL = baseURL, token = token) ?: return RevokeOutcome.Done
            val (code, data) = try {
                session.await(request)
            } catch (_: IOException) {
                return RevokeOutcome.Retry
            }
            return revokeOutcome(status = code, body = data)
        }

        /**
         * Ends a session from the password sign-in this app had before OAuth. Best effort; the
         * token is already off the device.
         *
         * Port note: LurkerKit sends this through `URLSession.shared` and then empties the shared
         * cookie jar, because on lurker.chat that jar holds the cell session the proxy minted for
         * the old token, and that's the session the cell has to delete. There is no shared jar
         * here: Android's password sign-in kept no cookies (its client had no jar), so there is
         * none to send or to empty, and the request goes through the `session` and `scope` the
         * caller passes. Not run off main as LurkerKit's `Task.detached` is: its reason — a
         * static function with no actor to inherit — has no counterpart, and nothing here blocks.
         */
        fun endPasswordSession(server: String, token: String, scope: CoroutineScope, session: OkHttpClient) {
            val request = logoutRequest(baseURL = ServerAddress.normalize(server), token = token) ?: return
            scope.task {
                try {
                    session.await(request)
                } catch (_: IOException) {
                }
            }
        }

        /**
         * Where a proxied clip is staged for playback.
         *
         * ⚠ On iOS, `Library/Caches`, not `temporaryDirectory` — the previous comment claimed
         * the former and used the latter. This is a copy of something the server will hand over
         * again, so the OS reclaiming it under pressure is exactly right.
         */
        private fun stagedMediaURL(path: String, mime: String?): File {
            // FNV-1a: stable across launches, unlike `String.hashValue`, and enough to name a
            // file.
            var hash: ULong = 0xcbf29ce484222325uL
            for (byte in path.toByteArray(Charsets.UTF_8)) {
                hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
            }
            val name = hash.toString(36) + "." + fileExtension(mime)
            return File(stagedMediaDirectory(), name)
        }

        /**
         * Port note: under `java.io.tmpdir`, which on Android is the app's cache directory — the
         * counterpart of `Library/Caches`, reclaimed by the OS under pressure — as
         * `MultipartBody` writes there.
         */
        private fun stagedMediaDirectory(): File {
            val directory = File(System.getProperty("java.io.tmpdir"), "preview-media")
            directory.mkdirs()
            return directory
        }

        /**
         * ⚠ Only what the player can actually open. webm and ogg are deliberately absent: the
         * server will happily proxy them (`kindForContentType` passes any `video/…` type) and iOS
         * cannot decode either, so they fall through to `bin` and the caller's "can't play this"
         * path — which offers the browser — rather than a player that spins.
         *
         * Port note: the list is iOS's, kept as LurkerKit has it; what Android's player decodes
         * is a wider set, and widening this is a change to make in LurkerKit first.
         */
        private fun fileExtension(mime: String?): String =
            when (mime?.lowercase()) {
                "video/mp4" -> "mp4"
                "video/quicktime" -> "mov"
                "video/x-m4v" -> "m4v"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/x-m4a" -> "m4a"
                "audio/aac" -> "aac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/flac", "audio/x-flac" -> "flac"
                else -> "bin"
            }

        /**
         * The request for `/api/config`.
         *
         * ⚠⚠ It carries the bearer token, even though the server calls this endpoint "public,
         * unauthenticated bootstrap config the client can read before login". That is true of a
         * self-hosted instance and false of a hosted one: on lurker.chat the control plane
         * proxies `/api/…` to a CELL, and it works out which cell from the caller's session. An
         * anonymous request has nothing to route on, so it comes back `401 {"error":"not
         * routable"}` — which this client correctly reads as "no answer", leaving previews off
         * forever on precisely the deployment most people use. The browser never noticed because
         * its fetch carries the session cookie; native auth is a Bearer header and has to be
         * asked for.
         *
         * ⚠ Harmless where it genuinely is public — an instance that ignores the header returns
         * the same body — so there is one code path rather than a deployment test the client
         * would have to get right.
         *
         * In the companion for the same reason as `mediaRequest`: the property worth asserting is
         * invisible through the awaiting method, which can only answer "no flags".
         */
        fun configRequest(baseURL: String, token: String?): Request? {
            val url = (baseURL + "/api/config").toHttpUrlOrNull() ?: return null
            val builder = Request.Builder()
                .url(url)
                // Nothing waits on this to render, and a flag deciding whether to DECORATE
                // messages must never be in a position to delay getting them.
                .tag(RequestTimeout::class.java, RequestTimeout(Duration.ofSeconds(10)))
            if (token != null) builder.header("Authorization", "Bearer $token")
            return builder.build()
        }

        /**
         * `/api/config` → the instance's config, or **null for "that wasn't an answer"**.
         *
         * ⚠ An absent field IS an answer: an older instance without the feature, or without the
         * version fields (lurker-ios#17), which states no version rather than version 0. Only a
         * failure to obtain a well-formed response is unknown — and the difference decides
         * whether the caller acts on the result or keeps what it had.
         *
         * In the companion per `mediaRequest`'s note: this is the half worth asserting and it
         * is unreachable through the awaiting method without a live server.
         */
        fun parseConfig(data: ByteString, code: Int): InstanceConfig? {
            if (code !in 200..<300) return null
            val body = FrameParser.jsonObject(data) ?: return null
            val features = body["features"] as? JsonObject
            return InstanceConfig(
                features = InstanceFeatures(linkPreviews = features?.bool("linkPreviews") == true),
                protocolVersion = body.intOrNull("protocolVersion"),
                minProtocolVersion = body.intOrNull("minProtocolVersion"),
            )
        }

        /** `JSONDecoder()` ignores keys it does not know; kotlinx has to be told to. */
        private val previewJson = Json { ignoreUnknownKeys = true }

        /**
         * The resolve response's descriptors, skipping any this build cannot understand.
         *
         * ⚠⚠ Decoded ELEMENT BY ELEMENT, not as `List<LinkPreview>` in one go. `kind` and
         * `status` are non-optional raw-value enums with no unknown case, so a single descriptor
         * carrying a value this build doesn't know would throw inside the array decode and take
         * the whole batch of twenty with it — indistinguishable, one layer up, from a transport
         * failure. The store would then arm all twenty for retry, and because a decode failure
         * is deterministic every retry fails identically: nineteen perfectly good previews never
         * rendering, and all twenty polling to the 300s ceiling forever.
         *
         * It cannot fire against today's server, whose union matches the enum exactly. It is
         * pinned because this is a self-hosted product where operators upgrade on their own
         * schedule and store builds lag, and the repo treats that skew as normal elsewhere.
         *
         * ⚠ In the companion for the same reason as `mediaRequest`: the behaviour worth asserting
         * is invisible through `resolveLinkPreviews`, which only answers `List<LinkPreview>` and
         * cannot be reached without a live session. A test that rebuilt this envelope itself
         * would assert a fact about `decodeEach` rather than about what this client does with it
         * — which is exactly what the first version of its test did, and the revert drill said
         * so.
         *
         * Port note: the envelope is read by `FrameParser`, which bounds its nesting, and each
         * element by `Json.decodeEach` (LurkerKit's `FailableDecodable`). LurkerKit repairs the
         * bytes first, because `JSONDecoder` refuses the whole document for a description the
         * server capped mid-emoji (a lone surrogate escape); kotlinx reads one, so nothing is
         * repaired here and the lone half stays in the description (PORTING.md, JSON).
         */
        fun decodePreviews(data: ByteString): List<LinkPreview> {
            val envelope = FrameParser.jsonObject(data) ?: return emptyList()
            val previews = envelope["previews"] as? JsonArray ?: return emptyList()
            return previewJson.decodeEach(LinkPreview.serializer(), previews)
        }

        /**
         * Resolve a `LinkPreview`'s `src`/`thumb` into a request.
         *
         * ⚠⚠ THE VALUE IS OPAQUE, and this is the only place that may interpret it. The server
         * mints it and documents it as a string a client never constructs or parses; today it is
         * either a proxy path (`/api/link-preview/media/<token>`) or, when the instance has a
         * bucket-backed byte cache configured, an absolute URL on that bucket's public CDN. Both
         * arrive in the same field and nothing distinguishes them on the wire.
         *
         * ⚠⚠ Concatenating unconditionally is what this replaces, and it did not fail loudly:
         * `baseURL + "https://cdn.example.com/..."` yields `https://instance.examplehttps://...`,
         * which the URL parser rejects, so `fetchProxiedMedia` returned nothing and the image
         * loader latched the path into `failed` — every cached preview permanently blank for the
         * rest of the session, with no error surfaced anywhere.
         *
         * ⚠⚠ AN ABSOLUTE URL GETS NO BEARER TOKEN. It is a third-party host by construction, and
         * the HTTP client sends manually-set headers to whatever it is given — so sending one here
         * would put the user's session token in a CDN operator's access log for every image. The
         * header belongs only to requests aimed at this instance.
         *
         * ⚠ In the companion, taking the base and the token rather than reading them off the
         * client. Both are private state on a main-confined class, so an instance method could
         * only be exercised by driving a whole configured client — and the two properties worth
         * asserting (an absolute URL is not concatenated onto the base, and never carries the
         * token) are invisible from `fetchProxiedMedia`, which only answers `MediaFetch`. A wrong
         * URL and an unreachable server look identical through it. The function touches no
         * client state, so confining it buys nothing and costs testability.
         *
         * Port note: "has a scheme" is decided as `URL(string:)` decides it — a letter, then
         * letters, digits, `+`, `-` or `.`, then a colon — and the scheme is compared as written,
         * as LurkerKit compares it. Only then is the address read, by `HttpUrl`.
         */
        fun mediaRequest(path: String, baseURL: String, token: String?): Request? {
            val scheme = schemePattern.find(path)?.groupValues?.get(1)
            if (scheme != null) {
                if (scheme != "https" && scheme != "http") return null
                val absolute = path.toHttpUrlOrNull() ?: return null
                return Request.Builder().url(absolute).build()
            }
            // ⚠⚠ The relative branch must be genuinely relative TO THE ROOT, or the concatenation
            // moves the host. `baseURL + "api/media/x"` is `https://chat.exampleapi/media/x`, whose
            // host is `chat.exampleapi` — a domain someone else can register — and this is the
            // branch that attaches the Bearer token. Nothing the current server mints looks like
            // that, which is exactly why it needs stating: it is the one path where a change at
            // the other end of the wire turns a cosmetic bug into handing out the session token.
            if (!path.startsWith("/")) return null
            if (token == null) return null
            val url = (baseURL + path).toHttpUrlOrNull() ?: return null
            return Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        }

        private val schemePattern = unicodeRegex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

        /**
         * The socket's address. It always announces the protocol version this build speaks
         * (lurker-ios#17): the server treats a missing `?v` as current, so a build that left it
         * off could never be told it's too old. `since > 0` resumes from that event id.
         *
         * In the companion so the address can be asserted without opening a socket.
         *
         * Port note: a `String`, because `HttpUrl` cannot hold a `ws:`/`wss:` address; OkHttp's
         * `Request.Builder.url(String)` reads one. Null where it couldn't — anything but a ws(s)
         * address OkHttp can parse — where `URL(string:)` takes any scheme and the socket then
         * fails; OkHttp would throw.
         */
        fun socketURL(baseURL: String, since: Long): String? {
            var address = wsBase(baseURL) + "/ws?v=${ProtocolVersion.spoken}"
            if (since > 0) address += "&since=$since"
            val http = when {
                address.regionMatches(0, "ws:", 0, 3, ignoreCase = true) -> "http:" + address.substring(3)
                address.regionMatches(0, "wss:", 0, 4, ignoreCase = true) -> "https:" + address.substring(4)
                else -> return null
            }
            return if (http.toHttpUrlOrNull() != null) address else null
        }

        /**
         * http → ws, https → wss. Replacing only the leading `http` turns the trailing `s`
         * of `https` into `wss` for free.
         */
        private fun wsBase(base: String): String = unicodeRegex("^http").replaceFirst(base, "ws")

        // MARK: - Channel controls (lurker#727)

        /**
         * The server waits up to 30 s on the IRC server for a list; the wait here has to outlast
         * it, or a slow list reads as no answer while the server is still collecting it.
         */
        private val listReplyTimeout: Duration = Duration.ofSeconds(35)

        /** The other two answer as soon as their lines are written. */
        private val verbReplyTimeout: Duration = Duration.ofSeconds(10)

        /**
         * A JSON request body, written as bytes so the media type goes out exactly as given:
         * OkHttp's `String.toRequestBody` would append `; charset=utf-8`.
         */
        private fun jsonBody(body: JsonObject, mediaType: String = "application/json"): RequestBody =
            Json.encodeToString(JsonObject.serializer(), body).encodeToByteArray().toRequestBody(mediaType.toMediaType())
    }
}

/**
 * `URLRequest.timeoutInterval`, for the two requests that set one.
 *
 * Port note: OkHttp keeps timeouts on the client, so a request that wants its own carries it as a
 * tag and [await] runs it on a client with that timeout for connecting, reading and writing —
 * each an idle timeout, as `timeoutInterval` is.
 */
private class RequestTimeout(val interval: Duration)

/**
 * `try await session.data(for:)`: the status code and the whole body, or an `IOException` for a
 * transport failure. Cancelling the caller cancels the call.
 *
 * Port-only — the one way this file makes an HTTP request. The body is read on OkHttp's thread.
 */
private suspend fun OkHttpClient.await(request: Request): Pair<Int, ByteString> {
    val client = request.tag(RequestTimeout::class.java)?.let { timeout ->
        newBuilder()
            .connectTimeout(timeout.interval)
            .readTimeout(timeout.interval)
            .writeTimeout(timeout.interval)
            .build()
    } ?: this
    return suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val answer = try {
                        response.use { it.code to (it.body?.byteString() ?: ByteString.EMPTY) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(answer)
                }
            },
        )
    }
}

/**
 * `try await session.download(for:)`: the status code and a temporary file holding the body,
 * streamed there on OkHttp's thread — never held in memory. The caller moves or deletes the
 * file.
 *
 * Port-only, beside [await] because a download is not a REST read: the one place a body is not
 * buffered whole.
 */
@OptIn(ExperimentalCoroutinesApi::class) // `resume(value, onCancellation)`, to delete a file nobody will take
private suspend fun OkHttpClient.download(request: Request): Pair<Int, File> =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    var temporary: File? = null
                    try {
                        response.use {
                            val file = File.createTempFile("lurker-download-", ".tmp")
                            temporary = file
                            file.sink().buffer().use { sink ->
                                it.body?.source()?.let { source -> sink.writeAll(source) }
                            }
                            val code = it.code
                            continuation.resume(code to file) { _ -> file.delete() }
                        }
                    } catch (e: IOException) {
                        temporary?.delete()
                        continuation.resumeWithException(e)
                    }
                }
            },
        )
    }

/** An error's `localizedDescription`, near enough: its message, or its type where it has none. */
private fun Throwable.description(): String = message ?: toString()

/** How often `awaitWritten` looks at the socket's queue, and how long it is prepared to look. */
private const val WRITE_DRAIN_POLL_MS = 10L
private const val WRITE_DRAIN_DEADLINE_MS = 2_000L
