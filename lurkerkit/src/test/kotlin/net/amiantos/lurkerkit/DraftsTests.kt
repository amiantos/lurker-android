// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.DraftEntry
import net.amiantos.lurkerkit.model.DraftReply
import net.amiantos.lurkerkit.model.DraftSync
import net.amiantos.lurkerkit.model.Drafts
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.ReplyParent
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Composer drafts that follow you across devices, their pending reply included (lurker-ios#188). */
class DraftsTests {

    private val chat = BufferKey(networkId = 1, target = "#chat")
    private val alice = ReplyParent(id = 7, nick = "alice", type = EventType.Message, text = "lunch?", userhost = "alice!a@host")

    private fun entry(
        key: BufferKey? = null,
        body: String = "half a thought",
        reply: DraftReply? = null,
        carriesReply: Boolean = true,
    ): DraftEntry {
        val resolved = key ?: chat
        return DraftEntry(
            networkId = resolved.networkId!!, target = resolved.target, body = body, reply = reply, carriesReply = carriesReply,
        )
    }

    // MARK: - Wire

    @Test
    fun testParsesTheSnapshotWithItsReplies() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"draft-snapshot","drafts":[
              {"networkId":1,"target":"#chat","bufferId":4,"body":"alice: sure","updatedAt":"2026-10-01 10:00:00",
               "reply":{"messageId":7,"addressed":true,"parent":{"id":7,"nick":"alice","type":"message","text":"lunch?","userhost":"alice!a@host","self":false}}},
              {"networkId":1,"target":"bob","body":"","reply":{"messageId":9,"addressed":false,"parent":null}},
              {"networkId":2,"target":"#x","body":"plain","reply":null},
              {"target":"#nowhere","body":"no network"}
            ]}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.DraftSnapshot) fail("$frame")
        assertEquals(
            listOf(
                DraftEntry(
                    networkId = 1, target = "#chat", body = "alice: sure",
                    reply = DraftReply(messageId = 7, addressed = true, parent = alice),
                ),
                DraftEntry(
                    networkId = 1, target = "bob", body = "",
                    reply = DraftReply(messageId = 9, addressed = false, parent = null),
                ),
                DraftEntry(networkId = 2, target = "#x", body = "plain", reply = null),
            ),
            frame.entries,
            "an entry with no network is a draft for nowhere",
        )
    }

    @Test
    fun testAnUpdateWithoutAReplyKeyIsNotOneThatClearsIt() {
        // ⚠⚠ `has` reads a null as absent; this is the one place the two mean different things.
        val cleared = FrameParser.parseWs("""{"kind":"draft-updated","networkId":1,"target":"#chat","body":"x","reply":null}""")
        val silent = FrameParser.parseWs("""{"kind":"draft-updated","networkId":1,"target":"#chat","body":"x"}""")
        assertEquals(ServerFrame.DraftUpdated(entry(body = "x", reply = null, carriesReply = true)), cleared)
        assertEquals(ServerFrame.DraftUpdated(entry(body = "x", reply = null, carriesReply = false)), silent)
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"draft-updated","networkId":1,"body":"x"}"""))
    }

    // MARK: - The store

    @Test
    fun testTheSnapshotResolvesRepliesAndSkipsEmptyDrafts() {
        var state = ChatState()
        state = state.seedDrafts(
            listOf(
                entry(body = "alice: sure", reply = DraftReply(messageId = 7, addressed = true, parent = alice)),
                entry(BufferKey(networkId = 1, target = "#empty"), body = ""),
                entry(BufferKey(networkId = 1, target = "#gone"), body = "", reply = DraftReply(messageId = 3, addressed = false, parent = null)),
            ),
        )
        assertEquals(
            ComposerDraft(
                body = "alice: sure",
                reply = PendingReply(
                    messageId = 7, nick = "alice", type = EventType.Message, text = "lunch?", isSelf = false, addressed = true,
                ),
            ),
            state.drafts[chat.id],
        )
        assertFalse(state.hasDraft(BufferKey(networkId = 1, target = "#empty")))
        assertFalse(
            state.hasDraft(BufferKey(networkId = 1, target = "#gone")),
            "a reply to a line that's gone is no reply, and with no text there's nothing left",
        )
        assertTrue(state.hasDraft(BufferKey(networkId = 1, target = "#CHAT")), "keys fold case")
    }

    @Test
    fun testAReplyWithNothingTypedIsADraft() {
        var state = ChatState()
        state = state.seedDrafts(listOf(entry(body = "", reply = DraftReply(messageId = 7, addressed = false, parent = alice))))
        assertTrue(state.hasDraft(chat))
    }

    @Test
    fun testTheSnapshotLeavesWhatThisDeviceIsHolding() {
        val mine = ComposerDraft(body = "mine, newer")
        val other = BufferKey(networkId = 1, target = "#other")
        val unlisted = BufferKey(networkId = 1, target = "#unlisted")
        var state = ChatState(
            drafts = mapOf(
                chat.id to mine,
                other.id to ComposerDraft(body = "stale"),
                unlisted.id to ComposerDraft(body = "typed before the server ever heard"),
            ),
        )
        state = state.seedDrafts(listOf(entry(body = "older"), entry(other, body = "fresh")), keeping = setOf(chat.id, unlisted.id))
        assertEquals(mine, state.drafts[chat.id])
        assertEquals(ComposerDraft(body = "fresh"), state.drafts[other.id])
        assertEquals("typed before the server ever heard", state.drafts[unlisted.id]?.body)
    }

    @Test
    fun testTheSnapshotIsAuthoritativeForWhatItLeavesOut() {
        var state = ChatState(drafts = mapOf(chat.id to ComposerDraft(body = "sent from the browser since")))
        state = state.seedDrafts(emptyList())
        assertNull(state.drafts[chat.id])
    }

    @Test
    fun testAnUpdateFromAnOlderServerKeepsTheReply() {
        var state = ChatState()
        state = state.seedDrafts(listOf(entry(body = "alice: ", reply = DraftReply(messageId = 7, addressed = true, parent = alice))))
        state = state.applyDraftUpdate(entry(body = "alice: on my way", carriesReply = false))
        assertEquals("alice: on my way", state.drafts[chat.id]?.body)
        assertEquals(7L, state.drafts[chat.id]?.reply?.messageId)

        state = state.applyDraftUpdate(entry(body = "alice: on my way", reply = null, carriesReply = true))
        assertNull(state.drafts[chat.id]?.reply, "reply: null clears it")
        state = state.applyDraftUpdate(entry(body = ""))
        assertNull(state.drafts[chat.id], "emptied on another device")
    }

    @Test
    fun testARelayedLineIsRepliedToAsThePersonInside() {
        // The strip and a cancel's `nick: ` both name who the Reply addressed — carol, not the bot.
        var state = ChatState(relayBots = RelayBotSet.empty.applying(networkId = 1, nick = "bridge", marked = true, pattern = ""))
        val relayed = ReplyParent(id = 8, nick = "bridge", type = EventType.Message, text = "<carol> hello there")
        state = state.seedDrafts(listOf(entry(body = "carol: hi", reply = DraftReply(messageId = 8, addressed = true, parent = relayed))))
        assertEquals("carol", state.drafts[chat.id]?.reply?.nick)
        assertEquals("hello there", state.drafts[chat.id]?.reply?.text)
    }

    @Test
    fun testALineFromSomeoneIgnoredSinceIsStillTheReplyWithoutTheirWords() {
        var state = ChatState(
            ignores = IgnoreSet(global = listOf(IgnoreRule(id = 1, mask = "alice!*@*")), byNetwork = emptyMap()),
        )
        state = state.seedDrafts(listOf(entry(body = "", reply = DraftReply(messageId = 7, addressed = false, parent = alice))))
        assertEquals(7L, state.drafts[chat.id]?.reply?.messageId)
        assertEquals("alice", state.drafts[chat.id]?.reply?.nick)
        assertEquals("", state.drafts[chat.id]?.reply?.text)
    }

    @Test
    fun testAClosedBufferTakesItsDraft() {
        var state = ChatState(drafts = mapOf(chat.id to ComposerDraft(body = "x")))
        state = LurkerStore.reduce(state, ServerFrame.BufferClosed(networkId = 1, target = "#chat"))
        assertNull(state.drafts[chat.id])
    }

    @Test
    fun testADeletedNetworkTakesItsDraftsAndOnlyItsDrafts() {
        val eleven = BufferKey(networkId = 11, target = "#chat")
        var state = ChatState(drafts = mapOf(chat.id to ComposerDraft(body = "x"), eleven.id to ComposerDraft(body = "y")))
        state = state.dropNetwork(1)
        assertNull(state.drafts[chat.id])
        assertNotNull(state.drafts[eleven.id], "network 11 is not network 1")
    }

    @Test
    fun testARenameCarriesTheDraft() {
        val old = BufferKey(networkId = 1, target = "bob")
        var state = ChatState(
            buffers = mapOf(old.id to Buffer(networkId = 1, target = "bob", kind = BufferKind.Dm)),
            drafts = mapOf(old.id to ComposerDraft(body = "you there?")),
        )
        state = LurkerStore.reduce(
            state,
            ServerFrame.BufferRenamed(
                networkId = 1, from = "bob", to = "bobby", bufferId = null, merged = false, mergedFromBufferId = null,
            ),
        )
        assertNull(state.drafts[old.id])
        assertEquals("you there?", state.drafts[BufferKey(networkId = 1, target = "bobby").id]?.body)
    }

    @Test
    fun testARenameCarriesADraftWhoseRowHasntArrived() {
        val old = BufferKey(networkId = 1, target = "bob")
        var state = ChatState(drafts = mapOf(old.id to ComposerDraft(body = "seeded before the backlog")))
        state = LurkerStore.reduce(
            state,
            ServerFrame.BufferRenamed(
                networkId = 1, from = "bob", to = "bob_", bufferId = 5, merged = false, mergedFromBufferId = null,
            ),
        )
        assertNull(state.drafts[old.id])
        assertEquals("seeded before the backlog", state.drafts[BufferKey(networkId = 1, target = "bob_").id]?.body)
    }

    @Test
    fun testAMergeKeepsTheSurvivorsDraft() {
        // The renamed buffer survives; the one that already held the name is absorbed. The server
        // keeps the survivor's draft and sends a draft-updated if that changed anything.
        val survivor = BufferKey(networkId = 1, target = "bob")
        val absorbed = BufferKey(networkId = 1, target = "bobby")
        var state = ChatState(
            buffers = mapOf(
                survivor.id to Buffer(networkId = 1, target = "bob", kind = BufferKind.Dm),
                absorbed.id to Buffer(networkId = 1, target = "bobby", kind = BufferKind.Dm),
            ),
            drafts = mapOf(
                survivor.id to ComposerDraft(body = "survivor's"),
                absorbed.id to ComposerDraft(body = "absorbed"),
            ),
        )
        state = LurkerStore.reduce(
            state,
            ServerFrame.BufferRenamed(
                networkId = 1, from = "bob", to = "bobby", bufferId = 5, merged = true, mergedFromBufferId = 6,
            ),
        )
        assertEquals("survivor's", state.drafts[absorbed.id]?.body)
    }

    @Test
    fun testAMergeAdoptsTheAbsorbedDraftWhenTheSurvivorHasNone() {
        val survivor = BufferKey(networkId = 1, target = "bob")
        val absorbed = BufferKey(networkId = 1, target = "bobby")
        var state = ChatState(
            buffers = mapOf(
                survivor.id to Buffer(networkId = 1, target = "bob", kind = BufferKind.Dm),
                absorbed.id to Buffer(networkId = 1, target = "bobby", kind = BufferKind.Dm),
            ),
            drafts = mapOf(absorbed.id to ComposerDraft(body = "absorbed")),
        )
        state = LurkerStore.reduce(
            state,
            ServerFrame.BufferRenamed(
                networkId = 1, from = "bob", to = "bobby", bufferId = 5, merged = true, mergedFromBufferId = 6,
            ),
        )
        assertEquals("absorbed", state.drafts[absorbed.id]?.body)
    }

    // MARK: - DraftSync

    @Test
    fun testAnEditIsProtectedUntilItIsTaken() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "a"), composing = false)
        assertTrue(sync.isProtected(chat.id))
        assertEquals("a", sync.take(chat.id)?.draft?.body)
        assertFalse(sync.isProtected(chat.id))
    }

    @Test
    fun testACompositionHoldsTheFlushAndStaysProtectedAfterIt() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "nihao"), composing = true)
        assertTrue(sync.defersFlush(chat.id), "raw preedit never becomes the draft everyone sees")
        assertEquals(emptyList(), sync.flushableIds)
        assertEquals(chat, sync.endComposition(), "the deferred flush is due now")
        assertFalse(sync.defersFlush(chat.id))
        assertTrue(sync.isProtected(chat.id), "and its edit still waits")
    }

    @Test
    fun testCommittingInAnotherBufferLeavesTheCompositionAlone() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "ni"), composing = true)
        sync.edit(BufferKey(networkId = 1, target = "#other"), ComposerDraft(body = "x"), composing = false)
        assertTrue(sync.defersFlush(chat.id))
    }

    @Test
    fun testAnEditThatFoundNoSocketWaitsUnlessANewerOneReplacedIt() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "first"), composing = false)
        val first = sync.take(chat.id)!!
        sync.restore(first)
        assertEquals("first", sync.local(chat.id)?.body)

        val again = sync.take(chat.id)!!
        sync.edit(chat, ComposerDraft(body = "second"), composing = false)
        sync.restore(again)
        assertEquals("second", sync.local(chat.id)?.body, "the older write never wins")
        sync.settle(again)
        assertEquals("second", sync.local(chat.id)?.body, "nor does settling it take the newer one away")
    }

    @Test
    fun testARenameMovesTheEditAndTheRenamedBufferWins() {
        val sync = DraftSync()
        val from = BufferKey(networkId = 1, target = "bob")
        val to = BufferKey(networkId = 1, target = "bobby")
        sync.edit(from, ComposerDraft(body = "moving"), composing = true)
        sync.rekey(from = from, to = to)
        assertNull(sync.local(from.id))
        assertEquals(to, sync.take(to.id)?.key, "the flush names the buffer as it's called now")
        assertTrue(sync.defersFlush(to.id), "the composition followed")

        // On a merge the renamed buffer survives and the server keeps its draft (lurker
        // renameBuffer.ts) — the absorbed buffer's is adopted only when it has none.
        val merge = DraftSync()
        merge.edit(from, ComposerDraft(body = "the live conversation"), composing = false)
        merge.edit(to, ComposerDraft(body = "absorbed"), composing = false)
        merge.rekey(from = from, to = to)
        assertEquals("the live conversation", merge.local(to.id)?.body)

        val adopt = DraftSync()
        adopt.edit(to, ComposerDraft(body = "absorbed"), composing = false)
        adopt.rekey(from = from, to = to)
        assertEquals("absorbed", adopt.local(to.id)?.body)
    }

    @Test
    fun testALateFailureNeverPutsBackAnOlderEdit() {
        // ⚠⚠ Edit A goes to a dying socket; B goes out on the next one; A's write then fails.
        // Put back, A would go out after B on the next snapshot and overwrite it.
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "A"), composing = false)
        val a = sync.take(chat.id)!!
        sync.edit(chat, ComposerDraft(body = "B"), composing = false)
        sync.take(chat.id)!!
        sync.restore(a)
        assertNull(sync.local(chat.id))
        assertFalse(sync.isProtected(chat.id))
    }

    @Test
    fun testAnEditSentBeforeTheSnapshotOutranksIt() {
        // ⚠⚠ A socket still connecting takes the write, but the server builds the snapshot first.
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "hello"), composing = false)
        sync.sentBeforeSnapshot(sync.take(chat.id)!!)
        assertFalse(sync.isProtected(chat.id), "nothing is waiting until the snapshot")
        sync.requeueAwaitingSnapshot()
        assertEquals("hello", sync.local(chat.id)?.body, "kept over the snapshot, and sent again")
        sync.requeueAwaitingSnapshot()
        sync.take(chat.id)
        sync.requeueAwaitingSnapshot()
        assertNull(sync.local(chat.id), "only the snapshot it raced")
    }

    @Test
    fun testAnEditSentBeforeTheSnapshotYieldsToNewerWords() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "old"), composing = false)
        sync.sentBeforeSnapshot(sync.take(chat.id)!!)
        sync.superseded(chat.id)
        sync.requeueAwaitingSnapshot()
        assertNull(sync.local(chat.id), "another device wrote after it")

        sync.edit(chat, ComposerDraft(body = "old"), composing = false)
        sync.sentBeforeSnapshot(sync.take(chat.id)!!)
        sync.edit(chat, ComposerDraft(body = "new"), composing = false)
        sync.take(chat.id)
        sync.requeueAwaitingSnapshot()
        assertNull(sync.local(chat.id), "nor over this device's own newer edit")
    }

    @Test
    fun testTakingEverythingIncludesWhatTheConnectingSocketTook() {
        // Backgrounding or signing out before the snapshot: the connecting socket's write may
        // never have been read, so the HTTP flush has to carry it.
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "sent while connecting"), composing = false)
        sync.sentBeforeSnapshot(sync.take(chat.id)!!)
        val other = BufferKey(networkId = 1, target = "#other")
        sync.edit(other, ComposerDraft(body = "waiting"), composing = true)
        val taken = sync.takeAll()
        assertEquals(setOf("sent while connecting", "waiting"), taken.map { it.draft.body }.toSet())
        sync.requeueAwaitingSnapshot()
        assertNull(sync.local(chat.id), "taken, not left behind to go out twice")

        // A rename can leave one buffer in both; the newer edit is the one that goes.
        val both = DraftSync()
        val from = BufferKey(networkId = 1, target = "bob")
        val to = BufferKey(networkId = 1, target = "bobby")
        both.edit(to, ComposerDraft(body = "older"), composing = false)
        both.sentBeforeSnapshot(both.take(to.id)!!)
        both.edit(from, ComposerDraft(body = "newer"), composing = false)
        both.rekey(from = from, to = to)
        assertEquals(listOf("newer"), both.takeAll().map { it.draft.body })
    }

    @Test
    fun testAWriteStillInFlightIsCarriedByTheBackgroundFlush() {
        // ⚠⚠ Queued on the socket, not yet out: suspension or sign-out's close() can cancel it,
        // so the HTTP flush has to carry it.
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "on the wire"), composing = false)
        val edit = sync.take(chat.id)!!
        sync.sending(edit)
        assertEquals(listOf("on the wire"), sync.takeAll().map { it.draft.body })
        sync.completed(seq = edit.seq, ok = false)
        assertNull(sync.local(chat.id), "the flush took it; a late failure finds nothing to put back")
    }

    @Test
    fun testAFailedWriteComesBackUnderTheBuffersNewName() {
        val sync = DraftSync()
        val from = BufferKey(networkId = 1, target = "bob")
        val to = BufferKey(networkId = 1, target = "bobby")
        sync.edit(from, ComposerDraft(body = "x"), composing = false)
        val edit = sync.take(from.id)!!
        sync.sending(edit)
        sync.rekey(from = from, to = to)
        sync.completed(seq = edit.seq, ok = false)
        assertEquals(to, sync.take(to.id)?.key)
        assertNull(sync.local(from.id))
    }

    @Test
    fun testACompletedWriteIsDone() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "x"), composing = false)
        val edit = sync.take(chat.id)!!
        sync.sending(edit)
        sync.completed(seq = edit.seq, ok = true)
        assertTrue(sync.takeAll().isEmpty())
    }

    @Test
    fun testAWriteOnItsWayOutOutranksTheServer() {
        // Anything the server sends before our write leaves was built before it read it.
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "x"), composing = false)
        val edit = sync.take(chat.id)!!
        sync.sending(edit)
        assertTrue(sync.isProtected(chat.id))
        assertTrue(sync.protectedIds.contains(chat.id))
        sync.completed(seq = edit.seq, ok = true)
        assertFalse(sync.isProtected(chat.id))
    }

    @Test
    fun testAMergeDecidesOnceAcrossEveryMap() {
        // ⚠⚠ The survivor's edit is in flight, the absorbed one's waits: resolved per map, both
        // stayed, and the absorbed one could win a takeAll.
        val from = BufferKey(networkId = 1, target = "bob")
        val to = BufferKey(networkId = 1, target = "bobby")
        val sync = DraftSync()
        sync.edit(from, ComposerDraft(body = "survivor"), composing = false)
        sync.sending(sync.take(from.id)!!)
        sync.edit(to, ComposerDraft(body = "absorbed"), composing = false)
        sync.rekey(from = from, to = to)
        assertNull(sync.local(to.id), "the absorbed edit is gone")
        assertEquals(listOf("survivor"), sync.takeAll().map { it.draft.body })
    }

    @Test
    fun testAnAbsorbedEditNeverOverwritesTheSurvivorsSavedDraft() {
        // ⚠⚠ The survivor's draft was flushed (nothing pending here), the absorbed buffer's edit
        // waits. Kept, it would go out under the survivor's name over the draft the merge kept.
        val from = BufferKey(networkId = 1, target = "bob")
        val to = BufferKey(networkId = 1, target = "bobby")
        val sync = DraftSync()
        sync.edit(to, ComposerDraft(body = "absorbed"), composing = false)
        assertTrue(sync.rekey(from = from, to = to, survivorHasDraft = true), "its flush is cancelled")
        assertNull(sync.local(to.id))
        assertTrue(sync.takeAll().isEmpty())
        // With no draft anywhere on the survivor's side, it's adopted.
        val adopt = DraftSync()
        adopt.edit(to, ComposerDraft(body = "absorbed"), composing = false)
        assertFalse(adopt.rekey(from = from, to = to, survivorHasDraft = false))
        assertEquals("absorbed", adopt.local(to.id)?.body)
    }

    @Test
    fun testAnAdoptedEditCanStillBePutBack() {
        // The source has nothing pending: the absorbed buffer's in-flight edit stays, and its
        // failure still restores — the source's stale `latest` must not replace its own.
        val from = BufferKey(networkId = 1, target = "bob")
        val to = BufferKey(networkId = 1, target = "bobby")
        val sync = DraftSync()
        sync.edit(to, ComposerDraft(body = "absorbed"), composing = false)
        val absorbed = sync.take(to.id)!!
        sync.sending(absorbed)
        sync.edit(from, ComposerDraft(body = "sent long ago"), composing = false)
        val old = sync.take(from.id)!!
        sync.sending(old)
        sync.completed(seq = old.seq, ok = true)
        sync.rekey(from = from, to = to)
        sync.completed(seq = absorbed.seq, ok = false)
        assertEquals("absorbed", sync.local(to.id)?.body)
    }

    @Test
    fun testADeletedNetworksEditsGo() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "x"), composing = false)
        val eleven = BufferKey(networkId = 11, target = "#chat")
        sync.edit(eleven, ComposerDraft(body = "y"), composing = false)
        sync.dropNetworks(keeping = setOf(11))
        assertNull(sync.local(chat.id))
        assertNotNull(sync.local(eleven.id))
    }

    @Test
    fun testACaseOnlyRenameRenamesTheFlush() {
        val sync = DraftSync()
        sync.edit(BufferKey(networkId = 1, target = "bob"), ComposerDraft(body = "x"), composing = false)
        val to = BufferKey(networkId = 1, target = "Bob")
        sync.rekey(from = BufferKey(networkId = 1, target = "bob"), to = to)
        assertEquals("Bob", sync.take(to.id)?.key?.target)
    }

    // MARK: - The view model

    // Waiting on ChatViewModel, SessionStore (and the private `viewModel` helper):
    // testAMergeDropsTheAbsorbedEditInTheViewModelToo,
    // testAnEditOutranksTheServerUntilItGoesOut, testAFlushWithNoSocketKeepsTheEditForTheNextConnect,
    // testAnotherDevicesWriteLandsWhenNothingHereIsNewer, testTheSystemBufferAndServerLogsKeepNoDraft,
    // testClosingABufferDropsItsWaitingEdit, testARenameCarriesTheWaitingEdit

    // Port-only: `Drafts.pendingReply`, the wire types and `DraftSync.reset`, which LurkerKit
    // reaches only through `ChatState.seedDrafts` and the view model. The first three are the
    // store's cases of the same names, asked of the function they turn on; every answer here is
    // the Swift's own, taken from LurkerKit compiled on a Mac.

    private fun pendingReply(
        reply: DraftReply?,
        ignores: IgnoreSet = IgnoreSet.empty,
        relayBots: RelayBotSet = RelayBotSet.empty,
        ownNick: String? = "me",
    ): PendingReply? =
        Drafts.pendingReply(
            reply, networkId = 1, target = "#chat", ignores = ignores, relayBots = relayBots, ownNick = ownNick,
        )

    @Test
    fun testPendingReplyResolvesAStoredReply() {
        assertEquals(
            PendingReply(messageId = 7, nick = "alice", type = EventType.Message, text = "lunch?", isSelf = false, addressed = true),
            pendingReply(DraftReply(messageId = 7, addressed = true, parent = alice)),
        )
        assertNull(pendingReply(null))
        assertNull(
            pendingReply(DraftReply(messageId = 3, addressed = false, parent = null)),
            "a reply to a line that's gone is no reply",
        )
    }

    @Test
    fun testPendingReplyNamesThePersonInsideARelayedLine() {
        // The strip and a cancel's `nick: ` both name who the Reply addressed — carol, not the bot.
        val bots = RelayBotSet.empty.applying(networkId = 1, nick = "bridge", marked = true, pattern = "")
        val relayed = ReplyParent(id = 8, nick = "bridge", type = EventType.Message, text = "<carol> hello there")
        val pending = pendingReply(DraftReply(messageId = 8, addressed = true, parent = relayed), relayBots = bots)
        assertEquals("carol", pending?.nick)
        assertEquals("hello there", pending?.text)
        assertEquals(false, pending?.isSelf)
        // The person inside is you when they carry your nick.
        val echo = ReplyParent(id = 8, nick = "bridge", type = EventType.Message, text = "<Me> mine")
        assertEquals(true, pendingReply(DraftReply(messageId = 8, addressed = false, parent = echo), relayBots = bots)?.isSelf)
    }

    @Test
    fun testPendingReplyToSomeoneIgnoredSinceKeepsTheReplyWithoutTheirWords() {
        val ignores = IgnoreSet(global = listOf(IgnoreRule(id = 1, mask = "alice!*@*")), byNetwork = emptyMap())
        val pending = pendingReply(DraftReply(messageId = 7, addressed = false, parent = alice), ignores = ignores)
        assertEquals(7L, pending?.messageId)
        assertEquals("alice", pending?.nick)
        assertEquals("", pending?.text)
    }

    @Test
    fun testOnlyAChannelOrDmKeepsASyncedDraft() {
        assertTrue(Drafts.syncs(chat))
        assertTrue(Drafts.syncs(BufferKey(networkId = 1, target = "bob")))
        assertTrue(Drafts.syncs(BufferKey(networkId = 1, target = "=bob")))
        assertFalse(Drafts.syncs(BufferKey(networkId = null, target = ":system:")))
        assertFalse(Drafts.syncs(BufferKey(networkId = 1, target = ":server:1")))
        assertFalse(Drafts.syncs(BufferKey(networkId = null, target = "#chat")))
        assertEquals(Duration.ofMillis(500), Drafts.flushDelay)
    }

    @Test
    fun testTheWireTypesCarryWhatTheyWereGiven() {
        val entry = DraftEntry(networkId = 1, target = "#Chat", body = "x", reply = null)
        assertEquals(BufferKey(networkId = 1, target = "#Chat"), entry.key)
        assertTrue(entry.carriesReply, "a frame is taken to speak about the reply unless told otherwise")
        assertTrue(ComposerDraft().isEmpty)
        assertFalse(ComposerDraft(body = "x").isEmpty)
        assertFalse(
            ComposerDraft(reply = PendingReply(messageId = 7, nick = "alice", type = EventType.Message, text = "", isSelf = false)).isEmpty,
            "a reply with nothing typed yet is a draft",
        )
    }

    @Test
    fun testResetForgetsEverythingIncludingTheSequence() {
        val sync = DraftSync()
        sync.edit(chat, ComposerDraft(body = "a"), composing = true)
        val sent = BufferKey(networkId = 1, target = "#sent")
        sync.edit(sent, ComposerDraft(body = "b"), composing = false)
        sync.sending(sync.take(sent.id)!!)
        val early = BufferKey(networkId = 1, target = "#early")
        sync.edit(early, ComposerDraft(body = "c"), composing = false)
        sync.sentBeforeSnapshot(sync.take(early.id)!!)
        sync.reset()
        assertTrue(sync.unflushed.isEmpty())
        assertTrue(sync.awaitingSnapshot.isEmpty())
        assertTrue(sync.inFlight.isEmpty())
        assertNull(sync.composing)
        assertTrue(sync.protectedIds.isEmpty())
        sync.edit(chat, ComposerDraft(body = "again"), composing = false)
        assertEquals(1, sync.take(chat.id)?.seq, "the count starts over, as a fresh one's does")
    }
}
