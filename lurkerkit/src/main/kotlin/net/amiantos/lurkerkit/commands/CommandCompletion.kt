// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.isInWhitespacesAndNewlines

/**
 * The pure logic behind command autocomplete — the sibling of `NickCompletion`, feeding the
 * same floating pill strip. It answers one question the composer asks on every keystroke and
 * caret move: *is a command completion live under the caret, and if so, what kind?*
 *
 *  - Typing the verb (`/jo|`) → command chips, filtered by what's typed.
 *  - Typing an argument of a known command (`/join #li|`, `/msg ali|`) → channel or nick
 *    chips, decided by that command's argument grammar (`CommandSpec.argKind`).
 *  - A free-text argument slot (`/me hello…`) or an unknown command → nothing here; the
 *    composer falls through to `@`-mention detection, so `/me @al|` still completes a nick.
 *
 * Offsets are UTF-16 (a `TextRange`'s currency, and a Kotlin `String`'s), so the composer can
 * hand its selection straight in and splice the completion back by index.
 */
object CommandCompletion {

    /**
     * A live command completion under the caret. `range` is the whole token the pick
     * replaces (verb or argument), so completing `/jo|in` swallows the tail rather than
     * welding onto it — the same rule `NickCompletion` uses for `@al|ice`.
     */
    sealed interface Context {
        /** The verb is being typed. `query` is the text after the slash, up to the caret. */
        data class Command(val query: String, val range: TextRange) : Context

        /**
         * An argument of a known command is being typed. `kind` is `.channel` or `.nick` —
         * the only kinds that produce chips.
         */
        data class Argument(
            val verb: String,
            val index: Int,
            val kind: ArgKind,
            val query: String,
            val range: TextRange,
        ) : Context
    }

    /**
     * Classify the caret. Returns null when the line isn't a command, it's a `//` escape, the
     * verb is unknown, or the slot under the caret is free text / opaque (channel key, mode
     * string, your new nick) — every case where the composer should try `@`-mention instead.
     */
    fun context(text: String, caret: Int): Context? {
        // Port note: LurkerKit copies the string out as an array of UTF-16 units to index it; a
        // Kotlin `String` already is one.
        val chars = text
        if (caret < 0 || caret > chars.length) return null
        val slash = '/'

        // The command must open the line. Leading whitespace is skipped (the send path trims
        // it too), so " /join" still completes.
        var start = 0
        while (start < chars.length && isWhitespace(chars[start])) start += 1
        if (start >= chars.length || chars[start] != slash) return null
        // `//…` is an escaped literal, not a command.
        if (start + 1 < chars.length && chars[start + 1] == slash) return null
        // Caret sitting in the leading whitespace or on the slash has nothing to complete.
        if (caret <= start) return null

        // The verb token: from the slash to the first whitespace.
        var index = start + 1
        while (index < chars.length && !isWhitespace(chars[index])) index += 1
        val verbEnd = index

        // Caret still inside the verb token → command-name completion.
        if (caret <= verbEnd) {
            val query = string(chars, start + 1, caret)
            return Context.Command(query = query, range = TextRange.of(location = start, length = verbEnd - start))
        }

        // Past the verb: resolve it. An unknown verb goes raw on send, so there's nothing to
        // suggest for its arguments.
        val verb = string(chars, start + 1, verbEnd).lowercase()
        val spec = CommandRegistry.spec(verb) ?: return null

        // Walk the argument tokens to find which one the caret sits in (or the empty slot it's
        // poised to start).
        var argIndex = 0
        // The whole tokens before the one under the caret — what a command with several forms
        // (`/dcc`) reads to tell which form is being typed.
        val preceding = mutableListOf<String>()
        var scan = verbEnd
        while (scan < chars.length) {
            while (scan < chars.length && isWhitespace(chars[scan])) scan += 1
            val tokenStart = scan
            // Caret is in the whitespace gap before this token → an empty new argument here.
            if (caret < tokenStart) {
                return argument(
                    spec = spec, index = argIndex, preceding = preceding, query = "",
                    range = TextRange.of(location = caret, length = 0),
                )
            }
            while (scan < chars.length && !isWhitespace(chars[scan])) scan += 1
            val tokenEnd = scan
            if (caret >= tokenStart && caret <= tokenEnd) {
                val query = string(chars, tokenStart, caret)
                return argument(
                    spec = spec, index = argIndex, preceding = preceding, query = query,
                    range = TextRange.of(location = tokenStart, length = tokenEnd - tokenStart),
                )
            }
            preceding.add(string(chars, tokenStart, tokenEnd))
            argIndex += 1
        }
        // Caret is past the last token, in trailing whitespace → a fresh empty argument.
        return argument(
            spec = spec, index = argIndex, preceding = preceding, query = "",
            range = TextRange.of(location = caret, length = 0),
        )
    }

    /** Wrap an argument slot in a `Context`, but only when its kind is one we can suggest for. */
    private fun argument(
        spec: CommandSpec,
        index: Int,
        preceding: List<String>,
        query: String,
        range: TextRange,
    ): Context? {
        val kind = spec.argKind(preceding, typing = query)
        if (!(kind == ArgKind.Channel || kind == ArgKind.Nick)) return null
        return Context.Argument(verb = spec.name, index = index, kind = kind, query = query, range = range)
    }

    /**
     * Port note: LurkerKit asks `CharacterSet.whitespacesAndNewlines` about the unit read as a
     * scalar, and a surrogate — which is no scalar — is not whitespace. `isInWhitespacesAndNewlines`
     * says the same of one.
     */
    private fun isWhitespace(unit: Char): Boolean = unit.isInWhitespacesAndNewlines()

    /**
     * The units from `start` up to `end`, as a string.
     *
     * Port note: LurkerKit decodes the slice (`String(decoding:as: UTF16.self)`), which repairs
     * half a surrogate pair into U+FFFD — what a caret sitting inside an emoji leaves at the end
     * of the query. `substring` would hand the lone surrogate back, so the repair is done here.
     */
    private fun string(chars: String, start: Int, end: Int): String {
        val slice = chars.substring(start, end)
        if (slice.none { it.isSurrogate() }) return slice
        val repaired = StringBuilder(slice.length)
        var index = 0
        while (index < slice.length) {
            val unit = slice[index]
            if (unit.isHighSurrogate() && index + 1 < slice.length && slice[index + 1].isLowSurrogate()) {
                repaired.append(unit).append(slice[index + 1])
                index += 2
                continue
            }
            repaired.append(if (unit.isSurrogate()) '\uFFFD' else unit)
            index += 1
        }
        return repaired.toString()
    }
}
