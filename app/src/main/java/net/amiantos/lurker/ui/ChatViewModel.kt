// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import net.amiantos.lurker.store.SocketStatus

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
 * observes.
 *
 * Owns two lifecycles:
 *  - session (#4): restore a persisted token on launch; a mid-session 401 bounces
 *    cleanly to sign-in.
 *  - connection (#5): reconnect with backoff when the socket drops, resuming from
 *    the highest event id seen (`?since=`); and on return-to-foreground, reconnect
 *    a socket that died while backgrounded.
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

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var isForeground = true
    private var backgroundedAt = 0L

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            isForeground = true
            onForeground()
        }

        override fun onStop(owner: LifecycleOwner) {
            isForeground = false
            backgroundedAt = System.currentTimeMillis()
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        restoreSession()
    }

    /**
     * Route frames. A 401 is session-level (never reaches the store); socket
     * open/close additionally drive the reconnect machinery.
     */
    private fun onFrame(frame: ServerFrame) {
        // Frames arrive on OkHttp/IO threads. store.apply is thread-safe, but the
        // reconnect bookkeeping (reconnectJob/reconnectAttempt/isForeground) is
        // confined to the main thread, so hop there for it via viewModelScope.
        when (frame) {
            is ServerFrame.Unauthorized -> onAuthLost()
            is ServerFrame.SocketOpen -> {
                store.apply(frame)
                viewModelScope.launch { reconnectAttempt = 0 } // a clean connection resets backoff
            }
            is ServerFrame.SocketClosed -> {
                store.apply(frame)
                viewModelScope.launch { onSocketDropped() }
            }
            else -> store.apply(frame)
        }
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
        cancelReconnect()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                client.logout()
                sessions.clear()
            }
            store.reset()
            _session.value = SessionState.LoggedOut
        }
    }

    // --- Connection lifecycle (#5) ----------------------------------------------

    /** A drop while signed-in + foregrounded schedules a backed-off reconnect. */
    private fun onSocketDropped() {
        if (_session.value == SessionState.LoggedIn && isForeground) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return // one attempt in flight already
        val wait = backoffMillis(reconnectAttempt++)
        reconnectJob = viewModelScope.launch {
            delay(wait)
            doReconnect(force = false)
        }
    }

    /**
     * Back on screen: a socket that died in the background often hasn't fired
     * onFailure yet (pings are suspended while backgrounded), so the status can
     * still read Connected over a dead connection. Reconnect immediately if we're
     * disconnected OR we were backgrounded long enough that the socket may be
     * stale; a brief app-switch leaves a healthy socket alone.
     */
    private fun onForeground() {
        if (_session.value != SessionState.LoggedIn) return
        val stale = System.currentTimeMillis() - backgroundedAt > STALE_AFTER_MS
        if (store.state.value.connection == SocketStatus.Connected && !stale) return
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch { doReconnect(force = true) }
    }

    /**
     * The single reconnect path. [force] reconnects even when the status reads
     * Connected (the stale-socket case); otherwise a scheduled attempt bails if the
     * connection was re-established meanwhile, so a pending backoff timer can't tear
     * down a good socket.
     */
    private suspend fun doReconnect(force: Boolean) {
        if (_session.value != SessionState.LoggedIn || !isForeground) return
        if (!force && store.state.value.connection == SocketStatus.Connected) return
        withContext(Dispatchers.IO) { client.reconnect(store.state.value.maxEventId) }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
    }

    /** 1s, 2s, 4s … capped at 30s. */
    private fun backoffMillis(attempt: Int): Long =
        minOf(BASE_BACKOFF_MS shl attempt.coerceIn(0, MAX_BACKOFF_SHIFT), MAX_BACKOFF_MS)

    /**
     * The token expired or was revoked elsewhere. Drop the (already-dead) session
     * without a revoke round-trip and bounce to sign-in with an explanation.
     */
    private fun onAuthLost() {
        // A 401 can arrive from both the REST call and the WS upgrade; handle the
        // first and ignore the rest.
        if (_session.value == SessionState.LoggedOut) return
        // Flip the visible state + message synchronously so the sign-in screen shows
        // the explanation the instant we bounce; do the teardown (including the
        // main-confined reconnect cancel) in the coroutine.
        _status.value = "Your session ended — please sign in again."
        _session.value = SessionState.LoggedOut
        viewModelScope.launch {
            cancelReconnect()
            withContext(Dispatchers.IO) {
                client.close()
                sessions.clear()
            }
            store.reset()
        }
    }

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        // Tear down the socket + OkHttp threads when the ViewModel goes away.
        client.close()
    }

    private companion object {
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_SHIFT = 5 // 1s << 5 = 32s, clamped to 30s
        // Backgrounded longer than this → assume the socket may be dead and
        // reconnect on return, even if the status still reads Connected.
        const val STALE_AFTER_MS = 30_000L
    }
}
