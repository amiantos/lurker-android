// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import net.amiantos.lurker.R
import net.amiantos.lurkerkit.model.StatusNotification

/**
 * The in-app notification sounds: the web's six (`public/sounds`), bundled as they are.
 *
 * A `SoundPool` on the notification stream rather than a `MediaPlayer`, for the three things an
 * alert sound has to get right that a player would have to be talked into: the ringer's silent and
 * vibrate modes silence them, they play at the notification volume rather than over the media
 * volume, and they never take audio focus — so a ping neither pauses the reader's music nor
 * interrupts a video in the media viewer. The web's per-kind volume has no place in that model;
 * the phone's notification volume is the one the reader already set for this. Its 0 still means
 * silence (`StatusNotification.sound`). iOS plays them as system sounds for the same reasons.
 *
 * Loaded once, up front ([preload]): the set is fixed and small (about 270 KB), and a `SoundPool`
 * loads asynchronously — a sound asked for before its load has finished is skipped rather than
 * waited on, which only the first alert of the process's first second could hit.
 */
object NotificationSounds {
    private val pool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }

    /** Sound id by name, once loaded. */
    private val ids = mutableMapOf<String, Int>()
    private val loaded = mutableSetOf<Int>()

    private val resources = mapOf(
        "ping" to R.raw.ping,
        "chime" to R.raw.chime,
        "pop" to R.raw.pop,
        "beep" to R.raw.beep,
        "knock" to R.raw.knock,
        "plink" to R.raw.plink,
    )

    /** Load every bundled sound. Cheap, and idempotent. */
    fun preload(context: Context) {
        if (ids.isNotEmpty()) return
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded += sampleId }
        for (name in StatusNotification.sounds) {
            val resource = resources[name] ?: continue
            ids[name] = pool.load(context, resource, 1)
        }
    }

    /** Play [name], one of `StatusNotification.sounds`. Nothing for a name that isn't bundled or isn't loaded yet. */
    fun play(name: String) {
        val id = ids[name] ?: return
        if (id !in loaded) return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }
}
