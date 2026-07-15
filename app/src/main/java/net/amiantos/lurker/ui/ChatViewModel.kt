// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurker.client.LurkerClient
import net.amiantos.lurker.model.Backend
import net.amiantos.lurker.model.BufferKey
import net.amiantos.lurker.store.ChatState
import net.amiantos.lurker.store.LurkerStore

/** Where the account stands with the server. Persisted tokens/expiry are #4. */
sealed interface SessionState {
    data object LoggedOut : SessionState
    data object LoggingIn : SessionState
    data object LoggedIn : SessionState
}

/**
 * Owns the client + store for the app's lifetime (survives config changes). The
 * client does I/O and emits frames; the store folds them into [chatState]; the UI
 * observes. This is the seam #5 (connection lifecycle) hooks into.
 */
class ChatViewModel : ViewModel() {
    private val store = LurkerStore()
    private val client = LurkerClient(onFrame = store::apply)

    val chatState: StateFlow<ChatState> = store.state

    private val _session = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val session: StateFlow<SessionState> = _session

    /** Transient status/error text for the login screen. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    fun login(backend: Backend, server: String, identifier: String, password: String) {
        _session.value = SessionState.LoggingIn
        _status.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                client.login(backend, server, identifier, password).also { r ->
                    if (r is LurkerClient.LoginResult.Success) client.start()
                }
            }
            when (result) {
                is LurkerClient.LoginResult.Success -> _session.value = SessionState.LoggedIn
                is LurkerClient.LoginResult.Failure -> {
                    _session.value = SessionState.LoggedOut
                    _status.value = result.message
                }
            }
        }
    }

    fun openBuffer(key: BufferKey) = client.openBuffer(key.networkId, key.target)

    fun send(key: BufferKey, text: String) = client.sendMessage(key.networkId, key.target, text)

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { client.logout() }
            store.reset()
            _session.value = SessionState.LoggedOut
        }
    }
}
