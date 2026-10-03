// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared page stack under the networks and buffer dialogs. */
class PagedFlowTest {

    private class Stack : PagedFlow<String>() {
        val poppedPages = mutableListOf<String>()

        override fun popped(page: String) {
            poppedPages += page
        }
    }

    @Test
    fun backPopsTheTopPageButNeverTheRoot() {
        val stack = Stack()
        stack.pages.addAll(listOf("root", "pushed"))
        assertTrue(stack.back())
        assertEquals(listOf("root"), stack.pages.toList())
        assertEquals(listOf("pushed"), stack.poppedPages)
        // Back at the root is the dialog's to answer (it dismisses), not the stack's.
        assertFalse(stack.back())
        assertEquals(listOf("root"), stack.pages.toList())
        stack.close()
    }

    @Test
    fun theStoreKeepsAFlowPerTokenUntilDiscarded() {
        val store = PagedFlowStore()
        val first = store.flow("a") { Stack() }
        assertTrue(first === store.flow("a") { Stack() })
        store.discard("a")
        assertFalse(first === store.flow("a") { Stack() })
    }
}
