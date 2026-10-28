// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import androidx.compose.ui.platform.UriHandler
import org.junit.Test
import org.junit.Assert.assertEquals

/** The app's one link door: the allowlist, before the platform is asked. */
class SafeUriHandlerTest {

    private class Recording : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @Test
    fun `the web and mail reach the platform`() {
        val platform = Recording()
        val handler = SafeUriHandler(platform)
        handler.openUri("https://example.org/x")
        handler.openUri("http://example.org/x")
        handler.openUri("mailto:someone@example.org")
        handler.openUri("HTTPS://example.org/upper")
        assertEquals(
            listOf("https://example.org/x", "http://example.org/x", "mailto:someone@example.org", "HTTPS://example.org/upper"),
            platform.opened,
        )
    }

    @Test
    fun `every other scheme is a tap on text`() {
        val platform = Recording()
        val handler = SafeUriHandler(platform)
        for (uri in listOf("ftp://example.org/x", "tel:+15551234567", "sms:5551234567", "intent://scan/#Intent;end", "javascript:alert(1)", "file:///etc/passwd", "example.org/no-scheme", "")) {
            handler.openUri(uri)
        }
        assertEquals(emptyList<String>(), platform.opened)
    }
}
