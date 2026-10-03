// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import net.amiantos.lurker.ui.message.MessageText
import net.amiantos.lurkerkit.rendering.IRCColor
import net.amiantos.lurkerkit.rendering.IRCFormatting

/**
 * A line as plain text for a sheet's header, with every spoiler box still hidden: [shown] masks the
 * box's characters, [spoken] says "hidden spoiler" in its place — what TalkBack hears, as the list's
 * own label does (`MessageText.spokenAnnotated`).
 *
 * ⚠⚠ Not `IRCFormatting.strip`. Stripping drops the colour codes and keeps their text, and the colour
 * IS a spoiler's hiding: the list draws `\u000301,01he was a ghost\u0003` as a solid box, and a
 * stripped header printed "he was a ghost" right above it. A header names which line you pressed — the
 * box's shape is enough for that, and it's what's on screen.
 */
data class SpoilerSafeText(val shown: String, val spoken: String) {
    companion object {
        /** The mask a hidden character is drawn as — a block, as the list's box reads. */
        const val MASK = '█'

        /**
         * [text]'s runs with their codes gone, a hidden run (`FormattingRun.hidesText`, the list's
         * spoiler rule) masked. Every box stays hidden here, even one the reader opened in the list:
         * a header is a glance, not a place to read the secret.
         */
        fun of(text: String): SpoilerSafeText {
            val shown = StringBuilder()
            val spoken = StringBuilder()
            var previousSpoiler: IRCColor? = null
            for (run in IRCFormatting.parse(text)) {
                if (!run.hidesText) {
                    shown.append(run.text)
                    spoken.append(run.text)
                    previousSpoiler = null
                    continue
                }
                // One mask per code point, so the box is as long as the list's.
                repeat(run.text.codePointCount(0, run.text.length)) { shown.append(MASK) }
                // One announcement per box: a bold inside a spoiler splits it into runs of one colour.
                if (previousSpoiler != run.fg) spoken.append(MessageText.HIDDEN_SPOILER)
                previousSpoiler = run.fg
            }
            return SpoilerSafeText(shown.toString(), spoken.toString())
        }
    }
}
