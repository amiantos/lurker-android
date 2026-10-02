// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.splitOnSwiftWhitespace
import net.amiantos.lurkerkit.support.isSwiftWhitespace
import java.time.Instant

/**
 * One WHOIS reply (lurker-ios#12) — the `whois` payload of a `whois_result` frame.
 *
 * The server does not parse the numerics itself: irc-framework aggregates RPL_WHOIS*
 * (311/312/317/319/330/…) into a single `whois` event at RPL_ENDOFWHOIS, and
 * `ircConnection.ts` fans that object out verbatim. So the field names here are
 * irc-framework's spellings, not Lurker's, and this type is the only place that
 * should know them.
 *
 * The server buffer separately gets the *raw* numerics through the default-show `raw`
 * handler (lurker#281, lurker#342). That is a different rendering of the same reply, not a
 * fallback for this one — both happen, always.
 */
data class WhoisResult(
    /**
     * The nick as the server spelled it. Present on every reply including a miss, because
     * RPL_ENDOFWHOIS carries it (`user.js:152`) — which is what makes a miss addressable.
     */
    val nick: String,
    val ident: String? = null,
    val hostname: String? = null,
    val realName: String? = null,
    /** Where they are actually connected from, when the server tells opers/self (RPL_WHOISACTUALLY). */
    val actualHostname: String? = null,
    val actualIP: String? = null,
    val server: String? = null,
    val serverInfo: String? = null,
    /**
     * Services account (RPL_WHOISACCOUNT). Distinct from `registeredNick`, which is only a
     * claim that the nick is registered.
     */
    val account: String? = null,
    /**
     * ⚠ The RPL_WHOISCHANNELS payload is **one space-separated string** of prefix+name
     * tokens — `"@#foo +#bar #baz"` — accumulated across repeats (`user.js:222`). Not an
     * array. Read it through `channels`, which does the splitting.
     */
    val channelsLine: String? = null,
    /** User modes, when the server discloses them (RPL_WHOISMODES). A raw mode string. */
    val modes: String? = null,
    /**
     * The trailing text of the numeric that asserts each of these, so a set flag is a
     * non-empty string rather than a bool. Kept as text because the wording is the server's
     * ("is an IRC Operator", "is a Network Service") and is worth showing.
     */
    val isOperator: String? = null,
    val helpop: String? = null,
    val bot: String? = null,
    val registeredNick: String? = null,
    /** RPL_WHOISSECURE. The one genuinely boolean flag irc-framework sets (`user.js:269`). */
    val isSecure: Boolean = false,
    val certfp: String? = null,
    /**
     * The away reason, present only when the whois ran while they were away. `peerPresence`
     * is the fresher source for the same fact — prefer it and fall back here.
     */
    val away: String? = null,
    /**
     * Idle seconds (RPL_WHOISIDLE).
     *
     * Port note: a `Long`. It is a number read off the wire — out of a string, usually — into
     * Swift's 64-bit `Int`, and a count of seconds a server made up need not fit in 32 bits.
     */
    val idleSeconds: Long? = null,
    /** Signon time. */
    val signedOn: Instant? = null,
    /** `"not_found"` when the nick isn't on the network — read through `isNotFound`. */
    val error: String? = null,
) {
    /**
     * The nick isn't on the network.
     *
     * ⚠⚠ This does **not** come from ERR_NOSUCHNICK. That numeric reaches only
     * irc-framework's generic error handler and produces no `whois` event at all; the miss
     * is *synthesized* at RPL_ENDOFWHOIS when nothing filled the cache (`user.js:151`).
     *
     * The corollary is worth knowing before trusting silence: a server that answers 401 and
     * then sends no 318 produces no signal whatsoever, and a screen waiting on one waits
     * forever. There is no timeout here (the web has none either).
     */
    val isNotFound: Boolean get() = error == "not_found"

    /**
     * `nick!ident@host`, the form an ignore rule's mask is matched against.
     *
     * A missing half becomes `*` rather than making the whole thing null — unlike
     * `Member.userhost`, which is fed straight to the matcher and where a half-mask would
     * silently fail a rule. This one is for *display*, where "we know the host but not the
     * ident" is worth showing.
     */
    val hostmask: String?
        get() {
            if (ident == null && hostname == null) return null
            return "$nick!${ident ?: "*"}@${hostname ?: "*"}"
        }

    /**
     * One channel from the RPL_WHOISCHANNELS line: the sigils they hold there, and the
     * name without them.
     */
    data class ChannelEntry(
        /** The membership sigils (`~ & @ % +`), as sent — possibly several, possibly none. */
        val prefix: String,
        val name: String,
    )

    /**
     * The channels they're in, split out of `channelsLine`.
     *
     * The membership sigils are peeled off so `name` is joinable as-is — see
     * `MemberPrefix.splitChannelToken` for why that peel can't be greedy.
     *
     * Port note: the line is split at whitespace UTF-16 units, where LurkerKit splits at
     * whitespace `Character`s. They differ only when a combining mark or a zero-width joiner
     * follows a space: Swift takes the mark as part of the separator, and here it is left on the
     * front of the next token.
     */
    val channels: List<ChannelEntry>
        get() {
            val channelsLine = channelsLine ?: return emptyList()
            return channelsLine.splitOnSwiftWhitespace().mapNotNull { token ->
                // Null for a token that is only sigils — it names no channel, and a conforming
                // server can't send one, but it would otherwise be a row that is tappable and empty.
                val split = MemberPrefix.splitChannelToken(token) ?: return@mapNotNull null
                ChannelEntry(prefix = split.prefix, name = split.name)
            }
        }
}
