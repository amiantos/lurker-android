// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.MemberPrefix
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.NickNoteSet
import net.amiantos.lurkerkit.model.WhoisResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // Waiting on LurkerStore, ChatState, ChatViewModel (and the `viewModel()` helper):
    // testAReplyIsCachedUnderTheServersCasingAndFoundUnderAnyOther, testAReplyFreesTheInFlightSlot,
    // testANotFoundFreesTheSlotToo, testAReplyForOneNickLeavesAnotherLookupPending,
    // testTheSameNickOnTwoNetworksIsTwoLookups,
    // testDroppingANetworkForgetsItsRepliesNotesAndPendingLookups,
    // testRequestingAWhoisWithNoSocketClaimsNothing, testRequestingAWhoisForNobodyDoesNothing,
    // testAPaddedNickIsKeyedTheWayTheServerWillAnswerIt,
    // testASocketDropFreesEveryLookupThatWasOutOverIt, testTheNoteUpdateFramePatchesOneNick,
    // testTheSnapshotReplacesNotesWholesale
}
