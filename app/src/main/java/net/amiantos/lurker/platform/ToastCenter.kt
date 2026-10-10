// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.StatusNotification
import java.time.Duration
import java.time.Instant

/**
 * The next in-app notification to show (lurker#1098) — lurker-ios's `ToastCenter`. The composer's
 * status row is where it surfaces, or the capsule over the buffer list when no conversation is on
 * screen, and the status row's highlight count is what's left once it's gone.
 *
 * Decides *whether* a notification is worth showing, the web's `shouldNotifyInApp`: not while the
 * app is in the background (push has that), and not for the buffer already on screen, where the
 * line is arriving in plain view.
 *
 * iOS posts through `NotificationCenter` and every screen listens; here the surfaces register
 * ([register]) and are asked in turn, newest first — the screen composed last is the one on top: a
 * conversation over the list on a phone, the conversation beside it on a tablet. Synchronous, so a
 * surface that shows the toast has said so before [post] decides on the sound.
 *
 * @param isForeground whether the app is visible — `ProcessLifecycleOwner`, which stays started
 *   across an activity recreated on rotation.
 * @param play plays a bundled sound by name (`NotificationSounds`).
 */
class ToastCenter(
    private val isForeground: () -> Boolean = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
    private val play: (String) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    /** A screen that can show an in-app notification. */
    interface Surface {
        /**
         * Whether this surface is showing [key] right now, uncovered — what keeps a notification
         * for it from toasting anywhere, since its line is arriving in plain view.
         */
        fun showsBuffer(key: BufferKey): Boolean = false

        /** Show [notification] here. False when this surface can't be seen, and the next is asked. */
        fun take(notification: StatusNotification): Boolean
    }

    private val surfaces = mutableListOf<Surface>()

    /** A surface came on screen; returns what takes it off again. */
    fun register(surface: Surface): () -> Unit {
        surfaces += surface
        return { surfaces -= surface }
    }

    /**
     * Every one is passed on. A burst from one source — ChanServ answering /HELP — becomes one
     * toast, which updates in place (`StatusToastQueue`). The web drops the repeats instead,
     * since its first toast stays up; a one-line toast has to show the latest.
     *
     * The sound goes with a toast a surface took, once for whichever one did, and a burst gets
     * one: the web's per-source throttle, kept here for the sound alone. The sound comes with a
     * toast you can see, as on the web: none under a dialog, where nothing says what it was or
     * where to go.
     */
    fun post(notification: StatusNotification, settings: Settings) {
        if (!isForeground()) return
        if (surfaces.any { it.showsBuffer(notification.key) }) return
        val shown = surfaces.asReversed().any { it.take(notification) }
        if (!shown) return
        val sound = notification.sound(settings) ?: return
        if (!soundThrottled(notification)) play(sound)
    }

    /** When each source last made a sound: network, buffer, nick and kind, the web's key. */
    private val lastSoundAt = mutableMapOf<String, Instant>()

    private fun soundThrottled(notification: StatusNotification): Boolean {
        val key = "${notification.key.id}::${notification.nick?.lowercase() ?: "?"}::${notification.kind.rawValue}"
        val at = now()
        val last = lastSoundAt[key]
        if (last != null && Duration.between(last, at) < SOUND_THROTTLE) return true
        lastSoundAt.entries.removeAll { Duration.between(it.value, at) >= SOUND_THROTTLE }
        lastSoundAt[key] = at
        return false
    }

    companion object {
        private val SOUND_THROTTLE: Duration = Duration.ofSeconds(3)
    }
}

/** The app's [ToastCenter], for the surfaces that show notifications. Provided by `MainScaffold`; null in previews. */
val LocalToastCenter = staticCompositionLocalOf<ToastCenter?> { null }
