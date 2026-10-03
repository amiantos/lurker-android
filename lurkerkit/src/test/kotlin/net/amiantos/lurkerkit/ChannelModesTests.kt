// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.client.ChannelSnapshot
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.TopicMeta
import net.amiantos.lurkerkit.client.VerbReply
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ChannelModeDrafts
import net.amiantos.lurkerkit.model.ChannelModeForm
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ChannelRank
import net.amiantos.lurkerkit.model.ChannelRefusals
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.channelAccess
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.ModeListEntry
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.OutgoingModeChange
import net.amiantos.lurkerkit.model.PrefixMode
import net.amiantos.lurkerkit.support.Result
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Channel controls (lurker-ios#187, the iOS half of lurker#727): the mode vocabulary and
 * channel state off the wire, rank gating, the settings form's diff, its drafts, and how an
 * open list stays current.
 */
class ChannelModesTests {

    private val spec = ModeSpec(
        list = "beIq", always = "k", onSet = "lj", flags = "imnstC",
        prefix = listOf(PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+")),
        maxModes = 4, topicLen = 390,
    )

    // MARK: - Wire

    @Test
    fun testSnapshotCarriesTheSpecAndEachChannelsModeState() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"snapshot","networks":[{"networkId":1,"state":"connected","nick":"me",
              "modeSpec":{"list":"beI","always":"k","onSet":"l","flags":"imnst",
                "prefix":[{"mode":"o","symbol":"@"},{"mode":"vv","symbol":"+"},{"mode":"v","symbol":"+"}],
                "maxModes":null,"topicLen":307},
              "channels":[{"name":"#c","topic":"hi","topicSetBy":"alice!a@h","topicSetAt":"2026-09-01T10:00:00.000Z",
                "modes":"ntkl","modeParams":{"l":"50"},"createdAt":"2020-01-01T00:00:00.000Z","members":[]}]}]}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        val network = frame.networks.firstOrNull() ?: fail("expected snapshot, got $frame")
        val parsed = network.modeSpec
        assertEquals("beI", parsed?.list)
        assertEquals(listOf("o", "v"), parsed?.prefix?.map { it.mode }, "a prefix entry that isn't one letter is dropped")
        assertNull(parsed?.maxModes, "null is no limit, not a default")
        assertEquals(307, parsed?.topicLen)
        val channel = network.channels.firstOrNull()?.modeState
        assertEquals("ntkl", channel?.modes)
        assertEquals(mapOf("l" to "50"), channel?.params)
        assertEquals("alice!a@h", channel?.topicSetBy)
        assertNotNull(channel?.topicSetAt)
        assertNotNull(channel?.createdAt)
    }

    /** ⚠⚠ Null until the burst ends — and null must stay "unknown", never become the defaults. */
    @Test
    fun testANullSpecIsUnknown() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":1,"state":"connected","nick":"me","modeSpec":null,"channels":[]}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot")
        assertNull(frame.networks.firstOrNull()?.modeSpec)
    }

    /** A network-scoped frame on a `:server:` carrier — below the target guard it would be a line. */
    @Test
    fun testModeSpecFrame() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"irc","networkId":2,"target":":server:2","type":"mode-spec",
             "modeSpec":{"list":"beIq","always":"k","onSet":"l","flags":"nt","prefix":[{"mode":"o","symbol":"@"}],"maxModes":4,"topicLen":null}}
            """.trimIndent(),
        )
        assertEquals(
            ServerFrame.ModeSpec(
                networkId = 2,
                spec = ModeSpec(
                    list = "beIq", always = "k", onSet = "l", flags = "nt",
                    prefix = listOf(PrefixMode(mode = "o", symbol = "@")), maxModes = 4, topicLen = null,
                ),
            ),
            frame,
        )
    }

    @Test
    fun testChannelModesFrameNeverBecomesALine() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#c","type":"channel-modes","modes":"ntl","modeParams":{"l":"50"},"createdAt":null}""",
        )
        assertEquals(
            ServerFrame.ChannelModes(networkId = 1, target = "#c", modes = "ntl", params = mapOf("l" to "50"), createdAt = null),
            frame,
        )
    }

    /**
     * The setter's KEY is what says the server stated it: `null` replaces a stale setter, an
     * absent key leaves it alone.
     */
    @Test
    fun testChannelTopicMetaFollowsKeyPresence() {
        val stated = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#c","type":"channel-topic","topic":"t","setBy":"bob","setAt":null}""",
        )
        if (stated !is ServerFrame.ChannelTopic) fail("expected channelTopic")
        assertEquals(TopicMeta(setBy = "bob", setAt = null), stated.meta)

        val silent = FrameParser.parseWs("""{"kind":"irc","networkId":1,"target":"#c","type":"channel-topic","topic":"t"}""")
        if (silent !is ServerFrame.ChannelTopic) fail("expected channelTopic")
        assertNull(silent.meta)
    }

    @Test
    fun testVerbReplyCarriesItsData() {
        val list = FrameParser.parseVerbReply(
            """
            {"kind":"send-result","clientId":"ios-verb-3","ok":true,"data":{"ok":true,"channel":"#c","letter":"b",
             "entries":[{"mask":"*!*@bad","setBy":"op","setAt":"2026-09-01T10:00:00.000Z"},{"mask":"x!*@*","setBy":null,"setAt":null},{"mask":""}]}}
            """.trimIndent(),
        )
        assertEquals("ios-verb-3", list?.clientId)
        assertEquals(true, list?.reply?.ok)
        assertEquals(listOf("*!*@bad", "x!*@*"), list?.reply?.entries?.map { it.mask }, "an entry with no mask names nothing")
        assertEquals("op", list?.reply?.entries?.firstOrNull()?.setBy)
        assertNotNull(list?.reply?.entries?.firstOrNull()?.setAt)

        val refused = FrameParser.parseVerbReply(
            """
            {"kind":"send-result","clientId":"ios-verb-4","ok":false,"error":"refused","data":{"ok":false,"error":"refused","numeric":"482","text":"You're not a channel operator"}}
            """.trimIndent(),
        )
        assertEquals("Only channel operators can see this list.", ChatViewModel.listError(refused!!.reply))
        assertNull(FrameParser.parseVerbReply("""{"kind":"send-result","ok":true}"""), "no clientId, nobody waiting")
    }

    @Test
    fun testVerbErrorsAreWorded() {
        assertNull(ChatViewModel.saveError(VerbReply(ok = true, error = null)))
        assertEquals("Not connected.", ChatViewModel.saveError(VerbReply.notSent))
        assertEquals("The server didn't answer.", ChatViewModel.saveError(VerbReply.noAnswer))
        assertEquals("Couldn't save (unknown-mode:x).", ChatViewModel.saveError(VerbReply(ok = false, error = "unknown-mode:x")))
        assertEquals(
            "The server refused: No such channel",
            ChatViewModel.listError(VerbReply(ok = false, error = "refused", numeric = "403", text = "No such channel")),
        )
        assertEquals("The server didn't answer.", ChatViewModel.listError(VerbReply.noAnswer))
        assertEquals("The server didn't answer.", ChatViewModel.listError(VerbReply(ok = false, error = "no-reply")))
        assertEquals(
            "This account is paused, so nothing can be fetched.",
            ChatViewModel.listError(VerbReply(ok = false, error = "account-paused")), "a refusal is not silence",
        )
        assertEquals(
            "Couldn't load the list (unsupported-list-mode).",
            ChatViewModel.listError(VerbReply(ok = false, error = "unsupported-list-mode")),
        )
    }

    @Test
    fun testNetworkConfigReadsChannelKeys() {
        val configs = FrameParser.parseNetworkConfigs(
            """
            {"networks":[{"id":1,"name":"n","host":"h","port":6697,"nick":"me",
              "channels":[{"name":"#Secret","key":"hunter2"},{"name":"#open","key":null},{"name":"","key":"x"}]}]}
            """.trimIndent(),
        )
        val config = configs?.firstOrNull()
        assertEquals("hunter2", config?.key(channel = "#secret"), "looked up case-insensitively")
        assertNull(config?.key(channel = "#open"))
        assertEquals(1, config?.channelKeys?.size)
    }

    // MARK: - Live lines

    /**
     * The settings screens patch lists and read refusals off these — including for a detached
     * buffer, which holds live lines out of its log. So they come off the frame, not the store.
     *
     * Port note: the collector runs on an unconfined test dispatcher, so it is subscribed before
     * the first frame, as Combine's `sink` is the moment it is called.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // `UnconfinedTestDispatcher`, `runCurrent`
    @Test
    fun testLiveLinesAndResyncsReachChannelEvents() = runTest {
        val model = testViewModel()
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.channelEvents.collect { event ->
                when (event) {
                    is ChatViewModel.ChannelEvent.Line -> seen.add("${event.key.id} ${event.message.type.rawValue}")
                    ChatViewModel.ChannelEvent.Resynced -> seen.add("resynced")
                }
            }
        }
        model.handle(
            ServerFrame.Live(
                networkId = 1, target = "#C",
                message = Message(
                    id = 7, type = EventType.Mode, nick = "op", text = "+b x",
                    modes = listOf(ModeChange(mode = "+b", param = "x", kind = ModeChangeKind.List)),
                ),
            ),
        )
        model.handle(ServerFrame.SocketOpen)
        model.handle(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )
        runCurrent()
        assertEquals(listOf("1::#c mode", "resynced"), seen, "after the snapshot, not the socket opening")
    }

    // MARK: - Store

    private fun storeWithChannel(modes: String = "nt", selfModes: List<String> = listOf("o")): LurkerStore {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1, state = ConnectionState.Connected, nick = "me",
                        channels = listOf(
                            ChannelSnapshot(
                                name = "#c", topic = "hi",
                                members = listOf(Member(nick = "Me", modes = selfModes), Member(nick = "bob")),
                                modeState = ChannelModeState(modes = modes, topicSetBy = "alice"),
                            ),
                        ),
                        modeSpec = spec,
                    ),
                ),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )
        return store
    }

    private val key = BufferKey(networkId = 1, target = "#c")

    @Test
    fun testSnapshotSeedsSpecAndChannelState() {
        val store = storeWithChannel()
        assertEquals(spec, store.state.networks[1]?.modeSpec)
        assertEquals("nt", store.state.channelModes[key.id]?.modes)
        assertEquals("alice", store.state.channelModes[key.id]?.topicSetBy)
    }

    @Test
    fun testChannelModesFrameReplacesModesButKeepsTheTopicSetter() {
        val store = storeWithChannel()
        store.apply(
            ServerFrame.ChannelModes(networkId = 1, target = "#C", modes = "ntl", params = mapOf("l" to "9"), createdAt = null),
        )
        assertEquals("ntl", store.state.channelModes[key.id]?.modes)
        assertEquals(mapOf("l" to "9"), store.state.channelModes[key.id]?.params)
        assertEquals("alice", store.state.channelModes[key.id]?.topicSetBy)

        store.apply(
            ServerFrame.ChannelModes(networkId = 1, target = "#elsewhere", modes = "n", params = emptyMap(), createdAt = null),
        )
        assertNull(store.state.channelModes[BufferKey(networkId = 1, target = "#elsewhere").id], "never materializes")
    }

    @Test
    fun testATopicLineNamesItsSetter() {
        val store = storeWithChannel()
        val `when` = Instant.ofEpochSecond(1_000)
        store.apply(
            ServerFrame.Live(
                networkId = 1, target = "#c",
                message = Message(id = 5, type = EventType.Topic, nick = "carol", text = "new", date = `when`),
            ),
        )
        assertEquals("new", store.state.buffers[key.id]?.topic)
        assertEquals("carol", store.state.channelModes[key.id]?.topicSetBy)
        assertEquals(`when`, store.state.channelModes[key.id]?.topicSetAt)
    }

    @Test
    fun testChannelTopicMetaReplacesOnlyWhenStated() {
        val store = storeWithChannel()
        store.apply(ServerFrame.ChannelTopic(networkId = 1, target = "#c", topic = "x"))
        assertEquals("alice", store.state.channelModes[key.id]?.topicSetBy, "no meta: the held setter stands")
        store.apply(
            ServerFrame.ChannelTopic(networkId = 1, target = "#c", topic = "x", meta = TopicMeta(setBy = null, setAt = null)),
        )
        assertNull(store.state.channelModes[key.id]?.topicSetBy)
    }

    @Test
    fun testSpecIsForgottenWhenTheLinkDropsAndRestatedByTheFrame() {
        val store = storeWithChannel()
        store.apply(ServerFrame.NetworkState(networkId = 1, state = ConnectionState.Reconnecting, nick = null))
        assertNull(store.state.networks[1]?.modeSpec)
        store.apply(ServerFrame.NetworkState(networkId = 1, state = ConnectionState.Connected, nick = null))
        assertNull(store.state.networks[1]?.modeSpec, "unknown until the new burst says")
        store.apply(ServerFrame.ModeSpec(networkId = 1, spec = spec))
        assertEquals(spec, store.state.networks[1]?.modeSpec)
        store.apply(ServerFrame.ModeSpec(networkId = 9, spec = spec))
        assertNull(store.state.networks[9], "never materializes a network")
    }

    /**
     * A dropped socket can't hear the `state` frame that would retire the vocabulary, so the
     * drop itself does — until the next snapshot restates it.
     */
    @Test
    fun testSpecIsForgottenWhenOurSocketDrops() {
        val store = storeWithChannel()
        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertNull(store.state.networks[1]?.modeSpec)
    }

    @Test
    fun testChannelStateFollowsARenameAndGoesWithAClose() {
        val store = storeWithChannel()
        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "#c", to = "#d", bufferId = null, merged = false, mergedFromBufferId = null,
            ),
        )
        assertNull(store.state.channelModes[key.id])
        assertEquals("nt", store.state.channelModes[BufferKey(networkId = 1, target = "#d").id]?.modes)
        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#d"))
        assertNull(store.state.channelModes[BufferKey(networkId = 1, target = "#d").id])
    }

    // MARK: - Access

    @Test
    fun testAnOpEditsEverything() {
        val access = storeWithChannel().state.channelAccess(key)
        assertTrue(access.joined)
        assertTrue(access.canEditModes, "our own row found case-insensitively")
        assertTrue(access.canSetTopic)
    }

    @Test
    fun testPlusTGatesTheTopicOnHalfopAndAVoiceEditsNoModes() {
        val voiced = storeWithChannel(modes = "nt", selfModes = listOf("v")).state.channelAccess(key)
        assertFalse(voiced.canEditModes)
        assertFalse(voiced.canSetTopic, "+t, and this network has no halfop: the gate rounds UP to op")
        val open = storeWithChannel(modes = "n", selfModes = emptyList()).state.channelAccess(key)
        assertTrue(open.canSetTopic, "-t: anyone in the channel")
    }

    /**
     * ⚠ No rank gate opens on a guessed ladder: until the network's PREFIX arrives, a +t topic
     * and the modes are read-only — even for someone holding `o`. A -t topic needs no rank.
     */
    @Test
    fun testAnUnknownSpecOpensNoRankGate() {
        val store = storeWithChannel(modes = "nt", selfModes = listOf("o"))
        store.apply(ServerFrame.ModeSpec(networkId = 1, spec = null))
        val keyed = store.state.channelAccess(key)
        assertNull(keyed.spec)
        assertFalse(keyed.canEditModes)
        assertFalse(keyed.canSetTopic)

        store.apply(ServerFrame.ChannelModes(networkId = 1, target = "#c", modes = "n", params = emptyMap(), createdAt = null))
        assertTrue(store.state.channelAccess(key).canSetTopic, "-t: anyone in the channel")
    }

    @Test
    fun testNothingIsEditableOutOfTheChannel() {
        val store = storeWithChannel()
        store.apply(ServerFrame.ChannelParted(networkId = 1, target = "#c"))
        val access = store.state.channelAccess(key)
        assertFalse(access.joined)
        assertFalse(access.canEditModes)
        assertFalse(access.canSetTopic)
    }

    // MARK: - Rank

    @Test
    fun testRankUsesTheNetworksOwnLadder() {
        val odd = listOf(PrefixMode(mode = "Y", symbol = "!"), PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+"))
        assertTrue(ChannelRank.atLeast(listOf("Y"), prefix = odd, letter = "o"))
        assertTrue(ChannelRank.atLeast(listOf("v", "o"), prefix = odd, letter = "o"), "scans by rank, not array order")
        assertFalse(ChannelRank.atLeast(listOf("v"), prefix = odd, letter = "o"))
        assertFalse(ChannelRank.atLeast(emptyList(), prefix = odd, letter = "v"))
        assertEquals(0, ChannelRank.index(listOf("v", "Y"), prefix = odd))
    }

    @Test
    fun testAGateForALetterTheNetworkLacksRoundsUp() {
        val noHalfop = listOf(PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+"))
        assertTrue(ChannelRank.atLeast(listOf("o"), prefix = noHalfop, letter = "h"))
        assertFalse(ChannelRank.atLeast(listOf("v"), prefix = noHalfop, letter = "h"))
        assertFalse(ChannelRank.atLeast(listOf("o"), prefix = noHalfop, letter = "Z"), "a letter on no ladder gates nobody in")
    }

    // MARK: - Form

    @Test
    fun testRowsComeFromTheSpecNamedFirst() {
        val rows = ChannelModeForm.rows(spec)
        assertEquals(listOf("i", "m", "n", "s", "t", "k", "l", "C", "j"), rows.map { it.letter })
        assertEquals(ChannelModeForm.RowKind.Key, rows.firstOrNull { it.letter == "k" }?.kind)
        assertEquals(ChannelModeForm.RowKind.Param, rows.firstOrNull { it.letter == "j" }?.kind)
        assertNull(rows.firstOrNull { it.letter == "C" }?.name, "no name we can vouch for")
        assertFalse(rows.any { it.letter == "b" }, "lists have their own screens")
        assertEquals(listOf("b", "e", "I", "q"), ChannelModeForm.lists(spec).map { it.letter })
    }

    private fun changes(
        live: ChannelModeForm.Live,
        draft: Map<String, ChannelModeForm.DraftRow>,
    ): Result<List<OutgoingModeChange>, ChannelModeForm.ChangeError> =
        ChannelModeForm.changes(spec = spec, live = live, draft = draft)

    private fun row(on: Boolean, value: String = ""): ChannelModeForm.DraftRow =
        ChannelModeForm.DraftRow(on = on, value = value)

    @Test
    fun testFlagsDiffAgainstTheLiveState() {
        val live = ChannelModeForm.Live(modes = "nt", params = emptyMap())
        assertEquals(
            listOf(
                OutgoingModeChange(sign = '+', letter = "m"),
                OutgoingModeChange(sign = '-', letter = "t"),
            ),
            changes(live, mapOf("m" to row(true), "t" to row(false), "n" to row(true))).get(),
        )
    }

    @Test
    fun testParamModes() {
        val live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "50"))
        assertEquals(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), changes(live, mapOf("l" to row(true, " 60 "))).get())
        assertEquals(emptyList(), changes(live, mapOf("l" to row(true, "50"))).get(), "unchanged")
        assertEquals(listOf(OutgoingModeChange(sign = '-', letter = "l")), changes(live, mapOf("l" to row(false, "50"))).get(), "-l takes no param")
        assertEquals(ChannelModeForm.ChangeError.ValueRequired("j"), changes(live, mapOf("j" to row(true))).get_error())
        assertEquals(ChannelModeForm.ChangeError.Spaces("j"), changes(live, mapOf("j" to row(true, "3 4"))).get_error())
        assertEquals(emptyList(), changes(live, mapOf("j" to row(false, "3 4"))).get(), "a value being turned off needn't be valid")
    }

    @Test
    fun testTheKey() {
        val keyed = ChannelModeForm.Live(modes = "k", params = mapOf("k" to "old"))
        assertEquals(
            listOf(
                OutgoingModeChange(sign = '-', letter = "k"),
                OutgoingModeChange(sign = '+', letter = "k", param = "new"),
            ),
            changes(keyed, mapOf("k" to row(true, "new"))).get(),
            "replacing a key takes the old one off first (467 otherwise)",
        )
        assertEquals(emptyList(), changes(keyed, mapOf("k" to row(true, ""))).get(), "on, and no new key: keep it")
        assertEquals(listOf(OutgoingModeChange(sign = '-', letter = "k")), changes(keyed, mapOf("k" to row(false))).get(), "the server fills -k")

        val unknownKey = ChannelModeForm.Live(modes = "k", params = emptyMap())
        assertEquals(emptyList(), changes(unknownKey, mapOf("k" to row(true, ""))).get(), "Key is set, and left alone")

        val open = ChannelModeForm.Live(modes = "", params = emptyMap())
        assertEquals(ChannelModeForm.ChangeError.KeyRequired, changes(open, mapOf("k" to row(true, ""))).get_error())
        assertEquals(listOf(OutgoingModeChange(sign = '+', letter = "k", param = "s3cret")), changes(open, mapOf("k" to row(true, "s3cret"))).get())
    }

    /** A B-group mode other than the key names its value to unset it — `*` when we never learned it. */
    @Test
    fun testAnAlwaysParamModeNamesItsValueToUnset() {
        val bGroup = ModeSpec(list = "b", always = "kf", onSet = "l", flags = "n", prefix = emptyList(), maxModes = 3, topicLen = null)
        val live = ChannelModeForm.Live(modes = "f", params = emptyMap())
        assertEquals(
            listOf(OutgoingModeChange(sign = '-', letter = "f", param = "*")),
            ChannelModeForm.changes(spec = bGroup, live = live, draft = mapOf("f" to row(false))).get(),
        )
    }

    @Test
    fun testTopicIsBytesAndOneLine() {
        assertEquals(6, ChannelModeForm.topicBytes("héllo"))
        assertEquals("a b c", ChannelModeForm.topicToSend("a\r\nb\nc"))
    }

    // MARK: - Drafts

    @Test
    fun testAnEditDissolvesWhenTheChannelMatchesIt() {
        val live = ChannelModeForm.Live(modes = "nt", params = emptyMap())
        var drafts = ChannelModeDrafts()
        drafts = drafts.setOn("m", true, live = live)
        drafts = drafts.reconcile(live = live, liveTopic = "")
        assertNotNull(drafts.rows["m"], "the channel hasn't answered yet")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "ntm", params = emptyMap()), liveTopic = "")
        assertNull(drafts.rows["m"])
    }

    /**
     * The server may echo a value normalized — `+l 050` comes back as 50. A saved row whose
     * live state MOVED is answered, matching or not.
     */
    @Test
    fun testASavedRowDissolvesWhenItsLiveStateMoves() {
        val live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "50"))
        var drafts = ChannelModeDrafts()
        drafts = drafts.setValue("l", "050", live = live)
        val sent = ChannelModeForm.changes(spec = spec, live = live, draft = drafts.rows).get()
        drafts = drafts.noteSending(drafts.sending(sent, live = live))
        drafts = drafts.reconcile(live = live, liveTopic = "")
        assertNotNull(drafts.rows["l"], "never cleared on the ack — only the channel answers")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "50")), liveTopic = "")
        assertNotNull(drafts.rows["l"], "nothing moved: a refusal leaves the edit standing")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nl", params = mapOf("l" to "51")), liveTopic = "")
        assertNull(drafts.rows["l"])
    }

    /**
     * Untick +m again before +m comes back: the echo answers the edit that was SENT, and the
     * newer one is the user's to keep.
     */
    @Test
    fun testANewerEditOutlivesTheEchoOfTheOldOne() {
        val live = ChannelModeForm.Live(modes = "n", params = emptyMap())
        var drafts = ChannelModeDrafts()
        drafts = drafts.setOn("m", true, live = live)
        drafts = drafts.setOn("s", true, live = live)
        drafts = drafts.noteSending(drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "m")), live = live))
        drafts = drafts.setOn("m", false, live = live)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nm", params = emptyMap()), liveTopic = "")
        assertEquals(ChannelModeForm.DraftRow(on = false, value = ""), drafts.rows["m"], "the untick stands")
        assertNotNull(drafts.rows["s"], "a row nobody answered stands")
    }

    @Test
    fun testTheTopicDraft() {
        var drafts = ChannelModeDrafts()
        assertNull(drafts.topicChange(live = "old"), "untouched")
        drafts = drafts.setTopic("new\nline")
        assertEquals("new line", drafts.topicChange(live = "old"))
        drafts = drafts.noteTopicSending("new line", liveTopic = "old")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "", params = emptyMap()), liveTopic = "old")
        assertNotNull(drafts.topic)
        // The server trimmed it: moved, so the saved edit is answered.
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "", params = emptyMap()), liveTopic = "new lin")
        assertNull(drafts.topic)
    }

    /**
     * ⚠⚠ A change that never went out is not the echo's to answer. The topic failed, so the
     * +m behind it never left — and another op's +m then -m must not dissolve it.
     */
    @Test
    fun testAnUnsentChangeIsNotAnsweredBySomeoneElsesMove() {
        val live = ChannelModeForm.Live(modes = "n", params = emptyMap())
        var drafts = ChannelModeDrafts()
        drafts = drafts.setOn("m", true, live = live)
        val sending = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "m")), live = live)
        drafts = drafts.noteSending(sending)
        drafts = drafts.settle(sending, wentOut = false)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "nm", params = emptyMap()), liveTopic = "")
        assertNull(drafts.rows["m"], "matching still answers it")

        drafts = drafts.setOn("m", true, live = live)
        val again = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "m")), live = live)
        drafts = drafts.noteSending(again)
        drafts = drafts.settle(again, wentOut = false)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "ns", params = emptyMap()), liveTopic = "")
        assertNotNull(drafts.rows["m"], "but a move it never caused doesn't")
    }

    /**
     * A slow first Save whose failure lands after a second Save must not take back the second's
     * record.
     */
    @Test
    fun testTakingBackAnOldSaveLeavesANewerOne() {
        var drafts = ChannelModeDrafts()
        val fifty = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "50"))
        drafts = drafts.setValue("l", "60", live = fifty)
        val first = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = fifty)
        drafts = drafts.noteSending(first)
        val fiftyFive = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "55"))
        val second = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = fiftyFive)
        drafts = drafts.noteSending(second)
        drafts = drafts.settle(first, wentOut = false)
        // The server normalized: 55 → 56 is the second Save's echo, and answers the edit.
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "56")), liveTopic = "")
        assertNull(drafts.rows["l"])
    }

    /**
     * The echo can come before the answer. If the answer then says the send never went out,
     * the edit the echo dissolved comes back.
     */
    @Test
    fun testAnEditDissolvedWhileItsSendIsOutComesBackIfItNeverWent() {
        val live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "50"))
        var drafts = ChannelModeDrafts()
        drafts = drafts.setValue("l", "60", live = live)
        val sending = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = live)
        drafts = drafts.noteSending(sending)
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "51")), liveTopic = "")
        assertNull(drafts.rows["l"], "dissolved by the move, tentatively")
        drafts = drafts.settle(sending, wentOut = false)
        assertEquals("60", drafts.rows["l"]?.value, "never went out: the edit is the user's again")

        // Went out: the dissolve stands.
        var sent = ChannelModeDrafts()
        sent = sent.setValue("l", "60", live = live)
        val out = sent.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = live)
        sent = sent.noteSending(out)
        sent = sent.reconcile(live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "51")), liveTopic = "")
        sent = sent.settle(out, wentOut = true)
        assertNull(sent.rows["l"])
    }

    @Test
    fun testARestoreNeverOverwritesANewerEdit() {
        val live = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "50"))
        var drafts = ChannelModeDrafts()
        drafts = drafts.setValue("l", "60", live = live)
        val sending = drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "l", param = "60")), live = live)
        drafts = drafts.noteSending(sending)
        val moved = ChannelModeForm.Live(modes = "l", params = mapOf("l" to "51"))
        drafts = drafts.reconcile(live = moved, liveTopic = "")
        drafts = drafts.setValue("l", "70", live = moved)
        drafts = drafts.settle(sending, wentOut = false)
        assertEquals("70", drafts.rows["l"]?.value)
    }

    @Test
    fun testATopicDissolvedWhileItsSendIsOutComesBackIfItNeverWent() {
        var drafts = ChannelModeDrafts()
        drafts = drafts.setTopic("mine")
        drafts = drafts.noteTopicSending("mine", liveTopic = "old")
        drafts = drafts.reconcile(live = ChannelModeForm.Live(modes = "", params = emptyMap()), liveTopic = "theirs")
        assertNull(drafts.topic, "the channel moved: tentatively answered")
        drafts = drafts.settleTopic("mine", wentOut = false)
        assertEquals("mine", drafts.topic)
        assertEquals("mine", drafts.topicChange(live = "theirs"), "Save offers it again")
    }

    @Test
    fun testOnlyErrorsSoonAfterAChangeAnswerIt() {
        var refusals = ChannelRefusals()
        val start = Instant.ofEpochSecond(1_000)
        refusals = refusals.note("before", now = start)
        assertEquals(emptyList(), refusals.current, "nothing sent yet")
        refusals = refusals.arm(now = start.plusSeconds(1))
        refusals = refusals.note("482 not an op", now = start.plusSeconds(2))
        refusals = refusals.note("much later", now = start.plusSeconds(1).plus(ChannelRefusals.window).plusSeconds(1))
        assertEquals(listOf("482 not an op"), refusals.current)
        refusals = refusals.arm(now = start.plusSeconds(100))
        assertEquals(emptyList(), refusals.current, "a new change starts clean")
    }

    @Test
    fun testASaveFailureSaysWhetherAnythingCanHaveGoneOut() {
        assertNull(ChatViewModel.saveFailure(VerbReply(ok = true, error = null)))
        assertEquals(true, ChatViewModel.saveFailure(VerbReply.notSent)?.certainlyUnsent)
        assertEquals(true, ChatViewModel.saveFailure(VerbReply(ok = false, error = "account-paused"))?.certainlyUnsent)
        assertEquals(false, ChatViewModel.saveFailure(VerbReply.noAnswer)?.certainlyUnsent, "it may have gone out")
        assertEquals(false, ChatViewModel.saveFailure(VerbReply.connectionLost)?.certainlyUnsent)
        assertEquals(
            "The connection dropped before the server answered.",
            ChatViewModel.saveFailure(VerbReply.connectionLost)?.message,
        )
    }

    // MARK: - Lists

    private fun modeRow(nick: String, changes: List<ModeChange>): Message =
        Message(id = 1, type = EventType.Mode, nick = nick, text = null, date = Instant.ofEpochSecond(50), modes = changes)

    @Test
    fun testAnOpenListIsPatchedFromLiveRows() {
        val fetched = listOf(ModeListEntry(mask = "*!*@Bad.host", setBy = "op", setAt = null))
        val patched = ChannelModeForm.patch(
            fetched,
            rows = listOf(
                modeRow("op2", listOf(ModeChange(mode = "+b", param = "troll!*@*", kind = ModeChangeKind.List))),
                modeRow("op2", listOf(ModeChange(mode = "-b", param = "*!*@bad.HOST", kind = ModeChangeKind.List))),
                modeRow("op2", listOf(ModeChange(mode = "+b", param = "TROLL!*@*", kind = ModeChangeKind.List))),
                modeRow("op2", listOf(ModeChange(mode = "+e", param = "friend!*@*", kind = ModeChangeKind.List))),
                // Solanum's +q is a list; elsewhere it's an owner, which the server stamps `prefix`.
                modeRow("op2", listOf(ModeChange(mode = "+b", param = "nick", kind = ModeChangeKind.Prefix))),
            ),
            letter = "b",
        )
        assertEquals(listOf("troll!*@*"), patched.map { it.mask }, "case-insensitive both ways, other letters and kinds ignored")
        assertEquals("op2", patched.firstOrNull()?.setBy)
        assertEquals(Instant.ofEpochSecond(50), patched.firstOrNull()?.setAt)
    }

    @Test
    fun testLastKeyChange() {
        assertEquals(ChannelModeForm.KeySighting.None, ChannelModeForm.lastKeyChange(emptyList()))
        assertEquals(
            ChannelModeForm.KeySighting.Set("pw"),
            ChannelModeForm.lastKeyChange(listOf(modeRow("a", listOf(ModeChange(mode = "+k", param = "pw", kind = ModeChangeKind.Chan))))),
        )
        assertEquals(
            ChannelModeForm.KeySighting.Removed,
            ChannelModeForm.lastKeyChange(
                listOf(
                    modeRow("a", listOf(ModeChange(mode = "+k", param = "pw", kind = ModeChangeKind.Chan))),
                    modeRow("a", listOf(ModeChange(mode = "-k", param = "*", kind = ModeChangeKind.Chan))),
                )
            ),
        )
        // A hidden value is still the newest word: an older key must not show through.
        assertEquals(
            ChannelModeForm.KeySighting.SetUnknown,
            ChannelModeForm.lastKeyChange(
                listOf(
                    modeRow("a", listOf(ModeChange(mode = "+k", param = "pw", kind = ModeChangeKind.Chan))),
                    modeRow("a", listOf(ModeChange(mode = "+k", param = "*", kind = ModeChangeKind.Chan))),
                )
            ),
            "`*` is a mask, not a key",
        )
        assertEquals(
            ChannelModeForm.KeySighting.SetUnknown,
            ChannelModeForm.lastKeyChange(listOf(modeRow("a", listOf(ModeChange(mode = "+k", param = null, kind = ModeChangeKind.Chan))))),
        )
    }

    /**
     * The success, or a failed test — Swift's `try result.get()`.
     *
     * Port note: `get()` is the Swift standard library's; `support.Result` carries only the two
     * cases, so the test supplies it, beside the `get_error` LurkerKit's own test file adds.
     */
    private fun <T, E> Result<T, E>.get(): T =
        when (this) {
            is Result.Success -> value
            is Result.Failure -> fail("expected a success, got $error")
        }

    /** The failure, or null — so a test can compare it with `assertEquals`. */
    @Suppress("FunctionName")
    private fun <T, E> Result<T, E>.get_error(): E? =
        when (this) {
            is Result.Success -> null
            is Result.Failure -> error
        }


    // Port-only: LurkerKit mutates these two in place, so a change always sticks. Here each
    // change is a new value, and a holder that keeps the latest value only when it differs
    // (`MutableStateFlow`, Compose state) keeps it only if it compares unequal — which
    // LurkerKit's own `==`, looking at what is on screen and nothing else, would not say.

    @Test
    fun testArmingIsAChangeEvenThoughNothingIsCurrentYet() {
        val now = Instant.ofEpochSecond(1_000)
        val refusal = "482 You're not a channel operator"
        val fresh = ChannelRefusals()
        val armed = fresh.arm(now)
        assertEquals(fresh.current, armed.current)
        assertNotEquals(fresh, armed)
        // And the refusal that answers it is seen only because the arming was kept.
        assertEquals(listOf(refusal), armed.note(refusal, now).current)
        assertEquals(emptyList(), fresh.note(refusal, now).current)
    }

    @Test
    fun testNotingASendIsAChangeEvenThoughTheFormLooksTheSame() {
        val live = ChannelModeForm.Live(modes = "nt", params = emptyMap())
        val drafts = ChannelModeDrafts().setOn("m", true, live)
        val noted = drafts.noteSending(drafts.sending(listOf(OutgoingModeChange(sign = '+', letter = "m")), live = live))
        assertEquals(drafts.rows, noted.rows)
        assertEquals(drafts.topic, noted.topic)
        assertNotEquals(drafts, noted)
    }
}
