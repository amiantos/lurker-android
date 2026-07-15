// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.model

/**
 * A line in a buffer. Faithful to the server's MessageEvent, trimmed to what a
 * client renders. `id` is the persisted message id (0 for ephemeral events).
 */
data class Message(
    val id: Long,
    val type: EventType,
    val nick: String?,
    val text: String?,
    val self: Boolean = false,
    val time: String? = null,
    /** A highlight rule matched this line — renders as a mention. */
    val matched: Boolean = false,
)

/**
 * The event enum the domain renders against. The server sends far more `type`s
 * than a 1.0 client special-cases (join/part/quit/mode/names/typing/presence/…);
 * everything not yet handled folds into [Other] until a feature needs it.
 */
enum class EventType(val wire: String) {
    Message("message"),
    Action("action"),
    Notice("notice"),
    Error("error"),
    System("system"),
    Join("join"),
    Part("part"),
    Quit("quit"),
    Nick("nick"),
    Kick("kick"),
    Mode("mode"),
    Topic("topic"),
    Other("");

    /** Types that render as someone speaking (vs. a structural/system line). */
    val isSpeech: Boolean get() = this == Message || this == Action || this == Notice

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun from(raw: String?): EventType = byWire[raw] ?: Other
    }
}
