// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.ChannelSnapshot
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.HistoryMode
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageActionContext
import net.amiantos.lurkerkit.model.MessageActionKey
import net.amiantos.lurkerkit.model.MessageActionScope
import net.amiantos.lurkerkit.model.MessageActions
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.Redaction
import net.amiantos.lurkerkit.model.RedactionChange
import net.amiantos.lurkerkit.model.Redactions
import net.amiantos.lurkerkit.model.RelayBot
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyContext
import net.amiantos.lurkerkit.model.ReplyParent
import net.amiantos.lurkerkit.model.ReplyQuote
import net.amiantos.lurkerkit.model.TagSupport
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.support.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Message redaction (lurker#1013 / #1277): the wire, the side map, the placeholder, and what a
 * deleted line stops offering. LurkerKit's `RedactionTests`.
 */
class RedactionTests {

    private val key = BufferKey(networkId = 1, target = "#lurker")
    private val mark = Redaction(by = "alice", reason = null, time = "2026-11-05T00:00:00Z")

    private fun line(
        id: Long,
        nick: String = "alice",
        text: String? = "hi",
        isSelf: Boolean = false,
        msgid: String? = "m",
        redaction: Redaction? = null,
        reactions: List<MessageReaction>? = null,
        replyTo: ReplyContext? = null,
    ): Message =
        Message(
            id = id, type = EventType.Message, nick = nick, text = text, isSelf = isSelf, msgid = msgid,
            reactions = reactions, replyTo = replyTo, redaction = redaction,
        )

    private fun backlog(messages: List<Message>, networkId: Int? = 1, target: String = "#lurker"): ServerFrame =
        ServerFrame.Backlog(
            buffer = Buffer(networkId = networkId, target = target, kind = BufferKind.Channel, hydrated = true),
            messages = messages, hydrated = true, append = false, speakers = null,
        )

    private fun redacted(id: Long, by: String = "alice", reason: String? = null, target: String = "#lurker"): ServerFrame =
        ServerFrame.MessageRedacted(
            RedactionChange(
                networkId = 1, target = target, messageId = id, by = by, reason = reason,
                time = "2026-11-05T00:00:00Z",
            ),
        )

    // MARK: - Wire

    @Test
    fun testADeletedRowParsesItsMarkBesideANullText() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,"events":[{"id":1,"type":"message","nick":"alice","text":null,"msgid":"m","redaction":{"by":"Bob","reason":"spam","time":"2026-11-05T00:00:00Z"}},{"id":2,"type":"message","nick":"alice","text":"hi"}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("$frame")
        val messages = frame.messages
        assertNull(messages[0].text)
        assertEquals(Redaction(by = "Bob", reason = "spam", time = "2026-11-05T00:00:00Z"), messages[0].redaction)
        assertTrue(messages[0].isRenderable, "no words, still a row to show")
        assertNull(messages[1].redaction, "absent on every line that wasn't deleted")
    }

    @Test
    fun testTheFrameParsesAndNeedsALineAndANetwork() {
        val frame = FrameParser.parseWs(
            """{"kind":"message-redacted","networkId":1,"bufferId":9,"target":"#lurker","messageId":42,"by":"bob","reason":"oops","self":true,"time":"2026-11-05T00:00:00Z"}""",
        )
        assertEquals(
            ServerFrame.MessageRedacted(
                RedactionChange(
                    networkId = 1, target = "#lurker", messageId = 42, by = "bob", reason = "oops",
                    time = "2026-11-05T00:00:00Z",
                ),
            ),
            frame,
        )
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"message-redacted","networkId":1,"by":"bob"}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"message-redacted","messageId":4,"by":"bob"}"""))
    }

    @Test
    fun testAReplyParentCarriesItsDeletion() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,"events":[{"id":2,"type":"message","nick":"bob","text":"alice: why","msgid":"r","replyTo":{"msgid":"m","parent":{"id":1,"nick":"alice","type":"message","text":"","redacted":true}}}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("$frame")
        assertEquals(true, frame.messages[0].replyTo?.parent?.redacted)
    }

    @Test
    fun testTheSyncAnswerNamesDeletions() {
        val frame = FrameParser.parseWs(
            """{"kind":"reactions-sync","messageIds":[1,2],"reactions":{},"redactions":{"2":{"by":"op","time":"2026-11-05T00:00:00Z"}}}""",
        )
        assertEquals(
            ServerFrame.ReactionsSync(
                messageIds = listOf(1, 2), reactions = emptyMap(),
                redactions = mapOf(2L to Redaction(by = "op", time = "2026-11-05T00:00:00Z")),
            ),
            frame,
        )
    }

    /** `canRedact` is newer than the split, so an old server's `canReact` never stands for it. */
    @Test
    fun testCanRedactHasNoLegacyFallback() {
        fun support(fields: String): TagSupport {
            val frame = FrameParser.parseWs(
                """{"kind":"irc","type":"react-support","networkId":3,"target":":server:3"$fields}""",
            )
            if (frame !is ServerFrame.ReactSupport) fail("$frame")
            return frame.support
        }
        assertFalse(support(""","canReact":true""").canRedact)
        assertTrue(support(""","canReact":true,"canRedact":true""").canRedact)
        assertFalse(support(""","canRedact":true""").canAddReaction, "and it stands alone")
    }

    // MARK: - The placeholder

    @Test
    fun testTheLabelNamesTheDeleterOnlyWhenItIsntTheAuthor() {
        assertEquals("message deleted", Redaction(by = "alice").label("alice"))
        assertEquals("message deleted", Redaction(by = "ALICE").label("alice"), "case-insensitive on the nick")
        assertEquals("message deleted by op", Redaction(by = "op").label("alice"))
        assertEquals("message deleted by op: spam", Redaction(by = "op", reason = "spam").label("alice"))
        assertEquals("message deleted: typo", Redaction(by = "alice", reason = "typo").label("alice"))
        assertEquals("message deleted", Redaction(by = "").label("alice"), "a mark with no deleter names nobody")
    }

    @Test
    fun testDeletedTakesTheWordsAndWhatCopyWouldRead() {
        val reply = line(2, nick = "bob", text = "alice: why").showingReply(quote = null, text = "why")
        val gone = reply.deleted(mark)
        assertNull(gone.text)
        assertNull(gone.copyText)
        assertEquals(mark, gone.redaction)
        assertEquals(2L, gone.id)
        assertEquals("bob", gone.nick)
        assertTrue(gone.isRenderable)
    }

    // MARK: - Side map

    @Test
    fun testARowSeedsTheMapAndIsAuthoritativeForItself() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, text = null, redaction = mark), line(2))))
        assertEquals(mark, store.state.redaction(line(1), networkId = 1))
        assertNull(store.state.redaction(line(2), networkId = 1))
        // Shown: the mark rides the line, the words don't.
        val shown = Redactions.presenting(store.state.messages[key.id].orEmpty(), redactions = store.state.redactions)
        assertEquals(listOf(mark, null), shown.map { it.redaction })
    }

    @Test
    fun testTheFrameMarksAHeldLineAndTakesItsReactionsDown() {
        val store = LurkerStore()
        val thumbs = MessageReaction(nick = "bob", value = "👍", isSelf = false)
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)), line(2))))
        val before = store.state.reactionsRevision(key)
        store.apply(redacted(1, by = "op", reason = "spam"))

        val shown = Redactions.presenting(store.state.messages[key.id].orEmpty(), redactions = store.state.redactions)
        assertNull(shown[0].text, "the words are gone at render")
        assertEquals("message deleted by op: spam", shown[0].redaction?.label("alice"))
        assertEquals("hi", shown[1].text, "its neighbour is untouched")
        assertTrue(store.state.reactionGroups(1).isEmpty(), "the server tombstoned them; so does the view")
        assertNotEquals(before, store.state.reactionsRevision(key), "the screen's gate lets it through")
        assertEquals("hi", store.state.messages[key.id]?.get(0)?.text, "the stored row is never patched")
    }

    /**
     * The feeds show lines outside any buffer, so a mark for one is kept — but only a buffer that
     * holds the line is told to redraw.
     */
    @Test
    fun testAFrameForALineNobodyHoldsIsKeptWithoutARedraw() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        val before = store.state.reactionsRevision(key)
        store.apply(redacted(7))
        assertEquals(mark, store.state.redactions[7L], "a bookmark or a search hit can show it")
        assertEquals(before, store.state.reactionsRevision(key), "nothing on this screen changed")
        store.apply(redacted(1, target = "#other"))
        assertEquals(mark, store.state.redactions[1L])
        assertEquals(before, store.state.reactionsRevision(key), "the frame named another buffer")
    }

    /** A page read before the REDACT landed can arrive after the frame that applied it. */
    @Test
    fun testAStalePageNeverUndeletesALine() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.apply(redacted(1))
        store.apply(
            ServerFrame.History(
                networkId = 1, target = "#lurker", events = listOf(line(1)), mode = HistoryMode.Around,
                hasMoreOlder = false, hasMoreNewer = false, speakers = null,
            ),
        )
        assertEquals(mark, store.state.redaction(line(1), networkId = 1), "a deletion is for good")
    }

    @Test
    fun testTheSyncAnswerDeletesWhatItNames() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1), line(2))))
        val before = store.state.reactionsRevision(key)
        store.apply(
            ServerFrame.ReactionsSync(
                messageIds = listOf(1, 2),
                reactions = mapOf(1L to listOf(MessageReaction(nick = "bob", value = "👍", isSelf = false))),
                redactions = mapOf(2L to mark),
            ),
        )
        assertNull(store.state.redaction(line(1), networkId = 1))
        assertEquals(mark, store.state.redaction(line(2), networkId = 1))
        assertTrue(store.state.reactionGroups(2).isEmpty())
        assertEquals(1, store.state.reactionGroups(1).size, "its neighbour keeps its chips")
        assertNotEquals(before, store.state.reactionsRevision(key))
    }

    @Test
    fun testPresentingReturnsTheSameListWhenNothingInItIsDeleted() {
        val lines = listOf(line(1), line(2))
        assertTrue(Redactions.presenting(lines, redactions = mapOf(9L to mark)) === lines)
        assertNull(Redactions.presenting(listOf(line(1, text = null, redaction = mark)), redactions = emptyMap()).first().text)
    }

    /** A bookmark or a search hit can still be showing the line after its buffer closes. */
    @Test
    fun testClosingABufferKeepsItsMarks() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.apply(redacted(1))
        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#lurker"))
        assertEquals(mark, store.state.redactions[1L])
    }

    /**
     * A page read before the REDACT landed can put the line's reactions back in the map; none are
     * drawn for a line known to be deleted.
     */
    @Test
    fun testAStalePageNeverRestoresADeletedLinesChips() {
        val store = LurkerStore()
        val thumbs = MessageReaction(nick = "bob", value = "👍", isSelf = false)
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)))))
        store.apply(redacted(1))
        store.apply(
            ServerFrame.History(
                networkId = 1, target = "#lurker", events = listOf(line(1, reactions = listOf(thumbs))), mode = HistoryMode.Around,
                hasMoreOlder = false, hasMoreNewer = false, speakers = null,
            ),
        )
        assertTrue(store.state.reactionGroups(1).isEmpty())
    }

    /** The feeds read rows over REST; a deleted one they carry marks the line everywhere. */
    @Test
    fun testAFeedRowSeedsTheMark() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.noteRedactions(rows = listOf(line(1, text = null, redaction = mark), line(2)))
        assertEquals(mark, store.state.redaction(line(1), networkId = 1))
        assertNull(store.state.redactions[2L])
    }

    @Test
    fun testSystemRowsNeverTouchTheMap() {
        val store = LurkerStore()
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = null, target = ":system:", kind = BufferKind.System, hydrated = true),
                messages = listOf(line(1, text = null, redaction = mark)), hydrated = true, append = false, speakers = null,
            ),
        )
        assertTrue(store.state.redactions.isEmpty())
    }

    // MARK: - Replies

    private fun shown(reply: Message, redactions: Map<Long, Redaction> = emptyMap()): ReplyQuote? =
        Replies.shown(
            reply.replyTo!!, line = reply, networkId = 1, target = "#lurker",
            ignores = IgnoreSet(global = emptyList(), byNetwork = emptyMap()), relayBots = RelayBotSet(), ownNick = "me",
            redactions = redactions,
        ).quote

    @Test
    fun testTheQuoteOfADeletedLineReadsAsDeletedNotUnavailable() {
        val parent = ReplyParent(id = 1, nick = "alice", type = EventType.Message, text = "", redacted = true)
        val reply = line(2, nick = "bob", text = "alice: why", replyTo = ReplyContext(msgid = "m", parent = parent))
        val quote = shown(reply)
        assertEquals(true, quote?.redacted)
        assertEquals(1L, quote?.id, "still jumps")
        assertEquals("alice", quote?.nick)
    }

    /**
     * The answered line deleted while this reply was on screen: the quote copied its words; the
     * store knows they're gone.
     */
    @Test
    fun testTheStoreOverridesAQuoteThatStillHoldsTheWords() {
        val parent = ReplyParent(id = 1, nick = "alice", type = EventType.Message, text = "secret")
        val reply = line(2, nick = "bob", text = "alice: why", replyTo = ReplyContext(msgid = "m", parent = parent))
        assertEquals(false, shown(reply)?.redacted)
        val quote = shown(reply, redactions = mapOf(1L to mark))
        assertEquals(true, quote?.redacted)
        assertEquals("", quote?.text, "the words don't leak through the quote")
    }

    /**
     * A bridged line deleted while on screen still quotes the person inside its envelope, as its
     * own row does.
     */
    @Test
    fun testADeletedRelayedParentKeepsItsSpeaker() {
        val parent = ReplyParent(id = 1, nick = "bridge", type = EventType.Message, text = "<alice> secret")
        val reply = line(2, nick = "bob", text = "alice: why", replyTo = ReplyContext(msgid = "m", parent = parent))
        val quote = Replies.shown(
            reply.replyTo!!, line = reply, networkId = 1, target = "#lurker",
            ignores = IgnoreSet(global = emptyList(), byNetwork = emptyMap()),
            relayBots = RelayBotSet(byNetwork = mapOf(1 to listOf(RelayBot(nick = "bridge")))),
            ownNick = "me", redactions = mapOf(1L to mark),
        ).quote
        assertEquals("alice", quote?.nick)
        assertEquals("bridge", quote?.relayBot)
        assertEquals(true, quote?.redacted)
        assertEquals("", quote?.text)
    }

    // MARK: - Actions

    private fun scope(target: String = "#lurker", canRedact: Boolean = true, moderating: Boolean = false): MessageActionScope =
        MessageActionScope(
            networkId = 1, isBookmarked = false, target = target,
            support = TagSupport(canAddReaction = true, canRemoveReaction = true, canReply = true, canRedact = canRedact),
            moderating = moderating,
        )

    private fun keys(message: Message, scope: MessageActionScope): List<MessageActionKey> =
        MessageActions.build(message, scope = scope).map { it.key }

    @Test
    fun testADeletedLineOffersOnlyWhatNeedsNoWords() {
        val gone = line(1).deleted(mark)
        assertEquals(
            listOf(MessageActionKey.Bookmark, MessageActionKey.Profile), keys(gone, scope()),
            "no reply, react, copy — or a second delete",
        )
        assertNull(MessageActions.replyAction(gone, target = "#lurker", canReply = true), "so the swipe doesn't move either")
        assertFalse(Reactions.lineTakes(gone, target = "#lurker"))
    }

    @Test
    fun testDeleteIsOfferedOnYourOwnLineWhereTheNetworkTakesIt() {
        val mine = line(1, isSelf = true)
        assertEquals(
            listOf(
                MessageActionKey.Reply, MessageActionKey.React, MessageActionKey.Copy, MessageActionKey.Bookmark,
                MessageActionKey.Delete, MessageActionKey.Profile,
            ),
            keys(mine, scope()),
        )
        assertFalse(MessageActionKey.Delete in keys(mine, scope(canRedact = false)), "not without the cap")
        assertFalse(MessageActionKey.Delete in keys(line(1, isSelf = true, msgid = null), scope()), "a REDACT names a msgid")
        assertFalse(MessageActionKey.Delete in keys(line(0, isSelf = true), scope()), "an ephemeral line has no row")
        assertTrue(MessageActionKey.Delete in keys(mine, scope(target = "bob")), "your own line in a DM too")
        assertFalse(MessageActionKey.Delete in keys(mine, scope(target = ":server:")), "not the console")
    }

    @Test
    fun testDeleteOnSomeoneElsesLineNeedsAPrefixModeInAChannel() {
        val theirs = line(1)
        assertFalse(MessageActionKey.Delete in keys(theirs, scope()))
        assertTrue(MessageActionKey.Delete in keys(theirs, scope(moderating = true)))
        assertFalse(MessageActionKey.Delete in keys(theirs, scope(target = "alice", moderating = true)), "no modes in a DM")
    }

    @Test
    fun testRunDeleteHandsTheLineToTheScreenAndNothingElse() {
        val deleted = mutableListOf<Long>()
        val copied = mutableListOf<String>()
        val context = MessageActionContext(
            reply = { fail("not a reply") }, copy = { copied.add(it) },
            setBookmark = { _, _ -> fail("not a bookmark") }, showProfile = { fail("not a profile") },
            delete = { deleted.add(it.id) },
        )
        MessageActions.run(MessageActionKey.Delete, message = line(1, isSelf = true), scope = scope(), context = context)
        assertEquals(listOf(1L), deleted)
        // Not offered on their line here, so a stale menu's Delete is a no-op.
        MessageActions.run(MessageActionKey.Delete, message = line(2), scope = scope(), context = context)
        assertEquals(listOf(1L), deleted)
        // And Copy on a deleted line copies nothing.
        MessageActions.run(MessageActionKey.Copy, message = line(3).deleted(mark), scope = scope(), context = context)
        assertTrue(copied.isEmpty())
    }

    @Test
    fun testModeratingReadsYourOwnModesInTheChannel() {
        val store = LurkerStore()
        store.apply(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        store.apply(
            ServerFrame.ChannelMembers(
                networkId = 1, target = "#lurker", members = listOf(Member(nick = "Me", modes = listOf("o")), Member(nick = "alice")),
            ),
        )
        assertTrue(store.state.moderates(key), "case-folded against your nick")
        store.apply(
            ServerFrame.ChannelMembers(
                networkId = 1, target = "#lurker", members = listOf(Member(nick = "me"), Member(nick = "alice", modes = listOf("o"))),
            ),
        )
        assertFalse(store.state.moderates(key), "someone else's op isn't yours")
        assertFalse(store.state.moderates(BufferKey(networkId = 1, target = "alice")), "never in a DM")
    }

    /** `/react` lands on the last line someone else said; when that one was deleted, it says why. */
    @Test
    fun testReactCommandRefusesADeletedLine() {
        val result = Reactions.commandTarget(listOf(line(1), line(2).deleted(mark)))
        assertEquals(Result.Failure(Reactions.CommandRefusal("can't react to a deleted message")), result)
    }

    // MARK: - Through the view model

    private fun model(): ChatViewModel {
        val model = testViewModel()
        model.handle(ServerFrame.SocketOpen)
        model.handle(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1, state = ConnectionState.Connected, nick = "me",
                        channels = listOf(ChannelSnapshot(name = "#lurker", topic = null, members = emptyList())),
                        tagSupport = TagSupport(canAddReaction = true, canRemoveReaction = true, canReply = true, canRedact = true),
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        return model
    }

    private fun lastLocal(model: ChatViewModel): String? =
        model.state.messages[key.id]?.lastOrNull { it.id == 0L }?.text

    /** The stored rows carry no mark, so the command has to look at the lines as shown. */
    @Test
    fun testReactCommandSkipsALineDeletedWhileHeld() {
        val model = model()
        val sent = mutableListOf<String>()
        model.reactSeam = { id, value, _ -> sent.add("$id $value"); true }
        model.handle(backlog(listOf(line(1, nick = "bob"), line(2, nick = "bob"))))
        model.handle(redacted(2, by = "bob"))
        model.send(key, "/react 👍")
        assertEquals(emptyList(), sent)
        assertEquals("can't react to a deleted message", lastLocal(model))
    }

    @Test
    fun testReactAndDeleteTakeTheLastLineShownNotTheNewestId() {
        // A filled gap (lurker#1290): ids 3 and 4 were said before 1 and 2, and show above them.
        fun said(id: Long, minute: Long, isSelf: Boolean) = Message(
            id = id, type = EventType.Message, nick = if (isSelf) "me" else "bob", text = "hi", isSelf = isSelf,
            date = java.time.Instant.parse("2026-11-06T09:00:00Z").plusSeconds(minute * 60), msgid = "m$id",
        )
        val model = model()
        val sent = mutableListOf<String>()
        model.reactSeam = { id, value, _ -> sent.add("react $id $value"); true }
        model.redactSeam = { id, _ -> sent.add("delete $id"); true }
        model.handle(
            backlog(
                listOf(
                    said(1, 22, isSelf = false), said(2, 23, isSelf = true),
                    said(3, 19, isSelf = false), said(4, 20, isSelf = true),
                ),
            ),
        )
        model.send(key, "/react 👍")
        model.send(key, "/delete")
        assertEquals(listOf("react 1 👍", "delete 2"), sent)
    }

    @Test
    fun testReactAndDeleteLeaveALineAboveTheClearMarkerAlone() {
        // Cleared rows stay held; a command typed at the screen never lands on one.
        val model = model()
        model.reactSeam = { _, _, _ -> fail("nothing should go out") }
        model.redactSeam = { _, _ -> fail("nothing should go out") }
        model.handle(backlog(listOf(line(1, nick = "bob"), line(2, isSelf = true))))
        model.handle(
            ServerFrame.BufferCleared(
                networkId = 1, target = "#lurker", clearedBeforeId = 2, clearedAt = java.time.Instant.now(),
            ),
        )
        model.send(key, "/react 👍")
        assertEquals("nothing here to react to", lastLocal(model))
        model.send(key, "/delete")
        assertEquals("nothing of yours here to delete", lastLocal(model))
    }

    @Test
    fun testReactSkipsALineFromSomeoneIgnored() {
        val model = model()
        val sent = mutableListOf<Long>()
        model.reactSeam = { id, _, _ -> sent.add(id); true }
        model.handle(ServerFrame.IgnoreListUpdated(networkId = null, rules = listOf(IgnoreRule(mask = "mallory"))))
        model.handle(backlog(listOf(line(1, nick = "bob"), line(2, nick = "mallory"))))
        model.send(key, "/react 👍")
        assertEquals(listOf(1L), sent)
    }

    @Test
    fun testDeleteCommandTakesYourNewestStandingLineWithItsReason() {
        val model = model()
        val sent = mutableListOf<String>()
        model.redactSeam = { id, reason -> sent.add("$id ${reason ?: "-"}"); true }
        model.handle(backlog(listOf(line(1, isSelf = true), line(2, nick = "bob"), line(3, isSelf = true))))
        model.send(key, "/delete")
        model.send(key, "/delete  typo  ")
        assertEquals(listOf("3 -", "3 typo"), sent)
        // Deleted while held: the next /delete reaches past it.
        model.handle(redacted(3, by = "me"))
        model.send(key, "/delete")
        assertEquals("1 -", sent.last())
        assertNull(lastLocal(model), "nothing prints on success")
    }

    @Test
    fun testDeleteCommandSaysWhyWhenItCant() {
        val model = model()
        model.redactSeam = { _, _ -> fail("nothing should go out") }
        model.handle(backlog(listOf(line(1, nick = "bob"))))
        model.send(key, "/delete")
        assertEquals("nothing of yours here to delete", lastLocal(model))
        model.handle(backlog(listOf(line(1, nick = "bob"), line(2, isSelf = true, msgid = null))))
        model.send(key, "/delete")
        assertEquals("can't delete that line (no message id)", lastLocal(model))
        model.handle(ServerFrame.ReactSupport(networkId = 1, support = TagSupport.all))
        model.send(key, "/delete")
        assertEquals("this network can't delete messages", lastLocal(model))
    }

    @Test
    fun testDeleteParsesItsOptionalReason() {
        val bare = CommandParser.parse("/delete", networkId = 1, target = "#lurker", formatted = { it.toString() })
        val reasoned = CommandParser.parse("/delete  oops ", networkId = 1, target = "#lurker", formatted = { it.toString() })
        if (bare !is ParsedInput.Command || reasoned !is ParsedInput.Command) fail("a command")
        assertEquals(listOf(CommandEffect.Delete(reason = null)), bare.effects)
        assertEquals(listOf(CommandEffect.Delete(reason = "oops")), reasoned.effects)
    }
}
