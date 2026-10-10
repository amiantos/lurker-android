// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.amiantos.lurker.platform.AppEvents
import net.amiantos.lurker.platform.ToastCenter
import net.amiantos.lurker.ui.dcc.DccOffers
import net.amiantos.lurker.prefs.UiPreferences
import net.amiantos.lurker.ui.signin.SignInScreen
import net.amiantos.lurker.ui.uploads.UploadServices
import kotlinx.coroutines.flow.StateFlow
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * The root: the session decides the screen. Sign-in and restore → the app proper; sign-out and a
 * mid-session 401 → back to sign-in (whose status line says why). `LoggingIn` stays on sign-in,
 * which shows its own progress.
 *
 * The two are swapped whole, never stacked, as iOS swaps the window's root: on a tablet a stack
 * would leave the sign-in screen in a pane with the previous account's buffer list beside it.
 * Swapping also drops everything the signed-in side held (open dialogs, the navigator's history)
 * with it, the way iOS dismisses whatever was presented before showing sign-in.
 *
 * [lastServerURL] is read when the sign-in screen is built, so a sign-out prefills the server
 * that was just in use. [uiPreferences] is handed down from `LurkerApp` rather than looked up from
 * the Application inside composition, so what the app proper reads and writes is visible here.
 */
@Composable
fun AppRoot(
    model: ChatViewModel,
    uiPreferences: UiPreferences,
    events: AppEvents,
    toastCenter: ToastCenter,
    dccOffers: DccOffers,
    uploads: UploadServices,
    signInNotice: StateFlow<String?>,
    signInWaiting: StateFlow<Boolean>,
    lastServerURL: () -> String,
    onSignIn: (server: String) -> Unit,
    onCancelSignIn: () -> Unit,
) {
    val session by model.sessionPublisher.collectAsStateWithLifecycle(initialValue = model.session)
    val signedIn = session == ChatViewModel.SessionState.LoggedIn
    // Which session this is: bumped on every arrival at LoggedIn, so each session gets a scaffold of
    // its own. Keyed on signed-in alone, a sign-in landing while the last sign-out's fade is still
    // running would hand the NEW session the OLD scaffold — Crossfade reuses the content still on
    // screen for a target it already holds — with the old account's dialogs and history in it.
    // Saveable, and an Int, so a rotation restores the same key and the scaffold's saved state with
    // it. Counted in composition rather than an effect: an effect runs after the frame, and that one
    // frame is exactly the reuse this exists to prevent.
    val generations = rememberSaveable { SessionGenerations.start(signedIn) }
    val current = SessionGenerations.advance(generations, signedIn)
    // The window's ground, behind the panes and the gap between them.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Signed out is one key (`SIGNED_OUT`) whether `LoggedOut` or `LoggingIn`, so the form isn't
        // rebuilt between them; signed in is the session's generation.
        Crossfade(targetState = if (signedIn) current else SIGNED_OUT, label = "root") { shown ->
            if (shown != SIGNED_OUT) {
                MainScaffold(
                    model = model,
                    uiPreferences = uiPreferences,
                    events = events,
                    toastCenter = toastCenter,
                    dccOffers = dccOffers,
                    uploads = uploads,
                    onSignOut = model::logout,
                    // False while the scaffold fades out after a sign-out or a 401 — or after a newer
                    // session has replaced it: its dialogs go at once rather than sitting over what's
                    // coming in for the fade.
                    sessionLive = signedIn && shown == current,
                )
            } else {
                SignInScreen(
                    model = model,
                    notice = signInNotice,
                    waiting = signInWaiting,
                    initialServer = lastServerURL(),
                    onSignIn = onSignIn,
                    onCancel = onCancelSignIn,
                )
            }
        }
    }
}

/** The root's key while signed out — never a session generation, which count up from 0. */
private const val SIGNED_OUT = -1

/**
 * The session generation `AppRoot` keys the signed-in side on, as a saveable `IntArray`: `[0]` the
 * generation, `[1]` whether the last look was signed in. Bumped on each arrival at signed in, and
 * idempotent per edge, so a recomposition with the same session counts nothing.
 */
internal object SessionGenerations {
    fun start(signedIn: Boolean): IntArray = intArrayOf(0, if (signedIn) 1 else 0)

    fun advance(state: IntArray, signedIn: Boolean): Int {
        if (signedIn && state[1] == 0) state[0] += 1
        state[1] = if (signedIn) 1 else 0
        return state[0]
    }
}
