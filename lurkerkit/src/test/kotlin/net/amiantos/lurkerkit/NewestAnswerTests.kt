// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.session.NewestAnswer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Overlapping reads of `/api/config`: which answer is allowed to land. */
class NewestAnswerTests {

    @Test
    fun testAnOlderAnswerLandingLastIsRefused() {
        val reads = NewestAnswer()
        val older = reads.start()
        val newer = reads.start()
        assertTrue(reads.accept(newer))
        assertFalse(reads.accept(older), "it would undo the newer answer")
    }

    /**
     * The edge `/code-review` found on lurker-ios#17: keyed on the newest request, a newer read
     * that failed discarded the older one that succeeded, and a stale refusal stayed on screen.
     */
    @Test
    fun testANewerReadThatFailsDoesNotDiscardAnOlderAnswer() {
        val reads = NewestAnswer()
        val older = reads.start()
        reads.start() // fails, so it never answers
        assertTrue(reads.accept(older))
    }

    /**
     * Copilot on lurker-ios#171: a compatible `/api/config` answer to a read sent before a 426
     * cleared the refusal the 426 had just set, and the app tried the socket again.
     */
    @Test
    fun testAnAnswerFromElsewhereRefusesReadsAlreadyOut() {
        val reads = NewestAnswer()
        val before = reads.start()
        reads.supersedeInFlight()
        assertFalse(reads.accept(before))
        val after = reads.start()
        assertTrue(reads.accept(after), "a read started afterwards can still land")
    }

    @Test
    fun testAnswersThatLandInOrderAllApply() {
        val reads = NewestAnswer()
        val first = reads.start()
        assertTrue(reads.accept(first))
        val second = reads.start()
        assertTrue(reads.accept(second))
    }
}
