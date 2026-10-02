// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines

/**
 * Channel-name handling shared by the command parser (which prefixes a bare `/join` target)
 * and the composer's autocomplete (which folds a query to match channels regardless of the
 * sigil the user has typed yet). Kept in one place so the set of channel sigils isn't
 * written twice and drift into disagreement.
 */
object ChannelName {
    /**
     * The four IRC channel prefixes (RFC 2811 §2.1): `#` global, `&` server-local, `+`
     * no-modes, `!` safe/timestamped.
     *
     * Private on purpose: every question about the set is answered by a function below, so
     * there is no way to re-ask one of them at a call site and get a different answer. Half
     * the set (`#&`, hand-written in a sort key) is how the ordering divergence in
     * lurker-ios#98 got in beside the classification one.
     */
    private val sigils: Set<Char> = setOf('#', '&', '+', '!')

    /**
     * Whether `target` names a **channel** — the client-side twin of the server's
     * `isChannelTarget` (`shared/channels.ts`).
     *
     * ⚠⚠ Testing only for `#` is the most-repeated bug in this codebase's lineage: it had
     * accumulated in ~30 places in the web client (lurker#724), and one survived on iOS
     * (`IgnoreSet.isDmTarget`, lurker-ios#98) long enough for a DMs-level ignore rule to hide
     * an `&local` channel on iOS while the same rule left it visible on the web. It survives
     * because `&` is server-local and `+`/`!` are historic, so almost every real network only
     * uses `#`.
     *
     * One definition, called everywhere, rather than a per-call-site prefix test — the halves
     * disagreeing is the whole failure mode. `BufferKind.of`, the command parser's target
     * classification, the `/ignore` channel-scope parser and the ignore matcher's DM test all
     * route through here.
     *
     * ⚠ This answers "is this the NAME of a channel". It is *not* the question a completion
     * trigger or a literal sigil asks — a hex colour's leading `#`, for instance, is a
     * character, not a classification, and stays `#`-only.
     */
    fun isChannelTarget(target: String): Boolean =
        target.firstOrNull()?.let(sigils::contains) ?: false

    /**
     * Whether this is a channel *name* at all, rather than punctuation and whitespace.
     *
     * The guard in front of every join: `ensurePrefix("#")` sends a JOIN for "#", which is a
     * request for a channel whose name is empty.
     *
     * ⚠⚠ `stripSigils`, not `fold`. `fold` drops exactly ONE leading sigil, so `##`, `#&`
     * and `&&` all fold to something non-empty and sail through — and `ensurePrefix` passes
     * them along untouched, which is a JOIN for a sigil-only target: precisely what this
     * guard exists to stop. `stripSigils` strips them all and still keeps a real `##anime`.
     *
     * Trims here so there is one rule rather than one per caller: the two that existed on iOS
     * had already drifted to two different trims while a comment asserted they agreed.
     */
    fun namesAChannel(name: String): Boolean = stripSigils(name.trimmingWhitespacesAndNewlines()).isNotEmpty()

    /**
     * A bare name gets a leading `#`; an already-sigiled one is left alone. The web's
     * `ensureChannelPrefix`.
     */
    fun ensurePrefix(name: String): String = if (isChannelTarget(name)) name else "#$name"

    /**
     * Strip **every** leading channel sigil — the web's `stripChannelPrefix`, and for the
     * same two uses: sort keys and display. Never for addressing.
     *
     * All of them, not one, because the question is "what is this channel called" and `##`
     * is a real convention (`##anime`). All FOUR, because a sort key that stripped `#&` and
     * left `+`/`!` floated those channels above every named one — the same network listed
     * two ways on two clients, off a hand-written half of the set.
     */
    fun stripSigils(name: String): String = name.trimStart { it in sigils }

    /**
     * Fold for prefix-matching in autocomplete: lowercased, with one leading sigil dropped,
     * so `li` and `#li` both match `#linux`.
     */
    fun fold(name: String): String {
        val lowered = name.lowercase()
        return if (lowered.firstOrNull() in sigils) lowered.drop(1) else lowered
    }
}
