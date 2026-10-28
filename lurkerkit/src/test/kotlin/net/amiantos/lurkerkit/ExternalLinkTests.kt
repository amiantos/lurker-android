// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.ExternalLink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The allowlist every system open in the app sits behind.
 *
 * Port note: a Swift Testing suite in LurkerKit ("External link schemes"), where each case carries
 * a display name. The method names are kept and the display name is the comment above each.
 */
class ExternalLinkTests {

    private fun opens(string: String): Boolean = ExternalLink.isOpenable(string)

    /** the web and mail open */
    @Test
    fun webAndMailOpen() {
        assertTrue(opens("http://example.org/x"))
        assertTrue(opens("https://example.org/x"))
        assertTrue(opens("mailto:someone@example.org"))
    }

    /** scheme case is not a loophole */
    @Test
    fun schemeCaseIsFolded() {
        assertTrue(opens("HTTPS://example.org/x"))
        assertTrue(opens("MailTo:someone@example.org"))
    }

    /**
     * every other scheme is refused
     *
     * ⚠ `ftp` is the one the matcher itself produces; the rest are what a peer could smuggle
     * into a link the app did not build.
     */
    @Test
    fun otherSchemesAreRefused() {
        for (link in listOf(
            "ftp://files.example.org/x", "ftps://files.example.org/x", "tel:+15555550100",
            "sms:+15555550100", "file:///etc/passwd", "javascript:alert(1)",
            "chat.lurker:/oauth", "x-custom://whatever",
        )) {
            assertFalse(opens(link), "$link must not leave the app")
        }
    }

    /** no scheme is no link */
    @Test
    fun schemelessIsRefused() {
        assertFalse(opens("example.org/x"))
    }

    // Port-only:

    /** What `URL(string:)` would have refused before the scheme was ever read. */
    @Test
    fun aStringThatIsNotAURLIsRefused() {
        assertFalse(opens(""))
        assertFalse(opens(":"))
        assertFalse(opens("1http://x"))
        assertFalse(opens("ht tp://x"))
    }
}
