// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.amiantos.lurker.ui.shell.AppRoot
import net.amiantos.lurker.ui.theme.LurkerTheme

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
        // was up, so there was nothing for `onNewIntent` to reach. Only a fresh launch carries a new
        // one — a recreation (saved state) or a relaunch from recents replays the old intent, which
        // must not be read as an answer to a later attempt.
        val replayed = savedInstanceState != null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!replayed) app.browserSignIn.onRedirect(intent.dataString)
        setContent {
            LurkerTheme {
                AppRoot(
                    model = app.model,
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
        app.browserSignIn.onRedirect(intent.dataString)
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
