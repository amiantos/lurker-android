// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * Collapses a run of consecutive membership-churn events into a single net-effect
 * summary, IRCCloud-style — a port of the web client's `shared/consolidate.ts`, matching
 * its event set exactly.
 *
 * Pure and side-effect-free: no UI, no state. Given the buffer's rendered messages, in
 * order, it returns a row stream where each maximal run of 2+ consolidatable events is one
 * `Summary`, and everything else passes through untouched.
 *
 * Algorithm:
 *   1. Walk the stream; group consecutive consolidatable events into a run. Any other row
 *      (a real message, a kick, a mode, a topic, an error) terminates it.
 *   2. Inside a run, accumulate a per-identity action sequence: `J` join, `L` leave
 *      (part *or* quit), `R` rename, `H` rehost (chghost). A rename transfers the identity
 *      to the new nick key so the chain is followed across renames.
 *   3. Classify each identity by its first/last J|L action into joined / left /
 *      reconnected / joinedAndLeft. An identity with no J|L falls back to rename over
 *      rehost: any `R` is `renamed`, otherwise `H`-only is `rehosted`.
 *   4. A run of exactly one event passes through unchanged, so a lone "alice joined" keeps
 *      its familiar standalone styling.
 *
 * Port note: nicks fold with `lowercase()` and the folded keys compare by code unit, so two of
 * PORTING.md's string edges show here. A nick ending in `Σ` folds to `ς` here and to `σ` on
 * iOS (final sigma), and two spellings of one nick that differ only in normalisation (`é`
 * against `e` + U+0301) are one identity on iOS and two here. Checked against the Swift over
 * generated runs: with neither in play the two agree row for row.
 */
object Consolidation {

    /**
     * The event types that fold into a summary — the web's `CONSOLIDATABLE_TYPES`
     * (`shared/consolidate.ts:121`).
     *
     * `mode` is deliberately *not* here, matching the web — but that no longer means mode
     * rows never fold. They do, when every change in them grants or revokes member status
     * (`foldsIntoRun`), through a second pass with its own vocabulary. That second
     * vocabulary is exactly the cost this comment used to cite as the reason not to; it is
     * also the thing being asked for, so it is paid deliberately now (lurker#673).
     *
     * What this set still is: the per-identity fold set, AND the definition of the
     * `Renderable` page unit. `mode` staying out of it is what keeps that unit meaning the
     * same thing for every client. `kick`, `topic` and `invite` stay standalone outright.
     */
    internal val consolidatableTypes: Set<EventType> =
        setOf(EventType.Join, EventType.Part, EventType.Quit, EventType.Nick, EventType.Chghost)

    /**
     * Whether a message can sit inside a run.
     *
     * Wider than `consolidatableTypes`, and deliberately a separate question — see the note
     * there. A mode row that carries anything but member-status changes still breaks the
     * run, so a ban is never folded away behind "alice was opped".
     */
    internal fun foldsIntoRun(message: Message): Boolean {
        if (consolidatableTypes.contains(message.type)) return true
        return message.type == EventType.Mode && Modes.isChurn(message.modes)
    }

    /** A row in the consolidated stream. */
    sealed interface Row {
        /** An event that stands on its own — rendered exactly as it would be uncollapsed. */
        data class Passthrough(val message: Message) : Row

        /** A run of 2+ consolidatable events, collapsed to one net-effect summary. */
        data class Summary(val summary: ConsolidationSummary) : Row
    }

    /**
     * Consolidate a buffer's rendered messages.
     *
     * @param messages the buffer's messages, already filtered to what it renders, in order.
     * @param maxNames how many names to show per category before "and N others" (floored 1).
     * @param recentSpeakers lowercased nicks to float to the front of a truncated list, so
     *   the people you were just talking to stay visible. Empty keeps insertion order.
     */
    fun consolidate(
        messages: List<Message>,
        maxNames: Int = 5,
        recentSpeakers: Set<String> = emptySet(),
    ): List<Row> {
        val out = mutableListOf<Row>()
        var run = mutableListOf<Message>()

        fun flush() {
            try {
                if (run.size <= 1) {
                    run.firstOrNull()?.let { only -> out.add(Row.Passthrough(only)) }
                    return
                }
                val summary = summarize(run, maxNames = maxNames, recentSpeakers = recentSpeakers)
                // A run that produced nothing to show falls back to rendering each event on its
                // own, rather than emitting a blank summary row. Every consolidatable type
                // contributes an action today, so this is unreachable — it's the guard that keeps
                // it that way, since adding a type to the set above without teaching
                // `identityGroups` about it would otherwise silently swallow it into a blank row.
                if (summary.groups.isEmpty()) {
                    out.addAll(run.map { Row.Passthrough(it) })
                } else {
                    out.add(Row.Summary(summary))
                }
            } finally {
                run = mutableListOf()
            }
        }

        for (message in messages) {
            if (foldsIntoRun(message)) {
                run.add(message)
            } else {
                flush()
                out.add(Row.Passthrough(message))
            }
        }
        flush()
        return out
    }

    // MARK: - Building one summary

    private fun summarize(
        events: List<Message>,
        maxNames: Int,
        recentSpeakers: Set<String>,
    ): ConsolidationSummary {
        // The two passes read disjoint slices of the run. Mode groups trail the presence
        // ones: who is here reads first, what they were given second.
        val modeEvents = events.filter { it.type == EventType.Mode }
        val presence = if (modeEvents.isEmpty()) events else events.filter { it.type != EventType.Mode }
        return ConsolidationSummary(
            groups = identityGroups(presence, maxNames = maxOf(1, maxNames), recentSpeakers = recentSpeakers) +
                modeGroups(modeEvents, maxNames = maxOf(1, maxNames), recentSpeakers = recentSpeakers),
            date = events.lastOrNull()?.date,
            firstId = events.firstOrNull()?.id ?: 0,
            lastId = events.lastOrNull()?.id ?: 0,
        )
    }

    // MARK: - Member-status net effect (+o / -v / …)

    /** Mutable per-(nick, letter) bookkeeping while walking a run's mode changes. */
    private class ModeState(
        var nick: String,
        var letter: String,
        /** The run's first change to this pair — which implies the state BEFORE it. */
        var first: Boolean,
        /** The run's last change, i.e. the state after. */
        var last: Boolean,
        var seenIndex: Int,
    )

    /**
     * Fold a run's member-status changes into per-(nick, letter) net effects.
     *
     * A SEPARATE pass from the identity walk, and it has to be: that walk is keyed on
     * identity and classifies by a join/leave sequence, while a mode change's subject is a
     * (nick, letter) pair, its verdict is a sign, and its target need never have joined
     * inside the run at all.
     *
     * Classified by first and last, the same first/last reading `classify` gives a presence
     * identity and for the same reason — the FIRST change implies the prior state, so an
     * opening `-o` means they held op before the run started:
     *
     *   `+…+`  didn't have it, does now         → granted    "was opped"
     *   `-…-`  had it, doesn't now              → revoked    "was deopped"
     *   `+…-`  didn't have it, blipped          → briefly    "was briefly opped"
     *   `-…+`  had it, lost it, has it again    → regranted  "was opped again"
     *
     * Nothing is ever dropped: the summary row has no expand affordance, so a nick dropped
     * here would be information deleted with no way to get it back.
     *
     * Renames are NOT followed. The identity walk migrates a nick across an `R` action;
     * this keys on the parameter as written, so alice→bob opped as bob is reported as bob.
     */
    private fun modeGroups(
        events: List<Message>,
        maxNames: Int,
        recentSpeakers: Set<String>,
    ): List<ConsolidationSummary.IdentityGroup> {
        val net = mutableMapOf<String, ModeState>()
        var seen = 0
        for (event in events) {
            for (change in event.modes) {
                // A run can only hold mode rows that passed `Modes.isChurn`, so this is a
                // narrowing rather than a second filter.
                val param = change.param
                if (change.kind != ModeChangeKind.Prefix || param == null || param.isEmpty()) continue
                val letter = change.letter
                if (letter.isEmpty()) continue
                val key = "${param.lowercase()}\u0000$letter"
                val existing = net[key]
                if (existing != null) {
                    // `first` is captured once and never overwritten — it is what says
                    // whether they held the mode before the run.
                    existing.last = change.isGrant
                    existing.nick = param
                } else {
                    net[key] = ModeState(
                        nick = param, letter = letter,
                        first = change.isGrant, last = change.isGrant,
                        seenIndex = seen,
                    )
                    seen += 1
                }
            }
        }

        val buckets = mutableMapOf<ConsolidationSummary.IdentityGroup.Kind, MutableList<ConsolidationSummary.Entry>>()
        val bucketOrder = mutableListOf<ConsolidationSummary.IdentityGroup.Kind>()
        for (state in net.values.sortedBy { it.seenIndex }) {
            val kind = classifyMode(first = state.first, last = state.last, letter = state.letter)
            if (buckets[kind] == null) bucketOrder.add(kind)
            buckets.getOrPut(kind) { mutableListOf() }.add(ConsolidationSummary.Entry.Nick(state.nick))
        }

        val speakersLc = recentSpeakers.map { it.lowercase() }.toSet()
        return bucketOrder.mapNotNull { kind ->
            val entries = buckets[kind]
            if (entries == null || entries.isEmpty()) return@mapNotNull null
            val capped = cap(entries, maxNames = maxNames, recentSpeakers = speakersLc)
            ConsolidationSummary.IdentityGroup(
                kind = kind, visible = capped.visible, hidden = capped.hidden,
            )
        }
    }

    private fun classifyMode(
        first: Boolean,
        last: Boolean,
        letter: String,
    ): ConsolidationSummary.IdentityGroup.Kind {
        if (first) {
            return if (last) {
                ConsolidationSummary.IdentityGroup.Kind.ModeGranted(letter)
            } else {
                ConsolidationSummary.IdentityGroup.Kind.ModeBriefly(letter)
            }
        }
        return if (last) {
            ConsolidationSummary.IdentityGroup.Kind.ModeRegranted(letter)
        } else {
            ConsolidationSummary.IdentityGroup.Kind.ModeRevoked(letter)
        }
    }

    // MARK: - Identity net effect (join / part / quit / nick / chghost)

    /** Mutable per-identity bookkeeping while walking a run. */
    private class Identity(
        var displayNick: String,
        var originalNick: String,
        val actions: MutableList<Char>, // 'J' | 'L' | 'R' | 'H'
        var seenIndex: Int,
    )

    private fun identityGroups(
        events: List<Message>,
        maxNames: Int,
        recentSpeakers: Set<String>,
    ): List<ConsolidationSummary.IdentityGroup> {
        // identityKey (lowercased current nick) → bookkeeping. Renames re-key, so a separate
        // seenIndex preserves first-seen order across the migration.
        val ids = mutableMapOf<String, Identity>()
        var seenCounter = 0

        for (event in events) {
            when (event.type) {
                EventType.Nick -> {
                    val oldKey = (event.nick ?: "").lowercase()
                    val newKey = (event.newNick ?: "").lowercase()
                    val existing = ids[oldKey]
                    if (existing != null) {
                        existing.actions.add('R')
                        existing.displayNick = event.newNick ?: ""
                        ids.remove(oldKey)
                        ids[newKey] = existing
                    } else {
                        ids[newKey] = Identity(
                            displayNick = event.newNick ?: "",
                            originalNick = event.nick ?: "",
                            actions = mutableListOf('R'),
                            seenIndex = seenCounter,
                        )
                        seenCounter += 1
                    }
                }
                EventType.Join, EventType.Part, EventType.Quit, EventType.Chghost -> {
                    val key = (event.nick ?: "").lowercase()
                    val state: Identity
                    val existing = ids[key]
                    if (existing != null) {
                        state = existing
                    } else {
                        state = Identity(
                            displayNick = event.nick ?: "",
                            originalNick = event.nick ?: "",
                            actions = mutableListOf(),
                            seenIndex = seenCounter,
                        )
                        seenCounter += 1
                    }
                    val action: Char = when (event.type) {
                        EventType.Join -> 'J'
                        EventType.Chghost -> 'H'
                        else -> 'L' // part or quit
                    }
                    state.actions.add(action)
                    ids[key] = state
                }
                else -> {} // nothing else reaches a run
            }
        }

        // Bucket in a fixed display order so the readout reads the same way every time.
        val buckets = mutableMapOf<ConsolidationSummary.IdentityGroup.Kind, MutableList<ConsolidationSummary.Entry>>()
        for (identity in ids.values.sortedBy { it.seenIndex }) {
            val kind = classify(identity.actions)
            val entry: ConsolidationSummary.Entry = if (kind == ConsolidationSummary.IdentityGroup.Kind.Renamed) {
                ConsolidationSummary.Entry.Renamed(from = identity.originalNick, to = identity.displayNick)
            } else {
                ConsolidationSummary.Entry.Nick(identity.displayNick)
            }
            buckets.getOrPut(kind) { mutableListOf() }.add(entry)
        }

        val speakersLc = recentSpeakers.map { it.lowercase() }.toSet()
        val order: List<ConsolidationSummary.IdentityGroup.Kind> = listOf(
            ConsolidationSummary.IdentityGroup.Kind.Joined,
            ConsolidationSummary.IdentityGroup.Kind.Left,
            ConsolidationSummary.IdentityGroup.Kind.Reconnected,
            ConsolidationSummary.IdentityGroup.Kind.JoinedAndLeft,
            ConsolidationSummary.IdentityGroup.Kind.Renamed,
            ConsolidationSummary.IdentityGroup.Kind.Rehosted,
        )
        return order.mapNotNull { kind ->
            val entries = buckets[kind]
            if (entries == null || entries.isEmpty()) return@mapNotNull null
            val capped = cap(entries, maxNames = maxNames, recentSpeakers = speakersLc)
            ConsolidationSummary.IdentityGroup(kind = kind, visible = capped.visible, hidden = capped.hidden)
        }
    }

    /**
     * Net effect of an identity's actions. Only the J|L actions decide presence.
     *
     * With no presence change, a rename outranks a rehost — "alice → bob" says more than
     * "alice changed host" for an identity that did both.
     *
     * `H` being transparent to the J|L scan is deliberate (lurker#593): after a netsplit each
     * rejoining user emits JOIN then CHGHOST as they identify to services, so their sequence
     * is `[J, H]`. That has to read as a plain "joined" rather than splitting the summary
     * into "N joined" plus the same N "changed host". A host change earns its own category
     * only when nothing else happened.
     */
    private fun classify(actions: List<Char>): ConsolidationSummary.IdentityGroup.Kind {
        val jl = actions.filter { it == 'J' || it == 'L' }
        val first = jl.firstOrNull()
        val last = jl.lastOrNull()
        if (first == null || last == null) {
            return if (actions.contains('R')) {
                ConsolidationSummary.IdentityGroup.Kind.Renamed
            } else {
                ConsolidationSummary.IdentityGroup.Kind.Rehosted
            }
        }
        val wasPresent = first == 'L' // a leave first means they were here to begin with
        val isPresent = last == 'J' // a join last means they're here now
        return when {
            !wasPresent && isPresent -> ConsolidationSummary.IdentityGroup.Kind.Joined
            wasPresent && !isPresent -> ConsolidationSummary.IdentityGroup.Kind.Left
            !wasPresent && !isPresent -> ConsolidationSummary.IdentityGroup.Kind.JoinedAndLeft
            else -> ConsolidationSummary.IdentityGroup.Kind.Reconnected
        }
    }

    /** What `cap` hands back: the names to show, and how many it left out. Port-only. */
    private data class Capped(val visible: List<ConsolidationSummary.Entry>, val hidden: Int)

    /**
     * Cap a category's names, floating recent speakers to the front of a truncated list.
     * Stable: within the same recency tier, insertion order holds.
     */
    private fun cap(
        entries: List<ConsolidationSummary.Entry>,
        maxNames: Int,
        recentSpeakers: Set<String>,
    ): Capped {
        if (entries.size <= maxNames) return Capped(entries.toList(), 0)
        val ranked = entries.withIndex().sortedWith { lhs, rhs ->
            val lRecent = if (recentSpeakers.contains(lhs.value.rankKey)) 0 else 1
            val rRecent = if (recentSpeakers.contains(rhs.value.rankKey)) 0 else 1
            if (lRecent != rRecent) lRecent.compareTo(rRecent) else lhs.index.compareTo(rhs.index)
        }.map { it.value }
        return Capped(ranked.take(maxNames), ranked.size - maxNames)
    }
}

/**
 * The structured result of collapsing one run. The renderer turns this into text; keeping
 * it data (not a string) means the summary can be styled — nicks in their colors, the
 * connective words muted — the same way the web client colors its `NickRef`s.
 *
 * Port note: `firstId` and `lastId` are message ids, so they are `Long` here (PORTING.md,
 * Types).
 */
data class ConsolidationSummary(
    /** Net-effect membership categories, in fixed display order. */
    val groups: List<IdentityGroup>,
    /** The last event's timestamp — what the summary reveals on a drag, matching a line. */
    val date: Instant?,
    /**
     * The persisted-id span of the events this summary replaces. Lets the view find the
     * summary that now stands in for a given line after a history page reshapes the run —
     * which is what keeps scroll position pinned across a "load older" (see iOS's
     * `ChatViewController`). A run grows only at its top as older history prepends, so
     * `lastId` is a stable anchor.
     */
    val firstId: Long,
    val lastId: Long,
) {
    /**
     * One identity within the summary: a nick that joined/left/reconnected/joined-briefly/
     * changed host, or a nick that renamed itself.
     */
    sealed interface Entry {
        data class Nick(val nick: String) : Entry

        data class Renamed(val from: String, val to: String) : Entry

        /** The key a truncated list ranks by (the current display nick), lowercased. */
        val rankKey: String
            get() = when (this) {
                is Nick -> nick.lowercase()
                is Renamed -> to.lowercase()
            }
    }

    /** One net-effect category and its (possibly truncated) member list. */
    data class IdentityGroup(
        val kind: Kind,
        val visible: List<Entry>,
        val hidden: Int,
    ) {
        sealed interface Kind {
            data object Joined : Kind

            data object Left : Kind

            data object Reconnected : Kind

            data object JoinedAndLeft : Kind

            data object Renamed : Kind

            data object Rehosted : Kind

            /**
             * A member-status mode, by letter. The four mirror the presence cases exactly:
             * granted↔joined, revoked↔left, briefly↔joinedAndLeft, regranted↔reconnected —
             * because a mode pair cancels the same way a join/part pair does, and the
             * presence half has always had a category for that rather than dropping it.
             */
            data class ModeGranted(val letter: String) : Kind

            data class ModeRevoked(val letter: String) : Kind

            data class ModeBriefly(val letter: String) : Kind

            data class ModeRegranted(val letter: String) : Kind

            /** The mode letter, for the four mode cases; null for the presence ones. */
            val modeLetter: String?
                get() = when (this) {
                    is ModeGranted -> letter
                    is ModeRevoked -> letter
                    is ModeBriefly -> letter
                    is ModeRegranted -> letter
                    else -> null
                }
        }
    }
}
