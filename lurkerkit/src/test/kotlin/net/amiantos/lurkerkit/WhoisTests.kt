// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.MemberPrefix
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.NickNoteSet
import net.amiantos.lurkerkit.model.WhoisResult
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whois and nick notes end to end (lurker-ios#12): the wire in, the types they build, what the
 * store does with them, and the in-flight bookkeeping that decides whether a lookup can be
 * retried.
 *
 * The marker rules are ported from the web's `stores/whois.test.ts` rather than invented —
 * each was a shipped bug (lurker#818) before it was a rule, and each fails differently.
 */
class WhoisTests {

    /** Its own secure storage and its own defaults, so nothing here touches the app's. */
    private fun viewModel(): ChatViewModel = testViewModel()

    // MARK: - The payload

    @Test
    fun testParsesTheFullReplyUsingIrcFrameworksFieldNames() {
        // Field-for-field the object irc-framework assembles and `ircConnection.ts` forwards
        // untouched. `real_name`, `actual_ip`, `server_info` and `registered_nick` are its
        // spellings, and this is the only place that should know them.
        val frame = FrameParser.parseWs(
            """
            {"kind":"irc","type":"whois_result","networkId":7,"whois":{
              "nick":"Alice","ident":"~alice","hostname":"example.org","real_name":"Alice A",
              "actual_hostname":"gateway.example.org","actual_ip":"198.51.100.4",
              "server":"irc.example.org","server_info":"Example Network","account":"alice",
              "channels":"@#foo +#bar","modes":"+iw","operator":"is an IRC Operator",
              "helpop":"is available for help","bot":"is a bot","registered_nick":"is identified",
              "secure":true,"certfp":"abc123","away":"back later","idle":"345","logon":"1700000000"}}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.WhoisResult) fail("expected a whoisResult, got $frame")
        val (networkId, whois) = frame
        assertEquals(7, networkId)
        assertEquals("Alice", whois.nick)
        assertEquals("~alice", whois.ident)
        assertEquals("example.org", whois.hostname)
        assertEquals("Alice A", whois.realName)
        assertEquals("gateway.example.org", whois.actualHostname)
        assertEquals("198.51.100.4", whois.actualIP)
        assertEquals("irc.example.org", whois.server)
        assertEquals("Example Network", whois.serverInfo)
        assertEquals("alice", whois.account)
        assertEquals("+iw", whois.modes)
        assertEquals("is an IRC Operator", whois.isOperator)
        assertEquals("is available for help", whois.helpop)
        assertEquals("is a bot", whois.bot)
        assertEquals("is identified", whois.registeredNick)
        assertTrue(whois.isSecure)
        assertEquals("abc123", whois.certfp)
        assertEquals("back later", whois.away)
        assertFalse(whois.isNotFound)
    }

    @Test
    fun testIdleAndSignonArriveAsStringsBecauseIrcParametersAreText() {
        // ⚠⚠ The regression this locks: irc-framework assigns `idle`/`logon` straight off
        // `command.params` (user.js:238), and IRC parameters are text. A bare integer read gives
        // null on every real reply, so both rows would silently never render.
        val frame = FrameParser.parseWs(
            """
            {"kind":"irc","type":"whois_result","networkId":1,
             "whois":{"nick":"bob","idle":"345","logon":"1700000000"}}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.WhoisResult) fail("expected a whoisResult")
        assertEquals(345L, frame.whois.idleSeconds)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), frame.whois.signedOn)
    }

    @Test
    fun testIdleAndSignonAlsoAcceptRealJsonNumbers() {
        // A server (or a future irc-framework) that sends them as numbers must work too —
        // the point is that the reader takes either, not that it swapped one guess for another.
        val frame = FrameParser.parseWs(
            """
            {"kind":"irc","type":"whois_result","networkId":1,
             "whois":{"nick":"bob","idle":345,"logon":1700000000}}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.WhoisResult) fail("expected a whoisResult")
        assertEquals(345L, frame.whois.idleSeconds)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), frame.whois.signedOn)
    }

    @Test
    fun testAnAbsentSignonIsNilRatherThanTheEpoch() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","type":"whois_result","networkId":1,"whois":{"nick":"bob"}}""",
        )
        if (frame !is ServerFrame.WhoisResult) fail("expected a whoisResult")
        // 1970 is plausible-looking and wrong; a missing row simply doesn't draw.
        assertNull(frame.whois.signedOn)
        assertNull(frame.whois.idleSeconds)
    }

    @Test
    fun testTheNotFoundMissIsAnOrdinaryReplyCarryingAnError() {
        // ⚠⚠ This does NOT come from ERR_NOSUCHNICK — that numeric produces no whois event at
        // all. irc-framework synthesizes this at RPL_ENDOFWHOIS when nothing filled the cache,
        // which is why it arrives with a nick and nothing else.
        val frame = FrameParser.parseWs(
            """
            {"kind":"irc","type":"whois_result","networkId":1,
             "whois":{"nick":"ghost","error":"not_found"}}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.WhoisResult) fail("expected a whoisResult")
        assertTrue(frame.whois.isNotFound)
        assertEquals("ghost", frame.whois.nick)
    }

    @Test
    fun testHostmaskFillsAMissingHalfWithAStarAndIsNilWithNeither() {
        assertEquals(
            "bob!~bob@example.org",
            WhoisResult(nick = "bob", ident = "~bob", hostname = "example.org").hostmask,
        )
        // Unlike `Member.userhost`, which is fed to the matcher and must refuse a half-mask,
        // this one is for display, where "we know the host but not the ident" is worth showing.
        assertEquals("bob!*@example.org", WhoisResult(nick = "bob", hostname = "example.org").hostmask)
        assertEquals("bob!~bob@*", WhoisResult(nick = "bob", ident = "~bob").hostmask)
        assertNull(WhoisResult(nick = "bob").hostmask)
    }

    // MARK: - The channels line

    @Test
    fun testChannelsIsOneSpaceSeparatedStringNotAnArray() {
        val whois = WhoisResult(nick = "bob", channelsLine = "@#foo +#bar #baz")
        assertEquals(listOf("#foo", "#bar", "#baz"), whois.channels.map { it.name })
        assertEquals(listOf("@", "+", ""), whois.channels.map { it.prefix })
    }

    @Test
    fun testAGreedySigilPeelWouldEatTheChannelSigil() {
        // ⚠⚠ The bug the web client has (`UserProfileModal.vue`, `channelsList`): `&` and `+`
        // are membership glyphs AND channel sigils, so a greedy `[~&@%+]*` turns `@&chan` into
        // `chan` — a channel that doesn't exist, offered as something to tap.
        assertEquals(
            listOf("&chan"),
            WhoisResult(nick = "bob", channelsLine = "@&chan").channels.map { it.name },
        )
        assertEquals(
            listOf("@"),
            WhoisResult(nick = "bob", channelsLine = "@&chan").channels.map { it.prefix },
        )
    }

    @Test
    fun testAnUnprefixedChannelKeepsItsOwnSigil() {
        // `&chan` and `+chan` are channels in their own right (RFC 2811 §2.1). Peeling here
        // would leave `chan`, which names nothing.
        for (name in listOf("&chan", "+chan", "!chan", "#chan", "##anime")) {
            val entry = WhoisResult(nick = "bob", channelsLine = name).channels.firstOrNull()
            assertEquals(name, entry?.name, name)
            assertEquals("", entry?.prefix, name)
        }
    }

    @Test
    fun testSplitPrefersTheLargestPeelThatStillLeavesAChannel() {
        // `+#chan` is ambiguous — voiced in `#chan`, or a channel named `+#chan` — and nothing
        // here can tell without ISUPPORT. Preferring the largest legal peel resolves it the way
        // traffic actually runs, and leaves `+chan` (whose peel isn't legal) alone.
        assertEquals("+", MemberPrefix.splitChannelToken("+#chan")?.prefix)
        assertEquals("#chan", MemberPrefix.splitChannelToken("+#chan")?.name)
        assertEquals("", MemberPrefix.splitChannelToken("+chan")?.prefix)
        assertEquals("+chan", MemberPrefix.splitChannelToken("+chan")?.name)
        // Two glyphs deep, with a sigil-shaped one in the middle.
        assertEquals("~", MemberPrefix.splitChannelToken("~&chan")?.prefix)
        assertEquals("&chan", MemberPrefix.splitChannelToken("~&chan")?.name)
    }

    @Test
    fun testASigilOnlyTokenIsDroppedRatherThanBecomingATappableBlank() {
        assertEquals(listOf("#real"), WhoisResult(nick = "bob", channelsLine = "@ #real").channels.map { it.name })
        assertNull(MemberPrefix.splitChannelToken("@"))
        assertNull(MemberPrefix.splitChannelToken("@@"))
    }

    @Test
    fun testATokenOnAnUnknownChannelTypeIsKeptUnpeeledRatherThanDropped() {
        // No legal peel (nothing left is a channel by this client's CHANTYPES), but a network
        // that uses another one still has real channels there. Showing it unpeeled beats
        // silently hiding it.
        assertEquals("chan", MemberPrefix.splitChannelToken("chan")?.name)
        assertEquals("", MemberPrefix.splitChannelToken("chan")?.prefix)
    }

    @Test
    fun testAnAbsentChannelsLineIsNoChannels() {
        assertTrue(WhoisResult(nick = "bob").channels.isEmpty())
    }

    // MARK: - The frame

    @Test
    fun testWhoisResultSurvivesCarryingNoTarget() {
        // ⚠⚠ The regression that made `/whois` do nothing on this client for its whole life:
        // the reply has no `target`, so below `parseIrc`'s target guard it folded to `Other`
        // and was discarded. This asserts it is recognised *above* that guard.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","type":"whois_result","networkId":3,"whois":{"nick":"bob"}}""",
        )
        if (frame !is ServerFrame.WhoisResult) fail("expected a whoisResult, got $frame")
    }

    @Test
    fun testAReplyWeCannotAddressIsRefused() {
        for (bad in listOf(
            """{"kind":"irc","type":"whois_result","whois":{"nick":"bob"}}""",
            """{"kind":"irc","type":"whois_result","networkId":null,"whois":{"nick":"bob"}}""",
            """{"kind":"irc","type":"whois_result","networkId":1}""",
            """{"kind":"irc","type":"whois_result","networkId":1,"whois":{}}""",
            """{"kind":"irc","type":"whois_result","networkId":1,"whois":{"nick":""}}""",
        )) {
            assertEquals(ServerFrame.Ignored, FrameParser.parseWs(bad), bad)
        }
    }

    // MARK: - The store

    @Test
    fun testAReplyIsCachedUnderTheServersCasingAndFoundUnderAnyOther() {
        val store = LurkerStore()
        store.apply(ServerFrame.WhoisResult(networkId = 1, whois = WhoisResult(nick = "Alice", account = "alice")))
        assertEquals("alice", store.state.whoisResult(networkId = 1, nick = "ALICE")?.account)
        assertEquals("Alice", store.state.whoisResult(networkId = 1, nick = "Alice")?.nick)
        // Network-scoped, like every other per-nick fact here.
        assertNull(store.state.whoisResult(networkId = 2, nick = "Alice"))
    }

    @Test
    fun testAReplyFreesTheInFlightSlot() {
        val store = LurkerStore()
        store.markWhoisPending(networkId = 1, nick = "alice")
        assertTrue(store.state.isWhoisPending(networkId = 1, nick = "alice"))
        store.apply(ServerFrame.WhoisResult(networkId = 1, whois = WhoisResult(nick = "Alice")))
        assertFalse(store.state.isWhoisPending(networkId = 1, nick = "alice"))
    }

    @Test
    fun testANotFoundFreesTheSlotToo() {
        // ⚠⚠ lurker#818 itself. A miss IS an answer; leaving the slot claimed kept the slot
        // held for the session, so reopening that nick declined to retry forever.
        val store = LurkerStore()
        store.markWhoisPending(networkId = 1, nick = "ghost")
        store.apply(ServerFrame.WhoisResult(networkId = 1, whois = WhoisResult(nick = "ghost", error = "not_found")))
        assertFalse(store.state.isWhoisPending(networkId = 1, nick = "ghost"))
        // And the miss is cached, so a screen can render it rather than sitting blank.
        assertEquals(true, store.state.whoisResult(networkId = 1, nick = "ghost")?.isNotFound)
    }

    @Test
    fun testAReplyForOneNickLeavesAnotherLookupPending() {
        val store = LurkerStore()
        store.markWhoisPending(networkId = 1, nick = "alice")
        store.markWhoisPending(networkId = 1, nick = "bob")
        store.apply(ServerFrame.WhoisResult(networkId = 1, whois = WhoisResult(nick = "alice")))
        assertFalse(store.state.isWhoisPending(networkId = 1, nick = "alice"))
        assertTrue(store.state.isWhoisPending(networkId = 1, nick = "bob"))
    }

    @Test
    fun testTheSameNickOnTwoNetworksIsTwoLookups() {
        val store = LurkerStore()
        store.markWhoisPending(networkId = 1, nick = "alice")
        store.apply(ServerFrame.WhoisResult(networkId = 2, whois = WhoisResult(nick = "alice")))
        assertTrue(store.state.isWhoisPending(networkId = 1, nick = "alice"))
    }

    @Test
    fun testDroppingANetworkForgetsItsRepliesNotesAndPendingLookups() {
        val store = LurkerStore()
        store.apply(ServerFrame.WhoisResult(networkId = 1, whois = WhoisResult(nick = "alice")))
        store.apply(ServerFrame.WhoisResult(networkId = 2, whois = WhoisResult(nick = "alice")))
        store.apply(ServerFrame.NickNoteUpdated(networkId = 1, nick = "alice", note = "n1", updatedAt = null))
        store.apply(ServerFrame.NickNoteUpdated(networkId = 2, nick = "alice", note = "n2", updatedAt = null))
        store.markWhoisPending(networkId = 1, nick = "bob")
        store.markWhoisPending(networkId = 2, nick = "bob")

        var next = store.state
        next = next.dropNetwork(1)

        assertNull(next.whoisResult(networkId = 1, nick = "alice"))
        assertNull(next.nickNotes.note(networkId = 1, nick = "alice"))
        // Nothing is coming back to free this one — the connection it was asked over is gone.
        assertFalse(next.isWhoisPending(networkId = 1, nick = "bob"))
        // And the other network is untouched.
        assertNotNull(next.whoisResult(networkId = 2, nick = "alice"))
        assertEquals("n2", next.nickNotes.note(networkId = 2, nick = "alice")?.note)
        assertTrue(next.isWhoisPending(networkId = 2, nick = "bob"))
    }

    // MARK: - Asking

    @Test
    fun testRequestingAWhoisWithNoSocketClaimsNothing() {
        // ⚠⚠ Rule 2 of the marker (lurker#818): claim only if the WHOIS actually left. A slot
        // held for a request that never went out wedges exactly like one that is never freed —
        // no reply is coming. A fresh view model has no socket, so `sendRaw` returns false.
        val model = viewModel()
        model.requestWhois(networkId = 1, nick = "alice")
        assertTrue(model.state.whoisPending.isEmpty())
    }

    @Test
    fun testRequestingAWhoisForNobodyDoesNothing() {
        val model = viewModel()
        for (nobody in listOf("", " ", "\n", "   \t ")) {
            model.requestWhois(networkId = 1, nick = nobody)
        }
        assertTrue(model.state.whoisPending.isEmpty())
    }

    @Test
    fun testAPaddedNickIsKeyedTheWayTheServerWillAnswerIt() {
        // ⚠⚠ The reply names the bare nick, so keying the slot on the padded form would free
        // a slot nobody claimed and leave the claimed one held forever — the wedge again,
        // reachable with no malformed input at all. Asserted at the store, since the send that
        // would claim it needs a socket.
        val store = LurkerStore()
        store.markWhoisPending(networkId = 1, nick = "alice")
        store.apply(ServerFrame.WhoisResult(networkId = 1, whois = WhoisResult(nick = "alice")))
        assertTrue(store.state.whoisPending.isEmpty())
        // And the trimmed spelling is what `requestWhois` would have keyed.
        assertEquals(
            ChatState.whoisKey(networkId = 1, nick = "alice"),
            ChatState.whoisKey(networkId = 1, nick = "  Alice  ".trimmingWhitespacesAndNewlines()),
        )
    }

    @Test
    fun testASocketDropFreesEveryLookupThatWasOutOverIt() {
        // ⚠⚠ The most ordinary route to the wedge: a reconnect between asking and
        // RPL_ENDOFWHOIS. No reply is coming over the socket that closed, so a slot left
        // claimed here would make `requestWhois` refuse that nick for the rest of the session —
        // profile stuck on "waiting…", Refresh inert. `typing` is cleared beside it for the
        // same reason.
        val store = LurkerStore()
        store.markWhoisPending(networkId = 1, nick = "alice")
        store.markWhoisPending(networkId = 2, nick = "bob")
        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertTrue(store.state.whoisPending.isEmpty())
        // Cached replies survive: they're answers we already have, not requests waiting on a
        // socket. The screen re-asks on open anyway.
        store.apply(ServerFrame.WhoisResult(networkId = 1, whois = WhoisResult(nick = "alice")))
        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertNotNull(store.state.whoisResult(networkId = 1, nick = "alice"))
    }

    // MARK: - Nick notes

    @Test
    fun testNotesFoldCaseButKeepTheirStoredCasingForDisplay() {
        val set = NickNoteSet(byNetwork = mapOf(1 to listOf(NickNote(nick = "Alice", note = "lives in Berlin"))))
        assertEquals("lives in Berlin", set.note(networkId = 1, nick = "ALICE")?.note)
        assertEquals("Alice", set.note(networkId = 1, nick = "alice")?.nick)
        assertTrue(set.hasNote(networkId = 1, nick = "alice"))
    }

    @Test
    fun testNotesAreScopedToOneNetwork() {
        // The same nick on two networks may be two people — the server keys them apart too.
        val set = NickNoteSet(byNetwork = mapOf(1 to listOf(NickNote(nick = "alice", note = "n"))))
        assertFalse(set.hasNote(networkId = 2, nick = "alice"))
        assertFalse(set.hasNote(networkId = null, nick = "alice"))
    }

    @Test
    fun testABlankNoteNeverBecomesAnEntry() {
        // An empty note is the server's spelling of "no note" — `set_nick_note` deletes the row
        // rather than storing a blank. A present-but-blank entry would make `hasNote` lie.
        val set = NickNoteSet(
            byNetwork = mapOf(1 to listOf(NickNote(nick = "alice", note = ""), NickNote(nick = "", note = "x")))
        )
        assertFalse(set.hasNote(networkId = 1, nick = "alice"))
        assertNull(set.note(networkId = 1, nick = ""))
    }

    @Test
    fun testApplyingWritesAndClearsOneNoteWithoutDisturbingTheOthers() {
        val set = NickNoteSet(
            byNetwork = mapOf(
                1 to listOf(NickNote(nick = "alice", note = "a"), NickNote(nick = "bob", note = "b")),
            )
        )
        val written = set.applying(networkId = 1, nick = "carol", note = "c", updatedAt = null)
        assertEquals("c", written.note(networkId = 1, nick = "carol")?.note)
        assertEquals("a", written.note(networkId = 1, nick = "alice")?.note)

        // An empty note is the delete — the same frame shape as a write.
        val cleared = written.applying(networkId = 1, nick = "ALICE", note = "", updatedAt = null)
        assertNull(cleared.note(networkId = 1, nick = "alice"))
        assertEquals("b", cleared.note(networkId = 1, nick = "bob")?.note)

        // And the original is untouched — these are replaced, never mutated, which is what
        // makes `===` a valid "did the notes change" test for the screens.
        assertEquals("a", set.note(networkId = 1, nick = "alice")?.note)
        assertNull(set.note(networkId = 1, nick = "carol"))
    }

    @Test
    fun testRewritingANoteReplacesItRatherThanAddingASecondRow() {
        val set = NickNoteSet(byNetwork = mapOf(1 to listOf(NickNote(nick = "alice", note = "old"))))
            .applying(networkId = 1, nick = "ALICE", note = "new", updatedAt = null)
        assertEquals("new", set.note(networkId = 1, nick = "alice")?.note)
        // The server's canonical casing wins, because that's what the echo carries.
        assertEquals("ALICE", set.note(networkId = 1, nick = "alice")?.nick)
    }

    @Test
    fun testRemovingANetworkDropsOnlyItsNotes() {
        val set = NickNoteSet(
            byNetwork = mapOf(
                1 to listOf(NickNote(nick = "alice", note = "a")),
                2 to listOf(NickNote(nick = "alice", note = "b")),
            )
        ).removing(networkId = 1)
        assertNull(set.note(networkId = 1, nick = "alice"))
        assertEquals("b", set.note(networkId = 2, nick = "alice")?.note)
    }

    @Test
    fun testParsesTheNoteUpdateFrame() {
        // ⚠⚠ `"2026-08-29 12:00:00"` — a space, no zone — is what the server actually sends.
        // `user_nick_notes.updated_at` is `DEFAULT (datetime('now'))` and `nickNotes.ts` echoes
        // the column verbatim, unlike the tables declared with an explicit
        // `strftime('%Y-%m-%dT%H:%M:%fZ')`. Feeding this an ISO string is a test that passes
        // over a broken production path — which is what the first cut of it did.
        assertEquals(
            ServerFrame.NickNoteUpdated(
                networkId = 7, nick = "Alice", note = "hi",
                updatedAt = Instant.ofEpochSecond(1_788_004_800),
            ),
            FrameParser.parseWs(
                """
                {"kind":"nick-note-updated","networkId":7,"nick":"Alice","note":"hi",
                 "updatedAt":"2026-08-29 12:00:00"}
                """.trimIndent(),
            ),
        )
        // The clear arrives as the same frame with an empty note and no timestamp.
        assertEquals(
            ServerFrame.NickNoteUpdated(networkId = 7, nick = "Alice", note = "", updatedAt = null),
            FrameParser.parseWs("""{"kind":"nick-note-updated","networkId":7,"nick":"Alice","note":""}"""),
        )
    }

    @Test
    fun testANoteAboutNobodyIsRefused() {
        for (bad in listOf(
            """{"kind":"nick-note-updated","nick":"alice","note":"n"}""",
            """{"kind":"nick-note-updated","networkId":null,"nick":"alice","note":"n"}""",
            """{"kind":"nick-note-updated","networkId":7,"note":"n"}""",
            """{"kind":"nick-note-updated","networkId":7,"nick":"","note":"n"}""",
        )) {
            assertEquals(ServerFrame.Ignored, FrameParser.parseWs(bad), bad)
        }
    }

    @Test
    fun testAFrameWithNoReadableNoteIsRefusedRatherThanTreatedAsAClear() {
        // ⚠⚠ An empty note is a DELETE, so folding an absent or non-string `note` to `""`
        // would let a malformed frame destroy something the user typed. Absent is not a
        // statement that the note is empty. The asymmetry settles it: refusing costs a missed
        // update, accepting costs the note.
        for (bad in listOf(
            """{"kind":"nick-note-updated","networkId":7,"nick":"alice"}""",
            """{"kind":"nick-note-updated","networkId":7,"nick":"alice","note":null}""",
            """{"kind":"nick-note-updated","networkId":7,"nick":"alice","note":42}""",
        )) {
            assertEquals(ServerFrame.Ignored, FrameParser.parseWs(bad), bad)
        }
        // The real clear still lands — `""` is present.
        assertEquals(
            ServerFrame.NickNoteUpdated(networkId = 7, nick = "alice", note = "", updatedAt = null),
            FrameParser.parseWs("""{"kind":"nick-note-updated","networkId":7,"nick":"alice","note":""}"""),
        )
    }

    @Test
    fun testTheNoteUpdateFramePatchesOneNick() {
        val store = LurkerStore()
        store.apply(ServerFrame.NickNoteUpdated(networkId = 1, nick = "alice", note = "a", updatedAt = null))
        store.apply(ServerFrame.NickNoteUpdated(networkId = 1, nick = "bob", note = "b", updatedAt = null))
        store.apply(ServerFrame.NickNoteUpdated(networkId = 1, nick = "alice", note = "", updatedAt = null))
        assertNull(store.state.nickNotes.note(networkId = 1, nick = "alice"))
        assertEquals("b", store.state.nickNotes.note(networkId = 1, nick = "bob")?.note)
    }

    @Test
    fun testTheSnapshotSeedsNotesAndDropsUnusableRows() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"snapshot","networks":[{"networkId":7,"state":"connected","nick":"me","channels":[],
             "nickNotes":[{"nick":"Alice","note":"lives in Berlin","updatedAt":"2026-08-29 12:00:00"},
                          {"nick":"","note":"x"},{"nick":"bob","note":""}]}]}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.Snapshot) fail("expected a snapshot")
        assertEquals(listOf("Alice"), frame.networks.firstOrNull()?.nickNotes?.map { it.nick })
        assertEquals(
            Instant.ofEpochSecond(1_788_004_800),
            frame.networks.firstOrNull()?.nickNotes?.firstOrNull()?.updatedAt,
        )
    }

    @Test
    fun testTheSnapshotReplacesNotesWholesale() {
        // A note cleared on the web while this device was away has to be GONE here, not survive
        // as a leftover the profile screen keeps showing.
        val store = LurkerStore()
        store.apply(ServerFrame.NickNoteUpdated(networkId = 7, nick = "alice", note = "stale", updatedAt = null))
        store.apply(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 7, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        assertNull(store.state.nickNotes.note(networkId = 7, nick = "alice"))
    }
}
