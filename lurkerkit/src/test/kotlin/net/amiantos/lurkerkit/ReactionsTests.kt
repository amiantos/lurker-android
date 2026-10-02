// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.HistoryMode
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.commands.CommandEffect
import net.amiantos.lurkerkit.commands.CommandParser
import net.amiantos.lurkerkit.commands.ParsedInput
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.FeedReaction
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageActionContext
import net.amiantos.lurkerkit.model.MessageActionKey
import net.amiantos.lurkerkit.model.MessageActionScope
import net.amiantos.lurkerkit.model.MessageActions
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.ReactionChange
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.support.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** IRCv3 reactions (lurker-ios#183): the wire, the side map, and the gates. */
class ReactionsTests {

    @Suppress("unused") // As in LurkerKit, where nothing reads it either.
    private val chanKey = "1::#lurker"

    private fun line(
        id: Long,
        msgid: String? = "m",
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
        isE2E: Boolean = false,
        reactions: List<MessageReaction>? = null,
    ): Message =
        Message(
            id = id, type = type, nick = "alice", text = "hi", isSelf = isSelf,
            msgid = msgid, isE2E = isE2E, reactions = reactions,
        )

    private fun backlog(messages: List<Message>, networkId: Int? = 1, target: String = "#lurker"): ServerFrame =
        ServerFrame.Backlog(
            buffer = Buffer(networkId = networkId, target = target, kind = BufferKind.Channel, hydrated = true),
            messages = messages, hydrated = true, append = false, speakers = null,
        )

    private fun change(
        messageId: Long,
        nick: String,
        value: String,
        isSelf: Boolean = false,
        remove: Boolean = false,
    ): ServerFrame =
        ServerFrame.Reaction(
            ReactionChange(
                networkId = 1, target = "#lurker", messageId = messageId, nick = nick, value = value,
                isSelf = isSelf, remove = remove, toSelf = false,
            ),
        )

    private val thumbs = MessageReaction(nick = "bob", value = "👍", isSelf = false)

    // MARK: - Wire

    @Test
    fun testRowsCarryMsgidE2eAndReactions() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,"events":[{"id":1,"type":"message","nick":"a","text":"plain"},{"id":2,"type":"message","nick":"a","text":"x","msgid":"abc","e2e":true,"reactions":[{"nick":"bob","value":"👍","self":false},{"nick":"me","value":"lol","self":true},{"nick":"","value":"?"}]}]}""",
        )
        if (frame !is ServerFrame.Backlog) fail("$frame")
        val messages = frame.messages
        assertNull(messages[0].msgid)
        assertNull(messages[0].reactions, "absent means none, not an empty list")
        assertFalse(messages[0].isE2E)
        assertEquals("abc", messages[1].msgid)
        assertTrue(messages[1].isE2E)
        assertEquals(
            listOf(
                MessageReaction(nick = "bob", value = "👍", isSelf = false),
                MessageReaction(nick = "me", value = "lol", isSelf = true),
            ),
            messages[1].reactions,
            "an entry with no nick names nobody",
        )
    }

    @Test
    fun testReactionFrameParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"reaction","networkId":1,"bufferId":9,"target":"#lurker","messageId":42,"nick":"bob","value":"🎉","self":false,"remove":true,"toSelf":true,"time":"2026-09-30T00:00:00Z"}""",
        )
        assertEquals(
            ServerFrame.Reaction(
                ReactionChange(
                    networkId = 1, target = "#lurker", messageId = 42, nick = "bob", value = "🎉",
                    isSelf = false, remove = true, toSelf = true,
                ),
            ),
            frame,
        )
    }

    @Test
    fun testReactionFrameThatAddressesNothingIsIgnored() {
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"reaction","networkId":1,"value":"x"}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"reaction","messageId":4,"value":"x"}"""))
        assertEquals(
            ServerFrame.Ignored,
            FrameParser.parseWs("""{"kind":"reaction","networkId":1,"messageId":4,"value":""}"""),
        )
    }

    @Test
    fun testReactionsSyncParses() {
        val frame = FrameParser.parseWs(
            """{"kind":"reactions-sync","messageIds":[1,2],"reactions":{"1":[{"nick":"bob","value":"👍","self":false}]}}""",
        )
        assertEquals(
            ServerFrame.ReactionsSync(messageIds = listOf(1L, 2L), reactions = mapOf(1L to listOf(thumbs))),
            frame,
        )
        assertEquals(
            ServerFrame.Ignored,
            FrameParser.parseWs("""{"kind":"reactions-sync","reactions":{}}"""),
            "with no id list it can't be authoritative about anything",
        )
    }

    @Test
    fun testReactSupportAndSnapshotCanReactParse() {
        assertEquals(
            ServerFrame.ReactSupport(networkId = 3, canReact = true),
            FrameParser.parseWs("""{"kind":"irc","type":"react-support","networkId":3,"target":":server:3","canReact":true}"""),
        )
        val snapshot = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":3,"state":"connected","nick":"me","channels":[],"canReact":true}]}""",
        )
        if (snapshot !is ServerFrame.Snapshot) fail("$snapshot")
        assertTrue(snapshot.networks[0].canReact)
    }

    // MARK: - Side map

    @Test
    fun testARowIsAuthoritativeForItselfInBothDirections() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)), line(2))))
        assertEquals(listOf("👍"), store.state.reactionGroups(1).map { it.value })
        val key = BufferKey(networkId = 1, target = "#lurker")
        val before = store.state.reactionsRevision(key)

        // The same line again with nothing on it: taken back while we weren't listening.
        store.apply(backlog(listOf(line(1))))
        assertTrue(store.state.reactionGroups(1).isEmpty())
        assertNotEquals(before, store.state.reactionsRevision(key))
    }

    @Test
    fun testSilenceAboutALineIsNotARemoval() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)))))
        store.apply(
            ServerFrame.History(
                networkId = 1, target = "#lurker", events = listOf(line(5)), mode = HistoryMode.Before,
                hasMoreOlder = true, hasMoreNewer = false, speakers = null,
            ),
        )
        assertEquals(1, store.state.reactionGroups(1).size)
    }

    @Test
    fun testSystemRowsNeverTouchTheMap() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)))))
        // A system-buffer row sharing the id, carrying nothing.
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = null, target = ":system:", kind = BufferKind.System, hydrated = true),
                messages = listOf(line(1)), hydrated = true, append = false, speakers = null,
            ),
        )
        assertEquals(1, store.state.reactionGroups(1).size)
    }

    @Test
    fun testGroupsKeepFirstReactedOrderAndMarkOurs() {
        val groups = Reactions.groups(
            listOf(
                MessageReaction(nick = "bob", value = "👍", isSelf = false),
                MessageReaction(nick = "carol", value = "lol", isSelf = false),
                MessageReaction(nick = "me", value = "👍", isSelf = true),
            )
        )
        assertEquals(
            listOf(
                ReactionGroup(value = "👍", nicks = listOf("bob", "me"), mine = true),
                ReactionGroup(value = "lol", nicks = listOf("carol"), mine = false),
            ),
            groups,
        )
    }

    @Test
    fun testLiveReactionsAddDedupeAndRemove() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.apply(change(1, "bob", "👍"))
        store.apply(change(1, "Bob", "👍")) // same person, server-cased differently
        store.apply(change(1, "carol", "👍"))
        assertEquals(
            listOf(ReactionGroup(value = "👍", nicks = listOf("bob", "carol"), mine = false)),
            store.state.reactionGroups(1),
        )

        store.apply(change(1, "bob", "👍", remove = true))
        assertEquals(listOf("carol"), store.state.reactionGroups(1).firstOrNull()?.nicks)
        store.apply(change(1, "carol", "👍", remove = true))
        assertNull(store.state.reactions[1], "an emptied line leaves no entry behind")
    }

    /**
     * ⚠⚠ Our own unreact matches by `self`, never by nick: a reaction given under an older nick
     * is still ours, and matching the nick would leave it standing forever.
     */
    @Test
    fun testOurUnreactMatchesSelfNotNick() {
        val store = LurkerStore()
        store.apply(
            backlog(listOf(line(1, reactions = listOf(MessageReaction(nick = "oldme", value = "👍", isSelf = true), thumbs)))),
        )
        store.apply(change(1, "newme", "👍", isSelf = true, remove = true))
        assertEquals(
            listOf(ReactionGroup(value = "👍", nicks = listOf("bob"), mine = false)),
            store.state.reactionGroups(1),
        )
    }

    @Test
    fun testAReactionWeAlreadyHoldChangesNothing() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)))))
        val key = BufferKey(networkId = 1, target = "#lurker")
        val revision = store.state.reactionsRevision(key)
        store.apply(change(1, "bob", "👍"))
        store.apply(change(1, "nobody", "x", remove = true))
        assertEquals(revision, store.state.reactionsRevision(key), "no change, no redraw")
    }

    @Test
    fun testSyncIsAuthoritativeForEveryIdItNames() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)), line(2, reactions = listOf(thumbs)), line(3))))
        val lol = MessageReaction(nick = "carol", value = "lol", isSelf = false)
        store.apply(ServerFrame.ReactionsSync(messageIds = listOf(1, 3), reactions = mapOf(3L to listOf(lol))))
        assertTrue(store.state.reactionGroups(1).isEmpty(), "named, absent = none now")
        assertEquals(1, store.state.reactionGroups(2).size, "not named = untouched")
        assertEquals(listOf("lol"), store.state.reactionGroups(3).map { it.value })
    }

    @Test
    fun testClosingABufferDropsItsLinesReactions() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)))))
        store.apply(ServerFrame.BufferClosed(networkId = 1, target = "#lurker"))
        assertNull(store.state.reactions[1])
    }

    @Test
    fun testSyncIdsAreTheNewestOfEachNetworkBuffer() {
        val store = LurkerStore()
        store.apply(backlog((1..(Reactions.syncPerBuffer + 10)).map { line(it.toLong()) }))
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = null, target = ":system:", kind = BufferKind.System, hydrated = true),
                messages = listOf(line(9999)), hydrated = true, append = false, speakers = null,
            ),
        )
        val ids = store.state.reactionSyncIds()
        assertEquals(Reactions.syncPerBuffer, ids.size)
        assertEquals((Reactions.syncPerBuffer + 10).toLong(), ids.firstOrNull(), "newest first")
        assertFalse(ids.contains(9999), "system lines are another id space")
    }

    // MARK: - canReact

    @Test
    fun testCanReactNeedsTheFlagAndALiveLink() {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        store.apply(
            ServerFrame.Snapshot(
                listOf(NetworkSnapshot(id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList())),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )
        assertFalse(store.state.canReact(networkId = 1), "false until the burst says otherwise")
        store.apply(ServerFrame.ReactSupport(networkId = 1, canReact = true))
        assertTrue(store.state.canReact(networkId = 1))

        // The link drops: whatever the last registration allowed is no answer now…
        store.apply(ServerFrame.NetworkState(networkId = 1, state = ConnectionState.Reconnecting, nick = null))
        assertFalse(store.state.canReact(networkId = 1))
        // …and coming back isn't one either until the new burst re-announces it.
        store.apply(ServerFrame.NetworkState(networkId = 1, state = ConnectionState.Connected, nick = null))
        assertFalse(store.state.canReact(networkId = 1))
        assertFalse(store.state.canReact(networkId = null))
        assertFalse(store.state.canReact(networkId = 99))
    }

    /**
     * While our own socket is down, the network's last-known state says nothing: whatever we
     * send goes nowhere.
     */
    @Test
    fun testCanReactNeedsOurOwnSocket() {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1, state = ConnectionState.Connected, nick = "me", channels = emptyList(), canReact = true,
                    ),
                ),
                globalIgnores = emptyList(), maxUploadBytes = null,
            ),
        )
        assertTrue(store.state.canReact(networkId = 1))
        store.apply(ServerFrame.SocketClosed(reason = null, code = null))
        assertFalse(store.state.canReact(networkId = 1))
    }

    /**
     * ⚠⚠ Someone who takes the nick we reacted under is not us: their reaction is theirs, and
     * taking theirs back must not take ours.
     */
    @Test
    fun testANickCollisionNeverFoldsIntoOurReaction() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(MessageReaction(nick = "alice", value = "👍", isSelf = true))))))
        store.apply(change(1, "alice", "👍"))
        assertEquals(
            listOf(ReactionGroup(value = "👍", nicks = listOf("alice", "alice"), mine = true)),
            store.state.reactionGroups(1),
        )
        store.apply(change(1, "alice", "👍", remove = true))
        assertEquals(
            listOf(ReactionGroup(value = "👍", nicks = listOf("alice"), mine = true)),
            store.state.reactionGroups(1),
        )
    }

    @Test
    fun testAReactionToALineNobodyLoadedIsDropped() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.apply(change(77, "bob", "👍"))
        assertNull(store.state.reactions[77], "its row brings its reactions when it's fetched")
        assertEquals(0, store.state.reactionsRevision(BufferKey(networkId = 1, target = "#lurker")))
    }

    @Test
    fun testARevisionIsPerBuffer() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1))))
        store.apply(backlog(listOf(line(2)), target = "#other"))
        store.apply(change(1, "bob", "👍"))
        assertEquals(1, store.state.reactionsRevision(BufferKey(networkId = 1, target = "#lurker")))
        assertEquals(0, store.state.reactionsRevision(BufferKey(networkId = 1, target = "#other")))
    }

    /** System-buffer ids are another sequence: dropping that buffer must not free network lines'. */
    @Test
    fun testDroppingTheSystemBufferLeavesNetworkReactionsAlone() {
        val store = LurkerStore()
        store.apply(backlog(listOf(line(1, reactions = listOf(thumbs)))))
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = null, target = ":system:", kind = BufferKind.System, hydrated = true),
                messages = listOf(line(1)), hydrated = true, append = false, speakers = null,
            ),
        )
        store.apply(ServerFrame.BufferClosed(networkId = null, target = ":system:"))
        assertEquals(1, store.state.reactionGroups(1).size)
    }

    // MARK: - Gates

    @Test
    fun testValueRules() {
        assertTrue(Reactions.isValidValue("👍"))
        assertTrue(Reactions.isValidValue("lol"))
        assertTrue(Reactions.isValidValue("👨‍👩‍👧‍👦"), "one grapheme, however many scalars")
        assertFalse(Reactions.isValidValue("  "))
        assertFalse(Reactions.isValidValue("a\nb"))
        assertTrue(Reactions.isValidValue("x".repeat(64)))
        assertFalse(Reactions.isValidValue("x".repeat(65)))
    }

    @Test
    fun testSendGate() {
        val ok = line(1)
        assertTrue(Reactions.canSend(ok, target = "#lurker", networkCanReact = true))
        assertTrue(Reactions.canSend(ok, target = "bob", networkCanReact = true), "a DM")
        assertTrue(Reactions.canSend(line(1, type = EventType.Action), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(ok, target = "#lurker", networkCanReact = false))
        assertFalse(Reactions.canSend(line(1, msgid = null), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(line(0), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(line(1, isE2E = true), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(line(1, type = EventType.Notice), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(ok, target = ":server:1", networkCanReact = true))
        assertFalse(Reactions.canSend(ok, target = "=bob", networkCanReact = true), "DCC chat")
    }

    @Test
    fun testReactActionFollowsTheSendGate() {
        val keys = { message: Message, canReact: Boolean ->
            MessageActions.build(
                message,
                scope = MessageActionScope(networkId = 1, isBookmarked = false, target = "#lurker", canReact = canReact),
            ).map { it.key }
        }
        assertTrue(keys(line(1), true).contains(MessageActionKey.React))
        assertTrue(keys(line(1, isSelf = true), true).contains(MessageActionKey.React), "you can react to your own line")
        assertFalse(keys(line(1), false).contains(MessageActionKey.React))
        assertFalse(keys(line(1, type = EventType.Notice), true).contains(MessageActionKey.React))

        var reacted: Message? = null
        val context = MessageActionContext(
            reply = { _ -> }, copy = { _ -> }, setBookmark = { _, _ -> }, showProfile = { _ -> },
            react = { reacted = it },
        )
        val scope = MessageActionScope(networkId = 1, isBookmarked = false, target = "#lurker", canReact = false)
        MessageActions.run(MessageActionKey.React, line(1), scope = scope, context = context)
        assertNull(reacted, "not offered, so not run")
        MessageActions.run(
            MessageActionKey.React, line(1),
            scope = MessageActionScope(networkId = 1, isBookmarked = false, target = "#lurker", canReact = true),
            context = context,
        )
        assertEquals(1L, reacted?.id)
    }

    // Port-only:

    /**
     * Pins the unit of the limit. Swift's `count` is grapheme clusters for free; a UTF-16
     * length would refuse 64 family emoji (each is eleven units), and a code-point count 64
     * flags (each is two). The expectations are the Swift's own answers for the same strings
     * (LurkerKit's `Reactions`, compiled and run on a Mac).
     *
     * ⚠ Needs a host JDK of 20 or later: before that `java.text.BreakIterator` splits a ZWJ
     * sequence into its parts, and the first assertion fails. The device's segmenter is ICU —
     * a candidate for the instrumented suite (see the Port note on `Reactions.graphemeCount`).
     */
    @Test
    fun testTheLimitCountsGraphemeClustersNotUnits() {
        val family = "👨‍👩‍👧‍👦"
        val flag = "🇺🇸"
        assertTrue(Reactions.isValidValue(family.repeat(64)))
        assertFalse(Reactions.isValidValue(family.repeat(65)))
        assertTrue(Reactions.isValidValue(flag.repeat(64)))
        assertFalse(Reactions.isValidValue(flag.repeat(65)))
        assertTrue(Reactions.isValidValue("e\u0301".repeat(64)))
        assertFalse(Reactions.isValidValue("e\u0301".repeat(65)))
        assertTrue(Reactions.isValidValue("x".repeat(63) + family))
        assertFalse(Reactions.isValidValue("x".repeat(64) + family))
    }
}

/** `GET /api/activity` (lurker-ios#183): two sources merged, a cursor per source. */
class ActivityFeedParsingTests {

    @Test
    fun testHighlightAndReactionRowsAndThePairedCursor() {
        val page = FrameParser.parseActivity(
            """
            {"items":[
              {"kind":"reaction","id":40,"reactionId":7,"networkId":1,"networkName":"Libera","target":"#c","nick":"bob","userhost":"bob!b@h","value":"🎉","time":"2026-09-30T12:00:00Z","text":"my line","messageTime":"2026-09-30T11:00:00Z"},
              {"kind":"highlight","id":39,"networkId":1,"target":"#c","type":"message","nick":"carol","text":"me: hi","matched":true}
            ],"next":{"beforeMessage":39,"beforeReaction":7}}
            """.trimIndent(),
        )
        assertEquals(2, page.items.size)
        val reaction = page.items[0]
        assertEquals(FeedReaction(reactionId = 7, value = "🎉", lineText = "my line"), reaction.reaction)
        assertEquals(40L, reaction.message.id, "your line's id: the jump target")
        assertEquals("bob", reaction.message.nick, "the reactor, for the header and ignore rules")
        assertEquals("🎉", reaction.message.text)
        assertEquals("bob!b@h", reaction.message.userhost)
        assertNotNull(reaction.message.date, "the reaction's own time")
        assertNull(page.items[1].reaction)
        assertEquals("me: hi", page.items[1].message.text)
        assertEquals(FeedCursor(beforeMessage = 39, beforeReaction = 7), page.next)
        assertTrue(page.hasMore)
    }

    @Test
    fun testOneSidedCursorStillPagesAndNullEnds() {
        val oneSided = FrameParser.parseActivity("""{"items":[],"next":{"beforeReaction":3}}""")
        assertEquals(FeedCursor(beforeMessage = null, beforeReaction = 3), oneSided.next)
        assertTrue(oneSided.hasMore, "a side that's given nothing yet has no cursor, and that's not the end")
        assertFalse(FrameParser.parseActivity("""{"items":[],"next":null}""").hasMore)
    }

    @Test
    fun testAReactionRowWithoutAValueIsDropped() {
        val page = FrameParser.parseActivity(
            """{"items":[{"kind":"reaction","id":1,"reactionId":2,"value":""}],"next":null}""",
        )
        assertTrue(page.items.isEmpty())
    }
}

/** `/react` (lurker-ios#183). */
class ReactCommandTests {

    // Port note: `CommandParser.parse` takes the expiry's formatter here (see
    // `IgnoreRule.summary`); no `/react` line uses it.
    private fun effects(input: String): List<CommandEffect> {
        val parsed = CommandParser.parse(input, networkId = 1, target = "#c", formatted = { it.toString() })
        return (parsed as? ParsedInput.Command)?.effects ?: emptyList()
    }

    @Test
    fun testParses() {
        assertEquals(listOf<CommandEffect>(CommandEffect.React(value = "👍")), effects("/react 👍"))
        assertEquals(listOf<CommandEffect>(CommandEffect.React(value = "nice one")), effects("/react  nice one "))
        if (effects("/react").firstOrNull() !is CommandEffect.Info) fail("usage expected")
        if (effects("/react " + "x".repeat(65)).firstOrNull() !is CommandEffect.Info) {
            fail("too long should be refused before it reaches the wire")
        }
    }

    private fun line(
        id: Long,
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
        msgid: String? = "m",
        e2e: Boolean = false,
    ): Message =
        Message(id = id, type = type, nick = if (isSelf) "me" else "bob", text = "x", isSelf = isSelf, msgid = msgid, isE2E = e2e)

    @Test
    fun testLandsOnTheLastLineSomeoneElseSaid() {
        val target = Reactions.commandTarget(
            listOf(
                line(1), line(2), line(3, isSelf = true), line(4, EventType.Join),
                Message(id = 0, type = EventType.System, nick = null, text = "local"),
            )
        )
        assertEquals(2L, (target as? Result.Success)?.value?.id)
    }

    @Test
    fun testSaysWhyRatherThanReachingBack() {
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("can't react to a notice")),
            Reactions.commandTarget(listOf(line(1), line(2, EventType.Notice))),
        )
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("can't react to an encrypted line")),
            Reactions.commandTarget(listOf(line(1), line(2, e2e = true))),
        )
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("can't react to that line (no message id)")),
            Reactions.commandTarget(listOf(line(1), line(2, msgid = null))),
        )
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("nothing here to react to")),
            Reactions.commandTarget(listOf(line(3, isSelf = true))),
        )
    }
}

class ReactionRenameTests {
    @Test
    fun testARenameCarriesTheRevision() {
        val store = LurkerStore()
        store.apply(
            ServerFrame.Backlog(
                buffer = Buffer(networkId = 1, target = "bob", kind = BufferKind.Dm, hydrated = true),
                messages = listOf(Message(id = 1, type = EventType.Message, nick = "bob", text = "hi", msgid = "m")),
                hydrated = true, append = false, speakers = null,
            ),
        )
        store.apply(
            ServerFrame.Reaction(
                ReactionChange(
                    networkId = 1, target = "bob", messageId = 1, nick = "me", value = "👍", isSelf = true,
                    remove = false, toSelf = false,
                ),
            ),
        )
        store.apply(
            ServerFrame.BufferRenamed(
                networkId = 1, from = "bob", to = "bobby", bufferId = null, merged = false, mergedFromBufferId = null,
            ),
        )
        assertEquals(1, store.state.reactionsRevision(BufferKey(networkId = 1, target = "bobby")))
        assertNull(store.state.reactionsRevisions["1::bob"])
    }
}
