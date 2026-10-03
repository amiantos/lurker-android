// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurkerkit.support.isInWhitespacesAndNewlines

/**
 * Text dropped into the field from outside it — how a finished upload's link lands (lurker-ios#14),
 * and the uploads browser's Add to Message. lurker-ios's `ComposerBar.insert(_:atCaret:)`, pure.
 *
 * A space goes before the text when it would otherwise weld onto the preceding word, and one after it
 * so the caret sits ready for a caption. The user then edits and sends: an upload produces a link, it
 * doesn't send one, which keeps send-control where IRC wants it (a message is a URL plus whatever you
 * say about it).
 */
internal object ComposerInsert {
    /**
     * The field after the insert: its text, the selection to put back (a caret when `start == end`),
     * and whether the keyboard comes up for it.
     */
    data class Result(val text: String, val selectionStart: Int, val selectionEnd: Int, val focuses: Boolean)

    /**
     * Insert [payload] into [text] whose selection is [selectionStart]..[selectionEnd].
     *
     * `atCaret = false` appends at the end instead, and leaves the keyboard alone. That is for the
     * *second and later* links of a multi-file upload, which arrive minutes apart while the user may
     * well be typing the caption: splicing each one wherever the caret happens to be would cut their
     * sentence in half, and raising the keyboard every time would shove it back up over a run they'd
     * stopped watching.
     *
     * An append still carries the caret along **when it was sitting at the end** — which it is in the
     * ordinary case, because that's where the previous insert left it. Pinning it regardless left every
     * later caption going in behind the first link. Only a caret the user moved *into* the text is one
     * they're using, and that is the only one worth protecting.
     */
    fun insert(text: String, selectionStart: Int, selectionEnd: Int, payload: String, atCaret: Boolean): Result {
        val length = text.length
        val start = if (atCaret) selectionStart.coerceIn(0, length) else length
        val end = if (atCaret) selectionEnd.coerceIn(start, length) else length
        var inserted = payload
        if (start > 0 && !text[start - 1].isInWhitespacesAndNewlines()) inserted = " $inserted"
        inserted += " "
        val result = text.substring(0, start) + inserted + text.substring(end)
        val caret = start + inserted.length
        if (atCaret) return Result(result, caret, caret, focuses = true)
        // A caret resting at the end isn't one the user is working at — it rides along to the new end.
        // Anywhere else, or a live selection, is theirs and is put back.
        val trailing = selectionStart == selectionEnd && selectionStart >= length
        return if (!trailing && selectionEnd <= result.length) {
            Result(result, selectionStart, selectionEnd, focuses = false)
        } else {
            Result(result, caret, caret, focuses = false)
        }
    }
}
