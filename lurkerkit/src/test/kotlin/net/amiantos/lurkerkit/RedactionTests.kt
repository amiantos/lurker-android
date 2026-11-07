// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
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
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyContext
import net.amiantos.lurkerkit.model.ReplyParent
import net.amiantos.lurkerkit.model.ReplyQuote
import net.amiantos.lurkerkit.model.TagSupport
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
                networkId = 1, target = target, messageId = id, by = by, reason = reason, isSelf = false,
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
                    networkId = 1, target = "#lurker", messageId = 42, by = "bob", reason = "oops", isSelf = true,
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

    @Test
    fun testAFrameForALineNobodyHoldsIsDropped() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.apply(redacted(7))
        assertTrue(store.state.redactions.isEmpty(), "the row brings its mark with it when it's fetched")
        store.apply(redacted(1, target = "#other"))
        assertTrue(store.state.redactions.isEmpty(), "and a buffer that doesn't hold it says nothing about it")
    }

    @Test
    fun testTheSyncAnswerDeletesWhatItNames() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1), line(2))))
        store.apply(ServerFrame.ReactionsSync(messageIds = listOf(1, 2), reactions = emptyMap(), redactions = mapOf(2L to mark)))
        assertNull(store.state.redaction(line(1), networkId = 1))
        assertEquals(mark, store.state.redaction(line(2), networkId = 1))
    }

    @Test
    fun testClosingABufferFreesItsMarks() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.apply(redacted(1))
        assertFalse(store.state.redactions.isEmpty())
        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#lurker"))
        assertTrue(store.state.redactions.isEmpty())
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
}
