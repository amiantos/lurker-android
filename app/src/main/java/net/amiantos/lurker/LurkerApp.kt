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
import net.amiantos.lurker.platform.NotificationSounds
import net.amiantos.lurker.platform.ToastCenter
import net.amiantos.lurker.platform.ExpiryText
import net.amiantos.lurker.platform.NoticeAction
import net.amiantos.lurker.platform.ReachabilityMonitor
import net.amiantos.lurker.platform.push.PushNotifier
import net.amiantos.lurker.platform.push.PushRegistrar
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
import net.amiantos.lurkerkit.push.RelayPushKeys
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
     * In-app notifications (lurker#1098): a highlight, DM or always-notify line while the app is open
     * — push's foreground half — offered to whichever screen is on top, with its sound. See [ToastCenter].
     */
    val toastCenter = ToastCenter(play = NotificationSounds::play)

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
     * Push (lurker-android#16). App-scoped: a token rotation arrives in the messaging service with no
     * activity up. `MainActivity` lends it the permission prompt while started.
     */
    lateinit var push: PushRegistrar
        private set

    /**
     * This install's Web Push keys, for pushes relayed through push.lurker.chat (lurker-dev/
     * RELAY_PLAN.md §6.2): read by [push] to register, and by the messaging service to open each
     * push. In secure storage of their own — see `KeystoreSecureStorage`.
     */
    lateinit var pushKeys: RelayPushKeys
        private set

    /**
     * Same shape as reachability and push: the kit decides the number, the app makes the platform
     * call. Android has no first-party launcher badge outside notifications — a launcher draws a
     * dot or a count from the app's *notifications* — so there is nothing for this to write to, and
     * it only logs. What a launcher shows is the app's notifications, so those come down as their
     * messages are read instead (`PushNotifier.clearRead`). No third-party badge library: they poke
     * private launcher APIs.
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
            // FCM starts this process for every push that arrives while it's dead, with no activity.
            // A restored session connects on the first foreground instead (`ProcessLifecycleOwner`'s
            // start, below), not for a phone in someone's pocket.
            startsInForeground = false,
        )

        // Keep the badge honest (lurker#490, lurker-ios#134), driven off state so it follows
        // read-state from other devices. ⚠ Before anything applies a frame: the restore's connect
        // is a `task`, so it starts only after this returns, and on `Main.immediate` this collector
        // subscribes now, not later — so no state published by the restore can slip past it.
        badge.follow(model.statePublisher, scope)
        // A notification comes down once its message is read, here or anywhere (`PushNotifier.clearRead`).
        // Only when the buffers changed: the publisher emits for every typing and presence frame, and
        // reading the shade is a call into the system.
        scope.launch {
            var buffers: Any? = null
            model.statePublisher.collect { state ->
                if (state.buffers === buffers) return@collect
                buffers = state.buffers
                PushNotifier.clearRead(this@LurkerApp, state)
            }
        }

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

        PushNotifier.createChannels(this)
        NotificationSounds.preload(this)
        pushKeys = RelayPushKeys(KeystoreSecureStorage(this, prefsName = PUSH_KEYS_FILE, keyAlias = PUSH_KEYS_ALIAS))
        // New keys for each session: the last account's pushes, still in flight, then can't open.
        model.onPushStateReset = pushKeys::forget
        push = PushRegistrar(this, model, scope, pushKeys)

        wireCallbacks()
        // Before any activity exists to deliver a redirect.
        browserSignIn.onOrphanRedirect = ::resumeSignIn
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
     * A redirect that found no attempt waiting: this process was started by it, after the system
     * killed the one that opened the page. The kit finishes from what it saved, or says why not.
     */
    private fun resumeSignIn(callback: String) {
        scope.launch {
            // Remembered as `signIn` does, normalized since what was typed died with the process.
            model.resumeSignIn(callback)?.let { server -> uiPreferences.lastServerURL = server }
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

        // A join that didn't happen, or a DM that couldn't be asked for, says why, as a snackbar over
        // whatever is on screen.
        model.onJoinNotice = { notice -> events.send(AppEvent.Notice(notice.message)) }

        // A buffer this device opened has its row — navigate, as for a join: a DCC chat opened or
        // accepted (lurker#270), or a DM from a profile's Send Message, a Friends row, `/msg` or
        // `/query` (`ChatViewModel.openAndShow`, lurker-ios#201). Only once the row exists: the kit
        // holds the open until then (`PendingOpens`), since landing on an absent buffer in a settled
        // roster pops straight back.
        model.onBufferOpened = { key -> events.send(AppEvent.OpenBuffer(key)) }

        // A highlight, DM or always-notify line while the app is open — push's foreground half
        // (lurker#1098). Whether it's worth showing is the center's call: not for the buffer on
        // screen, not while backgrounded.
        model.onNotify = { notification -> toastCenter.post(notification, model.state.settings) }

        // An invitation offers a Join on a snackbar (lurker#261) — the web's toast, which a snackbar
        // can carry and an iOS toast can't (iOS asks in an alert). One at a time: the system buffer
        // holds every invitation, so a flood is one snackbar rather than a queue of them.
        model.onInvited = { networkId, channel, from ->
            if (events.notices.value.none { it.action != null }) {
                val network = model.state.networks[networkId]?.displayName
                val text = if (network != null) "$from invited you to $channel on $network" else "$from invited you to $channel"
                val join = NoticeAction("Join") { model.requestJoin(networkId = networkId, channel = channel, opens = true) }
                events.send(AppEvent.Notice(text, join))
            }
        }

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
                    // The previous account's messages must not stay on the lock screen. (The token
                    // goes too: `push.signedOut`, below.)
                    PushNotifier.clearAll(this@LurkerApp)
                }
                // Only a session ENDING: a share received while signed out waits through a sign-in
                // attempt that fails (LoggingIn → LoggedOut) for the one that works.
                if (was == ChatViewModel.SessionState.LoggedIn && session != ChatViewModel.SessionState.LoggedIn) {
                    uploads.reset()
                    push.signedOut()
                }
                // Signing in is the moment push becomes askable. Only the transition: a restored
                // session is replayed here as LoggedIn at launch, which may be FCM starting the process
                // in the background, and `MainActivity.onStart` asks for that one.
                if (was != ChatViewModel.SessionState.LoggedIn && session == ChatViewModel.SessionState.LoggedIn) {
                    push.enableIfSignedIn(mayPrompt = true)
                }
                was = session
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
                    // Push re-enables on every foreground from `MainActivity.onStart` instead: the
                    // permission prompt needs the activity, which isn't up yet when this runs.
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

        /** The relayed-push keys' preferences file — excluded from backup in res/xml — and Keystore key. */
        private const val PUSH_KEYS_FILE = "lurker_push_keys"
        private const val PUSH_KEYS_ALIAS = "lurker_push_keys_key"

        /**
         * How this install names itself on the server's list of authorized apps. iOS says
         * "Lurker for iPhone" / "for iPad"; `Build.MODEL` would be the analogue, but it reads as a
         * part number on most phones ("SM-S918B"), so the platform it is.
         */
        const val APP_NAME = "Lurker for Android"
    }
}
