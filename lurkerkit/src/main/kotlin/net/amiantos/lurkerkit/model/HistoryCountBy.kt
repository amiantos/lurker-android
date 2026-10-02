// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * What `limit` counts on a history request (`countBy`, WS_PROTOCOL_FIXES #10).
 *
 * The server sizes a page in stored rows. We render *consolidated* rows, folding each run of
 * join/part/quit/nick/chghost into one summary line (`Consolidation.consolidatableTypes`). On
 * a channel coming back from a netsplit those are wildly different numbers: a hundred stored
 * rows can render as three visible lines, so we'd hydrate, fold it to nothing, notice the page
 * looked short, page again — and the reader would watch the buffer assemble itself.
 *
 * `Renderable` asks the server to spend the budget only on rows that render as their own
 * line. The churn still arrives — consolidation needs the whole run to summarize it accurately
 * — it just no longer eats the page.
 *
 * `Chat` is the same argument one rung stricter, for a reader on the `None` event tier
 * (lurker#666): there we draw nothing for a `mode` row either, so counting it would spend
 * budget on rows that render as nothing.
 */
internal enum class HistoryCountBy(val rawValue: String) {
    /** Every stored row counts. The protocol default, and what an older server does regardless. */
    Event("event"),

    /** Only rows that render standalone count. */
    Renderable("renderable"),

    /** Only conversation counts — `Renderable` minus `mode`. */
    Chat("chat");

    companion object {
        fun fromRawValue(raw: String): HistoryCountBy? = entries.firstOrNull { it.rawValue == raw }

        /**
         * The unit to *ask* for, given what we're going to *render* in.
         *
         * These must match. With `chat.consolidate_joins` off, every event gets its own line, so
         * `Event` is already correct — and asking for `Renderable` there would pull the server's
         * whole scan window (up to 2000 rows) into a page the reader then sees in full. The
         * defaults mirror the registry's, so behavior doesn't shift under the user when settings
         * bootstrap lands a moment after launch.
         *
         * At `None` consolidation is moot — there is nothing left to fold — so the tier decides
         * alone. Mirrors the web's `pageUnitFor()`.
         */
        fun forRendering(settings: Settings): HistoryCountBy {
            if (EventFilter.mode(settings) == EventMode.None) return Chat
            return if (settings.bool("chat.consolidate_joins", default = true)) Renderable else Event
        }
    }
}
