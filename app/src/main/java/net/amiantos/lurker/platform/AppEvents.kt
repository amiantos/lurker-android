// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import net.amiantos.lurkerkit.model.BufferKey

/**
 * What the kit asks of the screen, on its way from `LurkerApp` (which wires the view model's
 * callbacks, and outlives every activity) to `MainScaffold` (which owns the navigator, and lives in
 * composition). lurker-ios's `SceneDelegate` calls straight into its navigation; here the two ends
 * don't share a lifetime, so the events wait between them.
 */
sealed interface AppEvent {
    /**
     * A join or a DCC chat this device asked for has its buffer — go there (lurker-ios#57). With
     * [jumpTo], land on that message rather than the bottom: U9's notification tap, which carries
     * the message the push was about (`MainScaffold.open`).
     */
    data class OpenBuffer(val key: BufferKey, val jumpTo: Long? = null) : AppEvent

    /** A one-line notice over whatever is on screen — a join that didn't happen says why. */
    class Notice(val message: String) : AppEvent

    /**
     * The store rekeyed a buffer (a nick change in a DM, a channel rename). Whatever the navigator
     * holds under [from] has to follow it to [to].
     */
    data class BufferRenamed(val from: BufferKey, val to: BufferKey) : AppEvent
}

/**
 * The hand-off itself, in two parts — plus a third, the composer's refusal nudge ([refusals]), which
 * every composer hears and none queues.
 *
 * **Navigation** (`OpenBuffer`, `BufferRenamed`) rides a channel with one collector, `MainScaffold`,
 * so a rename that lands while the activity is being recreated still reaches the navigator.
 *
 * **Notices** are a list, not a stream: one is removed only once a host has shown it for its full
 * time ([consume]), so a rotation mid-notice shows it again rather than losing it. Whichever host
 * claimed last shows them ([claimNotices]) — a full-screen dialog's over the scaffold's, since a
 * snackbar in the activity's window is drawn under every dialog window, where nobody reads it. iOS
 * puts its toast on the sheet on top for the same reason.
 *
 * Both are taken only while a screen is attached ([attach]). iOS's `land(on:)` and `showNotice`
 * do nothing without a scene; a queue that outlived the activity would instead fire on the next
 * launch — an hours-old join opening over the launch restore, a refusal nobody remembers asking
 * for. Renames are the exception: the navigator's saved state still holds the old key, and must
 * hear where it went whenever it comes back.
 */
class AppEvents {
    private val channel = Channel<AppEvent>(Channel.UNLIMITED)

    /** `OpenBuffer` and `BufferRenamed`, for `MainScaffold`'s collector. Never a `Notice`. */
    val events: Flow<AppEvent> = channel.receiveAsFlow()

    private val pending = MutableStateFlow<List<AppEvent.Notice>>(emptyList())

    /** Notices waiting to be shown, oldest first. */
    val notices: StateFlow<List<AppEvent.Notice>> = pending.asStateFlow()

    private val hosts = MutableStateFlow<List<Any>>(emptyList())

    /** The hosts that can show a notice, in the order they claimed; the last one shows. */
    val noticeHosts: StateFlow<List<Any>> = hosts.asStateFlow()

    /** The current attachment ([attach]'s token), or null while no screen is up. */
    private var attachment: Any? = null
    private val attached: Boolean get() = attachment != null

    private val refused = MutableSharedFlow<BufferKey>(extraBufferCapacity = 64)

    /**
     * The server refused a line typed in this buffer, and it is waiting in the kit's hold
     * (`ChatViewModel.onSendRefused`, lurker-ios#128). For the composer showing that buffer, which
     * takes it back with `takeUnsent`.
     *
     * ⚠ A nudge, not the delivery, and nothing replays it: the line waits in the kit, not here. A
     * composer that isn't on screen — another buffer is open, or the app is between activities —
     * drains the hold itself the next time it appears, as iOS's `viewDidAppear` does. Not gated on
     * [attach] for the same reason: dropping a nudge loses nothing.
     */
    val refusals: SharedFlow<BufferKey> = refused.asSharedFlow()

    /** See [refusals]. */
    fun sendRefused(key: BufferKey) {
        refused.tryEmit(key)
    }

    fun send(event: AppEvent) {
        when (event) {
            is AppEvent.Notice -> if (attached) pending.update { it + event }
            is AppEvent.OpenBuffer -> if (attached) channel.trySend(event)
            is AppEvent.BufferRenamed -> channel.trySend(event)
        }
    }

    /** A notice was shown for its full time (or dismissed) — take it off the list. */
    fun consume(notice: AppEvent.Notice) {
        pending.update { list -> if (list.firstOrNull() === notice) list.drop(1) else list }
    }

    fun claimNotices(host: Any) {
        hosts.update { it - host + host }
    }

    fun releaseNotices(host: Any) {
        hosts.update { it - host }
    }

    /**
     * A screen that can act on events is up; returns its token for [detach]. The latest attach is the
     * attachment, not a count: `MainScaffold` skips [detach] across a configuration change — the gap
     * the queue exists to bridge — and the recreated one attaches again, which a count would read as
     * two screens and never get back to none.
     */
    fun attach(): Any {
        val token = Any()
        attachment = token
        return token
    }

    /**
     * The screen holding [token] has gone. ⚠ A no-op unless it is still the CURRENT attachment: on a
     * quick sign-out and sign-in, the old session's scaffold can still be fading out after the new
     * one attached, and its late detach must not switch the new screen's events off (dropping its
     * joins' OpenBuffers and its notices) or clear the notices waiting for it.
     */
    fun detach(token: Any) {
        if (attachment !== token) return
        attachment = null
        pending.value = emptyList()
    }

    /**
     * Sign-out: whatever is still queued belongs to the previous session, and must not open a buffer
     * or read out a notice in the next one's.
     */
    fun drain() {
        while (channel.tryReceive().isSuccess) Unit
        pending.value = emptyList()
    }
}

/**
 * The app's [AppEvents], for the surfaces that show notices without being handed them — a
 * full-screen dialog's own notice host. Provided by `MainScaffold`; null outside it (previews).
 */
val LocalAppEvents = staticCompositionLocalOf<AppEvents?> { null }
