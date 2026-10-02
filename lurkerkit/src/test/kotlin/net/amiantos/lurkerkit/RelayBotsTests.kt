// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.ParsedInput
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
import kotlin.test.fail

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

    @Test
    fun testParsesTheSnapshotAndUpdateFrames() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"snapshot","networks":[{"networkId":7,"state":"connected","nick":"me","channels":[],
             "relayBots":[{"nick":"bridge","pattern":"{nick}: {message}"},{"nick":"","pattern":"x"}]}]}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.Snapshot) fail("expected a snapshot")
        // The nick-less row is dropped rather than becoming a mark keyed on the empty string,
        // which would then "match" every nick-less line the client renders.
        assertEquals(
            listOf(RelayBot(nick = "bridge", pattern = "{nick}: {message}")),
            frame.networks.firstOrNull()?.relayBots,
        )

        assertEquals(
            ServerFrame.RelayBotUpdated(networkId = 7, nick = "Bridge", marked = true, pattern = "p"),
            FrameParser.parseWs("""{"kind":"relay-bot-updated","networkId":7,"nick":"Bridge","marked":true,"pattern":"p"}"""),
        )
        // A mark with no network or no nick addresses nothing, so it's refused rather than folded
        // onto network 0 or the empty nick.
        for (bad in listOf(
            """{"kind":"relay-bot-updated","nick":"bridge","marked":true}""",
            """{"kind":"relay-bot-updated","networkId":null,"nick":"bridge","marked":true}""",
            """{"kind":"relay-bot-updated","networkId":7,"marked":true}""",
            """{"kind":"relay-bot-updated","networkId":7,"nick":"","marked":true}""",
        )) {
            assertEquals(ServerFrame.Ignored, FrameParser.parseWs(bad), bad)
        }
    }

    // MARK: - /relay

    // Port note: `CommandParser.parse` takes the expiry's formatter here (see
    // `IgnoreRule.summary`); no `/relay` line uses it.
    private fun effects(input: String, relayBots: RelayBotSet? = RelayBotSet.empty): List<CommandEffect> {
        val parsed = CommandParser.parse(
            input, networkId = 1, target = "#chan", relayBots = relayBots, formatted = { it.toString() },
        )
        if (parsed !is ParsedInput.Command) fail("expected a command for $input")
        return parsed.effects
    }

    @Test
    fun testRelayAddAndRemoveAskTheServer() {
        assertEquals(
            listOf<CommandEffect>(
                CommandEffect.SetRelayBot(
                    networkId = 1, nick = "bridge", marked = true, pattern = "",
                    receipt = "marked bridge as a relay bot.",
                ),
            ),
            effects("/relay add bridge"),
        )
        assertEquals(
            listOf<CommandEffect>(
                CommandEffect.SetRelayBot(
                    networkId = 1, nick = "bridge", marked = false, pattern = "",
                    receipt = "unmarked bridge as a relay bot.",
                ),
            ),
            effects("/relay remove bridge"),
        )
    }

    /**
     * ⚠ The pattern is the raw remainder of the line, spaces and all — running it through a
     * tokenizer would hand the server a template that no longer matches anything.
     */
    @Test
    fun testACustomPatternSurvivesWithItsSpacing() {
        assertEquals(
            listOf<CommandEffect>(
                CommandEffect.SetRelayBot(
                    networkId = 1, nick = "bridge", marked = true,
                    pattern = "<{nick}>  [{source}] {message}",
                    receipt = "marked bridge as a relay bot (pattern: <{nick}>  [{source}] {message}).",
                ),
            ),
            effects("/relay add bridge <{nick}>  [{source}] {message}"),
        )
        // Quoting isn't required, but one surrounding pair is peeled if it's there.
        val pattern = (effects("/relay add b \"{nick}: {message}\"").firstOrNull() as? CommandEffect.SetRelayBot)?.pattern
            ?: fail("expected a mark")
        assertEquals("{nick}: {message}", pattern)
    }

    @Test
    fun testRelayListsTheMarksOnThisNetwork() {
        val set = RelayBotSet(
            byNetwork = mapOf(
                1 to listOf(RelayBot(nick = "zbot"), RelayBot(nick = "abot", pattern = "{nick}: {message}")),
                2 to listOf(RelayBot(nick = "elsewhere")),
            ),
        )
        val text = (effects("/relay", relayBots = set).firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a listing")
        assertEquals(
            "relay bots (2):\n" +
                "  abot  — {nick}: {message}\n" +
                "  zbot",
            text,
        )
    }

    /**
     * An empty listing has to carry the way out of it: someone typing `/relay` into a channel a
     * bridge is talking in is asking how to fix what they're looking at.
     */
    @Test
    fun testAnEmptyListingSaysHowToMarkOne() {
        val text = (effects("/relay").firstOrNull() as? CommandEffect.Info)?.text ?: fail("expected a listing")
        assertTrue(text.contains("/relay add <nick>"), text)
    }

    /**
     * A listing built before the connect burst finished would be a confident "none" for an
     * account that has several — the same trap `/ignore` avoids the same way.
     */
    @Test
    fun testAListingIsWithheldUntilTheMarksHaveArrived() {
        val text = (effects("/relay", relayBots = null).firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a note")
        assertTrue(text.contains("haven't arrived yet"), text)
        // Marking is NOT gated on the same thing: the mark is fine, it's only our ability to
        // describe the list that's missing.
        assertEquals(1, effects("/relay add bridge", relayBots = null).size)
    }

    /**
     * ⚠ A pattern that can't compile marks the bot and then re-attributes nothing, forever —
     * `templates` deliberately won't fall back to the built-ins for one. Nothing downstream
     * reports that, and `/relay list` shows the mark as if it were working, so the refusal has to
     * happen at the only moment anyone is looking.
     */
    @Test
    fun testAPatternThatCannotCompileIsRefusedRatherThanMarked() {
        // The realistic way to get one: forgetting the braces.
        val text = (effects("/relay add bot [Discord] <nick> message").firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a refusal")
        assertTrue(text.contains("{nick} and {message}"), text)
        assertEquals(1, effects("/relay add bot [Discord] <nick> message").size, "nothing may reach the wire")
        // A pattern that DOES compile still goes through, and so does no pattern at all.
        assertEquals(1, effects("/relay add bot <{nick}> {message}").size)
        if (effects("/relay add bot <{nick}> {message}").firstOrNull() is CommandEffect.Info) {
            fail("a valid pattern must not be refused")
        }
    }

    @Test
    fun testUnusableRelayInputNeverReachesTheWire() {
        for (input in listOf("/relay add", "/relay remove", "/relay wat")) {
            val text = (effects(input).firstOrNull() as? CommandEffect.Info)?.text
                ?: fail("expected a note for $input")
            assertTrue(text.startsWith("/relay:"), text)
        }
    }

    /**
     * One surrounding pair of quotes is peeled; a pair that isn't one is left alone. Peeling it
     * would produce a template that still compiles — so it would be stored and marked with a
     * confident receipt — while matching something the user never wrote.
     */
    @Test
    fun testUnquoteOnlyPeelsAnActualQuotedRun() {
        fun pattern(input: String): String? =
            (effects(input).firstOrNull() as? CommandEffect.SetRelayBot)?.pattern
        assertEquals("{nick}: {message}", pattern("/relay add b \"{nick}: {message}\""))
        assertEquals(
            "\"{nick}\" said \"{message}\"",
            pattern("/relay add b \"{nick}\" said \"{message}\""),
        )
    }

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

    /**
     * A mark is per-(network, nick), so there is no answer to `/relay` in the system buffer — and
     * the generic network gate is what has to say so, rather than the command writing a mark on
     * some network it picked.
     */
    @Test
    fun testRelayNeedsANetwork() {
        val parsed = CommandParser.parse("/relay", networkId = null, target = "", formatted = { it.toString() })
        val text = ((parsed as? ParsedInput.Command)?.effects?.firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a note")
        assertTrue(text.contains("needs an active network"), text)
    }

    // Waiting on LurkerStore: testTheSnapshotSeedsMarksAndReplacesThemWholesale,
    // testTheUpdateFramePatchesOneNick

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
