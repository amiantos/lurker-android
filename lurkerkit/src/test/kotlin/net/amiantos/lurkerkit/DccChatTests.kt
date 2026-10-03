// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.commands.ArgKind
import net.amiantos.lurkerkit.commands.CommandCompletion
import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.CommandRegistry
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferOrder
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.DccOpens
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.PendingDccOpen
import net.amiantos.lurkerkit.model.SearchQuery
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.store.SocketStatus
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
 * DCC CHAT (lurker#270): a `=nick` buffer is its own kind, the `/dcc` chat verbs follow irssi
 * exactly (the web's parser tests, ported), and the store holds which chats are live and which
 * offers are waiting — reconciled against every snapshot, because a phone misses live events.
 */
class DccChatTests {

    // MARK: - Names

    @Test
    fun testAnEqualsTargetIsADccChat() {
        assertEquals(BufferKind.Dcc, BufferKind.of(networkId = 1, target = "=bob"))
        assertEquals(BufferKind.Dm, BufferKind.of(networkId = 1, target = "bob"))
        assertEquals(BufferKind.Channel, BufferKind.of(networkId = 1, target = "#bob"))
        // A bare `=` is still no nick: classed as a DM, it would reach the wire as `PRIVMSG =`.
        assertEquals(BufferKind.Dcc, BufferKind.of(networkId = 1, target = "="))
    }

    @Test
    fun testThePeerIsTheNameAfterTheSigil() {
        assertEquals("bob", DccChat.peer("=bob"))
        assertEquals("bob", DccChat.peer("bob"), "a plain nick passes through")
        assertEquals("", DccChat.peer("="))
        assertEquals("=bob", DccChat.target("bob"))
    }

    @Test
    fun testADccChatShowsWhatADmShows() {
        assertTrue(BufferKind.Dcc.renders(EventType.Message))
        assertTrue(BufferKind.Dcc.renders(EventType.Notice))
        assertFalse(BufferKind.Dcc.renders(EventType.Motd))
        assertTrue(BufferKind.Dcc.hydratesOnDemand)
    }

    // MARK: - Order

    private fun buffer(target: String): Buffer =
        Buffer(networkId = 1, target = target, kind = BufferKind.of(networkId = 1, target = target))

    @Test
    fun testAChatFilesAmongTheDmsUnderItsPeer() {
        // Port note: LurkerKit hands `BufferOrder.order` straight to `sorted(by:)`; a Kotlin sort
        // wants the three-way answer, which is the same test asked both ways round.
        val sorted = listOf(buffer("=zed"), buffer("carol"), buffer("=bob"), buffer("#lurker"), buffer("bob"))
            .sortedWith { lhs, rhs ->
                if (BufferOrder.order(lhs, rhs)) -1 else if (BufferOrder.order(rhs, lhs)) 1 else 0
            }
            .map { it.target }
        // Keyed on the buffer name, every chat would pile up at the top of the DMs under `=`.
        assertEquals(listOf("#lurker", "bob", "=bob", "carol", "=zed"), sorted)
    }

    // MARK: - Search scope

    @Test
    fun testAChatCanScopeASearch() {
        assertEquals("in:=bob on:libera ", SearchQuery.scope(buffer("=bob"), networkName = "libera"))
    }

    // MARK: - /dcc (the web's dcc.test.ts, chat half)

    // Port note: `CommandParser.parse` takes the expiry's formatter here (see
    // `IgnoreRule.summary`); no `/dcc` line uses it.
    private fun effects(input: String, networkId: Int? = 1, target: String = "#chan"): List<CommandEffect> {
        val parsed = CommandParser.parse(input, networkId = networkId, target = target, formatted = { it.toString() })
        if (parsed !is ParsedInput.Command) fail("expected a command from $input")
        return parsed.effects
    }

    /** The info line a command answered with, or null if it did something else. */
    private fun info(input: String, target: String = "#chan"): String? =
        (effects(input, target = target).firstOrNull() as? CommandEffect.Info)?.text

    @Test
    fun testDccChatOffersToANick() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.DccChat(nick = "alice", passive = false)),
            effects("/dcc chat alice"),
        )
        assertEquals(listOf<CommandEffect>(CommandEffect.DccChat(nick = "Bob", passive = false)), effects("/DCC CHAT Bob"))
        assertNotNull(info("/dcc chat"))
    }

    /**
     * Opt-in, never a fallback: WeeChat and HexDroid turn a passive offer into a silent dial to
     * port 0, so it has to be asked for by name.
     */
    @Test
    fun testPassiveIsAFlagInEitherPosition() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.DccChat(nick = "alice", passive = true)),
            effects("/dcc chat -passive alice"),
        )
        assertEquals(
            listOf<CommandEffect>(CommandEffect.DccChat(nick = "alice", passive = true)),
            effects("/dcc chat alice -passive"),
        )
    }

    @Test
    fun testAnUnknownOptionIsRefusedRatherThanReadAsANick() {
        assertEquals(true, info("/dcc chat -active alice")?.contains("-active"))
    }

    /** Read literally, `/dcc chat close bob` would OFFER a chat to a peer named "close". */
    @Test
    fun testChatCloseIsCaughtAndPointedAtTheRealSpelling() {
        assertEquals(true, info("/dcc chat close alice")?.contains("/dcc close chat <nick>"))
        // …but someone genuinely nicked "close" is still reachable.
        assertEquals(listOf<CommandEffect>(CommandEffect.DccChat(nick = "close", passive = false)), effects("/dcc chat close"))
    }

    /**
     * ⚠⚠ The web's QA finding: a `/dcc close <nick>` shorthand read irssi's `/dcc close chat bob`
     * as closing a chat with a peer called "chat", and left the real one open.
     */
    @Test
    fun testCloseIsIrssisTypeFirstForm() {
        assertEquals(
            listOf<CommandEffect>(CommandEffect.DccCloseChat(nick = "ami|shellter")),
            effects("/dcc close chat ami|shellter"),
        )
        assertEquals(listOf<CommandEffect>(CommandEffect.DccCloseChat(nick = "bob")), effects("/dcc close CHAT bob"))
        assertNotNull(info("/dcc close bob"), "the non-irssi shorthand is gone")
        assertNotNull(info("/dcc close"))
        assertNotNull(info("/dcc close chat"), "asks for a nick rather than closing a peer named chat")
        assertEquals(listOf<CommandEffect>(CommandEffect.DccCloseChat(nick = "chat")), effects("/dcc close chat chat"))
        assertNotNull(info("/dcc close chat bob extra"))
    }

    /**
     * `=bob` is the BUFFER; `/dcc chat =bob` would offer to someone literally named "=bob". And a
     * channel would broadcast the offer to everyone in it — all four sigils.
     */
    @Test
    fun testABufferNameOrAChannelIsNotAPeer() {
        for (input in listOf(
            "/dcc chat =bob", "/dcc chat #room", "/dcc chat &local", "/dcc chat +modeless",
            "/dcc chat !safe", "/dcc close chat =bob", "/dcc close chat #room",
        )) {
            assertNotNull(info(input), input)
        }
    }

    @Test
    fun testFileTransfersSayTheyAreNotHereRatherThanGoingRaw() {
        for (input in listOf(
            "/dcc list", "/dcc accept 3", "/dcc cancel 3", "/dcc close send bob",
            "/dcc send bob file.txt", "/dcc resume bob file.txt",
        )) {
            assertEquals("DCC file transfers aren't in the app yet.", info(input), input)
        }
        // And nothing falls through to the raw default, which put `DCC chat bob` on the IRC wire.
        for (input in listOf("/dcc", "/dcc frobnicate", "/dcc chat")) {
            assertNotNull(info(input), input)
        }
    }

    @Test
    fun testDccNeedsANetwork() {
        val effects = effects("/dcc chat bob", networkId = null, target = Buffer.systemTarget)
        if (effects.firstOrNull() !is CommandEffect.Info) fail("expected the network gate")
        assertEquals(1, effects.size)
    }

    // MARK: - /dcc help and completion

    /**
     * Both come from the spec, and one positional list could only describe `/dcc` as a shape
     * neither form has (`/dcc <chat|close chat> <nick>`, no `-passive`).
     */
    @Test
    fun testTheHelpShowsBothFormsAsTheyAreTyped() {
        assertEquals(
            "/dcc chat [-passive] <nick> · /dcc close chat <nick>",
            CommandRegistry.spec("dcc")?.usage,
        )
    }

    private fun completes(text: String): ArgKind? =
        (CommandCompletion.context(text, caret = text.length) as? CommandCompletion.Context.Argument)?.kind

    @Test
    fun testCompletionFindsTheNickInEitherForm() {
        assertEquals(ArgKind.Nick, completes("/dcc chat b"))
        assertEquals(ArgKind.Nick, completes("/dcc chat "), "the optional flag is skipped")
        assertEquals(ArgKind.Nick, completes("/dcc chat -passive b"))
        assertEquals(ArgKind.Nick, completes("/dcc close chat b"))
        assertNull(completes("/dcc close b"), "the word after close is `chat`, not a nick")
        assertNull(completes("/dcc chat -pa"), "a flag is typed, not completed")
        assertNull(completes("/dcc ch"))
        assertNull(completes("/dcc chat bob b"), "nothing after the nick")
    }

    // MARK: - Bare /whois and /ping in a chat

    /**
     * ⚠⚠ Both put their argument on the IRC wire, and `/ping` as a CTCP no server guard covers —
     * so a bare one in `=bob` must mean bob.
     */
    @Test
    fun testABareWhoisOrPingInAChatMeansThePeer() {
        assertEquals(listOf<CommandEffect>(CommandEffect.ShowProfile(nick = "bob")), effects("/whois", target = "=bob"))
        assertEquals(
            listOf<CommandEffect>(CommandEffect.Ctcp(target = "bob", type = "PING", args = "")),
            effects("/ping", target = "=bob"),
        )
        assertNotNull(info("/ping", target = "="), "a bare `=` has no peer to ping")
    }

    // MARK: - Status light

    /** The chat's session, never the network's state: the chat works while the IRC link is down. */
    @Test
    fun testTheLightFollowsTheSessionNotTheNetwork() {
        assertEquals(StatusLight.Good, StatusLight.ofDccChat(reachable = true, connection = SocketStatus.Connected, live = true))
        assertEquals(StatusLight.Bad, StatusLight.ofDccChat(reachable = true, connection = SocketStatus.Connected, live = false))
        assertEquals(
            StatusLight.Warn, StatusLight.ofDccChat(reachable = true, connection = SocketStatus.Connected, live = null),
            "not known until this socket's snapshot lands",
        )
        // The outer layers still win: a live chat is out of reach from a phone with no path.
        assertEquals(StatusLight.Bad, StatusLight.ofDccChat(reachable = false, connection = SocketStatus.Connected, live = true))
        assertEquals(
            StatusLight.Warn,
            StatusLight.ofDccChat(reachable = true, connection = SocketStatus.Reconnecting, live = true),
        )
    }

    // MARK: - Wire

    @Test
    fun testTheSnapshotCarriesLiveChatsAndWaitingOffers() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":1,"state":"disconnected","nick":"me","channels":[],"dccChats":["Bob",""],"dccChatOffers":["carol"]}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("got $frame")
        assertEquals(listOf("Bob"), frame.networks.firstOrNull()?.dccChats, "an empty peer names no one")
        assertEquals(listOf("carol"), frame.networks.firstOrNull()?.dccChatOffers)
    }

    @Test
    fun testTheThreeEventsNameThePeerInTheFromField() {
        assertEquals(
            ServerFrame.DccChatOffer(networkId = 1, nick = "bob", passive = true),
            FrameParser.parseWs("""{"kind":"irc","type":"dcc-chat-offer","networkId":1,"target":":server:1","from":"bob","passive":true}"""),
        )
        assertEquals(
            ServerFrame.DccChatOfferClosed(networkId = 1, nick = "bob"),
            FrameParser.parseWs("""{"kind":"irc","type":"dcc-chat-offer-closed","networkId":1,"target":":server:1","from":"bob"}"""),
        )
        assertEquals(
            ServerFrame.DccChatState(networkId = 1, nick = "bob", live = true),
            FrameParser.parseWs("""{"kind":"irc","type":"dcc-chat-state","networkId":1,"target":":server:1","from":"bob","live":true}"""),
        )
        // Nobody named, nothing to key on.
        assertEquals(
            ServerFrame.Ignored,
            FrameParser.parseWs("""{"kind":"irc","type":"dcc-chat-offer","networkId":1,"target":":server:1"}"""),
        )
    }

    // MARK: - Store

    private fun snapshot(
        id: Int = 1,
        state: ConnectionState = ConnectionState.Connected,
        chats: List<String> = emptyList(),
        offers: List<String> = emptyList(),
    ): ServerFrame =
        ServerFrame.Snapshot(
            listOf(
                NetworkSnapshot(
                    id = id, state = state, nick = "me", channels = emptyList(), dccChats = chats, dccChatOffers = offers,
                ),
            ),
            globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
        )

    private val bob = BufferKey(networkId = 1, target = "=bob")

    /**
     * The reverse of a DM: the chat is a socket straight to the peer, so a dropped network says
     * nothing about it.
     */
    @Test
    fun testAChatIsLiveWhateverTheNetworkIsDoing() {
        val store = LurkerStore()
        store.apply(snapshot(state = ConnectionState.Disconnected, chats = listOf("Bob")))
        assertTrue(store.state.isDccChatLive(bob), "listed live, and case-folded")
        assertFalse(store.state.isDccChatLive(BufferKey(networkId = 1, target = "=carol")))
        assertFalse(store.state.isDccChatLive(BufferKey(networkId = 1, target = "bob")), "a DM is not a chat")
    }

    @Test
    fun testLiveStateFollowsTheEvents() {
        val store = LurkerStore()
        store.apply(snapshot())
        store.apply(ServerFrame.DccChatState(networkId = 1, nick = "bob", live = true))
        assertTrue(store.state.isDccChatLive(bob))
        store.apply(ServerFrame.DccChatState(networkId = 1, nick = "BOB", live = false))
        assertFalse(store.state.isDccChatLive(bob))
    }

    @Test
    fun testTheSnapshotReplacesLiveChatsWholesale() {
        val store = LurkerStore()
        store.apply(ServerFrame.DccChatState(networkId = 1, nick = "bob", live = true))
        // The chat ended while this device was away: only the snapshot can say so.
        store.apply(snapshot(chats = emptyList()))
        assertFalse(store.state.isDccChatLive(bob))
    }

    @Test
    fun testAnOfferWaitsUntilTheServerClosesIt() {
        val store = LurkerStore()
        store.apply(ServerFrame.DccChatOffer(networkId = 1, nick = "bob", passive = true))
        assertEquals(listOf("bob"), store.state.dccChatOffers.map { it.nick })
        assertEquals(true, store.state.dccChatOffers.firstOrNull()?.passive)
        assertEquals(bob, store.state.dccChatOffers.firstOrNull()?.key)
        store.apply(ServerFrame.DccChatOfferClosed(networkId = 1, nick = "Bob"))
        assertEquals(emptyList(), store.state.dccChatOffers)
    }

    /** Offering again is a new question, so it gets a new id — which is what makes the app ask it. */
    @Test
    fun testAnOfferMadeAgainReplacesTheOldOneWithANewId() {
        val store = LurkerStore()
        store.apply(ServerFrame.DccChatOffer(networkId = 1, nick = "bob", passive = false))
        val first = store.state.dccChatOffers[0].id
        store.apply(ServerFrame.DccChatOffer(networkId = 1, nick = "bob", passive = false))
        assertEquals(1, store.state.dccChatOffers.size)
        assertNotEquals(first, store.state.dccChatOffers[0].id)
    }

    /**
     * A reconnect re-lists an offer still pending. It must stay the SAME offer, or the app would
     * ask about it again on every reconnect.
     */
    @Test
    fun testASnapshotKeepsAnOfferWeAlreadyHold() {
        val store = LurkerStore()
        store.apply(ServerFrame.DccChatOffer(networkId = 1, nick = "bob", passive = true))
        val held = store.state.dccChatOffers[0]
        store.apply(snapshot(offers = listOf("Bob")))
        assertEquals(listOf(held), store.state.dccChatOffers, "same id, and `passive` survives")
    }

    /** The case the snapshot exists for: the offer came in while the phone's socket was asleep. */
    @Test
    fun testASnapshotSurfacesAnOfferWeMissed() {
        val store = LurkerStore()
        store.apply(snapshot(offers = listOf("carol")))
        assertEquals(listOf("carol"), store.state.dccChatOffers.map { it.nick })
        assertEquals(false, store.state.dccChatOffers.firstOrNull()?.passive, "the snapshot doesn't say")
    }

    /**
     * …and the other half: the offer was answered or expired while we weren't listening, so the
     * closing event never reached us.
     */
    @Test
    fun testASnapshotRetiresAnOfferItNoLongerLists() {
        val store = LurkerStore()
        store.apply(ServerFrame.DccChatOffer(networkId = 1, nick = "bob", passive = false))
        store.apply(snapshot(offers = emptyList()))
        assertEquals(emptyList(), store.state.dccChatOffers)
    }

    /**
     * ⚠ The list survives a reconnect until the new snapshot replaces it, so until then nobody may
     * read it as an answer — the info sheet offered End or Start off the last session's list.
     */
    @Test
    fun testASessionIsUnknownUntilThisSocketsSnapshot() {
        val store = LurkerStore()
        assertNull(store.state.dccChatSession(bob), "nothing heard yet")
        store.apply(ServerFrame.SocketOpen)
        store.apply(snapshot(chats = listOf("bob")))
        assertEquals(true, store.state.dccChatSession(bob))
        assertEquals(false, store.state.dccChatSession(BufferKey(networkId = 1, target = "=carol")))

        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertNull(store.state.dccChatSession(bob), "the socket is down; the list is last session's")
        store.apply(ServerFrame.SocketOpen)
        assertNull(store.state.dccChatSession(bob), "back, but the snapshot hasn't landed")
        store.apply(snapshot(chats = emptyList()))
        assertEquals(false, store.state.dccChatSession(bob))
    }

    /** A deleted network's chats end with it, and its offers can't be answered any more. */
    @Test
    fun testDroppingANetworkForgetsItsChatsAndOffers() {
        val store = LurkerStore()
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList(),
                        dccChats = listOf("bob"), dccChatOffers = listOf("carol"),
                    ),
                    NetworkSnapshot(
                        id = 2, state = ConnectionState.Connected, nick = "me", channels = emptyList(),
                        dccChatOffers = listOf("dave"),
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        store.apply(ServerFrame.Networks(listOf(Network(id = 2, name = "Other"))))
        assertFalse(store.state.isDccChatLive(bob))
        assertEquals(listOf("dave"), store.state.dccChatOffers.map { it.nick }, "the other network's offer stays")
    }

    // MARK: - Going to a chat once its buffer exists

    private val opened = Instant.ofEpochSecond(1_000)

    @Test
    fun testAnOpenWaitsForTheRowThenGoesThere() {
        val pending = PendingDccOpen(networkId = 1, nick = "bob", now = opened)
        assertEquals(
            PendingDccOpen.Outcome.Waiting,
            pending.settle(buffers = emptyMap(), now = opened.plusSeconds(1)),
        )
        val row = Buffer(networkId = 1, target = "=Bob", kind = BufferKind.Dcc)
        assertEquals(
            PendingDccOpen.Outcome.Open(row.key),
            pending.settle(buffers = mapOf(row.key.id to row), now = opened.plusSeconds(1)),
            "the server's spelling of the name",
        )
    }

    /** What a close checks before it cancels the wait: the same chat, however it was spelled. */
    @Test
    fun testAWaitKnowsWhichChatItIsFor() {
        val pending = PendingDccOpen(networkId = 1, nick = "bob", now = opened)
        assertTrue(pending.isFor(networkId = 1, nick = "Bob"))
        assertFalse(pending.isFor(networkId = 1, nick = "carol"))
        assertFalse(pending.isFor(networkId = 2, nick = "bob"))
    }

    /**
     * ⚠ The deadline wins over a row that arrives late: by then nobody is waiting for it, and
     * going there would pull the user out of whatever they're reading.
     */
    @Test
    fun testARowThatLandsAfterTheDeadlineGoesNowhere() {
        val pending = PendingDccOpen(networkId = 1, nick = "bob", now = opened)
        val late = opened.plus(PendingDccOpen.patience.plusSeconds(1))
        assertEquals(PendingDccOpen.Outcome.Expired, pending.settle(buffers = emptyMap(), now = late))
        val row = Buffer(networkId = 1, target = "=bob", kind = BufferKind.Dcc)
        assertEquals(
            PendingDccOpen.Outcome.Expired,
            pending.settle(buffers = mapOf(row.key.id to row), now = late),
        )
    }

    // MARK: - Opens and closes that race

    private fun row(target: String): Map<String, Buffer> {
        val buffer = Buffer(networkId = 1, target = target, kind = BufferKind.Dcc)
        return mapOf(buffer.key.id to buffer)
    }

    private val bobKey = BufferKey(networkId = 1, target = "=bob")
    private val carolKey = BufferKey(networkId = 1, target = "=carol")

    /**
     * ⚠⚠ Start, then End before Start's request returns: the close found nothing to cancel, and
     * Start's reply then installed a wait that took the user into the chat they had just ended.
     */
    @Test
    fun testACloseOvertakesAnOpenStillInFlight() {
        val opens = DccOpens()
        val ticket = opens.begin(networkId = 1, nick = "bob")
        opens.closing(networkId = 1, nick = "Bob")
        opens.opened(ticket, now = opened)
        assertNull(opens.waiting)
        assertNull(opens.settle(buffers = row("=bob"), now = opened))
    }

    @Test
    fun testACloseOfAnotherChatDoesNot() {
        val opens = DccOpens()
        val ticket = opens.begin(networkId = 1, nick = "bob")
        opens.closing(networkId = 1, nick = "carol")
        opens.opened(ticket, now = opened)
        assertEquals(bobKey, opens.settle(buffers = row("=bob"), now = opened))
    }

    /** Once a close is behind it, opening the same chat again works as it did the first time. */
    @Test
    fun testAnOpenAfterACloseStillWaits() {
        val opens = DccOpens()
        opens.closing(networkId = 1, nick = "bob")
        val ticket = opens.begin(networkId = 1, nick = "bob")
        opens.opened(ticket, now = opened)
        assertNotNull(opens.waiting)
    }

    /**
     * ⚠⚠ The close marks BEFORE its request goes out: its own "Cancelled…" notice mints the row
     * over the socket, usually ahead of the HTTP reply, and must find nothing waiting.
     */
    @Test
    fun testACloseStopsAWaitBeforeItsOwnNoticeCanSatisfyIt() {
        val opens = DccOpens()
        opens.opened(opens.begin(networkId = 1, nick = "bob"), now = opened)
        opens.closing(networkId = 1, nick = "bob")
        assertNull(opens.settle(buffers = row("=bob"), now = opened))
    }

    /** A refused close ended nothing: the chat is still coming, so the wait comes back. */
    @Test
    fun testARefusedClosePutsTheWaitBack() {
        val opens = DccOpens()
        opens.opened(opens.begin(networkId = 1, nick = "bob"), now = opened)
        val mark = opens.closing(networkId = 1, nick = "bob")
        opens.closeRefused(mark)
        assertEquals(bobKey, opens.settle(buffers = row("=bob"), now = opened))
    }

    /** …unless the user has asked for something else since. */
    @Test
    fun testARefusedCloseDoesNotOverrideANewerOpen() {
        val opens = DccOpens()
        opens.opened(opens.begin(networkId = 1, nick = "bob"), now = opened)
        val mark = opens.closing(networkId = 1, nick = "bob")
        opens.begin(networkId = 1, nick = "carol")
        opens.closeRefused(mark)
        assertNull(opens.waiting)
    }

    /**
     * One wait, deliberately — and "last" means the last ASKED, not the last to answer. Bob's
     * reply arriving after Carol's is stale and must not take the user to Bob.
     */
    @Test
    fun testTheLatestRequestWinsWhateverOrderTheRepliesArrive() {
        val opens = DccOpens()
        val bob = opens.begin(networkId = 1, nick = "bob")
        val carol = opens.begin(networkId = 1, nick = "carol")
        opens.opened(carol, now = opened)
        opens.opened(bob, now = opened)
        assertNull(opens.settle(buffers = row("=bob"), now = opened))
        assertEquals(carolKey, opens.settle(buffers = row("=carol"), now = opened))
        assertNull(opens.waiting, "settled once, then done")
    }

    /** An open sent before a sign-out must not land in whoever signs in next. */
    @Test
    fun testAReplyFromBeforeASignOutIsStale() {
        val opens = DccOpens()
        val ticket = opens.begin(networkId = 1, nick = "bob")
        opens.reset()
        opens.opened(ticket, now = opened)
        assertNull(opens.waiting)
    }

    @Test
    fun testSignOutForgetsOffersAndChats() {
        val store = LurkerStore()
        store.apply(snapshot(chats = listOf("bob"), offers = listOf("carol")))
        store.reset()
        assertEquals(emptyList(), store.state.dccChatOffers)
        assertFalse(store.state.isDccChatLive(bob))
    }
}
