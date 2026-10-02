// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * Which composer line a `send-result` is answering.
 *
 * The server acknowledges a send by echoing back the `clientId` the request carried, and nothing
 * else — not the buffer, not the text. So the correlation has to be held here, and it is the only
 * thing that knows how to give a refused line back to the person who typed it (lurker-ios#128).
 *
 * ⚠⚠ A separate type, and pure, because the alternative is a map inside `ChatViewModel`
 * reachable only through a private frame handler — untestable, which is how the last round of
 * this subsystem shipped a rule nobody could exercise. The invariants below are small and each
 * one has a way to be wrong; they belong somewhere a test can drive them.
 *
 * Port note: a `struct` with `mutating` methods in LurkerKit, two of which return a value, so a
 * plain mutable class here (PORTING.md, "Structs that mutate", case 3). It has one owner (the
 * view model), it never goes into `ChatState`, and it is not synchronised: every call comes from
 * that owner's thread. It has no equality, as the Swift has none; `Origin` does.
 */
internal class UnsentCorrelator {

    /**
     * The buffer a line was TYPED IN, and the line AS TYPED.
     *
     * ⚠⚠ Neither is recoverable from the wire verb, which is the whole reason to keep them.
     * `/msg bob hi` typed in `#chat` puts `hi` on the wire addressed to `bob` — so the verb's
     * target is the DM, while the composer that should get the text back belongs to `#chat`, and
     * the text to give back is the whole `/msg bob hi` rather than the `hi` that went out.
     * Restoring the payload to the destination would strand a fragment in the wrong conversation.
     */
    data class Origin(
        val key: BufferKey,
        val line: String,
        /**
         * The reply the line went out as (lurker-ios#184), given back with it — a refused reply
         * that came home as a plain line would go out the second time looking like one it wasn't.
         */
        val reply: PendingReply? = null,
    )

    private val inFlight = mutableMapOf<String, Origin>()
    private var seq = 0

    /** Whether anything is waiting on an answer. Test seam for the leak the removals prevent. */
    val pendingCount: Int get() = inFlight.size

    /**
     * Mint a correlator for one composer line and remember where it came from.
     *
     * ⚠ Per LINE, not per wire verb. One command can put several sends on the wire, and the
     * caller is expected to share this id across them: there is one line in the composer to give
     * back, so it should come back once.
     *
     * A counter rather than a UUID — it only has to be unique within a socket's lifetime, and a
     * readable id is worth something in a frame log.
     *
     * Port note: `android-` where LurkerKit mints `ios-`. The server only echoes the id back,
     * so nothing depends on the prefix; it is there for whoever reads a frame log, and should
     * name the client that sent the line.
     */
    fun track(key: BufferKey, line: String, reply: PendingReply? = null): String {
        seq += 1
        val id = "android-$seq"
        inFlight[id] = Origin(key = key, line = line, reply = reply)
        return id
    }

    /**
     * Answer a `send-result`: the line to give back, or null if there is nothing to do.
     *
     * ⚠⚠ Forgets the entry on EITHER verdict. A success has nothing to restore, but leaving its
     * entry behind grows the map for the life of the socket — and the second refusal of a line
     * whose siblings already resolved must not restore it twice.
     *
     * Null for an unknown id, which covers both the second answer to a multi-send line and any
     * ack this client never asked for.
     */
    fun resolve(clientId: String?, ok: Boolean): Origin? {
        if (clientId == null) return null
        val origin = inFlight.remove(clientId) ?: return null
        return if (ok) null else origin
    }

    /**
     * Follow a buffer rename, so a line still in flight comes home to the surviving buffer.
     *
     * ⚠⚠ An `Origin` captured before a rename holds the OLD key, and a rename landing between a
     * send and its ack is exactly when this matters. The screen follows the rename, so it only
     * ever asks for the new key — a hold written under the old one is unreachable forever and
     * the line is lost silently. `ChatState.rekeyBuffer` moves the holds already written; this
     * moves the ones not written yet, and both are needed to cover the window.
     *
     * ⚠ Collected before mutating. Writing into `inFlight` while iterating it is actually
     * well-defined in Swift — a Dictionary is a value type, so the walk stays on an intact
     * snapshot — but it reads like the bug it is in most other languages, this one among them,
     * and a reader should not have to know which writes a map tolerates mid-walk to be sure.
     * Two passes cost nothing at this size and ask nothing of them: `filter` copies the matches
     * into a map of their own, and the writes go to `inFlight` after it.
     *
     * Port note: `from` is matched by `BufferKey`'s equality, as in LurkerKit — exact casing, not
     * the folded `id` — and by code unit, where Swift's is by canonical equivalence: a line
     * typed in `#é` follows a rename from `#e` + U+0301 on iOS and not here (PORTING.md, Strings).
     */
    fun rekey(from: BufferKey, to: BufferKey) {
        val moving = inFlight.filter { it.value.key == from }
        for ((id, origin) in moving) {
            inFlight[id] = Origin(key = to, line = origin.line, reply = origin.reply)
        }
    }

    /**
     * Give up on everything outstanding — the socket died, so no answer is coming.
     *
     * ⚠⚠ Deliberately does NOT hand the lines back, and that is a choice between two bad
     * outcomes. A send the socket died under may or may not have reached IRC; the ack is exactly
     * what would have said, and it is what is not coming. Restoring risks the user sending the
     * same line twice, to a channel, with no way to take it back. Not restoring risks losing a
     * line. Duplicate-in-public is the worse one, and it is the call the web makes too.
     */
    fun abandonAll() {
        inFlight.clear()
    }
}
