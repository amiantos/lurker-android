// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.amiantos.lurker.platform.AppEvents
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
    dccOffers: DccOffers,
    uploads: UploadServices,
    signInNotice: StateFlow<String?>,
    lastServerURL: () -> String,
    onSignIn: (server: String) -> Unit,
) {
    val session by model.sessionPublisher.collectAsStateWithLifecycle(initialValue = model.session)
    // The window's ground, behind the panes and the gap between them.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Keyed on signed-in or not, so `LoggedOut` ↔ `LoggingIn` doesn't rebuild the form.
        Crossfade(targetState = session == ChatViewModel.SessionState.LoggedIn, label = "root") { signedIn ->
            if (signedIn) {
                MainScaffold(
                    model = model,
                    uiPreferences = uiPreferences,
                    events = events,
                    dccOffers = dccOffers,
                    uploads = uploads,
                    onSignOut = model::logout,
                )
            } else {
                SignInScreen(model = model, notice = signInNotice, initialServer = lastServerURL(), onSignIn = onSignIn)
            }
        }
    }
}
