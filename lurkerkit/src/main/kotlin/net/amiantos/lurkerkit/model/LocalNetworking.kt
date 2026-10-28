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
 * ⚠⚠ The ONE definition of "local" in the app. `ServerAddress` judges a typed-in server by it
 * too, so the sign-in gate and the media gate cannot drift: they once did (sign-in admitted any
 * IP literal, on a pre-iOS 17 reading of the exemption), and the drift was a public address
 * that passed the sign-in copy and then died inside the first request.
 *
 * Port note: this is the rule only. What makes it true on iOS is the plist key. On Android the
 * network security config permits cleartext wholesale (it cannot say "private addresses only"),
 * so what makes it true is the kit itself: `ServerAddress` asks this at sign-in and restore, and
 * `CleartextPolicy` asks it of every request the kit's HTTP clients send.
 */
internal object LocalNetworking {

    /** Whether `http` to this host is permitted rather than merely attempted. */
    fun permitsCleartext(host: String): Boolean {
        @Suppress("NAME_SHADOWING")
        var host = host.lowercase()
        // ⚠ An IPv6 literal arrives both ways: on iOS `URL.host` hands it over bare, and since
        // iOS 17 `URLComponents.host` keeps the brackets (`[fe80::1]`); here `HttpUrl.host` is
        // bare and `ServerAddress` parses by hand. One definition has to read both, or the
        // sign-in gate and the media gate disagree about the same address.
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        // An IPv6 literal.
        if (host.contains(":")) return isLocalIPv6(host)
        // ⚠⚠ A host made only of numbers is an address in SOME notation, and the resolver's
        // notation is `inet_aton`'s, not this file's: `010.0.0.1` is 8.0.0.1 (octal), `134744072`
        // and `0x8080808` are 8.8.8.8, `127.1` is 127.0.0.1. Read here as a decimal quad or as a
        // "single-label name", every one of those was admitted — and the socket then dialled a
        // public host. So only a strict dotted-decimal quad, no leading zeros, is an address at
        // all, and every other numeric spelling is refused whatever it might mean. (Both ports
        // hold the same rule, so one address is judged alike on both.)
        val labels = host.split(".")
        if (labels.all(::isNumericLabel)) {
            if (labels.size != 4 || labels.any { it.length > 1 && it[0] == '0' }) return false
            // ⚠ Every octet has to parse, or `1.2.3.999` would be read on its first components.
            val octets = labels.mapNotNull { uint8(it) }
            return octets.size == 4 && isLocalIPv4(octets)
        }
        if (host.endsWith(".local")) return true
        // A single-label name — `box`, not `box.example.com`. A numeric host has already been
        // judged above, so `134744072` can't fall in here as a "name".
        return !host.contains(".")
    }

    /**
     * A label `inet_aton` reads as a number: decimal or octal digits, or `0x` hex.
     *
     * Port note: Swift's `Character.isHexDigit` takes the fullwidth compatibility forms as well
     * as ASCII, so they are hex digits here too.
     */
    private fun isNumericLabel(label: String): Boolean {
        if (label.isEmpty()) return false
        if (label.startsWith("0x")) {
            val digits = label.substring(2)
            return digits.isNotEmpty() && digits.all(::isHexDigit)
        }
        return label.all { it in '0'..'9' }
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' ||
            c in '０'..'９' || c in 'Ａ'..'Ｆ' || c in 'ａ'..'ｆ'

    /**
     * The private and machine-local IPv4 ranges: 10/8, 172.16/12, 192.168/16, link-local
     * 169.254/16, and loopback 127/8. Four parsed octets, so the mapped-IPv6 branch asks the
     * same question of the same table without spelling the address out and reading it back.
     */
    private fun isLocalIPv4(octets: List<Int>): Boolean {
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 10 || first == 127 || (first == 192 && second == 168) || (first == 169 && second == 254) -> true
            first == 172 && second in 16..31 -> true
            else -> false
        }
    }

    /**
     * Loopback, unique-local (fc00::/7) and link-local (fe80::/10) — judged on the PARSED
     * address, not its spelling. `::1`, `0:0:0:0:0:0:0:1` and `::0001` are one address, and a
     * rule that read the text admitted only the first; now that a typed-in server is judged
     * here too, a spelling the old sign-in gate accepted must not become a rejection. An
     * IPv4-mapped address (`::ffff:192.168.1.5`) is judged as the IPv4 address it carries.
     */
    private fun isLocalIPv6(host: String): Boolean {
        // A zone index (`fe80::1%en0`) belongs to the interface, not to the prefix test.
        //
        // Port note: Swift's `split(separator: "%", maxSplits: 1)[0]`, which drops empty pieces —
        // so it is the first non-empty run before a `%`, not simply the text before the first one.
        val literal = host.trimStart('%').substringBefore('%')
        val bytes = inet6Bytes(literal) ?: return false
        if ((0 until 15).all { bytes[it] == 0 } && bytes[15] == 1) return true
        if ((bytes[0] and 0xFE) == 0xFC) return true
        if (bytes[0] == 0xFE && (bytes[1] and 0xC0) == 0x80) return true
        if ((0 until 10).all { bytes[it] == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF) {
            return isLocalIPv4(bytes.subList(12, 16))
        }
        return false
    }

    /**
     * The sixteen bytes of an IPv6 literal, or null where it isn't one.
     *
     * Port note: Swift's `inet_pton(AF_INET6, …)`, spelled out because nothing on this side reads
     * a literal and only a literal: `InetAddress.getByName` falls through to the resolver for
     * anything it can't read as an address, and OkHttp's decoder is internal to it. The grammar is
     * BSD's: up to eight groups of one to four hex digits, one `::` standing for the missing
     * groups, and optionally a dotted-decimal IPv4 address (no leading zeros) as the last two.
     */
    private fun inet6Bytes(literal: String): List<Int>? {
        val halves = literal.split("::")
        if (halves.size > 2) return null
        val head = parseGroups(halves[0], ipv4Allowed = halves.size == 1) ?: return null
        val tail = if (halves.size == 2) parseGroups(halves[1], ipv4Allowed = true) ?: return null else emptyList()
        val missing = 16 - head.size - tail.size
        if (halves.size == 1) {
            if (missing != 0) return null
        } else if (missing <= 0) {
            return null
        }
        return head + List(missing) { 0 } + tail
    }

    /** One side of a `::`, as bytes: empty for an empty side, null where a group is malformed. */
    private fun parseGroups(side: String, ipv4Allowed: Boolean): List<Int>? {
        if (side.isEmpty()) return emptyList()
        val groups = side.split(":")
        val bytes = mutableListOf<Int>()
        for ((index, group) in groups.withIndex()) {
            if (group.isEmpty()) return null
            if (group.contains('.')) {
                if (!ipv4Allowed || index != groups.lastIndex) return null
                val quad = group.split(".")
                if (quad.size != 4) return null
                for (label in quad) {
                    if (label.isEmpty() || label.any { it !in '0'..'9' }) return null
                    if (label.length > 1 && label[0] == '0') return null
                    val octet = uint8(label) ?: return null
                    bytes.add(octet)
                }
                continue
            }
            if (group.length > 4 || !group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val value = group.toInt(16)
            bytes.add(value shr 8)
            bytes.add(value and 0xFF)
        }
        return bytes
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
