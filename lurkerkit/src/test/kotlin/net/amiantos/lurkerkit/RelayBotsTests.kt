// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageGrouping
import net.amiantos.lurkerkit.model.RelayBot
import net.amiantos.lurkerkit.model.RelayBotSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Relay-bot marks end to end (lurker#277): the wire in, the set they build, what they do to a
 * list of messages, and the `/relay` verb that asks for one.
 */
class RelayBotsTests {

    private fun message(
        id: Long,
        nick: String,
        text: String,
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
    ): Message =
        Message(id = id, type = type, nick = nick, text = text, isSelf = isSelf)

    // MARK: - The set

    @Test
    fun testMarksFoldCaseButKeepTheirStoredCasingForDisplay() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "RelayBot"))))
        assertTrue(set.isRelay(networkId = 1, nick = "relaybot"))
        assertTrue(set.isRelay(networkId = 1, nick = "RELAYBOT"))
        assertEquals(listOf("RelayBot"), set.listing(1).map { it.nick })
    }

    @Test
    fun testMarksAreScopedToOneNetwork() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge"))))
        assertTrue(set.isRelay(networkId = 1, nick = "bridge"))
        assertFalse(set.isRelay(networkId = 2, nick = "bridge"))
        // Null is the app-scoped system buffer, where no network's marks are in scope.
        assertFalse(set.isRelay(networkId = null, nick = "bridge"))
    }

    @Test
    fun testApplyingSetsAndClearsOneMarkWithoutDisturbingTheOthers() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "a"), RelayBot(nick = "b"))))
        val marked = set.applying(networkId = 1, nick = "c", marked = true, pattern = "{nick}: {message}")
        assertEquals(listOf("a", "b", "c"), marked.listing(1).map { it.nick })
        assertEquals("{nick}: {message}", marked.bot(networkId = 1, nick = "c")?.pattern)

        val cleared = marked.applying(networkId = 1, nick = "A", marked = false, pattern = "")
        assertEquals(listOf("b", "c"), cleared.listing(1).map { it.nick })
        // And the original is untouched — these are replaced, never mutated, which is what makes
        // `===` a valid "did the marks change" test for the screens.
        assertEquals(listOf("a", "b"), set.listing(1).map { it.nick })
    }

    @Test
    fun testRemarkingReplacesThePatternRatherThanAddingASecondRow() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bot", pattern = "old"))))
            .applying(networkId = 1, nick = "BOT", marked = true, pattern = "{nick}> {message}")
        assertEquals(1, set.listing(1).size)
        assertEquals("{nick}> {message}", set.bot(networkId = 1, nick = "bot")?.pattern)
        // The server's canonical casing wins, because that's what the echo carries.
        assertEquals("BOT", set.listing(1).firstOrNull()?.nick)
    }

    // MARK: - Re-attribution

    @Test
    fun testReattributesAMarkedBotsLines() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge"))))
        val rows = set.reattributing(
            listOf(
                message(1, nick = "bridge", text = "[Discord] <alice> hello"),
                message(2, nick = "carol", text = "not from the bridge"),
            ),
            networkId = 1,
        )
        assertEquals("alice", rows[0].nick)
        assertEquals("hello", rows[0].text)
        assertEquals("bridge", rows[0].relayBot)
        assertEquals("Discord", rows[0].relaySource)
        // Same line, shown differently: the id survives, so a bookmark or a jump still finds it.
        assertEquals(1L, rows[0].id)
        assertEquals("carol", rows[1].nick)
        assertNull(rows[1].relayBot)
    }

    @Test
    fun testLeavesALineWhoseEnvelopeDoesNotParse() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge"))))
        val rows = set.reattributing(
            listOf(message(1, nick = "bridge", text = "connected to the bridge network")),
            networkId = 1,
        )
        assertEquals("bridge", rows[0].nick)
        assertEquals("connected to the bridge network", rows[0].text)
        assertNull(rows[0].relayBot)
    }

    /**
     * Relays bridge speech as PRIVMSG. Re-attributing an action or a notice would tangle with the
     * special body rendering those get, and your own lines aren't a bridge whatever they look
     * like.
     */
    @Test
    fun testOnlyPlainMessagesFromSomeoneElseAreReattributed() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge"))))
        val rows = set.reattributing(
            listOf(
                message(1, nick = "bridge", text = "[X] <alice> hi", type = EventType.Action),
                message(2, nick = "bridge", text = "[X] <alice> hi", type = EventType.Notice),
                message(3, nick = "bridge", text = "[X] <alice> hi", isSelf = true),
            ),
            networkId = 1,
        )
        assertEquals(listOf<String?>("bridge", "bridge", "bridge"), rows.map { it.nick })
        assertTrue(rows.all { it.relayBot == null })
    }

    @Test
    fun testAnUnmarkedNetworkOrAnEmptySetIsAPassthrough() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge"))))
        val line = listOf(message(1, nick = "bridge", text = "[X] <alice> hi"))
        assertNull(set.reattributing(line, networkId = 2)[0].relayBot)
        assertNull(set.reattributing(line, networkId = null)[0].relayBot)
        assertNull(RelayBotSet.empty.reattributing(line, networkId = 1)[0].relayBot)
    }

    @Test
    fun testEachBotUsesItsOwnPattern() {
        val set = RelayBotSet(
            byNetwork = mapOf(
                1 to listOf(RelayBot(nick = "a"), RelayBot(nick = "b", pattern = "{nick} :: {message}")),
            )
        )
        val rows = set.reattributing(
            listOf(
                message(1, nick = "a", text = "<alice> one"),
                message(2, nick = "b", text = "bob :: two"),
                // `b`'s custom pattern REPLACES the defaults rather than adding to them, so the
                // default shape no longer parses for it.
                message(3, nick = "b", text = "<carol> three"),
            ),
            networkId = 1,
        )
        assertEquals(listOf<String?>("alice", "bob", "b"), rows.map { it.nick })
    }

    // MARK: - Chained bridges (lurker#801)

    /**
     * The reported line, verbatim from a real corpus: `nR` bridges the IRC-nERDs network, where
     * a bot nicked `|` is itself bridging Matrix/OFTC.
     */
    private val nested =
        "\u0002[IRC-nERDs]\u0002 <~\u000306|\u0003> \u000313<yrdsb3222[m]/OFTC> \u000fIs their a discord"

    @Test
    fun testStopsAtTheIntermediateBridgeWhenOnlyTheOuterBotIsMarked() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "nR"))))
        val rows = set.reattributing(listOf(message(1, nick = "nR", text = nested)), networkId = 1)
        assertEquals("|", rows[0].nick)
        assertEquals("IRC-nERDs", rows[0].relaySource)
        assertEquals("\u000313<yrdsb3222[m]/OFTC> \u000fIs their a discord", rows[0].text)
    }

    @Test
    fun testReachesTheRealSpeakerOnceTheIntermediateBridgeIsMarkedToo() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "nR"), RelayBot(nick = "|"))))
        val rows = set.reattributing(listOf(message(1, nick = "nR", text = nested)), networkId = 1)
        assertEquals("yrdsb3222[m]/OFTC", rows[0].nick)
        assertEquals("\u000fIs their a discord", rows[0].text)
        // The outer tag survives the hops inside it, and `via` stays the real IRC entity.
        assertEquals("IRC-nERDs", rows[0].relaySource)
        assertEquals("nR", rows[0].relayBot)
    }

    /**
     * Quoting looks exactly like an envelope, which is why every hop needs a mark of its own.
     * Real line: raah, on efnet, quoting ultros.
     */
    @Test
    fun testLeavesAQuotedNickAlone() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "nR"), RelayBot(nick = "|"))))
        val quote = "\u0002[efnet]\u0002 <+\u000303raah\u0003> <\u000310ultros\u0003> blasphemer! and a heretic!"
        val rows = set.reattributing(listOf(message(1, nick = "nR", text = quote)), networkId = 1)
        assertEquals("raah", rows[0].nick)
    }

    @Test
    fun testFollowsAThreeDeepChainAndPrefersTheInnermostSourceTag() {
        val set = RelayBotSet(
            byNetwork = mapOf(1 to listOf(RelayBot(nick = "DeltaCharlie"), RelayBot(nick = "nR")))
        )
        val rows = set.reattributing(
            listOf(message(1, nick = "DeltaCharlie", text = "<nR> [rizon] <Nsane> Looks like the sequel bombed")),
            networkId = 1,
        )
        assertEquals("Nsane", rows[0].nick)
        assertEquals("rizon", rows[0].relaySource)
        assertEquals("Looks like the sequel bombed", rows[0].text)
    }

    /**
     * Load-bearing rather than incidental: the next hop is looked up by the nick the last one
     * returned, so a prefix left on it would miss the marks and stop the chain a hop short.
     */
    @Test
    fun testStripsAMembershipPrefixOnAnInnerHopToo() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "sscout"), RelayBot(nick = "DOMF"))))
        val rows = set.reattributing(
            listOf(message(1, nick = "sscout", text = "<+DOMF> <+Nelluk> many ghosts living and dead abound")),
            networkId = 1,
        )
        assertEquals("Nelluk", rows[0].nick)
        assertEquals("many ghosts living and dead abound", rows[0].text)
    }

    /**
     * The case that actually pins the direction of the coalesce: the tests either side of it
     * carry a tag on one hop only, and pass whichever way it's written.
     */
    @Test
    fun testPrefersTheInnermostSourceWhenEveryHopCarriesOne() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "outer"), RelayBot(nick = "bridge"))))
        val rows = set.reattributing(
            listOf(message(1, nick = "outer", text = "[A] <bridge> [B] <alice> hi")),
            networkId = 1,
        )
        assertEquals("alice", rows[0].nick)
        assertEquals("B", rows[0].relaySource)
    }

    @Test
    fun testAnInnerHopUsesItsOwnCustomTemplate() {
        val set = RelayBotSet(
            byNetwork = mapOf(
                1 to listOf(
                    RelayBot(nick = "outer"),
                    RelayBot(nick = "bridge", pattern = "{source} » {nick} » {message}"),
                ),
            )
        )
        val rows = set.reattributing(
            listOf(message(1, nick = "outer", text = "[net] <bridge> matrix » alice » hey")),
            networkId = 1,
        )
        assertEquals("alice", rows[0].nick)
        assertEquals("matrix", rows[0].relaySource)
        assertEquals("hey", rows[0].text)
    }

    /** A marked hop speaking in its own voice ends the chain where it stands, still attributed. */
    @Test
    fun testEndsTheChainWhenAMarkedHopSaysSomethingOfItsOwn() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "outer"), RelayBot(nick = "bridge"))))
        val rows = set.reattributing(
            listOf(message(1, nick = "outer", text = "[net] <bridge> reconnected to matrix")),
            networkId = 1,
        )
        assertEquals("bridge", rows[0].nick)
        assertEquals("net", rows[0].relaySource)
        assertEquals("reconnected to matrix", rows[0].text)
    }

    @Test
    fun testTerminatesOnABotWhoseEnvelopeNamesItself() {
        val set = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "loop"))))
        val rows = set.reattributing(
            listOf(message(1, nick = "loop", text = "<loop> <loop> <loop> <loop> <loop> <loop> <loop> done")),
            networkId = 1,
        )
        // Depth-capped rather than looping — it stops mid-chain, still attributed.
        assertEquals("loop", rows[0].nick)
        assertEquals("<loop> <loop> <loop> done", rows[0].text)
    }

    // MARK: - The wire

    // MARK: - /relay

    // MARK: - A bridged speaker is not the local one with the same name

    /**
     * ⚠ Anyone on a bridged platform can pick a nick that matches an IRC regular. Grouping on
     * nick alone folded the bridged line into the local speaker's run, which renders it headerless
     * under their name and drops the very tag that would have said otherwise.
     */
    @Test
    fun testARelayedSpeakerDoesNotJoinALocalNamesakesRun() {
        val local = message(1, nick = "alice", text = "from the channel")
        val bridged = message(2, nick = "bridge", text = "[Discord] <alice> from discord")
        val rows = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge"))))
            .reattributing(listOf(local, bridged), networkId = 1)
        assertEquals("alice", rows[1].nick, "precondition: both rows read as alice")
        assertFalse(MessageGrouping.continuesRun(rows[1], previous = rows[0]))
    }

    @Test
    fun testTwoLinesFromTheSameBridgedSpeakerDoGroup() {
        val rows = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge")))).reattributing(
            listOf(
                message(1, nick = "bridge", text = "[Discord] <alice> one"),
                message(2, nick = "bridge", text = "[Discord] <alice> two"),
                // Same name, same bot, DIFFERENT platform — a different person.
                message(3, nick = "bridge", text = "[Telegram] <alice> three"),
            ),
            networkId = 1,
        )
        assertTrue(MessageGrouping.continuesRun(rows[1], previous = rows[0]))
        assertFalse(MessageGrouping.continuesRun(rows[2], previous = rows[1]))
    }

    // Waiting on LurkerStore, ServerFrame, NetworkSnapshot: testTheSnapshotSeedsMarksAndReplacesThemWholesale,
    // testTheUpdateFramePatchesOneNick
    //
    // Waiting on FrameParser, ServerFrame: testParsesTheSnapshotAndUpdateFrames
    //
    // Waiting on CommandParser, CommandEffect (and the private `effects` helper):
    // testRelayAddAndRemoveAskTheServer, testACustomPatternSurvivesWithItsSpacing,
    // testRelayListsTheMarksOnThisNetwork, testAnEmptyListingSaysHowToMarkOne,
    // testAListingIsWithheldUntilTheMarksHaveArrived,
    // testAPatternThatCannotCompileIsRefusedRatherThanMarked, testUnusableRelayInputNeverReachesTheWire,
    // testUnquoteOnlyPeelsAnActualQuotedRun, testRelayNeedsANetwork

    // Port-only: equality. LurkerKit's `RelayBotSet` is a reference type compared by identity;
    // here it sits in published state and compares every mark it holds (see its port note).

    @Test
    fun testSetsHoldingTheSameMarksAreEqual() {
        val one = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "a"), RelayBot(nick = "b", pattern = "p"))))
        // Built another way round, and through `applying`: the same marks all the same.
        val two = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "b", pattern = "p"))))
            .applying(networkId = 1, nick = "a", marked = true, pattern = "")
        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        // A bucket emptied by a clear is the same as one that never existed.
        assertEquals(
            RelayBotSet.empty,
            RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "a"))))
                .applying(networkId = 1, nick = "A", marked = false, pattern = ""),
        )

        // And every field of a mark counts: its pattern, its stored casing, its network.
        assertNotEquals(one, one.applying(networkId = 1, nick = "b", marked = true, pattern = "q"))
        assertNotEquals(one, one.applying(networkId = 1, nick = "B", marked = true, pattern = "p"))
        assertNotEquals(
            RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "a")))),
            RelayBotSet(byNetwork = mapOf(2 to listOf(RelayBot(nick = "a")))),
        )
    }
}
