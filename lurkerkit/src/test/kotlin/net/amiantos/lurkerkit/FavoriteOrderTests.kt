// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.FavoriteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dragging a Favorites chip (lurker-ios#53) writes back to a list that is *longer* than the
 * grid — a pinned buffer whose network hasn't connected yet has a stored slot and no chip. The
 * interesting cases are all about that gap, because the failure mode is losing a favorite the
 * user can't currently see, and the only copy of the list is the one being rewritten.
 */
class FavoriteOrderTests {

    @Test
    fun testAMoveWithEverythingVisible() {
        assertEquals(
            listOf("b", "c", "a"),
            FavoriteOrder.moved(listOf("a", "b", "c"), visible = listOf("a", "b", "c"), from = 0, to = 2),
        )
        assertEquals(
            listOf("c", "a", "b"),
            FavoriteOrder.moved(listOf("a", "b", "c"), visible = listOf("a", "b", "c"), from = 2, to = 0),
        )
    }

    @Test
    fun testHiddenFavoritesKeepTheirSlots() {
        // `b` has no chip (its network is still connecting). Dragging `c` above `a` reorders
        // the two chips and leaves `b` exactly where it was — it isn't part of the gesture, so
        // it can't be moved by it, and it certainly can't be dropped.
        val next = FavoriteOrder.moved(
            listOf("a", "b", "c", "d"), visible = listOf("a", "c", "d"), from = 1, to = 0,
        )
        assertEquals(listOf("c", "b", "a", "d"), next)
        assertEquals(4, next.size, "nothing is lost")
        assertEquals(setOf("a", "b", "c", "d"), next.toSet(), "and nothing is invented")
    }

    @Test
    fun testAMoveIsANoOpWhenItLandsWhereItStarted() {
        assertEquals(
            listOf("a", "b"),
            FavoriteOrder.moved(listOf("a", "b"), visible = listOf("a", "b"), from = 1, to = 1),
        )
    }

    @Test
    fun testOutOfRangeIndicesChangeNothing() {
        assertEquals(
            listOf("a", "b"),
            FavoriteOrder.moved(listOf("a", "b"), visible = listOf("a", "b"), from = 5, to = 0),
        )
        assertEquals(
            listOf("a", "b"),
            FavoriteOrder.moved(listOf("a", "b"), visible = listOf("a", "b"), from = 0, to = 5),
        )
        assertEquals(
            emptyList(),
            FavoriteOrder.moved(emptyList(), visible = emptyList(), from = 0, to = 0),
        )
    }

    @Test
    fun testAVisibleKeyTheStoredListDoesNotHaveChangesNothing() {
        // The two lists have drifted — a rebuild raced the drop. Rewriting on a partial match
        // would deal the visible keys into too few slots and leave a stale one behind.
        assertEquals(
            listOf("a", "b"),
            FavoriteOrder.moved(listOf("a", "b"), visible = listOf("a", "b", "ghost"), from = 0, to = 2),
        )
    }

    @Test
    fun testADuplicateInTheStoredListCannotSmuggleAKeyIn() {
        // The case a count-only guard let through: the second "a" contributes a third slot, so
        // the counts agreed while "c" — which was never pinned — had no slot at all. The deal
        // then wrote "c" into the list and destroyed an "a".
        assertEquals(
            listOf("a", "a", "b"),
            FavoriteOrder.moved(listOf("a", "a", "b"), visible = listOf("a", "b", "c"), from = 0, to = 2),
        )
    }

    @Test
    fun testADuplicateInTheVisibleListChangesNothing() {
        // Two chips for one key can't be dealt into one slot. There's no sane answer, so the
        // gesture is refused rather than resolved arbitrarily.
        assertEquals(
            listOf("a", "b"),
            FavoriteOrder.moved(listOf("a", "b"), visible = listOf("a", "a"), from = 0, to = 1),
        )
    }

    // MARK: - Where a hidden favorite sits (the boundary cases)

    @Test
    fun testAHiddenFavoriteAtTheHeadKeepsTheHead() {
        // The documented cost: "drag this to the top" means the top of the *grid*, and a hidden
        // key at stored index 0 still comes back above it. Pinned so the behavior is a decision
        // rather than something a reader discovers on a bug report.
        assertEquals(
            listOf("hidden", "c", "b"),
            FavoriteOrder.moved(listOf("hidden", "b", "c"), visible = listOf("b", "c"), from = 1, to = 0),
        )
    }

    @Test
    fun testAHiddenFavoriteAtTheTailKeepsTheTail() {
        assertEquals(
            listOf("c", "b", "hidden"),
            FavoriteOrder.moved(listOf("b", "c", "hidden"), visible = listOf("b", "c"), from = 0, to = 1),
        )
    }

    @Test
    fun testEveryPairwiseMoveAppliesTheMoveAndKeepsEveryKey() {
        // Two properties, because the first alone is satisfied by doing nothing — which is
        // exactly the branch `moved` takes when it declines a gesture. Asserting only that the
        // set survives would pass against `{ return stored }`, and the test guarding the
        // lose-a-pin failure mode would never have exercised the code that can lose one.
        val stored = listOf("a", "b", "c", "d", "e")
        val visible = listOf("a", "c", "e")
        for (from in visible.indices) {
            for (to in visible.indices) {
                val next = FavoriteOrder.moved(stored, visible = visible, from = from, to = to)
                assertEquals(stored.sorted(), next.sorted(), "move $from→$to changed the set")
                assertEquals("b", next[1], "move $from→$to moved a hidden key")
                assertEquals("d", next[3], "move $from→$to moved a hidden key")

                // And the grid really is in the order the drag drew it.
                val expected = visible.toMutableList()
                expected.add(to, expected.removeAt(from))
                assertEquals(
                    expected, next.filter(visible::contains),
                    "move $from→$to wasn't applied",
                )
            }
        }
    }
}
