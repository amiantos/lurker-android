// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurkerkit.model.OutgoingTyping
import net.amiantos.lurkerkit.model.TypingSignal
import java.time.Instant

/**
 * Our own outgoing composing state (lurker-ios#61): the kit's `OutgoingTyping` decides *what* to
 * send, and this says when the idle downgrade is due. The composer owns only the timer that asks.
 * iOS's `draftChanged` / `endTyping` / `emitTyping`, with the timer lifted out so the schedule is
 * testable without one.
 *
 * The `chat.send_typing_notifications` privacy gate is not here: it lives in
 * `ChatViewModel.setTyping`, at the single point every tag leaves through. `OutgoingTyping` still
 * advances while suppressed, so it believes it said things it didn't — harmless both ways: nothing
 * goes out while the switch is off, and turning it on mid-draft costs at most one refresh window
 * (3s) before the next `active`.
 *
 * What it puts on the wire, for the record (each is a `+typing` TAGMSG to this buffer's target):
 * `active` when a non-command draft first appears and at most every 3s while it keeps changing;
 * `paused` once after 3s with no change; `done` when the draft empties or becomes a `/command`, on
 * send, and on leaving the buffer — never for a buffer you merely passed through. Whether the draft
 * is a command is `OutgoingTyping`'s call, made on the draft's first character, as the send decides
 * it (lurker-ios#210): " /whois bob" goes to the channel as text and is announced, "/whois bob"
 * runs as a command and says nothing, "//shrug" goes to the channel and is announced.
 */
internal class ComposerTyping(private val emit: (TypingSignal) -> Unit) {
    private val outgoing = OutgoingTyping()

    /**
     * The draft as of the last change this heard — what the CHANNEL was told about, so a re-measure
     * or a restore back to the same words isn't news. Null after [ended]: once the claim is over, the
     * next change is news whatever it says. ⚠ Without that reset, typing "hello", having a restore
     * from another device set "hell", and typing the "o" again read as no change at all, and the
     * channel never heard you'd resumed.
     */
    private var lastDraft: String? = null

    /**
     * The draft changed — tell the network if it's news. Returns whether the idle timer should be
     * (re-)armed for [draft] — only while we're claiming to type, since there's nothing to downgrade
     * otherwise; any change re-arms it, so the draft the timer captures is always the latest — or
     * null when this is the draft already heard, which leaves the timer alone.
     */
    fun draftChanged(draft: String, now: Instant): Boolean? {
        if (draft == lastDraft) return null
        lastDraft = draft
        outgoing.draftChanged(draft, now)?.let(emit)
        return outgoing.isSignalling
    }

    /** The idle timer armed for [draft] fired with nothing changed since. */
    fun idled(draft: String, now: Instant) {
        outgoing.idled(draft, now)?.let(emit)
    }

    /** Stop claiming to type — on send, on a restore, and on leaving. Silent when we weren't. */
    fun ended() {
        lastDraft = null
        outgoing.ended()?.let(emit)
    }
}
