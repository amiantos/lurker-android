// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.bufferinfo

import net.amiantos.lurkerkit.model.BufferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An open buffer dialog following a rename — what each page, and the saved request, now describe. */
class DialogRenamesTest {

    private val dm = BufferRename(BufferKey(1, "bob"), BufferKey(1, "robert"))
    private val channel = BufferRename(BufferKey(1, "#old"), BufferKey(1, "#new"))

    @Test
    fun aPageAboutTheRenamedBufferMovesFoldingCase() {
        assertEquals(BufferKey(1, "#new"), DialogRenames.key(BufferKey(1, "#OLD"), channel))
        assertNull(DialogRenames.key(BufferKey(1, "#other"), channel))
        assertNull(DialogRenames.key(BufferKey(2, "#old"), channel))
    }

    @Test
    fun aProfileFollowsItsPersonsDmOrDccChatRenameOnly() {
        assertEquals("robert", DialogRenames.nick(1, "Bob", dm))
        assertEquals("robert", DialogRenames.nick(1, "bob", BufferRename(BufferKey(1, "=bob"), BufferKey(1, "=robert"))))
        // Someone else, another network, or a channel — nobody's nick changed.
        assertNull(DialogRenames.nick(1, "alice", dm))
        assertNull(DialogRenames.nick(2, "bob", dm))
        assertNull(DialogRenames.nick(1, "#old", channel))
    }

    @Test
    fun theSavedRequestFollowsWhatItWasOpenedFor() {
        val info = BufferSheetRequest(BufferSheetStart.Info, "t", 1, "#old")
        assertEquals(info.copy(target = "#new"), DialogRenames.request(info, channel))
        assertNull(DialogRenames.request(info, dm))
        val members = BufferSheetRequest(BufferSheetStart.Members, "t", 1, "#old")
        assertEquals("#new", DialogRenames.request(members, channel)?.target)
        val profile = BufferSheetRequest(BufferSheetStart.Profile, "t", 1, "bob")
        assertEquals(profile.copy(target = "robert"), DialogRenames.request(profile, dm))
        // The token stays: the flow is found again under it.
        assertEquals("t", DialogRenames.request(profile, dm)?.token)
        assertNull(DialogRenames.request(profile, channel))
        // A second delivery of the same rename finds nothing left to move.
        assertNull(DialogRenames.request(DialogRenames.request(info, channel)!!, channel))
    }
}
