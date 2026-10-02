// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the transport policy (lurker-ios#29) to ATS's own definition of "local": on iOS, with
 * `NSAllowsLocalNetworking` as the app's only exception, everything this policy passes must be
 * loadable and everything it rejects must fail with our copy — the two lists drifting apart is
 * the bug these tests exist to catch. Here they hold the policy to the same answers iOS gives.
 */
class ServerAddressTests {

    // MARK: - Normalize

    @Test
    fun testNormalizeTrimsWhitespaceAndTrailingSlashes() {
        assertEquals("https://app.lurker.chat", ServerAddress.normalize("  https://app.lurker.chat//  "))
    }

    @Test
    fun testASchemelessAddressDefaultsToHTTPSNotAParseError() {
        assertEquals("https://chat.example.org", ServerAddress.normalize("chat.example.org"))
    }

    /**
     * The host:port trap: `localhost:8010` parses as scheme "localhost", so scheme
     * detection sniffs `://` instead. A bare host:port gets the secure default.
     */
    @Test
    fun testHostColonPortIsSchemelessNotAScheme() {
        assertEquals("https://localhost:8010", ServerAddress.normalize("localhost:8010"))
    }

    @Test
    fun testAnExplicitSchemeIsKept() {
        assertEquals("http://localhost:8010", ServerAddress.normalize("http://localhost:8010"))
    }

    // MARK: - Policy: what passes

    @Test
    fun testHTTPSPassesAnywhere() {
        assertNull(ServerAddress.rejection("https://app.lurker.chat"))
        assertNull(ServerAddress.rejection("https://chat.example.org:8443"))
    }

    @Test
    fun testPlainHTTPPassesForApplesThreeLocalHostClasses() {
        // Unqualified single-label names.
        assertNull(ServerAddress.rejection("http://localhost:8010"))
        assertNull(ServerAddress.rejection("http://xerxes:8010"))
        // .local names (case-insensitively — DNS names have no case).
        assertNull(ServerAddress.rejection("http://xerxes.local:8010"))
        assertNull(ServerAddress.rejection("http://Xerxes.LOCAL:8010"))
        // IP literals, v4 and v6 (the v6 brackets are stripped).
        assertNull(ServerAddress.rejection("http://192.168.1.5:8010"))
        assertNull(ServerAddress.rejection("http://[fe80::1]:8010"))
    }

    /**
     * The server the sign-in screen prefills must pass — a rejected default would
     * dead-end the first-run experience.
     */
    @Test
    fun testTheDefaultServerPassesThePolicy() {
        assertNull(ServerAddress.rejection(ServerAddress.normalize(ServerAddress.lurkerChat)))
    }

    // MARK: - Policy: what's rejected, and with what copy

    @Test
    fun testPlainHTTPToAQualifiedDomainIsRejectedWithTheHTTPSMessage() {
        val reason = ServerAddress.rejection("http://chat.example.org")
        assertNotNull(reason)
        assertTrue(reason.contains("HTTPS"), "the copy must say what to do, not just refuse")
    }

    /**
     * A near-miss like 999.1.2.3 isn't an IP to ATS either — it's a (dead) hostname.
     * Passing it would promise a load the OS then blocks.
     */
    @Test
    fun testAnOutOfRangeOctetIsAHostnameNotAnIPLiteral() {
        assertNotNull(ServerAddress.rejection("http://999.1.2.3:8010"))
        assertNotNull(ServerAddress.rejection("http://1.2.3.4.5:8010"))
    }

    @Test
    fun testEmptyAndUnparsableAddressesAreRejectedLegibly() {
        assertEquals("Enter a server URL.", ServerAddress.rejection(""))
        assertNotNull(ServerAddress.rejection("https://"))
    }

    @Test
    fun testANonHTTPSchemeIsRejected() {
        assertNotNull(ServerAddress.rejection("ftp://example.org"))
        assertNotNull(ServerAddress.rejection("ws://localhost:8010"))
    }

    // Port-only:

    /**
     * LurkerKit leaves the parsing to `URLComponents`; this port parses by hand, so the edges
     * are pinned to what `URLComponents` answered for the same strings (LurkerKit's own
     * `ServerAddress`, compiled and run on a Mac).
     */
    @Test
    fun testAgreesWithURLComponentsAtTheEdges() {
        val looksWrong = "That server URL doesn't look right."
        val wrongScheme = "Server URLs start with https:// (or http:// for a local server)."
        val passes = listOf(
            "http://my_box:8010",
            "http://user:pw@localhost:8010",
            "http://a.b@c.d@localhost",
            "http://localhost:",
            "http://[::1]",
            "http://001.002.003.004",
            "http://box.local/path?q=1#f",
            "http://localhost/a b",
            "http://localhost#frag",
            "http://localhost?x=1",
            "http://LOCALHOST",
            "http://éxample.local",
        )
        for (url in passes) assertNull(ServerAddress.rejection(url), url)

        val needsHTTPS = listOf(
            "http://my_box.example.com",
            "http://user@chat.example.org",
            "http://256.1.1.1",
            "http://1.2.3",
            "http://1.2.3.",
            // A trailing dot makes it a qualified name, not a `.local` one.
            "http://box.local.",
            "http://x.localx",
            "http://例え.jp",
        )
        for (url in needsHTTPS) assertTrue(ServerAddress.rejection(url)?.contains("HTTPS") == true, url)

        val unparsable = listOf(
            "localhost:8010",
            "https://exa mple.org",
            "http://localhost:80a",
            "http://localhost:-1",
            "http://:8010",
            "https:///path",
            "http://[::1",
            "http://[::1]x",
            "http://fe80::1:8010",
            "https:app.lurker.chat",
            "https:/app.lurker.chat",
            "1http://x",
            "://x",
            "javascript:alert(1)",
            "mailto:me@example.org",
        )
        for (url in unparsable) assertEquals(looksWrong, ServerAddress.rejection(url), url)

        // Where this port deliberately leaves iOS: `URLComponents` passes these, but OkHttp
        // cannot load them, and an address that clears sign-in must be one a request can use.
        val unloadable = listOf(
            "http://1.2.3.4:99999",
            "https://chat.example.org:0",
            "http://.local",
            "https://a..b",
            "http://[fe80::1%25en0]:8010",
        )
        for (url in unloadable) assertEquals(looksWrong, ServerAddress.rejection(url), url)

        // The scheme is compared as written — an upper-case one is not `https`.
        for (url in listOf("HTTPS://app.lurker.chat", "HTTP://localhost", "ht+tp://x")) {
            assertEquals(wrongScheme, ServerAddress.rejection(url), url)
        }
    }
}
