// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.channel

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelModeDrafts
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.OutgoingModeChange
import net.amiantos.lurkerkit.model.PrefixMode
import net.amiantos.lurkerkit.store.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The channel settings page — lurker-ios's `ChannelSettingsViewController`, around the kit's form. */
class ChannelSettingsModelTest {

    private val key = BufferKey(networkId = 1, target = "#lurker")
    private val spec = ModeSpec(
        list = "beIq",
        always = "k",
        onSet = "l",
        flags = "imntCR",
        prefix = listOf(PrefixMode("o", "@"), PrefixMode("h", "%"), PrefixMode("v", "+")),
        maxModes = 4,
        topicLen = 10,
    )
    private val stamp: (Instant) -> String = { "T${it.epochSecond}" }

    private fun slice(
        mine: List<String> = listOf("o"),
        modes: String = "nt",
        params: Map<String, String> = emptyMap(),
        topic: String? = "Hello",
        joined: Boolean = true,
        withSpec: Boolean = true,
    ): ChannelSlice {
        val state = ChatState(
            networks = mapOf(1 to Network(id = 1, name = "L", state = ConnectionState.Connected, nick = "me", modeSpec = if (withSpec) spec else null)),
            buffers = mapOf(key.id to Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, joined = joined, topic = topic)),
            members = mapOf(key.id to listOf(Member("me", modes = mine))),
            channelModes = mapOf(key.id to ChannelModeState(modes = modes, params = params, topicSetBy = "alice!a@h", topicSetAt = Instant.ofEpochSecond(3))),
        )
        return ChannelSlice.of(state, key)
    }

    private fun modeRow(vararg changes: ModeChange): Message =
        Message(id = 1, type = EventType.Mode, nick = "op", text = null, modes = changes.toList())

    private fun build(slice: ChannelSlice, drafts: ChannelModeDrafts = ChannelModeDrafts(), errors: List<String> = emptyList(), key: String? = null) =
        ChannelSettingsModel.build(slice, ChannelSettingsModel.live(slice.modes, key), drafts, errors, stamp)

    // MARK: - The key

    @Test
    fun theNewestLiveKeyChangeOutranksTheConfigsCopy() {
        assertEquals("cfg", ChannelSettingsModel.storedKey(emptyList(), "cfg"))
        val set = modeRow(ModeChange("+k", "live", ModeChangeKind.Chan))
        assertEquals("live", ChannelSettingsModel.storedKey(listOf(set), "cfg"))
        // A -k means there's none: the config's memory doesn't bring it back.
        assertNull(ChannelSettingsModel.storedKey(listOf(set, modeRow(ModeChange("-k", null, ModeChangeKind.Chan))), "cfg"))
        // A hidden +k suppresses both.
        assertNull(ChannelSettingsModel.storedKey(listOf(modeRow(ModeChange("+k", "*", ModeChangeKind.Chan))), "cfg"))
    }

    @Test
    fun theKeyJoinsTheLiveStateOnlyWhileTheChannelIsKeyed() {
        assertEquals("pw", ChannelSettingsModel.live(ChannelModeState(modes = "ntk"), "pw").params["k"])
        // ⚠ Switching +k back on must start empty, not quietly re-send an old one.
        assertNull(ChannelSettingsModel.live(ChannelModeState(modes = "nt"), "pw").params["k"])
        assertEquals("", ChannelSettingsModel.live(null, null).modes)
    }

    @Test
    fun theConfigIsAskedOncePerKeyedStretchAndALateAnswerIsDropped() {
        val lookup = KeyLookup()
        assertNull(lookup.onModes("nt"))
        val first = lookup.onModes("ntk")!!
        assertNull(lookup.onModes("ntk"))
        assertTrue(lookup.answer(first, "pw"))
        assertEquals("pw", lookup.configKey)
        // The channel loses +k: the answer is forgotten, and a read still out can't put it back.
        assertNull(lookup.onModes("nt"))
        assertNull(lookup.configKey)
        val third = lookup.onModes("ntk")!!
        assertFalse(lookup.answer(first, "stale"))
        // A resync asks again, and the read before it no longer answers.
        lookup.resynced()
        assertFalse(lookup.answer(third, "stale"))
        val fourth = lookup.onModes("ntk")!!
        assertTrue(lookup.answer(fourth, "fresh"))
        assertEquals("fresh", lookup.configKey)
    }

    // MARK: - What Save sends

    @Test
    fun anOpsEditsBecomeModeChanges() {
        val s = slice()
        val live = ChannelSettingsModel.live(s.modes, null)
        val drafts = ChannelModeDrafts().setOn("m", true, live).setOn("t", false, live)
        val pending = ChannelSettingsModel.pending(s.access, live, drafts, "Hello")
        assertEquals(listOf(OutgoingModeChange('+', "m"), OutgoingModeChange('-', "t")), pending.changes)
        assertNull(pending.topic)
        assertNull(pending.error)
        assertTrue(ChannelSettingsModel.saveEnabled(saving = false, pending))
        assertFalse(ChannelSettingsModel.saveEnabled(saving = true, pending))
    }

    @Test
    fun aProblemEnablesSaveSoTheTapCanExplainIt() {
        val s = slice()
        val live = ChannelSettingsModel.live(s.modes, null)
        val pending = ChannelSettingsModel.pending(s.access, live, ChannelModeDrafts().setOn("k", true, live), "Hello")
        assertEquals("Enter a key.", pending.error)
        assertTrue(ChannelSettingsModel.saveEnabled(saving = false, pending))
        assertFalse(ChannelSettingsModel.saveEnabled(saving = false, ChannelSettingsModel.pending(s.access, live, ChannelModeDrafts(), "Hello")))
    }

    @Test
    fun aTopicOverTheByteLimitIsRefusedBeforeItGoes() {
        val s = slice()
        val live = ChannelSettingsModel.live(s.modes, null)
        val pending = ChannelSettingsModel.pending(s.access, live, ChannelModeDrafts().setTopic("ééééééé"), "Hello")
        assertEquals("ééééééé", pending.topic)
        assertEquals("The topic is over the network's 10-byte limit.", pending.error)
    }

    @Test
    fun aVoicedMemberSendsNoModesAndOnlyATopicWithoutPlusT() {
        val voiced = slice(mine = listOf("v"))
        val live = ChannelSettingsModel.live(voiced.modes, null)
        val drafts = ChannelModeDrafts().setOn("m", true, live).setTopic("New")
        val pending = ChannelSettingsModel.pending(voiced.access, live, drafts, "Hello")
        assertTrue(pending.changes.isEmpty())
        assertNull(pending.topic)
        assertFalse(ChannelSettingsModel.showsSave(voiced.access))
        val open = slice(mine = listOf("v"), modes = "n")
        assertEquals("New", ChannelSettingsModel.pending(open.access, ChannelSettingsModel.live(open.modes, null), drafts, "Hello").topic)
        assertTrue(ChannelSettingsModel.showsSave(open.access))
    }

    // MARK: - The page

    @Test
    fun anOpSeesEveryModeNamedOnesFirstWithTheTopicField() {
        val sections = build(slice(modes = "ntl", params = mapOf("l" to "50")))
        assertEquals(listOf("topic", "modes", "other"), sections.map { it.id })
        assertEquals(SettingsItem.TopicField("Hello"), sections[0].items.single())
        assertEquals(SettingsFooter("Set by alice · T3 · 5 / 10 bytes"), sections[0].footer)
        val named = sections[1].items
        assertEquals(
            listOf("toggle:i", "toggle:m", "toggle:n", "toggle:t", "toggle:k", "toggle:l", "value:l"),
            named.map { it.id },
        )
        assertEquals(SettingsItem.Value("l", "Limit", "50", isKey = false, placeholder = "Required", enabled = true), named.last())
        assertEquals(listOf("toggle:C", "toggle:R"), sections[2].items.map { it.id })
        assertEquals(SettingsItem.Toggle("C", "+C", on = false, enabled = true), sections[2].items.first())
        assertNull(sections[2].footer)
    }

    @Test
    fun aKeyedChannelWhoseKeyIsUnknownSaysOneIsSet() {
        val value = build(slice(modes = "ntk")).flatMap { it.items }.single { it.id == "value:k" } as SettingsItem.Value
        assertEquals("", value.value)
        assertEquals("Key is set", value.placeholder)
        assertTrue(value.isKey)
        val known = build(slice(modes = "ntk"), key = "pw").flatMap { it.items }.single { it.id == "value:k" } as SettingsItem.Value
        assertEquals("pw", known.value)
    }

    @Test
    fun someoneWhoCantEditSeesOnlyWhatsSetAndWhy() {
        val sections = build(slice(mine = emptyList(), modes = "ntC"))
        assertEquals(SettingsItem.TopicText("Hello", muted = false), sections[0].items.single())
        assertEquals(listOf("toggle:n", "toggle:t"), sections[1].items.map { it.id })
        assertTrue(sections[1].items.all { (it as SettingsItem.Toggle).enabled.not() })
        assertNull(sections[1].footer)
        assertEquals(SettingsFooter("Only channel operators can change modes."), sections[2].footer)
    }

    @Test
    fun theTopicSaysWhatItCanWhenItCantBeEdited() {
        assertEquals(SettingsItem.TopicText("No topic set.", muted = true), build(slice(mine = emptyList(), topic = null))[0].items.single())
        assertEquals(
            SettingsItem.TopicText("Join the channel to see its topic.", muted = true),
            build(slice(topic = null, joined = false))[0].items.single(),
        )
    }

    @Test
    fun modesWaitForTheVocabularyAndForTheChannel() {
        assertEquals(SettingsItem.Notice("spec", "Modes show once the network is connected."), build(slice(withSpec = false))[1].items.single())
        assertEquals(SettingsItem.Notice("parted", "Join the channel to see its modes."), build(slice(joined = false))[1].items.single())
        assertEquals(SettingsItem.Notice("none", "No modes set."), build(slice(mine = emptyList(), modes = ""))[1].items.single())
    }

    @Test
    fun refusalsLandRedUnderTheLastSectionAfterItsNote() {
        val sections = build(slice(mine = emptyList(), modes = "ntC"), errors = listOf("You're not a channel operator"))
        assertEquals(
            SettingsFooter("Only channel operators can change modes.\nYou're not a channel operator", isError = true),
            sections.last().footer,
        )
        assertNull(sections.first().footer?.takeIf { it.isError })
    }

    @Test
    fun aDraftShowsInPlaceOfTheLiveValue() {
        val s = slice()
        val live = ChannelSettingsModel.live(s.modes, null)
        val drafts = ChannelModeDrafts().setOn("l", true, live).setValue("l", "25", live).setTopic("Typed")
        val sections = ChannelSettingsModel.build(s, live, drafts, emptyList(), stamp)
        assertEquals(SettingsItem.TopicField("Typed"), sections[0].items.single())
        assertEquals("25", (sections[1].items.single { it.id == "value:l" } as SettingsItem.Value).value)
    }
}
