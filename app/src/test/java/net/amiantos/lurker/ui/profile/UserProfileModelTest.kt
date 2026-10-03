// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.profile

import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.NickNoteSet
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.ProfileStatus
import net.amiantos.lurkerkit.model.WhoisResult
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The profile's sections and copy — lurker-ios's `UserProfileViewController`. */
class UserProfileModelTest {

    private val stamp: (Instant) -> String = { "T${it.epochSecond}" }

    private fun inputs(
        whois: WhoisResult? = null,
        looking: Boolean = false,
        presence: FriendPresence = FriendPresence.Unknown,
        note: NickNote? = null,
        relay: Boolean = false,
        self: String = "me",
    ) = ProfileInputs(whois, looking, presence, note, relay, self)

    private fun rows(inputs: ProfileInputs, canOpen: Boolean = true, nick: String = "alice") =
        UserProfileModel.sections(inputs, nick, canOpenBuffers = canOpen, dateTime = stamp).flatMap { it.rows }

    @Test
    fun aLookupInFlightSaysSoAndOmitsTheUnknownStatusRow() {
        val rows = rows(inputs(looking = true))
        assertEquals(ProfileRow.Status(ProfileStatus.StatusLine.Waiting, "Looking up alice…"), rows.first())
        // ⚠ No "Status: Unknown" under a line saying we're finding out.
        assertTrue(rows.none { it is ProfileRow.Detail && it.title == "Status" })
    }

    @Test
    fun aMissSaysTheyArentOnTheNetworkAndOffersNoMessage() {
        val rows = rows(inputs(whois = WhoisResult(nick = "alice", error = "not_found"), presence = FriendPresence.Unknown))
        assertEquals(ProfileRow.Status(ProfileStatus.StatusLine.NotFound, "alice isn't on this network."), rows.first())
        assertFalse(rows.contains(ProfileRow.SendMessage))
        assertTrue(rows.contains(ProfileRow.Refresh))
    }

    @Test
    fun theFactsReadInIosOrderWithTheirCopy() {
        val whois = WhoisResult(
            nick = "alice",
            ident = "~a",
            hostname = "host",
            realName = "Alice",
            actualHostname = "real.host",
            actualIP = "10.0.0.1",
            server = "irc.example",
            serverInfo = "Example",
            account = "alice",
            isSecure = true,
            idleSeconds = 3_725,
            signedOn = Instant.ofEpochSecond(42),
        )
        val details = rows(inputs(whois = whois, presence = FriendPresence.Online)).filterIsInstance<ProfileRow.Detail>()
        assertEquals(
            listOf(
                ProfileRow.Detail("Status", "Online"),
                ProfileRow.Detail("Real name", "Alice"),
                ProfileRow.Detail("Hostmask", "alice!~a@host", copyable = true),
                ProfileRow.Detail("Connected from", "real.host 10.0.0.1"),
                ProfileRow.Detail("Connection", "Secure (TLS)"),
                ProfileRow.Detail("Account", "alice"),
                ProfileRow.Detail("Server", "irc.example (Example)"),
                ProfileRow.Detail("Idle", "1h 2m"),
                ProfileRow.Detail("Signed on", "T42"),
            ),
            details,
        )
    }

    @Test
    fun anAbsentFlagIsSilenceNotADenial() {
        val details = rows(inputs(whois = WhoisResult(nick = "alice", realName = "A", serverInfo = "Orphan description"))).filterIsInstance<ProfileRow.Detail>()
        assertTrue(details.none { it.title == "Connection" })
        // ⚠ No " (Orphan description)" row without a server name in front of it.
        assertTrue(details.none { it.title == "Server" })
        assertTrue(details.none { it.title == "Connected from" })
    }

    /**
     * What the page's one status place announces when a lookup lands (#20): nothing while it's out,
     * the miss in the status line's words, a hit as the Status row's value under the nick.
     */
    @Test
    fun aLookupsOutcomeIsSaidOnlyOnceItLands() {
        assertNull(UserProfileModel.lookupOutcome(inputs(looking = true), "alice"))
        assertEquals(
            "alice isn't on this network.",
            UserProfileModel.lookupOutcome(inputs(whois = WhoisResult(nick = "alice", error = "not_found")), "alice"),
        )
        assertEquals(
            "alice, Online",
            UserProfileModel.lookupOutcome(inputs(whois = WhoisResult(nick = "alice", realName = "A"), presence = FriendPresence.Online), "alice"),
        )
        // Still waiting on the reply (nothing to draw yet), whatever MONITOR says: no outcome to announce.
        assertNull(UserProfileModel.lookupOutcome(inputs(presence = FriendPresence.Online), "alice"))
    }

    /**
     * A refresh (or a reopen) over a cached reply: the details stay on screen with no status line, but
     * the outcome goes quiet until the new reply lands — so the same answer is read out again, as a change.
     */
    @Test
    fun aRefreshOverCachedDetailsGoesQuietUntilItLands() {
        val cached = WhoisResult(nick = "alice", realName = "A")
        val refreshing = inputs(whois = cached, looking = true, presence = FriendPresence.Online)
        assertNull(UserProfileModel.lookupOutcome(refreshing, "alice"))
        // The cached details still draw meanwhile.
        assertTrue(rows(refreshing).contains(ProfileRow.Detail("Real name", "A")))
        assertEquals("alice, Online", UserProfileModel.lookupOutcome(refreshing.copy(isLookingUp = false), "alice"))
        assertEquals(
            "alice, Away — lunch",
            UserProfileModel.lookupOutcome(inputs(whois = WhoisResult(nick = "alice", realName = "A", away = "lunch")), "alice"),
        )
    }

    @Test
    fun anAwayReasonRidesTheStatusRowOnlyBesideAnAwayDot() {
        val away = WhoisResult(nick = "alice", realName = "A", away = "lunch")
        assertEquals(
            ProfileRow.Detail("Status", "Away — lunch"),
            rows(inputs(whois = away)).filterIsInstance<ProfileRow.Detail>().first(),
        )
        // MONITOR says they're back: the old reason doesn't come with it.
        assertEquals(
            ProfileRow.Detail("Status", "Online"),
            rows(inputs(whois = away, presence = FriendPresence.Online)).filterIsInstance<ProfileRow.Detail>().first(),
        )
    }

    @Test
    fun sendMessageNeedsSomeoneThereSomewhereToGoAndNotYou() {
        val whois = WhoisResult(nick = "alice", realName = "A")
        assertTrue(rows(inputs(whois = whois, presence = FriendPresence.Online)).contains(ProfileRow.SendMessage))
        assertFalse(rows(inputs(whois = whois, presence = FriendPresence.Offline)).contains(ProfileRow.SendMessage))
        assertFalse(rows(inputs(whois = whois, presence = FriendPresence.Online), canOpen = false).contains(ProfileRow.SendMessage))
        // Our own nick, folding case.
        assertFalse(rows(inputs(whois = whois, presence = FriendPresence.Online, self = "ALICE")).contains(ProfileRow.SendMessage))
    }

    @Test
    fun channelsAreOfferedOnlyWhereTheyCanBeOpened() {
        val whois = WhoisResult(nick = "alice", channelsLine = "@#lurker +&local #swift")
        val sections = UserProfileModel.sections(inputs(whois = whois), "alice", canOpenBuffers = true, dateTime = stamp)
        val channels = sections.single { it.header == "Channels" }.rows.map { (it as ProfileRow.Channel).title }
        assertEquals(listOf("@#lurker", "+&local", "#swift"), channels)
        assertTrue(UserProfileModel.sections(inputs(whois = whois), "alice", canOpenBuffers = false, dateTime = stamp).none { it.header == "Channels" })
    }

    @Test
    fun flagsAreTheServersWordsPlusTheLocalRelayMark() {
        val whois = WhoisResult(nick = "bot", registeredNick = "is a registered nick", isOperator = "is an IRC Operator", helpop = "is available for help", bot = "is a bot")
        val flags = rows(inputs(whois = whois, relay = true)).filterIsInstance<ProfileRow.Flag>()
        assertEquals(
            listOf(
                ProfileRow.Flag("is a registered nick", ProfileFlagIcon.Registered),
                ProfileRow.Flag("is an IRC Operator", ProfileFlagIcon.Operator),
                ProfileRow.Flag("is available for help", ProfileFlagIcon.HelpOp),
                ProfileRow.Flag("is a bot", ProfileFlagIcon.Bot),
                ProfileRow.Flag("Marked as a relay bot", ProfileFlagIcon.Relay),
            ),
            flags,
        )
        // The mark shows with no reply in at all — an offline bot is exactly when you'd come looking.
        assertEquals(listOf(ProfileRow.Flag("Marked as a relay bot", ProfileFlagIcon.Relay)), rows(inputs(relay = true)).filterIsInstance<ProfileRow.Flag>())
    }

    @Test
    fun theNoteSectionSaysWhoCanSeeItUntilThereIsAnUpdateTime() {
        val empty = UserProfileModel.noteSection(null, stamp)
        assertEquals("Your note", empty.header)
        assertEquals(UserProfileModel.NOTE_FOOTER, empty.footer)
        assertEquals(listOf(ProfileRow.EditNote(hasNote = false)), empty.rows)
        assertEquals("Add Note", ProfileRow.EditNote(false).title)

        val written = UserProfileModel.noteSection(NickNote("alice", "Lives in Berlin", updatedAt = Instant.ofEpochSecond(7)), stamp)
        assertEquals("Updated T7", written.footer)
        assertEquals(listOf(ProfileRow.Note("Lives in Berlin"), ProfileRow.EditNote(hasNote = true)), written.rows)
        assertEquals("Edit Note", ProfileRow.EditNote(true).title)
    }

    @Test
    fun idleReadsInTwoUnitsAtMost() {
        assertEquals("Active now", UserProfileModel.idle(0))
        assertEquals("59s", UserProfileModel.idle(59))
        assertEquals("1m", UserProfileModel.idle(60))
        assertEquals("1h 2m", UserProfileModel.idle(3_725))
        assertEquals("1d", UserProfileModel.idle(86_400))
        assertEquals("3d 4h", UserProfileModel.idle(3 * 86_400 + 4 * 3_600 + 59))
    }

    @Test
    fun inputsReadThePresenceThatSaysOfflineWhileOurSocketIsDown() {
        val base = ChatState(
            connection = SocketStatus.Reconnecting,
            reachable = true,
            networks = mapOf(1 to Network(id = 1, name = "L", state = ConnectionState.Connected, nick = "me")),
            peerPresence = mapOf(1 to mapOf("alice" to PresenceState.Online)),
            nickNotes = NickNoteSet(mapOf(1 to listOf(NickNote("Alice", "hi")))),
        )
        val down = ProfileInputs.of(base, 1, "alice")
        assertEquals(FriendPresence.Offline, down.presence)
        assertEquals("hi", down.note?.note)
        assertEquals("me", down.selfNick)
        assertEquals(FriendPresence.Online, ProfileInputs.of(base.copy(connection = SocketStatus.Connected), 1, "alice").presence)
        assertNull(ProfileInputs.of(base, 1, "bob").note)
    }

    @Test
    fun aNoteGoesOutOnlyOverAConnectedSocket() {
        assertEquals(null, NickNoteModel.sendRefusal(SocketStatus.Connected, reachable = true))
        assertEquals(NickNoteModel.NOT_CONNECTED, NickNoteModel.sendRefusal(SocketStatus.Reconnecting, reachable = true))
        assertEquals(NickNoteModel.NOT_CONNECTED, NickNoteModel.sendRefusal(SocketStatus.Connected, reachable = false))
        assertEquals("Not connected — try again when you're back online", NickNoteModel.NOT_CONNECTED)
    }

    @Test
    fun theNoteEditorOffersDeleteOnlyWhenThereWasANote() {
        assertFalse(NickNoteModel.offersDelete(""))
        assertTrue(NickNoteModel.offersDelete("x"))
        assertEquals("Note about alice", NickNoteModel.fieldLabel("alice"))
        assertEquals("Your note about alice will be removed from all your devices.", NickNoteModel.deleteMessage("alice"))
    }
}
