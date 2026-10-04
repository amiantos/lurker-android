// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.CommandRegistry
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.PrefixMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the slash-command parser to the web client's `handleCommand` dispatcher: the same
 * verbs translate to the same wire effects, the `//` escape sends a literal slash, the
 * system buffer gates network commands, and an unknown verb falls through to `raw`.
 */
class CommandParserTests {

    /**
     * Port note: `CommandParser.parse` takes the expiry's formatter here, where LurkerKit
     * formats it itself (see `IgnoreRule.summary`); every parse in this file hands it this one.
     */
    private val formatted: (Instant) -> String = { it.toString() }

    /** A command issued from a channel buffer on network 1. */
    private fun parse(input: String, networkId: Int? = 1, target: String = "#chan"): ParsedInput =
        CommandParser.parse(input, networkId = networkId, target = target, formatted = formatted)

    /** The effects of a command (fails the test if the input parsed as a message/notCommand). */
    private fun effects(input: String, networkId: Int? = 1, target: String = "#chan"): List<CommandEffect> {
        val parsed = parse(input, networkId = networkId, target = target)
        if (parsed !is ParsedInput.Command) fail("expected a command from $input")
        return parsed.effects
    }

    /**
     * The effects of a command typed in #chan on network 1, with the context the call site
     * supplies: the network's mode vocabulary and which buffers it has open.
     */
    private fun context(
        input: String,
        target: String = "#chan",
        modeSpec: ModeSpec? = null,
        hasBuffer: (String) -> Boolean = { false },
    ): List<CommandEffect> {
        val parsed = CommandParser.parse(
            input, networkId = 1, target = target, modeSpec = modeSpec, hasBuffer = hasBuffer, formatted = formatted,
        )
        if (parsed !is ParsedInput.Command) fail("expected a command from $input")
        return parsed.effects
    }

    /** A network's vocabulary with the given list modes and MODES limit. */
    private fun spec(list: String = "beI", maxModes: Int? = 3): ModeSpec =
        ModeSpec(
            list = list, always = "k", onSet = "l", flags = "imnst",
            prefix = listOf(PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+")),
            maxModes = maxModes, topicLen = null,
        )

    private fun isInfo(effects: List<CommandEffect>): Boolean =
        effects.size == 1 && effects[0] is CommandEffect.Info

    private fun raws(vararg lines: String): List<CommandEffect> = lines.map { CommandEffect.Raw(line = it) }

    // MARK: - Plain text vs commands

    @Test
    fun testPlainTextIsAMessage() {
        assertEquals(ParsedInput.Message("hello there"), parse("hello there"))
    }

    @Test
    fun testDoubleSlashEscapesToALiteralMessage() {
        // `//foo` sends the literal `/foo` — one slash stripped — so you can start a line with
        // a slash without invoking a command.
        assertEquals(ParsedInput.Message("/slap me"), parse("//slap me"))
    }

    @Test
    fun testNonCommandInSystemBufferIsFlagged() {
        assertEquals(ParsedInput.NotCommand, parse("hello", networkId = null, target = ":system:"))
    }

    @Test
    fun testBareSlashNudgesRatherThanEmittingAnEmptyRawLine() {
        // A lone `/` must not reach the raw fallback and put "" on the wire.
        if (effects("/").firstOrNull() !is CommandEffect.Info) fail("expected a nudge")
    }

    @Test
    fun testEscapedMessageInSystemBufferHasNowhereToGo() {
        // `//x` in the system buffer nudges rather than silently dropping (no network to send to).
        assertEquals(ParsedInput.NotCommand, parse("//hello", networkId = null, target = ":system:"))
        // But in a real buffer it's a literal message with the slash stripped.
        assertEquals(ParsedInput.Message("/hello"), parse("//hello"))
    }

    // MARK: - Messaging

    @Test
    fun testMeIsAnAction() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Action(target = "#chan", text = "waves")), effects("/me waves"))
    }

    @Test
    fun testMePreservesInteriorSpacing() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Action(target = "#chan", text = "waves   slowly")),
            effects("/me waves   slowly"),
        )
    }

    @Test
    fun testMeFollowedByANewlineSendsOnlyTheText() {
        // lurker-ios#197: a multi-line paste or a shift-return can put a newline, not a space,
        // after the verb. The web's `trim()` drops it; the action must not start with it.
        assertEquals(listOf<CommandEffect>(CommandEffect.Action(target = "#chan", text = "waves")), effects("/me\nwaves"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Action(target = "#chan", text = "waves  slowly")),
            effects("/me\n waves  slowly\n"),
        )
    }

    @Test
    fun testABodyAfterAFirstArgumentDropsALeadingNewline() {
        // The same trim, one token in: `/notice bob⏎hi` must not send "\nhi".
        assertEquals(effects("/notice bob hi"), effects("/notice bob\nhi"))
    }

    @Test
    fun testEmptyMeIsANoOp() {
        assertEquals(emptyList(), effects("/me"))
    }

    @Test
    fun testSlapFillsTheTroutLine() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Action(target = "#chan", text = "slaps bob around a bit with a large trout")),
            effects("/slap bob"),
        )
    }

    @Test
    fun testMsgSendsThenActivates() {
        assertEquals(
            listOf(CommandEffect.Send(target = "bob", text = "hey there"), CommandEffect.Activate(target = "bob")),
            effects("/msg bob hey there"),
        )
    }

    @Test
    fun testMsgWithNoBodyOnlyActivates() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Activate(target = "bob")), effects("/msg bob"))
    }

    @Test
    fun testQueryIsAnAliasOfMsg() {
        assertEquals(
            listOf(CommandEffect.Send(target = "bob", text = "hi"), CommandEffect.Activate(target = "bob")),
            effects("/query bob hi"),
        )
    }

    @Test
    fun testNoticeNeedsATargetAndBody() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Notice(target = "bob", text = "heads up")),
            effects("/notice bob heads up"),
        )
        if (effects("/notice bob").firstOrNull() !is CommandEffect.Info) fail("expected usage info")
    }

    @Test
    fun testNoticePreservesInteriorSpacing() {
        // The body is sliced past the target, not re-joined from split tokens (mirrors /me).
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Notice(target = "bob", text = "heads   up")),
            effects("/notice bob heads   up"),
        )
    }

    // MARK: - Channels

    @Test
    fun testJoinPrefixesABareChannel() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Join(channel = "#linux", key = null)), effects("/join linux"))
    }

    @Test
    fun testJoinKeepsAnExistingPrefixAndTakesAKey() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Join(channel = "#secret", key = "hunter2")),
            effects("/join #secret hunter2"),
        )
    }

    @Test
    fun testBareJoinIsANoOp() {
        assertEquals(emptyList(), effects("/join"))
    }

    @Test
    fun testPartDefaultsToCurrentBufferAndRetargetsWithALeadingChannel() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Part(channel = "#chan", reason = null)), effects("/part"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Part(channel = "#other", reason = "so long")),
            effects("/part #other so long"),
        )
    }

    @Test
    fun testPartReasonOnlyLeavesTheCurrentChannel() {
        // A non-channel first word is a parting reason, not a channel named "heading".
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Part(channel = "#chan", reason = "heading out")),
            effects("/part heading out"),
        )
    }

    @Test
    fun testPartOutsideAChannelIsRefused() {
        if (effects("/part", target = "bob").firstOrNull() !is CommandEffect.Info) {
            fail("expected a channel-context note when parting from a DM")
        }
    }

    @Test
    fun testLeaveIsAnAliasOfPart() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Part(channel = "#chan", reason = null)), effects("/leave"))
    }

    @Test
    fun testCycleIsPartThenJoinOfTheCurrentChannel() {
        assertEquals(
            listOf(CommandEffect.Part(channel = "#chan", reason = null), CommandEffect.Join(channel = "#chan", key = null)),
            effects("/cycle"),
        )
    }

    @Test
    fun testCycleArgumentIsAReasonNotAChannel() {
        // Both legs stay on the current channel; the arg line is the part reason.
        assertEquals(
            listOf(
                CommandEffect.Part(channel = "#chan", reason = "back soon"),
                CommandEffect.Join(channel = "#chan", key = null),
            ),
            effects("/cycle back soon"),
        )
    }

    @Test
    fun testCycleOutsideAChannelIsRefused() {
        if (effects("/cycle", target = "bob").firstOrNull() !is CommandEffect.Info) {
            fail("expected a channel-context note")
        }
    }

    @Test
    fun testCloseTargetsTheCurrentBuffer() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Close(target = "#chan")), effects("/close"))
    }

    @Test
    fun testTopicQueriesWhenEmptyAndSetsOtherwise() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "TOPIC #chan")), effects("/topic"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "TOPIC #chan :hello world")),
            effects("/topic hello world"),
        )
    }

    @Test
    fun testTopicRetargetsWithALeadingChannel() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "TOPIC #other :new topic")),
            effects("/topic #other new topic"),
        )
    }

    @Test
    fun testModeShortcutRefusedInADm() {
        if (effects("/op alice", target = "bob").firstOrNull() !is CommandEffect.Info) {
            fail("expected a channel-context note for a mode shortcut in a DM")
        }
    }

    @Test
    fun testModeShortcutUsageNamesTheActualCommand() {
        // The usage hint must say /deop, not a letter-derived "/deo".
        val text = (effects("/deop").firstOrNull() as? CommandEffect.Info)?.text ?: fail("expected usage")
        assertTrue(text.contains("/deop"), "usage should name the command, got: $text")
    }

    @Test
    fun testNickIsARawLine() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "NICK newname")), effects("/nick newname"))
    }

    /**
     * `/whois` opens the profile rather than sending the line itself (lurker-ios#12).
     *
     * ⚠ No `.raw` beside it, deliberately. The profile asks on open through `requestWhois`,
     * which owns the in-flight bookkeeping; a second WHOIS here would be dropped by that very
     * bookkeeping, and the server buffer gets the raw numerics either way.
     */
    @Test
    fun testWhoisOpensTheProfile() {
        assertEquals(listOf<CommandEffect>(CommandEffect.ShowProfile(nick = "bob")), effects("/whois bob"))
    }

    @Test
    fun testBareWhoisInADmTargetsThePeer() {
        assertEquals(listOf<CommandEffect>(CommandEffect.ShowProfile(nick = "bob")), effects("/whois", target = "bob"))
    }

    @Test
    fun testInviteDefaultsChannelToCurrentBuffer() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "INVITE bob #chan")), effects("/invite bob"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "INVITE bob #other")), effects("/invite bob #other"))
    }

    @Test
    fun testInviteOutsideAChannelIsRefused() {
        // A bare /invite from a DM would otherwise emit "INVITE bob <peer-nick>".
        if (effects("/invite bob", target = "alice").firstOrNull() !is CommandEffect.Info) {
            fail("expected a channel-context note")
        }
    }

    @Test
    fun testCommandsRecognizeAmpersandChannels() {
        // `&` is a valid channel sigil (BufferKind.of), so it must be honored as an explicit
        // channel argument, not folded into a nick/reason/topic.
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "KICK &local bob")), effects("/kick &local bob"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "MODE &local +o alice")), effects("/op &local alice"))
        // `/topic` and `/part` take free text first, so there `&local` is a channel only while
        // it's open (see the sigil tests below).
        val open = { name: String -> name == "&local" }
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "TOPIC &local :hi")),
            context("/topic &local hi", hasBuffer = open),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Part(channel = "&local", reason = "bye")),
            context("/part &local bye", hasBuffer = open),
        )
    }

    // MARK: - Moderation

    @Test
    fun testKickBuildsARawLineWithReason() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "KICK #chan bob :be nice")), effects("/kick bob be nice"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "KICK #chan bob")), effects("/kick bob"))
    }

    @Test
    fun testKickTakesAnExplicitLeadingChannel() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "KICK #other bob :spam")),
            effects("/kick #other bob spam"),
        )
    }

    @Test
    fun testKickOutsideAChannelWithoutAChannelArgIsRefused() {
        if (effects("/kick bob", target = "alice").firstOrNull() !is CommandEffect.Info) {
            fail("expected a channel-context note")
        }
    }

    @Test
    fun testOpRepeatsTheModeLetterPerNick() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "MODE #chan +oo alice bob")), effects("/op alice bob"))
    }

    @Test
    fun testDeopIsTheMinusForm() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "MODE #chan -o alice")), effects("/deop alice"))
    }

    @Test
    fun testBanTakesAnExplicitLeadingChannel() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "MODE #other +b *!*@spam.host")),
            effects("/ban #other *!*@spam.host"),
        )
    }

    @Test
    fun testModeExplicitTargetPassesThrough() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "MODE #chan +m")), effects("/mode #chan +m"))
    }

    @Test
    fun testModeFlagsOnlyPrependsTheCurrentChannel() {
        // `/mode +m` in a channel targets that channel, not a bogus target "+m".
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "MODE #chan +m")), effects("/mode +m"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "MODE #chan +b *!*@x")), effects("/mode +b *!*@x"))
    }

    // MARK: - Server / services

    @Test
    fun testRawAndQuoteSendTheLineVerbatim() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "PING :x")), effects("/raw PING :x"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "PING :x")), effects("/quote PING :x"))
    }

    @Test
    fun testNickServAndChanServ() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "PRIVMSG NickServ :identify hunter2")),
            effects("/ns identify hunter2"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "PRIVMSG ChanServ :op #chan")),
            effects("/cs op #chan"),
        )
    }

    @Test
    fun testServerQueryVerbsGoRawUppercased() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "MOTD")), effects("/motd"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "WHO #chan")), effects("/who #chan"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "NAMES #chan")), effects("/names #chan"))
    }

    @Test
    fun testUnknownCommandFallsThroughToRaw() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "frobnicate a b")), effects("/frobnicate a b"))
    }

    // MARK: - Status (network-agnostic)

    @Test
    fun testAwayCarriesItsMessageAndRunsFromSystemBuffer() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "lunch", all = null)),
            effects("/away lunch", networkId = null, target = ":system:"),
        )
    }

    @Test
    fun testBackRunsFromSystemBuffer() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Back(all = null)),
            effects("/back", networkId = null, target = ":system:"),
        )
    }

    /**
     * lurker#994: `-all` reaches every network, `-one` just this one; without either the
     * server's setting decides.
     */
    @Test
    fun testAwayAndBackTakeAScopeFlag() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Away(message = "lunch", all = true)), effects("/away -all lunch"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "lunch break", all = false)),
            effects("/away -ONE lunch break"),
        )
        assertEquals(listOf<CommandEffect>(CommandEffect.Away(message = "", all = true)), effects("/away -all"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Back(all = true)), effects("/back -all"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Back(all = false)), effects("/back -one"))
        // Any Unicode whitespace separates: a pasted non-breaking space too.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "lunch", all = true)),
            effects("/away -all\u00A0lunch"),
        )
    }

    @Test
    fun testOneIsRefusedWhereThereIsNoNetwork() {
        val text = (effects("/back -one", networkId = null, target = ":system:").firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected an info line, not a back to every network")
        assertTrue(text.contains("no network here"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "", all = true)),
            effects("/away -all", networkId = null, target = ":system:"),
        )
    }

    @Test
    fun testAwayReadsAFlagOnlyAtTheFrontAndAsAWholeWord() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "back at -all hands", all = null)),
            effects("/away back at -all hands"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "-allnighter", all = null)),
            effects("/away -allnighter"),
        )
    }

    @Test
    fun testCommandsPrintsLocalHelpFromSystemBuffer() {
        val text = (effects("/commands", networkId = null, target = ":system:").firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected help info")
        assertTrue(text.contains("/join"), "the cheatsheet should list the vocabulary")
    }

    // MARK: - Network gate

    @Test
    fun testNetworkCommandInSystemBufferIsGated() {
        val text = (effects("/join #x", networkId = null, target = ":system:").firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a gate message")
        assertTrue(text.contains("needs an active network"))
    }

    // MARK: - Connection lifecycle (lurker-ios#152)

    @Test
    fun testConnectStartsThisNetwork() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Connect), effects("/connect"))
    }

    @Test
    fun testDisconnectStopsThisNetworkWithAnOptionalReason() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Disconnect(reason = null)), effects("/disconnect"))
        // The whole line is the reason, interior spacing kept — it's a quit message.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Disconnect(reason = "back  later")),
            effects("/disconnect back  later"),
        )
    }

    @Test
    fun testQuitIsAnAliasOfDisconnect() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Disconnect(reason = "going home")), effects("/quit going home"))
        assertEquals(effects("/disconnect"), effects("/quit"))
        assertEquals("disconnect", CommandRegistry.spec("quit")?.name)
    }

    @Test
    fun testReconnectRestartsThisNetwork() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Reconnect), effects("/reconnect"))
    }

    @Test
    fun testQuitNeverReachesTheWireAsARawLine() {
        // A raw QUIT leaves the server's requested-disconnect flag unset, so the close reads
        // as unexpected and the network reconnects on its own — the one outcome /quit must
        // never have. From the system buffer it's gated, not rawed.
        for ((networkId, target) in listOf<Pair<Int?, String>>(1 to "#chan", null to ":system:")) {
            for (line in listOf("/quit", "/quit bye")) {
                for (effect in effects(line, networkId = networkId, target = target)) {
                    if (effect is CommandEffect.Raw) fail("$line went raw from $target")
                }
            }
        }
    }

    @Test
    fun testQuitReasonFoldsLineBreaksIntoOneLine() {
        // The reason is the tail of one IRC line; a pasted break would otherwise end it early
        // and put the rest on the wire as a command of its own.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Disconnect(reason = "going home now")),
            effects("/quit going\nhome\r\nnow"),
        )
    }

    @Test
    fun testLifecycleVerbsAreGatedInTheSystemBufferWithTheirOwnCopy() {
        // Nothing there to start or stop — but the generic gate's "switch to a channel or DM"
        // is wrong advice for these: they act on a network, the server buffer works, and
        // someone with an offline network may have no channel to switch to.
        for (line in listOf("/connect", "/disconnect", "/quit", "/reconnect")) {
            val text = (effects(line, networkId = null, target = ":system:").firstOrNull() as? CommandEffect.Info)?.text
                ?: fail("expected a gate message for $line")
            assertTrue(text.contains("needs a network"), text)
            assertTrue(text.contains("Settings"), text)
        }
    }

    @Test
    fun testServerIsInterceptedRatherThanRawed() {
        // `SERVER` is a server-to-server command; what people mean by it is the networks screen.
        if (effects("/server irc.example.org").firstOrNull() !is CommandEffect.Info) {
            fail("expected /server to be intercepted with a note")
        }
    }

    // MARK: - Ignore rules (lurker-ios#86)

    /**
     * Three rules across both buckets: one global and two on network 1, the second pair
     * sharing a mask so a by-mask removal has something to count. Listed order is globals
     * first, so the indices are 1: spammer, 2: bob, 3: BOB.
     */
    private val listedRules: IgnoreSet
        get() = IgnoreSet(
            global = listOf(IgnoreRule(id = 7, mask = "spammer")),
            byNetwork = mapOf(
                1 to listOf(
                    IgnoreRule(id = 9, mask = "bob", levels = listOf("JOINS")),
                    IgnoreRule(id = 11, mask = "BOB", levels = listOf("PARTS")),
                ),
            ),
        )

    private fun effects(
        input: String,
        ignores: IgnoreSet,
        networkId: Int? = 1,
        target: String = "#chan",
    ): List<CommandEffect> {
        val parsed = CommandParser.parse(
            input, networkId = networkId, target = target, ignores = ignores, formatted = formatted,
        )
        if (parsed !is ParsedInput.Command) fail("expected a command from $input")
        return parsed.effects
    }

    @Test
    fun testIgnoreNoLongerPutsARawIgnoreLineOnTheWire() {
        // The bug lurker-ios#86 exists to fix: the unknown-verb fallback sent a literal
        // `IGNORE bob` to a server that has no such command, so the user got a numeric back and
        // no rule.
        for (effect in effects("/ignore bob") + effects("/unignore bob", ignores = listedRules)) {
            if (effect is CommandEffect.Raw) fail("an ignore verb must never reach the raw fallback")
        }
    }

    @Test
    fun testIgnoreDefaultsToAGlobalRule() {
        // The default scope is global (lurker#350) — null, not the issuing network.
        assertEquals(
            CommandEffect.AddIgnore(
                scope = null,
                rule = IgnoreRule(mask = "bob", levels = listOf("ALL")),
                receipt = "ignore added: bob  [global]  ALL",
            ),
            effects("/ignore bob").firstOrNull(),
        )
    }

    @Test
    fun testIgnoreNetworkScopesToTheIssuingConnection() {
        assertEquals(
            CommandEffect.AddIgnore(
                scope = 1,
                rule = IgnoreRule(mask = "bob", levels = listOf("NOHIGHLIGHT")),
                receipt = "ignore added: bob  NOHIGHLIGHT",
            ),
            effects("/ignore -network bob NOHIGHLIGHT").firstOrNull(),
        )
    }

    @Test
    fun testTheReceiptRidesOnTheEffectSoAFailedSendCanWithholdIt() {
        // Not a separate `.info`: whether the line may be printed isn't known until the verb
        // has been handed to a socket, and nothing queues these. See `ChatViewModel.report`.
        val effects = effects("/ignore bob")
        assertEquals(1, effects.size, "the confirmation must not be a second, unconditional effect")
        for (effect in effects) {
            if (effect is CommandEffect.Info) fail("an ignore add must not print unconditionally")
        }
    }

    @Test
    fun testIgnoreRunsFromTheSystemBufferBecauseRulesAreGlobal() {
        assertEquals(
            CommandEffect.AddIgnore(
                scope = null,
                rule = IgnoreRule(mask = "bob", levels = listOf("ALL")),
                receipt = "ignore added: bob  [global]  ALL",
            ),
            effects("/ignore bob", networkId = null, target = ":system:").firstOrNull(),
        )
    }

    @Test
    fun testEveryNetworkAgnosticSpecIsActuallyReachableFromTheSystemBuffer() {
        // `networkAgnostic` is documentation — the real gate is where the verb's case sits in
        // `resolve`. This is what keeps the two from drifting apart.
        for (spec in CommandRegistry.all.filter { it.networkAgnostic }) {
            val effects = effects("/${spec.name}", networkId = null, target = ":system:")
            val first = effects.firstOrNull()
            if (first is CommandEffect.Info) {
                assertFalse(
                    first.text.contains("needs an active network"),
                    "/${spec.name} claims to be network-agnostic but is gated",
                )
            }
        }
    }

    @Test
    fun testIgnoreNetworkInTheSystemBufferIsRefusedRatherThanMadeGlobal() {
        // Writing the global rule they didn't ask for would be a wider ignore than intended.
        val effects = effects("/ignore -network bob", networkId = null, target = ":system:")
        val text = (effects.firstOrNull() as? CommandEffect.Info)?.text?.takeIf { effects.size == 1 }
            ?: fail("expected a refusal and nothing else")
        assertTrue(text.contains("needs an active network"), text)
    }

    @Test
    fun testIgnoreReportsAParseErrorRatherThanStoringSomethingElse() {
        val effects = effects("/ignore -bogus bob")
        val text = (effects.firstOrNull() as? CommandEffect.Info)?.text?.takeIf { effects.size == 1 }
            ?: fail("expected an error and no rule")
        assertTrue(text.contains("unknown flag"), text)
    }

    @Test
    fun testBareIgnoreListsTheRulesNumberedGlobalsFirst() {
        val text = (effects("/ignore", ignores = listedRules).firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a listing")
        val lines = text.split("\n").filter { it.isNotEmpty() }
        assertTrue(lines[0].contains("(3)"), lines[0])
        assertTrue(lines[1].contains("1. spammer"), lines[1])
        assertTrue(lines[1].contains("[global]"), lines[1])
        assertTrue(lines[2].contains("2. bob"), lines[2])
        assertFalse(lines[2].contains("[global]"), lines[2])
    }

    @Test
    fun testTheListingCarriesTheGrammarBecauseNothingElseDoes() {
        // `/commands` can only print the positional form, so the flags are unreachable without
        // this — including `-network`, the only way to scope a rule to one connection.
        val text = (effects("/ignore").firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a listing")
        assertTrue(text.contains("empty"), text)
        assertTrue(text.contains("-network"), text)
        assertTrue(text.contains("-pattern"), text)
        assertTrue(text.contains("NOHIGHLIGHT"), text)
    }

    @Test
    fun testALapsedRuleKeepsItsPlaceInTheListingAndIsMarkedExpired() {
        // Filtering it out would renumber everything below it the moment it lapsed — so an
        // index read off one `/ignore` and spent on the next `/unignore` would delete a
        // different, still-live rule. Its row is also still on the server until the sweeper
        // gets to it, and a by-mask DELETE takes it with the rest.
        val now = Instant.now()
        val set = IgnoreSet(
            global = listOf(
                IgnoreRule(id = 1, mask = "gone", expiresAt = now.plusSeconds(-60)),
                IgnoreRule(id = 2, mask = "live", expiresAt = now.plusSeconds(60)),
            ),
        )
        val listing = CommandParser.parse(
            "/ignore", networkId = 1, target = "#chan", ignores = set, now = now, formatted = formatted,
        )
        val text = ((listing as? ParsedInput.Command)?.effects?.firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a listing")
        assertTrue(text.contains("(2)"), text)
        assertTrue(text.contains("1. gone"), text)
        assertTrue(text.contains("(expired "), text)
        assertTrue(text.contains("2. live"), text)
        // And the index still means what it said.
        val removal = (
            CommandParser.parse(
                "/unignore 2", networkId = 1, target = "#chan", ignores = set, now = now, formatted = formatted,
            ) as? ParsedInput.Command
            )?.effects ?: fail("expected a removal")
        assertEquals(2, (removal.firstOrNull() as? CommandEffect.RemoveIgnore)?.id)
    }

    @Test
    fun testBothVerbsRefuseToAnswerBeforeTheRulesHaveArrived() {
        // `null` is "not synced yet", which an empty set cannot express — and both verbs would
        // otherwise deny out loud that rules the account really has exist.
        for (input in listOf("/ignore", "/unignore bob", "/unignore 1")) {
            val effects = (
                CommandParser.parse(
                    input, networkId = 1, target = "#chan", ignores = null, formatted = formatted,
                ) as? ParsedInput.Command
                )?.effects
            val text = (effects?.firstOrNull() as? CommandEffect.Info)?.text?.takeIf { effects.size == 1 }
                ?: fail("expected one explanation from $input")
            assertTrue(text.contains("haven't arrived yet"), text)
        }
        // Authoring doesn't need the listing, so it isn't gated — the send path reports
        // whether it landed. But the receipt can't say whether the server will add or upsert
        // without the rules, so it claims neither.
        val effects = (
            CommandParser.parse(
                "/ignore bob", networkId = 1, target = "#chan", ignores = null, formatted = formatted,
            ) as? ParsedInput.Command
            )?.effects
        val receipt = (effects?.firstOrNull() as? CommandEffect.AddIgnore)?.receipt
            ?: fail("expected /ignore <nick> to still author a rule")
        assertTrue(receipt.startsWith("ignore sent:"), receipt)
    }

    @Test
    fun testUnignoreByIndexRemovesThatRuleByIdInItsOwnScope() {
        // #2 is the network-scoped rule: both the id and the scope come from the listing, which
        // is the whole reason `IgnoreRule.id` is carried.
        assertEquals(
            CommandEffect.RemoveIgnore(scope = 1, id = 9, mask = null, receipt = "removed ignore #2: bob  JOINS"),
            effects("/unignore 2", ignores = listedRules).firstOrNull(),
        )
        assertEquals(
            CommandEffect.RemoveIgnore(
                scope = null, id = 7, mask = null,
                receipt = "removed ignore #1: spammer  [global]  ALL",
            ),
            effects("/unignore 1", ignores = listedRules).firstOrNull(),
        )
    }

    @Test
    fun testUnignoreByIndexOutOfRangeRemovesNothing() {
        for (input in listOf("/unignore 0", "/unignore 4", "/unignore 99999999999999999999")) {
            val effects = effects(input, ignores = listedRules)
            val text = (effects.firstOrNull() as? CommandEffect.Info)?.text?.takeIf { effects.size == 1 }
                ?: fail("expected a complaint and no removal from $input")
            assertTrue(text.contains("see /ignore"), text)
        }
    }

    @Test
    fun testAnAllDigitMaskIsStillRemovableByMask() {
        // The index branch wins when the number names a rule that exists; otherwise the digits
        // are tried as a mask, so a numeric nick (bots, `*!*@1234`) isn't create-only.
        val set = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 3, mask = "12345"))))
        assertEquals(
            CommandEffect.RemoveIgnore(
                scope = 1, id = null, mask = "12345",
                receipt = "removed 1 ignore matching \"12345\".",
            ),
            effects("/unignore 12345", ignores = set).firstOrNull(),
        )
        // An in-range index still means the index.
        assertEquals(
            CommandEffect.RemoveIgnore(scope = 1, id = 3, mask = null, receipt = "removed ignore #1: 12345  ALL"),
            effects("/unignore 1", ignores = set).firstOrNull(),
        )
    }

    @Test
    fun testUnignoreByMaskClearsEveryRuleCarryingIt() {
        // Scoped to the issuing network: the server's by-mask delete spans the globals plus
        // that one network, which is the set the count describes. Two rules share this mask up
        // to case, and SQLite's NOCASE folds them the same way.
        assertEquals(
            CommandEffect.RemoveIgnore(
                scope = 1, id = null, mask = "BOB",
                receipt = "removed 2 ignores matching \"BOB\".",
            ),
            effects("/unignore BOB", ignores = listedRules).firstOrNull(),
        )
    }

    @Test
    fun testTheByMaskCountFoldsCaseTheWayTheServersDeleteDoes() {
        // `caseInsensitiveCompare` would count these as matches; SQLite's `COLLATE NOCASE`
        // (ASCII-only, byte-exact otherwise) does not — so counting them would report a
        // removal the DELETE never makes.
        val set = IgnoreSet(
            byNetwork = mapOf(
                1 to listOf(
                    IgnoreRule(id = 1, mask = "caf\u00E9"), // composed
                    IgnoreRule(id = 2, mask = "stra\u00DFe"),
                ),
            ),
        )
        for (input in listOf("/unignore cafe\u0301", "/unignore STRASSE")) {
            val effects = effects(input, ignores = set)
            val text = (effects.firstOrNull() as? CommandEffect.Info)?.text?.takeIf { effects.size == 1 }
                ?: fail("expected no removal from $input")
            assertTrue(text.contains("no ignore with mask"), text)
        }
        // ASCII case still folds, because NOCASE folds it — and it has to fold per BYTE, not
        // per grapheme: `A` + combining acute is one non-ASCII `Character` whose `A` byte
        // SQLite still lowers. Folding by character left it alone and reported no match for a
        // rule the DELETE would have removed.
        val ascii = IgnoreSet(
            byNetwork = mapOf(
                1 to listOf(
                    IgnoreRule(id = 3, mask = "Bob"),
                    IgnoreRule(id = 4, mask = "A\u0301bc"),
                ),
            ),
        )
        assertEquals(
            CommandEffect.RemoveIgnore(scope = 1, id = null, mask = "BOB", receipt = "removed 1 ignore matching \"BOB\"."),
            effects("/unignore BOB", ignores = ascii).firstOrNull(),
        )
        assertEquals(
            CommandEffect.RemoveIgnore(
                scope = 1, id = null, mask = "a\u0301BC",
                receipt = "removed 1 ignore matching \"a\u0301BC\".",
            ),
            effects("/unignore a\u0301BC", ignores = ascii).firstOrNull(),
        )
    }

    @Test
    fun testAQuotedMaskIsRemovableInTheSpellingThatCreatedIt() {
        // `/ignore "bob smith"` stores `bob smith`; comparing the raw arg would have matched
        // only the unquoted spelling, which isn't the one that made the rule.
        val set = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 5, mask = "bob smith"))))
        assertEquals(
            CommandEffect.RemoveIgnore(
                scope = 1, id = null, mask = "bob smith",
                receipt = "removed 1 ignore matching \"bob smith\".",
            ),
            effects("/unignore \"bob smith\"", ignores = set).firstOrNull(),
        )
    }

    @Test
    fun testReIssuingAnIdenticalRuleReportsAnUpdateBecauseTheServerUpserts() {
        // `add-ignore` matches on every dimension but expiry and rewrites that row's
        // `expires_at` in place — so `/ignore -time 1h bob` then `/ignore bob` doesn't add a
        // second rule, it makes the hour-long mute permanent. "added" would hide that.
        val set = IgnoreSet(
            global = listOf(
                IgnoreRule(id = 1, mask = "bob", levels = listOf("ALL"), expiresAt = Instant.now().plusSeconds(3600)),
            ),
        )
        val receipt = (effects("/ignore bob", ignores = set).firstOrNull() as? CommandEffect.AddIgnore)?.receipt
            ?: fail("expected an add")
        assertTrue(receipt.startsWith("ignore updated:"), receipt)
        // A rule that differs in any compared dimension is genuinely new.
        val fresh = (effects("/ignore bob JOINS", ignores = set).firstOrNull() as? CommandEffect.AddIgnore)?.receipt
            ?: fail("expected an add")
        assertTrue(fresh.startsWith("ignore added:"), fresh)
    }

    @Test
    fun testAnInteriorNewlineDoesNotBecomePartOfTheMask() {
        // The composer is multi-line and Return inserts a newline, so `/unignore \nbob` is
        // reachable; `.whitespaces` (which excludes newlines) left it glued to the mask.
        val set = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 6, mask = "bob"))))
        assertEquals(
            CommandEffect.RemoveIgnore(scope = 1, id = null, mask = "bob", receipt = "removed 1 ignore matching \"bob\"."),
            effects("/unignore \nbob", ignores = set).firstOrNull(),
        )
        // And a newline-only argument lists rather than authoring a rule that names nobody.
        val text = (effects("/ignore \n", ignores = set).firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected a listing")
        assertTrue(text.contains("ignore list"), text)
    }

    @Test
    fun testUnignoreStarSaysWhyItCannotMatch() {
        // The listing prints a maskless rule as `*`, so typing it back is the obvious move —
        // but `*` normalizes to no mask at all, and the server's delete matches on a string.
        val set = IgnoreSet(global = listOf(IgnoreRule(id = 1, mask = null, levels = listOf("JOINS"))))
        val effects = effects("/unignore *", ignores = set)
        val text = (effects.firstOrNull() as? CommandEffect.Info)?.text?.takeIf { effects.size == 1 }
            ?: fail("expected an explanation and no removal")
        assertTrue(text.contains("by number"), text)
    }

    @Test
    fun testUnignoreByMaskWithNoMatchRemovesNothing() {
        val effects = effects("/unignore nobody", ignores = listedRules)
        val text = (effects.firstOrNull() as? CommandEffect.Info)?.text?.takeIf { effects.size == 1 }
            ?: fail("expected a complaint and no removal")
        assertTrue(text.contains("no ignore with mask"), text)
    }

    @Test
    fun testBareUnignoreExplainsItself() {
        val text = (effects("/unignore").firstOrNull() as? CommandEffect.Info)?.text
            ?: fail("expected usage")
        assertTrue(text.contains("index|mask"), text)
    }

    // Port-only: answers taken from the Swift, pinning what a `Character`-by-`Character` parser
    // gets for free and a UTF-16 one has to be told.

    @Test
    fun testTheVerbEndsAtSwiftsWhitespaceAndTheArgumentsAreTrimmedByFoundations() {
        // The verb and the tokens split on `Character.isWhitespace`, which a zero-width space is
        // not: it stays on the verb, and an unknown verb goes raw.
        assertEquals(listOf<CommandEffect>(CommandEffect.Raw(line = "me\u200B hi")), effects("/me\u200B hi"))
        // The argument line is trimmed with `CharacterSet.whitespacesAndNewlines`, which it is in.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Action(target = "#chan", text = "hi")),
            effects("/me \u200Bhi\u200B"),
        )
        // An ideographic space separates like any other.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Join(channel = "#x", key = "key")),
            effects("/join\u3000#x\u3000key"),
        )
    }

    @Test
    fun testQuitFoldsEveryLineBreakSwiftCallsANewline() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Disconnect(reason = "a b c d e f")),
            effects("/quit a\u2028b\u2029c\u0085d\u000Be\u000Cf"),
        )
    }

    @Test
    fun testALineBreakAfterTheVerbIsTrimmedOffTheBody() {
        // Until lurker-ios#197, `argLine` kept the break (only spaces and tabs were trimmed off
        // it) while the tokens were split past it, so "the body after the first token" was cut
        // one `Character` early, into the token. Now the break is trimmed with the spaces and
        // the body is cut after the token, whatever its units: a CR-LF, an emoji.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Notice(target = "bob", text = "hi")),
            effects("/notice\nbob hi"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Notice(target = "bob", text = "hi")),
            effects("/notice\r\nbob hi"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Notice(target = "👍bob", text = "hi")),
            effects("/notice\n👍bob hi"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Raw(line = "TOPIC #other :new")),
            effects("/topic\r\n#other new"),
        )
        // A mark after the break starts the token; it fuses with nothing.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Notice(target = "${0x0301.toChar()}bob", text = "hi")),
            effects("/notice\n${0x0301.toChar()}bob hi"),
        )
        // The body's own trim takes every line break Swift calls a newline.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Notice(target = "bob", text = "hi")),
            effects("/notice bob\r\n\u2028hi\u0085"),
        )
    }

    @Test
    fun testTheAwayFlagEndsAtSwiftsWhitespace() {
        // Answers taken from LurkerKit at 63255a5. Swift's whitespace, not the web's `\s`: a
        // U+FEFF or a zero-width space after the flag is part of the word, so the line is a
        // message; an ideographic space, a NEL and a line separator end it.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "-all\uFEFFlunch", all = null)),
            effects("/away -all\uFEFFlunch"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "-all\u200Blunch", all = null)),
            effects("/away -all\u200Blunch"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "lunch", all = false)),
            effects("/away -one\u3000lunch"),
        )
        assertEquals(listOf<CommandEffect>(CommandEffect.Back(all = true)), effects("/back -ALL\u0085"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "back soon", all = true)),
            effects("/away -all\u2028back soon"),
        )
        // A mark the trim leaves at the front fuses with nothing, so it is the start of the word.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "\u0301-all x", all = null)),
            effects("/away \u0301-all x"),
        )
        // ⚠ Differs: a space wearing a mark is one separator to LurkerKit, which drops the mark
        // with it and sends "x". Here the mark starts the message (see `parse`'s Port note).
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Away(message = "\u0301x", all = true)),
            effects("/away -all \u0301x"),
        )
    }

    // MARK: - Client sweep: the web's guards (L01, L03, L04, L18, L19, L34)

    @Test
    fun testQuietIsRefusedWhereQIsNotAListMode() {
        // InspIRCd and Unreal: +q is the owner rank, so /quiet would make the target an owner.
        val inspircd = spec(list = "beI")
        val refusal = listOf<CommandEffect>(CommandEffect.Info("this network has no +q quiet list"))
        assertEquals(refusal, context("/quiet troll", modeSpec = inspircd))
        assertEquals(refusal, context("/unquiet troll", modeSpec = inspircd))
    }

    @Test
    fun testQuietGoesThroughOnAQuietListOrAnUnknownSpec() {
        assertEquals(raws("MODE #chan +q troll"), context("/quiet troll", modeSpec = spec(list = "eIbq")))
        assertEquals(raws("MODE #chan -q troll"), context("/unquiet troll", modeSpec = spec(list = "eIbq")))
        // Before the burst ends there's nothing to check against, as on the web.
        assertEquals(raws("MODE #chan +q troll"), context("/quiet troll"))
    }

    @Test
    fun testModeShortcutsSplitAtTheNetworksModesLimit() {
        assertEquals(
            raws("MODE #chan +ooo a b c", "MODE #chan +oo d e"),
            context("/op a b c d e", modeSpec = spec(maxModes = 3)),
        )
        assertEquals(
            raws("MODE #other -vvvv a b c d", "MODE #other -v e"),
            context("/devoice #other a b c d e", modeSpec = spec(maxModes = 4)),
        )
    }

    @Test
    fun testModeShortcutsDefaultToThreeAndHonourNoLimit() {
        // Unknown vocabulary: the web's DEFAULT_MAX_MODES.
        assertEquals(raws("MODE #chan +bbb a b c", "MODE #chan +b d"), context("/ban a b c d"))
        // A known spec without MODES is no limit.
        assertEquals(raws("MODE #chan +ooooo a b c d e"), context("/op a b c d e", modeSpec = spec(maxModes = null)))
    }

    @Test
    fun testInviteTakesTheChannelFirstAsKickDoes() {
        assertEquals(raws("INVITE bob #other"), effects("/invite #other bob"))
        assertEquals(raws("INVITE bob &local"), effects("/invite &local bob", target = "alice"))
        assertTrue(isInfo(effects("/invite #other")))
    }

    @Test
    fun testInviteIgnoresASecondWordThatIsNotAChannel() {
        assertEquals(raws("INVITE bob #chan"), effects("/invite bob notachan"))
        assertTrue(isInfo(effects("/invite bob notachan", target = "alice")))
    }

    @Test
    fun testPartAndTopicReadAPunctuatedFirstWordAsText() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Part(channel = "#chan", reason = "+brb")), effects("/part +brb"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Part(channel = "#chan", reason = "!gone")), effects("/p !gone"))
        assertEquals(raws("TOPIC #chan :!!! maintenance !!!"), effects("/topic !!! maintenance !!!"))
        assertEquals(raws("TOPIC #chan :&more to come"), effects("/topic &more to come"))
    }

    @Test
    fun testPartAndTopicTakeAPunctuatedChannelThatIsOpen() {
        val open = { name: String -> name == "+local" || name == "!ABCDEsafe" }
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Part(channel = "+local", reason = "bye")),
            context("/part +local bye", hasBuffer = open),
        )
        assertEquals(raws("TOPIC !ABCDEsafe :hi"), context("/topic !ABCDEsafe hi", hasBuffer = open))
        // `#` needs no buffer: a sentence effectively never starts with one.
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Part(channel = "#elsewhere", reason = null)),
            effects("/part #elsewhere"),
        )
    }

    @Test
    fun testModeAimsAtAnOpenPlusChannelRatherThanReadingItAsFlags() {
        // lurker#724: `+local` is both a flag string and a channel name.
        val open = { name: String -> name == "+local" }
        assertEquals(raws("MODE +local +m"), context("/mode +local +m", hasBuffer = open))
        assertEquals(raws("MODE #chan +m"), context("/mode +m", hasBuffer = open))
    }

    @Test
    fun testJoinAndPartShortAliases() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Join(channel = "#rust", key = null)), effects("/j #rust"))
        assertEquals(listOf<CommandEffect>(CommandEffect.Part(channel = "#chan", reason = "see ya")), effects("/p see ya"))
        assertEquals("join", CommandRegistry.spec("j")?.name)
        assertEquals("part", CommandRegistry.spec("p")?.name)
    }

    @Test
    fun testShrugSaysTheKaomojiAfterAnyText() {
        assertEquals(listOf<CommandEffect>(CommandEffect.Send(target = "#chan", text = "¯\\_(ツ)_/¯")), effects("/shrug"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Send(target = "bob", text = "no idea ¯\\_(ツ)_/¯")),
            effects("/shrug no idea", target = "bob"),
        )
        assertTrue(isInfo(effects("/shrug", target = ":server:")))
    }

    @Test
    fun testKickbanBansThenKicks() {
        assertEquals(raws("MODE #chan +b troll", "KICK #chan troll :spam"), effects("/kickban troll spam"))
        assertEquals(raws("MODE #other +b troll", "KICK #other troll"), effects("/kickban #other troll", target = "alice"))
        assertTrue(isInfo(effects("/kickban troll", target = "alice")))
        assertTrue(isInfo(effects("/kickban")))
    }

    @Test
    fun testWebOnlyCommandsAreAnsweredRatherThanSentRaw() {
        for (line in listOf(
            "/list", "/list rust", "/set foo", "/get foo", "/theme dark", "/hilight word",
            "/dehilight word", "/highlight", "/unhighlight x", "/retention 30d", "/jitsi",
            "/talk", "/e2e on", "/network add", "/net list",
        )) {
            assertTrue(isInfo(effects(line)), "$line should be intercepted")
        }
    }

    @Test
    fun testReactRefusesAnEmojiNameItCannotResolve() {
        assertTrue(isInfo(effects("/react :tada:")))
        assertTrue(isInfo(effects("/react :+1:")))
        // Emoticons and the emoji itself still go out.
        assertEquals(listOf<CommandEffect>(CommandEffect.React(value = ":D")), effects("/react :D"))
        assertEquals(listOf<CommandEffect>(CommandEffect.React(value = ":-)")), effects("/react :-)"))
        assertEquals(listOf<CommandEffect>(CommandEffect.React(value = "🎉")), effects("/react 🎉"))
    }
}
