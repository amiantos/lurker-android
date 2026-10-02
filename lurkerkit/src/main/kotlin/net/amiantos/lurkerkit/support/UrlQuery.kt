// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import java.util.Locale

/**
 * A URL query, percent-encoded the way LurkerKit sends one: what `URLComponents` does to each
 * item, and then every `+` as `%2B`. Port-only, and the ONE encoder for every request the kit
 * builds — hand the result to `HttpUrl.Builder.encodedQuery`.
 *
 * ⚠⚠ The `+` step is load-bearing. `URLComponents` leaves a literal `+` alone — it is legal in
 * a query — but the server parses query strings with form-urlencoded semantics, where `+` MEANS
 * SPACE. So `C++` arrives as `C  ` and the request quietly asks a different question. It is safe
 * as a blanket replacement because a space is encoded as `%20` and never as `+`, so every `+`
 * left is one the user typed. `encodedQuery` passes a `+` through untouched, exactly as
 * `URLComponents` does.
 *
 * Not `HttpUrl.Builder.addQueryParameter`, which would do: it also escapes `~ ! @ $ ' ( ) , / :
 * ; ?`, so a `!chan` target goes out as `%21chan` — the same value to the server and a
 * different string from every other client's, where LurkerKit's tests hold the URL to the
 * literal. One rule here rather than a choice per request.
 *
 * The rule, enumerated from Foundation on a Mac: the UTF-8 bytes of a name or value, each
 * written as `%XX` unless it is an ASCII letter or digit or one of `- . _ ~ ! $ ' ( ) * + , / :
 * ; ? @`. (`HttpUrl` then re-spells `'` as `%27`; nothing else changes.)
 */
internal fun percentEncodedQuery(items: List<Pair<String, String>>): String =
    items.joinToString("&") { (name, value) -> percentEncoded(name) + "=" + percentEncoded(value) }
        .replace("+", "%2B")

private const val literalInQuery = "-._~!$'()*+,/:;?@"

private fun percentEncoded(text: String): String {
    val out = StringBuilder()
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val unit = byte.toInt() and 0xFF
        val character = unit.toChar()
        val literal = unit < 0x80 &&
            (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in literalInQuery)
        if (literal) out.append(character) else out.append(String.format(Locale.ROOT, "%%%02X", unit))
    }
    return out.toString()
}
