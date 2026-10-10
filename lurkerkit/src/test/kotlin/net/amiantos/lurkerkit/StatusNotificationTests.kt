// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.StatusNotification
import net.amiantos.lurkerkit.store.ChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Which live lines and presence flips become an in-app notification, and of what kind. */
class StatusNotificationTests {
    private fun line(
        notify: Boolean = true,
        isSelf: Boolean = false,
        matched: Boolean = false,
        dm: Boolean = false,
        notifyAlways: Boolean = false,
        selfKicked: Boolean = false,
    ): Message =
        Message(
            id = 42, type = EventType.Message, nick = "bob", text = "\u0002hey\u0002 there", isSelf = isSelf,
            matched = matched, notify = notify, dm = dm, notifyAlways = notifyAlways, selfKicked = selfKicked,
        )

    private fun settings(values: Map<String, SettingValue> = emptyMap()): Settings =
        Settings(registry = emptyMap(), values = values)

    // MARK: - Lines

    @Test
    fun testKindFollowsTheWebsPriority() {
        val s = settings()
        fun kind(m: Message): StatusNotification.Kind? =
            StatusNotification.make(networkId = 1, target = "#a", message = m, settings = s)?.kind
        assertEquals(StatusNotification.Kind.Kicked, kind(line(matched = true, dm = true, notifyAlways = true, selfKicked = true)))
        assertEquals(StatusNotification.Kind.Dm, kind(line(matched = true, dm = true, notifyAlways = true)))
        assertEquals(StatusNotification.Kind.Highlight, kind(line(matched = true, notifyAlways = true)))
        assertEquals(StatusNotification.Kind.AlwaysNotify, kind(line(notifyAlways = true)))
    }

    @Test
    fun testNothingWithoutTheServersVerdictOrForOurOwnLine() {
        val s = settings()
        assertNull(StatusNotification.make(networkId = 1, target = "#a", message = line(notify = false, matched = true), settings = s))
        assertNull(StatusNotification.make(networkId = 1, target = "#a", message = line(isSelf = true, matched = true), settings = s))
        assertNull(StatusNotification.make(networkId = 1, target = "#a", message = line(notify = true), settings = s))
        assertNull(StatusNotification.make(networkId = null, target = "#a", message = line(matched = true), settings = s))
    }

    @Test
    fun testAKindSwitchedOffSaysNothing() {
        val off = settings(mapOf("notifications.highlight.enabled" to SettingValue.Bool(false)))
        assertNull(StatusNotification.make(networkId = 1, target = "#a", message = line(matched = true), settings = off))
        assertEquals(
            StatusNotification.Kind.Dm,
            StatusNotification.make(networkId = 1, target = "bob", message = line(dm = true), settings = off)?.kind,
        )
    }

    @Test
    fun testEachKindHasItsOwnSwitch() {
        fun off(kind: String): Settings = settings(mapOf("notifications.$kind.enabled" to SettingValue.Bool(false)))
        assertNull(StatusNotification.make(networkId = 1, target = "#a", message = line(selfKicked = true), settings = off("kicked")))
        assertNull(StatusNotification.make(networkId = 1, target = "bob", message = line(dm = true), settings = off("dm")))
        assertNull(StatusNotification.make(networkId = 1, target = "#a", message = line(notifyAlways = true), settings = off("always_notify")))
        // Each switch is its own: turning DMs off leaves always-notify alone.
        assertEquals(
            StatusNotification.Kind.AlwaysNotify,
            StatusNotification.make(networkId = 1, target = "#a", message = line(notifyAlways = true), settings = off("dm"))?.kind,
        )
    }

    /** The flags as the server's `decorateMessage` spreads them onto a live frame. */
    @Test
    fun testTheServersFlagsAreReadOffTheFrame() {
        val flagged = FrameParser.parseWs(
            """{"kind":"irc","id":7,"networkId":1,"target":"#lurker","type":"kick","nick":"op","text":"bye","self":false,"notify":true,"dm":true,"notifyAlways":true,"selfKicked":true}""",
        )
        if (flagged !is ServerFrame.Live) fail("expected live")
        val message = flagged.message
        assertTrue(message.notify)
        assertTrue(message.dm)
        assertTrue(message.notifyAlways)
        assertTrue(message.selfKicked)
        val unflagged = FrameParser.parseWs(
            """{"kind":"irc","id":8,"networkId":1,"target":"#lurker","type":"message","nick":"op","text":"hi","self":false}""",
        )
        if (unflagged !is ServerFrame.Live) fail("expected live")
        val plain = unflagged.message
        assertFalse(plain.notify || plain.dm || plain.notifyAlways || plain.selfKicked)
    }

    /** A detached buffer keeps live lines in `heldLive`; a repeat of one is still a repeat. */
    @Test
    fun testAHeldLineCountsAsHeld() {
        var state = ChatState()
        val key = BufferKey(networkId = 1, target = "#a").id
        val held = line(matched = true)
        assertFalse(state.alreadyHolds(held, key))
        state = state.copy(heldLive = mapOf(key to listOf(held)))
        assertTrue(state.alreadyHolds(held, key))
        state = state.copy(heldLive = emptyMap(), messages = mapOf(key to listOf(held)))
        assertTrue(state.alreadyHolds(held, key))
        // Ephemeral lines are never repeats.
        val ephemeral = Message(id = 0, type = EventType.Message, nick = "bob", text = "x")
        state = state.copy(messages = mapOf(key to listOf(ephemeral)))
        assertFalse(state.alreadyHolds(ephemeral, key))
    }

    @Test
    fun testTextIsStrippedOfFormatting() {
        val n = StatusNotification.make(networkId = 1, target = "#a", message = line(matched = true), settings = settings())
        assertEquals("hey there", n?.text)
        assertEquals(42L, n?.messageId)
        assertEquals(BufferKey(networkId = 1, target = "#a"), n?.key)
    }

    // MARK: - Sounds

    @Test
    fun testEachKindsSoundFollowsTheRegistryDefaults() {
        fun sound(m: Message, s: Settings = Settings(registry = emptyMap(), values = emptyMap())): String? =
            StatusNotification.make(networkId = 1, target = "#a", message = m, settings = s)?.sound(s)
        // Off by default for the everyday kinds, on for the ones that are rarer and louder.
        assertNull(sound(line(matched = true)))
        assertNull(sound(line(dm = true)))
        assertEquals("plink", sound(line(notifyAlways = true)))
        assertEquals("beep", sound(line(selfKicked = true)))
        // Switched on, each has its own default sound.
        val on = settings(
            mapOf(
                "notifications.highlight.sound.enabled" to SettingValue.Bool(true),
                "notifications.dm.sound.enabled" to SettingValue.Bool(true),
            ),
        )
        assertEquals("ping", sound(line(matched = true), on))
        assertEquals("chime", sound(line(dm = true), on))
    }

    @Test
    fun testTheChosenSoundAndVolume() {
        val n = assertNotNull(StatusNotification.make(networkId = 1, target = "#a", message = line(matched = true), settings = settings()))
        fun sound(values: Map<String, SettingValue>): String? =
            n.sound(settings(mapOf("notifications.highlight.sound.enabled" to SettingValue.Bool(true)) + values))
        assertEquals("knock", sound(mapOf("notifications.highlight.sound.choice" to SettingValue.String("knock"))))
        // A choice this build doesn't bundle falls back to the kind's default rather than silence.
        assertEquals("ping", sound(mapOf("notifications.highlight.sound.choice" to SettingValue.String("gong"))))
        assertNull(sound(mapOf("notifications.highlight.sound.volume" to SettingValue.Int(0))))
        assertNull(sound(mapOf("notifications.highlight.sound.enabled" to SettingValue.Bool(false))))
    }

    @Test
    fun testAFriendComingOnlineHasItsOwnSound() {
        val state = ChatState().copy(
            peerPresence = mapOf(1 to mapOf("bob" to PresenceState.Offline)),
            favorites = listOf(FavoriteEntry(networkId = 1, target = "Bob", bufferId = 9)),
        )
        val n = StatusNotification.cameOnline(ServerFrame.PeerPresence(networkId = 1, nick = "Bob", state = PresenceState.Online), before = state)
        assertNull(n?.sound(state.settings))
        assertEquals("knock", n?.sound(settings(mapOf("notifications.friend_online.sound.enabled" to SettingValue.Bool(true)))))
    }

    // MARK: - Came online

    private fun state(was: PresenceState?, favorite: Boolean = true, enabled: Boolean? = null): ChatState {
        var state = ChatState()
        if (was != null) state = state.copy(peerPresence = mapOf(1 to mapOf("bob" to was)))
        if (favorite) state = state.copy(favorites = listOf(FavoriteEntry(networkId = 1, target = "Bob", bufferId = 9)))
        if (enabled != null) state = state.copy(settings = settings(mapOf("notifications.friend_online.enabled" to SettingValue.Bool(enabled))))
        return state
    }

    private val online = ServerFrame.PeerPresence(networkId = 1, nick = "Bob", state = PresenceState.Online)

    @Test
    fun testAFriendGoingFromOfflineToOnlineIsNews() {
        val n = StatusNotification.cameOnline(online, before = state(was = PresenceState.Offline))
        assertEquals(StatusNotification.Kind.FriendOnline, n?.kind)
        assertEquals("Bob", n?.nick)
        assertEquals(BufferKey(networkId = 1, target = "Bob"), n?.key)
        assertEquals(0L, n?.messageId)
    }

    @Test
    fun testOnlyAWitnessedFlipCounts() {
        // MONITOR's seed, or a nick freshly added to the watch: the current state, not an arrival.
        assertNull(StatusNotification.cameOnline(online, before = state(was = null)))
        // Back from away is not coming online.
        assertNull(StatusNotification.cameOnline(online, before = state(was = PresenceState.Away)))
        assertNull(StatusNotification.cameOnline(online, before = state(was = PresenceState.Online)))
        assertNull(
            StatusNotification.cameOnline(
                ServerFrame.PeerPresence(networkId = 1, nick = "Bob", state = PresenceState.Away), before = state(was = PresenceState.Offline),
            ),
        )
    }

    @Test
    fun testOnlyFriendsAndOnlyWhenSwitchedOn() {
        assertNull(StatusNotification.cameOnline(online, before = state(was = PresenceState.Offline, favorite = false)))
        assertNull(StatusNotification.cameOnline(online, before = state(was = PresenceState.Offline, enabled = false)))
    }
}

/**
 * The came-online check reads the presence the frame is about to replace, so it has to run
 * before the store applies it — through `handle`, where that order lives.
 */
class CameOnlineHandleTests {
    @Test
    fun testHandleSeesTheFlipAndOnlyTheFlip() {
        val model = testViewModel()
        model.handle(ServerFrame.SocketOpen)
        model.handle(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        model.handle(ServerFrame.FavoritesChanged(listOf(FavoriteEntry(networkId = 1, target = "bob", bufferId = 9))))
        val notified = mutableListOf<String>()
        model.onNotify = { notified.add("${it.kind.rawValue} ${it.nick ?: ""}") }

        model.handle(ServerFrame.PeerPresence(networkId = 1, nick = "bob", state = PresenceState.Online)) // the seed
        model.handle(ServerFrame.PeerPresence(networkId = 1, nick = "bob", state = PresenceState.Offline))
        model.handle(ServerFrame.PeerPresence(networkId = 1, nick = "bob", state = PresenceState.Online))
        model.handle(ServerFrame.PeerPresence(networkId = 1, nick = "bob", state = PresenceState.Online)) // a repeat
        assertEquals(listOf("friend_online bob"), notified)
    }

    /** A line the store already holds — a backlog/live overlap — is dropped, and so is its alert. */
    @Test
    fun testARepeatedLineAlertsOnce() {
        val model = testViewModel()
        model.handle(ServerFrame.SocketOpen)
        model.handle(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        val notified = mutableListOf<Long>()
        model.onNotify = { notified.add(it.messageId) }
        val line = Message(id = 77, type = EventType.Message, nick = "bob", text = "me: hi", matched = true, notify = true)
        model.handle(ServerFrame.Live(networkId = 1, target = "#lurker", message = line))
        model.handle(ServerFrame.Live(networkId = 1, target = "#lurker", message = line))
        assertEquals(listOf(77L), notified)
    }
}
