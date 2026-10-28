// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * Which links a tap may hand to the system to open.
 *
 * The renderer marks everything `URLMatcher` finds as tappable, and that includes `ftp://`
 * and `ftps://`, because the web client linkifies them (lurker's `shared/urlPattern.ts`) and
 * this app follows it. Matching is parity; OPENING is a different act. On iOS `UIApplication.open`
 * (here, an `ACTION_VIEW` intent) launches whichever app registered the scheme with an address a
 * channel peer chose — a stock device has no FTP handler and ignores it, but a third-party FTP
 * client or file manager would come up pointed wherever the peer said. So every door a link
 * leaves the app through checks here first, and a refused scheme does nothing: the tap on an
 * `ftp://` link is a tap on text.
 */
object ExternalLink {
    /** The web and mail. Nothing the app renders as a link legitimately needs another scheme. */
    val openableSchemes: Set<String> = setOf("http", "https", "mailto")

    /**
     * Port note: a `String` where LurkerKit takes a `URL` (PORTING.md, Types), so the scheme is
     * read here as RFC 3986 has it — a letter, then letters, digits, `+`, `-`, `.`, up to the
     * first `:` — which is what `URL.scheme` answers; a string with no such scheme is no link.
     */
    fun isOpenable(url: String): Boolean {
        val scheme = scheme(url) ?: return false
        return openableSchemes.contains(scheme.lowercase())
    }

    private fun scheme(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val scheme = url.substring(0, colon)
        if (!scheme.first().isAsciiLetter()) return null
        if (!scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
        return scheme
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
