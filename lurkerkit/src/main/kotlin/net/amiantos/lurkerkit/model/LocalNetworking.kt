// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * Whether a cleartext (`http`) load to a host is one this app can actually make.
 *
 * ⚠⚠ On iOS this mirrors `NSAllowsLocalNetworking` in `Info.plist`, and it exists so that a
 * refusal and a capability can't drift apart. App Transport Security blocks cleartext to a
 * public host no matter what the app asks for, so admitting one produces a failure deep inside
 * AVFoundation that the reader can't act on. It does NOT block the local network, which that
 * key exempts — so refusing those too would delete a case that works: a self-hosted instance on
 * a LAN serving plain http, whose own uploads come back as ordinary `http://box.local/…`
 * addresses.
 *
 * ⚠ The exemption's own definition, not a guess at it: Apple grants it to `.local` names,
 * unqualified single-label names, and the private IPv4 ranges. Loopback and IPv6's local ranges
 * are included on the same reasoning. Anything else — a name with a dot in it, a public
 * address — is a public cleartext load and is refused.
 *
 * Port note: this is the rule only. What makes it true on iOS is the plist key; on Android
 * the matching capability is the app's network security config, which lives in `:app` and
 * which nothing here checks.
 */
internal object LocalNetworking {

    /** Whether `http` to this host is permitted rather than merely attempted. */
    fun permitsCleartext(host: String): Boolean {
        @Suppress("NAME_SHADOWING")
        val host = host.lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        // An IPv6 literal, handed over without its brackets (as `HttpUrl.host` does).
        if (host.contains(":")) return isLocalIPv6(host)
        if (host.endsWith(".local")) return true
        if (isLocalIPv4(host)) return true
        // A single-label name — `box`, not `box.example.com`. Last, so a dotted quad has already
        // been judged as an address rather than falling in here as a "name".
        return !host.contains(".")
    }

    /**
     * The private and machine-local IPv4 ranges: 10/8, 172.16/12, 192.168/16, link-local
     * 169.254/16, and loopback 127/8.
     */
    private fun isLocalIPv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        // ⚠ Every octet has to parse, or `1.2.3.four` would be read as an address on its first
        // two components and admitted.
        val octets = parts.mapNotNull { uint8(it) }
        if (octets.size != 4) return false
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 10 || first == 127 || (first == 192 && second == 168) || (first == 169 && second == 254) -> true
            first == 172 && second in 16..31 -> true
            else -> false
        }
    }

    /** Loopback, unique-local (fc00::/7) and link-local (fe80::/10). */
    private fun isLocalIPv6(host: String): Boolean {
        // A zone index (`fe80::1%en0`) belongs to the address, not to the prefix test.
        //
        // Port note: Swift's `split(separator: "%", maxSplits: 1)[0]`, which drops empty pieces —
        // so it is the first non-empty run before a `%`, not simply the text before the first one.
        val address = host.trimStart('%').substringBefore('%')
        if (address == "::1") return true
        return address.startsWith("fc") || address.startsWith("fd") || address.startsWith("fe8") ||
            address.startsWith("fe9") || address.startsWith("fea") || address.startsWith("feb")
    }

    /**
     * Port note: Swift's `UInt8(String)`, spelled out because nothing on this side reads a
     * string the same way. An optional sign, then ASCII digits only, any number of leading
     * zeros, and a value that fits in 0...255 — which a leading `-` leaves only `-0` to meet.
     * `toUByteOrNull` refuses the sign; `toIntOrNull` takes other scripts' digits.
     */
    private fun uint8(text: String): Int? {
        var digits = text
        var negative = false
        if (text.startsWith("+") || text.startsWith("-")) {
            negative = text.startsWith("-")
            digits = text.substring(1)
        }
        if (digits.isEmpty()) return null
        var value = 0
        for (digit in digits) {
            if (digit !in '0'..'9') return null
            value = value * 10 + (digit - '0')
            if (value > 255) return null
        }
        if (negative && value != 0) return null
        return value
    }
}
