// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The transport policy for a typed-in server address (lurker-ios#29).
 *
 * HTTPS everywhere, except the host classes Apple defines as local: unqualified single-label
 * names (`localhost`, `xerxes`), `.local` names, and IP-address literals. On iOS this mirrors
 * the `NSAllowsLocalNetworking` exception to App Transport Security rather than inventing its
 * own definition (e.g. "private ranges only"), so that the sign-in check and what the OS will
 * actually permit can't drift apart: everything allowed here is loadable, and everything
 * blocked here fails with our copy instead of an error from deep inside the HTTP stack.
 *
 * Port note: Android has no such class of host. Its network security config permits cleartext
 * per named domain or for everything, so an app that lets a self-hoster reach a LAN box over
 * plain http has to permit cleartext wholesale — which makes this function the policy itself
 * on this platform, not a mirror of one the OS enforces. The definition is kept identical to
 * iOS's so the same address is accepted or refused on both, with the same copy.
 *
 * Self-signed HTTPS is deliberately not handled — supporting it means a per-server trust
 * prompt, which is a feature, not ship-readiness. A self-hoster on a LAN uses plain http
 * (allowed here); one on a real domain has Let's Encrypt via the documented Caddy setup.
 */
object ServerAddress {
    /** lurker.chat, which the sign-in screen offers until another server is used. */
    const val lurkerChat = "https://app.lurker.chat"

    /**
     * What the user typed, made into a base URL: trimmed, trailing slashes stripped,
     * and a missing scheme defaulted to `https://` — a bare `chat.example.org` should
     * mean the secure thing, not be a parse error. The scheme sniff is `://` rather
     * than a URL parser's idea of the scheme, because `localhost:8010` parses as scheme
     * "localhost" — the classic host:port trap.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trimmingWhitespacesAndNewlines().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    /**
     * Why a normalized address fails the transport policy, or null if it passes.
     * The strings are sign-in screen copy — this is the "error is a real message,
     * not a failed connect" half of the policy.
     */
    fun rejection(normalized: String): String? {
        if (normalized.isEmpty()) return "Enter a server URL."
        val components = Components.parse(normalized)
        val host = components?.host
        if (components == null || host.isNullOrEmpty()) return "That server URL doesn't look right."
        return when {
            components.scheme == "https" -> loadable(normalized)
            components.scheme == "http" && isLocalHost(host) -> loadable(normalized)
            components.scheme == "http" ->
                "That server needs HTTPS — plain http:// only works for local " +
                    "addresses (an IP, a .local name, or a single-word host like localhost)."
            else -> "Server URLs start with https:// (or http:// for a local server)."
        }
    }

    /**
     * Port note: the last word on an address the policy passes. On iOS the gate and the HTTP
     * stack read a URL with the same parser, so what passes is loadable by construction. Here
     * the gate parses by hand and OkHttp loads, and OkHttp is the stricter: it refuses a port
     * past 65535 or of 0, an empty label (`a..b`, `.local`), and a zone id in an IPv6 literal.
     * Without this, such an address would clear sign-in and then throw from the first request.
     */
    private fun loadable(normalized: String): String? =
        if (normalized.toHttpUrlOrNull() != null) null else "That server URL doesn't look right."

    /**
     * Apple's three "local" host classes, verbatim from the `NSAllowsLocalNetworking`
     * documentation: unqualified domains, `.local` domains, and IP addresses.
     */
    private fun isLocalHost(host: String): Boolean {
        val lower = host.lowercase()
        if (lower.endsWith(".local")) return true
        if (!lower.contains(".")) return true // unqualified: localhost, xerxes, …
        return isIPLiteral(lower)
    }

    /**
     * IPv6 is any host with a colon — the brackets are already stripped, and a colon can't
     * appear in a hostname. IPv4 is exactly four in-range octets; near-misses like
     * `999.1.1.1` are hostnames to ATS too (and dead ones), so they fall through to the HTTPS
     * requirement rather than getting the carve-out.
     */
    private fun isIPLiteral(host: String): Boolean {
        if (host.contains(":")) return true
        val octets = host.split(".")
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.all { it in '0'..'9' } && isUInt8(octet)
        }
    }

    /** Whether a run of ASCII digits is a value 0...255, however many leading zeros it has. */
    private fun isUInt8(digits: String): Boolean {
        var value = 0
        for (digit in digits) {
            value = value * 10 + (digit - '0')
            if (value > 255) return false
        }
        return true
    }

    /**
     * The two parts of a URL this policy reads, as Foundation's `URLComponents` reports them.
     *
     * Port note: hand-rolled because nothing on this side parses the way `URLComponents` does.
     * `okhttp3.HttpUrl` refuses any scheme but http(s), and the policy has to be able to SEE an
     * `ftp://` to name what is wrong with it. `java.net.URI` reports no host at all for
     * `999.1.2.3` or `my_box`, which would turn "needs HTTPS" into "doesn't look right". The
     * answers `ServerAddressTests.testAgreesWithURLComponentsAtTheEdges` pins are the real
     * `URLComponents`' own, taken from LurkerKit compiled on a Mac.
     *
     * One known difference: `URLComponents` validates a non-ASCII host as an IDN and refuses
     * some (`١.٢.٣.٤`), which then "doesn't look right" on iOS. Here it is a hostname like any
     * other, and is told it needs HTTPS.
     */
    private data class Components(val scheme: String, val host: String?) {
        companion object {
            fun parse(url: String): Components? {
                val colon = url.indexOf(':')
                if (colon <= 0) return null
                val scheme = url.substring(0, colon)
                if (!isScheme(scheme)) return null
                val rest = url.substring(colon + 1)
                // `scheme:something` with no `//` has a scheme and a path, but no host.
                if (!rest.startsWith("//")) return Components(scheme, null)
                val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
                // A space in the host is not a URL; one further along (`/a b`) is merely a path
                // that wants encoding, and `URLComponents` takes it.
                if (authority.any { it.isWhitespace() || it.isISOControl() }) return null
                val hostPort = authority.substringAfterLast('@')
                val host: String
                val port: String
                if (hostPort.startsWith("[")) {
                    val close = hostPort.indexOf(']')
                    if (close < 0) return null
                    host = hostPort.substring(1, close)
                    val after = hostPort.substring(close + 1)
                    if (after.isNotEmpty() && !after.startsWith(":")) return null
                    port = after.removePrefix(":")
                } else {
                    host = hostPort.substringBefore(':')
                    port = if (hostPort.contains(':')) hostPort.substringAfter(':') else ""
                }
                if (port.any { it !in '0'..'9' }) return null
                return Components(scheme, host)
            }

            /** RFC 3986: a letter, then letters, digits, `+`, `-`, `.`. */
            private fun isScheme(scheme: String): Boolean =
                scheme.first().isAsciiLetter() &&
                    scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }

            private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
        }
    }
}
