// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.push.PushMessage
import net.amiantos.lurker.ui.shell.AppRoot
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.uploads.SharePayload
import net.amiantos.lurkerkit.client.NotificationTap
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * The app's one activity. Everything that must outlive it lives in [LurkerApp]; this hosts the
 * Compose tree and is where the sign-in redirect lands.
 *
 * `singleTask` (manifest), so the approval page's `chat.lurker:/oauth` redirect brings this task
 * forward, clears the Custom Tab off the top of it, and arrives in [onNewIntent] rather than
 * starting a second copy of the app on top of the tab.
 */
class MainActivity : ComponentActivity() {
    private val app: LurkerApp get() = application as LurkerApp

    /**
     * The notification-permission dialog, lent to the app's `PushRegistrar` while this is started.
     * Its answer goes back to the registrar, which outlives this activity — so a rotation mid-dialog
     * delivers it to the recreated activity's launcher, and it still lands.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> app.push.onPermissionResult(granted) }
    // InlinedApi: the registrar only prompts on Android 13+, where the permission exists.
    @SuppressLint("InlinedApi")
    private val promptForNotifications: () -> Unit = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A redirect can also be what *starts* this activity: the system reclaimed it while the tab
        // was up, so there was nothing for `onNewIntent` to reach — and it arrives WITH saved
        // state, so saved state cannot be what tells a fresh redirect from a replayed one. A
        // relaunch from recents replays the launching intent (flagged); a recreation replays
        // whatever `setIntent` left, which is why a redirect is consumed below once it is read.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!fromHistory) {
            consumeRedirect(intent)
            // A share only on a FRESH start. A relaunch from recents replays the share that once
            // launched the app, and so does the system recreating this activity after a process death
            // — from the ORIGINAL intent, whatever `setIntent` left, with saved state — which would
            // upload the files a second time. A share to the running task arrives in `onNewIntent`.
            if (savedInstanceState == null) consumeShare(intent)
            // A tap, likewise only on a fresh start: a relaunch from recents replays it.
            if (savedInstanceState == null) consumeNotificationTap(intent)
        }
        setContent {
            LurkerTheme {
                AppRoot(
                    model = app.model,
                    uiPreferences = app.uiPreferences,
                    events = app.events,
                    dccOffers = app.dccOffers,
                    uploads = app.uploads,
                    signInNotice = app.browserSignIn.notice,
                    lastServerURL = { app.uiPreferences.lastServerURL },
                    onSignIn = app::signIn,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Always before the `onResume` that follows it — which is what lets that resume read "no
        // redirect" as "the tab was closed" (see `RedirectWaiter`).
        consumeRedirect(intent)
        consumeShare(intent)
        consumeNotificationTap(intent)
    }

    /**
     * A tapped push notification (lurker-android#16): open the buffer it names, at the message it was
     * about. Ours (`PushNotifier`) and one the system drew from an older server's `notification`
     * block carry the same string extras, so both read here.
     *
     * Through `openFromNotification`, which waits for the scaffold: on a cold launch this runs before
     * it exists. Signed out — a notification that outlived its session — it's dropped, as on iOS:
     * signing in rebuilds the screen from scratch, and by then the tap is stale.
     */
    private fun consumeNotificationTap(intent: Intent) {
        val extras = intent.extras ?: return
        val fields = PushMessage.TAP_KEYS.mapNotNull { key -> extras.getString(key)?.let { key to it } }.toMap()
        val tap = NotificationTap.parse(fields) ?: return
        if (app.model.session == ChatViewModel.SessionState.LoggedIn) {
            app.events.openFromNotification(AppEvent.OpenBuffer(BufferKey(tap.networkId, tap.target), jumpTo = tap.messageId))
        }
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    /**
     * Something shared to Lurker from another app's share sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`):
     * handed to the share inbox, which the signed-in scaffold asks about — which conversation it goes to
     * — as soon as it's up. Then stripped from the intent the activity keeps, as a redirect is.
     *
     * The files' read grants came with this intent and last as long as this task, which is why the run
     * copies each one into the cache at its turn rather than reading the provider at upload time.
     */
    private fun consumeShare(intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val streams = if (action == Intent.ACTION_SEND) {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        app.uploads.shares.receive(SharePayload.of(streams.map(Uri::toString), text))
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    /**
     * Hand the intent's data to the sign-in, then strip it from the intent the activity keeps, so
     * a later recreation replays an intent that answers nothing.
     */
    private fun consumeRedirect(intent: Intent) {
        val data = intent.dataString ?: return
        app.browserSignIn.onRedirect(data)
        intent.data = null
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.browserSignIn.attach(this)
        // Re-run on every foreground, not just the first: FCM can rotate a token, and the user may
        // have allowed (or blocked) notifications in system settings while we were away. Cheap — the
        // token comes back the same and the server upserts. lurker-ios does this on scene activation.
        app.push.permissionPrompt = promptForNotifications
        app.push.enableIfSignedIn(mayPrompt = true)
    }

    override fun onResume() {
        super.onResume()
        app.browserSignIn.onHostResumed()
    }

    override fun onDestroy() {
        // Gone for good with the dialog maybe still up: its answer has nowhere to land, so settle the
        // registrar's wait with what the system now says, or it would wait for the process's life.
        // (Android 13+ only: there's no prompt to wait on before it.)
        if (isFinishing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            app.push.onPermissionResult(granted)
        }
        super.onDestroy()
    }

    override fun onStop() {
        // Only ours: a recreated activity may already have lent its own.
        if (app.push.permissionPrompt === promptForNotifications) app.push.permissionPrompt = null
        app.browserSignIn.detach(this)
        super.onStop()
    }
}
