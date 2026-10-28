// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.LocalNetworking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which hosts count as the local network — the rule `LinkPreview.isViewable` admits cleartext
 * by. On iOS it is exactly what `NSAllowsLocalNetworking` exempts.
 */
class LocalNetworkingTests {

    private fun permits(host: String): Boolean = LocalNetworking.permitsCleartext(host)

    // MARK: - Permitted, because ATS permits them

    @Test
    fun testBonjourNames() {
        assertTrue(permits("box.local"))
        assertTrue(permits("BOX.LOCAL"))
    }

    /**
     * ⚠ Exempt for having no dot in it, not for being called anything in particular — the
     * exemption is about single-label names, so `notlocal` qualifies exactly as `box` does.
     */
    @Test
    fun testSingleLabelNames() {
        assertTrue(permits("box"))
        assertTrue(permits("localhost"))
        assertTrue(permits("notlocal"))
    }

    @Test
    fun testPrivateAndMachineLocalAddresses() {
        for (host in listOf(
            "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.9.9", "192.168.1.9",
            "169.254.3.4", "127.0.0.1", "::1", "fd00::1", "fe80::1%en0",
        )) {
            assertTrue(permits(host), "$host is on the local network")
        }
    }

    /**
     * On iOS `URLComponents.host` keeps an IPv6 literal's brackets (iOS 17+); `URL.host` strips
     * them. The rule reads both, so the sign-in gate and the media gate judge one address alike.
     */
    @Test
    fun testBracketedIPv6LiteralsReadTheSame() {
        assertTrue(permits("[fe80::1]"))
        assertTrue(permits("[::1]"))
        assertFalse(permits("[2001:db8::1]"))
        assertFalse(permits("[]"))
    }

    /**
     * The address, not its spelling: every way of writing loopback is loopback, and a
     * mapped IPv4 address is judged as the IPv4 address.
     */
    @Test
    fun testIPv6SpellingsAreOneAddress() {
        for (host in listOf(
            "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001", "::0001", "FE80::1",
            "fd12:3456:789a::1", "fc00::", "::ffff:192.168.1.5", "::ffff:10.0.0.1",
        )) {
            assertTrue(permits(host), "$host is local")
        }
        for (host in listOf("::", "::2", "fe00::1", "fec0::1", "::ffff:8.8.8.8", "2001:db8::1", "fe80:::1", "::1::")) {
            assertFalse(permits(host), "$host is not local")
        }
    }

    // MARK: - Refused, because ATS refuses them

    @Test
    fun testPublicNames() {
        assertFalse(permits("cdn.example.com"))
        assertFalse(permits("example.com"))
        // ⚠ The trap in `.local`: a name that merely CONTAINS it is a public name.
        assertFalse(permits("box.local.example.com"))
        assertFalse(permits("local.example.com"))
    }

    @Test
    fun testPublicAddresses() {
        for (host in listOf(
            "8.8.8.8", "172.32.0.1", "172.15.255.254", "192.169.1.1", "1.1.1.1",
            "2606:4700::1111",
        )) {
            assertFalse(permits(host), "$host is a public address")
        }
    }

    /**
     * ⚠ A host made only of numbers is judged as the address the resolver would DIAL, never as
     * a decimal quad or a "name": `inet_aton` reads a leading zero as octal, a lone number as a
     * 32-bit address, `0x` as hex, and `127.1` as `127.0.0.1`. Only the strict quad is an address
     * here; every other spelling is refused, whatever it might mean.
     */
    @Test
    fun testNumericSpellingsAreNotLoopholes() {
        assertTrue(permits("10.0.0.1"))
        for (host in listOf(
            "010.0.0.1", "0010.0.0.1", "192.168.001.1", "134744072", "0x8080808", "0x7f.0.0.1",
            "127.1", "1.2.3.4.5", "1..2.3", "1.2.3.999",
        )) {
            assertFalse(permits(host), "$host is not read as a local address")
        }
    }

    /**
     * ⚠ A dotted string that isn't an address must not fall through to the single-label rule
     * and be admitted as a "name".
     */
    @Test
    fun testMalformedAddressesAreNotNames() {
        assertFalse(permits("1.2.3.four"))
        assertFalse(permits("10.0.0"))
        assertFalse(permits("10.0.0.999"))
        assertFalse(permits(""))
    }

    // Port-only:

    /**
     * More spellings for `testNumericSpellingsAreNotLoopholes`, from the Android audit
     * (lurker-android#97) that held this rule before LurkerKit did: on this platform nothing
     * judges the address the socket actually dials, so the refusal here is the only one.
     */
    @Test
    fun testOnlyAStrictDottedDecimalQuadIsAnAddress() {
        assertTrue(permits("10.0.0.1"))
        assertTrue(permits("127.0.0.1"))
        for (host in listOf(
            "010.0.0.1", "0177.0.0.1", "10.000.0.1", "134744072", "0x8080808", "0x7f.0.0.1", "127.1",
            "10.0.1", "2130706433", "0", "0x0a000001", "08.0.0.1",
        )) {
            assertFalse(permits(host), "$host is a number the resolver reads its own way")
        }
        // A label with a letter in it that isn't hex is a name, as it always was.
        assertTrue(permits("box2"))
        assertTrue(permits("2box"))
    }
}
