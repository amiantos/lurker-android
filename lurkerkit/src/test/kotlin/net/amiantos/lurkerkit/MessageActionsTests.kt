// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.LinkActionContext
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageAction
import net.amiantos.lurkerkit.model.MessageActionContext
import net.amiantos.lurkerkit.model.MessageActionKey
import net.amiantos.lurkerkit.model.MessageActionScope
import net.amiantos.lurkerkit.model.MessageActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Which actions a message offers, and what running one does (lurker-ios#60).
 *
 * The eligibility rules are the point: they decide whether a long press on a given row does
 * anything at all, and getting one wrong is quiet — an action bar offering Reply on a join line,
 * or a message that silently refuses to open a menu, both look like plausible behaviour.
 */
class MessageActionsTests {

    private fun msg(
        id: Long = 1,
        type: EventType = EventType.Message,
        nick: String? = "alice",
        text: String? = "hello",
        isSelf: Boolean = false,
    ): Message =
        Message(id = id, type = type, nick = nick, text = text, isSelf = isSelf)

    // MARK: - Eligibility

    @Test
    fun testSpeechOffersReplyCopyBookmark() {
        val actions = build(msg())
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Copy, MessageActionKey.Bookmark, MessageActionKey.Profile),
            actions.map { it.key },
        )
        assertEquals("Reply to alice", actions.firstOrNull()?.title)
    }

    /**
     * Notices and `/me` actions are speech too, so they carry the same menu — a notice bubbles
     * and an action renders as a full-width line, but that's a layout difference, not a
     * difference in what you can do with them.
     */
    @Test
    fun testNoticeAndActionAreEligible() {
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Copy, MessageActionKey.Bookmark, MessageActionKey.Profile),
            build(msg(type = EventType.Notice)).map { it.key },
        )
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Copy, MessageActionKey.Bookmark, MessageActionKey.Profile),
            build(msg(type = EventType.Action)).map { it.key },
        )
    }

    /**
     * The server's own output — MOTD, system, error — is not speech, so no Reply. But it is the
     * text people most often want off the screen, and on iOS this menu is the only way to get it
     * (the row menu took the long press from the selection loupe), so Copy has to be there.
     *
     * Copy is the ONLY thing that divergence buys. Bookmark keeps the web's speech gate: the
     * feed is for things people said, not for a saved MOTD or connection error.
     *
     * ⚠ Profile is out too, and this is the case that decides its gate. These lines carry a
     * nick-shaped field that is not a person — a MOTD's is the server — so gating Profile on
     * "has a nick" would offer a whois for a hostname. Note the fixture gives every type the
     * nick `alice`, which is exactly why the gate can't be about whether one is there.
     */
    @Test
    fun testServerTextOffersCopyOnly() {
        for (type in listOf(
            EventType.System, EventType.Motd, EventType.Error, EventType.Ctcp, EventType.E2e, EventType.Other,
        )) {
            assertEquals(
                listOf(MessageActionKey.Copy),
                build(msg(type = type, text = "something")).map { it.key },
                "$type should offer Copy and nothing else",
            )
        }
    }

    /**
     * A NOTICE is speech, so it stays bookmarkable even though it's most often seen in a
     * server buffer — matching the web, whose gate is the message type and not the buffer.
     */
    @Test
    fun testNoticeStaysBookmarkable() {
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Copy, MessageActionKey.Bookmark, MessageActionKey.Profile),
            build(msg(type = EventType.Notice)).map { it.key },
        )
    }

    /**
     * Activity narration offers Profile and nothing else.
     *
     * The three it still refuses each have their own reason, and none of them generalises to
     * "narration is inert": you can't address a sentence (Reply), its `text` is a fragment of
     * what's on screen so Copy would paste something other than the pressed line, and churn
     * isn't content so one saved "alice joined" says nothing on its own.
     *
     * Profile has no such reason (lurker-ios#12). The nick in a join or a kick is a real person
     * on this network, and "who is that" is exactly what you want to ask about a nick you just
     * watched arrive — which used to be a line you could not interact with at all.
     */
    @Test
    fun testActivityNarrationOffersOnlyProfile() {
        for (type in listOf(
            EventType.Join, EventType.Part, EventType.Quit, EventType.Nick, EventType.Kick, EventType.Mode,
            EventType.Topic, EventType.Invite, EventType.Chghost,
        )) {
            assertEquals(
                listOf(MessageActionKey.Profile),
                build(msg(type = type, text = "brb")).map { it.key },
                "$type should offer Profile and nothing else",
            )
        }
    }

    /**
     * Id 0 is this client's "no id" — an ephemeral, locally synthesized line. The server having
     * never heard of it doesn't make its text less copyable, so Reply and Copy stay. Bookmark is
     * the one that genuinely needs the id, and it's the one that drops.
     */
    @Test
    fun testEphemeralLineStillOffersCopy() {
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Copy, MessageActionKey.Profile),
            build(msg(id = 0)).map { it.key },
        )
        assertEquals(
            listOf(MessageActionKey.Copy),
            build(msg(id = 0, type = EventType.System, nick = null)).map { it.key },
        )
    }

    // MARK: - Profile (lurker-ios#12)

    @Test
    fun testProfileNamesWhoItWillLookUp() {
        // The title carries the subject because on a relayed line it is not the nick on
        // screen — see below. Naming it always keeps the two cases reading the same way.
        assertEquals(
            "Profile of alice",
            build(msg()).firstOrNull { it.key == MessageActionKey.Profile }?.title,
        )
    }

    @Test
    fun testProfileNeedsANetworkToAskOn() {
        // A system-buffer line is app-scoped and has no connection; a whois there has nowhere
        // to go. Same gate Bookmark needs, for a different reason.
        val actions = MessageActions.build(
            msg(), scope = MessageActionScope(networkId = null, isBookmarked = false),
        )
        assertFalse(actions.any { it.key == MessageActionKey.Profile })
    }

    @Test
    fun testProfileNeedsANick() {
        assertFalse(build(msg(nick = null)).any { it.key == MessageActionKey.Profile })
        assertFalse(build(msg(nick = "")).any { it.key == MessageActionKey.Profile })
    }

    @Test
    fun testYourOwnLineStillOffersAProfile() {
        // Unlike Reply. Your own whois is how you check your host and your modes.
        assertTrue(build(msg(isSelf = true)).any { it.key == MessageActionKey.Profile })
    }

    @Test
    fun testARelayedLineProfilesTheBridgeNotTheSpeaker() {
        // ⚠⚠ On a re-attributed line the nick on screen is somebody on the far side of a
        // bridge, with no IRC presence at all — a whois for them answers `not_found` every
        // time. The bot is the only thing here the network has heard of, which is the same
        // rule the action sheet's own header follows ("alice via relaybot").
        val relayed = msg(nick = "relaybot", text = "<alice> hi")
            .relayed(speaker = "alice", text = "hi", bot = "relaybot", source = "Discord")
        assertEquals("relaybot", MessageActions.profileSubject(relayed))
        // And the title says so, so the offer is legible beside a row that reads "alice".
        assertEquals(
            "Profile of relaybot",
            build(relayed).firstOrNull { it.key == MessageActionKey.Profile }?.title,
        )
    }

    @Test
    fun testRunningProfileHandsBackTheBridgeToo() {
        val relayed = msg(nick = "relaybot", text = "<alice> hi")
            .relayed(speaker = "alice", text = "hi", bot = "relaybot", source = null)
        var asked: String? = null
        run(
            MessageActionKey.Profile, relayed,
            context = context(
                showProfile = { asked = it },
            ),
        )
        assertEquals("relaybot", asked)
    }

    @Test
    fun testANickChangeProfilesTheNewNameNotTheOldOne() {
        // ⚠⚠ A nick line's `nick` is what the sentence is ABOUT, not who is there now. Asking
        // the network about it answers `not_found` every time, so the profile would report
        // "bob isn't on this network" about somebody standing right there as bob_afk.
        val renamed = Message(id = 1, type = EventType.Nick, nick = "bob", text = null, newNick = "bob_afk")
        assertEquals("bob_afk", MessageActions.profileSubject(renamed))
        assertEquals(
            "Profile of bob_afk",
            MessageActions.build(renamed, scope = scope())
                .firstOrNull { it.key == MessageActionKey.Profile }?.title,
        )
    }

    @Test
    fun testANickChangeWithNoNewNameFallsBackToTheOldOne() {
        // A malformed frame shouldn't cost the row entirely — the old nick is still the best
        // guess about who the line is about.
        val renamed = Message(id = 1, type = EventType.Nick, nick = "bob", text = null)
        assertEquals("bob", MessageActions.profileSubject(renamed))
    }

    @Test
    fun testProfileIsANoOpOnALineThatDoesNotOfferIt() {
        // `run`'s standing guarantee: an action the line doesn't have does nothing. A MOTD
        // carries a nick-shaped field, so without the gate this would whois a server.
        run(MessageActionKey.Profile, msg(type = EventType.Motd), context = context())
    }

    // MARK: - Per-action gating

    @Test
    fun testOwnMessageOffersNoReply() {
        assertEquals(
            listOf(MessageActionKey.Copy, MessageActionKey.Bookmark, MessageActionKey.Profile),
            build(msg(isSelf = true)).map { it.key },
        )
    }

    @Test
    fun testNicklessMessageOffersNoReply() {
        assertEquals(listOf(MessageActionKey.Copy, MessageActionKey.Bookmark), build(msg(nick = null)).map { it.key })
        assertEquals(listOf(MessageActionKey.Copy, MessageActionKey.Bookmark), build(msg(nick = "")).map { it.key })
    }

    /**
     * An upload with no caption, say: nothing to put on the clipboard, but still someone to
     * reply to and still a line worth keeping.
     */
    @Test
    fun testTextlessMessageOffersNoCopy() {
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Bookmark, MessageActionKey.Profile),
            build(msg(text = null)).map { it.key },
        )
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Bookmark, MessageActionKey.Profile),
            build(msg(text = "")).map { it.key },
        )
    }

    // MARK: - Running

    @Test
    fun testReplyHandsBackTheNick() {
        val replied = mutableListOf<String>()
        run(MessageActionKey.Reply, msg(), context = context(reply = { replied.add(it.nick ?: "") }))
        assertEquals(listOf("alice"), replied)
    }

    /**
     * The raw text, not a rendering of it: what gets pasted should be what was typed, mIRC
     * color codes included.
     */
    @Test
    fun testCopyHandsBackTheRawText() {
        val copied = mutableListOf<String>()
        val raw = "\u000304red\u0003 and https://example.com"
        run(MessageActionKey.Copy, msg(text = raw), context = context(copy = { copied.add(it) }))
        assertEquals(listOf(raw), copied)
    }

    /**
     * A menu built from a row that has since changed underneath it can't fire an action on
     * nothing.
     */
    @Test
    fun testRunningAnUnavailableActionDoesNothing() {
        var fired = 0
        val ctx = context(reply = { _ -> fired += 1 }, copy = { _ -> fired += 1 })
        run(MessageActionKey.Reply, msg(nick = null), context = ctx)
        run(MessageActionKey.Copy, msg(text = ""), context = ctx)
        assertEquals(0, fired)
    }

    /**
     * `run` gates on exactly what `build` offers, not on a looser restatement of it. Each of these
     * has the field the action needs — a nick, some text — but isn't offered the action, so
     * running it anyway would reply to yourself, reply to a server line, or paste the fragment out
     * of an activity line ("brb" from `alice left (brb)`).
     */
    @Test
    fun testRunEnforcesTheSameGateAsBuild() {
        var fired = 0
        val ctx = context(reply = { _ -> fired += 1 }, copy = { _ -> fired += 1 })

        run(MessageActionKey.Reply, msg(isSelf = true), context = ctx)
        run(MessageActionKey.Reply, msg(type = EventType.Motd), context = ctx)
        run(MessageActionKey.Reply, msg(type = EventType.Part, text = "brb"), context = ctx)
        run(MessageActionKey.Copy, msg(type = EventType.Part, text = "brb"), context = ctx)
        assertEquals(0, fired)

        // …and still runs the ones that ARE offered, so the gate isn't just refusing everything.
        run(MessageActionKey.Reply, msg(), context = ctx)
        run(MessageActionKey.Copy, msg(), context = ctx)
        assertEquals(2, fired)
    }

    // MARK: - Links

    /** A URL is a URL — nothing to gate on, so the list is fixed. */
    @Test
    fun testLinkOffersOpenCopyShare() {
        val actions = MessageActions.build("https://example.com")
        assertEquals(
            listOf(MessageActionKey.OpenLink, MessageActionKey.CopyLink, MessageActionKey.ShareLink),
            actions.map { it.key },
        )
        assertEquals(listOf("Open Link", "Copy Link", "Share Link"), actions.map { it.title })
    }

    @Test
    fun testLinkActionsDispatchToTheirHandlers() {
        val url = "https://example.com/thing"
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val ctx = LinkActionContext(
            open = { opened.add(it) }, copy = { copied.add(it) }, share = { shared.add(it) },
        )
        MessageActions.run(MessageActionKey.OpenLink, url, context = ctx)
        MessageActions.run(MessageActionKey.CopyLink, url, context = ctx)
        MessageActions.run(MessageActionKey.ShareLink, url, context = ctx)
        assertEquals(listOf(url), opened)
        assertEquals(listOf(url), copied)
        assertEquals(listOf(url), shared)
    }

    /**
     * One screen renders both menus, so each dispatcher has to ignore the other's keys rather
     * than trap on them.
     */
    @Test
    fun testKeysFromTheOtherMenuAreIgnored() {
        val url = "https://example.com"
        val linkContext = LinkActionContext(
            open = { _ -> fail("unexpected open") },
            copy = { _ -> fail("unexpected copy") },
            share = { _ -> fail("unexpected share") },
        )
        MessageActions.run(MessageActionKey.Reply, url, context = linkContext)
        MessageActions.run(MessageActionKey.Copy, url, context = linkContext)

        for (key in listOf(MessageActionKey.OpenLink, MessageActionKey.CopyLink, MessageActionKey.ShareLink)) {
            run(key, msg(), context = context())
        }
    }

    // MARK: - Bookmark

    /**
     * The network gate. A system-buffer line is app-scoped, and the server refuses to bookmark
     * one — the ownership check joins through networks, which it has none of, so the insert
     * writes nothing and no echo comes back. Offering Save there would be a permanent silent
     * no-op, so it isn't offered.
     */
    @Test
    fun testSystemBufferLineOffersNoBookmark() {
        val scope = MessageActionScope(networkId = null, isBookmarked = false)
        assertEquals(
            listOf(MessageActionKey.Reply, MessageActionKey.Copy),
            MessageActions.build(msg(), scope = scope).map { it.key },
        )
        assertEquals(
            listOf(MessageActionKey.Copy),
            MessageActions.build(msg(type = EventType.System, nick = null), scope = scope).map { it.key },
        )
    }

    /**
     * The label and glyph are the only thing that changes with saved state — the action is in
     * the same place either way, so the row doesn't move under a thumb that's already reaching.
     */
    @Test
    fun testBookmarkLabelReflectsSavedState() {
        val saved = build(msg(), isBookmarked = true).firstOrNull { it.key == MessageActionKey.Bookmark }
        assertEquals("Remove Bookmark", saved?.title)
        assertEquals("bookmark.fill", saved?.symbol)

        val unsaved = build(msg()).firstOrNull { it.key == MessageActionKey.Bookmark }
        assertEquals("Save Message", unsaved?.title)
        assertEquals("bookmark", unsaved?.symbol)
    }

    /**
     * The direction comes from the scope that titled the row, NOT from a re-read — so a sheet
     * built before an echo landed still does what it said it would, rather than inverting
     * under the user.
     */
    @Test
    fun testBookmarkSendsTheDirectionItsLabelPromised() {
        val calls = mutableListOf<Pair<Long, Boolean>>()
        val ctx = context(setBookmark = { id, saved -> calls.add(Pair(id, saved)) })

        // Row read "Save Message" → a save goes out.
        MessageActions.run(
            MessageActionKey.Bookmark, msg(id = 77),
            scope = MessageActionScope(networkId = 1, isBookmarked = false), context = ctx,
        )
        // Row read "Remove Bookmark" → an unsave does.
        MessageActions.run(
            MessageActionKey.Bookmark, msg(id = 77),
            scope = MessageActionScope(networkId = 1, isBookmarked = true), context = ctx,
        )

        assertEquals(listOf(77L, 77L), calls.map { it.first })
        assertEquals(listOf(true, false), calls.map { it.second })
    }

    /**
     * `run` gates on what `build` offers, so the two unbookmarkable cases can't fire it even if
     * a stale sheet asks.
     */
    @Test
    fun testRunningBookmarkOnAnIneligibleLineDoesNothing() {
        var fired = 0
        val ctx = context(setBookmark = { _, _ -> fired += 1 })
        // No network (system buffer), no id (ephemeral), narration, and server output —
        // the four ways a line fails the gate.
        MessageActions.run(
            MessageActionKey.Bookmark, msg(),
            scope = MessageActionScope(networkId = null, isBookmarked = false), context = ctx,
        )
        run(MessageActionKey.Bookmark, msg(id = 0), context = ctx)
        run(MessageActionKey.Bookmark, msg(type = EventType.Join, text = null), context = ctx)
        run(MessageActionKey.Bookmark, msg(type = EventType.Motd), context = ctx)
        assertEquals(0, fired)
    }

    // MARK: - Helpers

    /**
     * The default scope: a real network, nothing saved. The bookmark-specific cases pass their
     * own.
     */
    private fun scope(isBookmarked: Boolean = false): MessageActionScope =
        MessageActionScope(networkId = 1, isBookmarked = isBookmarked)

    private fun build(message: Message, isBookmarked: Boolean = false): List<MessageAction> =
        MessageActions.build(message, scope = scope(isBookmarked = isBookmarked))

    private fun run(key: MessageActionKey, message: Message, context: MessageActionContext) {
        MessageActions.run(key, message, scope = scope(), context = context)
    }

    private fun context(
        reply: (Message) -> Unit = { line -> fail("unexpected reply: ${line.id}") },
        copy: (String) -> Unit = { text -> fail("unexpected copy: $text") },
        setBookmark: (Long, Boolean) -> Unit = { id, saved ->
            fail("unexpected bookmark: $id saved=$saved")
        },
        showProfile: (String) -> Unit = { nick ->
            fail("unexpected profile: $nick")
        },
    ): MessageActionContext =
        MessageActionContext(
            reply = reply, copy = copy, setBookmark = setBookmark, showProfile = showProfile,
        )
}
