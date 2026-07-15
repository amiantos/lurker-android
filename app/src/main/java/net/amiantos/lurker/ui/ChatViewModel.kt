// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurker.auth.PersistedSession
import net.amiantos.lurker.auth.SessionStore
import net.amiantos.lurker.client.LurkerClient
import net.amiantos.lurker.client.ServerFrame
import net.amiantos.lurker.model.Backend
import net.amiantos.lurker.model.BufferKey
import net.amiantos.lurker.store.ChatState
import net.amiantos.lurker.store.LurkerStore

/** Where the account stands with the server. */
sealed interface SessionState {
    /** Checking the Keystore for a persisted session on launch — before we know which screen to show. */
    data object Restoring : SessionState
    data object LoggedOut : SessionState
    data object LoggingIn : SessionState
    data object LoggedIn : SessionState
}

/**
 * Owns the client + store for the app's lifetime (survives config changes). The
 * client does I/O and emits frames; the store folds them into [chatState]; the UI
 * observes. Also owns session lifecycle (#4): a persisted token is restored on
 * launch, and a mid-session 401 drops cleanly back to sign-in.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LurkerStore()
    private val sessions = SessionStore(app)
    private val client = LurkerClient(onFrame = ::onFrame)

    val chatState: StateFlow<ChatState> = store.state

    private val _session = MutableStateFlow<SessionState>(SessionState.Restoring)
    val session: StateFlow<SessionState> = _session

    /** Transient status/error text for the login screen. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    init {
        restoreSession()
    }

    /** Route frames: a 401 is session-level and never reaches the store. */
    private fun onFrame(frame: ServerFrame) {
        if (frame is ServerFrame.Unauthorized) onAuthLost() else store.apply(frame)
    }

    private fun restoreSession() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { sessions.load() }
            if (saved == null) {
                _session.value = SessionState.LoggedOut
                return@launch
            }
            // Go LoggedIn BEFORE connecting so that if the token is stale, the
            // Unauthorized fired during start() lands afterwards and deterministically
            // wins the bounce to sign-in (rather than racing a later LoggedIn).
            _session.value = SessionState.LoggedIn
            withContext(Dispatchers.IO) {
                client.restore(saved.server, saved.token)
                client.start()
            }
        }
    }

    fun login(backend: Backend, server: String, identifier: String, password: String) {
        _session.value = SessionState.LoggingIn
        _status.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { client.login(backend, server, identifier, password) }
            when (result) {
                is LurkerClient.LoginResult.Success -> {
                    withContext(Dispatchers.IO) {
                        sessions.save(PersistedSession(backend, server, result.token))
                        client.start()
                    }
                    _session.value = SessionState.LoggedIn
                }
                is LurkerClient.LoginResult.Failure -> {
                    _session.value = SessionState.LoggedOut
                    _status.value = result.message
                }
            }
        }
    }

    fun openBuffer(key: BufferKey) = client.openBuffer(key.networkId, key.target)

    fun send(key: BufferKey, text: String) = client.sendMessage(key.networkId, key.target, text)

    fun clearError() = store.clearError()

    /** Deliberate sign-out: revoke server-side, forget the token, reset state. */
    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                client.logout()
                sessions.clear()
            }
            store.reset()
            _session.value = SessionState.LoggedOut
        }
    }

    /**
     * The token expired or was revoked elsewhere. Drop the (already-dead) session
     * without a revoke round-trip and bounce to sign-in with an explanation.
     */
    private fun onAuthLost() {
        // A 401 can arrive from both the REST call and the WS upgrade; handle the
        // first and ignore the rest.
        if (_session.value == SessionState.LoggedOut) return
        // Flip the visible state + message synchronously so the sign-in screen shows
        // the explanation the instant we bounce; do the teardown in the background.
        _status.value = "Your session ended — please sign in again."
        _session.value = SessionState.LoggedOut
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                client.close()
                sessions.clear()
            }
            store.reset()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Tear down the socket + OkHttp threads when the ViewModel goes away.
        client.close()
    }
}
