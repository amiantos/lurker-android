// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.dcc

import net.amiantos.lurkerkit.model.DccChatOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which DCC chat offer is asked about, and how it's worded (lurker-ios `DccOfferPrompt`, lurker-android#38). */
class DccOfferQueueTest {

    private fun offer(id: Int, nick: String = "bob", networkId: Int = 1, passive: Boolean = false) =
        DccChatOffer(id = id, networkId = networkId, nick = nick, passive = passive)

    private val names = mapOf(1 to "Libera")

    private val settle = DccOfferQueue.SETTLE_MS

    /** Put [id]'s prompt on screen at [at] and answer it once it has settled. */
    private fun DccOfferQueue.showAndAnswer(id: Int, at: Long = 0): Boolean {
        shown(id, at)
        return answer(id, at + settle)
    }

    @Test
    fun `asks about the oldest offer, then the next once it's answered`() {
        val queue = DccOfferQueue()
        assertEquals(1, queue.update(listOf(offer(1), offer(2, "carol")), names)?.offer?.id)
        assertTrue(queue.showAndAnswer(1))
        assertEquals(2, queue.current?.offer?.id)
        assertTrue(queue.showAndAnswer(2, at = 10_000))
        assertNull(queue.current)
    }

    @Test
    fun `an offer is asked about once — a snapshot re-listing it doesn't ask again`() {
        val queue = DccOfferQueue()
        queue.update(listOf(offer(1)), names)
        queue.showAndAnswer(1) // Not Now: the offer stands.
        assertNull(queue.update(listOf(offer(1)), names))
    }

    @Test
    fun `a peer offering again is a fresh offer, and asked about`() {
        val queue = DccOfferQueue()
        queue.update(listOf(offer(1)), names)
        queue.showAndAnswer(1)
        assertEquals(5, queue.update(listOf(offer(5)), names)?.offer?.id)
    }

    @Test
    fun `a quick second tap can't answer the offer that just took the first one's place`() {
        val queue = DccOfferQueue()
        queue.update(listOf(offer(1), offer(2, "carol")), names)
        assertTrue(queue.showAndAnswer(1, at = 0))
        // The second offer's dialog arrives; the second tap of a double tap lands on it at once.
        queue.shown(2, settle)
        assertFalse(queue.answer(2, settle + 50))
        assertEquals(2, queue.current?.offer?.id)
        // Read, then answered deliberately.
        assertTrue(queue.answer(2, settle * 2))
        assertNull(queue.current)
    }

    @Test
    fun `an answer counts only for the prompt on screen, once it has settled`() {
        val queue = DccOfferQueue()
        queue.update(listOf(offer(1), offer(2, "carol")), names)
        // Never shown: nothing has been read.
        assertFalse(queue.answer(1, 10_000))
        queue.shown(1, 0)
        assertFalse(queue.answer(1, settle - 1))
        // Not the offer being asked about.
        assertFalse(queue.answer(2, 10_000))
        // Shown again (a rotation rebuilds the dialog) — the settle still runs from the first showing.
        queue.shown(1, settle - 1)
        assertTrue(queue.answer(1, settle))
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
