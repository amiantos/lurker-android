// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.dcc

import net.amiantos.lurkerkit.model.DccChatOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which DCC chat offer is asked about, and how it's worded (lurker-ios `DccOfferPrompt`, lurker-android#38). */
class DccOfferQueueTest {

    private fun offer(id: Int, nick: String = "bob", networkId: Int = 1, passive: Boolean = false) =
        DccChatOffer(id = id, networkId = networkId, nick = nick, passive = passive)

    private val names = mapOf(1 to "Libera")

    @Test
    fun `asks about the oldest offer, then the next once it's answered`() {
        val queue = DccOfferQueue()
        assertEquals(1, queue.update(listOf(offer(1), offer(2, "carol")), names)?.offer?.id)
        assertEquals(2, queue.answered(1)?.offer?.id)
        assertNull(queue.answered(2))
    }

    @Test
    fun `an offer is asked about once — a snapshot re-listing it doesn't ask again`() {
        val queue = DccOfferQueue()
        queue.update(listOf(offer(1)), names)
        queue.answered(1) // Not Now: the offer stands.
        assertNull(queue.update(listOf(offer(1)), names))
    }

    @Test
    fun `a peer offering again is a fresh offer, and asked about`() {
        val queue = DccOfferQueue()
        queue.update(listOf(offer(1)), names)
        queue.answered(1)
        assertEquals(5, queue.update(listOf(offer(5)), names)?.offer?.id)
    }

    @Test
    fun `the question is over when its offer is`() {
        val queue = DccOfferQueue()
        queue.update(listOf(offer(1)), names)
        assertNull(queue.update(emptyList(), names))
        assertNull(queue.current)
    }

    @Test
    fun `an offer first learned from a snapshot is asked about too`() {
        // A snapshot offer reads passive = false; it's asked all the same.
        assertEquals(3, DccOfferQueue().update(listOf(offer(3)), names)?.offer?.id)
    }

    @Test
    fun `the prompt is iOS's, word for word`() {
        val prompt = DccOfferPrompt(offer(1), "Libera")
        assertEquals("DCC chat from bob", prompt.title)
        assertEquals("bob on Libera wants to chat directly.", prompt.message)
        assertEquals("bob wants to chat directly.", DccOfferPrompt(offer(1), null).message)
        assertEquals(
            "bob on Libera wants to chat directly. They're behind a firewall, so your Lurker server would listen for them.",
            DccOfferPrompt(offer(1, passive = true), "Libera").message,
        )
    }

    @Test
    fun `the network's name rides the prompt`() {
        assertEquals("OFTC", DccOfferQueue().update(listOf(offer(1, networkId = 2)), mapOf(2 to "OFTC"))?.networkName)
    }
}
