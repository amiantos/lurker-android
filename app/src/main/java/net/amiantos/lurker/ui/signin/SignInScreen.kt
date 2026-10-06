// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.amiantos.lurker.ui.theme.LurkerTheme
import kotlinx.coroutines.flow.StateFlow
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * Screen 1: the server's address, then that server's own sign-in and approval pages in a Custom
 * Tab (`BrowserSignIn`). The app never handles the password. lurker-ios's `LoginViewController`.
 *
 * Leaving here on success is the shell's doing — it observes the session; this screen only
 * starts the sign-in.
 *
 * [initialServer] prefills the field (the last server used), so a returning user after sign-out
 * doesn't retype it. [onSignIn] starts the attempt; it outlives this screen (see `LurkerApp.signIn`).
 * While the approval page is up ([waiting]) the screen says so and offers [onCancel]: nothing tells
 * the app that the tab was closed.
 */
@Composable
fun SignInScreen(
    model: ChatViewModel,
    notice: StateFlow<String?>,
    waiting: StateFlow<Boolean>,
    initialServer: String,
    onSignIn: (server: String) -> Unit,
    onCancel: () -> Unit,
) {
    val session by model.sessionPublisher.collectAsStateWithLifecycle(initialValue = model.session)
    // The reason a sign-in failed, or why a prior session ended (a mid-session 401 bounces here
    // with an explanation). The publisher replays its latest, so it is right on first frame.
    val status by model.statusPublisher.collectAsStateWithLifecycle(initialValue = null)
    // What the kit cannot know: the browser side's reason (`BrowserSignIn.notice`). The kit's
    // word wins when it has one; a new attempt clears both.
    val browserNotice by notice.collectAsStateWithLifecycle()
    val inBrowser by waiting.collectAsStateWithLifecycle()
    var server by rememberSaveable { mutableStateOf(initialServer) }
    SignInContent(
        server = server,
        onServerChange = { server = it },
        busy = session == ChatViewModel.SessionState.LoggingIn,
        inBrowser = inBrowser,
        status = status ?: browserNotice,
        onSignIn = { onSignIn(server) },
        onCancel = onCancel,
    )
}

@Composable
private fun SignInContent(
    server: String,
    onServerChange: (String) -> Unit,
    busy: Boolean,
    inBrowser: Boolean,
    status: String?,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val submit = {
        if (!busy) {
            focus.clearFocus()
            onSignIn()
        }
    }
    // `safeDrawing` includes the IME, so the form scrolls above the keyboard rather than under it,
    // as iOS insets its scroll view by the keyboard's overlap.
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A phone-width form, centred, on a tablet: a field stretched across 1200dp reads as
            // a banner, not a place to type.
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Lurker", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Enter your Lurker server. For lurker.chat, that's app.lurker.chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = onServerChange,
                    label = { Text("Server") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    // 24 after the blurb, 12 between the rest, as iOS's stack spaces them.
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Button(onClick = submit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in")
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
                if (inBrowser) {
                    Text(
                        "Finish signing in in your browser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancel")
                    }
                }
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = LurkerTheme.colors.badText,
                        // Read out when it appears: it's the only word on why nothing happened.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

@Preview(name = "Sign in — light")
@Composable
private fun SignInPreviewLight() {
    LurkerTheme(darkTheme = false) {
        SignInContent(
            "https://app.lurker.chat",
            {},
            busy = false,
            inBrowser = false,
            status = "Enter a server URL.",
            onSignIn = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Sign in — dark, busy")
@Composable
private fun SignInPreviewDark() {
    LurkerTheme(darkTheme = true) {
        SignInContent("https://app.lurker.chat", {}, busy = true, inBrowser = true, status = null, onSignIn = {}, onCancel = {})
    }
}
