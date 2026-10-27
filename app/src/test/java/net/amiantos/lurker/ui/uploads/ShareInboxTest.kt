// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The share sheet's payload, the inbox that holds it, and the picker's list. */
class ShareInboxTest {
    @Test
    fun blankTextIsNoText() {
        assertNull(SharePayload.of(listOf("content://a"), " \n ").text)
        assertTrue(SharePayload.of(emptyList(), "  ").isEmpty)
        assertEquals("https://example.com", SharePayload.of(emptyList(), "https://example.com").text)
    }

    @Test
    fun theSameFileSharedTwiceIsOneUpload() {
        assertEquals(listOf("content://a"), SharePayload.of(listOf("content://a", "content://a"), null).streams)
    }

    @Test
    fun theInboxHoldsOneShareUntilItIsTaken() {
        val inbox = ShareInbox()
        inbox.receive(SharePayload.of(emptyList(), " "))
        assertNull(inbox.share.value)
        inbox.receive(SharePayload.of(listOf("content://a"), null))
        inbox.receive(SharePayload.of(listOf("content://b"), null))
        // The newest intent is what the system means.
        assertEquals(listOf("content://b"), inbox.take()!!.streams)
        assertNull(inbox.take())
        inbox.receive(SharePayload.of(emptyList(), "hi"))
        inbox.clear()
        assertNull(inbox.share.value)
    }

    @Test
    fun theListIsEveryConversationUnderItsNetworkInTheUsersOrder() {
        val networks = mapOf(
            1 to Network(id = 1, name = "Libera", position = 2),
            2 to Network(id = 2, name = "OFTC", position = 1),
        )
        val buffers = listOf(
            Buffer(networkId = 1, target = "bob", kind = BufferKind.Dm),
            Buffer(networkId = 1, target = "#Lurker", kind = BufferKind.Channel),
            Buffer(networkId = 1, target = "&local", kind = BufferKind.Channel),
            Buffer(networkId = 1, target = Buffer.serverTarget(1), kind = BufferKind.Server),
            Buffer(networkId = null, target = Buffer.systemTarget, kind = BufferKind.System),
            Buffer(networkId = 2, target = "#debian", kind = BufferKind.Channel),
            Buffer(networkId = 3, target = "=carol", kind = BufferKind.Dcc),
        )
        val sections = ShareTargets.sections(networks, buffers)
        assertEquals(listOf("OFTC", "Libera", Network.unnamedDisplayName), sections.map { it.title })
        // Channels (any sigil) first, then people; no server log, no Lurker buffer.
        assertEquals(listOf("#Lurker", "&local", "bob"), sections[1].targets.map { it.name })
        assertEquals(BufferKey(1, "#Lurker"), sections[1].targets[0].key)
        assertEquals(listOf("=carol"), sections[2].targets.map { it.name })
    }

    /**
     * ⚠⚠ The share sheet's confused-deputy guard: only another app's `content://` address is a
     * share. A `file://` path would be opened as this app — a path into its own sandbox included —
     * and this app's own provider is the same trick one layer up.
     */
    @Test
    fun onlyAnotherAppsContentAddressIsAShare() {
        val pkg = "net.amiantos.lurker"
        assertTrue(ShareUris.isAcceptable("content", "com.android.providers.media.documents", pkg))
        assertTrue(ShareUris.isAcceptable("content", "com.google.android.apps.photos.contentprovider", pkg))
        assertFalse(ShareUris.isAcceptable("file", null, pkg))
        assertFalse(ShareUris.isAcceptable("file", "", pkg))
        assertFalse(ShareUris.isAcceptable("content", "net.amiantos.lurker.files", pkg))
        assertFalse(ShareUris.isAcceptable("content", "net.amiantos.lurker", pkg))
        assertFalse(ShareUris.isAcceptable("content", "net.amiantos.lurker.anything", pkg))
        assertFalse(ShareUris.isAcceptable("content", null, pkg))
        assertFalse(ShareUris.isAcceptable("content", "", pkg))
        assertFalse(ShareUris.isAcceptable(null, "com.android.providers.media.documents", pkg))
        assertFalse(ShareUris.isAcceptable("android.resource", "net.amiantos.other", pkg))
    }

    /** The pick is the only consent step, so it names what is being shared. */
    @Test
    fun theSummaryNamesTheFilesAndQuotesTheText() {
        assertNull(ShareSummary.files(emptyList()))
        assertEquals("photo.jpg", ShareSummary.files(listOf("photo.jpg")))
        assertEquals("unnamed file", ShareSummary.files(listOf(null)))
        assertEquals("unnamed file", ShareSummary.files(listOf("  ")))
        assertEquals("3 files: a.jpg, unnamed file, c.mp4", ShareSummary.files(listOf("a.jpg", null, "c.mp4")))
        assertNull(ShareSummary.text(null))
        assertNull(ShareSummary.text("  \n "))
        assertEquals("\u201Chttps://example.com\u201D", ShareSummary.text("https://example.com"))
        assertEquals("\u201Cfirst line\u201D", ShareSummary.text("  first line\nsecond line"))
        assertEquals("\u201Cabcde\u2026\u201D", ShareSummary.text("abcde fghij", limit = 5))
    }

    @Test
    fun onlyConversationsTakeUploads() {
        assertTrue(UploadTargets.takes(BufferKey(1, "#lurker")))
        assertTrue(UploadTargets.takes(BufferKey(1, "&local")))
        assertTrue(UploadTargets.takes(BufferKey(1, "bob")))
        assertTrue(UploadTargets.takes(BufferKey(1, "=carol")))
        assertFalse(UploadTargets.takes(BufferKey(1, Buffer.serverTarget(1))))
        assertFalse(UploadTargets.takes(BufferKey(null, Buffer.systemTarget)))
    }
}
