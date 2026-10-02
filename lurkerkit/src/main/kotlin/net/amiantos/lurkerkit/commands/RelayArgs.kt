// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

import net.amiantos.lurkerkit.support.isSwiftWhitespace
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * Argument parsing for `/relay` (lurker#277) — mark, unmark, and list relay/bridge bots on the
 * active network. A marked bot's messages get re-attributed to the speaker embedded in its
 * envelope (`[Discord] <alice> hi` → alice); see `RelayEnvelope`.
 *
 * Ported from the web's `vue_client/src/lib/commands/relay.ts`, and split out of `CommandParser`
 * for the same reason `IgnoreArgs` is: the interesting part is the tokenizing, and it's testable
 * on its own.
 *
 * ⚠ The custom-pattern argument is the **raw remainder of the line** — a template like
 * `<{nick}> {message}`, whose spaces are significant. So tokens are peeled by hand rather than
 * run through `IgnoreArgs.tokenize`, which would split and de-quote the template into something
 * that no longer matches anything.
 */
object RelayArgs {

    /** What a `/relay` line turned out to be. */
    sealed interface Parsed {
        data object List : Parsed
        data class Add(val nick: String, val pattern: String) : Parsed
        data class Remove(val nick: String) : Parsed

        /** Unusable input, with the line to print. Never reaches the wire. */
        data class Failure(val message: String) : Parsed
    }

    private val addVerbs: Set<String> = setOf("add", "mark", "set")
    private val removeVerbs: Set<String> = setOf("remove", "rm", "del", "delete", "unmark")

    internal const val usage = "usage: /relay [list] · /relay add <nick> [pattern] · /relay remove <nick>"

    fun parse(argLine: String): Parsed {
        // Newlines too, not just spaces and tabs: the composer is multi-line and Return
        // inserts a newline, so `/relay\n` arrives here with one still attached — and an argLine
        // that is only a newline would otherwise miss the listing and be read as a subcommand.
        val trimmed = argLine.trimmingWhitespacesAndNewlines()
        if (trimmed.isEmpty()) return Parsed.List

        val (sub, rest) = peel(trimmed)
        val verb = sub.lowercase()

        if (verb == "list" || verb == "ls") return Parsed.List

        if (addVerbs.contains(verb)) {
            val (nick, pattern) = peel(rest)
            if (nick.isEmpty()) return Parsed.Failure(message = "usage: /relay add <nick> [pattern]")
            return Parsed.Add(
                nick = nick,
                pattern = unquote(pattern.trimmingWhitespacesAndNewlines()),
            )
        }

        if (removeVerbs.contains(verb)) {
            val (nick, _) = peel(rest)
            if (nick.isEmpty()) return Parsed.Failure(message = "usage: /relay remove <nick>")
            return Parsed.Remove(nick = nick)
        }

        return Parsed.Failure(message = "unknown subcommand \"$sub\". $usage")
    }

    /**
     * Split off the first whitespace-delimited token, returning it and the remainder. The
     * remainder keeps its interior spacing — only the gap after the token is consumed — so a
     * custom template survives intact.
     */
    private fun peel(s: String): Pair<String, String> {
        val start = s.dropWhile { it.isSwiftWhitespace() }
        val token = start.takeWhile { !it.isSwiftWhitespace() }
        val rest = start.drop(token.length).dropWhile { it.isSwiftWhitespace() }
        return Pair(token, rest)
    }

    /**
     * Drop one matching pair of surrounding quotes, if present — a convenience so
     * `/relay add bot "[{s}] <{n}> {m}"` works even though quoting isn't required here.
     *
     * ⚠ "Starts and ends with the same quote" is not the same test as "is one quoted run", and
     * taking the first for the second peels a pair that was never a pair: `"{nick}" said
     * "{message}"` would become `{nick}" said "{message}`, which still compiles — both required
     * placeholders survive — so it would be stored and marked with a cheerful receipt while
     * matching something the user never wrote. Requiring the interior to be quote-free is what
     * makes the convenience refuse to guess: the template is then left exactly as typed, quotes
     * and all, which is at least visible in `/relay list`.
     */
    private fun unquote(s: String): String {
        if (s.length < 2) return s
        val quote = s.first()
        if (!(quote == '"' || quote == '\'') || s.last() != quote || s.drop(1).dropLast(1).contains(quote)) {
            return s
        }
        return s.drop(1).dropLast(1)
    }
}
