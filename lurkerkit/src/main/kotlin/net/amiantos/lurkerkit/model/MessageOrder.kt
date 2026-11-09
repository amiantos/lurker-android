// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * The order a buffer's rows are shown in: by the time the server stamped them, with id as the
 * tiebreak (lurker#1290; the web's `lib/messageOrder`, lurker-ios's `MessageOrder`).
 *
 * Ids are the order rows were STORED in, and the two agree for everything heard live. They part
 * on a line fetched after the fact (`replay`, lurker#1283): a gap filled from the network's
 * history after a reconnect holds lines from before the rejoin, stored after its JOIN row with
 * fresh ids, and shown by id they would sit under the join they precede. Ids stay what they are
 * everywhere else: the paging cursor, the dedupe key, the unread boundary (CLIENT_PROTOCOL §9).
 * The store's list is never reordered; this is applied where the rows are built.
 */
object MessageOrder {
    private class Key(val date: Instant, val id: Long, val offset: Int) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val byDate = date.compareTo(other.date)
            if (byDate != 0) return byDate
            val byId = id.compareTo(other.id)
            // The offset is the last word, so rows that tie keep the order they were held in.
            return if (byId != 0) byId else offset.compareTo(other.offset)
        }
    }

    /** A row's id as it sorts: one with no id (an ephemeral) is last among rows of its time. */
    private fun sortId(message: Message): Long = if (message.id != 0L) message.id else Long.MAX_VALUE

    /**
     * [rows] in display order. The list itself when it already is, which is every buffer that
     * holds nothing replayed, so a build costs one pass and allocates nothing.
     *
     * A row with no clock (a line the app wrote itself, `appendLocal`) has nothing to be placed
     * by, so it takes the latest key among the rows held before it: it stays under everything
     * that was on screen when it was written. Not the key of the row before it, which can be a
     * line fetched after the fact, shown far above.
     */
    fun byTime(rows: List<Message>): List<Message> {
        if (isOrdered(rows)) return rows
        val keys = ArrayList<Key>(rows.size)
        var latest: Key? = null
        for ((offset, message) in rows.withIndex()) {
            val date = message.date
            val next = if (date == null) {
                Key(latest?.date ?: Instant.MIN, latest?.id ?: 0L, offset)
            } else {
                Key(date, sortId(message), offset)
            }
            keys.add(next)
            if (latest == null || next > latest) latest = next
        }
        return keys.sorted().map { rows[it.offset] }
    }

    /** While the rows are in order, the latest key so far is the previous dated row's. */
    private fun isOrdered(rows: List<Message>): Boolean {
        var lastDate: Instant? = null
        var lastId = 0L
        for (message in rows) {
            val date = message.date ?: continue
            val id = sortId(message)
            if (lastDate != null) {
                val byDate = lastDate.compareTo(date)
                if (byDate > 0 || (byDate == 0 && lastId > id)) return false
            }
            lastDate = date
            lastId = id
        }
        return true
    }
}
