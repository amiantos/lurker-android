// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.amiantos.lurkerkit.client.HistoryMode
import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.client.InstanceFeatures
import net.amiantos.lurkerkit.client.LinkPreviewStore
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.OAuth
import net.amiantos.lurkerkit.client.PKCE
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadError
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.client.UploadResponse
import net.amiantos.lurkerkit.client.UploadServerProgress
import net.amiantos.lurkerkit.client.Uploads
import net.amiantos.lurkerkit.client.VerbReply
import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.push.AppPushUnavailable
import net.amiantos.lurkerkit.push.DeviceKeys
import net.amiantos.lurkerkit.push.PushRoute
import net.amiantos.lurkerkit.push.RelayPush
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.CertificateExport
import net.amiantos.lurkerkit.model.CertificateResult
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.ChannelName
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.DraftSync
import net.amiantos.lurkerkit.model.Drafts
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.model.HistoryCountBy
import net.amiantos.lurkerkit.model.JoinNotice
import net.amiantos.lurkerkit.model.MediaFetch
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeListResult
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkPresets
import net.amiantos.lurkerkit.model.NetworkSaveResult
import net.amiantos.lurkerkit.model.OutgoingModeChange
import net.amiantos.lurkerkit.model.PendingJoins
import net.amiantos.lurkerkit.model.PendingOpens
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.PreviewSelection
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.SearchQuery
import net.amiantos.lurkerkit.model.ServerAddress
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.TypingSignal
import net.amiantos.lurkerkit.model.UnsentCorrelator
import net.amiantos.lurkerkit.model.UploadsFilter
import net.amiantos.lurkerkit.model.UploadsPage
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.store.SettingsCache
import net.amiantos.lurkerkit.store.SocketStatus
import net.amiantos.lurkerkit.store.UnsentLine
import net.amiantos.lurkerkit.support.CurrentValueSubject
import net.amiantos.lurkerkit.support.Result
import net.amiantos.lurkerkit.support.task
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Owns the client + store for the app's lifetime. The client does I/O and emits frames;
 * the store folds them into `state`; the UI observes `statePublisher`. This is the seam
 * the foundation issues hook into.
 *
 * Owns two lifecycles:
 *  - **session (lurker-ios#3):** restore a persisted token on launch; a mid-session 401
 *    bounces cleanly to sign-in.
 *  - **connection (lurker-ios#4):** reconnect with backoff when the socket drops, resuming from
 *    the highest event id seen (`?since=`); and on return-to-foreground, reconnect a socket
 *    that died while backgrounded. The app feeds foreground/background via
 *    `enterForeground()`/`enterBackground()` (keeping this module Android-free).
 *
 * Confined to the main thread, as LurkerKit's `@MainActor` class is: every method, and
 * [scope]'s dispatcher, run there.
 *
 * Port note — the platform seams:
 *  - Every `Task { … }` is a launch in [scope] through `task`, which keeps a Swift `Task`'s
 *    promise that none of its body runs before the statement that started it returns. The app
 *    passes a scope on `Dispatchers.Main.immediate`; tests pass a `TestScope`. LurkerKit's
 *    `[weak self]`s are dropped — in the draft flush, the write completion, `lifecycle`, `dcc`,
 *    the join timeout, the background flush, the reconnect timer and the preview resolver: a
 *    launched coroutine holds the view model strongly, and what ends its work instead is
 *    [scope] — cancelled, it runs nothing more. A stored, cancellable `Task` is a [Job].
 *  - The three stores take the platform storage `:app` implements ([SecureStorage],
 *    `DefaultsStorage`), so none of them has a default here.
 *  - The Combine subjects are flows: `CurrentValueSubject` is the port-only class of that name
 *    (a replaying `SharedFlow`, as `LurkerStore`'s), and `channelEvents` a `SharedFlow`.
 */
class ChatViewModel(
    private val scope: CoroutineScope,
    private val sessions: SessionStore,
    /**
     * Last-known setting values, so behavior is right from the first frame rather than from
     * whenever the bootstrap fetch lands — see `SettingsCache`.
     */
    private val settingsCache: SettingsCache,
    private val oauthClients: OAuthClients,
    /**
     * Port note: not in LurkerKit, whose `CommandParser` formats a date itself. The kit never
     * formats a date for display here (PORTING.md), so the app hands in its long-lived,
     * locale-aware "when the ignore rule lapses" formatter once, and `send` passes it to the
     * parser on every line; only `/ignore` and `/unignore` ever call it.
     */
    private val formatExpiry: (Instant) -> String,
    /**
     * The client every REST call and the socket go through; the media client is built from it.
     * Also what ends a password-era session (`restoreSession`).
     */
    private val httpClient: OkHttpClient = LurkerClient.bearerOnlyConfiguration(),
    /** Where preview bytes are cached on disk, or null for none — see `LurkerClient`. */
    mediaCacheDirectory: File? = null,
    /**
     * Whether the process starts with the app on screen. Port note: not in LurkerKit, where nothing
     * launches the app without it becoming active (an alert push never does). An Android process
     * can start with no activity at all — FCM starts it for every push that arrives while it's dead
     * (lurker-android#16) — and a restore that connected there would open a socket and pull a
     * snapshot for a phone in someone's pocket, then keep reconnecting it. False holds a restored
     * session's connect until the first [enterForeground].
     */
    startsInForeground: Boolean = true,
) {

    /** Where the account stands with the server. */
    enum class SessionState {
        LoggedOut,
        LoggingIn,
        LoggedIn,
    }

    private val store = LurkerStore()

    /**
     * Port note: a plain property, where LurkerKit's is `lazy` only so its closure can capture
     * `self`; a Kotlin initializer can.
     */
    private val client = LurkerClient(
        scope = scope,
        onFrame = { handle(it) },
        httpClient = httpClient,
        mediaCacheDirectory = mediaCacheDirectory,
    )

    private val sessionSubject = CurrentValueSubject(SessionState.LoggedOut)
    private val statusSubject = CurrentValueSubject<String?>(null)

    private var reconnectTask: Job? = null
    private var reconnectAttempt = 0
    private var isForeground = startsInForeground
    private var backgroundedAt: Instant? = null

    /** A restored session whose first connect waits for [enterForeground] (see `startsInForeground`). */
    private var startDeferred = false

    /** Buffer keys with an older-history page in flight, so scroll-up can't spam requests. */
    private val loadingOlder = mutableSetOf<String>()

    /**
     * Buffer keys with a newer-history page in flight (lurker-ios#45), so scroll-down can't spam
     * requests.
     */
    private val loadingNewer = mutableSetOf<String>()

    /** Highest message id we've already marked read per buffer, to dedupe mark-read spam. */
    private val lastMarked = mutableMapOf<String, Long>()

    /**
     * Fired when a buffer changes names (protocol §9.7), AFTER this model's
     * own bookkeeping rekeys and BEFORE the store applies the frame. The app
     * layer uses it to rewrite persisted preference keys (favorites,
     * recents, last-buffer) in place — state LurkerKit has no business
     * touching directly.
     */
    var onBufferRenamed: ((from: BufferKey, to: BufferKey) -> Unit)? = null

    /**
     * The server refused a line typed in `key`, and it is waiting in `state.unsent`.
     *
     * ⚠ A nudge, not the delivery. The screen for `key` may be on top (fill the composer now) or
     * may not exist (it drains on next open, from `takeUnsent`) — this only says the hold
     * changed, because a screen cannot tell "a line arrived for me" from a whole-state
     * republish by diffing `ChatState`.
     */
    var onSendRefused: ((key: BufferKey) -> Unit)? = null

    /**
     * A join this device asked for landed, and the asker wanted to be taken there
     * (lurker-ios#57). The app navigates. Fired with the stored row's key, in the server's
     * spelling of the name.
     */
    var onJoinOpened: ((key: BufferKey) -> Unit)? = null

    /**
     * A join this device asked for didn't happen: refused, unanswered, or never sent
     * (lurker-ios#57). The app shows it as a toast.
     */
    var onJoinNotice: ((notice: JoinNotice) -> Unit)? = null

    /**
     * A buffer this device opened has a row to go to: a DCC chat opened or accepted
     * (lurker#270), or a DM from a profile, a Friends row or `/msg` (lurker-ios#201). The app
     * navigates, the same move as `onJoinOpened`. Fired with the stored row's key.
     */
    var onBufferOpened: ((key: BufferKey) -> Unit)? = null

    /**
     * Someone invited us to a channel we aren't in (lurker#261). The app offers a Join, which is
     * `requestJoin(…, opens = true)`; the system buffer already holds the line, so a prompt that
     * is never answered loses nothing. Not fired for a channel we're already in.
     */
    var onInvited: ((networkId: Int, channel: String, from: String) -> Unit)? = null

    /**
     * Where each raw line this device sent was typed, and when, by network and verb, oldest
     * first, until a 421 names that verb — see `noteUnknownCommand`. A queue rather than the
     * latest send: `/foo` in #a then in #b answers twice, in that order, and each answer belongs
     * where its line was typed. Entries past `unknownCommandWindow` are dropped as it's touched.
     */
    private val rawCommandOrigins = HashMap<String, List<RawCommandOrigin>>()

    private class RawCommandOrigin(val key: BufferKey, val sentAt: Instant)

    /**
     * The buffer we just opened, until its row exists — see `PendingOpens`. Giving up is quiet.
     * A DCC chat's notices say what happened, in a buffer the list will show; a DM's refused
     * `open-buffer` comes back as the server's `error` frame, which already says so.
     */
    private val pendingOpens = PendingOpens()

    /** The joins waiting on an answer — see `PendingJoins`, where the rules live and can be tested. */
    private val pendingJoins = PendingJoins()

    // MARK: - What the UI observes

    /**
     * Live lines as they arrive, and each new socket's snapshot — for the channel settings screens
     * (lurker#727), which patch an open list from live `MODE ±b/e/I/q` rows and read a Save's
     * refusal off the channel's `error` rows.
     *
     * ⚠⚠ Taken off the frames rather than out of `state.messages`: a DETACHED buffer (the
     * reader jumped back into history) holds live lines out of its log, and those screens still
     * need every one. Sent after the store has applied the frame.
     */
    val channelEvents: SharedFlow<ChannelEvent> get() = channelEventsSubject.asSharedFlow()

    /**
     * Port note: a `PassthroughSubject` — no replay, so with no collector an event goes nowhere —
     * whose buffer is unbounded rather than a fixed few. Its two consumers on iOS (the channel
     * settings and mode list screens) patch an open list from every live MODE row and owe a
     * fetch on every `resynced`, and `receive(on:)` there buffers without limit; one lost here
     * would leave a list silently wrong. A whole connect burst is applied in one stretch of the
     * main thread, before any collector on it can run, so a fixed buffer is exactly what a
     * busy channel would overflow.
     */
    private val channelEventsSubject = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = Int.MAX_VALUE - 1)

    sealed interface ChannelEvent {
        data class Line(val key: BufferKey, val message: Message) : ChannelEvent

        /**
         * A new socket's snapshot has been applied, so the store's word on each network is this
         * socket's now. A list fetched before the drop can't be patched up to date — the gap
         * arrives as backlog rather than live rows — and this is the moment to decide whether
         * asking again can work.
         */
        data object Resynced : ChannelEvent
    }

    val state: ChatState get() = store.state
    val statePublisher: SharedFlow<ChatState> get() = store.statePublisher

    val session: SessionState get() = sessionSubject.value
    val sessionPublisher: SharedFlow<SessionState> get() = sessionSubject.flow

    /**
     * Transient status/error text for the login screen (why a sign-in failed, or why a
     * session ended).
     */
    val statusPublisher: SharedFlow<String?> get() = statusSubject.flow

    // MARK: - Actions

    /**
     * Sign in through the server's approval page, then load the roster and open the socket,
     * saving the session so the next launch reconnects without signing in again. Returns
     * whether it worked; on failure `statusPublisher` carries the reason. Closing the browser
     * sheet, or choosing Deny, isn't a failure worth a message.
     *
     * `authorize` shows the page and returns the address the page sent the browser to, or
     * null when the sheet closed first. The app supplies it, because the browser sheet is the
     * platform's.
     *
     * Port note: `authorize` takes the page as an `HttpUrl` and answers with the callback as
     * text, because `OAuth.callback` reads a `String` (the app's custom-scheme callback is no
     * `HttpUrl`).
     */
    suspend fun signIn(
        server: String,
        appName: String,
        authorize: suspend (HttpUrl) -> String?,
    ): Boolean {
        sessionSubject.value = SessionState.LoggingIn
        statusSubject.value = null
        // Port-only: a new attempt voids one a killed process left in the browser, even if this one
        // fails before it saves its own.
        sessions.clearPendingSignIn()
        // A restored session that never came to the foreground was signed out since; this one
        // connects itself, and a held start for the old one must not open a second socket.
        startDeferred = false
        // The previous session's media purge finishes before this one can cache or stage anything.
        awaitMediaPurge()
        val server = ServerAddress.normalize(server)
        // The transport policy runs before any request, so its verdict is sign-in copy rather
        // than a failed connect (lurker-ios#29).
        ServerAddress.rejection(server)?.let { reason -> return signInFailed(reason) }

        // A saved registration is checked before it's used, because a server that lost it would
        // say so on the approval page, where the app can't hear it (see `OAuthClients`).
        var saved = oauthClients.clientId(server)
        if (saved != null && client.isClientKnown(server = server, clientId = saved) == false) {
            oauthClients.forget(server)
            saved = null
        }
        val clientId: String
        if (saved != null) {
            clientId = saved
        } else {
            when (val registration = client.registerApp(server = server, name = appName)) {
                is OAuth.Registration.Registered -> {
                    oauthClients.save(registration.clientId, server = server)
                    clientId = registration.clientId
                }
                is OAuth.Registration.Failure -> return signInFailed(registration.message)
            }
        }

        val pkce = PKCE()
        val state = OAuth.randomString()
        val page = OAuth.authorizeURL(server = server, clientId = clientId, challenge = pkce.challenge, state = state)
            ?: return signInFailed("That server URL doesn't look right.")
        // Port-only: kept until the page answers, so a redirect that outlives this process can still
        // finish (`resumeSignIn`).
        sessions.savePendingSignIn(PendingSignIn(server = server, clientId = clientId, state = state, verifier = pkce.verifier))
        val callback = authorize(page)
        sessions.clearPendingSignIn()
        if (callback == null) return signInFailed(null)
        return finishSignIn(server = server, clientId = clientId, state = state, verifier = pkce.verifier, callback = callback)
    }

    /**
     * Finish a sign-in whose redirect arrived with nothing waiting for it: the process that opened
     * the approval page was killed while it was up, and the redirect started this one. Returns the
     * server it signed in to, or null; on failure `statusPublisher` carries the reason. (The app
     * remembers the server on success, and here it never saw what was typed.)
     *
     * Ignored while signed in or already signing in: nothing here may end a live session, and an
     * attempt in flight has its own redirect coming.
     *
     * Port-only: see `PendingSignIn`.
     */
    suspend fun resumeSignIn(callback: String): String? {
        if (session != SessionState.LoggedOut) return null
        // A failure just shown (a duplicate redirect after a refused exchange) keeps its reason.
        val pending = sessions.pendingSignIn()
        if (pending == null) {
            signInFailed(statusSubject.value ?: "That sign-in has already ended. Try again.")
            return null
        }
        // Kept for a redirect that isn't this attempt's (any app or page can open the scheme), so
        // the real one can still finish; spent once the redirect is its answer.
        if (OAuth.callback(callback, state = pending.state) == OAuth.Callback.Invalid) {
            signInFailed("Sign-in didn't finish. Try again.")
            return null
        }
        sessions.clearPendingSignIn()
        // `signIn`'s own setup, step for step: a change to one belongs in both.
        sessionSubject.value = SessionState.LoggingIn
        statusSubject.value = null
        startDeferred = false
        awaitMediaPurge()
        val signedIn = finishSignIn(
            server = pending.server,
            clientId = pending.clientId,
            state = pending.state,
            verifier = pending.verifier,
            callback = callback,
        )
        return if (signedIn) pending.server else null
    }

    /** The approval page's answer, traded for a token and the session it opens. */
    private suspend fun finishSignIn(server: String, clientId: String, state: String, verifier: String, callback: String): Boolean {
        val code: String
        when (val answer = OAuth.callback(callback, state = state)) {
            is OAuth.Callback.Code -> code = answer.code
            OAuth.Callback.Denied -> return signInFailed(null)
            OAuth.Callback.Invalid -> return signInFailed("Sign-in didn't finish. Try again.")
        }

        when (
            val grant = client.exchangeCode(server = server, clientId = clientId, code = code, verifier = verifier)
        ) {
            is OAuth.TokenGrant.Token -> {
                sessions.save(PersistedSession(server = server, token = grant.token))
                client.restore(server = server, token = grant.token)
                sessionSubject.value = SessionState.LoggedIn
                scope.task { loadConfig() }
                store.setSocketOpening()
                client.start()
                return true
            }
            OAuth.TokenGrant.UnknownClient -> {
                oauthClients.forget(server)
                return signInFailed("Sign-in didn't finish. Try again.")
            }
            is OAuth.TokenGrant.Failure -> return signInFailed(grant.message)
        }
    }

    private fun signInFailed(message: String?): Boolean {
        sessionSubject.value = SessionState.LoggedOut
        statusSubject.value = message
        return false
    }

    /**
     * The sign-out purge of the media cache and the staged clips, in flight — what the next
     * session's first media work waits behind ([awaitMediaPurge]).
     */
    private var mediaPurge: Job? = null

    /**
     * Delete the departing account's preview bytes and staged clips: the on-disk HTTP cache and the
     * staged-media directory.
     *
     * Port note: LurkerKit does this on the main actor, under its own thread model; Android moves
     * the disk work off main, onto `Dispatchers.IO` in this model's scope. Not fire-and-forget,
     * though: the job is held, and `signIn` and `playableMediaURL` (the one path that stages
     * media) join it first, so a quick sign-in again never has its fresh files deleted behind it.
     * A process that dies before the purge finishes is covered at the next launch — a restore that
     * finds no session runs it again (`restoreSession`); it costs an empty directory and an empty
     * cache when there was nothing left.
     */
    private fun purgeMedia() {
        val previous = mediaPurge
        mediaPurge = scope.launch(Dispatchers.IO) {
            previous?.join()
            client.clearMediaCache()
            client.clearStagedMedia()
        }
    }

    /** Wait out a sign-out's media purge, if one is still running. */
    private suspend fun awaitMediaPurge() {
        mediaPurge?.join()
    }

    /**
     * The deliberate sign-out. Local teardown is immediate — the client revokes
     * server-side in the background — so the bounce to sign-in never waits on the network.
     *
     * The device token goes with it: this phone must stop receiving the departing user's
     * DMs. Handed to the client rather than deregistered here, because it has to happen
     * against the session being revoked and therefore before the revoke lands (lurker#490).
     */
    fun logout() {
        // What was being typed is still the account's: save it before the session ends, so it's
        // there on the next sign-in, here or anywhere. Mid-composition too — there's no commit
        // coming now. Over HTTP inside the logout's own sequence, ahead of the revoke: the
        // socket is closed the moment `logout` starts, which would cancel a queued write.
        for (task in draftFlushes.values) task.cancel()
        draftFlushes.clear()
        val drafts = draftSync.takeAll().map { LurkerClient.KeyedDraft(key = it.key, draft = it.draft) }
        cancelReconnect()
        // Owed a revoke until the server confirms it (lurker-ios#218), so a sign-out made offline still
        // reaches the server later instead of leaving the token, and its pushes, live for good.
        val now = Instant.now()
        val ending = client.currentSession?.let { PendingRevoke(server = it.server, token = it.token, since = now.toEpochMilli()) }
        if (ending != null) {
            sessions.addPendingRevoke(PersistedSession(server = ending.server, token = ending.token), now)
            revokingNow.add(ending.token)
        }
        client.logout(deviceToken = deviceToken, relayEndpoint = relayEndpoint, drafts = drafts) { outcome ->
            if (ending != null) revokeFinished(ending, outcome)
        }
        resetPush()
        sessions.clear()
        // Port-only: an attempt a killed process left out in the browser goes too, so a late
        // redirect from its tab can't sign this phone back in after a deliberate sign-out.
        sessions.clearPendingSignIn()
        // The next account's preferences are not this one's — and a privacy switch in
        // particular must not carry across users. Both this and the deliberate sign-out clear
        // it, because either can be followed by someone else signing in on this phone.
        settingsCache.clear()
        resetTimeZoneSync()
        // Previews carry the same hazard the settings cache does, plus two more: the metadata
        // is the previous account's reading history, and the `asked` set would suppress
        // re-resolution against a DIFFERENT instance — whose signed proxy tokens wouldn't verify
        // anyway, so every image would 403. Both of these were dead code until now.
        linkPreviews.reset()
        onPreviewCachesCleared?.invoke()
        // ⚠ And the BYTES, which are the part that actually identifies a reading history. The
        // decoded-image cache goes via the closure above (it's the platform's, this module isn't);
        // this drops the on-disk HTTP cache the images were served from.
        //
        // Port note: LurkerKit purges these on the main actor, under its own thread model; Android
        // moves the disk work off main — see `purgeMedia`, started at the same point in the teardown.
        purgeMedia()
        features = InstanceFeatures()
        lastPreviewToggles = null
        store.reset()
        // ⚠ The correlator holds the TEXT of every unanswered send, so it is account data and
        // goes with the rest of it. Neither teardown path emits `.socketClosed` — `close()`
        // cancels the socket without firing its handler, and a 401 arrives as `.unauthorized` —
        // so the abandon that path does would never run here.
        unsent.abandonAll()
        // Drafts are account data too, and a timer left armed would flush into the next session.
        resetDrafts()
        // A raw line's origin names a buffer of this account; the next one's ids may reuse it.
        rawCommandOrigins.clear()
        // Joins too: a pending one names a channel the next account never asked for, and its timer
        // would otherwise toast "No response" over the sign-in screen (lurker-ios#57).
        pendingJoins.removeAll()
        pendingOpens.cancel()
        loadingOlder.clear()
        loadingNewer.clear()
        lastMarked.clear()
        statusSubject.value = null
        sessionSubject.value = SessionState.LoggedOut
    }

    // MARK: - Push (lurker#490)

    /**
     * This install's push device token, once the OS has issued one (an APNs token on iOS, an
     * FCM one here). Held so sign-out can deregister it, and so a re-register after reconnect
     * doesn't need the app layer to remember.
     */
    private var deviceToken: String? = null

    /**
     * Whether this server has answered that it delivers FCM itself (the hosted service). Only that
     * answer is cached: it's fixed for a server, and [pushRoute] is asked on every activation. A
     * server without FCM is asked again each time — see [pushRoute]. The name is LurkerKit's, kept
     * so it greps across both.
     */
    private var apnsSupported: Boolean? = null

    /**
     * How this install gets push from this server (lurker-dev/RELAY_PLAN.md §6.2): directly, through
     * push.lurker.chat, or not at all. A server that can't push to the app says so before anyone is
     * asked for notification permission — a grant we'd never deliver anything under.
     *
     * `null` means we couldn't ask. Deliberately not folded into "no push": the two are one wifi
     * blip apart, and collapsing them would report a transient failure as a fact about the server's
     * configuration.
     *
     * Only a direct answer is cached ([apnsSupported]). The relay is a switch the server's admin can
     * flip while the app runs, so a server without FCM is asked again on each activation — one small
     * GET, on self-hosted servers only, so turning the relay on reaches phones without a restart.
     *
     * Port note: direct means `fcm` here, where LurkerKit looks for `apns`.
     * [allowAnyHttpsRelay] is for debug builds only: see [RelayPush.route].
     */
    suspend fun pushRoute(allowAnyHttpsRelay: Boolean): PushRoute? {
        if (apnsSupported == true) return PushRoute.Native
        val generation = pushGeneration
        val config = client.pushConfig() ?: return null
        // An answer about a session that has ended since: not this one's to act on.
        if (generation != pushGeneration) return null
        val route = RelayPush.route(config, allowAnyHttpsRelay)
        if (route == PushRoute.Native) apnsSupported = true
        _appPushUnavailable.value = when (route) {
            PushRoute.None -> AppPushUnavailable.NotTurnedOn
            PushRoute.Unsupported -> AppPushUnavailable.RelayUnsupported
            else -> null
        }
        return route
    }

    /**
     * Why this server can't push to the app, once it has answered so — the admin hasn't turned on
     * push.lurker.chat, or the server names a relay this app can't use — so Settings can say which
     * rather than leave a user wondering why notifications never come. Null otherwise, and after
     * either teardown ([resetPush]).
     */
    val appPushUnavailable: StateFlow<AppPushUnavailable?> get() = _appPushUnavailable.asStateFlow()
    private val _appPushUnavailable = MutableStateFlow<AppPushUnavailable?>(null)

    /** The push.lurker.chat endpoint this session filed (or tried to), so sign-out can take it off. */
    private var relayEndpoint: String? = null

    /** [relayEndpoint], for the teardown tests: nothing outside can see it once the session is gone. */
    internal val filedRelayEndpoint: String? get() = relayEndpoint

    /** How filing a relay endpoint went. */
    enum class RelayRegistration {
        Registered,

        /** `403`: the admin turned the relay off since the config was read. No push, not a failure. */
        RelayOff,

        /** No answer, or one we don't take as either of the above. */
        Failed,
    }

    /**
     * File [endpoint] — built by [PushRoute.Relay.endpoint] from this install's FCM token — as a Web
     * Push subscription with this install's [keys]. Idempotent: the server upserts, and moves the
     * endpoint to this account if another one on this phone held it.
     *
     * Recorded BEFORE the request, as [registerPushDevice] records its token: a registration whose
     * answer was lost may still have landed, and sign-out must take it off either way.
     */
    suspend fun registerRelaySubscription(endpoint: String, keys: DeviceKeys): RelayRegistration {
        if (session != SessionState.LoggedIn) return RelayRegistration.Failed
        // The other way round: a direct token this session filed goes, or pushes arrive twice.
        deviceToken?.let { old ->
            deviceToken = null
            client.dropDevice(old)
        }
        // A different endpoint filed earlier (a rotated FCM token, a changed server key) goes too.
        relayEndpoint?.takeIf { it != endpoint }?.let { client.dropRelaySubscription(it) }
        relayEndpoint = endpoint
        val generation = pushGeneration
        val code = client.registerRelaySubscription(endpoint, keys.p256dh, keys.auth) ?: return RelayRegistration.Failed
        // The session ended while this was out: its answer mustn't set the next one's state.
        if (generation != pushGeneration) return RelayRegistration.Failed
        return when {
            code in 200..<300 -> {
                _appPushUnavailable.value = null
                RelayRegistration.Registered
            }
            code == 403 -> {
                _appPushUnavailable.value = AppPushUnavailable.NotTurnedOn
                RelayRegistration.RelayOff
            }
            else -> RelayRegistration.Failed
        }
    }

    /**
     * Is [endpoint] still filed for this account? The server deletes relay subscriptions when its
     * admin turns the relay off — and turned back on while this app was in the background, nothing
     * here would know. So the registrar asks on every enable rather than trusting what it filed:
     * false means file it again; null means the server didn't answer.
     */
    suspend fun relaySubscriptionPresent(endpoint: String): Boolean? {
        if (session != SessionState.LoggedIn) return null
        return client.relayHeartbeat(endpoint)
    }

    /**
     * Forget this account's push state. Both teardowns call it — the deliberate sign-out and the 401
     * bounce — so they leave the same state behind: no token or endpoint of the last session's, and
     * no answer about a server the next sign-in may not be on.
     */
    private fun resetPush() {
        pushGeneration++
        deviceToken = null
        relayEndpoint = null
        apnsSupported = null
        _appPushUnavailable.value = null
        onPushStateReset?.invoke()
    }

    /**
     * Which session push requests belong to: bumped at each teardown, so a reply to a request the
     * last session sent — a 403, a config — is dropped instead of setting the next session's state.
     */
    private var pushGeneration = 0

    /**
     * Called when a session ends, by either teardown, so the app can rotate this install's relay
     * push keys (`RelayPushKeys.forget`): a push for the last account still in flight must not open
     * once another has signed in. A closure because the keys' storage is the platform's.
     */
    var onPushStateReset: (() -> Unit)? = null

    /**
     * Hand the OS-issued device token to the server. Idempotent — the OS re-issues the same
     * token on most launches, and the server upserts.
     */
    suspend fun registerPushDevice(token: String): Boolean {
        if (session != SessionState.LoggedIn) {
            deviceToken = token
            return false
        }
        // The server gained FCM since this session filed a relay endpoint (the hosted service, or
        // an operator adding a key): take the relay one off, or every push arrives twice.
        relayEndpoint?.let { old ->
            relayEndpoint = null
            client.dropRelaySubscription(old)
        }
        deviceToken = token
        return client.registerDevice(token = token)
    }

    /**
     * Fill in a shell's contents — what a screen calls when it opens on a buffer we already
     * hold but haven't fetched. A pure read; see `LurkerClient.loadLatest`.
     *
     * Deliberately the same request as `loadLatest(_:)` rather than a verb of its own. The
     * server's `history` verb predates every version of the app, so hydration keeps working
     * against an older self-hosted instance — which `protocol.ts` treats as a normal
     * operating condition, since operators upgrade on their own schedule.
     */
    fun hydrate(key: BufferKey) {
        // 200, not `loadLatest`'s default 100: this replaces what `open-buffer` used to
        // answer, and `buildBufferBacklog` ships 200. Taking the default would have quietly
        // halved every buffer's first screenful — the exact quantity `countBy` exists to
        // protect — and started the scroll-up pager a page sooner.
        client.loadLatest(
            networkId = key.networkId, target = key.target, countBy = historyCountBy, limit = 200,
        )
    }

    /**
     * Open a buffer for real: reopen, mint, or JOIN. Deliberate user intent only — this is a
     * write, and the user's other devices are told about it. Filling in a shell is
     * `hydrate(_:)`.
     *
     * Returns whether it was handed to a socket. That alone isn't "it went out" — a dropped socket
     * isn't nulled — so a caller that tells the user asks `ChatState.canWrite` first.
     */
    fun openBuffer(key: BufferKey): Boolean {
        openBufferSeam?.let { return it(key) }
        return client.openBuffer(networkId = key.networkId, target = key.target, countBy = historyCountBy)
    }

    /** Test seam: stands in for the socket `openBuffer` writes to, which no test has. */
    internal var openBufferSeam: ((BufferKey) -> Boolean)? = null

    /**
     * Test seam: stands in for the socket a command's PRIVMSG goes out on (`/msg bob hi`), so a
     * test can have a line that went — target and text in, whether it went out.
     */
    internal var sendMessageSeam: ((String, String) -> Boolean)? = null

    /** Test seam: stands in for the socket a raw line goes out on (`/frobnicate`). */
    internal var sendRawSeam: ((String) -> Boolean)? = null

    /** Test seam: stands in for the settings write `syncTimeZone` makes. */
    internal var timeZoneWriteSeam: ((String) -> Unit)? = null

    /**
     * The phone's time zone, written to `system.timezone` when the server's differs (sweep L08), as
     * the web's `syncDetectedTimezone` does. The server formats in it with no client connected: push
     * quiet hours, and the "since …" others see in the auto-away message. A phone that never wrote
     * it left both on the zone of whichever browser bootstrapped last, or on the server's clock.
     *
     * On each settings bootstrap — every start and every reconnect — so a phone that has travelled
     * corrects it on its next foreground. Never in answer to a `settings` frame: another device's
     * write isn't answered, so two devices in different zones can't trade it back and forth.
     *
     * Port note: `java.util.TimeZone.getDefault().id` where LurkerKit reads
     * `TimeZone.autoupdatingCurrent` (not `current`, a snapshot that can outlive a zone change). Android
     * resets the process's default zone when the system's changes, so it is already the live one. Not
     * `ZoneId.systemDefault()`: that can throw for an ID java.time doesn't know, and turns a legacy
     * short ID (`EST`) into an offset (`-05:00`) rather than the IANA name the server stores.
     *
     * ⚠ One write out at a time, and a bootstrap that arrives meanwhile is answered when it lands,
     * against the zone and the stored value as they are THEN. Two writes out together could land in
     * either order and leave the old zone stored; a bootstrap simply skipped would be lost if the
     * write out failed. Only a skipped bootstrap asks again, so a zone the server refuses is asked
     * once per bootstrap, never in a loop.
     */
    internal fun syncTimeZone(detected: String = TimeZone.getDefault().id) {
        if (timeZoneWrite != null) {
            timeZoneResyncOwed = true
            return
        }
        if (detected.isEmpty() || store.state.settings.values["system.timezone"] == SettingValue.String(detected)) return
        val write = UUID.randomUUID()
        timeZoneWrite = write
        // The seam's write never finishes on its own; a test finishes it (`timeZoneWriteFinished`).
        timeZoneWriteSeam?.let { return it(detected) }
        scope.task {
            // Not worth a word on failure: the next bootstrap asks again.
            client.updateSettings(mapOf("system.timezone" to SettingValue.String(detected)))
            // A sign-out since then ended it; the next session starts clean.
            if (timeZoneWrite == write) timeZoneWriteFinished()
        }
    }

    /**
     * The write out has landed, either way: a bootstrap that waited for it is answered now.
     * Internal for tests.
     */
    internal fun timeZoneWriteFinished() {
        timeZoneWrite = null
        if (!timeZoneResyncOwed) return
        timeZoneResyncOwed = false
        syncTimeZone()
    }

    /** The time zone write out, if any, so its answer can tell it still belongs to this session. */
    private var timeZoneWrite: UUID? = null

    /** A bootstrap arrived while a write was out, and is answered when it lands. */
    private var timeZoneResyncOwed = false

    /** Session-scoped: a sign-out drops both, so the next account's first bootstrap is never skipped. */
    private fun resetTimeZoneSync() {
        timeZoneWrite = null
        timeZoneResyncOwed = false
    }

    /**
     * Open a DM and go there once its row exists (lurker-ios#201): Send Message on a profile, a
     * Friends row whose DM is closed. The app is taken there through `onBufferOpened` — at once if
     * the row is already here, else as soon as the server's answer mints it, and not at all if that
     * hasn't happened within `PendingOpen.patience`. If the `open-buffer` couldn't go out, nothing
     * waits and `onJoinNotice` says so.
     *
     * ⚠⚠ Never `openBuffer` and then navigate. `open-buffer` only queues a write, and the row it
     * mints comes back later on the socket. A chat screen that opens first finds no row while the
     * roster is settled, which the screen's buffer-gone check (`handleBufferDisappeared` on iOS,
     * the app's `BufferWatch` here) reads as "this buffer isn't coming" — and it backs out to the
     * list. The DCC chat had the same race and the same cure (lurker#270), so the two share one
     * wait: the buffer asked for last is the one to land on.
     *
     * ⚠ DMs only. The server's `open-buffer` mints a row only for a nick: for a channel it JOINs,
     * and only a `#` one, so a wait on `&local` or an invite-only channel would never be met.
     */
    fun openAndShow(key: BufferKey) {
        if (!openThenShow(key)) sayDmNotConnected(key)
    }

    /**
     * The DM's `open-buffer` couldn't go out, so nothing waits: tell the user rather than leave
     * them tapping a row that does nothing.
     */
    private fun sayDmNotConnected(key: BufferKey) {
        val networkId = key.networkId ?: return
        val network = store.state.networks[networkId]?.displayName ?: "the network"
        onJoinNotice?.invoke(JoinNotice.DmNotConnected(nick = key.target, network = network))
    }

    /**
     * `openAndShow`, for a caller that tells the user its own way when it fails — `/query` gives
     * the line back to the composer. False when the `open-buffer` couldn't go out; nothing waits
     * then.
     */
    private fun openThenShow(key: BufferKey): Boolean {
        val now = Instant.now()
        val plan = pendingOpens.plan(key, held = store.state.buffers[key.id] != null, now = now)
        if (plan == PendingOpens.Plan.AlreadyWaiting) return true
        // ⚠ Before the write, not after it worked. This is the newest ask to be taken somewhere,
        // and one that fails is still newer: left standing, the older DM, DCC chat or join landed
        // after the "can't message" toast and took the user somewhere they'd stopped asking for.
        supersedeLandings()
        // The rule `/join` uses, so a reconnect can't make the two disagree.
        if (plan == PendingOpens.Plan.Write && !(store.state.canWrite(networkId = key.networkId) && openBuffer(key))) {
            return false
        }
        pendingOpens.waitFor(key, now = now)
        settlePendingOpen()
        return true
    }

    /**
     * Hand a buffer this device opened to the app once its row exists, or let go once it's clearly
     * not coming. Run after every frame, and straight after a wait goes in, so a row that's
     * already here is gone to without waiting for one.
     */
    private fun settlePendingOpen() {
        val key = pendingOpens.settle(buffers = store.state.buffers, now = Instant.now()) ?: return
        onBufferOpened?.invoke(key)
    }

    /**
     * Nothing asked for so far may land: something newer has the user's attention. Called for
     * every new ask to be taken somewhere — a DM, a DCC chat, a join that opens — and by the app
     * whenever a buffer goes on screen, since landing later would pull the user off what they
     * chose (lurker-ios#201). A landing calling it cancels nothing: its own wait has already ended.
     *
     * ⚠ Joins wait in `PendingJoins` and opens in `PendingOpens`, so "the latest ask wins" holds
     * only if each kind stands the other down: `/join #slow` and then Send Message to bob landed
     * on bob, then yanked the user to #slow when its join came back.
     */
    fun supersedeLandings() {
        pendingOpens.cancel()
        pendingJoins.stopOpening()
    }

    /**
     * The page-size unit every history read asks for (lurker-ios#10). Read fresh each time rather
     * than cached: `chat.consolidate_joins` is server-side, so it can change from the web
     * mid-session and the next fetch should already agree with what the next render will do.
     */
    private val historyCountBy: HistoryCountBy
        get() = HistoryCountBy.forRendering(store.state.settings)

    /**
     * Fetch a page of recent highlights (lurker-ios#13). `before` is the previous page's
     * `nextBefore` cursor, null for the first page. Returns null on failure (a 401 also
     * bounces the session); the caller renders an error state. The page itself returns
     * straight to the caller rather than folding into `state` — highlights span every buffer
     * and are shown in their own list, not merged into any one buffer's log.
     *
     * The saved flags are the exception, for the same reason `fetchBookmarks` and
     * `searchMessages` fold theirs: a cross-buffer feed is one of the few places a saved line
     * the user has never opened can become known, and without it, jumping from the row to the
     * message offers "Save Message" for something already saved. Every feed that can surface a
     * bookmark now says so, rather than two of the three.
     *
     * Filtered to the saved rows and inserted rather than reconciled, which is the additive
     * `noteBookmarked(ids:)` path the other two feeds already take. The reconciling half
     * (`noteBookmarks(in:networkId:)`) isn't reachable from here and isn't wanted: it's a
     * `ChatState` mutation applied inside `reduce` while handling a frame, keyed to the one
     * `networkId` that frame belongs to — a cross-buffer page has no single network to hand
     * it. Additive is also all this cache is for: it answers "is the line the user is looking
     * at saved?", an unsave arrives as its own `bookmark-updated` frame, and a feed page was
     * never a mirror of what the account owns. One mutation, not one per row, because each
     * `store.apply` publishes a whole `ChatState`.
     */
    suspend fun fetchHighlights(before: Long? = null): HighlightsPage? {
        val page = client.fetchHighlights(before = before)
        store.noteBookmarked(ids = page?.items.orEmpty().filter { it.message.bookmarked }.map { it.message.id })
        return page
    }

    /**
     * A page of the activity feed — highlights and reactions to your lines (lurker-ios#183).
     * Bookmark flags noted the way `fetchHighlights` notes them; a reaction row carries none.
     */
    suspend fun fetchActivity(cursor: FeedCursor? = null): HighlightsPage? {
        val page = client.fetchActivity(cursor = cursor)
        store.noteBookmarked(
            ids = page?.items.orEmpty().filter { it.reaction == null && it.message.bookmarked }.map { it.message.id },
        )
        return page
    }

    // MARK: - Link previews

    /**
     * The app-wide preview cache.
     *
     * One store, not one per screen: the same link shows up in a channel, in the highlights
     * feed and in search results, and it should be resolved once for all three. Lazy because
     * with both settings off nothing ever asks it anything, and it shouldn't cost even an
     * allocation for the people who — reasonably — don't want this feature.
     */
    val linkPreviews: LinkPreviewStore by lazy {
        LinkPreviewStore(scope) { urls -> client.resolveLinkPreviews(urls) }
    }

    /**
     * Called on sign-out so the app layer can drop its decoded-image cache. A closure rather
     * than a direct call because the image cache is the platform's and this module is not.
     */
    var onPreviewCachesCleared: (() -> Unit)? = null

    /**
     * Instance feature flags from `/api/config`, read again on every reconnect.
     *
     * ⚠ Off until proven on. Link previews are a whole feature behind an operator env flag: when
     * it's off the server doesn't even mount the routes, so the settings rows are HIDDEN rather
     * than offered inert, and nothing is primed.
     */
    var features: InstanceFeatures = InstanceFeatures()
        private set

    /**
     * The size to compress media to before uploading — the server's advertised cap when it
     * has given one, else `Uploads.fallbackMaxBytes` (lurker-ios#149).
     *
     * Read at the moment of the upload rather than captured: the cap is refreshed on every
     * reconnect and re-sent when the user changes their own limit, so a copy taken when a
     * screen was built is a number that may since have moved.
     */
    val uploadCapBytes: Long get() = Uploads.compressionTarget(advertised = state.maxUploadBytes)

    /**
     * The longest edge the server keeps of a static image, or null when it hasn't said — in
     * which case images go up untouched (lurker-ios#155). Read at upload time, like
     * `uploadCapBytes`.
     */
    val maxStaticImageDimension: Int? get() = state.maxStaticImageDimension

    /**
     * Which `/api/config` answer is the current one. Every reconnect attempt starts a read, so
     * several can be out at once, and an older answer landing last must not undo a newer one. See
     * `NewestAnswer` for why a newer read that fails doesn't count.
     */
    private val configReads = NewestAnswer()

    /**
     * Read `/api/config`: the instance's feature flags, and whether this build can talk to the
     * server at all (lurker-ios#17).
     *
     * ⚠⚠ **Never awaited ahead of `client.start()`.** It was, and that put an untimed HTTP GET
     * in front of the WebSocket for every user, previews or not: launch behind a captive portal
     * or an overloaded cell that accepts TCP but black-holes `/api/config`, and the buffer list
     * renders while the app sits with no socket, no buffers and no messages for up to a minute.
     * `LurkerClient.start()` documents that exact hazard and detaches `fetchSettings()` for it;
     * this is the same trade and it wants the same answer. A flag that decides whether to
     * DECORATE messages must never delay getting them.
     *
     * Ordering costs nothing now, because arriving late is handled rather than raced: turning
     * out to be enabled re-primes what is already loaded, exactly as flipping the setting does,
     * and a socket that opened before a refusal arrived is closed when it does.
     *
     * ⚠ Read on EVERY reconnect, where it used to be re-read only until it had answered once. A
     * server's version moves when it's deployed, and a deploy is what drops the socket, so the
     * reconnect is exactly when to ask. A failed read keeps the last answer: one 502 must not
     * switch previews off, or clear a refusal.
     */
    private suspend fun loadConfig() {
        val read = configReads.start()
        val config = client.fetchConfig()
        if (config == null || session != SessionState.LoggedIn || !configReads.accept(read)) return
        val wasEnabled = features.linkPreviews
        features = config.features
        if (config.features.linkPreviews && !wasEnabled) primeLoadedBuffers()
        val incompatibility = config.incompatibility
        if (incompatibility != null) {
            onIncompatible(incompatibility)
        } else if (store.state.connection.incompatibility != null) {
            // The server takes this build again: it was rolled back, or updated.
            store.clearIncompatible()
            if (!isForeground) return
            reconnectSocket()
        }
    }

    /**
     * The server and this build can't talk (lurker-ios#17). Reconnecting stops, because every
     * attempt would be refused the same way, and the session stays: the token is fine, and the
     * app connects again once it or the server is updated.
     *
     * Only `/api/config` saying otherwise clears it, and that's asked again whenever the app
     * comes back to the foreground or back online (`doReconnect`).
     */
    private fun onIncompatible(incompatibility: Incompatibility) {
        cancelReconnect()
        store.setIncompatible(incompatibility)
        client.dropSocket()
    }

    /**
     * Bytes from the server's media proxy, for a server-minted path off a `LinkPreview`.
     *
     * Goes through the client because the proxy is authenticated and native auth is a Bearer
     * header — there's no cookie for a bare image load to ride on.
     */
    suspend fun proxiedMedia(path: String): MediaFetch = client.fetchProxiedMedia(path = path)

    /**
     * A URL the media player can open for a preview's `src` — see the client's note for why
     * this is two cases rather than one.
     *
     * Port note: the address as text, as the client hands it back (an absolute address as
     * `HttpUrl` writes it, or a staged file's `file:` URI).
     */
    suspend fun playableMediaURL(path: String, mime: String?): String? {
        // It stages a file a sign-out's purge would otherwise delete under it (`purgeMedia`).
        awaitMediaPurge()
        return client.playableMediaURL(path = path, mime = mime)
    }

    /**
     * Fetch a page of bookmarks. Same cursor contract as `fetchHighlights`, and the
     * same row shape — hence the shared `HighlightsPage`.
     *
     * Newest-first by *message* id: the order is when each line was said, not when it was
     * saved. Bookmarking something from last spring files it under last spring.
     */
    suspend fun fetchBookmarks(before: Long? = null): HighlightsPage? {
        val page = client.fetchBookmarks(before = before)
        // Every row in this feed is saved by definition, so fold the page into the id set:
        // it's the one place a bookmark whose buffer was never loaded can become known. Without
        // this, jumping from the list to the message would offer "Save Message" for something
        // already saved.
        //
        // One mutation, not one per row: `store.apply` publishes a whole `ChatState`, so a
        // page of `bookmark-updated` frames would wake every screen 50 times over to say one
        // thing.
        store.noteBookmarked(ids = page?.items.orEmpty().map { it.message.id })
        return page
    }

    /**
     * Run one page of a message search. `raw` is the user's whole input, filter grammar and
     * all (`from:nick in:#channel on:network words`); `before` is the previous page's
     * `nextBefore` cursor, null for the first page.
     *
     * Returns null for "couldn't ask or wasn't answered" and an empty page for "no matches" —
     * the caller renders those differently, and collapsing them would let an offline search
     * claim the user has never said the word they're looking for. A query with nothing to
     * search on is neither: there is no question to ask, so it's an empty page.
     *
     * The `on:` token is resolved here rather than in the client because this is where the
     * roster lives. A name that matches no network drops the filter instead of failing the
     * search: `on:` is being typed a character at a time, and every prefix of a real network
     * name would otherwise be a search that returns nothing.
     */
    suspend fun searchMessages(raw: String, before: Long? = null): HighlightsPage? {
        val query = SearchQuery.parse(raw)
        if (query.isEmpty) return HighlightsPage(items = emptyList(), nextBefore = null)
        // Lowest id wins, rather than whichever the dictionary happens to yield first. Two
        // networks can legitimately share a name — two connections to the same server, or two
        // user-named entries that collide — and `networks` is a dictionary, whose iteration
        // order is unspecified and free to differ between two calls. That's not merely untidy:
        // page one and page two of the SAME search are two calls, so an unstable pick would
        // append rows from a different network than the page they're extending.
        val networkId = if (query.network.isEmpty()) {
            null
        } else {
            store.state.networks.values
                // A network we hold no name for matches no `on:` filter — it can't, and
                // guessing would point the search at an arbitrary network (lurker-ios#136).
                //
                // Port note: `equals(ignoreCase)` for Foundation's `caseInsensitiveCompare`. Both
                // fold case; Foundation also folds expansions (`ß` and `SS`) and canonical
                // equivalence, so a name differing only in those matches on iOS and not here.
                .filter { it.name?.equals(query.network, ignoreCase = true) == true }
                .minByOrNull { it.id }?.id
        }
        val page = client.search(query, networkId = networkId, before = before)
        // Search rows carry the same `bookmarked` flag as any other message row, and this is
        // the only place a saved line the user has never opened can become known — same
        // reasoning as `fetchBookmarks`, and the same single mutation rather than one per row.
        store.noteBookmarked(ids = page?.items.orEmpty().filter { it.message.bookmarked }.map { it.message.id })
        return page
    }

    /**
     * Whether `messageId` is saved — what the message action sheet reads to decide between
     * "Save Message" and "Remove Bookmark". See `ChatState.bookmarkedIds`.
     *
     * False for a saved message this session has never loaded. That's by design (the set is
     * bounded by what's been seen) and is why callers that already *know* the answer — the
     * Bookmarks list, where every row is saved — use `setBookmark` directly rather than
     * toggling against this.
     */
    fun isBookmarked(messageId: Long): Boolean = state.isBookmarked(messageId)

    /**
     * Save or unsave a message outright.
     *
     * Deliberately sends and waits: the server fans a `bookmark-updated` back to every device,
     * and a save it refuses (a message the account doesn't own) produces no echo at all — so
     * flipping local state here would show a bookmark that doesn't exist.
     * Returns false when the verb couldn't be put on the wire at all. Nothing retries it, so
     * a caller that has already removed the row from a list must not treat that as done —
     * see the Bookmarks list's swipe.
     */
    fun setBookmark(messageId: Long, saved: Boolean): Boolean =
        canWrite && client.setBookmark(messageId = messageId, saved = saved)

    /**
     * Whether a write the user made can reach the server now (`ChatState.socketWritable`). Asked
     * before every user write that reports its fate (sweep L02…L53), because the send's own answer
     * can't see two windows:
     * - a reconnect's new socket takes writes before its upgrade succeeds — deliberately, for the
     *   connect burst — and loses them if the attempt fails;
     * - airplane mode flips `reachable` while the old socket still reads Connected.
     */
    private val canWrite: Boolean get() = store.state.socketWritable

    /**
     * React with `value` on a line, or take ours back when it's already there (lurker-ios#183).
     *
     * The direction comes from the store as it stands, like the web's `toggle`: a chip is drawn
     * from the same map, so tapping a lit one takes it back. Never optimistic — the network's
     * echo is what lights a reaction up (see `LurkerClient.react`). False when the value can't
     * go out at all or there's no socket to carry it.
     */
    fun toggleReaction(messageId: Long, value: String): Boolean {
        if (messageId == 0L || !Reactions.isValidValue(value)) return false
        val mine = state.reactions[messageId].orEmpty().any { it.isSelf && it.value == value }
        return client.react(messageId = messageId, value = value, remove = mine)
    }

    /**
     * Upload a prepared file and return the stored object's URL for the composer to paste
     * (lurker-ios#14). The caller has already picked the file and — for video — compressed it to
     * fit the instance cap; this layer only speaks to the server. `onProgress` reports the
     * device→server leg as a 0…1 fraction; `onServerProgress` reports the two legs only the
     * server can see, over the WS this session already holds (lurker-ios#47). Feed both into an
     * `UploadProgress` rather than rendering them separately — it is what knows which leg
     * the readout should be naming. Platform-free by design: the picker and the video transcode
     * live in the app, so this module stays platform-light.
     *
     * Port note: catches every `Exception` as LurkerKit's `catch` does — a `RuntimeException`
     * out of the picked file or the request builder is an upload failure, not the caller's
     * crash — bar `CancellationException`, so a cancelled caller ends with its cancellation.
     * `localizedDescription` is the exception's message.
     */
    suspend fun upload(
        fileURL: File,
        filename: String,
        mime: String,
        progressToken: String,
        onProgress: (Double) -> Unit,
        onServerProgress: (UploadServerProgress) -> Unit,
    ): Result<UploadResponse, UploadError> =
        try {
            val response = client.upload(
                fileURL = fileURL, filename = filename, mime = mime,
                progressToken = progressToken, onProgress = onProgress,
                onServerProgress = onServerProgress,
            )
            Result.Success(response)
        } catch (error: UploadError) {
            Result.Failure(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.Failure(UploadError.Transport(error.message ?: error.toString()))
        }

    /**
     * One page of the account's upload history (lurker-ios#138). `before` is the previous page's
     * last row id; null for the first. The starred view ignores it and arrives whole — see
     * `UploadsRequest`.
     */
    suspend fun fetchUploads(
        filter: UploadsFilter,
        before: Int? = null,
        limit: Int,
    ): UploadsPage? = client.fetchUploads(filter = filter, before = before, limit = limit)

    /**
     * Star or unstar an upload. Null on success, else a sentence to show the user.
     *
     * Server-side rather than a device preference, deliberately: the same starred gifs should
     * be at hand on every device the account is signed in on.
     */
    suspend fun setUploadFavorite(id: Int, favorite: Boolean): String? =
        client.setUploadFavorite(id = id, favorite = favorite)

    /**
     * Destroy an upload's stored bytes and drop its row. Null on success, else the server's own
     * reason. Only ever called for a row whose `canDelete` is set.
     */
    suspend fun deleteUpload(id: Int): String? = client.deleteUpload(id = id)

    /**
     * What the UI should do after a line of input — almost always nothing. `/msg` and `/query`
     * to a channel ask the composer's owner to switch to it. To a nick they ask nothing of the
     * screen: the DM opens once its row exists (`openAndShow`), which a screen switching at once
     * would race (lurker-ios#201). `/join` goes through `requestJoin`, which opens the channel
     * once we're in it.
     */
    sealed interface SendOutcome {
        data object None : SendOutcome

        data class Activate(val key: BufferKey) : SendOutcome

        /**
         * `/whois` — open this person's profile. Carries the network because a profile is
         * about a person *on a connection*, and the buffer the command was typed in is the
         * only thing that knows which one.
         */
        data class ShowProfile(val networkId: Int, val nick: String) : SendOutcome
    }

    /**
     * Which composer line each outstanding `send-result` is answering — see
     * `UnsentCorrelator`, where the rules live and can be tested.
     */
    private val unsent = UnsentCorrelator()

    /**
     * Take back the line the server refused for `key`, if one is waiting (lurker-ios#128).
     *
     * Read-and-clear: once it is in a composer, the composer owns it. The caller decides whether
     * there is room for it — this does not know what is already typed.
     */
    fun takeUnsent(key: BufferKey): UnsentLine? = store.takeUnsentLine(key)

    // MARK: - Drafts (lurker-ios#188)

    /** Edits the server hasn't heard yet, and which buffer is composing — see `DraftSync`. */
    private val draftSync = DraftSync()

    /** The debounced flush per buffer (`BufferKey.id`): each edit re-arms its buffer's. */
    private val draftFlushes = mutableMapOf<String, Job>()

    /**
     * The socket (`LurkerClient.socketGeneration`) whose `draft-snapshot` arrived last. A write
     * to any other — one still connecting, or a forced reconnect's replacement, which swaps the
     * socket with no close in between — is one that socket's snapshot can't have seen.
     *
     * ⚠ Not `snapshotSinceOpen`: that turns on at frame 1, and the server built frame 2 in the
     * same turn, before reading anything we send — so a write between the two is still unseen.
     */
    private var draftSnapshotSocket: Int? = null

    /** The draft `key`'s composer should show: this device's unflushed edit, else the server's. */
    fun draft(key: BufferKey): ComposerDraft? = draftSync.local(key.id) ?: store.state.drafts[key.id]

    /**
     * Whether a server write to `key`'s draft is being held off — an edit here the server hasn't
     * heard, or a composition in flight. The composer asks before letting one repaint it.
     */
    fun isDraftProtected(key: BufferKey): Boolean = draftSync.isProtected(key.id)

    /**
     * The composer for `key` changed — its text, its reply, or whether an IME is composing.
     * Goes to the server after `Drafts.flushDelay` of quiet; a composition holds it until it ends.
     */
    fun editDraft(key: BufferKey, draft: ComposerDraft, composing: Boolean = false) {
        if (!Drafts.syncs(key)) return
        draftSync.edit(key, draft, composing = composing)
        scheduleDraftFlush(key)
    }

    /**
     * Send `key`'s draft now if it has an edit waiting — leaving the buffer, leaving the field,
     * a send. Ends its composition first: the field is done with it.
     */
    fun flushDraft(key: BufferKey) {
        if (draftSync.composing == key.id) draftSync.endComposition()
        sendDraft(key.id)
    }

    private fun scheduleDraftFlush(key: BufferKey) {
        val id = key.id
        draftFlushes.remove(id)?.cancel()
        // `endComposition` re-arms it. Until then the edit stays waiting, and protected.
        if (draftSync.defersFlush(id)) return
        draftFlushes[id] = scope.task {
            // Port note: Swift's `try? await Task.sleep` swallows the cancellation, so a guard
            // follows it; `delay` ends the body with a `CancellationException` instead.
            delay(Drafts.flushDelay.toKotlinDuration())
            draftFlushes.remove(id)
            sendDraft(id)
        }
    }

    private fun sendDraft(id: String) {
        draftFlushes.remove(id)?.cancel()
        val edit = draftSync.take(id) ?: return
        deliver(edit)
    }

    /** Put a taken edit in the store and on the socket. Returns false when there was no socket. */
    private fun deliver(edit: DraftSync.Edit): Boolean {
        val networkId = edit.key.networkId ?: return true
        // Into the store whether or not it reaches the server: the pencil and the next visit read
        // it from there.
        store.setDraft(edit.key, edit.draft)
        val sent = client.saveDraft(
            networkId = networkId, target = edit.key.target, draft = edit.draft,
            // Settled when the write completes. A failure (the socket died under it) waits for
            // the reconnect's snapshot — unless a newer edit has gone out since.
            onComplete = { ok ->
                scope.task { draftSync.completed(seq = edit.seq, ok = ok) }
            },
        )
        // ⚠ No socket, so it waits — protected from the next snapshot, which would otherwise put
        // the server's older copy back over it, and sent once that snapshot gives it a socket.
        if (!sent) {
            draftSync.restore(edit)
            return false
        }
        // Recorded before the completion can run: that hops to the main thread, which this holds.
        //
        // Port note: the client calls `onComplete` before `saveDraft` returns (OkHttp says at once
        // whether it queued the frame), so the hop is `task`'s, which yields before its body —
        // that is what keeps this ahead of `completed`, as the actor hop does in LurkerKit.
        draftSync.sending(edit)
        if (draftSnapshotSocket != client.socketGeneration) {
            // ⚠⚠ The server builds this socket's `draft-snapshot` before it reads this write, so
            // that snapshot holds the OLDER draft, and folding it would repaint the composer with
            // it. Never echoed back to us either.
            draftSync.sentBeforeSnapshot(edit)
        }
        return true
    }

    /** Everything waiting, now. `sendDraft` puts back whatever found no socket. */
    private fun flushAllDrafts() {
        for (id in draftSync.flushableIds) sendDraft(id)
    }

    private fun dropDraft(key: BufferKey) {
        draftFlushes.remove(key.id)?.cancel()
        draftSync.drop(key.id)
    }

    private fun dropDraftsForMissingNetworks() {
        val live = store.state.networks.keys.toSet()
        val doomed = draftFlushes.keys.filter { id ->
            val networkId = id.takeWhile { it != ':' }.toIntOrNull() ?: return@filter false
            !live.contains(networkId)
        }
        for (id in doomed) draftFlushes.remove(id)?.cancel()
        draftSync.dropNetworks(keeping = live)
    }

    private fun resetDrafts() {
        for (task in draftFlushes.values) task.cancel()
        draftFlushes.clear()
        draftSync.reset()
    }

    /**
     * Hand a line back to the buffer it was typed in, and nudge whatever screen is showing it.
     *
     * ⚠ The one place a refusal becomes a restore, so the two ways to learn of one — the ack
     * saying no, and the socket refusing to carry the verb at all — cannot drift apart.
     */
    private fun refuse(clientId: String) {
        val origin = unsent.resolve(clientId = clientId, ok = false) ?: return
        store.holdUnsent(origin.key, text = origin.line, reply = origin.reply)
        onSendRefused?.invoke(origin.key)
    }

    /**
     * Handle a line of composer input from `key`'s buffer: a plain message goes to the
     * current target; a slash command is parsed (`CommandParser`) and its effects carried
     * out. Returns the UI follow-up, if any.
     *
     * `reply` is the composer's pending reply (lurker-ios#184). A plain line or a `/me` goes out
     * as it (`Replies.consumes`); any other command leaves it unused, and the caller keeps it
     * pending. A refusal gives it back with the line.
     */
    fun send(key: BufferKey, text: String, reply: PendingReply? = null): SendOutcome {
        val reply = if (Replies.consumes(text)) reply else null
        // The ignore rules go in because two commands read them: `/ignore` prints the listing
        // and `/unignore <n>` resolves a number against it. Handed to the parser rather than
        // fetched by it, so the whole command vocabulary stays pure and testable — this is the
        // only place that knows where the rules live. The set itself, not a listing built from
        // it: only those two verbs materialize one, and this runs on every line typed.
        val parsed = CommandParser.parse(
            text,
            networkId = key.networkId,
            target = key.target,
            // Null until the connect burst has finished, because until then an empty set is
            // indistinguishable from an account with no rules — and both `/ignore` and
            // `/unignore` would answer that difference out loud ("ignore list is empty", "no
            // ignore with mask bob") for an account with a dozen of them. `backlogComplete` is
            // the latch that says the burst is done, and it isn't cleared by a drop, so a
            // reconnect doesn't take the answer away again.
            ignores = if (store.state.backlogComplete) store.state.ignores else null,
            // Same latch, same reason: `/relay` with no arguments is a claim about which bots are
            // marked, and an empty set mid-burst would answer "none" for a network that has some.
            relayBots = if (store.state.backlogComplete) store.state.relayBots else null,
            // `/quiet` and the mode shortcuts read the network's vocabulary; null until its burst
            // ends, which the parser reads as unknown rather than as the RFC defaults.
            modeSpec = key.networkId?.let { store.state.networks[it]?.modeSpec },
            // Whether `/part &local` names a channel or gives a reason: a buffer by that name on
            // this network says channel.
            hasBuffer = { name -> store.state.buffers[BufferKey(networkId = key.networkId, target = name).id] != null },
            formatted = formatExpiry,
        )
        when (parsed) {
            is ParsedInput.Message -> {
                // ⚠⚠ The Boolean matters. `LurkerClient.send` returns false when there is no
                // socket at all, and there is no queue behind it — the verb went NOWHERE, so no
                // `send-result` can ever come back for it. Without this the line vanished exactly
                // as it did before lurker-ios#128, in the window the ConnectionBanner is pointing
                // at. The ack path covers a live socket the cell refuses on; this covers not
                // reaching the cell.
                val id = unsent.track(key, line = text, reply = reply)
                if (!canWrite || !client.sendMessage(
                        networkId = key.networkId, target = key.target, text = parsed.text, clientId = id,
                        replyTo = reply?.messageId,
                    )
                ) {
                    refuse(id)
                }
                return SendOutcome.None
            }
            ParsedInput.NotCommand -> {
                // System-buffer input with no network to send to — the web's own nudge, rather
                // than dropping the one write the user made deliberately.
                store.appendLocal(key, text = "Not a command — type /commands to see what you can run here.")
                return SendOutcome.None
            }
            is ParsedInput.Command -> return run(parsed.effects, key = key, line = text, reply = reply)
        }
    }

    /**
     * Carry out a command's effects in order against `key`'s buffer, returning the last UI
     * follow-up (an `activate`, for `/msg` to a channel). Wire effects run on `key`'s network, `away`/
     * `back` too, which the server may widen to every network (lurker#994); `info` prints a
     * local line.
     */
    private fun run(
        effects: List<CommandEffect>,
        key: BufferKey,
        line: String,
        reply: PendingReply? = null,
    ): SendOutcome {
        val networkId = key.networkId
        var outcome: SendOutcome = SendOutcome.None
        // One correlator for the whole line, minted on first use so a command that puts nothing
        // on the wire (`/ignore`, `/commands`) records no in-flight entry to leak.
        var lineId: String? = null
        fun correlator(): String {
            lineId?.let { return it }
            val id = unsent.track(key, line = line, reply = reply)
            lineId = id
            return id
        }
        // Any wire effect that went nowhere refuses the whole line — see the note in `send`.
        // Recorded rather than acted on inline so the remaining effects still run: a command that
        // is half machinery should not stop halfway because one send found no socket.
        var wentNowhere = false
        // Every wire effect goes through here, and goes out only if it can (`canWrite`). The message
        // verbs mint their correlator before sending, since it rides the verb; the rest — `/topic`,
        // `/nick`, `/part`, `/away` — have no `send-result`, so theirs is minted only for a failure,
        // to carry the line back. Before sweep L02 those were dropped without a word.
        val writable = canWrite
        fun wire(send: () -> Boolean) {
            if (writable && send()) return
            correlator()
            wentNowhere = true
        }
        for (effect in effects) {
            when (effect) {
                is CommandEffect.Send -> {
                    val clientId = correlator()
                    wire {
                        sendMessageSeam?.invoke(effect.target, effect.text)
                            ?: client.sendMessage(networkId = networkId, target = effect.target, text = effect.text, clientId = clientId)
                    }
                }
                is CommandEffect.Action -> {
                    // A `/me` can be the reply — `reply` is null for every other command (see `send`).
                    val clientId = correlator()
                    wire {
                        client.sendAction(
                            networkId = networkId, target = effect.target, text = effect.text, clientId = clientId,
                            replyTo = if (effect.target == key.target) reply?.messageId else null,
                        )
                    }
                }
                is CommandEffect.Notice -> {
                    val clientId = correlator()
                    wire { client.sendNotice(networkId = networkId, target = effect.target, text = effect.text, clientId = clientId) }
                }
                is CommandEffect.Raw ->
                    wire {
                        val sent = sendRawSeam?.invoke(effect.line)
                            ?: client.sendRaw(networkId = networkId, line = effect.line)
                        if (!sent) return@wire false
                        val verb = rawVerb(effect.line)
                        if (networkId != null && verb != null) {
                            val now = Instant.now()
                            val pending = live(rawCommandOrigins[rawCommandKey(networkId, verb)].orEmpty(), now)
                            rawCommandOrigins[rawCommandKey(networkId, verb)] = pending + RawCommandOrigin(key, now)
                        }
                        true
                    }
                is CommandEffect.ShowProfile ->
                    // Nothing goes out here — the screen asks when it opens. A `/whois` typed in
                    // the system buffer has no connection to ask on and is silently no-op'd, the
                    // same as every other network-scoped effect there.
                    if (networkId != null) outcome = SendOutcome.ShowProfile(networkId = networkId, nick = effect.nick)
                is CommandEffect.Join ->
                    // The one join path: it opens the channel once we're in it, and says why when
                    // we aren't (lurker-ios#57). Typed in a buffer on that network, so opening it is
                    // what `/join` means.
                    // Not after a send in the same line went nowhere (`/cycle`'s part): the line is
                    // coming back to the composer, and a join notice on top would say it twice.
                    if (networkId != null && !wentNowhere) {
                        requestJoin(networkId = networkId, channel = effect.channel, key = effect.key, opens = true)
                    }
                is CommandEffect.Part ->
                    wire { client.part(networkId = networkId, channel = effect.channel, reason = effect.reason) }
                is CommandEffect.Close -> {
                    // As `closeBuffer` does: a `/close` stands down a pending open for that buffer.
                    pendingOpens.closing(BufferKey(networkId = networkId, target = effect.target))
                    // The server log can't be closed: nothing goes out, so there's nothing to hand back.
                    when (BufferKind.of(networkId = networkId, target = effect.target)) {
                        BufferKind.Server, BufferKind.System -> Unit
                        else -> wire { client.closeBuffer(networkId = networkId, target = effect.target) }
                    }
                }
                is CommandEffect.Clear ->
                    // Nothing is written locally, deliberately. The server picks the exact boundary
                    // id (the current tail) and fans a `buffer-cleared` back to every device
                    // including this one, so the marker this screen draws is always the
                    // authoritative one — an optimistic local clear would have to guess the id and
                    // would be wrong for anything that landed in between.
                    wire { client.clearBuffer(networkId = networkId, target = effect.target, undo = effect.undo) }
                is CommandEffect.Away ->
                    wire { client.setAway(effect.message, networkId = networkId, all = effect.all) }
                is CommandEffect.Back ->
                    wire { client.setBack(networkId = networkId, all = effect.all) }
                is CommandEffect.Ctcp ->
                    wire {
                        client.sendCTCP(
                            networkId = networkId, target = effect.target, issuingTarget = key.target,
                            ctcpType = effect.type, args = effect.args,
                        )
                    }
                is CommandEffect.Activate -> if (networkId != null) {
                    val to = BufferKey(networkId = networkId, target = effect.target)
                    if (wentNowhere) {
                        // `/msg … hi`'s line went nowhere: it is coming back to this composer to be
                        // sent again, and going anywhere would take the user away from it — a
                        // channel as much as a nick.
                    } else if (ChannelName.isChannelTarget(effect.target)) {
                        // A channel: switch at once, as before lurker-ios#201. `open-buffer` JOINs
                        // only a `#` channel and mints no row for the rest, so there is no row to
                        // wait for.
                        client.openBuffer(networkId = networkId, target = effect.target, countBy = historyCountBy)
                        outcome = SendOutcome.Activate(to)
                    } else {
                        // A nick: mint/hydrate the DM row and switch to it once it's here. A
                        // brand-new /query target isn't in `state.buffers` yet, so the destination
                        // screen's own hydrate wouldn't fire — this `open-buffer` is what brings
                        // the row (and its backlog) into being, and going there before it lands
                        // bounces off a settled roster (lurker-ios#201). See `openAndShow`.
                        //
                        // An `open-buffer` that couldn't go out refuses a bare `/query`, as a send
                        // that went nowhere does — it comes back to the composer. Minted here
                        // because nothing else in a bare `/query` would have. Never `/msg bob hi`'s:
                        // its line already went, and handing it back would have it sent twice — so
                        // say why the screen stayed put instead, as Send Message does.
                        if (!openThenShow(to)) {
                            if (lineId == null) {
                                correlator()
                                wentNowhere = true
                            } else {
                                sayDmNotConnected(to)
                            }
                        }
                    }
                }
                is CommandEffect.AddIgnore ->
                    // `scope`, not `networkId`: null is a global rule, which is the default and the
                    // one an unqualified `/ignore bob` makes. Nothing is written locally — the
                    // rule arrives on the server's `ignore-list-updated` fan-out, the same path a
                    // rule made on the web takes to get here.
                    report(client.addIgnore(networkId = effect.scope, rule = effect.rule), effect.receipt, key)
                is CommandEffect.RemoveIgnore ->
                    report(
                        client.removeIgnore(networkId = effect.scope, id = effect.id, mask = effect.mask),
                        effect.receipt,
                        key,
                    )
                is CommandEffect.SetRelayBot ->
                    // The effect's own network, not the buffer's — they're the same today (the parser
                    // takes it from this buffer), but a mark is *about* a connection rather than sent
                    // on one, and reading it off `key` here would quietly make that untrue the first
                    // time anything issues one from somewhere else.
                    report(
                        client.setRelayBot(
                            networkId = effect.networkId, nick = effect.nick, marked = effect.marked,
                            pattern = effect.pattern,
                        ),
                        effect.receipt,
                        key,
                    )
                is CommandEffect.DccChat ->
                    dcc(key) { networkId ->
                        openDccChat(networkId = networkId, nick = effect.nick, passive = effect.passive)
                    }
                is CommandEffect.DccCloseChat ->
                    dcc(key) { networkId ->
                        closeDccChat(networkId = networkId, nick = effect.nick)
                    }
                CommandEffect.Connect ->
                    lifecycle(NetworkAction.Connect, key)
                is CommandEffect.Disconnect ->
                    lifecycle(NetworkAction.Disconnect, key, reason = effect.reason)
                CommandEffect.Reconnect ->
                    lifecycle(NetworkAction.Reconnect, key)
                is CommandEffect.React ->
                    react(effect.value, key)
                is CommandEffect.Info ->
                    store.appendLocal(key, text = effect.text)
            }
        }
        val id = lineId
        if (wentNowhere && id != null) refuse(id)
        return outcome
    }

    /**
     * `/react` (lurker-ios#183): the value on the last line someone else said here. Nothing
     * prints on success — the chip lighting up when the network echoes it is the answer, and a
     * refusal is silence there too, the same as a tap on the sheet.
     */
    private fun react(value: String, key: BufferKey) {
        val state = store.state
        if (!(state.canReact(networkId = key.networkId) && Reactions.isConversation(key.target))) {
            store.appendLocal(key, text = "this network can't carry reactions right now")
            return
        }
        // Parked on a jump's slice (lurker-ios#42), the newest lines held aren't the conversation's
        // newest, and "the last thing someone said" would land on a line from whenever the jump
        // went.
        if (state.buffers[key.id]?.hasMoreNewer == true) {
            store.appendLocal(key, text = "jump back to the latest messages to react with /react")
            return
        }
        when (val target = Reactions.commandTarget(state.messages[key.id].orEmpty())) {
            is Result.Failure ->
                store.appendLocal(key, text = target.error.text)
            is Result.Success -> {
                val line = target.value
                if (state.reactions[line.id].orEmpty().any { it.isSelf && it.value == value }) {
                    store.appendLocal(key, text = "you already reacted $value to ${line.nick ?: "that"}")
                    return
                }
                if (!client.react(messageId = line.id, value = value, remove = false)) {
                    store.appendLocal(key, text = "not connected — the reaction wasn't sent")
                }
            }
        }
    }

    /**
     * Run one of the REST connection verbs on `key`'s network and print its refusal, if any,
     * into the buffer the command was typed in (lurker-ios#152).
     *
     * Nothing on success, on purpose. These answer `{ok:true}` the moment the server has told
     * its connection manager, and the transition arrives separately as `state` events: the
     * title's light and the buffer list's section header move on their own, and the server
     * buffer narrates "Connecting to …" and "Disconnected" itself. A receipt here would be the
     * app's own word for something the server hasn't done yet — the reason the networks
     * screen applies nothing optimistically, and the silence the web's `/quit` keeps.
     *
     * Not routed through `report`: that one gates on the *socket*, because the verbs it
     * covers go nowhere without one. These go over HTTP and answer for themselves.
     *
     * The failure line names the action rather than the command — `/quit` is an alias of
     * `/disconnect`, and "/disconnect failed" under a line that said `/quit` reads as the
     * app answering a different question.
     *
     * Port note: `capitalized` upper-cases the first letter of the raw value, which is one
     * lower-case ASCII word for every action.
     */
    private fun lifecycle(action: NetworkAction, key: BufferKey, reason: String? = null) {
        val networkId = key.networkId ?: return
        scope.task {
            val refusal = perform(action, id = networkId, reason = reason) ?: return@task
            store.appendLocal(key, text = "${action.rawValue.replaceFirstChar { it.uppercase() }} failed: $refusal")
        }
    }

    /**
     * Run a `/dcc` verb on `key`'s network and print its refusal, if any, where it was typed.
     *
     * Silent on success, like `lifecycle` and for the same reason: the server narrates the chat
     * itself, in the `=nick` buffer — "Offered a DCC chat…", "connected", "closed" — and a
     * receipt here would be the app's own word for something that hasn't happened yet.
     */
    private fun dcc(key: BufferKey, verb: suspend (Int) -> String?) {
        val networkId = key.networkId ?: return
        scope.task {
            val refusal = verb(networkId) ?: return@task
            store.appendLocal(key, text = "/dcc failed: $refusal")
        }
    }

    /**
     * Offer a DCC chat to `nick`, or accept the one they offered (lurker#270). Null on success, a
     * message otherwise.
     *
     * On success the app is taken to the chat's `=nick` buffer through `onBufferOpened` — at
     * once if the row exists, else as soon as the server mints it (see `PendingOpens`).
     */
    suspend fun openDccChat(networkId: Int, nick: String, passive: Boolean = false): String? {
        // The newest ask to be taken somewhere: what was waiting before it stands down
        // (lurker-ios#201).
        supersedeLandings()
        // Taken before the request goes out, so a later open, a close or a sign-out that lands
        // while it's in flight can make its reply stale — see `PendingOpens`.
        val ticket = pendingOpens.begin(DccChat.key(networkId = networkId, nick = nick))
        client.openDccChat(networkId = networkId, nick = nick, passive = passive)?.let { refusal ->
            return refusal
        }
        pendingOpens.opened(ticket, now = Instant.now())
        settlePendingOpen()
        return null
    }

    /**
     * End a DCC chat with `nick`, cancel our offer to them, or decline theirs. Null on success.
     *
     * ⚠ A chat being closed stops being waited for — and so does one whose open is still in
     * flight — BEFORE the request goes out. The server writes its "Cancelled…" notice into
     * `=nick` as it acts, which mints the row, and that frame usually beats the HTTP reply; a wait
     * still standing then would take the user into the chat they were ending. A refused close
     * ended nothing, so it puts the wait back.
     */
    suspend fun closeDccChat(networkId: Int, nick: String): String? {
        val mark = pendingOpens.closing(DccChat.key(networkId = networkId, nick = nick))
        val refusal = client.closeDccChat(networkId = networkId, nick = nick)
        if (refusal != null) pendingOpens.closeRefused(mark)
        return refusal
    }

    /**
     * Print a command's receipt, or say why there isn't one.
     *
     * `sent` is what `LurkerClient.send` returned. It is necessary and **not sufficient**: it
     * only says a socket object was there to hand the frame to, and a dropped socket isn't
     * nulled — `handleClose` reports the closure and leaves the socket assigned, so a write into
     * a connection that died while backgrounded returns true. `state.connection` is the flag
     * that actually knows, because it's driven by the open/closed frames themselves.
     *
     * Nothing queues these verbs, so anything short of both being true means it went nowhere
     * and will not be retried — and a receipt printed anyway is the app's own word for
     * something that didn't happen. The failure line says what to do about it.
     */
    private fun report(sent: Boolean, receipt: String, key: BufferKey) {
        val delivered = sent && store.state.connection == SocketStatus.Connected
        store.appendLocal(
            key,
            text = if (delivered) receipt else "Not sent — you're not connected. Try again once you're back.",
        )
    }

    /**
     * Page older history for a buffer (scroll-up). Uses the oldest held message id as an
     * exclusive cursor; no-ops if nothing older exists, nothing is held yet, or a page is
     * already in flight.
     * Returns whether a page is ON ITS WAY — not whether this particular call sent one.
     *
     * ⚠⚠ A page already in flight counts. The caller asks so it can show a spinner instead of
     * an empty state, and "someone already asked" is a yes to that question. Reading it as
     * "did I just send one" flashed "No messages yet" over a buffer whose page was in the air:
     * any second apply before the reply landed — a filtered live message, a read-state frame —
     * re-asked, got false, and resolved the placeholder to empty.
     */
    fun loadOlder(key: BufferKey, showingClearedHistory: Boolean = false): Boolean {
        if (loadingOlder.contains(key.id)) return true
        val buffer = store.state.buffers[key.id]
        if (buffer == null || !buffer.hasMoreOlder) return false
        val oldest = store.state.messages[key.id]?.firstOrNull { it.id != 0L }?.id ?: return false
        // ⚠⚠ Never page past a `/clear` boundary (lurker-ios#121) — see
        // `olderPageCouldBeVisible`, which holds the rule (and the reason) where it can be tested.
        if (!buffer.olderPageCouldBeVisible(oldestHeldId = oldest, showingClearedHistory = showingClearedHistory)) {
            return false
        }
        loadingOlder.add(key.id)
        client.loadOlder(networkId = key.networkId, target = key.target, before = oldest, countBy = historyCountBy)
        return true
    }

    /**
     * Page newer history for a detached buffer (scroll-down, lurker-ios#45). Uses the newest held
     * message id as an exclusive cursor; no-ops unless the buffer is detached (an `around`
     * slice below the tail), something is held to page from, and no page is already in flight.
     * The reply appends; reaching the tail (`hasMoreNewer: false`) re-attaches the buffer to
     * live. This is how a jump-to-first-unread reader walks forward to the present.
     */
    fun loadNewer(key: BufferKey) {
        if (loadingNewer.contains(key.id)) return
        val buffer = store.state.buffers[key.id]
        if (buffer == null || !buffer.hasMoreNewer) return
        val newest = store.state.messages[key.id]?.lastOrNull { it.id != 0L }?.id ?: return
        loadingNewer.add(key.id)
        client.loadNewer(networkId = key.networkId, target = key.target, after = newest, countBy = historyCountBy)
    }

    /**
     * Fetch a history slice centered on `anchorId` — the message a jump lands on (lurker-ios#42).
     * The reply (`history` mode `around`) replaces the buffer's slice with the anchor in the
     * middle; the screen scrolls to it once it lands. Distinct from `loadOlder`: this
     * hydrates a buffer *around* a target rather than paging the tail, so it isn't gated on
     * what's already held.
     */
    fun loadAround(key: BufferKey, anchorId: Long) {
        client.loadAround(
            networkId = key.networkId, target = key.target, anchorId = anchorId, countBy = historyCountBy,
        )
    }

    /**
     * Re-attach a detached buffer to the live tail (lurker-ios#42) — the "return to live" a
     * jump-to-latest tap makes after a jump parked the screen on an older `around` slice. The
     * reply replaces the slice with the latest and clears the buffer's detached flag.
     */
    fun loadLatest(key: BufferKey) {
        client.loadLatest(networkId = key.networkId, target = key.target, countBy = historyCountBy)
    }

    /**
     * Mark a buffer read up to its latest loaded message. Server-authoritative and
     * MAX-clamped, and deduped here, so calling it on every state change while viewing a
     * buffer is cheap. The `read-state` echo updates the counts.
     *
     * ⚠ Recorded only once it has gone out. A mark with no socket goes nowhere, and recording it
     * anyway had the dedupe skip the same id after the reconnect — the pointer never moved, on
     * this device's badge or anyone else's (sweep L23).
     */
    fun markRead(key: BufferKey) {
        if (!canWrite) return
        val latest = store.state.messages[key.id]?.mapNotNull { if (it.id != 0L) it.id else null }?.maxOrNull()
            ?: return
        if (latest <= (lastMarked[key.id] ?: 0L)) return
        if (client.markRead(networkId = key.networkId, target = key.target, messageId = latest)) {
            lastMarked[key.id] = latest
        }
    }

    fun markAllRead() {
        client.markAllRead()
    }

    // MARK: - Networks (lurker-ios#11)

    /** The account's networks as editable configuration. Null is "couldn't ask", never "none". */
    suspend fun networkConfigs(): List<NetworkConfig>? = client.networkConfigs()

    /**
     * The networks this instance recommends, and whether users may add anything else.
     * Null is "couldn't ask" — the picker still has the bundled catalogue.
     */
    suspend fun networkPresets(): NetworkPresets? = client.networkPresets()

    /**
     * Create a network. The server connects it immediately on success.
     *
     * The roster is re-read afterwards because nothing else will name the new network: it
     * reaches this client over the socket, and neither the `snapshot` nor any live frame
     * carries a network's name.
     */
    suspend fun createNetwork(draft: NetworkDraft): NetworkSaveResult {
        val result = client.createNetwork(draft)
        // `savedWithoutDetail` re-reads the roster itself, at the point that knows the write
        // landed — so only the ordinary success needs one here.
        if (result is NetworkSaveResult.Saved) client.refreshNetworks()
        return result
    }

    /**
     * Edit a network. Takes effect on its next connection — an established one keeps what it
     * registered with — so a caller that changed the nick or host owes the user that fact.
     */
    suspend fun updateNetwork(id: Int, draft: NetworkDraft): NetworkSaveResult {
        val result = client.updateNetwork(id = id, draft = draft)
        // `savedWithoutDetail` re-reads the roster itself, at the point that knows the write
        // landed — so only the ordinary success needs one here.
        if (result is NetworkSaveResult.Saved) client.refreshNetworks()
        return result
    }

    /**
     * Attach a client certificate to a network (lurker#459) — generate one, or import a pair —
     * replacing any it had. Written immediately; used from the network's next connection.
     */
    suspend fun attachCertificate(networkId: Int, source: CertificateSource): CertificateResult =
        client.attachCertificate(networkId = networkId, source = source)

    suspend fun removeCertificate(networkId: Int): CertificateResult = client.removeCertificate(networkId = networkId)

    /** The network's key and certificate as one PEM file, for keeping or for another client. */
    suspend fun exportCertificate(networkId: Int): CertificateExport = client.exportCertificate(networkId = networkId)

    /**
     * Start, stop, restart or delete a network. Null on success, a message otherwise.
     *
     * One entry for the four verbs whoever issues them — the networks screen's menu, the
     * server buffer's sheet, a slash command — so an action maps to its REST call in one
     * place instead of a switch per surface.
     *
     * None of the connection verbs reports the resulting state: the server acknowledges the
     * instruction, and the transition arrives separately as `state` events. A caller that
     * wanted to show "Connecting…" should read the network's state, not this return value.
     * `reason` is the quit message, read by `disconnect` alone.
     */
    suspend fun perform(action: NetworkAction, id: Int, reason: String? = null): String? =
        when (action) {
            NetworkAction.Connect -> client.connectNetwork(id = id)
            NetworkAction.Disconnect -> client.disconnectNetwork(id = id, reason = reason)
            NetworkAction.Reconnect -> client.reconnectNetwork(id = id)
            NetworkAction.Delete -> {
                // Everything under it goes too. The roster is re-read on success because nothing
                // else will retract the network: no frame carries a removal.
                val error = client.deleteNetwork(id = id)
                if (error == null) client.refreshNetworks()
                error
            }
        }

    // MARK: - Channel controls (lurker#727)

    /** Why a channel change didn't go out — or might not have. */
    @ConsistentCopyVisibility
    data class ChannelSaveFailure internal constructor(
        /** What to tell the user. */
        val message: String,
        /**
         * True when nothing can have reached IRC: the server refused before sending. False when
         * the answer simply never came, and the change may well have gone out.
         */
        val certainlyUnsent: Boolean,
    )

    /**
     * Set a channel's topic. Null when it went out.
     *
     * "Went out" is all the answer can say: a refusal (482 under `+t`) arrives as the channel's
     * `error` row, and the topic itself changes when the `topic` line comes back.
     */
    suspend fun setTopic(key: BufferKey, topic: String): ChannelSaveFailure? {
        val networkId = key.networkId ?: return ChannelSaveFailure(message = "Not connected.", certainlyUnsent = true)
        return saveFailure(client.setTopic(networkId = networkId, channel = key.target, topic = topic))
    }

    /**
     * Send mode changes to a channel, as the fewest MODE lines the network allows. Null when they
     * went out — the same "only that" as `setTopic`.
     */
    suspend fun setChannelModes(key: BufferKey, changes: List<OutgoingModeChange>): ChannelSaveFailure? {
        val networkId = key.networkId ?: return ChannelSaveFailure(message = "Not connected.", certainlyUnsent = true)
        return saveFailure(
            client.setChannelModes(networkId = networkId, channel = key.target, changes = changes),
        )
    }

    /** Fetch one of a channel's lists — `b`, `e`, `I`, or `q` where it's a list. */
    suspend fun fetchModeList(key: BufferKey, letter: String): ModeListResult {
        val networkId = key.networkId ?: return ModeListResult.Failed("Not connected.")
        val reply = client.fetchModeList(networkId = networkId, channel = key.target, letter = letter)
        val entries = reply.entries
        if (reply.ok && entries != null) return ModeListResult.Entries(entries)
        // Nothing to ask yet, or the socket took the answer with it: worth asking again once
        // the link is back, which a refusal isn't.
        if (reply.error == "not-connected" || reply.error == "connection-lost") return ModeListResult.Offline
        return ModeListResult.Failed(listError(reply))
    }

    /**
     * The key the server holds for a channel, read fresh from the network config — the only
     * place a key reaches this client. Null when there's none, or the read failed.
     */
    suspend fun storedChannelKey(key: BufferKey): String? {
        val networkId = key.networkId ?: return null
        val config = client.networkConfigs()?.firstOrNull { it.id == networkId } ?: return null
        return config.key(key.target)
    }

    /**
     * Re-read the roster (`GET /api/networks`) into the store: names, order, and whether the
     * admin's allowlist blocks each host.
     *
     * For a screen that wants the roster's *current* word before offering to connect a
     * network — the server buffer's sheet — rather than the copy read when the socket last
     * opened. The networks screen re-reads on every appearance for the same reason; without
     * this the two could disagree about an allowlist change for the life of the socket.
     */
    suspend fun refreshNetworks() {
        client.refreshNetworks()
    }

    /**
     * Emit a `typing` signal for `key`. Buffers with nobody on the other end — the system
     * buffer, a `:server:` log — are dropped by the client, which owns that guard for every
     * conversation-only verb (see `LurkerClient.setTyping`).
     *
     * Gated on `chat.send_typing_notifications` here rather than at the call site: this is a
     * privacy switch, and a gate you have to remember to apply isn't one. The web enforces
     * the same setting purely client-side (`MessageInput.vue:544`) — `ircManager.typing` does
     * no gating of its own — so honoring it here is the whole mechanism, not half of one.
     */
    fun setTyping(key: BufferKey, signal: TypingSignal) {
        if (!store.state.settings.bool("chat.send_typing_notifications", default = true)) return
        client.setTyping(networkId = key.networkId, target = key.target, signal = signal)
    }

    /**
     * Write settings (lurker-ios#65). The server validates, stores, and fans a `settings` frame
     * back to every device — this one included — so the store updates from that echo rather
     * than optimistically here: one path for "a setting changed", whatever caused it, and a
     * rejected write simply never lands instead of needing to be rolled back.
     *
     * Returns the server's own error message on failure, null on success. Also null, with nothing
     * applied, when the session that asked ended while the write was out: its screen is gone, and its
     * reply must not reach the next session.
     */
    suspend fun updateSettings(changes: Map<String, SettingValue>): String? = client.updateSettings(changes)

    /**
     * Join a channel: the one path every join takes — the composer's `/join`, the Join Channel
     * sheet, a channel on someone's profile, a parted row's Join (lurker-ios#57).
     *
     * A join is a request the server can refuse, forward, or never answer, so nothing moves until
     * it does. `channel-joined` opens the channel when `opens` asked for that. A refusal, or no
     * answer within `PendingJoins.timeout`, comes back as a `JoinNotice`. A 470 forward's part for
     * the asked-for name is dropped quietly, the forwarded channel arriving as its own row. A
     * channel we're already in opens at once.
     *
     * ⚠ Not sent unless this device, this app's socket and the network are all connected: the
     * server drops a JOIN for a network that's down without a word, so the user hears it here.
     */
    fun requestJoin(networkId: Int, channel: String, key: String? = null, opens: Boolean) {
        val joinKey = key
        val name = channel.trimmingWhitespacesAndNewlines()
        // A list (`/join #a,#b`) is one JOIN as typed, but each channel in it is answered, and so
        // tracked, on its own. Only the first opens: there's one screen to land on.
        val keys = PendingJoins.channels(name).map { BufferKey(networkId = networkId, target = it) }
        val first = keys.firstOrNull() ?: return
        val toJoin = keys.filter { store.state.buffers[it.id]?.joined != true }
        // Already in the first: it opens now, before anything is asked of the connection. A joined
        // row outlives a socket drop, and opening it needs nothing sent.
        if (opens) {
            val row = store.state.buffers[first.id]
            if (row != null && row.joined) {
                supersedeLandings()
                onJoinOpened?.invoke(row.key)
            }
        }
        // ⚠ All three, not just the network's row — see `canWrite`.
        val network = store.state.networks[networkId]
        // A join that will open is the newest ask to be taken somewhere (lurker-ios#201) — before
        // it is sent, so one that can't be still stands the older ones down (see `openThenShow`).
        if (opens && toJoin.contains(first)) supersedeLandings()
        if (!(store.state.canWrite(networkId = networkId) && client.joinChannel(networkId = networkId, channel = name, key = joinKey))) {
            // Nothing to say when every channel was already open: that was `/join` for a channel
            // you're in, and it opened.
            if (toJoin.isNotEmpty()) {
                onJoinNotice?.invoke(JoinNotice.NotConnected(channel = name, network = network?.displayName ?: "the network"))
            }
            return
        }
        // Sent for channels we're already in too, because `/cycle` joins right behind its own part
        // while the row still reads joined. That rejoin isn't tracked: its own part would read as
        // a forward. A refused one shows as the parted row it leaves.
        for ((index, bufferKey) in keys.withIndex()) {
            if (!toJoin.contains(bufferKey)) continue
            pendingJoins.request(bufferKey, opens = opens && index == 0, now = Instant.now())
        }
        if (toJoin.isEmpty()) return
        scope.task {
            delay(PendingJoins.timeout.toKotlinDuration())
            for (outcome in pendingJoins.expire(now = Instant.now())) settle(outcome)
        }
    }

    /** Act on what became of a join this device asked for. */
    private fun settle(outcome: PendingJoins.Outcome) {
        when (outcome) {
            is PendingJoins.Outcome.Joined ->
                // The stored row's key, so the screen that opens is the one the server named.
                if (outcome.opens) onJoinOpened?.invoke(store.state.buffer(outcome.key).key)
            is PendingJoins.Outcome.Refused ->
                onJoinNotice?.invoke(JoinNotice.Refused(channel = outcome.key.target, reason = outcome.reason))
            is PendingJoins.Outcome.TimedOut ->
                onJoinNotice?.invoke(JoinNotice.NoResponse(channel = outcome.key.target))
        }
    }

    /**
     * Close a buffer (part a channel / drop a DM) and remove its row immediately.
     *
     * False, with the row and its draft left alone, when the close couldn't go out. Removing the
     * row anyway left the channel joined and the draft unsent, and the reconnect's snapshot put
     * the row straight back with no account of why (sweep L16) — so the caller says so instead.
     */
    fun closeBuffer(key: BufferKey): Boolean {
        // A close stands down a wait for the same buffer: a DM closed while its open is pending
        // would otherwise be minted again by the late backlog and taken back into (lurker-ios#201).
        // Whether or not the close goes out — the user has said they're done with it.
        pendingOpens.closing(key)
        if (!canWrite || !client.closeBuffer(networkId = key.networkId, target = key.target)) return false
        dropDraft(key)
        store.removeBuffer(key)
        return true
    }

    /**
     * The networks the user is on, unordered.
     *
     * ⚠ Unordered, so it is only good for a lookup by id — which is its one remaining caller
     * (on iOS, `BufferInfoViewController`, resolving a buffer's network name). Anything that
     * *lists* networks wants `BufferOrder.networks`, which puts them in the order the user
     * arranged them in; the buffer list, the join sheet and the networks screen all do.
     */
    val networks: List<Network> get() = store.state.networks.values.toList()

    // MARK: - Favorites (the Friends/Contacts successor)

    /**
     * The global favorites list, in the user's order. The UI splits it by kind (DMs →
     * Friends, channels → Favorites) and reads presence live off `state`.
     */
    val favorites: List<FavoriteEntry> get() = store.state.favorites

    /**
     * Fired after every `favorites-changed` fold — the connect-burst seed included, which
     * makes the first firing proof the socket is live AND the server speaks favorites.
     * The app hangs its one-shot local→server favorites migration off exactly that.
     */
    var onFavoritesSynced: (() -> Unit)? = null

    /**
     * Favorite/unfavorite a buffer. No local mutation — the UI updates when the server's
     * `favorites-changed` echo folds in, same as every other server-authoritative list.
     */
    fun favoriteBuffer(networkId: Int, target: String) {
        client.favoriteBuffer(networkId = networkId, target = target)
    }

    fun unfavoriteBuffer(networkId: Int, target: String) {
        client.unfavoriteBuffer(networkId = networkId, target = target)
    }

    /**
     * `/back` from a control rather than the composer — the away strip's Back (lurker-ios#135),
     * on the network the strip is showing, scoped as a typed `/back` is (lurker#994). No local
     * mutation: the strip comes down when the server's `away-state` echo folds in, on every
     * device at once. False when it went nowhere.
     */
    fun setBack(networkId: Int?): Boolean = canWrite && client.setBack(networkId = networkId, all = null)

    /**
     * Ask the network who `nick` is (lurker-ios#12) — what the profile screen sends on open, and
     * what its Refresh does.
     *
     * Always re-asks rather than trusting the cache: presence, idle time and channel list go
     * stale within minutes, so the cached reply is there to render *immediately* while this
     * round trip is out, not to save it.
     *
     * ⚠⚠ The two guards are not interchangeable and both are load-bearing (lurker#818):
     * - skipping while a lookup for this exact nick is already out keeps a reopen from
     *   spamming the server, **without** leaving a failed lookup un-retryable — a `not_found`
     *   frees the slot on arrival like any other answer;
     * - claiming the slot only when the send returns true keeps a WHOIS that never left the
     *   socket from wedging that nick forever, since no reply is coming to free it.
     *
     * There is deliberately no timeout. A server that answers ERR_NOSUCHNICK and then sends
     * no RPL_ENDOFWHOIS produces no signal at all (see `WhoisResult.isNotFound`), and such a
     * lookup stays pending — the same behaviour the web has. Worth revisiting with evidence
     * of a server that does it, not before.
     * ⚠ Trimmed first, and the trimmed nick is what's both sent and keyed. Two reasons, and
     * the second outlives the first:
     * - `WHOIS " "` draws ERR_NONICKNAMEGIVEN, which irc-framework doesn't map at all — so no
     *   `whois_result` ever arrives and, with no timeout, that slot is claimed for good;
     * - keying on the padded form while the server answers with the bare one means the reply
     *   frees a slot nobody claimed, and the claimed one is never freed. Same wedge, reachable
     *   without any malformed input at all.
     *
     * `setNickNote` trims for the first reason too; both entry points here now agree.
     *
     * ⚠⚠ And a `=bob` DCC chat buffer name becomes bob. This goes out as a `raw` line, which
     * skips every `=` guard the server has — by design, since it is what `/quote` uses — so a
     * caller handing over the buffer's target would put `WHOIS =bob` on the wire. Normalized
     * here, at the one door every profile goes through, rather than trusted to each screen.
     */
    fun requestWhois(networkId: Int, nick: String) {
        val peer = DccChat.peer(nick.trimmingWhitespacesAndNewlines())
        if (peer.isEmpty() || store.state.isWhoisPending(networkId = networkId, nick = peer)) return
        if (client.sendRaw(networkId = networkId, line = "WHOIS $peer")) {
            store.markWhoisPending(networkId = networkId, nick = peer)
        }
    }

    /**
     * Write or clear the note about a nick (lurker-ios#12). No local mutation — the note appears
     * when the server's `nick-note-updated` echo folds in, like every other server-authoritative
     * list here. An empty `note` deletes it.
     *
     * ⚠ A `=bob` DCC chat is a conversation with bob, so its note IS bob's note. The server
     * stores whatever nick it is handed, so without this a note written from the chat would be
     * filed under `=bob` — a second note about the same person that the DM with bob never shows.
     *
     * False when it went nowhere: the editor stays open with what was typed (sweep L14).
     */
    fun setNickNote(networkId: Int, nick: String, note: String): Boolean =
        canWrite && client.setNickNote(networkId = networkId, nick = DccChat.peer(nick), note = note)

    /**
     * Rewrite the global order — pass the FULL permuted bufferId list (see LurkerClient).
     * False when it went nowhere, and then no echo is coming to settle a drop (sweep L29).
     */
    fun reorderFavorites(bufferIds: List<Int>): Boolean = canWrite && client.reorderFavorites(bufferIds = bufferIds)

    fun clearError() {
        store.clearError()
    }

    // MARK: - App lifecycle (fed by the app; on iOS, the SceneDelegate)

    /**
     * Back on screen: a socket that died in the background often hasn't fired its failure
     * yet (the connection is suspended while backgrounded), so the status can still read
     * Connected over a dead socket. Reconnect if we're disconnected OR were backgrounded
     * long enough that the socket may be stale; a brief app-switch leaves a healthy
     * socket alone.
     *
     * Returns whether the socket was kept: `true` means nothing in `state` is about to be
     * replaced by a reconnect's burst, so a caller may treat the counts as current (the
     * app-badge re-assert does, lurker-ios#134). `false` when a reconnect was kicked — the state
     * is a pre-background leftover until the burst lands — or there is no session.
     */
    fun enterForeground(): Boolean {
        isForeground = true
        // A server that was down while the phone stayed online gets asked again here (lurker-ios#218).
        retryPendingRevokes()
        if (session != SessionState.LoggedIn) return false
        // Tell the server we're looking, so it stops pushing (lurker#490). Sent before the
        // reconnect check below because the common case is a LIVE socket — we're back and
        // nothing needs reopening — and that path returns early. A new socket re-asserts
        // presence itself, so sending here too is at worst a duplicate the server folds.
        client.setPresence(true)
        // A restore held for this moment (`startsInForeground`): start it now, as the restore would
        // have. Not a reconnect — that resumes from `since`, and there is nothing yet to resume.
        if (startDeferred) {
            startDeferred = false
            startRestored()
            return false
        }
        val stale = backgroundedAt?.let { Duration.between(it, Instant.now()) > staleAfter } ?: false
        if (store.state.connection == SocketStatus.Connected && !stale) return true
        reconnectAttempt = 0
        reconnectTask?.cancel()
        reconnectTask = null
        doReconnect(force = true)
        return false
    }

    /**
     * `onFlush` fires once the presence frame is on the wire — the app holds a
     * background-task assertion until then, because this frame is sent in the one window
     * where the OS is actively trying to suspend us. Always called, including when there was
     * nothing to send, so the caller can't leak the assertion.
     *
     * Port note: `onFlush` is called from a `finally`, so a scope cancelled while the flush is out
     * still calls it, as LurkerKit's `[weak self]` branch does when the model is gone. A scope
     * already cancelled before the flush starts runs none of it, `onFlush` included.
     */
    fun enterBackground(onFlush: (() -> Unit)? = null) {
        isForeground = false
        backgroundedAt = Instant.now()
        // The moment that makes push work: until the server hears this it believes a
        // client is watching and suppresses every notification. The socket usually
        // survives backgrounding for a while, so waiting for it to drop would mean up to
        // ~60s of silence (the server pings every 30s and reaps on the second miss).
        //
        // Not covered here: a force-quit or a tunnel, where nothing gets sent and that
        // reaper IS the backstop. That gap is real and known — see lurker#490.
        if (session != SessionState.LoggedIn) {
            onFlush?.invoke()
            return
        }
        // Drafts too, the composing one included: the app may not come back, and what was being
        // typed should be waiting on every other device.
        val edits = takeDraftsForBackground()
        if (edits.isEmpty()) {
            client.setPresence(false, onFlush = onFlush)
            return
        }
        // ⚠ Over HTTP, never the socket — the route the web's `pagehide` beacon uses, for the
        // same reason. This is exactly the moment a socket is likeliest to be dead without
        // anyone knowing: it takes the write, the write fails after we're suspended, and the
        // draft reaches nobody. The background assertion is held until the server answers.
        //
        // ⚠ `onFlush` waits for BOTH: it ends the app's background assertion, and the presence
        // frame is what turns push back on — suspended with it still queued, the server stays
        // quiet until it reaps the socket.
        //
        // Port note: a `Task` always runs, and its `guard let self else { onFlush?() }` keeps the
        // promise above when the model is gone; a `launch` on a cancelled scope never does, so
        // the promise is kept here instead.
        if (!scope.isActive) {
            onFlush?.invoke()
            return
        }
        scope.task {
            try {
                val saved = async { client.flushDrafts(edits.map { LurkerClient.KeyedDraft(key = it.key, draft = it.draft) }) }
                suspendCancellableCoroutine<Unit> { sent ->
                    client.setPresence(false) { if (sent.isActive) sent.resume(Unit) }
                }
                if (saved.await()) {
                    for (edit in edits) draftSync.settle(edit)
                }
            } finally {
                onFlush?.invoke()
            }
        }
    }

    /**
     * Every waiting edit, composing or not, into the store and kept waiting — until the HTTP
     * flush settles it, or the next connect's snapshot sends it, should that flush not make it.
     */
    private fun takeDraftsForBackground(): List<DraftSync.Edit> {
        for (task in draftFlushes.values) task.cancel()
        draftFlushes.clear()
        val edits = draftSync.takeAll()
        for (edit in edits) {
            store.setDraft(edit.key, edit.draft)
            draftSync.restore(edit)
        }
        return edits
    }

    /**
     * The OS's network path came or went. Fed in from the app (which owns the platform's
     * network monitor) for the same reason foreground/background is — it keeps this module off
     * the platform's networking types, which on iOS would also have to share a namespace with
     * our own `Network` model.
     *
     * Regaining a path also short-circuits the backoff: the reason we were waiting just
     * went away, and a user who reconnects to wifi shouldn't watch a 30s timer run down.
     */
    fun setReachable(reachable: Boolean) {
        val was = store.state.reachable
        store.setReachable(reachable)
        // Signed in or not: the case this is for is a sign-out made offline (lurker-ios#218).
        if (reachable && !was) retryPendingRevokes()
        if (!(reachable && !was && session == SessionState.LoggedIn && isForeground)) return
        reconnectAttempt = 0
        reconnectTask?.cancel()
        reconnectTask = null
        doReconnect(force = false)
    }

    // MARK: - Revokes owed (lurker-ios#218)

    /**
     * Tokens with a revoke request out now, so a launch and a reachability change (or the sign-out's
     * own attempt) don't send the same one twice.
     */
    private val revokingNow = mutableSetOf<String>()

    /** See [revokingNow]; read by the tests. */
    internal val revoking: Set<String> get() = revokingNow.toSet()

    /**
     * Tokens a retry trigger (a foreground, the network coming back) skipped because their request
     * was already out. If that request then fails, the trigger is replayed at once — the moment it
     * stood for (the network is back) has already happened, and nothing else may come along to ask.
     */
    private val retryWanted = mutableSetOf<String>()

    /**
     * Ask again for every revoke this device still owes: on launch, on every foreground, and whenever
     * the network comes back. Never the live session's token — a pending entry can't name it, but
     * revoking it would sign the user out from under themselves, so it's checked rather than assumed.
     */
    internal fun retryPendingRevokes(now: Instant = Instant.now()) {
        // Almost always empty — checked before [sendRevoke]'s read of the live session (a decrypt).
        for (pending in sessions.pendingRevokes()) {
            if (pending.token in revokingNow) {
                retryWanted.add(pending.token)
                continue
            }
            sendRevoke(pending, now)
        }
    }

    /**
     * One owed revoke: dropped if it has outlived [revokeRetryWindow], otherwise sent. The one door
     * every revoke request goes through, so the live-session check covers the first try and every
     * replay alike.
     */
    private fun sendRevoke(pending: PendingRevoke, now: Instant = Instant.now()) {
        if (pending.token == (client.currentSession?.token ?: sessions.load()?.token)) return
        if (Duration.between(Instant.ofEpochMilli(pending.since), now) >= revokeRetryWindow) {
            sessions.removePendingRevoke(pending.token)
            return
        }
        revokingNow.add(pending.token)
        scope.task { revokeFinished(pending, client.revoke(server = pending.server, token = pending.token)) }
    }

    /**
     * ⚠⚠ A wanted replay sends THIS token again, never every owed one. Re-running
     * [retryPendingRevokes] here would mark each other token still out as wanted — a trigger it
     * never got — and two failing tokens would then re-mark each other forever: a request loop for
     * as long as the app runs, tight when offline makes each fail at once.
     */
    private fun revokeFinished(pending: PendingRevoke, outcome: LurkerClient.RevokeOutcome) {
        revokingNow.remove(pending.token)
        val wanted = retryWanted.remove(pending.token)
        if (outcome == LurkerClient.RevokeOutcome.Done) {
            sessions.removePendingRevoke(pending.token)
        } else if (wanted) {
            sendRevoke(pending)
        }
    }

    // MARK: - Session restore

    /**
     * On launch, re-arm from a persisted session if one exists. Optimistic: go `loggedIn` before
     * connecting, so a stale token's 401 (fired during `start()`) lands afterward and
     * deterministically wins the bounce back to sign-in rather than racing a `loggedIn` that
     * arrives later.
     */
    private fun restoreSession() {
        retryPendingRevokes()
        // A session from the password sign-in this app had before OAuth isn't restored:
        // everyone signs in again once, through the approval page.
        sessions.takeLegacySession()?.let { legacy ->
            LurkerClient.endPasswordSession(server = legacy.server, token = legacy.token, scope = scope, session = httpClient)
        }
        val saved = sessions.load()
        if (saved == null) {
            sessionSubject.value = SessionState.LoggedOut
            // A process that died right after a sign-out never finished its purge (`purgeMedia`).
            purgeMedia()
            return
        }
        // A session persisted before the transport policy (lurker-ios#29) can name a server the
        // policy now rejects — a non-local http:// address the old blanket ATS exemption
        // allowed on iOS. Restoring it would go loggedIn and then spin blocked reconnects
        // forever. Bounce to sign-in with the same copy login would give, and drop the
        // session: its token belongs to a server we can no longer talk to. The server
        // field still prefills from preferences, so the address stays visible to fix.
        val reason = ServerAddress.rejection(ServerAddress.normalize(saved.server))
        if (reason != null) {
            sessions.clear()
            statusSubject.value = reason
            sessionSubject.value = SessionState.LoggedOut
            return
        }
        sessionSubject.value = SessionState.LoggedIn
        client.restore(server = saved.server, token = saved.token)
        if (isForeground) startRestored() else startDeferred = true
    }

    /** A restored session's first connect: a fresh start (a snapshot), not a resume. */
    private fun startRestored() {
        scope.task { loadConfig() }
        store.setSocketOpening()
        scope.task { client.start() }
    }

    // MARK: - Frame routing

    /**
     * Kick off preview resolution for the messages a frame carries.
     *
     * ⚠⚠ The ONLY place previews are requested. Rendering a row must never trigger a fetch.
     *
     * The first version on iOS asked from `cellForRowAt`, which meant every scroll into history
     * started fetches that grew rows under the reader and forced the table to reload to
     * remeasure. QA felt it as "scrolling up, image links snap into existence later and mess
     * up the scroll position". Slack and Discord don't have this problem because an unfurl is
     * part of the message record — it arrives WITH the message, so scrollback is laid out
     * correctly on first paint. Priming at ingest buys the same property.
     *
     * Fire-and-forget: a history page must not wait on the internet before it can be read.
     */
    private fun primePreviews(frame: ServerFrame) {
        // The instance flag gates everything: a stored `true` from another instance must not
        // start priming against one that has the feature off.
        if (!features.linkPreviews) return
        val wantMedia = store.state.settings.bool("chat.inline_media.enabled", default = false)
        val wantPages = store.state.settings.bool("chat.link_previews.enabled", default = false)

        // ⚠⚠ Recorded BEFORE the "both off" return, which is where it used to sit — and the
        // ordering is a bug rather than a detail. Turning previews off never updated the record,
        // so the stored value stayed at the last ON state; turning them back on then compared
        // equal and skipped the rescan entirely, and every message already loaded stayed bare
        // until something else re-ingested it. Off-then-on is exactly how somebody tries a
        // setting out.
        if (frame is ServerFrame.SettingsBootstrap) recordToggles(wantMedia, wantPages)
        if (frame is ServerFrame.SettingsChanged) recordToggles(wantMedia, wantPages)
        if (frame is ServerFrame.SettingsValues) recordToggles(wantMedia, wantPages)

        if (!(wantMedia || wantPages)) return

        // Carried as (networkId, target, messages) rather than as bare text, because both
        // filters below need to know which buffer a line came from — an ignore rule can be
        // scoped to a network, and whether a target is a DM is part of what a rule matches.
        val groups: List<PrimeGroup> = when (frame) {
            is ServerFrame.Backlog ->
                listOf(PrimeGroup(frame.buffer.networkId, frame.buffer.target, frame.messages))
            is ServerFrame.History ->
                listOf(PrimeGroup(frame.networkId, frame.target, frame.events))
            is ServerFrame.Live ->
                listOf(PrimeGroup(frame.networkId, frame.target, listOf(frame.message)))
            // ⚠ A settings change has to re-prime what's ALREADY loaded. Priming is ingest-driven,
            // and turning a toggle on doesn't re-ingest anything — so without this the fix shows
            // previews only for messages that arrive afterwards, which reads as the setting being
            // broken. Exactly the "I turned it on and nothing happened" report.
            //
            // ⚠⚠ Gated on the two preview toggles actually MOVING, because this branch re-scans
            // every message in every buffer synchronously on the main thread and `state.messages`
            // has no cap — it grows with every scroll-up page. Measured on an iOS device: 30
            // buffers x 2,000 messages is ~330ms, x10,000 is ~1.5 SECONDS of parse + regex +
            // ignore evaluation.
            //
            // And it fired far more often than "a preview toggle changed": the switch keys on the
            // frame CASE and never looked at which setting moved, `updateSettings` applies its own
            // HTTP reply as `.settingsValues` while the server also echoes `.settingsChanged` (so
            // one toggle is two full walks), and a reconnect re-fetches settings — which
            // `enterForeground` triggers after any background longer than 30 seconds. Pocketing the
            // phone for half a minute cost a full rescan of every message ever loaded.
            is ServerFrame.SettingsBootstrap, is ServerFrame.SettingsChanged, is ServerFrame.SettingsValues -> {
                // `recordToggles` above already compared and stored; it answers whether this frame
                // actually moved a preview setting.
                if (!togglesJustChanged) return
                primeLoadedBuffers()
                return
            }
            else -> return
        }

        // The type filter and the ignore veto both live in `PreviewSelection`, not here — see
        // its `urls(in:networkId:target:ignores:…)`. What is left in this function is the list
        // of frames priming listens to, which is the part that genuinely belongs to routing.
        val ignores = store.state.ignores
        val urls = groups.flatMap {
            PreviewSelection.urls(
                it.messages, networkId = it.networkId, target = it.target, ignores = ignores,
                inlineMedia = wantMedia, linkPreviews = wantPages,
            )
        }
        linkPreviews.request(urls)
    }

    /** LurkerKit's `(networkId: Int?, target: String, messages: [Message])` tuple. */
    private data class PrimeGroup(val networkId: Int?, val target: String, val messages: List<Message>)

    /** The two preview settings, as one comparable value. See `lastPreviewToggles`. */
    private data class PreviewToggles(val media: Boolean, val pages: Boolean)

    /**
     * What the preview toggles were when a settings frame last carried them, so an unrelated
     * setting — or a reconnect's re-fetch — doesn't buy a full rescan.
     */
    private var lastPreviewToggles: PreviewToggles? = null

    /** Whether the frame being handled actually moved one of them. */
    private var togglesJustChanged = false

    private fun recordToggles(media: Boolean, pages: Boolean) {
        val toggles = PreviewToggles(media = media, pages = pages)
        togglesJustChanged = lastPreviewToggles != toggles
        lastPreviewToggles = toggles
    }

    /**
     * Re-prime every message already in the store.
     *
     * Two callers, and they are the two ways the answer can change after the messages arrived:
     * a preview toggle moving, and the instance feature flag turning out to be on. The second
     * is what makes `loadConfig` safe to detach — arriving late is handled rather than raced.
     *
     * ⚠ Walked by BUFFER rather than over `state.messages.values`, because that dictionary is
     * keyed by an id string with the network folded out of it, and the ignore check needs it.
     */
    private fun primeLoadedBuffers() {
        if (!features.linkPreviews) return
        val wantMedia = store.state.settings.bool("chat.inline_media.enabled", default = false)
        val wantPages = store.state.settings.bool("chat.link_previews.enabled", default = false)
        if (!(wantMedia || wantPages)) return

        val ignores = store.state.ignores
        val urls = store.state.buffers.values.flatMap { buffer ->
            PreviewSelection.urls(
                store.state.messages[buffer.key.id].orEmpty(), networkId = buffer.networkId,
                target = buffer.target, ignores = ignores,
                inlineMedia = wantMedia, linkPreviews = wantPages,
            )
        }
        linkPreviews.request(urls)
    }

    /**
     * Internal rather than private so a test can feed a frame through the same path the socket
     * does.
     */
    internal fun handle(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.SettingsBootstrap, is ServerFrame.SettingsChanged, is ServerFrame.SettingsValues -> {
                store.apply(frame)
                // Persist after folding, not from the frame: a `settingsChanged` patch carries only
                // what moved, so the cache has to mirror the merged result rather than the delta.
                settingsCache.save(store.state.settings.values)
                if (frame is ServerFrame.SettingsBootstrap) syncTimeZone()
            }
            ServerFrame.Unauthorized ->
                onAuthLost()
            is ServerFrame.Incompatible -> {
                // A 426 is newer than any `/api/config` read already out, so none of those may clear it.
                configReads.supersedeInFlight()
                onIncompatible(frame.incompatibility)
            }
            ServerFrame.SocketOpen -> {
                // A socket that opens after the server was found not to take this build (its config
                // answered first) is closed rather than used.
                if (store.state.connection.incompatibility != null) {
                    client.dropSocket()
                    return
                }
                // A new socket asks every read mark again. The drop clears them too, but a foreground
                // reconnect can replace a socket that died without saying so, and its close is never
                // heard — a mark lost on it would be deduped for good (sweep L23).
                lastMarked.clear()
                store.apply(frame)
                reconnectAttempt = 0 // a clean connection resets the backoff
            }
            is ServerFrame.SocketClosed -> {
                loadingOlder.clear() // in-flight history pages won't get a reply now
                loadingNewer.clear()
                // ⚠⚠ Dropped WITHOUT restoring, and that is a choice between two bad outcomes. A send
                // the socket died under may or may not have reached IRC — the ACK is what would have
                // told us, and it is exactly what is not coming. Restoring risks the user sending the
                // same line twice, to a channel, with no way to take it back; not restoring risks
                // losing a line they can see was never delivered. Duplicate-in-public is the worse
                // one, and it is the call the web makes too.
                unsent.abandonAll()
                // …and no join sent down that socket will be answered either. Dropped quietly: the
                // banner already names the outage, and a "No response" for each would only pile up
                // behind it (lurker-ios#57).
                pendingJoins.removeAll()
                // And the read marks are asked again. One written into a socket that had died
                // without saying so was recorded as sent, and the dedupe would skip it for good
                // (sweep L23). The server MAX-clamps, so a mark that did land costs one redundant write.
                lastMarked.clear()
                store.apply(frame)
                onSocketDropped()
            }
            is ServerFrame.ChannelJoined -> {
                // Applied first, so the row the user is taken to is the one this frame made.
                store.apply(frame)
                pendingJoins.joined(BufferKey(networkId = frame.networkId, target = frame.target))?.let { settle(it) }
            }
            is ServerFrame.JoinError -> {
                store.apply(frame)
                val key = BufferKey(networkId = frame.networkId, target = frame.target)
                pendingJoins.refused(key, reason = frame.reason)?.let { settle(it) }
            }
            is ServerFrame.ChannelParted -> {
                // A 470 forward parts the name we asked for: the join was answered, under another name.
                store.apply(frame)
                pendingJoins.parted(BufferKey(networkId = frame.networkId, target = frame.target))
            }
            is ServerFrame.History -> {
                // The page in flight is done — clear the set that requested this mode so the next
                // scroll can page again. Only `before` arms `loadingOlder` and only `after` arms
                // `loadingNewer`; `around`/`latest` are one-shots tracked by the screen, not here.
                val id = BufferKey(networkId = frame.networkId, target = frame.target).id
                when (frame.mode) {
                    HistoryMode.Before -> loadingOlder.remove(id)
                    HistoryMode.After -> loadingNewer.remove(id)
                    HistoryMode.Around, HistoryMode.Latest -> Unit
                }
                store.apply(frame)
            }
            is ServerFrame.BufferRenamed -> {
                // Rekey this model's own per-buffer bookkeeping alongside the
                // store's. An in-flight page flag left under the dead key would
                // never clear (nothing but a socket drop resets it) and would
                // block scroll-paging in the renamed buffer forever. On a merge
                // the mark dedupe takes the max of both sides so a stale absorbed
                // mark can't suppress a legitimate markRead.
                val fromKey = BufferKey(networkId = frame.networkId, target = frame.from)
                val toKey = BufferKey(networkId = frame.networkId, target = frame.to)
                if (loadingOlder.remove(fromKey.id)) loadingOlder.add(toKey.id)
                if (loadingNewer.remove(fromKey.id)) loadingNewer.add(toKey.id)
                // A line still awaiting its ack was addressed to the old key — see `rekey`.
                unsent.rekey(from = fromKey, to = toKey)
                // …and so was a draft edit still waiting to go out. Its flush has to name the buffer
                // as it's called now.
                // Read before the store applies the frame: whether the survivor already has a draft
                // the server holds, which the merge keeps over anything the absorbed buffer had.
                val dueFlush = draftFlushes.remove(fromKey.id)
                dueFlush?.cancel()
                val draftWasDue = dueFlush != null
                val survivorHasDraft = store.state.drafts[fromKey.id] != null
                if (draftSync.rekey(from = fromKey, to = toKey, survivorHasDraft = survivorHasDraft)) {
                    draftFlushes.remove(toKey.id)?.cancel()
                }
                if (draftWasDue) scheduleDraftFlush(toKey)
                val fromMark = lastMarked.remove(fromKey.id)
                if (fromMark != null) {
                    lastMarked[toKey.id] = if (frame.merged) max(fromMark, lastMarked[toKey.id] ?: 0L) else fromMark
                }
                onBufferRenamed?.invoke(fromKey, toKey)
                store.apply(frame)
            }
            is ServerFrame.SendResult -> {
                // ⚠⚠ The whole point of `clientId`. Give the line back to the composer it was typed
                // in, and say nothing: the `ConnectionBanner` is already naming the outage, and the
                // failures that need words of their own arrive as bare `error` frames alongside this
                // (see the reducer's note). The composer refilling IS the signal.
                //
                // ⚠ `resolve` forgets the entry on either verdict — see its note.
                // ⚠ Through `refuse`, not a second copy of it. That function calls itself the one
                // place a refusal becomes a restore, and this handler having its own hold-and-nudge
                // made that untrue the moment it was written — exactly the drift between the ack path
                // and the no-socket path the comment there warns about.
                val clientId = frame.clientId
                if (frame.ok) {
                    unsent.resolve(clientId = clientId, ok = true) // nothing to give back; forget it
                } else if (clientId != null) {
                    refuse(clientId)
                }
                store.apply(frame)
            }
            is ServerFrame.DraftSnapshot -> {
                // The server's drafts, except where this device has written something it hasn't
                // heard yet — newer by definition. That includes what went to this socket before the
                // snapshot was built. Then all of it goes out on this one.
                draftSnapshotSocket = client.socketGeneration
                draftSync.requeueAwaitingSnapshot()
                store.seedDrafts(frame.entries, keeping = draftSync.protectedIds)
                flushAllDrafts()
            }
            is ServerFrame.DraftUpdated -> {
                // Another device's write. Dropped while this one has an edit on the way (it'll land
                // after, and last write wins) or an IME is composing in that buffer.
                if (!draftSync.isProtected(frame.entry.key.id)) {
                    draftSync.superseded(frame.entry.key.id)
                    store.apply(frame)
                }
            }
            is ServerFrame.BufferClosed -> {
                dropDraft(BufferKey(networkId = frame.networkId, target = frame.target))
                store.apply(frame)
            }
            is ServerFrame.Networks -> {
                // The roster is authoritative for which networks exist, and the fold drops the rest —
                // their drafts included. An edit waiting for one would otherwise put it back.
                store.apply(frame)
                dropDraftsForMissingNetworks()
            }
            is ServerFrame.FavoritesChanged -> {
                // Apply FIRST, announce after — a hook reading `favorites` must see
                // the state this frame proved, not the one before it.
                store.apply(frame)
                onFavoritesSynced?.invoke()
            }
            is ServerFrame.Snapshot -> {
                // Lines held from before the socket opened are a resume: a reaction made or taken
                // back on one of them while we were away reached no socket of ours, and the resume
                // ships only new rows. Ask what stands now. A fresh connect holds nothing, and every
                // backlog that follows carries its own. Asked before applying, so the ids are the
                // ones that were on screen.
                client.syncReactions(messageIds = store.state.reactionSyncIds())
                store.apply(frame)
                refreshRosterIfAnyNetworkIsNameless()
            }
            is ServerFrame.NetworkState -> {
                store.apply(frame)
                refreshRosterIfAnyNetworkIsNameless()
            }
            else ->
                store.apply(frame)
        }

        settlePendingOpen()

        // ⚠⚠ AFTER `store.apply`, never before. `primePreviews` reads the settings out of the
        // store to decide whether either feature is on — so running it first meant a
        // `settingsChanged` frame was evaluated against the OLD values, the guard returned
        // early, and turning a toggle on primed nothing at all. The fix for "I enabled it and
        // nothing happened" was itself inert until this moved.
        //
        // It also means the message frames prime against a store that already holds them, which
        // is the more obviously correct order even though those read their texts from the frame.
        primePreviews(frame)

        when (frame) {
            is ServerFrame.Live -> {
                channelEventsSubject.tryEmit(
                    ChannelEvent.Line(BufferKey(networkId = frame.networkId, target = frame.target), frame.message),
                )
                noteUnknownCommand(frame.networkId, frame.message)
            }
            is ServerFrame.Snapshot ->
                channelEventsSubject.tryEmit(ChannelEvent.Resynced)
            is ServerFrame.Invited -> {
                val joined = store.state.buffers[BufferKey(networkId = frame.networkId, target = frame.channel).id]?.joined == true
                // Someone ignored outright doesn't get to put a prompt in front of us; their
                // invitation is still in the system buffer. There's no INVITES level, so only a
                // whole-identity rule counts — scoped to the channel they invited us to.
                val ignored = store.state.ignores.isIgnored(
                    networkId = frame.networkId, nick = frame.from, userhost = frame.userhost, channel = frame.channel,
                )
                if (!joined && !ignored) onInvited?.invoke(frame.networkId, frame.channel, frame.from)
            }
            else -> Unit
        }
    }

    /**
     * A 421 for a command this device sent raw: say so where it was typed. The server's own
     * line goes to the network's server log, which isn't where anyone is looking when
     * `/frobnicate` in a channel seems to do nothing. A 421 for a line sent from another device
     * matches nothing here (past `unknownCommandWindow`), and that device says so itself.
     */
    internal fun noteUnknownCommand(networkId: Int?, message: Message, now: Instant = Instant.now()) {
        if (networkId == null) return
        val verb = message.unknownCommand ?: return
        val rawKey = rawCommandKey(networkId, verb)
        val pending = live(rawCommandOrigins[rawKey].orEmpty(), now)
        val origin = pending.firstOrNull()
        if (pending.size > 1) rawCommandOrigins[rawKey] = pending.drop(1) else rawCommandOrigins.remove(rawKey)
        if (origin == null) return
        // Typed in the server log: the server's own line is already right there.
        if (origin.key.target == Buffer.serverTarget(networkId)) return
        // Closed since: a line for a buffer that's gone would sit in the side table unseen.
        if (store.state.buffers[origin.key.id] == null) return
        store.appendLocal(origin.key, text = "Unknown command: /${verb.lowercase()}")
    }

    /** `origins` without the ones too old to be answered now. */
    private fun live(origins: List<RawCommandOrigin>, now: Instant): List<RawCommandOrigin> =
        origins.filter { Duration.between(it.sentAt, now) <= unknownCommandWindow }

    /** IRC verbs are case-insensitive, and the ircd echoes one in whatever case it likes. */
    private fun rawCommandKey(networkId: Int, verb: String): String = "$networkId ${verb.uppercase()}"

    /**
     * Whether a roster re-read is already in flight. Without it a burst of `state` events
     * for one unnamed network would fire a GET each.
     */
    private var rosterRefreshInFlight = false

    /**
     * Re-read `GET /api/networks` when the store holds a network we have no name for
     * (lurker-ios#136).
     *
     * ⚠⚠ The snapshot names every network the account has and names none of them: network
     * names live only in the REST roster, which runs once on connect and is deliberately
     * skipped on reconnect. So a network the roster doesn't hold — one added from another
     * client since we connected, one created here whose `state` event beat the create's own
     * re-read, or every one of them after a roster fetch that failed — lands nameless and,
     * before this, stayed that way for the life of the app.
     *
     * Keyed on a null name rather than on "did the fetch fail", because the second question
     * misses every case but the last. Costs nothing in the ordinary case: the roster already
     * holds each id, so this asks nothing.
     *
     * Terminating without a retry bound, because the re-read is authoritative over
     * membership: it either names the network or removes it. Either way there is no nameless
     * network left to trigger another.
     */
    private fun refreshRosterIfAnyNetworkIsNameless() {
        if (rosterRefreshInFlight || store.state.networks.values.none { it.name == null }) return
        rosterRefreshInFlight = true
        scope.task {
            client.refreshNetworks()
            rosterRefreshInFlight = false
        }
    }

    // MARK: - Reconnect

    /**
     * A drop while signed-in + foregrounded schedules a backed-off reconnect — unless the server
     * can't take this build (lurker-ios#17), when every attempt would be refused.
     */
    private fun onSocketDropped() {
        if (!(session == SessionState.LoggedIn && isForeground && store.state.connection.incompatibility == null)) {
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectTask != null) return // one attempt already in flight
        val wait = backoff(reconnectAttempt)
        reconnectAttempt += 1
        reconnectTask = scope.task {
            // `delay` ends the body on cancellation; see the draft flush's note.
            delay(wait.seconds)
            reconnectTask = null
            doReconnect(force = false)
        }
    }

    /**
     * The single reconnect path. `force` reconnects even when the status reads Connected
     * (the stale-socket case); otherwise a scheduled attempt bails if the connection came
     * back meanwhile, so a pending timer can't tear down a good socket.
     */
    private fun doReconnect(force: Boolean) {
        if (!(session == SessionState.LoggedIn && isForeground)) return
        // A server that can't take this build would refuse the socket, so ask it again instead;
        // `loadConfig` reconnects if the answer has changed (lurker-ios#17).
        if (store.state.connection.incompatibility != null) {
            scope.task { loadConfig() }
            // ⚠ And check the token, which nothing else does while there's no socket. Without it, an
            // app revoked in Settings would sit on "Update the app to connect" instead of signing out.
            scope.task { client.checkToken() }
            return
        }
        if (!force && store.state.connection == SocketStatus.Connected) return
        // Every attempt re-reads the config, not only while it's unanswered: it carries the
        // server's version, which moves exactly when a deploy drops the socket. See `loadConfig`.
        scope.task { loadConfig() }
        reconnectSocket()
    }

    /**
     * Open a new socket in place of the old one, resuming from the last event. The one door every
     * reconnect goes through, so user writes wait for the new socket (`ChatState.socketOpening`) —
     * a forced one included, which replaces a stale socket while the state still reads Connected.
     */
    internal fun reconnectSocket() {
        store.setSocketOpening()
        client.reconnect(since = store.state.maxEventId)
    }

    private fun cancelReconnect() {
        reconnectTask?.cancel()
        reconnectTask = null
        reconnectAttempt = 0
    }

    /**
     * The token expired or was revoked elsewhere. Drop the (already-dead) session without
     * a revoke round-trip and bounce to sign-in with an explanation.
     */
    private fun onAuthLost() {
        if (session == SessionState.LoggedOut) return // handle the first 401, ignore the rest
        cancelReconnect()
        client.close()
        sessions.clear()
        // The next account's preferences are not this one's — and a privacy switch in
        // particular must not carry across users. Both this and the deliberate sign-out clear
        // it, because either can be followed by someone else signing in on this phone.
        settingsCache.clear()
        resetTimeZoneSync()
        // Previews carry the same hazard the settings cache does, plus two more: the metadata
        // is the previous account's reading history, and the `asked` set would suppress
        // re-resolution against a DIFFERENT instance — whose signed proxy tokens wouldn't verify
        // anyway, so every image would 403. Both of these were dead code until now.
        linkPreviews.reset()
        onPreviewCachesCleared?.invoke()
        // ⚠ Kept in step with `logout()` deliberately. This path had drifted — it dropped the
        // preview caches but left `features` asserting the departing instance's answer, so a
        // 401-bounce followed by signing in elsewhere primed against a flag nobody had checked.
        // The two teardowns lead to the same screen and must leave the same state behind.
        //
        // Port note: as in `logout()` — LurkerKit purges on the main actor; Android moves the disk
        // work off main (`purgeMedia`).
        purgeMedia()
        features = InstanceFeatures()
        lastPreviewToggles = null
        store.reset()
        // ⚠ The correlator holds the TEXT of every unanswered send, so it is account data and
        // goes with the rest of it. Neither teardown path emits `.socketClosed` — `close()`
        // cancels the socket without firing its handler, and a 401 arrives as `.unauthorized` —
        // so the abandon that path does would never run here.
        unsent.abandonAll()
        // Drafts are account data too, and a timer left armed would flush into the next session.
        resetDrafts()
        // A raw line's origin names a buffer of this account; the next one's ids may reuse it.
        rawCommandOrigins.clear()
        // Joins too: a pending one names a channel the next account never asked for, and its timer
        // would otherwise toast "No response" over the sign-in screen (lurker-ios#57).
        pendingJoins.removeAll()
        pendingOpens.cancel()
        loadingOlder.clear()
        loadingNewer.clear()
        lastMarked.clear()
        // As `logout()` does: the next sign-in may be another account, or another server.
        resetPush()
        statusSubject.value = "Your session ended — please sign in again."
        sessionSubject.value = SessionState.LoggedOut
    }

    /** 1s, 2s, 4s … capped at 30s. */
    private fun backoff(attempt: Int): Double =
        min(baseBackoff * 2.0.pow(min(attempt, maxShift)), maxBackoff)

    /**
     * Port note: at the bottom of the class rather than where LurkerKit's `init` sits, so that
     * every property initializer above has run before it — which Swift's two-phase
     * initialization guarantees and Kotlin's textual order does not.
     */
    init {
        // Seed before anything can read a setting. Patched in as *values* only — no registry —
        // so `settings.loaded` stays honestly false until a real bootstrap arrives, while every
        // behavior gate already reads the user's actual choice.
        val cached = settingsCache.load()
        // No limits: the cache holds setting VALUES, and the advertised limits are not among
        // them — each is the server's resolution of an operator policy and a user setting, and
        // only one of those is the user's. null here is the honest "nobody has said yet", and
        // the snapshot lands on connect, well before there is anything to upload.
        if (cached.isNotEmpty()) store.apply(ServerFrame.SettingsChanged(cached, uploadLimits = UploadLimits.unstated))
        restoreSession()
    }

    internal companion object {
        /**
         * How long an owed revoke is asked for (lurker-ios#218). A server unreachable for a month is
         * taken to be gone; without a bound, every launch would ask every dead address forever.
         */
        val revokeRetryWindow: Duration = Duration.ofDays(30)

        private const val baseBackoff: Double = 1.0
        private const val maxBackoff: Double = 30.0
        private const val maxShift = 5 // 1s << 5 = 32s, clamped to 30s
        private val staleAfter: Duration = Duration.ofSeconds(30)

        /**
         * How long a raw line waits for its 421. The ircd answers within a round trip; past this
         * the line was accepted, or its answer was lost with the socket, and a 421 that matches
         * now is someone else's — another device typing the same verb.
         */
        val unknownCommandWindow: Duration = Duration.ofSeconds(30)

        /**
         * The command of a raw line: its first word, past any IRCv3 tag block (`@label=x`) or
         * source prefix (`:me`) that `/raw` let the user type in front of it.
         */
        fun rawVerb(line: String): String? =
            line.split(' ').firstOrNull { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith(":") }

        fun saveFailure(reply: VerbReply): ChannelSaveFailure? {
            val message = saveError(reply) ?: return null
            val unknown = reply.error == "no-answer" || reply.error == "connection-lost"
            return ChannelSaveFailure(message = message, certainlyUnsent = !unknown)
        }

        fun saveError(reply: VerbReply): String? {
            if (reply.ok) return null
            return when (val error = reply.error) {
                "not-connected" -> "Not connected."
                "no-answer" -> "The server didn't answer."
                "connection-lost" -> "The connection dropped before the server answered."
                null -> "Couldn't save."
                else -> "Couldn't save ($error)."
            }
        }

        fun listError(reply: VerbReply): String =
            when (val error = reply.error) {
                "refused" -> {
                    if (reply.numeric == "482") {
                        "Only channel operators can see this list."
                    } else {
                        "The server refused: ${reply.text ?: reply.numeric ?: "no reason given"}"
                    }
                }
                "not-connected" -> "Not connected."
                // The server's own wait ran out, or ours did: nobody said anything.
                "no-reply", "no-answer", null -> "The server didn't answer."
                "account-paused" -> "This account is paused, so nothing can be fetched."
                else -> "Couldn't load the list ($error)."
            }
    }
}
