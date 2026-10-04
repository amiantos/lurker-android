// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.HistoryMode
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.VerbReply
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the parser to the real wire contract (mapped from the server source):
 * flat-spread `irc` events, `events:[] + hasMoreOlder:true` shells, names living only
 * in the REST roster, etc. Ported from the Android client's FrameParserTest.
 */
class FrameParserTests {

    @Test
    fun testBufferClosedParses() {
        val frame = FrameParser.parseWs("""{"kind":"buffer-closed","networkId":1,"target":"#lurker"}""")
        assertEquals(ServerFrame.BufferClosed(networkId = 1, target = "#lurker"), frame)
    }

    @Test
    fun testBufferClosedKeepsANullNetworkIdNullRatherThanFoldingItToZero() {
        // The system buffer's networkId is genuinely null, and BufferKey distinguishes null
        // from network 0 — reading this with a 0 default would key the wrong buffer.
        val frame = FrameParser.parseWs("""{"kind":"buffer-closed","networkId":null,"target":":system:"}""")
        assertEquals(ServerFrame.BufferClosed(networkId = null, target = ":system:"), frame)
    }

    @Test
    fun testBufferClosedWithoutATargetIsIgnored() {
        // Nothing to key on — dropping beats removing an arbitrary buffer.
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"buffer-closed","networkId":1}"""))
    }

    @Test
    fun testBufferRenamedParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"buffer-renamed","networkId":1,"bufferId":7,"from":"alice","to":"alice2","merged":false}""",
        )
        assertEquals(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "alice", to = "alice2", bufferId = 7,
                merged = false, mergedFromBufferId = null,
            ),
            frame,
        )
    }

    @Test
    fun testBufferRenamedMergeCarriesTheAbsorbedBufferId() {
        val frame = FrameParser.parseWs(
            """{"kind":"buffer-renamed","networkId":1,"bufferId":7,"from":"alice","to":"alice_away","merged":true,"mergedFromBufferId":9}""",
        )
        assertEquals(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "alice", to = "alice_away", bufferId = 7,
                merged = true, mergedFromBufferId = 9,
            ),
            frame,
        )
    }

    @Test
    fun testBufferRenamedWithoutBothNamesIsIgnored() {
        // Same posture as buffer-closed: an empty name can't identify anything, and
        // renaming an arbitrary buffer is worse than dropping the frame.
        assertEquals(
            ServerFrame.Ignored,
            FrameParser.parseWs("""{"kind":"buffer-renamed","networkId":1,"to":"alice2"}"""),
        )
        assertEquals(
            ServerFrame.Ignored,
            FrameParser.parseWs("""{"kind":"buffer-renamed","networkId":1,"from":"alice"}"""),
        )
    }

    @Test
    fun testBacklogCarriesTheBufferIdWhenTheServerStatesOne() {
        // The connect burst doubles as the id⇄name directory (§5.2).
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","bufferId":12,"events":[],"hasMoreOlder":true}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertEquals(12, frame.buffer.bufferId)
    }

    @Test
    fun testBacklogWithoutABufferIdLeavesItNilNotZero() {
        // A pre-id server sends no field; 0 would collide with nothing today and
        // something eventually. Absent must parse as absent.
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"hasMoreOlder":true}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertNull(frame.buffer.bufferId)
    }

    @Test
    fun testChannelBacklogShellParsesAsUnhydratedWithNoMessages() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"hasMoreOlder":true,"joined":true,"unread":3,"lastReadId":42}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        val (buffer, messages, hydrated) = frame
        assertFalse(hydrated, "events:[] + hasMoreOlder:true is a shell")
        assertEquals(0, messages.size)
        assertEquals(BufferKind.Channel, buffer.kind)
        assertEquals(3, buffer.unread)
        assertEquals(42L, buffer.lastReadId)
        assertTrue(buffer.readStateKnown, "the frame carried a pointer, so it stated one")
    }

    /**
     * ⚠ Read from the FIELD'S PRESENCE, never its value: `long()` reads a missing `lastReadId`
     * as 0, and 0 is also a legitimate "this buffer has been read up to nothing". A frame that
     * never mentioned the pointer must not be able to claim it stated one — a screen latching
     * its unread divider from that 0 loses the divider for good.
     */
    @Test
    fun testABacklogWithNoPointerStatesNoReadState() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"hasMoreOlder":true,"joined":true}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertEquals(0L, frame.buffer.lastReadId, "absent parses as 0…")
        assertFalse(frame.buffer.readStateKnown, "…but that 0 is the default, not a statement")
    }

    /**
     * And a pointer of 0 that the server actually sent IS a statement — the distinction the
     * value alone can't carry.
     */
    @Test
    fun testAnExplicitZeroPointerCounts() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"hasMoreOlder":true,"lastReadId":0}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertEquals(0L, frame.buffer.lastReadId)
        assertTrue(frame.buffer.readStateKnown, "the server said 0; that's an answer")
    }

    @Test
    fun testHydratedBacklogParsesItsEvents() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,"events":[{"id":1,"type":"message","nick":"alice","text":"hi","self":false},{"id":2,"type":"action","nick":"bob","text":"waves","self":true}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        val (_, messages, hydrated) = frame
        assertTrue(hydrated)
        assertEquals(2, messages.size)
        assertEquals(EventType.Message, messages[0].type)
        assertEquals("hi", messages[0].text)
        assertEquals(EventType.Action, messages[1].type)
        assertTrue(messages[1].isSelf)
    }

    @Test
    fun testMessageTextKeepsALeadingByteOrderMark() {
        // lurker-ios#196: Foundation strips one leading U+FEFF from every string; the web and
        // the server keep it.
        val frame = FrameParser.parseWs(
            "{\"kind\":\"backlog\",\"networkId\":1,\"target\":\"#lurker\",\"hasMoreOlder\":false,\"events\":[{\"id\":1,\"type\":\"message\",\"nick\":\"alice\",\"text\":\"${0xFEFF.toChar()}pasted\"}]}",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        val messages = frame.messages
        assertEquals(0xFEFF.toChar(), messages.firstOrNull()?.text?.firstOrNull())
    }

    @Test
    fun testLiveIrcFrameReadsTheEventSpreadFlatOnTheFrame() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","id":7,"networkId":1,"target":"#lurker","type":"message","nick":"carol","text":"yo","self":false,"matched":true}""",
        )
        if (frame !is ServerFrame.Live) fail("expected live, got $frame")
        val (networkId, target, message) = frame
        assertEquals(1, networkId)
        assertEquals("#lurker", target)
        assertEquals(7L, message.id)
        assertEquals("carol", message.nick)
        assertTrue(message.matched)
    }

    /**
     * The server's `extractExtras` spreads one structured field onto each structural event
     * — `newNick` on nick, `kicked` on kick, `invited` on invite, `modes` on mode. The
     * renderer needs these to synthesize "bob is now bob_afk" etc., so the parser must lift
     * them off the flat frame.
     */
    @Test
    fun testStructuralEventsParseTheirExtraFields() {
        val nick = FrameParser.parseWs(
            """{"kind":"irc","id":1,"networkId":1,"target":"#lurker","type":"nick","nick":"bob","newNick":"bob_afk"}""",
        )
        if (nick !is ServerFrame.Live) fail("expected live nick event")
        assertEquals("bob_afk", nick.message.newNick)

        val kick = FrameParser.parseWs(
            """{"kind":"irc","id":2,"networkId":1,"target":"#lurker","type":"kick","nick":"op","kicked":"troll","text":"bye"}""",
        )
        if (kick !is ServerFrame.Live) fail("expected live kick event")
        assertEquals("troll", kick.message.kicked)

        val invite = FrameParser.parseWs(
            """{"kind":"irc","id":3,"networkId":1,"target":"#lurker","type":"invite","nick":"host","invited":"guest"}""",
        )
        if (invite !is ServerFrame.Live) fail("expected live invite event")
        assertEquals("guest", invite.message.invited)

        val mode = FrameParser.parseWs(
            """{"kind":"irc","id":4,"networkId":1,"target":"#lurker","type":"mode","nick":"chan","text":"+o alice","modes":[{"mode":"+o","param":"alice"}]}""",
        )
        if (mode !is ServerFrame.Live) fail("expected live mode event")
        assertEquals(1, mode.message.modes.size)
        assertEquals("+o", mode.message.modes.firstOrNull()?.mode)
        assertEquals("alice", mode.message.modes.firstOrNull()?.param)

        val chghost = FrameParser.parseWs(
            """{"kind":"irc","id":5,"networkId":1,"target":"#lurker","type":"chghost","nick":"bob","userhost":"bob!old@old.host","newIdent":"~new","newHost":"new.host"}""",
        )
        if (chghost !is ServerFrame.Live) fail("expected live chghost event")
        assertEquals(EventType.Chghost, chghost.message.type, "chghost must not fold to Other — it renders nowhere there")
        assertEquals("~new@new.host", chghost.message.chghostMask)
        assertEquals("bob!old@old.host", chghost.message.userhost, "the mask before the change")
        assertTrue(chghost.message.isRenderable)

        val join = FrameParser.parseWs(
            """{"kind":"irc","id":6,"networkId":1,"target":"#lurker","type":"join","nick":"bob","userhost":"bob!u@h","account":"bobby"}""",
        )
        if (join !is ServerFrame.Live) fail("expected live join event")
        assertEquals("bobby", join.message.account)
        assertEquals("bob!u@h", join.message.userhost)
    }

    /**
     * A logged-out user's extended-join account is the `*` sentinel, which the server stores
     * as null and omits — so it must read as "nothing to show", not as an account named `*`.
     */
    @Test
    fun testAJoinWithoutAnAccountCarriesNone() {
        val join = FrameParser.parseWs(
            """{"kind":"irc","id":1,"networkId":1,"target":"#lurker","type":"join","nick":"bob"}""",
        )
        if (join !is ServerFrame.Live) fail("expected live join event")
        assertNull(join.message.account)
        assertNull(join.message.userhost)
    }

    /**
     * `channel-topic` rides `kind:"irc"` like an event, but it isn't one: no id, nothing
     * to render, and its payload is in `topic` rather than `text`. Parsed as an event it
     * would become an `Other` Message appended to the buffer with the topic in a field
     * nothing reads.
     */
    @Test
    fun testChannelTopicIsLiftedOutOfIrcRatherThanParsedAsAnEvent() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#lurker","type":"channel-topic","topic":"welcome all"}""",
        )
        if (frame !is ServerFrame.ChannelTopic) fail("expected channelTopic, got $frame")
        val (networkId, target, topic) = frame
        assertEquals(1, networkId)
        assertEquals("#lurker", target)
        assertEquals("welcome all", topic)
    }

    @Test
    fun testAClearedChannelTopicParsesAsNilNotEmptyString() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#lurker","type":"channel-topic"}""",
        )
        if (frame !is ServerFrame.ChannelTopic) fail("expected channelTopic, got $frame")
        assertNull(frame.topic)
    }

    /**
     * `names` is lifted out of `irc` for the same reason as `channel-topic`: state, not
     * a line, with its payload in `members` where `parseEvent` never looks.
     */
    @Test
    fun testANamesEventParsesToChannelMembersNotALine() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#lurker","type":"names","members":[{"nick":"alice","modes":["o"],"away":false,"user":"al","host":"example.org"},{"nick":"bob","modes":[],"away":true}]}""",
        )
        if (frame !is ServerFrame.ChannelMembers) fail("expected channelMembers, got $frame")
        val (networkId, target, members) = frame
        assertEquals(1, networkId)
        assertEquals("#lurker", target)
        assertEquals(listOf("alice", "bob"), members.map { it.nick })
        assertEquals(listOf("o"), members[0].modes)
        assertEquals("example.org", members[0].host)
        assertTrue(members[1].away)
    }

    /**
     * `channel-joined` and `channel-parted` are membership, not lines: no id, nothing to render.
     * Parsed as events they became `Other` Messages, and `applyLive` minted a row for each — a
     * forward's part for a channel we never had included.
     */
    @Test
    fun testChannelJoinedAndPartedAreLiftedOutOfIrcRatherThanParsedAsEvents() {
        assertEquals(
            ServerFrame.ChannelJoined(networkId = 1, target = "#lurker"),
            FrameParser.parseWs("""{"kind":"irc","networkId":1,"target":"#lurker","type":"channel-joined"}"""),
        )
        assertEquals(
            ServerFrame.ChannelParted(networkId = 1, target = "#lurker"),
            FrameParser.parseWs("""{"kind":"irc","networkId":1,"target":"#lurker","type":"channel-parted"}"""),
        )
    }

    /**
     * A refused join names the channel it refused, one we're not in. As a live event it reached
     * `applyLive`, which minted a row for that channel that read joined (lurker-ios#168). Its
     * own frame carries the server's sentence for the refusal (lurker-ios#57).
     */
    @Test
    fun testAJoinErrorParsesToItsOwnFrameWithTheServersReason() {
        assertEquals(
            ServerFrame.JoinError(networkId = 1, target = "#secret", reason = "This channel is invite-only."),
            FrameParser.parseWs(
                """{"kind":"irc","networkId":1,"target":"#secret","type":"join-error","text":"This channel is invite-only.","reason":"Cannot join channel (+i)"}""",
            ),
        )
        // No sentence from the server: the IRC server's own, then a plain one.
        assertEquals(
            ServerFrame.JoinError(networkId = 1, target = "#secret", reason = "Cannot join channel (+i)"),
            FrameParser.parseWs(
                """{"kind":"irc","networkId":1,"target":"#secret","type":"join-error","reason":"Cannot join channel (+i)"}""",
            ),
        )
        assertEquals(
            ServerFrame.JoinError(networkId = 1, target = "#secret", reason = "The server refused the join."),
            FrameParser.parseWs("""{"kind":"irc","networkId":1,"target":"#secret","type":"join-error"}"""),
        )
    }

    @Test
    fun testAMemberUpdateParsesItsMemberSnapshot() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#lurker","type":"member-update","member":{"nick":"bob","modes":["v"],"away":true,"user":"rob","host":"new.example.org"}}""",
        )
        if (frame !is ServerFrame.MemberUpdate) fail("expected memberUpdate, got $frame")
        val (networkId, target, member) = frame
        assertEquals(1, networkId)
        assertEquals("#lurker", target)
        assertEquals("bob", member.nick)
        assertEquals(listOf("v"), member.modes)
        assertTrue(member.away)
        assertEquals("new.example.org", member.host)
    }

    @Test
    fun testAMemberUpdateWithoutANickIsIgnoredNotAppliedToNobody() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#lurker","type":"member-update","member":{"away":true}}""",
        )
        if (frame !is ServerFrame.Ignored) fail("expected ignored, got $frame")
    }

    @Test
    fun testSnapshotParsesNetworksChannelsAndMembersButNoName() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":1,"state":"connected","nick":"me","channels":[{"name":"#lurker","topic":"hi","members":[{"nick":"alice","modes":["o"],"away":false},{"nick":"bob","modes":[],"away":true}]}]}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        val networks = frame.networks
        assertEquals(1, networks.size)
        val network = networks[0]
        assertEquals(1, network.id)
        assertEquals(ConnectionState.Connected, network.state)
        assertEquals("me", network.nick)
        val channel = network.channels[0]
        assertEquals("#lurker", channel.name)
        assertEquals(listOf("alice", "bob"), channel.members.map { it.nick })
        assertEquals(listOf("o"), channel.members[0].modes)
        assertTrue(channel.members[1].away)
    }

    @Test
    fun testResumeSliceWithResetFalseIsAnAppend() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","reset":false,"hasMoreOlder":false,"events":[{"id":5,"type":"message","nick":"a","text":"x"}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertTrue(frame.hydrated)
        assertTrue(frame.append, "reset:false gap → append")
    }

    @Test
    fun testResumeSliceWithResetTrueReplaces() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","reset":true,"hasMoreOlder":false,"events":[{"id":5,"type":"message","nick":"a","text":"x"}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertFalse(frame.append, "reset:true → replace")
    }

    @Test
    fun testFullBacklogWithNoResetFieldReplaces() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,"events":[{"id":5,"type":"message","nick":"a","text":"x"}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertFalse(frame.append, "absent reset → replace, not append")
    }

    @Test
    fun testHistoryBeforePageParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"history","networkId":1,"target":"#lurker","mode":"before","hasMoreOlder":true,"hasMoreNewer":false,"events":[{"id":10,"type":"message","nick":"a","text":"old"}]}""",
        )
        if (frame !is ServerFrame.History) fail("expected history, got $frame")
        val (networkId, target, events, mode, hasMoreOlder, hasMoreNewer) = frame
        assertEquals(1, networkId)
        assertEquals("#lurker", target)
        assertEquals(HistoryMode.Before, mode)
        assertEquals(listOf("old"), events.map { it.text })
        assertTrue(hasMoreOlder)
        assertFalse(hasMoreNewer)
    }

    @Test
    fun testHistoryHasMoreFallsBackToLegacyAlias() {
        val frame = FrameParser.parseWs(
            """{"kind":"history","networkId":1,"target":"#lurker","mode":"before","hasMore":true,"events":[]}""",
        )
        if (frame !is ServerFrame.History) fail("expected history, got $frame")
        assertTrue(frame.hasMoreOlder, "hasMore is the legacy alias for hasMoreOlder")
    }

    // MARK: - Speakers (lurker-ios#63)

    /**
     * `lastTime` is epoch milliseconds, not the ISO string every other timestamp on the wire
     * uses — so this is the one field where reading it like the others would be off by three
     * orders of magnitude and read as 1970.
     */
    @Test
    fun testSpeakersParseFromEpochMilliseconds() {
        val frame = FrameParser.parseWs(
            """{"kind":"history","networkId":1,"target":"#lurker","mode":"latest","events":[],"speakers":[{"nick":"Alice","lastTime":1784548800000}]}""",
        )
        if (frame !is ServerFrame.History) fail("expected history, got $frame")
        val speakers = frame.speakers
        assertEquals(1, speakers?.size)
        assertEquals("Alice", speakers?.firstOrNull()?.nick, "the server's casing survives")
        assertEquals(Instant.ofEpochSecond(1_784_548_800), speakers?.firstOrNull()?.lastSpoke)
    }

    /**
     * Absent and empty are different answers ON THE WIRE, and the parser keeps them apart
     * rather than folding both to an empty list — a decode that erases the difference cannot be
     * undone by a later layer that wants it.
     *
     * ⚠ What the store does with them is deliberately the SAME: `seedSpeakers` merges
     * forward-only and never clears, so neither answer can wipe what the client already knows.
     * The web reaches that by merging every existing entry back over the incoming list
     * (`buffers.ts:1178`), which makes seeding `[]` a no-op by construction; we reach it one
     * step earlier with a guard. Nobody acts on the difference today, and that is not an
     * oversight — an empty list is what `listSpeakers` returns for a channel with no speech in
     * its scan window, and what `buildSystemBacklog` hardcodes for a buffer that has no
     * speakers at all. Neither is an assertion worth discarding known nicks over. What the
     * server's OMISSION buys (`wsHub.ts:927`) is not having to rely on that.
     */
    @Test
    fun testAnAbsentSpeakersFieldIsNilAndAnEmptyOneIsEmpty() {
        val absent = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"hasMoreOlder":true}""",
        )
        if (absent !is ServerFrame.Backlog) fail("expected backlog, got $absent")
        assertNull(absent.speakers)

        val empty = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"speakers":[]}""",
        )
        if (empty !is ServerFrame.Backlog) fail("expected backlog, got $empty")
        assertEquals(emptyList(), empty.speakers)
    }

    /**
     * A half-filled entry is dropped rather than defaulted: a speaker stamped at the epoch
     * reads as infinitely stale, which is the same as being absent but harder to notice.
     */
    @Test
    fun testIncompleteSpeakerEntriesAreDropped() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],"speakers":[{"nick":"","lastTime":1784548800000},{"nick":"bob"},{"nick":"carol","lastTime":0},{"nick":"dave","lastTime":1784548800000}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertEquals(listOf("dave"), frame.speakers?.map { it.nick })
    }

    @Test
    fun testReadStateParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"read-state","networkId":1,"target":"#lurker","lastReadId":42,"unread":3,"highlights":1}""",
        )
        if (frame !is ServerFrame.ReadState) fail("expected readState, got $frame")
        val (networkId, target, lastReadId, unread, highlights) = frame
        assertEquals(1, networkId)
        assertEquals("#lurker", target)
        assertEquals(42L, lastReadId)
        assertEquals(3, unread)
        assertEquals(1, highlights)
    }

    @Test
    fun testSendResultCarriesClientIdOkAndError() {
        val frame = FrameParser.parseWs("""{"kind":"send-result","clientId":"c1","ok":false,"error":"unknown-network"}""")
        if (frame !is ServerFrame.SendResult) fail("expected sendResult, got $frame")
        val (clientId, ok, error) = frame
        assertEquals("c1", clientId)
        assertFalse(ok)
        assertEquals("unknown-network", error)
    }

    @Test
    fun testRestNetworksParseIdAndName() {
        val frame = FrameParser.parseNetworks("""{"networks":[{"id":1,"name":"Libera"},{"id":2,"name":"OFTC"}]}""")
        if (frame !is ServerFrame.Networks) fail("expected networks, got $frame")
        assertEquals(listOf(1, 2), frame.networks.map { it.id })
        assertEquals(listOf("Libera", "OFTC"), frame.networks.map { it.name })
    }

    @Test
    fun testRestNetworksReadBlockedAndTreatAbsentAsAllowed() {
        // `blocked` rides the roster in beside the name (lurker-ios#152). Absent is "not
        // blocked": an older server has no allowlist to be excluded from.
        val frame = FrameParser.parseNetworks("""{"networks":[{"id":1,"name":"Libera","blocked":true},{"id":2,"name":"OFTC"}]}""")
        if (frame !is ServerFrame.Networks) fail("expected networks, got $frame")
        assertEquals(listOf(true, false), frame.networks.map { it.blocked })
    }

    @Test
    fun testHighlightsPageParsesItemsWithBufferAddressAndCursor() {
        val page = FrameParser.parseHighlights(
            """
            {"items":[
              {"id":91,"networkId":1,"target":"#lurker","networkName":"Libera","type":"message","nick":"alice","text":"hey @you","self":false,"matched":true,"time":"2026-07-22T20:00:00.000Z"},
              {"id":88,"networkId":2,"target":"bob","networkName":"OFTC","type":"message","nick":"bob","text":"ping","self":false,"matched":true}
            ],"nextBefore":88}
            """.trimIndent(),
        )
        assertEquals(2, page.items.size)
        assertEquals(91L, page.items[0].message.id)
        assertEquals("alice", page.items[0].message.nick)
        assertEquals("hey @you", page.items[0].message.text)
        assertTrue(page.items[0].message.matched)
        assertNotNull(page.items[0].message.date, "the ISO time is parsed at the wire boundary")
        assertEquals(1, page.items[0].networkId)
        assertEquals("#lurker", page.items[0].target)
        assertEquals("Libera", page.items[0].networkName)
        assertEquals(BufferKey(networkId = 1, target = "#lurker"), page.items[0].bufferKey)
        // A DM highlight resolves its buffer the same way, keyed on the nick target.
        assertEquals(BufferKey(networkId = 2, target = "bob"), page.items[1].bufferKey)
        assertEquals(88L, page.nextBefore)
        assertTrue(page.hasMore)
    }

    @Test
    fun testHighlightsLastPageHasNoCursor() {
        // The server drops `nextBefore` (null) once a page doesn't fill the limit — that's
        // the end signal, and it must read as "no more" rather than a cursor of 0.
        val page = FrameParser.parseHighlights("""{"items":[{"id":5,"networkId":1,"target":"#c","type":"message","nick":"a","text":"hi"}],"nextBefore":null}""")
        assertEquals(1, page.items.size)
        assertNull(page.nextBefore)
        assertFalse(page.hasMore)
    }

    @Test
    fun testHighlightsMalformedBodyIsAnEmptyPageNotACrash() {
        val page = FrameParser.parseHighlights("not json")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextBefore)
    }

    @Test
    fun testAnUnknownFrameKindIsIgnoredNotAnError() {
        // Was `draft-snapshot`, until lurker-ios#188 handled it — a kind no server sends stays
        // unknown.
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"no-such-frame","drafts":{}}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("not json at all"))
    }

    @Test
    fun testTheSystemBufferIsClassifiedAsSystemNotADm() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":null,"target":":system:","hasMoreOlder":false,"events":[]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertEquals(BufferKind.System, frame.buffer.kind)
        assertNull(frame.buffer.networkId)
    }

    // MARK: - Bookmarks

    /**
     * `bookmarked` rides on the message rows in a BACKLOG — that's the path that matters,
     * since it's the only way the client learns about a save it didn't witness now that the
     * connect burst carries no bookmark snapshot. (A live message has just arrived, so it is
     * never already saved.)
     *
     * Asserted together with an unsaved row in the same page: absent means unsaved, because
     * the server omits the field rather than sending false — nearly every row in every
     * backlog is unsaved, and a false on each is pure wire weight.
     */
    @Test
    fun testBookmarkedFlagParsesOffBacklogRows() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,"events":[{"id":1,"type":"message","nick":"a","text":"plain"},{"id":2,"type":"message","nick":"a","text":"kept","bookmarked":true}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertFalse(frame.messages[0].bookmarked, "absent reads as unsaved")
        assertTrue(frame.messages[1].bookmarked)
    }

    @Test
    fun testBookmarkUpdatedParsesBothDirections() {
        assertEquals(
            ServerFrame.BookmarkUpdated(messageId = 42, saved = true),
            FrameParser.parseWs("""{"kind":"bookmark-updated","messageId":42,"saved":true}"""),
        )
        assertEquals(
            ServerFrame.BookmarkUpdated(messageId = 42, saved = false),
            FrameParser.parseWs("""{"kind":"bookmark-updated","messageId":42,"saved":false}"""),
        )
    }

    /**
     * A zero/missing id can't address a row, so it's dropped rather than folded into the set
     * where it would sit forever as a phantom bookmark.
     */
    @Test
    fun testBookmarkUpdatedWithoutAnIdIsIgnored() {
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"bookmark-updated","saved":true}"""))
    }

    // Port-only:

    // What follows pins the seam between the two JSON decoders — Foundation's
    // `JSONSerialization` under LurkerKit, kotlinx.serialization here — on the inputs where they
    // part company. Every "Foundation …" below is the real Swift's answer, taken by running
    // LurkerKit's `FrameParser` over the same text. kotlinx's answer is the one kept (see
    // `FrameParser.object`'s Port note), and this is where each difference is written down. None
    // of these inputs can come from `JSON.stringify`, bar the lone surrogate and the string that
    // begins with U+FEFF — the two LurkerKit's `JSONTextRepair` now works around on iOS
    // (lurker-ios#195, #196), which is not ported. With it, a leading U+FEFF reads the same on
    // both sides, and a lone surrogate is read on both, as U+FFFD on iOS and as itself here.

    /** U+FEFF, built from its number so no editor can quietly lose it from the source. */
    private val mark = 0xFEFF.toChar().toString()

    /** A JSON escape, as six characters of JSON text. */
    private fun escaped(hex: String): String = "\\" + "u" + hex

    /** differs: a byte-order mark before the document fails the frame here; Foundation steps over one (and fails two) */
    @Test
    fun testABomBeforeTheDocumentFailsTheFrame() {
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs(mark + """{"kind":"error","text":"x"}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs(mark + mark + """{"kind":"error","text":"x"}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"error","text":"x"}""" + mark))
        // Ordinary whitespace around the document is fine on both.
        assertEquals(ServerFrame.ServerError("ws"), FrameParser.parseWs("\n\t {\"kind\":\"error\",\"text\":\"ws\"}\r\n"))
    }

    /**
     * Agrees since lurker-ios#196. Foundation drops one leading U+FEFF from every string it
     * decodes — a value or a key, raw or escaped, at any depth — so LurkerKit's
     * `JSONTextRepair` writes a second for it to eat, and iOS gives these answers too. kotlinx
     * keeps the string as the server sent it, with nothing to repair.
     */
    @Test
    fun testALeadingBomInAStringIsKept() {
        assertEquals(ServerFrame.ServerError("${mark}bom"), FrameParser.parseWs("""{"kind":"error","text":"$mark""" + """bom"}"""))
        assertEquals(ServerFrame.ServerError("${mark}bom"), FrameParser.parseWs("""{"kind":"error","text":"${escaped("feff")}bom"}"""))
        assertEquals(ServerFrame.ServerError(mark), FrameParser.parseWs("""{"kind":"error","text":"$mark"}"""))
        assertEquals(ServerFrame.ServerError("mid${mark}bom"), FrameParser.parseWs("""{"kind":"error","text":"mid${mark}bom"}"""))
        // A key too: it is a key nothing reads.
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"${mark}kind":"error","text":"key"}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"${mark}error","text":"kind"}"""))
        // Deep inside a frame: the nick, not just the text.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","id":1,"networkId":1,"target":"#a","type":"message","nick":"${mark}alice","text":"$mark/me"}""",
        )
        if (frame !is ServerFrame.Live) fail("expected live, got $frame")
        assertEquals("${mark}alice", frame.message.nick)
        assertEquals("$mark/me", frame.message.text)
    }

    /**
     * differs: an unquoted token that isn't JSON fails the document in Foundation. kotlinx hands
     * it back as a literal and reads on; `Json.kt` then sees a literal that is not a number.
     */
    @Test
    fun testATokenThatIsNotJsonStillReadsTheFrame() {
        for (token in listOf("1d", "+1", "01", "1.", ".5", "-", "NaN", "Infinity", "0x10", "TRUE", "True", "nul", "hello", "1_000", "2.e3", "1e", "1e+")) {
            assertEquals(ServerFrame.ServerError("x"), FrameParser.parseWs("""{"kind":"error","text":"x","n":$token}"""), token)
        }
        // …and the tokens that are JSON read on both sides.
        for (token in listOf("0", "-0", "1", "1.5", "3.0", "1e3", "1E+5", "-0e-0", "0.1e1", "true", "false", "null", "9223372036854775807")) {
            assertEquals(ServerFrame.ServerError("x"), FrameParser.parseWs("""{"kind":"error","text":"x","n":$token}"""), token)
        }
        // Read through a field, such a token is not a count — unless Java's number grammar
        // happens to accept it: `Json.kt` trusts its token to be JSON, so `1d` reads as 1.
        assertEquals(
            ServerFrame.ReadState(networkId = 1, target = "#a", lastReadId = 0, unread = 0, highlights = 1),
            FrameParser.parseWs("""{"kind":"read-state","networkId":1,"target":"#a","lastReadId":hello,"unread":NaN,"highlights":1d}"""),
        )
    }

    /** differs: an unescaped control character inside a string fails the document in Foundation; here it is kept */
    @Test
    fun testARawControlCharacterInAStringIsKept() {
        for (code in listOf(0x00, 0x01, 0x09, 0x0A, 0x0D, 0x1B, 0x1F)) {
            val raw = code.toChar().toString()
            assertEquals(ServerFrame.ServerError("a${raw}b"), FrameParser.parseWs("""{"kind":"error","text":"a${raw}b"}"""), "U+%04X".format(code))
        }
        // Escaped, it reads on both.
        assertEquals(ServerFrame.ServerError("a\tb"), FrameParser.parseWs("""{"kind":"error","text":"a\tb"}"""))
        assertEquals(ServerFrame.ServerError("nul \u0000 x"), FrameParser.parseWs("""{"kind":"error","text":"nul ${escaped("0000")} x"}"""))
        // DEL and the C1 range are not control characters to JSON on either side.
        assertEquals(ServerFrame.ServerError("del ${0x7F.toChar()} x"), FrameParser.parseWs("""{"kind":"error","text":"del ${0x7F.toChar()} x"}"""))
        assertEquals(ServerFrame.ServerError("c1 ${0x85.toChar()} x"), FrameParser.parseWs("""{"kind":"error","text":"c1 ${0x85.toChar()} x"}"""))
    }

    /**
     * ⚠ The crash guard: kotlinx reads nested arrays by recursion, and 5,000 of them overflow
     * the stack. The bound is Foundation's own limit, 512 containers, so the two sides drop the
     * same frames — bar a 513th that opens only to close again, which Foundation reads and this
     * does not. Run on a thread with a stack of its own, so the answer does not depend on the
     * test runner's.
     */
    @Test
    fun testNestingIsBoundedAtFoundationsLimit() {
        fun frame(depth: Int, open: String, close: String, inner: String) =
            """{"kind":"error","text":"d$depth","j":""" + open.repeat(depth) + inner + close.repeat(depth) + "}"
        val results = mutableMapOf<String, ServerFrame>()
        val worker = Thread(null, {
            // The top-level object is the first container, so `j` holds `depth` more.
            results["arrays 511"] = FrameParser.parseWs(frame(511, "[", "]", "1"))
            results["arrays 512"] = FrameParser.parseWs(frame(512, "[", "]", "1"))
            results["arrays 512 empty"] = FrameParser.parseWs(frame(512, "[", "]", ""))
            results["objects 511"] = FrameParser.parseWs(frame(511, """{"a":""", "}", "1"))
            results["objects 512"] = FrameParser.parseWs(frame(512, """{"a":""", "}", "1"))
            results["arrays 600"] = FrameParser.parseWs(frame(600, "[", "]", "1"))
            results["arrays 5000"] = FrameParser.parseWs(frame(5000, "[", "]", "1"))
            results["arrays 100000"] = FrameParser.parseWs(frame(100000, "[", "]", "1"))
            results["objects 100000"] = FrameParser.parseWs(frame(100000, """{"a":""", "}", "1"))
            results["unclosed 100000"] = FrameParser.parseWs("""{"kind":"error","text":"x","j":""" + "[".repeat(100000))
            results["in a string"] = FrameParser.parseWs("""{"kind":"error","text":"${"[".repeat(2000)}"}""")
        }, "deep", 4L * 1024 * 1024)
        worker.start()
        worker.join()
        assertEquals(ServerFrame.ServerError("d511"), results["arrays 511"])
        assertEquals(ServerFrame.Ignored, results["arrays 512"])
        assertEquals(ServerFrame.Ignored, results["arrays 512 empty"])
        assertEquals(ServerFrame.ServerError("d511"), results["objects 511"])
        assertEquals(ServerFrame.Ignored, results["objects 512"])
        assertEquals(ServerFrame.Ignored, results["arrays 600"])
        assertEquals(ServerFrame.Ignored, results["arrays 5000"])
        assertEquals(ServerFrame.Ignored, results["arrays 100000"])
        assertEquals(ServerFrame.Ignored, results["objects 100000"])
        assertEquals(ServerFrame.Ignored, results["unclosed 100000"])
        assertEquals(ServerFrame.ServerError("[".repeat(2000)), results["in a string"])
    }

    /**
     * differs: ⚠ a lone surrogate written as an escape — which is what `JSON.stringify` writes
     * for half an emoji a `slice` left behind — fails the whole document in Foundation, so
     * LurkerKit's `JSONTextRepair` rewrites it to U+FFFD first and iOS reads the frame with a
     * U+FFFD in its place (lurker-ios#195). Here the frame is read and the string keeps the lone
     * unit. A valid pair reads the same on both.
     */
    @Test
    fun testALoneSurrogateEscapeIsReadNotDropped() {
        val high = 0xD83D.toChar().toString()
        val low = 0xDC00.toChar().toString()
        assertEquals(ServerFrame.ServerError("lone $high hi"), FrameParser.parseWs("""{"kind":"error","text":"lone ${escaped("d83d")} hi"}"""))
        assertEquals(ServerFrame.ServerError("lone $low lo"), FrameParser.parseWs("""{"kind":"error","text":"lone ${escaped("dc00")} lo"}"""))
        assertEquals(ServerFrame.ServerError("a${high}A"), FrameParser.parseWs("""{"kind":"error","text":"a${escaped("D83D")}${escaped("0041")}"}"""))
        assertEquals(ServerFrame.ServerError("pair ${String(Character.toChars(0x1F600))}"), FrameParser.parseWs("""{"kind":"error","text":"pair ${escaped("d83d")}${escaped("de00")}"}"""))
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#a","events":[{"id":1,"type":"message","nick":"a","text":"half ${escaped("d83d")}"}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertEquals("half $high", frame.messages[0].text)
    }

    /** differs: a trailing comma fails the document here; Foundation reads past it */
    @Test
    fun testATrailingCommaFailsTheFrame() {
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"error","text":"x",}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"error","text":"x","j":[1,]}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"error","text":"x","j":{"a":1,}}"""))
        // Both fail these.
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"error","text":"x",,}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"error","text":"x","j":[,]}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{,"kind":"error","text":"x"}"""))
    }

    /**
     * differs: a repeated key keeps its LAST value here and its first in Foundation — and two
     * keys that are canonically equivalent are two keys here, one there.
     */
    @Test
    fun testARepeatedKeyKeepsTheLastValue() {
        assertEquals(ServerFrame.ServerError("b"), FrameParser.parseWs("""{"kind":"error","text":"a","text":"b"}"""))
        assertEquals(ServerFrame.BacklogComplete, FrameParser.parseWs("""{"kind":"error","kind":"backlog-complete","text":"dup"}"""))
        // Two keys that differ only by a leading mark are two keys, on both sides since
        // lurker-ios#196 (LurkerKit doubles the mark for Foundation to strip).
        assertEquals(ServerFrame.ServerError("a"), FrameParser.parseWs("""{"text":"a","${mark}text":"b","kind":"error"}"""))
    }

    /**
     * differs: a number no `Double` can hold fails the document in Foundation for most of its
     * spellings (`1e400`, 310 digits); here the frame reads and the number is not an integer.
     * Numbers that do fit read the same on both sides, as `JsonTests` pins.
     */
    @Test
    fun testANumberBeyondDoubleStillReadsTheFrame() {
        for (token in listOf("1e400", "-1e400", "1.8e308", "9".repeat(310), "0." + "0".repeat(400) + "1")) {
            assertEquals(ServerFrame.ServerError("x"), FrameParser.parseWs("""{"kind":"error","text":"x","n":$token}"""), token)
        }
        // Read through a field: not a count, so not an integer.
        val frame = FrameParser.parseWs("""{"kind":"read-state","networkId":1,"target":"#a","lastReadId":1e400,"unread":1e400}""")
        assertEquals(ServerFrame.ReadState(networkId = 1, target = "#a", lastReadId = 0, unread = 0, highlights = 0), frame)
    }

    /** every prefix of a real frame is dropped, never thrown */
    @Test
    fun testATruncatedFrameNeverThrows() {
        val whole = """{"kind":"backlog","networkId":1,"target":"#lurker","bufferId":12,"events":[{"id":1,"type":"message","nick":"alice","text":"hi \"there\" ${String(Character.toChars(0x1F600))}","self":false,"time":"2026-08-29T12:00:00.000Z","reactions":[{"nick":"bob","value":"x","self":false}],"replyTo":{"msgid":"m","parent":{"id":7,"nick":"b","type":"message","text":"t"}}}],"hasMoreOlder":false,"joined":true,"unread":3,"lastReadId":42,"speakers":[{"nick":"Alice","lastTime":1784548800000}]}"""
        for (cut in 0 until whole.length) {
            assertEquals(ServerFrame.Ignored, FrameParser.parseWs(whole.substring(0, cut)), "cut at $cut")
        }
        for (garbage in listOf("", " ", "{", "}", "[", "\"", "\\", "nul", "-", "{\"", "null", "true", "3", "\"fragment\"", "[1,2]", "{}", "[]")) {
            assertEquals(ServerFrame.Ignored, FrameParser.parseWs(garbage), garbage)
        }
    }

    /**
     * `Int(String)` in Swift is a sign and ASCII digits, nothing else: the `reactions-sync` keys
     * and a WHOIS `idle`/`logon` are read to that rule, not to `toLongOrNull`'s, which also
     * reads the digits of other scripts.
     */
    @Test
    fun testANumericStringIsReadTheWaySwiftReadsIt() {
        val arabicThree = 0x0663.toChar().toString()
        val frame = FrameParser.parseWs(
            """{"kind":"reactions-sync","messageIds":[1,2],"reactions":{"1":[{"nick":"a","value":"x"}],"+2":[{"nick":"b","value":"y"}],"$arabicThree":[{"nick":"c","value":"z"}]," 4":[{"nick":"d","value":"w"}],"042":[{"nick":"e","value":"v"}],"9223372036854775808":[{"nick":"f","value":"u"}]}}""",
        )
        if (frame !is ServerFrame.ReactionsSync) fail("expected reactionsSync, got $frame")
        assertEquals(setOf(1L, 2L, 42L), frame.reactions.keys)
        fun idle(value: String): Long? {
            val whois = FrameParser.parseWs("""{"kind":"irc","type":"whois_result","networkId":1,"whois":{"nick":"a","idle":$value}}""")
            if (whois !is ServerFrame.WhoisResult) fail("expected whoisResult, got $whois")
            return whois.whois.idleSeconds
        }
        assertEquals(12L, idle("\"+12\""))
        assertEquals(0L, idle("\"-0\""))
        assertEquals(1L, idle("true"), "a boolean bridges to a number, as it does on iOS")
        assertNull(idle("\"$arabicThree\""))
        assertNull(idle("\" 3\""))
        assertNull(idle("\"0x10\""))
        assertNull(idle("\"1e3\""))
        assertNull(idle("\"12abc\""))
        assertNull(idle("\"\""))
        assertNull(idle("\"99999999999999999999\""))
        assertNull(idle("1.5"))
    }

    /** a mode letter is one grapheme cluster, as Swift's `count == 1` is — on both sides */
    @Test
    fun testAModeLetterIsOneGraphemeCluster() {
        val combining = "e" + 0x0301.toChar()
        val flag = String(Character.toChars(0x1F1FA)) + String(Character.toChars(0x1F1F8))
        val frame = FrameParser.parseWs(
            """{"kind":"irc","type":"channel-modes","networkId":1,"target":"#a","modes":"kl","modeParams":{"l":50,"k":"x","xx":"no","$combining":"comb","$flag":"flag","":"empty","f":true,"g":1.5,"h":3.0,"i":null,"\r\n":"crlf"}}""",
        )
        assertEquals(
            ServerFrame.ChannelModes(
                networkId = 1, target = "#a", modes = "kl",
                params = mapOf("l" to "50", "k" to "x", combining to "comb", flag to "flag", "f" to "1", "h" to "3", "\r\n" to "crlf"),
                createdAt = null,
            ),
            frame,
        )
        val spec = FrameParser.parseWs(
            """{"kind":"irc","type":"mode-spec","networkId":1,"target":":server:1","modeSpec":{"list":"b","always":"k","onSet":"l","flags":"i","prefix":[{"mode":"o","symbol":"@"},{"mode":"$combining","symbol":"+"},{"mode":"ab","symbol":"x"},{"mode":"$flag","symbol":"f"}],"maxModes":0,"topicLen":-3}}""",
        )
        if (spec !is ServerFrame.ModeSpec) fail("expected modeSpec, got $spec")
        assertEquals(listOf("o", combining, flag), spec.spec?.prefix?.map { it.mode })
        assertNull(spec.spec?.maxModes, "zero is no limit")
        assertNull(spec.spec?.topicLen, "and so is a negative")
    }

    /**
     * Port note pinned: a WHOIS signon an `Instant` cannot hold reads as absent, where a Swift
     * `Date` would hold it. Everything an IRC server can actually say is well inside the range.
     */
    @Test
    fun testASignonBeyondAnInstantReadsAsAbsent() {
        fun signedOn(logon: String): Instant? {
            val frame = FrameParser.parseWs("""{"kind":"irc","type":"whois_result","networkId":1,"whois":{"nick":"a","logon":$logon}}""")
            if (frame !is ServerFrame.WhoisResult) fail("expected whoisResult, got $frame")
            return frame.whois.signedOn
        }
        assertEquals(Instant.ofEpochSecond(1_784_548_800), signedOn("\"1784548800\""))
        assertEquals(Instant.MAX.epochSecond, signedOn("31556889864403199")?.epochSecond)
        assertEquals(Instant.MIN.epochSecond, signedOn("-31557014167219200")?.epochSecond)
        assertNull(signedOn("31556889864403200"))
        assertNull(signedOn("-31557014167219201"))
        assertNull(signedOn("9223372036854775807"))
    }

    /**
     * `networkId`, `bufferId`, a network or rule id, a port — an `Int` here (PORTING.md, Types)
     * where Swift's is 64-bit. A wire number past 32 bits reads as absent, which for most frames
     * means dropped, and for a buffer frame means the system buffer. SQLite ids are nowhere near.
     */
    @Test
    fun testANetworkIdPast32BitsReadsAsAbsent() {
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"irc","type":"state","networkId":2147483648,"state":"connected"}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"pins-changed","networkId":-2147483649,"pinned":[]}"""))
        val live = FrameParser.parseWs("""{"kind":"irc","id":1,"networkId":2147483648,"target":"#a","type":"message","nick":"a","text":"x"}""")
        if (live !is ServerFrame.Live) fail("expected live, got $live")
        assertNull(live.networkId)
        val roster = FrameParser.parseNetworks("""{"networks":[{"id":2147483647,"name":"fits"},{"id":2147483648,"name":"does not"}]}""")
        if (roster !is ServerFrame.Networks) fail("expected networks, got $roster")
        assertEquals(listOf(2147483647, 0), roster.networks.map { it.id })
        // The 64-bit fields are untouched: a message id, a read pointer, a byte count.
        val frame = FrameParser.parseWs("""{"kind":"read-state","networkId":1,"target":"#a","lastReadId":9007199254740993,"unread":1}""")
        assertEquals(ServerFrame.ReadState(networkId = 1, target = "#a", lastReadId = 9007199254740993L, unread = 1, highlights = 0), frame)
    }

    /**
     * Port note pinned: `lowercase()` applies final sigma where Swift's `lowercased()` does
     * not, so a Greek channel's key and a Greek nick's presence are filed one letter apart from
     * iOS. Whoever looks them up folds the same way, so the two never meet.
     */
    @Test
    fun testFinalSigmaFoldsDifferentlyFromSwift() {
        val odos = "#" + "${0x039F.toChar()}${0x0394.toChar()}${0x039F.toChar()}${0x03A3.toChar()}"
        val config = FrameParser.parseNetworkConfigs(
            """{"networks":[{"id":1,"name":"n","host":"h","channels":[{"name":"$odos","key":"k"}]}]}""",
        )?.firstOrNull()
        val folded = "#" + "${0x03BF.toChar()}${0x03B4.toChar()}${0x03BF.toChar()}${0x03C2.toChar()}"
        assertEquals(mapOf(folded to "k"), config?.channelKeys, "Swift files it under …σ (U+03C3), not …ς")
        assertEquals("k", config?.key(channel = odos))
    }

    /**
     * `parseVerbReply`, `errorMessage` and `jsonObject` have no test of their own in LurkerKit
     * that does not also drive `ChatViewModel`; the wire half of `testVerbReplyCarriesItsData`
     * is pinned here until that one can come over.
     */
    @Test
    fun testAVerbReplyAndTheRestBodiesAreRead() {
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
            """{"kind":"send-result","clientId":"ios-verb-4","ok":false,"error":"refused","data":{"ok":false,"error":"refused","numeric":"482","text":"You're not a channel operator"}}""",
        )
        assertEquals(VerbReply(ok = false, error = "refused", numeric = "482", text = "You're not a channel operator", entries = null), refused?.reply)
        // The error can ride `data` alone, and the numeric can arrive as a number.
        val dataOnly = FrameParser.parseVerbReply("""{"kind":"send-result","clientId":"c","ok":false,"data":{"error":"refused","numeric":482}}""")
        assertEquals(VerbReply(ok = false, error = "refused", numeric = "482"), dataOnly?.reply)
        assertNull(FrameParser.parseVerbReply("""{"kind":"send-result","ok":true}"""), "no clientId, nobody waiting")
        assertNull(FrameParser.parseVerbReply("""{"kind":"error","clientId":"c","text":"x"}"""), "not a send-result")

        assertEquals("must be one of light, dark", FrameParser.errorMessage("""{"error":"must be one of light, dark","key":"appearance.theme"}"""))
        assertNull(FrameParser.errorMessage("""{"error":""}"""))
        assertNull(FrameParser.errorMessage("""{"error":42}"""))
        assertNull(FrameParser.errorMessage("not json"))
        assertEquals(setOf("values"), FrameParser.jsonObject("""{"values":{"a":1}}""")?.keys)
        assertNull(FrameParser.jsonObject("""["values"]"""))
    }

    /**
     * LurkerKit's `testBacklogWithALoneSurrogateKeepsEveryRow`, over the same frame: every row
     * survives on both sides, and the cut row ends in U+FFFD on iOS (`JSONTextRepair`) and in
     * the lone high half the server sent here.
     */
    @Test
    fun testBacklogWithALoneSurrogateKeepsEveryRowAndTheLoneHalf() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,"events":[{"id":1,"type":"message","nick":"alice","text":"cut ${escaped("d83d")}"},{"id":2,"type":"message","nick":"bob","text":"fine"}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("expected backlog, got $frame")
        assertEquals(listOf("cut ${0xD83D.toChar()}", "fine"), frame.messages.map { it.text })
    }

    // Not ported: testBacklogWithALoneSurrogateKeepsEveryRow — it pins `JSONTextRepair`'s
    // output (the lone half read as U+FFFD), a workaround for Foundation's decoder that kotlinx
    // does not need; `testBacklogWithALoneSurrogateKeepsEveryRowAndTheLoneHalf` pins what is read
    // here instead.
}
