// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // MARK: - Search scope

    // MARK: - /dcc (the web's dcc.test.ts, chat half)

    // MARK: - /dcc help and completion

    // MARK: - Bare /whois and /ping in a chat

    // MARK: - Status light

    // MARK: - Wire

    // MARK: - Store

    // MARK: - Going to a chat once its buffer exists

    // MARK: - Opens and closes that race

    // Waiting on BufferOrder (and the private `buffer` helper): testAChatFilesAmongTheDmsUnderItsPeer
    //
    // Waiting on SearchQuery: testAChatCanScopeASearch
    //
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
    //
    // Waiting on PendingDccOpen (and `opened`): testAnOpenWaitsForTheRowThenGoesThere,
    // testAWaitKnowsWhichChatItIsFor, testARowThatLandsAfterTheDeadlineGoesNowhere
    //
    // Waiting on DccOpens (and the private `row` helper, `bobKey` and `carolKey`):
    // testACloseOvertakesAnOpenStillInFlight, testACloseOfAnotherChatDoesNot,
    // testAnOpenAfterACloseStillWaits, testACloseStopsAWaitBeforeItsOwnNoticeCanSatisfyIt,
    // testARefusedClosePutsTheWaitBack, testARefusedCloseDoesNotOverrideANewerOpen,
    // testTheLatestRequestWinsWhateverOrderTheRepliesArrive, testAReplyFromBeforeASignOutIsStale
}
