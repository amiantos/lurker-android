// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.networks

import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkPreset
import net.amiantos.lurkerkit.support.trimmingWhitespaces

/** One row of the preset picker. */
sealed interface PickerRow {
    data class Preset(val preset: NetworkPreset) : PickerRow

    /** The manual path. Absent entirely on a locked-down instance — see [NetworkPickerModel.rows]. */
    data object Custom : PickerRow
}

/** What the picker shows instead of rows: a title and, for the locked-down blank, why. */
data class PickerPlaceholder(val title: String, val subtitle: String? = null, val symbol: StateSymbol? = null)

/**
 * "Which network?" — the first step of adding one (lurker-ios#11), as rules. The screen is
 * `NetworkPickerPage`; lurker-ios's `NetworkPickerViewController`.
 *
 * It exists because the alternative first step is a blank field asking for a hostname and a port,
 * and the person most likely to be adding their first network is exactly the person who doesn't
 * know that `irc.libera.chat` listens on 6697. Picking a row fills in everything but the nick.
 *
 * **No tags, no popularity sort, no filter chips** — the web's picker has them and a phone has the
 * least room for them. The list is already ordered usefully (networks with a #lurker channel first,
 * then by size) and a search field beats a facet row at reaching one of 95 names.
 */
object NetworkPickerModel {

    /**
     * Said when a locked-down instance leaves the custom row out (as a footer), and when it leaves
     * nothing at all (as the blank's subtitle).
     */
    const val ADMIN_CHOOSES = "This server's administrator chooses which networks can be added."

    /** The search needle: trimmed of spaces (not newlines — iOS's `.whitespaces`) and lowercased. */
    private fun needle(query: String): String = query.trimmingWhitespaces().lowercase()

    /**
     * The presets the search keeps. Host as well as name: someone who knows they want OFTC may well
     * type "oftc.net", and someone pasting a hostname from a wiki should find it.
     */
    fun matches(offered: List<NetworkPreset>, query: String): List<NetworkPreset> {
        val needle = needle(query)
        if (needle.isEmpty()) return offered
        return offered.filter { it.name.lowercase().contains(needle) || it.host.lowercase().contains(needle) }
    }

    /**
     * The rows: the matching presets, then the custom row.
     *
     * ⚠⚠ The custom row only where the instance allows it. With `allowUserDefined` off, the enabled
     * presets are the entire allowed host set, so a custom server would be a form whose save can only
     * 403 — offering it would be promising something the server has already said no to.
     */
    fun rows(offered: List<NetworkPreset>, allowsCustom: Boolean, query: String): List<PickerRow> {
        val rows: MutableList<PickerRow> = matches(offered, query).map { PickerRow.Preset(it) }.toMutableList()
        if (allowsCustom) rows.add(PickerRow.Custom)
        return rows
    }

    /**
     * ⚠ The list CAN be empty, despite the catalogue being on the device: a locked-down instance
     * offers its own networks and nothing else, so an admin who has enabled none leaves nothing to
     * show — and the custom row is suppressed too. A blank screen is the one answer that explains
     * nothing, and it's reachable on a configuration that means "nobody may add a network here",
     * which is exactly when a user needs telling.
     *
     * Two different blanks: nothing on offer at all, versus a search that missed.
     */
    fun placeholder(offered: List<NetworkPreset>, allowsCustom: Boolean, query: String): PickerPlaceholder? {
        if (rows(offered, allowsCustom, query).isNotEmpty()) return null
        val matched = matches(offered, query).isNotEmpty()
        if (!matched && needle(query).isNotEmpty()) return PickerPlaceholder("No matches", symbol = StateSymbol.Search)
        return PickerPlaceholder(title = "No networks available", subtitle = ADMIN_CHOOSES, symbol = StateSymbol.NoNetworks)
    }

    /**
     * Said once, at the bottom, rather than leaving the absence of a custom row to be noticed: a user
     * hunting for the network they use needs to know it isn't missing by accident. Nothing to footnote
     * when there are no rows — the placeholder is saying it instead, and both at once is the same
     * sentence twice.
     */
    fun footer(allowsCustom: Boolean, rows: List<PickerRow>): String? =
        if (!allowsCustom && rows.isNotEmpty()) ADMIN_CHOOSES else null

    /**
     * A preset's second line. The admin's own networks say so, because "why is this one at the top"
     * is otherwise a mystery, and on a locked-down instance it's the whole answer.
     */
    fun subtitle(preset: NetworkPreset): String = if (preset.isInstance) "${preset.host} · offered by this server" else preset.host

    /**
     * The draft a pick opens the form with. Custom is a blank draft, not a half-filled one: "other"
     * means the user is going to type a hostname, and leaving a previous pick's name in the field
     * would be a form that starts out lying about which server it's for.
     */
    fun draft(row: PickerRow): NetworkDraft =
        when (row) {
            is PickerRow.Preset -> row.preset.draft()
            PickerRow.Custom -> NetworkDraft()
        }

    /** The custom row's title. */
    const val CUSTOM_TITLE = "Other Server…"
}
