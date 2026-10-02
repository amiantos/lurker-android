// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferOrder
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.DccOpens
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.PendingDccOpen
import net.amiantos.lurkerkit.model.SearchQuery
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // MARK: - /dcc help and completion

    // MARK: - Bare /whois and /ping in a chat

    // MARK: - Status light

    // MARK: - Wire

    // MARK: - Store

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

    // Waiting on CommandParser, CommandEffect (and the private `effects`/`info` helpers):
    // testDccChatOffersToANick, testPassiveIsAFlagInEitherPosition,
    // testAnUnknownOptionIsRefusedRatherThanReadAsANick,
    // testChatCloseIsCaughtAndPointedAtTheRealSpelling, testCloseIsIrssisTypeFirstForm,
    // testABufferNameOrAChannelIsNotAPeer, testFileTransfersSayTheyAreNotHereRatherThanGoingRaw,
    // testDccNeedsANetwork, testABareWhoisOrPingInAChatMeansThePeer
    //
    // Waiting on CommandRegistry: testTheHelpShowsBothFormsAsTheyAreTyped
    //
    // Waiting on CommandCompletion, ArgKind (and the private `completes` helper):
    // testCompletionFindsTheNickInEitherForm
    //
    // Waiting on StatusLight: testTheLightFollowsTheSessionNotTheNetwork
    //
    // Waiting on FrameParser, ServerFrame: testTheSnapshotCarriesLiveChatsAndWaitingOffers,
    // testTheThreeEventsNameThePeerInTheFromField
    //
    // Waiting on LurkerStore, ChatState, ServerFrame, NetworkSnapshot (and the private `snapshot`
    // helper and `bob`): testAChatIsLiveWhateverTheNetworkIsDoing, testLiveStateFollowsTheEvents,
    // testTheSnapshotReplacesLiveChatsWholesale, testAnOfferWaitsUntilTheServerClosesIt,
    // testAnOfferMadeAgainReplacesTheOldOneWithANewId, testASnapshotKeepsAnOfferWeAlreadyHold,
    // testASnapshotSurfacesAnOfferWeMissed, testASnapshotRetiresAnOfferItNoLongerLists,
    // testASessionIsUnknownUntilThisSocketsSnapshot, testDroppingANetworkForgetsItsChatsAndOffers,
    // testSignOutForgetsOffersAndChats
}
