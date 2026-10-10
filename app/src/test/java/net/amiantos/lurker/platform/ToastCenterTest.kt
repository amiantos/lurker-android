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
        /** Always-notify sounds by default (plink). */
        var settings = Settings(registry = emptyMap(), values = emptyMap())
        val center = ToastCenter(settings = { settings }, play = { played += it }, isForeground = { this.foreground }, now = { now })
    }

    @Test
    fun theNewestSurfaceIsAskedFirstAndASurfaceThatCantBeSeenPassesItOn() {
        val rig = Rig()
        val list = Surface()
        val chat = Surface()
        rig.center.register(list)
        rig.center.register(chat)
        rig.center.post(notification())
        assertEquals(1, chat.taken.size)
        assertEquals(0, list.taken.size)
        chat.takes = false
        rig.center.post(notification())
        assertEquals(1, list.taken.size)
        // Taking a toast makes no sound of its own: the surface says when it went up.
        assertEquals(emptyList<String>(), rig.played)
    }

    @Test
    fun nothingInTheBackgroundOrForTheBufferOnScreenAndAnUnregisteredSurfaceIsNotAsked() {
        val rig = Rig(foreground = false)
        val chat = Surface()
        val unregister = rig.center.register(chat)
        rig.center.post(notification())
        assertEquals(0, chat.taken.size)
        rig.foreground = true
        // The line is arriving in plain view: no toast anywhere, whatever the case of the target.
        val showing = Surface(shows = BufferKey(1, "#A"))
        rig.center.register(showing)
        rig.center.post(notification(target = "#a"))
        assertEquals(0, chat.taken.size + showing.taken.size)
        rig.center.post(notification(target = "#b"))
        assertEquals(1, showing.taken.size)
        unregister()
        showing.takes = false
        rig.center.post(notification(target = "#b"))
        assertEquals(0, chat.taken.size)
    }

    @Test
    fun theSoundComesWithTheToastGoingUpOncePerSourceAndPerTheSettings() {
        val rig = Rig()
        rig.center.shown(notification())
        rig.now = Instant.EPOCH.plusSeconds(1)
        // A burst from one source: one sound.
        rig.center.shown(notification())
        // Another person in the same buffer is their own source, whatever their nick's case.
        rig.center.shown(notification(nick = "Alice"))
        rig.now = Instant.EPOCH.plusSeconds(2)
        rig.center.shown(notification(nick = "alice"))
        rig.now = Instant.EPOCH.plusSeconds(4)
        rig.center.shown(notification())
        assertEquals(listOf("plink", "plink", "plink"), rig.played)
        // A kind whose sound is off goes up in silence.
        rig.center.shown(notification(nick = "carol", kind = StatusNotification.Kind.Highlight))
        assertEquals(3, rig.played.size)
        // A sound switched on by settings plays its pick — the settings as they stand when it goes up.
        rig.settings = Settings(
            registry = emptyMap(),
            values = mapOf(
                "notifications.highlight.sound.enabled" to SettingValue.Bool(true),
                "notifications.highlight.sound.choice" to SettingValue.String("knock"),
            ),
        )
        rig.center.shown(notification(nick = "dave", kind = StatusNotification.Kind.Highlight))
        assertEquals("knock", rig.played.last())
    }
}
