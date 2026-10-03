// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.bufferinfo

import net.amiantos.lurker.ui.networks.NetworksListModel
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NetworkAction
import net.amiantos.lurkerkit.model.PrefixMode
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The buffer info page's sections — lurker-ios's `BufferInfoViewController`. */
class BufferInfoModelTest {

    private val dateTime: (Instant) -> String = { "T${it.epochSecond}" }
    private val longDate: (Instant) -> String = { "D${it.epochSecond}" }

    private val spec = ModeSpec(list = "beIq", always = "k", onSet = "l", flags = "imnst", prefix = listOf(PrefixMode("o", "@")), maxModes = 4, topicLen = 390)
    private val channel = Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel)

    private fun state(joined: Boolean = true, withSpec: Boolean = true, topic: String? = "Hello", ignores: IgnoreSet = IgnoreSet.empty) =
        ChatState(
            connection = SocketStatus.Connected,
            snapshotSinceOpen = true,
            networks = mapOf(1 to Network(id = 1, name = "Libera", state = ConnectionState.Connected, nick = "me", modeSpec = if (withSpec) spec else null)),
            buffers = mapOf(channel.key.id to channel.copy(joined = joined, topic = topic)),
            members = mapOf(channel.key.id to listOf(Member("me"), Member("alice", user = "a", host = "spam"), Member("bob"))),
            channelModes = mapOf(
                channel.key.id to ChannelModeState(modes = "nt", createdAt = Instant.ofEpochSecond(5), topicSetBy = "alice!a@host", topicSetAt = Instant.ofEpochSecond(9)),
            ),
            ignores = ignores,
        )

    private fun sections(state: ChatState, opened: Buffer = channel, error: String? = null) =
        BufferInfoModel.sections(BufferInfoInputs.of(state, opened), error, dateTime, longDate)

    @Test
    fun aJoinedChannelShowsTopicSettingsListsMembersAndNotifications() {
        val sections = sections(state())
        assertEquals(listOf("Topic", null, null, "Notifications"), sections.map { it.header })
        assertEquals(listOf(InfoRow.Topic("Hello", muted = false)), sections[0].rows)
        assertEquals("Set by alice · T9", sections[0].footer)
        assertEquals(
            listOf(
                InfoRow.ChannelSettings("+nt"),
                InfoRow.ModeList("b", "Bans"),
                InfoRow.ModeList("e", "Exceptions"),
                InfoRow.ModeList("I", "Invite Exceptions"),
                InfoRow.ModeList("q", "Quiets"),
            ),
            sections[1].rows,
        )
        assertEquals("Created D5", sections[1].footer)
        assertEquals(listOf(InfoRow.Members(3)), sections[2].rows)
        assertEquals(BufferInfoModel.notifications, sections[3])
    }

    @Test
    fun noListsOutOfTheChannelOrBeforeTheVocabulary() {
        assertEquals(listOf(InfoRow.ChannelSettings("+nt")), sections(state(joined = false))[1].rows)
        assertEquals(listOf(InfoRow.ChannelSettings("+nt")), sections(state(withSpec = false))[1].rows)
    }

    @Test
    fun noTopicIsSaidAndCreditsNobody() {
        val topic = sections(state(topic = null))[0]
        assertEquals(listOf(InfoRow.Topic("No topic set.", muted = true)), topic.rows)
        assertNull(topic.footer)
        assertEquals(InfoRow.Topic("No topic set.", muted = true), sections(state(topic = ""))[0].rows.single())
    }

    @Test
    fun theTopicIsTheLiveOneNotTheOpenedCopy() {
        val stale = channel.copy(topic = "Old")
        assertEquals(InfoRow.Topic("Hello", muted = false), sections(state(), opened = stale)[0].rows.single())
    }

    @Test
    fun theMemberCountIsWhoTheNicklistShows() {
        val ignores = IgnoreSet(global = listOf(IgnoreRule(mask = "*!*@spam", levels = listOf("ALL"))))
        assertEquals(InfoRow.Members(2), sections(state(ignores = ignores))[2].rows.single())
    }

    @Test
    fun aDmGetsWhoisAndNotifications() {
        val dm = Buffer(networkId = 1, target = "alice", kind = BufferKind.Dm)
        val sections = sections(state(), opened = dm)
        assertEquals(listOf(listOf<InfoRow>(InfoRow.Whois), BufferInfoModel.notifications.rows), sections.map { it.rows })
    }

    @Test
    fun aDccChatOffersAVerbOnlyOnceItsSessionIsKnown() {
        val chat = Buffer(networkId = 1, target = "=bob", kind = BufferKind.Dcc)
        val unknown = sections(state().copy(snapshotSinceOpen = false), opened = chat)
        assertEquals(listOf<InfoRow>(InfoRow.DccStatus("Checking…", StatusLight.Warn)), unknown[0].rows)
        assertEquals("DCC Chat", unknown[0].header)

        val dead = sections(state(), opened = chat, error = "No such nick")
        assertEquals(listOf(InfoRow.DccStatus("Not connected", StatusLight.Bad), InfoRow.DccVerb(DccChatAction.Start)), dead[0].rows)
        assertEquals("No such nick", dead[0].footer)
        assertEquals("Start New Chat", InfoRow.DccVerb(DccChatAction.Start).title)

        val live = sections(state().copy(dccChats = mapOf(1 to listOf("Bob"))), opened = chat)
        assertEquals(listOf(InfoRow.DccStatus("Connected", StatusLight.Good), InfoRow.DccVerb(DccChatAction.End)), live[0].rows)
        assertEquals("End Chat", InfoRow.DccVerb(DccChatAction.End).title)
        assertEquals(listOf<InfoRow>(InfoRow.Whois), live[1].rows)
        assertEquals("bob", BufferInfoModel.dccPeer(chat.key))
    }

    @Test
    fun aServerLogManagesItsConnectionWithoutDelete() {
        val server = Buffer(networkId = 1, target = Buffer.serverTarget(1), kind = BufferKind.Server)
        val section = sections(state(), opened = server).single()
        assertEquals("Connection", section.header)
        assertEquals(
            listOf(InfoRow.Connection("Connected", StatusLight.Good), InfoRow.NetworkVerb(NetworkAction.Disconnect), InfoRow.NetworkVerb(NetworkAction.Reconnect)),
            section.rows,
        )
        assertNull(section.footer)
        assertEquals("Libera", BufferInfoModel.title(server, BufferInfoInputs.of(state(), server)))
    }

    @Test
    fun aBlockedServerSaysWhyAndARefusalOutranksThat() {
        val server = Buffer(networkId = 1, target = Buffer.serverTarget(1), kind = BufferKind.Server)
        val blocked = state().let { it.copy(networks = it.networks.mapValues { (_, n) -> n.copy(blocked = true) }) }
        val section = sections(blocked, opened = server).single()
        // Disconnect survives a block; Reconnect doesn't.
        assertEquals(listOf(InfoRow.Connection("Connected", StatusLight.Good), InfoRow.NetworkVerb(NetworkAction.Disconnect)), section.rows)
        assertEquals(NetworksListModel.BLOCKED_EXPLANATION, section.footer)
        assertEquals("Your account is paused.", sections(blocked, opened = server, error = "Your account is paused.").single().footer)
    }

    @Test
    fun aServerLogWhoseNetworkIsntKnownAndTheSystemBufferHaveNothing() {
        val server = Buffer(networkId = 7, target = Buffer.serverTarget(7), kind = BufferKind.Server)
        assertTrue(sections(state(), opened = server).isEmpty())
        assertTrue(sections(state(), opened = Buffer.system).isEmpty())
        assertEquals("Lurker", BufferInfoModel.title(Buffer.system, BufferInfoInputs.of(state(), Buffer.system)))
    }

    @Test
    fun theSubjectMovesWithTheConnectionOrSession() {
        val server = Buffer(networkId = 1, target = Buffer.serverTarget(1), kind = BufferKind.Server)
        val before = BufferInfoModel.Subject.of(BufferInfoInputs.of(state(), server))
        val moved = state().let { it.copy(networks = it.networks.mapValues { (_, n) -> n.copy(state = ConnectionState.Disconnected) }) }
        assertNotEquals(before, BufferInfoModel.Subject.of(BufferInfoInputs.of(moved, server)))
        // A message arriving moves nothing the refusal is about.
        assertEquals(before, BufferInfoModel.Subject.of(BufferInfoInputs.of(state().copy(maxEventId = 3), server)))
    }
}
