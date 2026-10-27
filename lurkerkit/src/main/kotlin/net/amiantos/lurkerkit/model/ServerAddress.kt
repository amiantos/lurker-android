// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.isInWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The transport policy for a typed-in server address (lurker-ios#29).
 *
 * HTTPS everywhere, except the local network: `localhost` and other single-label names, `.local`
 * names, and the private, link-local and loopback address ranges. ⚠⚠ ONE definition of "local",
 * `LocalNetworking.permitsCleartext` — the same one that gates clip playback and, on this
 * platform, every request the HTTP client sends (`CleartextPolicy`). This used to carry its own
 * copy of Apple's `NSAllowsLocalNetworking` host classes, which admit ANY IP literal, public ones
 * included; on iOS the OS backstopped that (ATS refuses cleartext to a public address whatever the
 * app asks), and on Android nothing did, so `http://203.0.113.5` sent the bearer token across the
 * internet in the clear. The two definitions had drifted apart exactly as the comment here said
 * they must not, and the fix is for there to be only one.
 *
 * Port note: Android has no such class of host. Its network security config permits cleartext
 * per named domain or for everything, so an app that lets a self-hoster reach a LAN box over
 * plain http has to permit cleartext wholesale — which makes this gate, together with
 * `CleartextPolicy` on the client, the policy itself on this platform, not a mirror of one the
 * OS enforces. The definition is kept identical to iOS's so the same address is accepted or
 * refused on both, with the same copy.
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
            components.scheme == "http" && isLocalHost(canonicalHost(normalized) ?: host) -> loadable(normalized)
            components.scheme == "http" ->
                "That server needs HTTPS — plain http:// only works for local " +
                    "addresses (a private IP, a .local name, or a single-word host like localhost)."
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
     * The local network, by `LocalNetworking`'s one definition: single-label names, `.local`
     * names, and the private, link-local and loopback ranges — never a public address. The
     * brackets of an IPv6 literal are already stripped, as `LocalNetworking` expects.
     */
    private fun isLocalHost(host: String): Boolean = LocalNetworking.permitsCleartext(host)

    /**
     * Port-only: the host as OkHttp will spell it, which is what `CleartextPolicy` and the
     * playback gate judge — `[0:0:0:0:0:0:0:1]` is `::1` to it. Judged here in the same spelling,
     * or an address the interceptor would let through is refused at the door. Null for an address
     * OkHttp can't read, which `loadable` refuses anyway.
     */
    private fun canonicalHost(normalized: String): String? = normalized.toHttpUrlOrNull()?.host

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
                if (authority.any { it.isInWhitespacesAndNewlines() || it.isISOControl() }) return null
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
