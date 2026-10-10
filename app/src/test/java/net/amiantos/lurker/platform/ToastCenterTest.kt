// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.StatusNotification
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Whether a notification is worth showing, where it goes, and when it sounds. */
class ToastCenterTest {

    private fun notification(nick: String = "bob", target: String = "#a", kind: StatusNotification.Kind = StatusNotification.Kind.AlwaysNotify) =
        StatusNotification(kind = kind, key = BufferKey(1, target), nick = nick, text = "hi", messageId = 1, date = Instant.EPOCH)

    /** Always-notify sounds by default (plink). */
    private val settings = Settings(registry = emptyMap(), values = emptyMap())

    private class Surface(val shows: BufferKey? = null, var takes: Boolean = true) : ToastCenter.Surface {
        val taken = mutableListOf<StatusNotification>()
        override fun showsBuffer(key: BufferKey): Boolean = shows?.id == key.id
        override fun take(notification: StatusNotification): Boolean {
            if (!takes) return false
            taken += notification
            return true
        }
    }

    private class Rig(foreground: Boolean = true, var now: Instant = Instant.EPOCH) {
        val played = mutableListOf<String>()
        var foreground = foreground
        val center = ToastCenter(isForeground = { this.foreground }, play = { played += it }, now = { now })
    }

    @Test
    fun theNewestSurfaceIsAskedFirstAndTheSoundGoesWithTheOneThatShowed() {
        val rig = Rig()
        val list = Surface()
        val chat = Surface()
        rig.center.register(list)
        rig.center.register(chat)
        rig.center.post(notification(), settings)
        assertEquals(1, chat.taken.size)
        assertEquals(0, list.taken.size)
        assertEquals(listOf("plink"), rig.played)
        // A surface that can't be seen passes it on.
        chat.takes = false
        rig.now = Instant.EPOCH.plusSeconds(10)
        rig.center.post(notification(), settings)
        assertEquals(1, list.taken.size)
        assertEquals(listOf("plink", "plink"), rig.played)
    }

    @Test
    fun nothingInTheBackgroundOrForTheBufferOnScreenOrWhenNobodyShowsIt() {
        val rig = Rig(foreground = false)
        val chat = Surface()
        rig.center.register(chat)
        rig.center.post(notification(), settings)
        assertEquals(0, chat.taken.size)
        rig.foreground = true
        // The line is arriving in plain view: no toast, no sound, anywhere.
        val showing = Surface(shows = BufferKey(1, "#A"))
        rig.center.register(showing)
        rig.center.post(notification(target = "#a"), settings)
        assertEquals(0, chat.taken.size + showing.taken.size)
        assertEquals(emptyList<String>(), rig.played)
        // Nobody takes it (a dialog over everything): no sound either.
        chat.takes = false
        showing.takes = false
        rig.center.post(notification(target = "#b"), settings)
        assertEquals(emptyList<String>(), rig.played)
    }

    @Test
    fun aBurstFromOneSourceSoundsOnceAndAnUnregisteredSurfaceIsNotAsked() {
        val rig = Rig()
        val chat = Surface()
        val unregister = rig.center.register(chat)
        rig.center.post(notification(), settings)
        rig.now = Instant.EPOCH.plusSeconds(1)
        rig.center.post(notification(), settings)
        // Another person in the same buffer is their own source.
        rig.center.post(notification(nick = "Alice"), settings)
        rig.now = Instant.EPOCH.plusSeconds(4)
        rig.center.post(notification(nick = "alice"), settings)
        assertEquals(4, chat.taken.size)
        assertEquals(listOf("plink", "plink", "plink"), rig.played)
        // A kind whose sound is off is shown in silence.
        rig.center.post(notification(nick = "carol", kind = StatusNotification.Kind.Highlight), settings)
        assertEquals(3, rig.played.size)
        // A sound switched on by settings plays its pick.
        val on = Settings(
            registry = emptyMap(),
            values = mapOf(
                "notifications.highlight.sound.enabled" to SettingValue.Bool(true),
                "notifications.highlight.sound.choice" to SettingValue.String("knock"),
            ),
        )
        rig.center.post(notification(nick = "dave", kind = StatusNotification.Kind.Highlight), on)
        assertEquals("knock", rig.played.last())
        unregister()
        rig.center.post(notification(nick = "erin"), settings)
        assertEquals(6, chat.taken.size)
    }
}
