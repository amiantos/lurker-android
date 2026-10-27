// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * How tightly the compact layout sits: the three `look.message.compact.*` settings (lurker#1186,
 * Mobile lurker#1224), in the units the registry stores them in. Turning them into dp and sp is the
 * renderer's job, since that needs the font.
 *
 * ⚠ Read as override → registry default. The web also consults the active theme in between, so a
 * density saved into a custom theme only reaches the phone once the apps resolve themes
 * (lurker#1094) — the same gap every other themed key has today.
 */
@ConsistentCopyVisibility
data class CompactDensity private constructor(
    /**
     * Space between blocks, in points. The web puts it before each head line, action, notice, event
     * and divider; the apps put it after the block above, which is the same gap between two blocks,
     * and give a run of status events one gap rather than one each. Rows within a run stay flush.
     */
    val groupGap: Int,
    /** The line height, as a percentage of the font size. */
    val lineHeightPercent: Int,
    /**
     * How far a message body sits in from the nick above it, in characters.
     *
     * ⚠ The web ignores it while `look.message.avatars` is on: beside the gutter's hairline any
     * body indent reads as a stray margin, so the gutter is the indent and the setting does not
     * apply (the registry greys it out, `dependsOn`). That rule is the LIST's, which draws the
     * gutter — `flushBodies`, taken where the column is drawn — not this class's: the history
     * feed reads the same settings and draws no gutter (lurker#1203).
     */
    val indent: Int,
) {
    // Port note: LurkerKit's initialiser clamps, which a data class's constructor can't. [of] is that
    // initialiser, and the constructor (and `copy`) is private, so nothing builds one out of range: a
    // negative gap would reach a `padding` that throws.

    /**
     * This density with the body indent off — for a list drawing the avatar gutter, where the
     * gutter is the indent. One place, so the body, the reply quote, the attachments and the
     * reactions can't disagree about it.
     */
    val flushBodies: CompactDensity
        get() = of(groupGap = groupGap, lineHeightPercent = lineHeightPercent, indent = 0)

    companion object {
        const val groupGapKey = "look.message.compact.group_gap"
        const val lineHeightKey = "look.message.compact.line_height"
        const val indentKey = "look.message.compact.indent"
        /** The avatar column in the compact layout (lurker#607). Default off. */
        const val avatarsKey = "look.message.avatars"

        /** The registry's defaults. */
        val standard = CompactDensity(groupGap = 10, lineHeightPercent = 155, indent = 1)

        /**
         * LurkerKit's memberwise initialiser, clamped to the registry's ranges: the server enforces
         * them on a write, but a stored value from before a range tightened would otherwise lay out
         * as whatever it says.
         */
        fun of(groupGap: Int, lineHeightPercent: Int, indent: Int): CompactDensity =
            CompactDensity(
                groupGap = groupGap.coerceIn(0, 48),
                lineHeightPercent = lineHeightPercent.coerceIn(100, 250),
                indent = indent.coerceIn(0, 8),
            )

        /** LurkerKit's `init(settings:)`. */
        fun of(settings: Settings): CompactDensity =
            of(
                groupGap = settings.int(groupGapKey, default = standard.groupGap),
                lineHeightPercent = settings.int(lineHeightKey, default = standard.lineHeightPercent),
                indent = settings.int(indentKey, default = standard.indent),
            )

        /** Whether the compact layout draws the avatar column (lurker#607). */
        fun avatarsOn(settings: Settings): Boolean = settings.bool(avatarsKey, default = false)
    }
}
