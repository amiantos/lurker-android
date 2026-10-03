// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.channel

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.ModeListEntry
import net.amiantos.lurkerkit.model.ModeListResult
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.PrefixMode
import net.amiantos.lurkerkit.store.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A channel's list page — lurker-ios's `ModeListViewController`. */
class ModeListModelTest {

    private val key = BufferKey(networkId = 1, target = "#lurker")
    private val spec = ModeSpec(list = "beI", always = "k", onSet = "l", flags = "nt", prefix = listOf(PrefixMode("q", "~"), PrefixMode("o", "@")), maxModes = 4, topicLen = null)

    private fun slice(linkUp: Boolean = true, joined: Boolean = true, withSpec: Boolean = true, op: Boolean = true, letter: String = "b"): ModeListSlice {
        val state = ChatState(
            networks = mapOf(
                1 to Network(
                    id = 1,
                    name = "L",
                    state = if (linkUp) ConnectionState.Connected else ConnectionState.Reconnecting,
                    nick = "me",
                    modeSpec = if (withSpec) spec else null,
                ),
            ),
            buffers = mapOf(key.id to Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, joined = joined)),
            members = mapOf(key.id to listOf(Member("me", modes = if (op) listOf("o") else emptyList()))),
        )
        return ModeListSlice.of(state, key, letter)
    }

    @Test
    fun readinessNeedsTheLinkTheChannelAndTheVocabulary() {
        assertTrue(slice().ready)
        assertFalse(slice(linkUp = false).ready)
        assertFalse(slice(joined = false).ready)
        assertFalse(slice(withSpec = false).ready)
        // ⚠ `q` is an owner PREFIX on this network, not a list: never offered as one.
        assertFalse(slice(letter = "q").listed)
        assertTrue(slice().canEdit)
        assertFalse(slice(op = false).canEdit)
    }

    @Test
    fun anOwedFetchIsPaidOnTheRisingEdgeOnly() {
        val fetches = ModeListFetches()
        assertFalse(fetches.linkMoved(slice(linkUp = false)))
        val first = fetches.start()
        // Asked while down: owed.
        assertEquals(ModeListStatus.Failed("Not connected."), fetches.answered(first, ModeListResult.Offline))
        assertFalse(fetches.linkMoved(slice(linkUp = false)))
        assertTrue(fetches.linkMoved(slice()))
        // Paid once: staying up asks nothing more.
        assertFalse(fetches.linkMoved(slice()))
    }

    @Test
    fun aResyncFetchesAtOnceWhenReadyElseWhenTheLinkComesUp() {
        val up = ModeListFetches()
        up.linkMoved(slice())
        assertTrue(up.resynced())

        val down = ModeListFetches()
        down.linkMoved(slice(linkUp = false))
        // ⚠ Never straight off a resync whose network is still registering.
        assertFalse(down.resynced())
        assertTrue(down.linkMoved(slice()))
    }

    @Test
    fun aRefusalFromAReadyLinkStandsUntilTheUserRefreshes() {
        val fetches = ModeListFetches()
        fetches.linkMoved(slice())
        val asked = fetches.start()
        assertEquals(ModeListStatus.Failed("Only channel operators can see this list."), fetches.answered(asked, ModeListResult.Failed("Only channel operators can see this list.")))
        // The link dropping and coming back is no reason to ask again.
        fetches.linkMoved(slice(linkUp = false))
        assertFalse(fetches.linkMoved(slice()))
    }

    @Test
    fun aRefusalBeforeTheVocabularyArrivedIsAskedAgainWhenItDoes() {
        val fetches = ModeListFetches()
        fetches.linkMoved(slice(withSpec = false))
        val asked = fetches.start()
        fetches.answered(asked, ModeListResult.Failed("Unknown list."))
        assertTrue(fetches.linkMoved(slice()))
    }

    @Test
    fun aSupersededFetchAnswersNothing() {
        val fetches = ModeListFetches()
        val old = fetches.start()
        val new = fetches.start()
        assertNull(fetches.answered(old, ModeListResult.Entries(emptyList())))
        assertEquals(ModeListStatus.Ready(emptyList()), fetches.answered(new, ModeListResult.Entries(emptyList())))
    }

    @Test
    fun aChangeIsCheckedWhenItsSentNotWhenTheButtonWasDrawn() {
        assertEquals("This network doesn't have this list right now.", ModeListModel.refusal(listedNow = false, canEditNow = true, mask = "x!*@*"))
        assertEquals("Only channel operators can change this list.", ModeListModel.refusal(listedNow = true, canEditNow = false, mask = "x!*@*"))
        assertEquals("A mask can't contain spaces.", ModeListModel.refusal(listedNow = true, canEditNow = true, mask = "x !*@*"))
        assertNull(ModeListModel.refusal(listedNow = true, canEditNow = true, mask = "x!*@*"))
    }

    @Test
    fun theListIsTheFetchPatchedByLiveRows() {
        val fetched = ModeListStatus.Ready(listOf(ModeListEntry("*!*@old", setBy = "op", setAt = null)))
        val rows = listOf(
            Message(id = 1, type = EventType.Mode, nick = "op2", text = null, date = Instant.ofEpochSecond(9), modes = listOf(ModeChange("+b", "new!*@*", ModeChangeKind.List))),
            Message(id = 2, type = EventType.Mode, nick = "op2", text = null, modes = listOf(ModeChange("-b", "*!*@OLD", ModeChangeKind.List))),
        )
        assertEquals(listOf(ModeListEntry("new!*@*", setBy = "op2", setAt = Instant.ofEpochSecond(9))), ModeListModel.shown(fetched, rows, "b"))
        // Another list's rows leave this one alone.
        assertEquals(fetched.entries, ModeListModel.shown(fetched, rows, "e"))
        assertTrue(ModeListModel.shown(ModeListStatus.Loading, rows, "b").isEmpty())
    }

    @Test
    fun anEntrySaysWhoAndWhenItKnows() {
        val stamp: (Instant) -> String = { "T${it.epochSecond}" }
        assertEquals("by alice · T5", ModeListModel.meta(ModeListEntry("m", "alice!a@h", Instant.ofEpochSecond(5)), stamp))
        assertEquals("by ChanServ", ModeListModel.meta(ModeListEntry("m", "ChanServ", null), stamp))
        assertNull(ModeListModel.meta(ModeListEntry("m", null, null), stamp))
        assertEquals("Add to Bans", ModeListModel.addTitle("Bans"))
    }
}
