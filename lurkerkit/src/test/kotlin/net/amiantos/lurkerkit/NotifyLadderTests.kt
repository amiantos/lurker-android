// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.client.ChannelSnapshot
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.LurkerClient
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.NotifyLadder
import net.amiantos.lurkerkit.model.NotifyRung
import net.amiantos.lurkerkit.model.ScopedIgnoreRule
import net.amiantos.lurkerkit.model.notifyPlan
import net.amiantos.lurkerkit.model.notifyRung
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.LurkerStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The notification ladder end to end (lurker#359, Mobile lurker#1087): the flag off the wire
 * and through the store, the mute rules the quiet rungs are made of, the rung a buffer reads
 * as, and what picking one sends.
 *
 * Ported from the web client's `useNotifyLadder.test.ts`, the mute-rule cases of
 * `stores/ignores.test.ts`, and the lifecycle hooks of `stores/channelNotify.ts`.
 */
class NotifyLadderTests {

    private val channel: NotifyLadder.Scope = NotifyLadder.Scope.Channel(networkId = 1, target = "#Chan")
    private val dm: NotifyLadder.Scope = NotifyLadder.Scope.Dm(networkId = 1, target = "Bob")
    private val network: NotifyLadder.Scope = NotifyLadder.Scope.Network(networkId = 1)

    private fun mute(
        id: Int,
        channels: List<String>? = null,
        levels: List<String> = listOf("NOUNREAD", "NONOTIFY"),
        mask: String? = null,
        pattern: String? = null,
        isExcept: Boolean = false,
    ): IgnoreRule =
        IgnoreRule(
            id = id, mask = mask, channels = channels, pattern = pattern, levels = levels, isExcept = isExcept,
        )

    // MARK: - Which ladder

    /**
     * ⚠ All four sigils. A `#` probe would hand `&local` the DM ladder, whose "All messages"
     * is the absence of a rule rather than the flag the server consults.
     */
    @Test
    fun testEveryChannelSigilGetsTheChannelLadder() {
        for (target in listOf("#chan", "&local", "+modeless", "!safe")) {
            assertEquals(
                NotifyLadder.Scope.Channel(networkId = 1, target = target),
                NotifyLadder.Scope.of(BufferKey(networkId = 1, target = target)),
                target,
            )
        }
    }

    @Test
    fun testADmADccChatAndAServerLogEachGetTheirs() {
        assertEquals(
            NotifyLadder.Scope.Dm(networkId = 1, target = "bob"),
            NotifyLadder.Scope.of(BufferKey(networkId = 1, target = "bob")),
        )
        // The web's buffer menu gives a `=nick` chat the DM ladder, under its own target.
        assertEquals(
            NotifyLadder.Scope.Dm(networkId = 1, target = "=bob"),
            NotifyLadder.Scope.of(BufferKey(networkId = 1, target = "=bob")),
        )
        assertEquals(
            NotifyLadder.Scope.Network(networkId = 1),
            NotifyLadder.Scope.of(BufferKey(networkId = 1, target = ":server:1")),
        )
        assertNull(NotifyLadder.Scope.of(Buffer.system.key), "the app's own buffer has no ladder")
    }

    @Test
    fun testEachLadderOffersItsOwnRungsInOrder() {
        assertEquals(
            listOf(NotifyRung.All, NotifyRung.Highlights, NotifyRung.Nothing, NotifyRung.Muted), channel.rungs,
        )
        assertEquals(listOf(NotifyRung.All, NotifyRung.Nothing, NotifyRung.Muted), dm.rungs)
        assertEquals(listOf(NotifyRung.Highlights, NotifyRung.Nothing, NotifyRung.Muted), network.rungs)
        assertEquals(listOf("All messages", "Highlights only", "Nothing", "Muted"), channel.rungs.map { it.label })
    }

    // MARK: - The current rung

    @Test
    fun testWithNothingSetEachLadderRestsOnItsBase() {
        assertEquals(
            NotifyRung.Highlights, NotifyLadder.rung(channel, notifiesAlways = false, muteLevels = emptyList()),
        )
        assertEquals(NotifyRung.All, NotifyLadder.rung(dm, notifiesAlways = false, muteLevels = emptyList()))
        assertEquals(
            NotifyRung.Highlights, NotifyLadder.rung(network, notifiesAlways = false, muteLevels = emptyList()),
        )
    }

    @Test
    fun testTheRuleLevelsNameTheQuietRungs() {
        for (scope in listOf(channel, dm, network)) {
            assertEquals(
                NotifyRung.Nothing, NotifyLadder.rung(scope, notifiesAlways = false, muteLevels = listOf("NONOTIFY")),
            )
            assertEquals(
                NotifyRung.Muted,
                NotifyLadder.rung(scope, notifiesAlways = false, muteLevels = listOf("NOUNREAD", "NONOTIFY")),
            )
        }
    }

    /**
     * Badge hidden but notifications still firing — only a hand-typed `/ignore` makes one, and
     * it is neither "Nothing" nor "Muted".
     */
    @Test
    fun testANoUnreadOnlyRuleFallsThroughToTheBaseRung() {
        assertEquals(
            NotifyRung.Highlights, NotifyLadder.rung(channel, notifiesAlways = false, muteLevels = listOf("NOUNREAD")),
        )
        assertEquals(NotifyRung.All, NotifyLadder.rung(dm, notifiesAlways = false, muteLevels = listOf("NOUNREAD")))
        assertEquals(
            NotifyRung.Highlights, NotifyLadder.rung(network, notifiesAlways = false, muteLevels = listOf("NOUNREAD")),
        )
    }

    @Test
    fun testTheFlagWinsOnAChannelAndIsNotReadAnywhereElse() {
        assertEquals(
            NotifyRung.All,
            NotifyLadder.rung(channel, notifiesAlways = true, muteLevels = listOf("NOUNREAD", "NONOTIFY")),
        )
        assertEquals(NotifyRung.Nothing, NotifyLadder.rung(dm, notifiesAlways = true, muteLevels = listOf("NONOTIFY")))
        assertEquals(NotifyRung.Highlights, NotifyLadder.rung(network, notifiesAlways = true, muteLevels = emptyList()))
    }

    // MARK: - The plan

    private fun plan(
        rung: NotifyRung,
        scope: NotifyLadder.Scope,
        always: Boolean = false,
        rule: ScopedIgnoreRule? = null,
    ): List<NotifyLadder.Step> =
        NotifyLadder.plan(rung, scope, notifiesAlways = always, muteRule = rule)

    @Test
    fun testAlreadyThereIsNoSteps() {
        assertEquals(emptyList(), plan(NotifyRung.Highlights, channel))
        assertEquals(emptyList(), plan(NotifyRung.All, channel, always = true))
        assertEquals(emptyList(), plan(NotifyRung.All, dm))
        assertEquals(emptyList(), plan(NotifyRung.Highlights, network))
        val muted = ScopedIgnoreRule(rule = mute(5, channels = listOf("#chan")), scope = 1)
        assertEquals(emptyList(), plan(NotifyRung.Muted, channel, rule = muted))
        // Level ORDER is not a difference: the same two tokens either way round are one rule.
        val reversed = ScopedIgnoreRule(
            rule = mute(5, channels = listOf("#chan"), levels = listOf("NONOTIFY", "NOUNREAD")), scope = 1,
        )
        assertEquals(emptyList(), plan(NotifyRung.Muted, channel, rule = reversed))
    }

    @Test
    fun testAllMessagesOnAChannelIsTheFlagAndNoRule() {
        assertEquals(
            listOf<NotifyLadder.Step>(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#Chan", notifyAlways = true),
            ),
            plan(NotifyRung.All, channel),
        )
        assertEquals(
            listOf<NotifyLadder.Step>(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#Chan", notifyAlways = false),
            ),
            plan(NotifyRung.Highlights, channel, always = true),
        )
    }

    /**
     * The rule a quiet rung adds: unmasked, unpatterned, non-except, scoped to exactly this
     * one target folded, at network scope, with the levels in canonical order — the server
     * dedupes on the stored CSV as a string.
     */
    @Test
    fun testAQuietRungAddsItsRuleAtNetworkScope() {
        assertEquals(
            listOf<NotifyLadder.Step>(
                NotifyLadder.Step.AddRule(
                    networkId = 1, rule = IgnoreRule(channels = listOf("#chan"), levels = listOf("NONOTIFY")),
                ),
            ),
            plan(NotifyRung.Nothing, channel),
        )
        assertEquals(
            listOf<NotifyLadder.Step>(
                NotifyLadder.Step.AddRule(
                    networkId = 1,
                    rule = IgnoreRule(channels = listOf("bob"), levels = listOf("NOUNREAD", "NONOTIFY")),
                ),
            ),
            plan(NotifyRung.Muted, dm),
        )
        val first = plan(NotifyRung.Muted, channel).firstOrNull()
        if (first !is NotifyLadder.Step.AddRule) fail("expected an add")
        val rule = first.rule
        assertNull(rule.mask)
        assertNull(rule.pattern)
        assertFalse(rule.isExcept)
        assertNull(rule.expiresAt)
    }

    @Test
    fun testANetworkRuleHasNoChannelScope() {
        assertEquals(
            listOf<NotifyLadder.Step>(
                NotifyLadder.Step.AddRule(networkId = 1, rule = IgnoreRule(channels = null, levels = listOf("NONOTIFY"))),
            ),
            plan(NotifyRung.Nothing, network),
        )
    }

    @Test
    fun testMovingBetweenQuietRungsRemovesTheOldRuleThenAddsTheNew() {
        val nothing = ScopedIgnoreRule(
            rule = mute(5, channels = listOf("#chan"), levels = listOf("NONOTIFY")), scope = 1,
        )
        assertEquals(
            listOf(
                NotifyLadder.Step.RemoveRule(id = 5, scope = 1),
                NotifyLadder.Step.AddRule(
                    networkId = 1,
                    rule = IgnoreRule(channels = listOf("#chan"), levels = listOf("NOUNREAD", "NONOTIFY")),
                ),
            ),
            plan(NotifyRung.Muted, channel, rule = nothing),
        )
    }

    /**
     * A hand-typed global mute is removed from the GLOBAL bucket, where it lives; a removal
     * addressed to the network would miss it. The replacement still goes in at network scope.
     */
    @Test
    fun testAStaleRuleIsRemovedFromItsOwnScope() {
        val global = ScopedIgnoreRule(rule = mute(7, channels = listOf("#chan")), scope = null)
        assertEquals(
            listOf<NotifyLadder.Step>(NotifyLadder.Step.RemoveRule(id = 7, scope = null)),
            plan(NotifyRung.Highlights, channel, rule = global),
        )
        assertEquals(
            listOf(
                NotifyLadder.Step.RemoveRule(id = 7, scope = null),
                NotifyLadder.Step.AddRule(
                    networkId = 1, rule = IgnoreRule(channels = listOf("#chan"), levels = listOf("NONOTIFY")),
                ),
            ),
            plan(NotifyRung.Nothing, channel, rule = global),
        )
    }

    @Test
    fun testLeavingAQuietRungForAllMessagesSetsTheFlagAndClearsTheRule() {
        val muted = ScopedIgnoreRule(rule = mute(5, channels = listOf("#chan")), scope = 1)
        assertEquals(
            listOf(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#Chan", notifyAlways = true),
                NotifyLadder.Step.RemoveRule(id = 5, scope = 1),
            ),
            plan(NotifyRung.All, channel, rule = muted),
        )
        // A DM's "All messages" is only the absence of a rule: there is no flag to send.
        assertEquals(
            listOf<NotifyLadder.Step>(NotifyLadder.Step.RemoveRule(id = 5, scope = 1)),
            plan(NotifyRung.All, dm, always = true, rule = muted),
        )
    }

    @Test
    fun testGoingQuietFromAllMessagesClearsTheFlagFirst() {
        assertEquals(
            listOf(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#Chan", notifyAlways = false),
                NotifyLadder.Step.AddRule(
                    networkId = 1,
                    rule = IgnoreRule(channels = listOf("#chan"), levels = listOf("NOUNREAD", "NONOTIFY")),
                ),
            ),
            plan(NotifyRung.Muted, channel, always = true),
        )
    }

    /**
     * The base rung clears a `NOUNREAD`-only rule too, as the web's reconcile does: the rule
     * is this buffer's canonical mute rule, and the base rung is the absence of one.
     */
    @Test
    fun testTheBaseRungRemovesANoUnreadOnlyRule() {
        val badgeOnly = ScopedIgnoreRule(
            rule = mute(4, channels = listOf("#chan"), levels = listOf("NOUNREAD")), scope = 1,
        )
        assertEquals(
            listOf<NotifyLadder.Step>(NotifyLadder.Step.RemoveRule(id = 4, scope = 1)),
            plan(NotifyRung.Highlights, channel, rule = badgeOnly),
        )
    }

    /**
     * A lapsed rule is still a row for up to a minute, and mutes nothing: it reads as no rule,
     * and picking its rung replaces it rather than counting as already there.
     */
    @Test
    fun testALapsedMuteRuleIsReplacedNotTrusted() {
        val now = Instant.now()
        val lapsed = IgnoreRule(
            id = 6, channels = listOf("#chan"), levels = listOf("NOUNREAD", "NONOTIFY"),
            expiresAt = now.minusSeconds(5),
        )
        val steps = NotifyLadder.plan(
            NotifyRung.Muted, channel, notifiesAlways = false,
            muteRule = ScopedIgnoreRule(rule = lapsed, scope = 1), now = now,
        )
        assertEquals(NotifyLadder.Step.RemoveRule(id = 6, scope = 1), steps.firstOrNull())
        assertEquals(2, steps.size)

        val store = LurkerStore()
        store.apply(ServerFrame.IgnoreListUpdated(networkId = 1, rules = listOf(lapsed)))
        assertEquals(NotifyRung.Highlights, store.state.notifyRung(channel, now = now))
        assertEquals(NotifyRung.Muted, store.state.notifyRung(channel, now = now.minusSeconds(10)))
    }

    @Test
    fun testARungTheLadderDoesNotOfferPlansNothing() {
        assertEquals(emptyList(), plan(NotifyRung.Highlights, dm))
        assertEquals(emptyList(), plan(NotifyRung.All, network))
    }

    // MARK: - The mute rules

    @Test
    fun testBufferMuteRuleFindsANetworkScopedPerChannelMuteAndReportsItsScope() {
        val set = IgnoreSet(byNetwork = mapOf(1 to listOf(mute(5, channels = listOf("#chan")))))
        val found = set.bufferMuteRule(networkId = 1, target = "#Chan") // case-insensitive
        assertEquals(5, found?.rule?.id)
        assertEquals(1, found?.scope)
        assertNull(set.bufferMuteRule(networkId = 2, target = "#chan"), "another network's rule")
    }

    @Test
    fun testBufferMuteRuleAlsoFindsAGlobalPerChannelMuteWithANilScope() {
        val set = IgnoreSet(global = listOf(mute(7, channels = listOf("#chan"))))
        val found = set.bufferMuteRule(networkId = 1, target = "#chan")
        assertEquals(7, found?.rule?.id)
        assertNotNull(found)
        assertNull(found.scope)
        // …and the badge path agrees the buffer is muted.
        assertTrue(set.mutesUnread(networkId = 1, target = "#chan"))
    }

    @Test
    fun testTheNetworkBucketIsSearchedBeforeTheGlobals() {
        val set = IgnoreSet(
            global = listOf(mute(7, channels = listOf("#chan"))),
            byNetwork = mapOf(1 to listOf(mute(5, channels = listOf("#chan"), levels = listOf("NONOTIFY")))),
        )
        assertEquals(5, set.bufferMuteRule(networkId = 1, target = "#chan")?.rule?.id)
    }

    @Test
    fun testBufferMuteRuleIgnoresMaskedPatternedExceptAndNonMuteRules() {
        val set = IgnoreSet(
            byNetwork = mapOf(
                1 to listOf(
                    mute(1, channels = listOf("#chan"), mask = "bob"), // masked: not a whole-buffer mute
                    mute(2, channels = listOf("#chan"), isExcept = true),
                    mute(3, channels = listOf("#chan"), levels = listOf("PUBLIC")), // a hide, not a mute
                    mute(4, channels = listOf("#chan"), pattern = "spam"),
                    mute(6, channels = listOf("#chan"), levels = listOf("NONOTIFY", "JOINS")), // mute plus a hide
                    mute(8, channels = listOf("#chan", "#other")), // not this buffer's alone
                    mute(9, channels = listOf("#chan"), levels = emptyList()),
                ),
            ),
        )
        assertNull(set.bufferMuteRule(networkId = 1, target = "#chan"))
    }

    @Test
    fun testNetworkMuteRuleFindsAChannellessMuteAndAPerChannelRuleIsNotIt() {
        val set = IgnoreSet(
            byNetwork = mapOf(1 to listOf(mute(9, channels = listOf("#chan")), mute(8, channels = null))),
        )
        val found = set.networkMuteRule(networkId = 1)
        assertEquals(8, found?.rule?.id)
        assertEquals(1, found?.scope)
        assertNull(
            IgnoreSet(byNetwork = mapOf(1 to listOf(mute(9, channels = listOf("#chan"))))).networkMuteRule(networkId = 1),
        )
        // An empty channel list is the same "no channel scope" a nil one is.
        assertEquals(
            3,
            IgnoreSet(byNetwork = mapOf(1 to listOf(mute(3, channels = emptyList())))).networkMuteRule(networkId = 1)?.rule?.id,
        )
        // A global one is "everything, everywhere", found from any network with a nil scope.
        val global = IgnoreSet(global = listOf(mute(2))).networkMuteRule(networkId = 4)
        assertEquals(2, global?.rule?.id)
        assertNull(global?.scope)
    }

    // MARK: - The wire

    @Test
    fun testParsesTheSnapshotsChannelNotifyMap() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"snapshot","networks":[{"networkId":7,"state":"connected","nick":"me","channels":[],
             "channelNotify":{"#Loud":{"notifyAlways":true},"&local":{"notifyAlways":true},
                              "#off":{"notifyAlways":false},"#bare":{},"#wrong":true,"":{"notifyAlways":true}}}]}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.Snapshot) fail("expected a snapshot")
        assertEquals(listOf("#Loud", "&local"), frame.networks.firstOrNull()?.notifyAlways)
    }

    @Test
    fun testAMissingOrMalformedChannelNotifyIsNoChannelsAndNeverFatal() {
        for (raw in listOf("", ""","channelNotify":null""", ""","channelNotify":[]""", ""","channelNotify":"yes"""")) {
            val frame = FrameParser.parseWs(
                """{"kind":"snapshot","networks":[{"networkId":7,"state":"connected","nick":"me","channels":[]""" +
                    raw + "}]}",
            )
            if (frame !is ServerFrame.Snapshot) fail("expected a snapshot: $raw")
            assertEquals(7, frame.networks.firstOrNull()?.id, raw)
            assertEquals(emptyList(), frame.networks.firstOrNull()?.notifyAlways, raw)
        }
    }

    @Test
    fun testParsesTheChangedFrame() {
        assertEquals(
            ServerFrame.ChannelNotifyChanged(networkId = 7, target = "#Chan", bufferId = 42, notifyAlways = true),
            FrameParser.parseWs(
                """{"kind":"channel-notify-changed","networkId":7,"target":"#Chan","bufferId":42,"notifyAlways":true}""",
            ),
        )
        assertEquals(
            ServerFrame.ChannelNotifyChanged(networkId = 7, target = "#Chan", bufferId = null, notifyAlways = false),
            FrameParser.parseWs(
                """{"kind":"channel-notify-changed","networkId":7,"target":"#Chan","bufferId":null,"notifyAlways":false}""",
            ),
        )
    }

    /**
     * A frame that can't say which channel, or doesn't say which way, changes nothing. An
     * absent `notifyAlways` in particular is not a statement that it is off.
     */
    @Test
    fun testAChangedFrameMissingAHalfIsRefused() {
        for (bad in listOf(
            """{"kind":"channel-notify-changed","target":"#chan","notifyAlways":true}""",
            """{"kind":"channel-notify-changed","networkId":null,"target":"#chan","notifyAlways":true}""",
            """{"kind":"channel-notify-changed","networkId":7,"notifyAlways":true}""",
            """{"kind":"channel-notify-changed","networkId":7,"target":"","notifyAlways":true}""",
            """{"kind":"channel-notify-changed","networkId":7,"target":"#chan"}""",
            """{"kind":"channel-notify-changed","networkId":7,"target":"#chan","notifyAlways":"yes"}""",
        )) {
            assertEquals(ServerFrame.Ignored, FrameParser.parseWs(bad), bad)
        }
    }

    @Test
    fun testTheVerbCarriesTheChannelAndTheFlag() {
        val frame = LurkerClient.channelNotifyFrame(networkId = 3, target = "&local", notifyAlways = true)
        assertEquals(JsonPrimitive("set-channel-notify-always"), frame["type"])
        assertEquals(JsonPrimitive(3), frame["networkId"])
        assertEquals(JsonPrimitive("&local"), frame["target"])
        assertEquals(JsonPrimitive(true), frame["notifyAlways"])
        assertEquals(4, frame.size)
    }

    // MARK: - The store

    private fun snapshot(notifyAlways: List<String>, channels: List<String> = listOf("#chan", "#other")): ServerFrame =
        ServerFrame.Snapshot(
            listOf(
                NetworkSnapshot(
                    id = 1, state = ConnectionState.Connected, nick = "me",
                    channels = channels.map { ChannelSnapshot(name = it, topic = null, members = emptyList()) },
                    notifyAlways = notifyAlways,
                ),
            ),
            globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
        )

    @Test
    fun testTheSnapshotSeedsTheFlagsAndReplacesThemWholesale() {
        val store = LurkerStore()
        store.apply(snapshot(listOf("#Chan")))
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#chan"), "folded like a buffer key")
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#CHAN"))
        assertFalse(store.state.notifiesAlways(networkId = 1, target = "#other"))
        assertFalse(store.state.notifiesAlways(networkId = 2, target = "#chan"), "scoped to its network")

        // Turned off on the web while this device was away: it has to read off here.
        store.apply(snapshot(listOf("#other")))
        assertFalse(store.state.notifiesAlways(networkId = 1, target = "#chan"))
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#other"))
    }

    @Test
    fun testTheChangedFramePatchesOneChannel() {
        val store = LurkerStore()
        store.apply(snapshot(listOf("#other")))
        store.apply(ServerFrame.ChannelNotifyChanged(networkId = 1, target = "#Chan", bufferId = null, notifyAlways = true))
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#chan"))
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#other"), "the others stand")

        store.apply(ServerFrame.ChannelNotifyChanged(networkId = 1, target = "#CHAN", bufferId = null, notifyAlways = false))
        assertFalse(store.state.notifiesAlways(networkId = 1, target = "#chan"))
        assertEquals(1, store.state.notifyAlways.size, "off drops the entry rather than storing a false")
    }

    /**
     * The server keeps the flag when a channel is closed and reads it again on a rejoin, so
     * the store keeps it too: dropped here, a rejoined channel would read "Highlights only"
     * while every message still notified.
     */
    @Test
    fun testAClosedBufferKeepsItsFlag() {
        val store = LurkerStore()
        store.apply(snapshot(listOf("#chan", "#other")))
        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#Chan"))
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#chan"))
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#other"))
    }

    @Test
    fun testARenamedBufferCarriesItsFlag() {
        val store = LurkerStore()
        store.apply(snapshot(listOf("#chan")))
        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "#chan", to = "#renamed", bufferId = null, merged = false,
                mergedFromBufferId = null,
            ),
        )
        assertFalse(store.state.notifiesAlways(networkId = 1, target = "#chan"))
        assertTrue(store.state.notifiesAlways(networkId = 1, target = "#renamed"))
    }

    @Test
    fun testARemovedNetworkTakesItsFlagsWithIt() {
        val store = LurkerStore()
        // `#gone` has no buffer row here, so only the network sweep can reach it.
        store.apply(snapshot(listOf("#chan", "#gone")))
        store.apply(ServerFrame.ChannelNotifyChanged(networkId = 11, target = "#chan", bufferId = null, notifyAlways = true))
        store.apply(ServerFrame.Networks(emptyList()))
        assertFalse(store.state.notifiesAlways(networkId = 1, target = "#chan"))
        assertFalse(store.state.notifiesAlways(networkId = 1, target = "#gone"))
        assertTrue(
            store.state.notifiesAlways(networkId = 11, target = "#chan"),
            "network 11 is not network 1: the sweep matches the whole id",
        )
    }

    @Test
    fun testTheStateReadsARungFromBothStores() {
        val store = LurkerStore()
        store.apply(snapshot(emptyList()))
        val scope = NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")
        assertEquals(NotifyRung.Highlights, store.state.notifyRung(scope))

        store.apply(
            ServerFrame.IgnoreListUpdated(
                networkId = 1, rules = listOf(mute(5, channels = listOf("#chan"), levels = listOf("NONOTIFY"))),
            ),
        )
        assertEquals(NotifyRung.Nothing, store.state.notifyRung(scope))
        assertEquals(
            NotifyRung.Highlights, store.state.notifyRung(NotifyLadder.Scope.Network(networkId = 1)), "a channel's rule",
        )

        store.apply(ServerFrame.ChannelNotifyChanged(networkId = 1, target = "#chan", bufferId = null, notifyAlways = true))
        assertEquals(NotifyRung.All, store.state.notifyRung(scope))
        assertEquals(
            listOf(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#chan", notifyAlways = false),
                NotifyLadder.Step.RemoveRule(id = 5, scope = 1),
            ),
            store.state.notifyPlan(NotifyRung.Highlights, scope),
        )
    }

    // MARK: - Picking a rung

    /** Its own storage; connected, with one channel. */
    private fun viewModel(): ChatViewModel {
        val model = testViewModel()
        model.handle(ServerFrame.SocketOpen)
        model.handle(snapshot(emptyList(), channels = listOf("#chan")))
        return model
    }

    private fun recording(model: ChatViewModel, answering: Boolean = true): () -> List<NotifyLadder.Step> {
        val steps = mutableListOf<NotifyLadder.Step>()
        model.notifyStepSeam = { step ->
            steps.add(step)
            answering
        }
        return { steps.toList() }
    }

    /**
     * A rung is a target: it reconciles against the state at the tap, not as it was when the
     * menu opened (web lurker#1136).
     */
    @Test
    fun testPickingARungClearsNotifyAlwaysSetElsewhereWhileTheMenuWasOpen() {
        val model = viewModel()
        val sent = recording(model)
        val scope = NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")
        // What a menu drawn now would say: already on Highlights only, nothing to send.
        assertEquals(emptyList(), model.state.notifyPlan(NotifyRung.Highlights, scope))
        // Another device turns All messages on while that menu sits open.
        model.handle(ServerFrame.ChannelNotifyChanged(networkId = 1, target = "#chan", bufferId = null, notifyAlways = true))
        assertTrue(model.setNotifyRung(NotifyRung.Highlights, scope))
        assertEquals(
            listOf<NotifyLadder.Step>(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#chan", notifyAlways = false),
            ),
            sent(),
        )
    }

    @Test
    fun testPickingARungLeavesNotifyAlwaysAloneWhenItAlreadyMatchesAtTheTap() {
        val model = viewModel()
        val sent = recording(model)
        val scope = NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")
        model.handle(ServerFrame.ChannelNotifyChanged(networkId = 1, target = "#chan", bufferId = null, notifyAlways = true))
        // Turned off elsewhere; Highlights only now has nothing to change.
        model.handle(ServerFrame.ChannelNotifyChanged(networkId = 1, target = "#chan", bufferId = null, notifyAlways = false))
        assertTrue(model.setNotifyRung(NotifyRung.Highlights, scope), "nothing to do is not a failure")
        assertEquals(emptyList(), sent())
    }

    @Test
    fun testPickingARungSendsEveryStepInOrder() {
        val model = viewModel()
        val sent = recording(model)
        model.handle(ServerFrame.IgnoreListUpdated(networkId = null, rules = listOf(mute(7, channels = listOf("#chan")))))
        assertTrue(model.setNotifyRung(NotifyRung.All, NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")))
        assertEquals(
            listOf(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#chan", notifyAlways = true),
                NotifyLadder.Step.RemoveRule(id = 7, scope = null),
            ),
            sent(),
        )
    }

    /**
     * Muted then All messages, tapped before the first echo: sent at once, the second plan
     * would see no rule and send only the flag, leaving both standing. It waits, then goes.
     */
    @Test
    fun testASecondPickWaitsForTheFirstOnesEchoThenGoesOut() {
        val model = viewModel()
        val sent = recording(model)
        val scope = NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")
        assertTrue(model.setNotifyRung(NotifyRung.Muted, scope))
        val first = sent()
        assertEquals(1, first.size)

        assertTrue(model.setNotifyRung(NotifyRung.Nothing, scope))
        assertTrue(model.setNotifyRung(NotifyRung.All, scope), "kept, not failed")
        assertEquals(first, sent(), "nothing more went out yet")

        // The echo lands: the channel is on Muted, and the LAST pick goes out against that.
        model.handle(ServerFrame.IgnoreListUpdated(networkId = 1, rules = listOf(mute(9, channels = listOf("#chan")))))
        assertEquals(
            listOf(
                NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#chan", notifyAlways = true),
                NotifyLadder.Step.RemoveRule(id = 9, scope = 1),
            ),
            sent().drop(first.size),
        )
    }

    /**
     * Muted to All messages is two sends. After the flag's echo the rung already reads "All
     * messages", with the rule's removal still on its way; a pick let through then would plan
     * around a rule that is about to vanish.
     */
    @Test
    fun testAPickIsNotSettledUntilEveryEchoHasLanded() {
        val model = viewModel()
        val sent = recording(model)
        val scope = NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")
        model.handle(ServerFrame.IgnoreListUpdated(networkId = 1, rules = listOf(mute(9, channels = listOf("#chan")))))
        assertTrue(model.setNotifyRung(NotifyRung.All, scope))
        assertEquals(2, sent().size)

        model.handle(ServerFrame.ChannelNotifyChanged(networkId = 1, target = "#chan", bufferId = null, notifyAlways = true))
        assertEquals(NotifyRung.All, model.state.notifyRung(scope), "reads settled, and is not")
        assertTrue(model.setNotifyRung(NotifyRung.Muted, scope))
        assertEquals(2, sent().size, "held until the rule's removal echoes")

        model.handle(ServerFrame.IgnoreListUpdated(networkId = 1, rules = emptyList()))
        val after = sent().drop(2)
        assertEquals(
            NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#chan", notifyAlways = false), after.firstOrNull(),
        )
        assertEquals(2, after.size, "the flag off and a fresh mute rule")
    }

    /** A pick the server refused, or whose echo was lost, must not lock the ladder for good. */
    @Test
    fun testAPickThatNeverSettlesStopsHoldingTheLadderAfterTheWindow() {
        val model = viewModel()
        val sent = recording(model)
        val scope = NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")
        val start = Instant.now()
        assertTrue(model.setNotifyRung(NotifyRung.Muted, scope, now = start))
        val late = start.plus(ChatViewModel.notifySettleWindow).plusMillis(100)
        assertTrue(model.setNotifyRung(NotifyRung.All, scope, now = late))
        assertEquals(
            NotifyLadder.Step.SetNotifyAlways(networkId = 1, target = "#chan", notifyAlways = true), sent().lastOrNull(),
        )
    }

    /** One buffer waiting does not hold another. */
    @Test
    fun testAPendingPickHoldsOnlyItsOwnBuffer() {
        val model = viewModel()
        val sent = recording(model)
        val start = Instant.now()
        assertTrue(
            model.setNotifyRung(NotifyRung.Muted, NotifyLadder.Scope.Channel(networkId = 1, target = "#chan"), now = start),
        )
        assertTrue(
            model.setNotifyRung(NotifyRung.Muted, NotifyLadder.Scope.Channel(networkId = 1, target = "#other"), now = start),
        )
        assertEquals(2, sent().size)
    }

    /** Carrying on past a remove that never left would add a second rule beside the first. */
    @Test
    fun testAStepThatGoesNowhereStopsThePlanAndIsReported() {
        val model = viewModel()
        val sent = recording(model, answering = false)
        model.handle(
            ServerFrame.IgnoreListUpdated(
                networkId = 1, rules = listOf(mute(5, channels = listOf("#chan"), levels = listOf("NONOTIFY"))),
            ),
        )
        assertFalse(model.setNotifyRung(NotifyRung.Muted, NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")))
        assertEquals(listOf<NotifyLadder.Step>(NotifyLadder.Step.RemoveRule(id = 5, scope = 1)), sent())
    }

    /** Before the socket opens nothing can reach the server, whatever a seam would say. */
    @Test
    fun testPickingARungOfflineSendsNothingAndSaysSo() {
        val model = testViewModel()
        val sent = recording(model)
        assertFalse(model.setNotifyRung(NotifyRung.Muted, NotifyLadder.Scope.Channel(networkId = 1, target = "#chan")))
        assertEquals(emptyList(), sent())

        // And with no seam, on a model that reads connected but has no socket: the real sends
        // answer false.
        assertFalse(viewModel().setNotifyRung(NotifyRung.Muted, NotifyLadder.Scope.Network(networkId = 1)))
    }
}
