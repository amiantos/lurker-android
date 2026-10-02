// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import java.time.Duration
import java.time.Instant

/**
 * How actively a peer is composing, per the `+typing` client tag.
 *
 * Only the two states that mean "still composing" exist here. The wire's third value,
 * `done`, is the *absence* of typing rather than a kind of it — so it parses to null and the
 * store removes the entry, as does any value some future client invents. Modelling `done` as
 * a case instead would leave an entry sitting in the map with nothing left to clear it: the
 * web client shipped exactly that and called the result "a permanently stuck indicator"
 * (`stores/buffers.ts:1238`).
 */
enum class TypingActivity(val rawValue: String) {
    Active("active"),
    Paused("paused");

    /**
     * How long an entry survives without a refresh, matching the web's `TYPING_DURATIONS`
     * (`stores/buffers.ts:12`) so a peer's indicator lives equally long on either client.
     *
     * The two differ by design, not by tuning: `active` is re-sent every few seconds while
     * keys are actually moving, so it only needs a lease long enough to bridge the gap
     * between refreshes. `paused` means "stopped, but the draft is still sitting there" and
     * is sent *once*, so it has to outlive a long pause entirely on its own.
     */
    val lease: Duration
        get() = when (this) {
            Active -> Duration.ofSeconds(6)
            Paused -> Duration.ofSeconds(30)
        }

    companion object {
        fun fromRawValue(raw: String): TypingActivity? = entries.firstOrNull { it.rawValue == raw }

        /**
         * `done`, an empty string, a missing field, and anything unrecognized all mean the same
         * thing to us: stop showing this peer.
         */
        fun from(raw: String?): TypingActivity? {
            if (raw == null) return null
            return fromRawValue(raw)
        }
    }
}

/** One peer's live typing state in one buffer. */
data class TypingEntry(
    /**
     * The nick as the server cased it. The map key is case-folded, so this is the copy that
     * actually gets displayed.
     */
    val nick: String,
    val activity: TypingActivity,
    /**
     * When this peer began the *current* run of typing — carried across refreshes, so a list
     * of several typists holds a stable order (see `ChatState.typists`). Ordering on
     * `expiresAt` instead would reshuffle the list every time somebody's `active` refreshed,
     * which in a busy channel is a permanent flicker.
     */
    val startedAt: Instant,
    /**
     * When this entry stops counting.
     *
     * Expiry is evaluated at *read* time rather than by a timer that mutates the map. Two
     * reasons, both load-bearing: it keeps `LurkerStore.reduce` a pure function of
     * (state, frame, now) — so every multi-typist case below is unit-testable without
     * sleeping — and it deletes a whole bug class the web had to handle by hand, where a
     * timer outlives the buffer it was armed for (`stores/buffers.ts:923-943`).
     */
    val expiresAt: Instant,
    /**
     * `nick!user@host`, when the server had one to send.
     *
     * Nothing reads it yet. It's here because it's what an ignore rule matches on, so a
     * typing indicator can be suppressed for an ignored peer the way the web already does
     * (`StatusBar.vue:318`) once rules land in this client — and because the field is on the
     * wire now, so dropping it would mean a second pass through the parser later.
     */
    val userhost: String? = null,
) {
    /**
     * Whether this entry still counts at `now`. Exclusive of the boundary, so a lease that
     * expires exactly on the tick is over rather than lingering a frame longer.
     */
    fun isLive(now: Instant): Boolean = now < expiresAt
}

/**
 * What we tell the network about our own composing. Unlike `TypingActivity` — which models
 * only the states worth *displaying* — this includes `done`, because stopping is a thing we
 * have to actively say.
 */
enum class TypingSignal(val rawValue: String) {
    Active("active"),
    Paused("paused"),
    Done("done");

    companion object {
        fun fromRawValue(raw: String): TypingSignal? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Decides which `typing` signals our own draft should emit, and when.
 *
 * Split out from the composer as its own type with an injected clock for the same reason the
 * incoming lease is read-time: this is throttling logic with three interacting deadlines, it
 * is miserable to verify by watching a phone, and here it can be driven to any instant in a
 * test. The composer keeps only the timer that asks it questions.
 *
 * The policy matches the web's (`MessageInput.vue:1616-1643`) so both clients present the
 * same rhythm to the network:
 *  - a non-empty draft emits `active`, re-sent no more than every `refresh` seconds;
 *  - `idle` seconds without a keystroke downgrades to `paused` — "stopped, draft still here";
 *  - emptying the draft, or turning it into a command, emits `done` immediately;
 *  - so does sending, or leaving the buffer (`ended()`).
 *
 * A leading `/` counts as not-composing on purpose: a command is not a message to the
 * channel, and telling everyone you're typing while you run `/whois` leaks that you're doing
 * *something* and then never delivers a line to justify it.
 *
 * Port note: a value type in LurkerKit, whose methods both mutate it and return the signal.
 * ⚠ Here it is a plain mutable class — a small state machine with exactly one owner (the
 * composer). It must never go into `ChatState` or travel through a flow: a mutation in place
 * neither publishes nor compares as a change.
 */
class OutgoingTyping {
    /** What the network currently believes, or null if we've said nothing (or already `done`). */
    private var sent: TypingSignal? = null
    private var lastActiveAt: Instant? = null

    /**
     * Whether we have an outstanding claim to be typing — i.e. whether `ended()` would need
     * to say anything.
     */
    val isSignalling: Boolean get() = sent != null

    /** The draft changed. Returns the signal to send, or null to stay quiet. */
    fun draftChanged(draft: String, now: Instant): TypingSignal? {
        if (!isComposing(draft)) return ended()
        // Re-assert on a state change (we were quiet or paused) or once the refresh window
        // has elapsed — never on every keystroke, which would be a tag per character.
        val stale = lastActiveAt?.let { Duration.between(it, now) > refresh } ?: true
        if (sent == TypingSignal.Active && !stale) return null
        sent = TypingSignal.Active
        lastActiveAt = now
        return TypingSignal.Active
    }

    /**
     * The idle deadline elapsed with `draft` still in the field.
     *
     * Takes the draft rather than trusting the last one it saw: the field can be emptied by
     * something that isn't a keystroke (a send, a completion replacing everything), and
     * announcing `paused` for a draft that no longer exists would leave a peer watching a
     * ghost for the full 30-second paused lease.
     */
    @Suppress("UNUSED_PARAMETER")
    fun idled(draft: String, now: Instant): TypingSignal? {
        if (sent != TypingSignal.Active || !isComposing(draft)) return null
        sent = TypingSignal.Paused
        return TypingSignal.Paused
    }

    /**
     * Sent the line, switched buffers, or otherwise stopped. Returns `done` only if the
     * network currently thinks we're typing — so a buffer switch with an untouched composer
     * doesn't spray `done` at every channel you pass through.
     */
    fun ended(): TypingSignal? {
        if (sent == null) return null
        sent = null
        lastActiveAt = null
        return TypingSignal.Done
    }

    companion object {
        /**
         * How often an ongoing `active` is re-sent. The peer's `active` lease is 6s, so a 3s
         * refresh keeps it alive with a full period to spare against a dropped tag.
         */
        val refresh: Duration = Duration.ofSeconds(3)

        /** How long a draft sits untouched before it's `paused`. */
        val idle: Duration = Duration.ofSeconds(3)

        /** Whether `draft` is something we'd tell the network we're composing. */
        private fun isComposing(draft: String): Boolean {
            val trimmed = draft.trimmingWhitespacesAndNewlines()
            return trimmed.isNotEmpty() && !draft.startsWith("/")
        }
    }
}
