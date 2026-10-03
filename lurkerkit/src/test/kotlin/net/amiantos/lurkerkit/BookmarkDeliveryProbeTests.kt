// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.test.TestScope
import net.amiantos.lurkerkit.client.LurkerClient
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The swipe's honesty depends on `setBookmark` reporting false when there is no socket.
 * Asserted directly, because everything above it is written on the assumption that a
 * dropped verb is distinguishable from a delivered one.
 */
class BookmarkDeliveryProbeTests {
    @Test
    fun testSetBookmarkReportsFailureWithNoSocket() {
        val client = LurkerClient(scope = TestScope(), onFrame = {})
        assertFalse(
            client.setBookmark(messageId = 1, saved = false),
            "no socket == nothing was sent; the Bookmarks swipe relies on this to keep the row",
        )
    }
}
