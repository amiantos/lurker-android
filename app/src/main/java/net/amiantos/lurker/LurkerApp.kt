// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.amiantos.lurker.auth.BrowserSignIn
import net.amiantos.lurker.auth.KeystoreSecureStorage
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.AppEvents
import net.amiantos.lurker.platform.ExpiryText
import net.amiantos.lurker.platform.ReachabilityMonitor
import net.amiantos.lurker.prefs.PrefsDefaultsStorage
import net.amiantos.lurker.prefs.SharedStringPrefs
import net.amiantos.lurker.prefs.UiPreferences
import net.amiantos.lurker.ui.dcc.DccOffers
import net.amiantos.lurker.ui.media.PreviewImageLoader
import net.amiantos.lurker.ui.media.PreviewUpdates
import net.amiantos.lurker.ui.uploads.AndroidUploadPlatform
import net.amiantos.lurker.ui.uploads.ComposerInserts
import net.amiantos.lurker.ui.uploads.ShareInbox
import net.amiantos.lurker.ui.uploads.UploadRunner
import net.amiantos.lurker.ui.uploads.UploadServices
import net.amiantos.lurkerkit.session.AppBadge
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.SettingsCache
import java.io.File

/**
 * The process-long pieces, built once: the kit's view model and everything that feeds it facts
 * about the device. lurker-ios's `SceneDelegate` + `AppDelegate`, moved to where Android keeps
 * what must outlive an activity — the activity is recreated on every configuration change, and
 * the session, the socket and an OAuth attempt in flight must not be.
 */
class LurkerApp : Application() {

    /**
     * The scope every kit coroutine runs in, for the life of the process.
     *
     * ⚠ `Dispatchers.Main.immediate`, and never an activity's or a view model's scope. The kit's
     * `task` (launch-then-yield) is written for `immediate`: it keeps a Swift `Task`'s promise that
     * none of its body runs before the statement that started it returns. And it must outlive any
     * one screen — a sign-in or a background flush cancelled by a rotation would strand the session.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One view model for the app's lifetime. It owns the client (session token + socket) and the store. */
    lateinit var model: ChatViewModel
        private set

    lateinit var uiPreferences: UiPreferences
        private set

    /** App-scoped so an attempt survives the activity under the tab being recreated. */
    val browserSignIn = BrowserSignIn()

    /** The kit's asks of the screen, queued for `MainScaffold` — see [AppEvents]. */
    val events = AppEvents()

    /**
     * The DCC chat offer standing for an answer (lurker-android#38) — a StateFlow here rather than an
     * [AppEvent], since an offer waits for its answer through the app's absence. See [DccOffers].
     */
    lateinit var dccOffers: DccOffers
        private set

    /**
     * The upload run, the composers' registry and the waiting share (lurker-android#15) — app-long, so
     * an upload survives a rotation and a buffer switch, and a share received while signed out waits
     * for the sign-in. See [UploadServices].
     */
    lateinit var uploads: UploadServices
        private set

    /**
     * Same shape as reachability and push: the kit decides the number, the app makes the platform
     * call. Android has no first-party launcher badge outside notifications — a launcher draws a
     * dot or a count from the app's *notifications* — so until push there is nothing to write to,
     * and this only logs.
     *
     * U9: the count becomes the badge number on the notification channel's notifications
     * (`NotificationCompat.Builder.setNumber`), which is where a launcher that shows counts reads
     * it. No third-party badge library: they poke private launcher APIs.
     */
    private val badge = AppBadge { count -> Log.d(TAG, "badge: $count") }

    override fun onCreate() {
        super.onCreate()
        val defaults = PrefsDefaultsStorage(SharedStringPrefs(prefs(PrefsDefaultsStorage.FILE)))
        uiPreferences = UiPreferences(SharedStringPrefs(prefs(UiPreferences.FILE)))

        // Restore runs in the view model's init, so the session is known as soon as this returns —
        // the activity builds the right screen first time, with no sign-in flash on a restored session.
        model = ChatViewModel(
            scope = scope,
            sessions = SessionStore(KeystoreSecureStorage(this)),
            settingsCache = SettingsCache(defaults),
            oauthClients = OAuthClients(defaults),
            formatExpiry = ExpiryText(this),
            mediaCacheDirectory = File(cacheDir, "preview-media-cache"),
        )

        // Keep the badge honest (lurker#490, lurker-ios#134), driven off state so it follows
        // read-state from other devices. ⚠ Before anything applies a frame: the restore's connect
        // is a `task`, so it starts only after this returns, and on `Main.immediate` this collector
        // subscribes now, not later — so no state published by the restore can slip past it.
        badge.follow(model.statePublisher, scope)

        dccOffers = DccOffers(model, scope) { refusal -> events.send(AppEvent.Notice(refusal)) }

        val uploadPlatform = AndroidUploadPlatform(this, model, scope)
        // A process killed mid-upload leaves its staged copy behind; nothing else will delete it.
        uploadPlatform.clearLeftovers()
        val inserts = ComposerInserts()
        uploads = UploadServices(
            runner = UploadRunner(scope, uploadPlatform, inserts, clipboard = uploadPlatform::copyToClipboard),
            inserts = inserts,
            shares = ShareInbox(),
        )

        wireCallbacks()
        observeSession()
        observeLifecycle()

        // Drives the "No internet connection" status: the socket only ever reports
        // connecting/connected/reconnecting, so without the OS's view of the path there'd be no
        // way to tell "no internet" from "still trying".
        ReachabilityMonitor(this).start { reachable -> model.setReachable(reachable) }
    }

    private fun prefs(name: String) = getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * Sign in to [server] through its approval page. On success the shell swaps the sign-in
     * screen out (it observes the session); on failure the status line carries the reason.
     *
     * Launched in [scope], not the screen's: the attempt spans a trip out to the browser, during
     * which the activity can be recreated, and a cancelled `signIn` would leave the session stuck
     * at `LoggingIn`. One at a time — `signIn` sets `LoggingIn` before its first suspension, so a
     * second tap lands here after it and is dropped.
     */
    fun signIn(server: String) {
        if (model.session == ChatViewModel.SessionState.LoggingIn) return
        scope.launch {
            val signedIn = model.signIn(server = server, appName = APP_NAME) { page -> browserSignIn.authorize(page) }
            // Remembered for the next sign-in (the prefill after sign-out) — once it has worked,
            // so a typo or a refused address never replaces the last good one. (iOS writes it on
            // the tap.) As typed, not normalised: the prefill should read as the user wrote it.
            if (signedIn) uiPreferences.lastServerURL = server
        }
    }

    /**
     * Every callback lurker-ios's `SceneDelegate` sets, wired here so none is forgotten when its
     * slice lands. The navigating ones will need the shell's navigator, which lives in
     * composition: the slice that fills one hands it in from there.
     */
    private fun wireCallbacks() {
        // A rename has to chase the buffer's key through the preferences that store it. Owned here,
        // not in the kit: the preferences are the app's, and the view model just announces the move.
        // The navigator holds keys too, and is the screen's: it hears through the queue.
        model.onBufferRenamed = { from, to ->
            uiPreferences.rewriteBuffer(from, to)
            events.send(AppEvent.BufferRenamed(from, to))
        }

        // Drop the decoded-image cache (link previews). Sign-out must: the images are the previous
        // account's reading history, and against a different instance the signed proxy tokens
        // wouldn't verify anyway. In memory only, so cheap on the main thread where the kit calls it.
        // (⚠ The kit's own teardown beside it — `clearMediaCache`'s `Cache.evictAll` and
        // `clearStagedMedia` — deletes files on the calling thread, which is the main thread: a kit
        // debt this can't reach from here.)
        model.onPreviewCachesCleared = {
            PreviewImageLoader.reset()
            PreviewUpdates.reset()
        }

        // Preview metadata landing — which URLs moved — as a version per URL that each row reads, so
        // exactly the rows mentioning them recompose, whichever screen they're on (iOS's chat screen
        // takes the store's one callback per screen). Touches the store, which the kit builds lazily;
        // it's a few empty maps.
        PreviewUpdates.install(model.linkPreviews)

        // A join this device asked for landed — navigate to it (lurker-ios#57).
        model.onJoinOpened = { key -> events.send(AppEvent.OpenBuffer(key)) }

        // A join that didn't happen says why, as a snackbar over whatever is on screen.
        model.onJoinNotice = { notice -> events.send(AppEvent.Notice(notice.message)) }

        // A DCC chat this device opened or accepted has a buffer — navigate, as for a join
        // (lurker#270). Only once its `=nick` row exists: the kit holds the open until then
        // (`PendingDccOpen`), since landing on an absent buffer in a settled roster pops straight back.
        model.onDccChatOpened = { key -> events.send(AppEvent.OpenBuffer(key)) }

        // An offer someone made us is asked about over whatever is on screen (iOS `DccOfferPrompt`):
        // `MainScaffold` draws the dialog from `dccOffers.prompt`.
        dccOffers.follow()

        // The server refused a line (lurker-ios#128): the composer showing that buffer refills from
        // `takeUnsent`. A nudge only — the line waits in the kit, and a composer that isn't on screen
        // drains it the next time it is (`ComposerState.restoreRefused`).
        model.onSendRefused = { key -> events.sendRefused(key) }

        // None here: iOS hangs its one-shot migration of a device-local favorites list off this.
        // Android never had a local favorites list, so there is nothing to migrate.
        model.onFavoritesSynced = {}
    }

    /**
     * Sign-out — deliberate, or a mid-session 401 — forgets the last open buffer: the next sign-in
     * may be somebody else, and restoring would land them in the previous account's channel.
     *
     * The publisher replays only its latest value, and the restore has already run, so a restored
     * session is seen here as `LoggedIn`, never as the `LoggedOut` it started from.
     */
    private fun observeSession() {
        scope.launch {
            var was = model.session
            model.sessionPublisher.collect { session ->
                if (session == ChatViewModel.SessionState.LoggedOut) {
                    uiPreferences.forgetLastOpenBuffer()
                    events.drain()
                }
                // Only a session ENDING: a share received while signed out waits through a sign-in
                // attempt that fails (LoggingIn → LoggedOut) for the one that works.
                if (was == ChatViewModel.SessionState.LoggedIn && session != ChatViewModel.SessionState.LoggedIn) uploads.reset()
                was = session
                // U9: signing in is the moment push becomes askable (`enablePushIfSignedIn`).
            }
        }
    }

    /**
     * Foreground/background, for the whole process rather than one activity, so an activity
     * recreated on rotation doesn't read as the app leaving. `ProcessLifecycleOwner` delays its
     * stop by a moment for exactly that reason.
     *
     * Visible, not focused: start/stop rather than resume/pause, so a dialog or a split-screen
     * neighbour taking focus doesn't tell the server nobody is looking.
     */
    private fun observeLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // A socket dies after a long background; the view model reconnects (resuming
                    // from `?since=`) when we come back, and reports presence, which is what stops
                    // the server pushing to a phone whose owner is looking at it.
                    val keptSocket = model.enterForeground()
                    // The user has just been looking at the launcher, and a push may have painted
                    // a number our count never moved off (lurker-ios#134). Re-assert ours — but only
                    // when the view model kept a live socket: after a reconnect the count is a
                    // pre-background leftover, and the burst's own write takes over once it's fresh.
                    if (keptSocket) badge.reassert(model.state)
                    // U9: re-run push enabling on every foreground (tokens rotate, permission changes).
                }

                override fun onStop(owner: LifecycleOwner) {
                    // Presence is the frame that makes push work at all: the server suppresses every
                    // notification until it hears nobody is looking. iOS holds a background-task
                    // assertion until `onFlush` says the frame (and any drafts) left. Android has no
                    // such assertion; the process usually keeps running for a while after its last
                    // activity stops, so for now nothing is held.
                    // U9/U10: decide whether an expedited WorkManager job is worth holding the
                    // process for, judged by how often presence is lost on real devices.
                    model.enterBackground(onFlush = null)
                }
            },
        )
    }

    companion object {
        private const val TAG = "Lurker"

        /**
         * How this install names itself on the server's list of authorized apps. iOS says
         * "Lurker for iPhone" / "for iPad"; `Build.MODEL` would be the analogue, but it reads as a
         * part number on most phones ("SM-S918B"), so the platform it is.
         */
        const val APP_NAME = "Lurker for Android"
    }
}
