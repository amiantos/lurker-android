// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * One rule as a listing sees it: the rule, plus which bucket it came from.
 *
 * The scope isn't on the rule — the row the server ships is identical in both buckets — so it
 * is carried alongside, and it's what makes a listed rule removable: a by-id delete has to be
 * addressed to the bucket the rule lives in. Null is the global bucket, i.e. every network.
 */
data class ScopedIgnoreRule(
    val rule: IgnoreRule,
    val scope: Int?,
)

/**
 * The account's ignore rules, compiled and ready to ask (lurker #301, lurker#350).
 *
 * Two buckets, exactly as the server keeps them: `global` rules apply on every network — the
 * default scope a bare `/ignore` creates — and `byNetwork` rules are scoped to one. A
 * network's effective set is the union of the two, and every query below takes it.
 *
 * Server-authoritative. `/ignore` and `/unignore` here (lurker-ios#86), like `/ignore` and the
 * settings pane on the web, *ask* — the server validates, stores, and fans the resulting list
 * back to every device, which is what makes a rule made in a browser take effect on the phone
 * without either side coordinating. **Nothing writes to this type but a frame:** it is replaced
 * whole by `snapshot`/`ignore-list-updated`, never mutated in place by the command that caused
 * the change, so a rule the server refuses simply never appears.
 *
 * Immutable, and replaced rather than mutated: a `snapshot` or `ignore-list-updated` frame
 * builds a whole new one. That's what makes compiling eager rather than cached — the globs
 * and patterns are compiled once per list change, and the render path, which runs per row,
 * only ever reads. A list that changes maybe twice a session against a set that's walked for
 * every visible line is the right way round for that trade.
 *
 * **Because it is only ever replaced, `===` is a valid test for "the rules changed."** On iOS
 * that's what lets the screens' `removeDuplicates` predicates compare it with a pointer test
 * instead of walking every rule on every frame the socket delivers. The contract lives here
 * rather than being restated at each of those predicates, because it's a property of this type.
 *
 * Port note: a `final class` with no `==` in LurkerKit, so there a second set holding the same
 * rules is a *different* set. Here it has value equality — two sets are equal when both
 * buckets hold the same rules in the same order — because it sits in published state, and a
 * `StateFlow` decides whether anything changed with `equals`: a frame that re-sends the rules
 * already held should not read as a change. The compiled sets are derived from the buckets and
 * take no part in it. `equals` tries `===` first, so the pointer test above is still the cost
 * of the common case; `===` remains valid for "changed" in the one direction LurkerKit uses
 * it — a different instance may now be an equal one, the same instance is never a changed one.
 */
class IgnoreSet(
    private val global: List<IgnoreRule> = emptyList(),
    private val byNetwork: Map<Int, List<IgnoreRule>> = emptyMap(),
) {
    /**
     * The effective compiled set per network — global ∪ that network's own — precomputed for
     * every network the store knows about. A network with no rules of its own isn't in here
     * and falls back to `compiledGlobal`, which is the same answer without the copy.
     */
    private val mergedByNetwork: Map<Int, IgnoreMatch.CompiledSet>
    private val compiledGlobal: IgnoreMatch.CompiledSet

    init {
        val compiledGlobal = IgnoreMatch.compile(global)
        this.compiledGlobal = compiledGlobal
        this.mergedByNetwork = buildMap {
            for ((networkId, rules) in byNetwork) {
                if (rules.isEmpty()) continue
                put(networkId, IgnoreMatch.CompiledSet.merged(compiledGlobal, IgnoreMatch.compile(rules)))
            }
        }
    }

    /**
     * Replace one bucket, keeping the other. `networkId` null targets the global bucket, a
     * number targets that network's — the same routing the `ignore-list-updated` frame uses.
     *
     * Note the null convention here is the *opposite* of the one buffers use, where a null
     * networkId means the app-scoped system buffer. An ignore scope of "no network" is
     * "every network"; a buffer with no network is the one place ignores don't apply at all.
     */
    fun replacing(networkId: Int?, rules: List<IgnoreRule>): IgnoreSet {
        if (networkId == null) return IgnoreSet(global = rules, byNetwork = byNetwork)
        val next = byNetwork.toMutableMap()
        next[networkId] = rules
        return IgnoreSet(global = global, byNetwork = next)
    }

    /**
     * The rules visible from `networkId`, in listing order: the globals first, then that
     * network's own. The one read accessor on the stored buckets — everything else here
     * answers a *question* about the rules, and this hands them over.
     *
     * Order is the contract, not a detail: the number a user reads off `/ignore` is a
     * position in this list, so `/unignore <n>` resolves both the rule's id and the bucket
     * to send the removal to from it. Matches the web's `combinedIgnores`, so the same
     * account numbers its rules identically on both clients.
     *
     * A null `networkId` is the system buffer, where only global rules are in scope — there's
     * no connection for a network-scoped rule to be about.
     *
     * **Lapsed rules are included**, unlike every other accessor here, which drops them. The
     * difference is what the question is: those answer "is this line hidden", where a rule
     * past its `expiresAt` has nothing to say; this one is an inventory of the rows the
     * server is holding, and the server does not delete a row the moment it lapses — its
     * sweeper gets to it within a minute, and a `DELETE … WHERE mask = ?` in the meantime
     * takes the lapsed row with the rest.
     *
     * Filtering them here looked tidier and was wrong twice over. The indices are minted from
     * this list and spent later: a rule lapsing between the `/ignore` that printed the
     * numbers and the `/unignore` that used one would silently renumber everything below it,
     * so `/unignore 2` would delete a different, still-live rule — with a receipt naming it.
     * And the by-mask receipt counts these rules to describe a delete that has no expiry
     * predicate, so it would have under-counted. A lapsed rule reads as `(expired …)` in the
     * listing instead, which is the honest form of the same information.
     */
    fun listing(networkId: Int?): List<ScopedIgnoreRule> {
        val globals = global.map { ScopedIgnoreRule(rule = it, scope = null) }
        if (networkId == null) return globals
        return globals + (byNetwork[networkId] ?: emptyList()).map {
            ScopedIgnoreRule(rule = it, scope = networkId)
        }
    }

    /** The rules in force on `networkId`. */
    private fun compiled(networkId: Int): IgnoreMatch.CompiledSet = mergedByNetwork[networkId] ?: compiledGlobal

    /**
     * Whether anything at all could apply on this network — the cheap gate every caller on a
     * hot path takes first, and the reason an account with no rules pays nothing for this
     * feature beyond a map lookup.
     *
     * A null `networkId` is the system buffer, whose lines have no IRC sender to ignore.
     */
    fun isEmpty(networkId: Int?): Boolean {
        if (networkId == null) return true
        return compiled(networkId).isEmpty
    }

    /** The full verdict for one event. The message-list render path's question. */
    fun evaluate(networkId: Int?, input: IgnoreInput, now: Instant = Instant.now()): IgnoreVerdict {
        if (networkId == null) return IgnoreVerdict.visible
        return IgnoreMatch.evaluate(compiled(networkId), input, now = now)
    }

    /** Whether this event is hidden outright. */
    internal fun isHidden(networkId: Int?, input: IgnoreInput, now: Instant = Instant.now()): Boolean =
        evaluate(networkId = networkId, input, now = now).hide

    /**
     * The verdict for a stored message in `target`. **The one adapter from `Message` to
     * `IgnoreInput`** — every surface that holds a message object comes through here, so
     * there is a single answer to what a line's sender, body and DM-ness are, and a new field
     * on `IgnoreInput` is added in one place.
     *
     * A line with no sender is never hidden, and neither is your own: a mask can legitimately
     * cover your nick (`*!*@somehost` on a shared host), and hiding your own messages from
     * your own screen is never what such a rule meant. That exemption is stated here, once,
     * for all of them.
     */
    fun verdict(
        networkId: Int?,
        message: Message,
        target: String,
        now: Instant = Instant.now(),
    ): IgnoreVerdict {
        val nick = message.nick
        if (nick == null || nick.isEmpty() || message.isSelf) return IgnoreVerdict.visible
        return evaluate(
            networkId = networkId,
            IgnoreInput(
                nick = nick,
                userhost = message.userhost,
                target = target,
                text = message.text ?: "",
                type = message.type,
                // Derived from the target rather than taken from a caller's buffer record, so
                // two callers looking at the same line can't classify it differently.
                //
                // The matcher's own DM test, not `BufferKind.of` — see `isDmTarget`. The two
                // agree on all four channel sigils now, but they still answer different
                // questions: `BufferKind` has a `System` case this does NOT fold into
                // "not a DM" (`:system:` carries no sigil and isn't `:server:`, so the
                // matcher would call it one). Harmless only because the system buffer has no
                // network and `evaluate` returns `visible` before `isDm` is ever read — a
                // guard one caller up, not a property of this test. Hence the named answer
                // here rather than a kind comparison that would look equivalent and isn't.
                isDm = isDmTarget(target),
            ),
            now = now,
        )
    }

    /**
     * Whether a message row is hidden, for the surfaces that hold the message object — the
     * highlights, bookmarks and search feeds.
     *
     * Unlike `isIgnored` this honors level, channel and content-pattern rules, so an
     * `/ignore x PUBLIC` or a `-pattern` rule keeps those lines out of search results too.
     */
    fun isMessageHidden(
        networkId: Int?,
        message: Message,
        target: String,
        now: Instant = Instant.now(),
    ): Boolean = verdict(networkId = networkId, message = message, target = target, now = now).hide

    /**
     * A buffer's lines with the ignored ones dropped and the `NOHIGHLIGHT`-covered ones
     * demoted — the message list's whole use of this type, in one call.
     *
     * Here rather than in the screen so it's reachable by tests (on iOS the app target has
     * no test bundle) and so the highest-traffic surface reads through the same adapter the
     * low-traffic feeds do. Returns the input untouched when nothing could apply, which is
     * the common case and costs one map lookup.
     * `keeping` is a message id that must survive the filter whatever the rules say — the
     * message a jump was aimed at. Navigating to a specific line and being shown the space
     * where it isn't is worse than showing a line a rule would otherwise hide, and on iOS
     * it also strands the landing (see `ChatViewController.jumpExemptId`). Its
     * highlight is still demoted: the exemption is about the row existing, not about
     * overriding what the rule says the row should look like.
     */
    fun visible(
        messages: List<Message>,
        networkId: Int?,
        target: String,
        keeping: Long? = null,
        now: Instant = Instant.now(),
    ): List<Message> {
        if (isEmpty(networkId)) return messages
        return messages.mapNotNull { message ->
            val verdict = verdict(networkId = networkId, message = message, target = target, now = now)
            // Ephemerals (id 0) can never be the exempt row: 0 is the *absence* of a
            // persisted id, not an address, so nothing can have jumped to one — and treating
            // it as a match would exempt every ephemeral at once.
            val isJumpTarget = message.id != 0L && message.id == keeping
            if (verdict.hide && !isJumpTarget) return@mapNotNull null
            if (verdict.nohilight) message.unhighlighted() else message
        }
    }

    /**
     * Whether this sender is *broadly* ignored — hidden regardless of what they say.
     *
     * For the callers that have a nick and a buffer but no message: nick completion, the
     * typing indicator. Deliberately narrow — a level-scoped, content-pattern or NOHIGHLIGHT
     * rule does not count here, because answering "should this person be offered for
     * completion" from a rule that only hides their joins would be inventing an opinion the
     * rule never expressed. Those need full event context; use `evaluate`.
     *
     * `channel` is where the question is being asked, and passing it is what makes a
     * channel-scoped rule work on these two surfaces. Omitting it (the web's behavior, which
     * hardcodes `''`) means only unscoped rules ever match — so `/ignore bob -channels #foo`
     * erased bob from #foo's message list and nicklist while "bob is typing…" kept appearing
     * at the foot of that same buffer, and `@bo` still completed to him.
     */
    fun isIgnored(
        networkId: Int?,
        nick: String,
        userhost: String?,
        channel: String = "",
        now: Instant = Instant.now(),
    ): Boolean =
        isMemberHidden(networkId = networkId, nick = nick, userhost = userhost, channel = channel, now = now)

    /**
     * The nicklist filter: whether a whole-identity `ALL` rule erases this member from the
     * open channel. See `IgnoreMatch.isMemberHidden` for why nothing weaker qualifies.
     */
    fun isMemberHidden(
        networkId: Int?,
        nick: String,
        userhost: String?,
        channel: String,
        now: Instant = Instant.now(),
    ): Boolean {
        if (networkId == null) return false
        return IgnoreMatch.isMemberHidden(
            compiled(networkId), nick = nick, userhost = userhost, channel = channel, now = now,
        )
    }

    /**
     * Whether this buffer's plain-unread signal is muted (lurker #359) — what the buffer list
     * reads to downgrade a badge from "everything" to "highlights only".
     */
    fun mutesUnread(networkId: Int?, target: String, now: Instant = Instant.now()): Boolean {
        if (networkId == null) return false
        return IgnoreMatch.channelMutesUnread(compiled(networkId), channel = target, now = now)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is IgnoreSet && global == other.global && byNetwork == other.byNetwork
    }

    override fun hashCode(): Int = 31 * global.hashCode() + byNetwork.hashCode()

    companion object {
        /** No rules at all — what a fresh session and a signed-out one both hold. */
        val empty = IgnoreSet()

        /**
         * Whether a target is a DM **as the matcher means it** — the client-side twin of the
         * server's `isDmTarget` (`wsHub.ts`, `ircConnection.ts:isDmTargetName`), which reads
         * `!isChannelTarget(target) && !target.startsWith(':server:')`.
         *
         * This is the one matcher input derived here rather than received on the wire, so it is
         * the one place a client can disagree with the server about what a rule covers. On iOS
         * it was exactly that, until lurker-ios#98: this test was `#`-only after the server
         * widened to all four sigils (lurker#724), so a DMs-level rule and an `&local` channel
         * rendered two ways on one account — hidden on iOS, visible on the web. The
         * classification now comes from `ChannelName.isChannelTarget`, the one definition both
         * tiers mirror.
         *
         * A `=bob` DCC chat counts, deliberately (lurker#270): the verdict the server reaches for
         * its lines comes from `wsHub.isDmTarget`, which counts it too, so a DMs-level rule covers
         * a direct chat on both tiers. (`isDmTargetName` does NOT count it — but that one asks
         * "can this go on the IRC wire", not "is this direct conversation".)
         *
         * Port note: both tests read UTF-16 units, as the server's do, where Swift reads
         * grapheme clusters. A target whose sigil carries a combining mark (`#` + U+FE0F +
         * U+20E3, the keycap emoji) is a channel here and on the server and a DM on iOS, and
         * `:server:` directly followed by a combining mark is still the server buffer here.
         */
        internal fun isDmTarget(target: String): Boolean =
            target.isNotEmpty() && !ChannelName.isChannelTarget(target) && !target.startsWith(":server:")
    }
}
