// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.store.ChatState
import java.time.Instant

/**
 * One rung of the notification ladder (lurker#359, Mobile lurker#1087), loudest first.
 *
 * The cases are in ladder order, and each is strictly quieter than the one above it — which is
 * what lets a buffer's setting be one choice out of a list rather than a set of switches that
 * can contradict each other.
 */
enum class NotifyRung(val rawValue: String) {
    /** Every message notifies. A channel's `notify_always` flag, and a DM's resting state. */
    All("all"),

    /** Only highlights notify. A channel's and a network's resting state. */
    Highlights("highlights"),

    /** Nothing notifies, but the unread badge still counts. A `NONOTIFY` ignore rule. */
    Nothing("nothing"),

    /** Nothing notifies and nothing counts as unread. A `NONOTIFY` + `NOUNREAD` ignore rule. */
    Muted("muted");

    /** The label, word for word the web client's. */
    val label: String
        get() = when (this) {
            All -> "All messages"
            Highlights -> "Highlights only"
            Nothing -> "Nothing"
            Muted -> "Muted"
        }

    companion object {
        fun fromRawValue(raw: String): NotifyRung? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The notification "quietness" ladder: which rungs a buffer offers, which one it is on, and what
 * to send to move it. The port of the web's `useNotifyLadder`, kept pure so the buffer info
 * screen and the buffer list's menu ask the same code and a test can ask it without a socket.
 *
 *     Channel:  All messages · Highlights only (default) · Nothing · Muted
 *     DM:       All messages (default) · Nothing · Muted
 *     Network:  Highlights only (default) · Nothing · Muted
 *
 * A DM has no "Highlights only" because every DM line is already the signal, and a network has
 * no "All messages" because a whole network's firehose is not something to be notified about.
 *
 * Two stores sit under the one list. "All messages" on a channel is the server's per-channel
 * `notify_always` flag (`ChatState.notifiesAlways`); the two quiet rungs are ignore rules
 * (`IgnoreSet.bufferMuteRule` / `networkMuteRule`). Nothing here writes to either: a `plan` is
 * what to *ask* for, and the rung only moves when the server's echoes fold in.
 */
object NotifyLadder {

    /** What a ladder is about: one channel, one DM, or a whole network. */
    sealed interface Scope {
        val networkId: Int

        data class Channel(override val networkId: Int, val target: String) : Scope
        data class Dm(override val networkId: Int, val target: String) : Scope
        data class Network(override val networkId: Int) : Scope

        /** The rungs on offer, in ladder order. */
        val rungs: List<NotifyRung>
            get() = when (this) {
                is Channel -> listOf(NotifyRung.All, NotifyRung.Highlights, NotifyRung.Nothing, NotifyRung.Muted)
                is Dm -> listOf(NotifyRung.All, NotifyRung.Nothing, NotifyRung.Muted)
                is Network -> listOf(NotifyRung.Highlights, NotifyRung.Nothing, NotifyRung.Muted)
            }

        /** The rung with nothing set — no flag, no rule. */
        val base: NotifyRung
            get() = when (this) {
                is Channel, is Network -> NotifyRung.Highlights
                is Dm -> NotifyRung.All
            }

        /**
         * The `channels` a mute rule for this scope carries: exactly this one target, folded,
         * or none at all for a network.
         */
        val ruleChannels: List<String>?
            get() = when (this) {
                is Channel -> listOf(target.lowercase())
                is Dm -> listOf(target.lowercase())
                is Network -> null
            }

        companion object {
            /**
             * The ladder a buffer gets, or null for the app's own system buffer, which has none.
             *
             * Classified by `BufferKind.of` — all four channel sigils, never a `#` probe — so an
             * `&local` channel gets the channel ladder and the flag the server will actually
             * consult for it. A server log stands for its network. A `=nick` DCC chat gets the DM
             * ladder under its own `=nick` target, which is what the web's buffer menu gives it.
             */
            fun of(key: BufferKey): Scope? {
                val networkId = key.networkId ?: return null
                return when (BufferKind.of(networkId = networkId, target = key.target)) {
                    BufferKind.Channel -> Channel(networkId = networkId, target = key.target)
                    BufferKind.Dm, BufferKind.Dcc -> Dm(networkId = networkId, target = key.target)
                    BufferKind.Server -> Network(networkId = networkId)
                    BufferKind.System -> null
                }
            }
        }
    }

    /** One thing to send. The cases map one to one onto `LurkerClient`'s three verbs. */
    sealed interface Step {
        /** `set-channel-notify-always`. */
        data class SetNotifyAlways(val networkId: Int, val target: String, val notifyAlways: Boolean) : Step

        /** `remove-ignore` by id, addressed to the bucket the rule lives in (null = global). */
        data class RemoveRule(val id: Int, val scope: Int?) : Step

        /** `add-ignore` at network scope. */
        data class AddRule(val networkId: Int, val rule: IgnoreRule) : Step
    }

    /**
     * The levels a quiet rung's rule carries, in canonical order; null for the two rungs that
     * are the absence of a rule.
     */
    internal fun levels(rung: NotifyRung): List<String>? =
        when (rung) {
            NotifyRung.Nothing -> IgnoreLevels.canonicalize(listOf("NONOTIFY"))
            NotifyRung.Muted -> IgnoreLevels.canonicalize(listOf("NOUNREAD", "NONOTIFY"))
            NotifyRung.All, NotifyRung.Highlights -> null
        }

    /**
     * The rung a buffer is on, from the two things that decide it.
     *
     * The flag wins: a channel with `notify_always` reads "All messages" whatever rule sits
     * beside it, as on the web. Otherwise it is keyed on `NONOTIFY`, the notify dimension —
     * with `NOUNREAD` that's Muted, alone it's Nothing. A `NOUNREAD`-only rule (badge hidden,
     * notifications still firing; only a hand-typed `/ignore` makes one) is neither, so it
     * falls through to the base rung.
     *
     * `notifiesAlways` is read for a channel only — nothing else can carry the flag.
     */
    fun rung(scope: Scope, notifiesAlways: Boolean, muteLevels: List<String>): NotifyRung {
        if (scope is Scope.Channel && notifiesAlways) return NotifyRung.All
        if (muteLevels.contains("NONOTIFY")) {
            return if (muteLevels.contains("NOUNREAD")) NotifyRung.Muted else NotifyRung.Nothing
        }
        return scope.base
    }

    /**
     * The ordered steps that put `scope` on `rung`. Empty when it is already there, and for a
     * rung the scope doesn't offer.
     *
     * A reconcile rather than a toggle: the flag is set or cleared only if it differs, a stale
     * mute rule is removed, and the wanted one is added. The stale rule is removed **from its
     * own scope** — a hand-typed global mute comes out of the global bucket, where a removal
     * addressed to the network would miss it and leave it standing under a menu that says
     * otherwise — while the new one is always added at network scope.
     *
     * Remove comes before add so the two never coexist, and a caller that stops at the first
     * step that fails to send leaves at most a missing rule, never a duplicate.
     *
     * A rule past its `expiresAt` is still a row the server holds, for up to a minute, but it
     * mutes nothing. It is never "already there": a quiet rung replaces it with a live one.
     */
    fun plan(
        rung: NotifyRung,
        scope: Scope,
        notifiesAlways: Boolean,
        muteRule: ScopedIgnoreRule?,
        now: Instant = Instant.now(),
    ): List<Step> {
        if (!scope.rungs.contains(rung)) return emptyList()
        val steps = mutableListOf<Step>()
        if (scope is Scope.Channel && (rung == NotifyRung.All) != notifiesAlways) {
            steps.add(
                Step.SetNotifyAlways(
                    networkId = scope.networkId, target = scope.target, notifyAlways = rung == NotifyRung.All,
                ),
            )
        }
        val want = levels(rung)
        if (muteRule != null && want != null && muteRule.rule.levels.toSet() == want.toSet() &&
            muteRule.rule.levels.size == want.size && !isLapsed(muteRule.rule, now)
        ) {
            return steps
        }
        if (muteRule != null) steps.add(Step.RemoveRule(id = muteRule.rule.id, scope = muteRule.scope))
        if (want != null) {
            steps.add(
                Step.AddRule(
                    networkId = scope.networkId,
                    rule = IgnoreRule(channels = scope.ruleChannels, levels = want),
                ),
            )
        }
        return steps
    }

    internal fun isLapsed(rule: IgnoreRule, now: Instant): Boolean =
        rule.expiresAt?.let { it <= now } ?: false
}

/** The mute rule the ladder for `scope` reads and replaces, with the bucket it lives in. */
fun ChatState.notifyMuteRule(scope: NotifyLadder.Scope): ScopedIgnoreRule? =
    when (scope) {
        is NotifyLadder.Scope.Channel -> ignores.bufferMuteRule(networkId = scope.networkId, target = scope.target)
        is NotifyLadder.Scope.Dm -> ignores.bufferMuteRule(networkId = scope.networkId, target = scope.target)
        is NotifyLadder.Scope.Network -> ignores.networkMuteRule(networkId = scope.networkId)
    }

private fun ChatState.notifiesAlways(scope: NotifyLadder.Scope): Boolean {
    if (scope !is NotifyLadder.Scope.Channel) return false
    return notifiesAlways(networkId = scope.networkId, target = scope.target)
}

/** The rung `scope` is on right now. A lapsed mute rule mutes nothing, so it reads as none. */
fun ChatState.notifyRung(scope: NotifyLadder.Scope, now: Instant = Instant.now()): NotifyRung {
    val rule = notifyMuteRule(scope)?.rule
    val live = rule?.let { !NotifyLadder.isLapsed(it, now) } ?: false
    return NotifyLadder.rung(
        scope,
        notifiesAlways = notifiesAlways(scope),
        muteLevels = if (live) rule?.levels ?: emptyList() else emptyList(),
    )
}

/** What to send to put `scope` on `rung`, from this state. */
fun ChatState.notifyPlan(
    rung: NotifyRung,
    scope: NotifyLadder.Scope,
    now: Instant = Instant.now(),
): List<NotifyLadder.Step> =
    NotifyLadder.plan(
        rung,
        scope,
        notifiesAlways = notifiesAlways(scope),
        muteRule = notifyMuteRule(scope),
        now = now,
    )
