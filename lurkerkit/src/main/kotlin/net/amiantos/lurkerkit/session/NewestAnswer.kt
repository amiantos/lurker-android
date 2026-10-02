// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.session

/**
 * Which of several overlapping reads of the same thing may land: an answer is applied unless a
 * newer one already was.
 *
 * ⚠ The newest ANSWER wins, not the newest request. A read that fails says nothing, so it must
 * not discard an older read that succeeds. For `/api/config` it did: a failed read during a
 * network flap threw away the older answer that the server takes this build again, and the
 * refusal banner stayed up until the next foreground (lurker-ios#17).
 *
 * Port note: a `struct` with `mutating` methods that return a value in LurkerKit, so a plain
 * class here (PORTING.md, "Structs that mutate", case 3). It has one owner, and it is not
 * synchronised: every call comes from that owner's thread.
 */
internal class NewestAnswer {
    private var started = 0
    private var landed = 0

    /** Call when a read starts, and hand the ticket to `accept` when it answers. */
    fun start(): Int {
        started += 1
        return started
    }

    /** Whether the answer to `ticket` is still news. Accepting it refuses every older ticket after. */
    fun accept(ticket: Int): Boolean {
        if (ticket <= landed) return false
        landed = ticket
        return true
    }

    /**
     * Refuse every read already out, as if a newer answer had just landed. For an answer that came
     * another way: a 426 on the socket is newer than any `/api/config` read started before it, and
     * one of those saying "compatible" must not clear the refusal (lurker-ios#17).
     */
    fun supersedeInFlight() {
        landed = started
    }
}
