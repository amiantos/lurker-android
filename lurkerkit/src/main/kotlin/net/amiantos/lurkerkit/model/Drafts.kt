// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Duration
import java.time.Instant

/**
 * A draft's reply as the server sends it back (lurker#1021): the line it answers, whether the
 * Reply put `nick: ` into the text, and that line resolved the way a reply's quote is. `parent`
 * is null when the line is gone or no longer one a reply can name — the text stays either way.
 *
 * Port note: `messageId` is a message id, so it is a `Long` here (PORTING.md, Types).
 */
data class DraftReply(
    val messageId: Long,
    val addressed: Boolean,
    val parent: ReplyParent?,
)

/** One buffer's draft on the wire: a `draft-snapshot` entry or a `draft-updated` frame. */
data class DraftEntry(
    val networkId: Int,
    val target: String,
    val body: String,
    val reply: DraftReply?,
    /**
     * Whether the frame said anything about a reply. A server from before replies sends none,
     * and a `draft-updated` without the key leaves the reply we hold alone — absence is not a
     * statement that there is none.
     */
    val carriesReply: Boolean = true,
) {
    val key: BufferKey get() = BufferKey(networkId = networkId, target = target)
}

/**
 * What a buffer's composer holds while you're away from it (lurker-ios#188): the text, and the
 * reply it's being written as. One draft — a reply that came back without its `alice: `, or the
 * `alice: ` without its reply, would go out as something it wasn't.
 *
 * Port note: both fields are `var` in LurkerKit and the struct has no mutating method, so it is
 * an immutable `data class` here (PORTING.md, "Structs that mutate", case 1): a change is a
 * `copy`. It sits in published state (`ChatState.drafts`), where a field changed in place would
 * neither publish nor compare as a change.
 */
data class ComposerDraft(
    val body: String = "",
    val reply: PendingReply? = null,
) {
    /**
     * Nothing worth keeping. A reply with nothing typed yet (on your own line, in a DM) is a
     * draft.
     */
    val isEmpty: Boolean get() = body.isEmpty() && reply == null
}

object Drafts {
    /**
     * How long typing has to pause before the draft goes to the server — the web's 500ms. A
     * burst of keystrokes is one write; a pause between sentences is plenty to persist the line.
     * Leaving the buffer, ending the edit and sending all flush at once.
     */
    val flushDelay: Duration = Duration.ofMillis(500)

    /**
     * Whether this buffer keeps a synced draft. The system buffer has no network to key one to,
     * and the server refuses one for a `:server:` log — both are command consoles, and command
     * typing needn't follow you around.
     *
     * Port note: reads the first UTF-16 unit, where LurkerKit reads the first `Character` — a
     * target opening with a `:` that carries a combining mark syncs there and not here.
     */
    fun syncs(key: BufferKey): Boolean =
        key.networkId != null && !key.target.startsWith(":")

    /**
     * The pending reply a stored draft reply stands for, or null for none — and for a line the
     * server couldn't resolve, which is no reply at all to send.
     *
     * Named as the timeline quotes it (`Replies.shown`, the one rule): a marked relay bot's line
     * as the person inside it — whom the Reply addressed, and whose `nick: ` a cancel takes back
     * — and a line from someone ignored since without their words. It's still a reply to them;
     * the strip just doesn't quote them. The web's `pendingReplyFrom`.
     */
    fun pendingReply(
        reply: DraftReply?,
        networkId: Int,
        target: String,
        ignores: IgnoreSet,
        relayBots: RelayBotSet,
        ownNick: String?,
        now: Instant = Instant.now(),
    ): PendingReply? {
        val parent = reply?.parent ?: return null
        val quote = Replies.shown(
            ReplyContext(msgid = "", parent = parent),
            line = Message(id = 0, type = EventType.Message, nick = null, text = ""),
            networkId = networkId,
            target = target,
            ignores = ignores,
            relayBots = relayBots,
            ownNick = ownNick,
            now = now,
        ).quote
        return PendingReply(
            messageId = reply.messageId,
            nick = quote?.nick ?: parent.nick,
            type = parent.type,
            text = quote?.text ?: "",
            isSelf = quote?.isSelf ?: parent.isSelf,
            addressed = reply.addressed,
        )
    }
}

/**
 * The client half of draft sync: which drafts this device has written and the server hasn't
 * heard yet, and which buffer is mid-IME-composition. Pure bookkeeping — the view model owns
 * the timers and the socket. The web keeps the same two things module-local in `drafts.ts`.
 *
 * Both protect the composer from the server. An unflushed edit is newer than any snapshot or
 * remote update by definition (last write wins), and a composition in flight is newer still —
 * a remote write landing there would repaint the field under a live preedit and destroy the
 * word. And the flush waits for the composition to end, so raw phonetic preedit never becomes
 * the draft every other device sees.
 *
 * Port note: a value type in LurkerKit, with mutating methods of which four return a value
 * (`endComposition`, `take`, `takeAll`, `rekey`). ⚠ Here it is a plain mutable class
 * (PORTING.md, "Structs that mutate", case 3) with exactly one owner, the view model. It must
 * never go into `ChatState` or travel through a flow: a mutation in place neither publishes nor
 * compares as a change. It is not synchronised: every call comes from that owner's thread. And
 * it has no equality — the Swift's `Equatable` compares two copies of a value, and there is only
 * ever the one of these; `Edit` does.
 *
 * Port note: the three maps keep the order their keys first went in, where a Swift dictionary
 * keeps none. Only `takeAll` and `flushableIds` can tell — see there. Read from outside, each
 * map is a copy as it stood at that moment, as reading a Swift dictionary is — never a window
 * onto one a later call will change under the reader.
 *
 * Port note: every map is keyed by `BufferKey.id`, compared by code unit. Checked against the
 * Swift over random sequences of every operation, the two agree step for step except where
 * the ids themselves differ: the final-sigma fold (see `BufferKey.id`), and two targets that
 * differ only in normalisation, which are one key to a Swift dictionary and two here
 * (PORTING.md, Strings).
 */
internal class DraftSync {
    data class Edit(
        val key: BufferKey,
        val draft: ComposerDraft,
        /**
         * When it was made, in edit order. A write that failed late must not put an edit back
         * over a newer one that already went out (`restore`).
         */
        val seq: Int,
    ) {
        /** The same edit, for the buffer under its new name. */
        fun renamed(key: BufferKey): Edit = Edit(key = key, draft = draft, seq = seq)
    }

    private val _unflushed = mutableMapOf<String, Edit>()
    private val _awaitingSnapshot = mutableMapOf<String, Edit>()
    private val _inFlight = mutableMapOf<String, Edit>()

    /**
     * By `BufferKey.id`. An entry stays until a send for it reached a socket — or, with none,
     * until the next connect's snapshot gives it one to go out on.
     */
    val unflushed: Map<String, Edit> get() = _unflushed.toMap()

    /**
     * Edits written to a socket before that socket's `draft-snapshot` — which the server built
     * before it read them, so it says nothing about them. The snapshot keeps them and they go
     * out again behind it.
     */
    val awaitingSnapshot: Map<String, Edit> get() = _awaitingSnapshot.toMap()

    /**
     * Edits handed to a socket whose write hasn't completed. Until it does, nothing says it got
     * out: a background or sign-out flush in that window has to carry them, and a rename has to
     * move them, or a failure would restore one under a name that's gone.
     */
    val inFlight: Map<String, Edit> get() = _inFlight.toMap()

    /** The buffer whose composer is IME-composing, or null. */
    var composing: String? = null
        private set

    /** Each buffer's newest edit, by `seq`. */
    private val latest = mutableMapOf<String, Int>()
    private var nextSeq = 0

    /**
     * Whether a server write for this buffer must be dropped: an edit waiting, a composition,
     * or a write still on its way out.
     *
     * ⚠ In flight counts. Until the socket reports the write sent, the server can't have read
     * it — so any snapshot or `draft-updated` arriving meanwhile was built before it, and the
     * server will end up holding ours. Folding theirs would repaint the field with text the
     * server no longer has, and nothing would ever come back to correct it.
     */
    fun isProtected(id: String): Boolean =
        _unflushed[id] != null || _inFlight[id] != null || composing == id

    val protectedIds: Set<String>
        get() {
            val ids = (_unflushed.keys + _inFlight.keys).toMutableSet()
            composing?.let { ids.add(it) }
            return ids
        }

    /** The draft as this device last wrote it, if the server hasn't heard it yet. */
    fun local(id: String): ComposerDraft? = _unflushed[id]?.draft

    /**
     * Record an edit. `composing` starts or ends that buffer's composition; ending one in a
     * buffer that isn't the composing one leaves the other alone.
     */
    fun edit(key: BufferKey, draft: ComposerDraft, composing: Boolean) {
        nextSeq += 1
        latest[key.id] = nextSeq
        _unflushed[key.id] = Edit(key = key, draft = draft, seq = nextSeq)
        // Superseded: this one goes out behind the snapshot instead.
        _awaitingSnapshot.remove(key.id)
        if (composing) {
            this.composing = key.id
        } else if (this.composing == key.id) {
            this.composing = null
        }
    }

    /** Whether a flush has to wait — the buffer is composing. */
    fun defersFlush(id: String): Boolean = composing == id

    /**
     * End whatever composition is marked — leaving the field and leaving the buffer do, so a
     * missed commit can't hold off remote updates forever. Returns the buffer whose deferred
     * flush is now due, if it has an edit waiting.
     */
    fun endComposition(): BufferKey? {
        val id = composing ?: return null
        composing = null
        return _unflushed[id]?.key
    }

    /** Take a buffer's edit to send it. */
    fun take(id: String): Edit? = _unflushed.remove(id)

    /**
     * Every edit the server may not have, the composing buffer's included — for a flush that
     * can't wait (the app leaving the foreground, sign-out), which takes them all.
     *
     * ⚠ The ones written to a socket still connecting too: nothing says the server read them,
     * and suspension or sign-out ends that socket. Per buffer, the newer of the two wins.
     *
     * Port note: the edits come back in the order their buffers first appear across the three
     * maps — waiting, then sent before the snapshot, then in flight. In LurkerKit their order
     * is whatever the dictionary's happens to be.
     */
    fun takeAll(): List<Edit> {
        val newer: (Edit, Edit) -> Edit = { first, second -> if (first.seq >= second.seq) first else second }
        val edits = LinkedHashMap(_unflushed)
        for ((id, edit) in _awaitingSnapshot) edits[id] = edits[id]?.let { newer(it, edit) } ?: edit
        for ((id, edit) in _inFlight) edits[id] = edits[id]?.let { newer(it, edit) } ?: edit
        _unflushed.clear()
        _awaitingSnapshot.clear()
        _inFlight.clear()
        composing = null
        return edits.values.toList()
    }

    /** An edit was handed to a socket. */
    fun sending(edit: Edit) {
        _inFlight[edit.key.id] = edit
    }

    /**
     * The socket's answer for the write of edit `seq`. A failure puts it back to wait for the
     * next snapshot, under whatever the buffer is called now — unless something newer exists.
     * Nothing to find means a background or sign-out flush already took it.
     */
    fun completed(seq: Int, ok: Boolean) {
        val (id, edit) = _inFlight.entries.firstOrNull { it.value.seq == seq } ?: return
        _inFlight.remove(id)
        if (!ok) restore(edit)
    }

    /**
     * The buffers with an edit waiting, except one whose composition holds its flush.
     *
     * Port note: in the order their edits were first made, where LurkerKit's is whatever the
     * dictionary's happens to be.
     */
    val flushableIds: List<String> get() = _unflushed.keys.filter { it != composing }

    /**
     * Put an edit back that didn't reach the server — unless a newer one has been made since,
     * waiting or already sent. Only the buffer's newest edit is ever worth sending again.
     */
    fun restore(edit: Edit) {
        if (latest[edit.key.id] != edit.seq) return
        if (_unflushed[edit.key.id] == null) _unflushed[edit.key.id] = edit
    }

    /**
     * An edit that was put back reached the server another way: forget it, unless a newer one
     * has replaced it since.
     */
    fun settle(edit: Edit) {
        if (_unflushed[edit.key.id] == edit) _unflushed.remove(edit.key.id)
    }

    /** Note an edit written to a socket whose `draft-snapshot` hasn't arrived. */
    fun sentBeforeSnapshot(edit: Edit) {
        _awaitingSnapshot[edit.key.id] = edit
    }

    /**
     * The snapshot is here: the edits it can't have seen. Each goes back to waiting (if it's
     * still the newest) so it outranks the snapshot and goes out again on this socket.
     */
    fun requeueAwaitingSnapshot() {
        val edits = _awaitingSnapshot.values.toList()
        _awaitingSnapshot.clear()
        for (edit in edits) restore(edit)
    }

    /** Another device wrote this buffer's draft after we did: ours isn't one to send again. */
    fun superseded(id: String) {
        _awaitingSnapshot.remove(id)
    }

    /** A closed buffer's draft goes with it; the server clears its row on the close. */
    fun drop(id: String) {
        _unflushed.remove(id)
        _awaitingSnapshot.remove(id)
        _inFlight.remove(id)
        latest.remove(id)
        if (composing == id) composing = null
    }

    /** Every edit for a network that's gone. */
    fun dropNetworks(keeping: Set<Int>) {
        val doomed = (_unflushed.values.map { it.key } + _awaitingSnapshot.values.map { it.key } + _inFlight.values.map { it.key })
            .toSet()
            .filter { key -> key.networkId?.let { it !in keeping } ?: false }
        for (key in doomed) drop(key.id)
    }

    /**
     * Follow a rename. On a merge the renamed buffer is the one that survives (lurker
     * `renameBuffer.ts`), and the server keeps its draft — adopting the absorbed one's only when
     * it has none. So an edit here moves over whatever the absorbed buffer had waiting.
     *
     * ⚠ Decided once, across all three maps — the buffer's edit is one thing wherever it sits. A
     * source with an edit anywhere replaces the destination's in all of them; per map, an
     * absorbed edit waiting beside a surviving one in flight would be left to win a `takeAll`.
     *
     * ⚠ `survivorHasDraft`: the source's draft as the server already holds it. A survivor whose
     * draft was flushed has nothing pending here, but it still has a draft — the one the merge
     * keeps — and an absorbed edit left waiting would go out under its name and overwrite it.
     *
     * Returns whether the destination's own edit was dropped, so its flush can be cancelled.
     */
    fun rekey(from: BufferKey, to: BufferKey, survivorHasDraft: Boolean = false): Boolean {
        if (from.id == to.id) {
            // Same storage key, new display name: the flush has to name the buffer as it is now.
            _unflushed.setOrRemove(to.id, _unflushed[from.id]?.renamed(to))
            _awaitingSnapshot.setOrRemove(to.id, _awaitingSnapshot[from.id]?.renamed(to))
            _inFlight.setOrRemove(to.id, _inFlight[from.id]?.renamed(to))
            return false
        }
        if (composing == from.id) composing = to.id
        val fromLatest = latest.remove(from.id)
        val movingUnflushed = _unflushed.remove(from.id)
        val movingAwaiting = _awaitingSnapshot.remove(from.id)
        val movingInFlight = _inFlight.remove(from.id)
        // The survivor has no draft at all: the absorbed buffer's edit, if any, is adopted, as the
        // server adopts its draft then. And its `latest` stays, or a failed write of it could no
        // longer be put back.
        val sourcePending = movingUnflushed != null || movingAwaiting != null || movingInFlight != null
        if (!sourcePending && !survivorHasDraft) return false
        val dropped = _unflushed[to.id] != null || _awaitingSnapshot[to.id] != null || _inFlight[to.id] != null
        _unflushed.setOrRemove(to.id, movingUnflushed?.renamed(to))
        _awaitingSnapshot.setOrRemove(to.id, movingAwaiting?.renamed(to))
        _inFlight.setOrRemove(to.id, movingInFlight?.renamed(to))
        latest.setOrRemove(to.id, fromLatest)
        return dropped
    }

    /** Port note: `self = DraftSync()` in LurkerKit; here every field is cleared in place. */
    fun reset() {
        _unflushed.clear()
        _awaitingSnapshot.clear()
        _inFlight.clear()
        composing = null
        latest.clear()
        nextSeq = 0
    }

    /**
     * Port-only. A Swift dictionary's `dict[key] = value?`: store the value, or — given null —
     * remove whatever the key held.
     */
    private fun <V : Any> MutableMap<String, V>.setOrRemove(key: String, value: V?) {
        if (value == null) remove(key) else this[key] = value
    }
}
