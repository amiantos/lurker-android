// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.amiantos.lurker.ui.shell.AppRoot
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.uploads.SharePayload

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
            // A share is consumed the same way and for the same reason: a recreation replays the intent,
            // and a relaunch from recents replays the share that once launched the app.
            consumeShare(intent)
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
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
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
    }

    override fun onResume() {
        super.onResume()
        app.browserSignIn.onHostResumed()
    }

    override fun onStop() {
        app.browserSignIn.detach(this)
        super.onStop()
    }
}
