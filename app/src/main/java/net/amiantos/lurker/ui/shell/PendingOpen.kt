// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import java.time.Duration
import java.time.Instant

/**
 * A DM this device just asked the server to open, waiting for its row before the app goes there — a
 * profile's Send Message, `/msg`, `/query`. The kit's `PendingDccOpen` rule, for a DM.
 *
 * ⚠ It waits because `open-buffer` is only a request: nothing is minted locally, and the row arrives
 * on the server's answer. A conversation opened before then on a settled roster reads the missing row
 * as a close (`BufferWatch` → Gone) and bounces straight back to the list. A DM that's already listed
 * opens at once, which is nearly always: the wait is only for a brand-new one.
 *
 * Pure, so the rule — and its way to be wrong — is tested here rather than through a navigator.
 */
internal class PendingOpen(val key: BufferKey, val deadline: Instant) {
    sealed interface Outcome {
        data object Waiting : Outcome

        /** Go there — the stored row's key, in the server's spelling of the name. */
        data class Open(val key: BufferKey) : Outcome

        /** Stop waiting, and go nowhere. */
        data object Expired : Outcome
    }

    /**
     * ⚠ The deadline FIRST, as `PendingDccOpen` has it: a row that lands after it belongs to a DM the
     * user has stopped waiting for, and going there would pull them out of whatever they're reading.
     */
    fun settle(buffers: Map<String, Buffer>, now: Instant): Outcome {
        if (now.isAfter(deadline)) return Outcome.Expired
        val row = buffers[key.id] ?: return Outcome.Waiting
        return Outcome.Open(row.key)
    }

    companion object {
        /** The server answers in well under a second; past this, nobody is still watching for it. */
        val patience: Duration = Duration.ofSeconds(15)

        fun of(key: BufferKey, now: Instant = Instant.now()): PendingOpen = PendingOpen(key, now.plus(patience))
    }
}
