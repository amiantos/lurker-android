// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * One entry of the server's recent-speakers list: who spoke in a buffer, and when they last
 * did. Ships on the `backlog` and `history` frames (`wsHub.ts`'s `listSpeakers`), which is the
 * only place it comes from — this is the server's answer, not something derived from the
 * messages a client happens to have loaded.
 */
data class Speaker(
    val nick: String,
    val lastSpoke: Instant,
)

/**
 * One buffer's "who has spoken here lately", keyed by lowercased nick.
 *
 * Feeds two readers, and it matters that they share one map. The smart filter (lurker-ios#63)
 * asks *when* a nick last spoke, to decide whether their join/part/quit/nick line is churn
 * worth hiding; `Consolidation` asks *whether* they're in the set at all, to float people you
 * were just talking to to the front of a truncated summary. Two derivations of "recent
 * speaker" would eventually disagree, and the disagreement would show up as a line the filter
 * hid and the summary still counted.
 *
 * **Seeded from the server, then kept current locally.** The seed is what makes the phone agree
 * with the browser: the server's list is capped at 20 and computed over a fixed scan window, and
 * deriving the map from loaded messages instead would read anyone who spoke before the loaded
 * window as silent — hiding *more* than the web does, in a buffer the reader has scrolled less
 * of. Live messages are recorded as they arrive because that's the half no fetch can supply:
 * the join-unmask rule ("they joined and immediately started talking") is entirely about speech
 * that happens after the seed.
 *
 * Port note: immutable. LurkerKit's `seed`, `record` and `rename` mutate the struct in place;
 * here each returns the updated copy (`speakers = speakers.record(nick, date)`), and returns
 * `this` when there is nothing to change.
 */
@ConsistentCopyVisibility
data class SpeakerMap private constructor(private val lastSpoke: Map<String, Instant>) {

    constructor() : this(emptyMap())

    constructor(speakers: List<Speaker>) : this(SpeakerMap().seed(speakers).lastSpoke)

    /** When `nick` last spoke here, or null if they haven't (within what we know). */
    operator fun get(nick: String): Instant? = lastSpoke[nick.lowercase()]

    /** Everyone in the map, lowercased — what `Consolidation` ranks its truncated name lists by. */
    val nicks: Set<String> get() = lastSpoke.keys.toSet()

    val isEmpty: Boolean get() = lastSpoke.isEmpty()

    /**
     * Apply the server's list, keeping any local entry it doesn't know about or that is newer.
     *
     * A merge rather than a replace, because the two sources answer at different moments: the
     * server's list was computed when it built the frame, and anything said since arrived here
     * as a live event. Replacing wholesale would roll those back — and on a `history` reply
     * (which the client fetches while the buffer is open) that rollback lands mid-conversation,
     * re-hiding the join of somebody who is demonstrably talking.
     */
    fun seed(speakers: List<Speaker>): SpeakerMap =
        speakers.fold(this) { map, speaker -> map.record(nick = speaker.nick, date = speaker.lastSpoke) }

    /**
     * Note that `nick` spoke at `date`. Older-than-known times are ignored: a replayed backlog
     * row must not walk a live entry backwards.
     */
    fun record(nick: String, date: Instant): SpeakerMap {
        val key = nick.lowercase()
        if (key.isEmpty()) return this
        val known = lastSpoke[key]
        if (known != null && known >= date) return this
        return SpeakerMap(trim(lastSpoke + (key to date)))
    }

    /**
     * Carry an entry across a nick change, so someone who spoke and then renamed doesn't read
     * as a stranger when they part. The newer of the two times wins where both exist.
     */
    fun rename(old: String, new: String): SpeakerMap {
        val oldKey = old.lowercase()
        val newKey = new.lowercase()
        if (oldKey.isEmpty() || newKey.isEmpty() || oldKey == newKey) return this
        val carried = lastSpoke[oldKey] ?: return this
        return SpeakerMap(lastSpoke - oldKey).record(nick = newKey, date = carried)
    }

    companion object {
        /**
         * How many nicks one buffer remembers. The web keeps the same number, and for the same
         * reason: the map only ever grows from live traffic, so a channel left open for a day
         * would otherwise accumulate every nick that ever said anything. Well past the server's
         * seed of 20, so a seed never immediately evicts itself.
         */
        const val cap = 128

        /**
         * Evict the least-recent speaker once past the cap. One at a time, because entries only
         * ever arrive one at a time — `seed` records each of its own.
         */
        private fun trim(lastSpoke: Map<String, Instant>): Map<String, Instant> {
            if (lastSpoke.size <= cap) return lastSpoke
            val oldest = lastSpoke.minByOrNull { it.value }?.key ?: return lastSpoke
            return lastSpoke - oldest
        }
    }
}
