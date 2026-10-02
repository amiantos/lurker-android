// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Consolidation
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Join consolidation — the net-effect collapse ported from the web client's
 * `shared/consolidate.ts`, over the same event set.
 *
 * The classification is load-bearing and easy to get subtly wrong (a join-then-part must
 * read "joined briefly", not "joined"), so the state machine is pinned here rather than
 * left to be eyeballed in the running app.
 */
class ConsolidationTests {

    private val base = Instant.ofEpochSecond(1_700_000_000)

    /** `offset` is in seconds — LurkerKit's `at:`, a `TimeInterval`. */
    private fun msg(
        type: EventType,
        nick: String? = null,
        newNick: String? = null,
        modes: List<ModeChange> = emptyList(),
        offset: Long = 0,
    ): Message =
        Message(
            id = offset + 1, type = type, nick = nick, text = null,
            date = base.plusSeconds(offset), newNick = newNick, modes = modes,
        )

    /** The one summary in a row stream (fails the test if there isn't exactly one). */
    private fun onlySummary(rows: List<Consolidation.Row>): ConsolidationSummary? {
        val summaries = rows.mapNotNull { row -> (row as? Consolidation.Row.Summary)?.summary }
        assertEquals(1, summaries.size, "expected exactly one consolidated summary")
        return summaries.firstOrNull()
    }

    private fun group(
        summary: ConsolidationSummary?,
        kind: ConsolidationSummary.IdentityGroup.Kind,
    ): ConsolidationSummary.IdentityGroup? =
        summary?.groups?.firstOrNull { it.kind == kind }

    private fun nicks(group: ConsolidationSummary.IdentityGroup?): List<String> =
        (group?.visible ?: emptyList()).map { entry ->
            when (entry) {
                is ConsolidationSummary.Entry.Nick -> entry.nick
                is ConsolidationSummary.Entry.Renamed -> entry.to
            }
        }

    /** A mode row carrying member-status changes — the shape the server publishes. */
    private fun modeMsg(signedLetter: String, vararg nicks: String, offset: Long = 0): Message =
        msg(
            EventType.Mode, "ChanServ",
            modes = nicks.map { ModeChange(mode = signedLetter, param = it, kind = ModeChangeKind.Prefix) },
            offset = offset,
        )

    private fun hasSummary(rows: List<Consolidation.Row>): Boolean = rows.any { it is Consolidation.Row.Summary }

    // MARK: - Mode consolidation (lurker#673)

    @Test
    fun testModeRowNoLongerBreaksTheRun() {
        // The case that motivated this: a netsplit rejoin on an auto-op channel used to come
        // out as summary, mode, summary, mode, summary.
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(
                    msg(EventType.Join, "alice"),
                    modeMsg("+o", "alice", offset = 1),
                    msg(EventType.Join, "bob", offset = 2),
                    modeMsg("+o", "bob", offset = 3),
                    msg(EventType.Join, "carol", offset = 4),
                ),
            ),
        )
        assertEquals(listOf("alice", "bob", "carol"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined)))
        assertEquals(listOf("alice", "bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeGranted("o"))))
    }

    @Test
    fun testPresenceGroupsComeBeforeModeGroups() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(msg(EventType.Join, "alice"), modeMsg("+o", "alice", offset = 1)),
            ),
        )
        assertEquals(
            listOf(
                ConsolidationSummary.IdentityGroup.Kind.Joined,
                ConsolidationSummary.IdentityGroup.Kind.ModeGranted("o"),
            ),
            summary?.groups?.map { it.kind },
        )
    }

    @Test
    fun testModesGroupByLetterAndDirection() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(modeMsg("+o", "alice"), modeMsg("+v", "bob", offset = 1), modeMsg("-v", "carol", offset = 2)),
            ),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeGranted("o"))))
        assertEquals(listOf("bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeGranted("v"))))
        assertEquals(listOf("carol"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeRevoked("v"))))
    }

    @Test
    fun testCancelledPairReadsAsBriefly() {
        // The first change implies the prior state, exactly as in the presence walk: an
        // opening `+o` means they did not hold it before, so `+o` then `-o` is the mode-side
        // of joined-and-left rather than a plain deop.
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(modeMsg("+o", "alice"), modeMsg("-o", "alice", offset = 1), msg(EventType.Join, "bob", offset = 2)),
            ),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeBriefly("o"))))
        assertNull(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeRevoked("o")))
    }

    @Test
    fun testRegainedModeReadsAsAgain() {
        // An opening `-o` means they DID hold it before the run.
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(modeMsg("-o", "alice"), modeMsg("+o", "alice", offset = 1), msg(EventType.Join, "bob", offset = 2)),
            ),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeRegranted("o"))))
    }

    @Test
    fun testEveryLetterClassifiesTheSameWayNotJustOp() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(
                    modeMsg("+v", "alice"), modeMsg("-v", "alice", offset = 1),
                    modeMsg("-v", "bob", offset = 2), modeMsg("+v", "bob", offset = 3),
                    modeMsg("+h", "carol", offset = 4), modeMsg("-q", "dave", offset = 5),
                ),
            ),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeBriefly("v"))))
        assertEquals(listOf("bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeRegranted("v"))))
        assertEquals(listOf("carol"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeGranted("h"))))
        assertEquals(listOf("dave"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeRevoked("q"))))
    }

    @Test
    fun testChurnBetweenFirstAndLastIsIgnored() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(
                    modeMsg("+o", "alice"), modeMsg("-o", "alice", offset = 1),
                    modeMsg("+o", "alice", offset = 2), modeMsg("-o", "alice", offset = 3),
                    msg(EventType.Join, "bob", offset = 4),
                ),
            ),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeBriefly("o"))))
    }

    @Test
    fun testMultiTargetMessageFoldsEveryTarget() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(modeMsg("+o", "alice", "bob", "carol"), msg(EventType.Join, "dave", offset = 1)),
            ),
        )
        assertEquals(
            listOf("alice", "bob", "carol"),
            nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeGranted("o"))),
        )
    }

    @Test
    fun testModeTargetDoesNotReachTheIdentityPass() {
        // alice never joined inside the run; being opped must not invent a presence verdict.
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(modeMsg("+o", "alice"), msg(EventType.Join, "bob", offset = 1)),
            ),
        )
        assertEquals(listOf("bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined)))
        assertNull(group(summary, ConsolidationSummary.IdentityGroup.Kind.JoinedAndLeft))
    }

    @Test
    fun testModeGroupsAreCappedLikeAnyOther() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(modeMsg("+o", "a", "b", "c", "d"), msg(EventType.Join, "z", offset = 1)), maxNames = 2,
            ),
        )
        val modeGroup = group(summary, ConsolidationSummary.IdentityGroup.Kind.ModeGranted("o"))
        assertEquals(2, modeGroup?.visible?.size)
        assertEquals(2, modeGroup?.hidden)
    }

    @Test
    fun testALoneModeRowPassesThroughUnconsolidated() {
        // A run of one passes through, so a solitary `+o alice` keeps its narrated line
        // rather than becoming a one-name summary.
        val rows = Consolidation.consolidate(listOf(modeMsg("+o", "alice")))
        assertEquals(1, rows.size)
        assertTrue(rows[0] is Consolidation.Row.Passthrough, "a single mode must not consolidate")
    }

    // MARK: - Mode rows that must NOT fold

    @Test
    fun testABanBreaksTheRun() {
        val ban = msg(
            EventType.Mode, "op",
            modes = listOf(ModeChange(mode = "+b", param = "*!*@host", kind = ModeChangeKind.List)), offset = 1,
        )
        val rows = Consolidation.consolidate(listOf(msg(EventType.Join, "alice"), ban, msg(EventType.Join, "bob", offset = 2)))
        assertEquals(3, rows.size)
    }

    @Test
    fun testAChannelFlagBreaksTheRun() {
        val flag = msg(
            EventType.Mode, "op",
            modes = listOf(ModeChange(mode = "+m", param = null, kind = ModeChangeKind.Chan)), offset = 1,
        )
        val rows = Consolidation.consolidate(listOf(msg(EventType.Join, "alice"), flag, msg(EventType.Join, "bob", offset = 2)))
        assertEquals(3, rows.size)
    }

    @Test
    fun testAMixedMessageBreaksTheRun() {
        // The whole-message gate: one non-prefix change anywhere and the row stands alone, so
        // a ban can never be folded away behind "alice was opped".
        val mixed = msg(
            EventType.Mode, "op",
            modes = listOf(
                ModeChange(mode = "+o", param = "alice", kind = ModeChangeKind.Prefix),
                ModeChange(mode = "-b", param = "*!*@host", kind = ModeChangeKind.List),
            ),
            offset = 1,
        )
        val rows = Consolidation.consolidate(listOf(msg(EventType.Join, "alice"), mixed, msg(EventType.Join, "bob", offset = 2)))
        assertEquals(3, rows.size)
    }

    @Test
    fun testAnUnstampedModeRowBreaksTheRun() {
        // Backlog older than the server-side `kind` stamp. Without the class there is no way
        // to know whether `+q alice` grants ownership or quiets a mask.
        val unstamped = msg(EventType.Mode, "op", modes = listOf(ModeChange(mode = "+o", param = "alice")), offset = 1)
        val rows = Consolidation.consolidate(
            listOf(msg(EventType.Join, "alice"), unstamped, msg(EventType.Join, "bob", offset = 2)),
        )
        assertEquals(3, rows.size)
    }

    @Test
    fun testAModeRowWithNoChangesBreaksTheRun() {
        val empty = msg(EventType.Mode, "op", offset = 1)
        val rows = Consolidation.consolidate(listOf(msg(EventType.Join, "alice"), empty, msg(EventType.Join, "bob", offset = 2)))
        assertEquals(3, rows.size)
    }

    // MARK: - Run detection

    @Test
    fun testALoneJoinPassesThroughUnconsolidated() {
        val rows = Consolidation.consolidate(listOf(msg(EventType.Join, "alice")))
        assertEquals(1, rows.size)
        assertTrue(rows[0] is Consolidation.Row.Passthrough, "a single event must not consolidate")
    }

    @Test
    fun testTwoJoinsConsolidate() {
        val summary = onlySummary(
            Consolidation.consolidate(listOf(msg(EventType.Join, "alice"), msg(EventType.Join, "bob", offset = 1))),
        )
        assertEquals(listOf("alice", "bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined)))
    }

    @Test
    fun testARealMessageBreaksTheRun() {
        // join, chat, join → three lone events, nothing to collapse.
        val rows = Consolidation.consolidate(
            listOf(
                msg(EventType.Join, "alice"), msg(EventType.Message, "alice", offset = 1), msg(EventType.Join, "bob", offset = 2),
            ),
        )
        assertFalse(hasSummary(rows))
    }

    @Test
    fun testKickBreaksTheRun() {
        // kick is an activity line but not consolidatable — it's a discrete event that
        // terminates the run rather than folding in.
        val rows = Consolidation.consolidate(
            listOf(
                msg(EventType.Join, "alice"), msg(EventType.Kick, "op"), msg(EventType.Join, "bob", offset = 2),
            ),
        )
        assertFalse(hasSummary(rows))
    }

    // MARK: - Net effect classification

    @Test
    fun testJoinThenPartReadsAsJoinedBriefly() {
        val summary = onlySummary(
            Consolidation.consolidate(listOf(msg(EventType.Join, "alice"), msg(EventType.Part, "alice", offset = 1))),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.JoinedAndLeft)))
        assertNull(group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined))
    }

    @Test
    fun testPartThenJoinReadsAsReconnected() {
        val summary = onlySummary(
            Consolidation.consolidate(listOf(msg(EventType.Part, "alice"), msg(EventType.Join, "alice", offset = 1))),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Reconnected)))
    }

    @Test
    fun testTwoPartsReadAsLeft() {
        val summary = onlySummary(
            Consolidation.consolidate(listOf(msg(EventType.Quit, "alice"), msg(EventType.Part, "bob", offset = 1))),
        )
        assertEquals(listOf("alice", "bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Left)))
    }

    // MARK: - Renames

    @Test
    fun testPureRenamesReadAsRenamed() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(
                    msg(EventType.Nick, "alice", newNick = "alice_afk"),
                    msg(EventType.Nick, "bob", newNick = "bob_afk", offset = 1),
                ),
            ),
        )
        val renamed = group(summary, ConsolidationSummary.IdentityGroup.Kind.Renamed)
        assertEquals(2, renamed?.visible?.size)
        // The identity carries both ends of the rename.
        val first = renamed?.visible?.firstOrNull() as? ConsolidationSummary.Entry.Renamed
            ?: fail("expected a rename entry")
        assertEquals("alice", first.from)
        assertEquals("alice_afk", first.to)
    }

    @Test
    fun testARenameFollowsTheIdentityThroughAJoin() {
        // alice joins, then renames to alice2 → one identity, present under its final name.
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(
                    msg(EventType.Join, "alice"), msg(EventType.Nick, "alice", newNick = "alice2", offset = 1),
                ),
            ),
        )
        assertEquals(listOf("alice2"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined)))
        assertNull(group(summary, ConsolidationSummary.IdentityGroup.Kind.Renamed))
    }

    // MARK: - Capping

    @Test
    fun testOverflowCollapsesToAndNOthers() {
        val joins = (0 until 7).map { msg(EventType.Join, "user$it", offset = it.toLong()) }
        val summary = onlySummary(Consolidation.consolidate(joins, maxNames = 5))
        val joined = group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined)
        assertEquals(5, joined?.visible?.size)
        assertEquals(2, joined?.hidden)
    }

    // MARK: - Mode is not consolidatable

    @Test
    fun testModeBreaksTheRun() {
        // The netsplit-auto-op shape: join, +o, join. Being opped is consequential in a way
        // join churn isn't, so the mode stands on its own line and splits the run rather
        // than folding in — matching the web's CONSOLIDATABLE_TYPES.
        val rows = Consolidation.consolidate(
            listOf(
                msg(EventType.Join, "alice"),
                msg(EventType.Mode, "chan", modes = listOf(ModeChange(mode = "+o", param = "alice")), offset = 1),
                msg(EventType.Join, "bob", offset = 2),
            ),
        )
        assertEquals(3, rows.size)
        assertFalse(hasSummary(rows))
    }

    @Test
    fun testTwoModesDoNotConsolidate() {
        val rows = Consolidation.consolidate(
            listOf(
                msg(EventType.Mode, "chan", modes = listOf(ModeChange(mode = "+o", param = "alice"))),
                msg(EventType.Mode, "chan", modes = listOf(ModeChange(mode = "+o", param = "bob")), offset = 1),
            ),
        )
        assertEquals(2, rows.size)
        assertFalse(hasSummary(rows))
    }

    // MARK: - chghost

    @Test
    fun testTwoChghostsReadAsRehosted() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(msg(EventType.Chghost, "alice"), msg(EventType.Chghost, "bob", offset = 1)),
            ),
        )
        assertEquals(listOf("alice", "bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Rehosted)))
    }

    @Test
    fun testChghostIsTransparentToAJoin() {
        // Post-netsplit each rejoining user emits JOIN then CHGHOST as they identify to
        // services. That must read as a plain "joined" — not "alice joined; alice changed
        // host", which would say the same churn twice (lurker#593).
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(msg(EventType.Join, "alice"), msg(EventType.Chghost, "alice", offset = 1)),
            ),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined)))
        assertNull(group(summary, ConsolidationSummary.IdentityGroup.Kind.Rehosted))
    }

    @Test
    fun testARenameOutranksARehost() {
        // No presence change, so it comes down to which says more: "alice → alice2" does.
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(msg(EventType.Chghost, "alice"), msg(EventType.Nick, "alice", newNick = "alice2", offset = 1)),
            ),
        )
        assertEquals(listOf("alice2"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Renamed)))
        assertNull(group(summary, ConsolidationSummary.IdentityGroup.Kind.Rehosted))
    }

    @Test
    fun testChghostJoinsARunWithJoinsAndParts() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(
                    msg(EventType.Join, "alice"), msg(EventType.Chghost, "bob", offset = 1),
                    msg(EventType.Quit, "carol", offset = 2),
                ),
            ),
        )
        assertEquals(listOf("alice"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Joined)))
        assertEquals(listOf("bob"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Rehosted)))
        assertEquals(listOf("carol"), nicks(group(summary, ConsolidationSummary.IdentityGroup.Kind.Left)))
    }

    // MARK: - Summary metadata

    @Test
    fun testSummaryTimestampIsTheLastEvent() {
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(msg(EventType.Join, "alice", offset = 0), msg(EventType.Join, "bob", offset = 30)),
            ),
        )
        assertEquals(base.plusSeconds(30), summary?.date)
    }

    @Test
    fun testSummarySpansItsEventIds() {
        // The id span is what lets the scroll anchor re-find a line after a history page
        // merges it into a summary. `msg` stamps id = offset + 1, so this run spans 1…31.
        val summary = onlySummary(
            Consolidation.consolidate(
                listOf(msg(EventType.Join, "alice", offset = 0), msg(EventType.Join, "bob", offset = 30)),
            ),
        )
        assertEquals(1L, summary?.firstId)
        assertEquals(31L, summary?.lastId)
    }
}
