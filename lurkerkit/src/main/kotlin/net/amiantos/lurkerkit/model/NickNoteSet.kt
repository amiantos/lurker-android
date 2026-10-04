// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/** One free-form note about a nick on a network — "lives in Berlin", "spouse: Pat". */
data class NickNote(
    /**
     * The nick in its stored casing. Lookups fold case (the server's column collates NOCASE
     * so a case-flip doesn't fragment the row), so this is only what to *show*.
     */
    val nick: String,
    val note: String,
    /**
     * When the note was last written. Null when the server didn't say — and always null on a
     * clear, where there's no row left to have been updated.
     */
    val updatedAt: Instant? = null,
) {
    companion object {
        /**
         * The server's cap (`setNickNote.ts`), in UTF-16 code units — JavaScript's `length`, and
         * what the web's `maxlength` counts. Past it the server cuts silently, and can cut an emoji
         * in half, so the editors refuse to grow a note beyond it instead (sweep L14).
         */
        const val maxLength = 4096

        /** Whether `note` is short enough to be stored as typed. */
        fun fits(note: String): Boolean = note.length <= maxLength
    }
}

/**
 * The account's nick notes, per network.
 *
 * Network-scoped because the same nick on two networks may be two people — the same keying
 * the server uses (`user_nick_notes` is `(user_id, network_id, nick)`) and the same one
 * `RelayBotSet` and `IgnoreSet` use.
 *
 * Server-authoritative, exactly like `RelayBotSet`: writing a note here *asks*, and the note
 * exists once the server fans a `nick-note-updated` back to every device. **Nothing writes to
 * this type but a frame** — replaced whole by `snapshot`, patched a nick at a time by
 * `nick-note-updated`, never mutated in place by the editor that caused the change. A note the
 * server refuses simply never appears.
 *
 * **Because it is only ever replaced, `===` is a valid test for "the notes changed"** — what
 * lets a `distinctUntilChanged` predicate compare it with a reference test rather than walking
 * every note on every frame the socket delivers.
 *
 * Port note: a reference type in LurkerKit too (a `final class`, not a struct), and for the
 * reason above — so this is a plain class with no `equals`, deliberately not a `data class`.
 * Two sets holding the same notes are not `==`.
 */
class NickNoteSet(byNetwork: Map<Int, List<NickNote>> = emptyMap()) {
    /** networkId → folded nick → note. */
    private val byNetwork: Map<Int, Map<String, NickNote>> = buildMap {
        for ((networkId, entries) in byNetwork) {
            // ⚠ An empty note is the server's spelling of "no note" — `set_nick_note` deletes
            // the row for an empty string rather than storing one. Dropping them here means a
            // cleared note can't survive as a present-but-blank entry that `hasNote` would
            // answer yes to.
            val notes = entries.filter { it.nick.isNotEmpty() && it.note.isNotEmpty() }
            if (notes.isEmpty()) continue
            put(networkId, notes.associateBy { it.nick.lowercase() })
        }
    }

    /**
     * The note about `nick` on `networkId`, or null when there isn't one. A null network is the
     * app-scoped system buffer, where there is nobody to have a note about.
     */
    fun note(networkId: Int?, nick: String?): NickNote? {
        if (networkId == null || nick == null || nick.isEmpty()) return null
        return byNetwork[networkId]?.get(nick.lowercase())
    }

    /**
     * Whether there is anything written about this nick — for a marker beside them in a list,
     * where the text itself doesn't fit.
     */
    fun hasNote(networkId: Int?, nick: String?): Boolean =
        note(networkId = networkId, nick = nick) != null

    /**
     * This set with one note written or cleared — how a `nick-note-updated` frame folds in.
     *
     * The frame carries one nick rather than a network's whole list, so this patches a bucket
     * rather than replacing it (where it differs from `IgnoreSet.replacing`, whose frame ships
     * a scope at a time).
     *
     * An empty `note` is a **delete**, which is the server's own rule rather than a convention
     * invented here: `setNickNote.ts` deletes the row for an empty string and echoes back
     * `note: ''`, so the clear and the write arrive as the same frame shape.
     */
    fun applying(networkId: Int, nick: String, note: String, updatedAt: Instant?): NickNoteSet {
        if (nick.isEmpty()) return this
        val notes = (byNetwork[networkId]?.values?.toMutableList() ?: mutableListOf())
        notes.removeAll { it.nick.lowercase() == nick.lowercase() }
        if (note.isNotEmpty()) notes.add(NickNote(nick = nick, note = note, updatedAt = updatedAt))
        val next = byNetwork.mapValues { it.value.values.toList() }.toMutableMap()
        next[networkId] = notes
        return NickNoteSet(byNetwork = next)
    }

    /**
     * This set with everything about one network forgotten — the local half of a network
     * delete, matching what `LurkerStore.dropNetwork` does to every other network-keyed slot.
     */
    fun removing(networkId: Int): NickNoteSet {
        if (byNetwork[networkId] == null) return this
        val next = byNetwork.mapValues { it.value.values.toList() }.toMutableMap()
        next.remove(networkId)
        return NickNoteSet(byNetwork = next)
    }

    companion object {
        /**
         * No notes at all — a fresh session, a signed-out one, and every account that has never
         * written one, which is most of them.
         */
        val empty = NickNoteSet()
    }
}
