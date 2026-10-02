// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Duration
import java.time.Instant

/**
 * How much join/part/quit/nick/host-change/mode noise reaches the message list — a port of
 * the web's `shared/eventFilter.ts` (lurker#666), reading the same server-side settings so the
 * phone and the browser can't disagree about the rules.
 *
 * This replaced two independent switches that answered the same question between them
 * (`chat.consolidate_joins` and `chat.smart_filter`), and added the rung neither could
 * express: hide all of it. That rung is why the tier exists — a phone screen holds a
 * fraction of the lines a desktop one does, and presence churn costs proportionally more.
 */
enum class EventMode(val rawValue: String) {
    /** Every event renders, folded into summary lines when `chat.consolidate_joins` is on. */
    All("all"),

    /** Events render only for nicks who have recently spoken — see `SmartFilter`. */
    Smart("smart"),

    /** No event rows at all. Conversation only. */
    None("none");

    companion object {
        fun fromRawValue(raw: String): EventMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The tier, the row types it hides, and the page unit that matches. */
object EventFilter {

    /**
     * The settings key the phone reads.
     *
     * Unconditionally the mobile one: the tier is split by device class (the web switches on
     * viewport width) and a phone is never the desktop case. Only the *tier* is split —
     * the modifiers below it are shared, because at `None` they're moot anyway and nobody
     * wants a different consolidation cap on their phone than at their desk.
     */
    const val modeKey = "chat.events.mobile"

    /**
     * The tier in force, defaulting to the registry's own default so behavior doesn't shift
     * under the reader when settings bootstrap lands a moment after launch.
     */
    fun mode(settings: Settings): EventMode =
        EventMode.fromRawValue(settings.string(modeKey, default = EventMode.All.rawValue)) ?: EventMode.All

    /**
     * The row types `None` hides: everything consolidation folds, plus `mode`.
     *
     * `mode` is excluded from `Consolidation.consolidatableTypes` for a reason that is about
     * page sizing rather than about modes: that set also defines the `Renderable` unit, so
     * moving `mode` into it would change what a page contains for every client. Mode rows
     * DO fold — see `Consolidation.foldsIntoRun`. And a reader who asked for *no* event
     * noise means op/voice/ban churn too, so the strictest rung takes all of it.
     *
     * ⚠ `kick`, `topic`, `invite` and `error` are absent here, and that is an
     * **undocumented default rather than a decision anyone made** — they were simply never
     * brought into the filters, and nobody has revisited it. Earlier revisions of this
     * comment asserted a principle ("things that happened, not churn") as though it were
     * settled; it wasn't. Don't cite it as a reason for anything. If someone asks for these
     * to be hidden, that is a live question, not a closed one.
     */
    val noiseTypes: Set<EventType> = Consolidation.consolidatableTypes + setOf(EventType.Mode)

    /** Whether a message is event noise, i.e. hidden entirely at `None`. */
    fun isNoise(type: EventType): Boolean = noiseTypes.contains(type)

    /**
     * Which of `messages` the current tier lets through.
     *
     * The one place the tier is applied. Both readers go through it — the row builder, and the
     * jump-to-latest pill's "N new below" count, which has to promise the number of lines the
     * reader will actually see arrive. Splitting them is how, on iOS, the pill came to advertise
     * "40 new" for a netsplit rejoin that built to nothing.
     *
     * @param speakers who has spoken in this buffer and when, for the `Smart` rung. An empty
     *   map means nobody qualifies as recent, so `Smart` hides every filterable event — the
     *   web behaves the same way with an unseeded buffer.
     * @param ownNick your nick on this buffer's network. Your own churn is never hidden, and
     *   `isSelf` doesn't cover it: the server stamps that on messages you *sent*, not on the
     *   JOIN it saw you make.
     */
    fun visible(
        messages: List<Message>,
        settings: Settings,
        speakers: SpeakerMap = SpeakerMap(),
        ownNick: String? = null,
    ): List<Message> =
        when (mode(settings)) {
            EventMode.All -> messages
            EventMode.None ->
                // Unconditional on purpose — this hides your own joins and mode changes too.
                // Someone who asked for no event noise on their phone wants none of it, not
                // none-except-mine. Kicks, topics and invites are outside `noiseTypes` and survive.
                messages.filter { !isNoise(it.type) }
            EventMode.Smart -> {
                val filter = SmartFilter(settings)
                messages.filter { !filter.hides(it, speakers = speakers, ownNick = ownNick) }
            }
        }
}

/**
 * The `Smart` rung: hide a join / part / quit / chghost / nick when its actor hasn't spoken
 * recently, so membership churn from silent lurkers stops threading through the conversation.
 * A port of the web's `MessageList.vue` filter (lurker-ios#63), reading the same tuning keys.
 *
 * Only churn is ever hidden. This never touches conversation, `kick`, `topic` or `invite`.
 *
 * `mode` DOES take part, but on different terms: a mode row is judged on the nicks it acted
 * ON rather than on its author (see `Modes.smartHides`), and only when every change in it
 * grants or revokes member status. Bans, keys, limits and channel flags always show.
 *
 * Port note: the primary constructor is private, and the public one is LurkerKit's only
 * `init`: the six fields are read off a `Settings`, never handed in.
 *
 * Port note: nicks fold with LurkerKit's `lowercased()` and compare by code unit — the
 * final-sigma and canonical-equivalence edges noted on `BufferKey.id` and in PORTING.md.
 *
 * Port note: the two windows are `Duration`s and the gaps held against them are exact, where
 * LurkerKit subtracts one `Double` of seconds from another. Checked against the Swift with
 * each gap on its edge and a millisecond either side: the two agree for any pair of
 * timestamps since 10 January 2004, and can part only for a pair that straddles that day
 * (2³⁰ seconds after 1970, where a `Double` of epoch seconds halves its precision).
 */
@ConsistentCopyVisibility
data class SmartFilter private constructor(
    /** How long before an event a nick's last message still counts as "recently spoke". */
    val delay: Duration,
    /**
     * How long *after* a join a nick's first message retroactively reveals it. 0 disables
     * unmasking. This is the half that needs live speaker recording: the reveal is always about
     * speech that happens after the buffer was fetched.
     */
    val unmask: Duration,
    val filtersJoin: Boolean,
    /**
     * Covers `part`, `quit` **and** `chghost`. The host change rides the quit toggle rather
     * than getting a fourth setting: it's the same churn from the same silent lurkers
     * (identifying to services after a netsplit fires one per shared channel), which is exactly
     * what smart filtering exists to absorb. weechat ships a dedicated `smart_filter_chghost`
     * for the same reason (lurker#591).
     */
    val filtersQuit: Boolean,
    val filtersNick: Boolean,
    /** Covers channel MODE rows that only grant or revoke member status. */
    val filtersMode: Boolean,
) {
    /**
     * Read the tuning keys. All server-side (lurker-ios#65) and shared across devices — only the
     * tier above them is split by device class. The fallbacks match the registry's own defaults
     * so behavior doesn't shift under the reader when bootstrap lands a moment after launch.
     *
     * Both windows are stored in minutes, which is what the registry's `int` controls edit.
     */
    constructor(settings: Settings) : this(
        delay = Duration.ofSeconds(settings.int("chat.smart_filter_delay", default = 5).toLong() * 60),
        unmask = Duration.ofSeconds(settings.int("chat.smart_filter_join_unmask", default = 30).toLong() * 60),
        filtersJoin = settings.bool("chat.smart_filter_join", default = true),
        filtersQuit = settings.bool("chat.smart_filter_quit", default = true),
        filtersNick = settings.bool("chat.smart_filter_nick", default = true),
        filtersMode = settings.bool("chat.smart_filter_mode", default = true),
    )

    /** Whether this row is churn from someone nobody was talking to. */
    fun hides(message: Message, speakers: SpeakerMap, ownNick: String?): Boolean {
        // A mode row asks a different question of a different subject, so it takes its own
        // path rather than being squeezed through the actor-keyed one below.
        if (message.type == EventType.Mode) {
            // The nick guard matches the web, which gates its whole smart walk on
            // `m.nick` being present. A channel MODE from the server itself — services or
            // the ircd restoring modes on a netjoin — arrives with no nick at all, and
            // those must show rather than be judged against a speaker map they can never
            // appear in.
            val nick = message.nick
            val at = message.date
            if (!filtersMode || message.isSelf || nick == null || nick.isEmpty() || at == null) return false
            return Modes.smartHides(
                message.modes,
                actorNick = nick,
                ownNick = ownNick,
                spokeRecently = { nick ->
                    val spoke = speakers[nick] ?: return@smartHides false
                    spoke <= at && Duration.between(spoke, at) <= delay
                },
            )
        }
        val nick = message.nick
        if (!filters(message.type) || message.isSelf ||
            nick == null || nick.isEmpty() ||
            isOurs(message, ownNick = ownNick)
        ) {
            return false
        }
        // No clock, no window to judge: an undated event can't be shown to be stale, so it
        // renders. (The web reads an unparseable time as epoch, which makes every such event
        // infinitely old and therefore always hidden — the wrong way to fail for a row whose
        // only problem is a missing timestamp.)
        val at = message.date ?: return false
        val spoke = lastSpoke(message, speakers) ?: return true
        // Spoke shortly before: somebody was talking to them, so their leaving is news.
        if (spoke <= at && Duration.between(spoke, at) <= delay) return false
        // Spoke shortly after joining: they arrived and got straight into it, so the join
        // shouldn't read as having been from a lurker. Joins only — there is nothing to reveal
        // about a part or a rename by what the nick says next.
        if (message.type == EventType.Join && unmask > Duration.ZERO && spoke > at &&
            Duration.between(at, spoke) <= unmask
        ) {
            return false
        }
        return true
    }

    /**
     * Whether this event is our own churn, which no rung of the tier hides.
     *
     * `isSelf` doesn't answer it: the server stamps that on messages we *sent*, not on the JOIN
     * it saw us make. Both nicks are checked because our own rename is the one event that
     * straddles the change — whichever of `own-nick` and the `nick` line the store applies
     * first, the other name is the one `ownNick` is holding.
     */
    private fun isOurs(message: Message, ownNick: String?): Boolean {
        val own = ownNick?.lowercase() ?: return false
        return message.nick?.lowercase() == own || message.newNick?.lowercase() == own
    }

    /**
     * When this event's actor last spoke, looked up under **both** of the nicks a rename gives
     * them.
     *
     * A `nick` row is the one event whose actor has two names, and the store carries their
     * speaker entry from the old to the new one as it applies the event — so by the time the
     * row is rendered, the nick printed on it (`nick`, the old one) is the one no longer in the
     * map. Looking that up alone hid the rename of somebody who had just been talking, which is
     * precisely the churn this rung is supposed to keep. The later of the two wins, so neither
     * order of the carry can lose recency.
     */
    private fun lastSpoke(message: Message, speakers: SpeakerMap): Instant? {
        val times = listOf(message.nick, message.newNick).mapNotNull { nick -> nick?.let { speakers[it] } }
        return times.maxOrNull()
    }

    private fun filters(type: EventType): Boolean =
        when (type) {
            EventType.Join -> filtersJoin
            EventType.Part, EventType.Quit, EventType.Chghost -> filtersQuit
            EventType.Nick -> filtersNick
            else -> false
        }
}
