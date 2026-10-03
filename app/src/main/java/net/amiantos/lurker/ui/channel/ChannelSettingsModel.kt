// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.channel

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ChannelAccess
import net.amiantos.lurkerkit.model.ChannelModeDrafts
import net.amiantos.lurkerkit.model.ChannelModeForm
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.OutgoingModeChange
import net.amiantos.lurkerkit.model.channelAccess
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.support.Result
import java.time.Instant

/** What the channel settings page draws from the store — only this channel's slice (iOS's `Slice`). */
data class ChannelSlice(
    val modes: ChannelModeState?,
    val topic: String?,
    val access: ChannelAccess,
) {
    companion object {
        fun of(state: ChatState, key: BufferKey): ChannelSlice =
            ChannelSlice(modes = state.channelModes[key.id], topic = state.buffers[key.id]?.topic, access = state.channelAccess(key))
    }
}

/** What Save would send, or why it can't. */
data class PendingSave(val changes: List<OutgoingModeChange>, val topic: String?, val error: String?) {
    /** Something to send, or a problem to show — iOS enables Save for either, so the tap can explain. */
    val actionable: Boolean get() = error != null || topic != null || changes.isNotEmpty()
}

/** One row of the channel settings page, as iOS's `Content`, with its identity (iOS's `ItemID`). */
sealed interface SettingsItem {
    val id: String

    /** The topic, editable. */
    data class TopicField(val text: String) : SettingsItem {
        override val id: String get() = "topicField"
    }

    /** The topic, read-only — or a sentence where there is none to show. */
    data class TopicText(val text: String, val muted: Boolean) : SettingsItem {
        override val id: String get() = "topicText"
    }

    data class Notice(val key: String, val text: String) : SettingsItem {
        override val id: String get() = "notice:$key"
    }

    /** A mode's switch. */
    data class Toggle(val letter: String, val label: String, val on: Boolean, val enabled: Boolean) : SettingsItem {
        override val id: String get() = "toggle:$letter"
    }

    /** A param mode's value — present while its switch is on. */
    data class Value(
        val letter: String,
        val label: String,
        val value: String,
        val isKey: Boolean,
        val placeholder: String,
        val enabled: Boolean,
        /**
         * The channel has a key we don't know (`+k`, field empty). Said under the field rather than
         * as a placeholder, which only shows while focused. Always false for anything but the key.
         */
        val keySet: Boolean = false,
    ) : SettingsItem {
        override val id: String get() = "value:$letter"
    }
}

data class SettingsFooter(val text: String, val isError: Boolean = false)

data class SettingsSection(val id: String, val header: String?, val footer: SettingsFooter?, val items: List<SettingsItem>)

/**
 * A channel's topic and modes (lurker-ios#187, the app half of lurker#727) — iOS's
 * `ChannelSettingsViewController`, pure. The form logic itself (rows from the spec, the diff, the
 * drafts) is the kit's `ChannelModeForm`/`ChannelModeDrafts`; this is the page around it.
 *
 * Everything is drawn from the network's `modeSpec`, so a network's own modes all show up, named
 * where the letter means the same thing on every ircd and as `+X` where it doesn't. Everyone can read
 * it; editing modes takes op or higher, the topic halfop or higher under `+t`. The server has the last
 * word, and its refusal shows here.
 */
object ChannelSettingsModel {
    const val TITLE = "Channel Settings"

    /**
     * The channel's key: the newest `±k` seen since opening (a `-k` means there's none, so a key the
     * config still remembers doesn't come back), else the config's copy. A `+k` whose value was hidden
     * suppresses both — see `ChannelModeForm.lastKeyChange`.
     */
    fun storedKey(modeRowsSeen: List<Message>, configKey: String?): String? =
        when (val sighting = ChannelModeForm.lastKeyChange(modeRowsSeen)) {
            is ChannelModeForm.KeySighting.Set -> sighting.key
            ChannelModeForm.KeySighting.Removed, ChannelModeForm.KeySighting.SetUnknown -> null
            ChannelModeForm.KeySighting.None -> configKey
        }

    /**
     * The channel's modes as the form diffs against them. ⚠ The key goes in only while the channel
     * has one: switching +k back on must start empty, not quietly re-send an old one. (324 never
     * carries the key — the server can't say it, and InspIRCd masks it as `<key>` — so the known key
     * is the config's or a live row's, never the mode state's.)
     */
    fun live(held: ChannelModeState?, storedKey: String?): ChannelModeForm.Live {
        val modes = held?.modes ?: ""
        val params = held?.params.orEmpty().toMutableMap()
        if (modes.contains("k") && storedKey != null) params["k"] = storedKey
        return ChannelModeForm.Live(modes = modes, params = params)
    }

    fun pending(access: ChannelAccess, live: ChannelModeForm.Live, drafts: ChannelModeDrafts, liveTopic: String): PendingSave {
        var changes: List<OutgoingModeChange> = emptyList()
        var error: String? = null
        val spec = access.spec
        if (access.canEditModes && spec != null) {
            when (val result = ChannelModeForm.changes(spec = spec, live = live, draft = drafts.rows)) {
                is Result.Success -> changes = result.value
                is Result.Failure -> error = result.error.message
            }
        }
        val topic = if (access.canSetTopic) drafts.topicChange(live = liveTopic) else null
        val limit = spec?.topicLen
        if (error == null && topic != null && limit != null && ChannelModeForm.topicBytes(topic) > limit) {
            error = "The topic is over the network's $limit-byte limit."
        }
        return PendingSave(changes = changes, topic = topic, error = error)
    }

    /** Save is offered to whoever can change something here, and to nobody else. */
    fun showsSave(access: ChannelAccess): Boolean = access.canEditModes || access.canSetTopic

    fun saveEnabled(saving: Boolean, pending: PendingSave): Boolean = !saving && pending.actionable

    /**
     * The page, top to bottom.
     *
     * @param errors the Save's refusal and the channel's error rows that answered it, shown under the
     *   last section, whichever that is.
     * @param dateTime medium date, short time — the topic setter's line.
     */
    fun build(
        slice: ChannelSlice,
        live: ChannelModeForm.Live,
        drafts: ChannelModeDrafts,
        errors: List<String>,
        dateTime: (Instant) -> String,
    ): List<SettingsSection> {
        val access = slice.access
        val topic = slice.topic ?: ""
        val out = mutableListOf<SettingsSection>()

        // Topic. Out of the channel we don't know it has no topic — or that it exists at all; only
        // what we last saw.
        val topicItem: SettingsItem = when {
            access.canSetTopic -> SettingsItem.TopicField(drafts.topic ?: topic)
            topic.isNotEmpty() -> SettingsItem.TopicText(topic, muted = false)
            else -> SettingsItem.TopicText(if (access.joined) "No topic set." else "Join the channel to see its topic.", muted = true)
        }
        val notes = mutableListOf<String>()
        if (topic.isNotEmpty()) slice.modes?.topicSetterLine(dateTime)?.let(notes::add)
        val limit = access.spec?.topicLen
        if (access.canSetTopic && limit != null) {
            val bytes = ChannelModeForm.topicBytes(ChannelModeForm.topicToSend(drafts.topic ?: topic))
            notes += "$bytes / $limit bytes"
        }
        out += SettingsSection("topic", "Topic", notes.takeIf { it.isNotEmpty() }?.let { SettingsFooter(it.joinToString(" · ")) }, listOf(topicItem))

        // Modes.
        val spec = access.spec
        if (spec == null) {
            out += SettingsSection("modes", "Modes", null, listOf(SettingsItem.Notice("spec", "Modes show once the network is connected.")))
            return withErrors(out, errors)
        }
        if (!access.joined) {
            out += SettingsSection("modes", "Modes", null, listOf(SettingsItem.Notice("parted", "Join the channel to see its modes.")))
            return withErrors(out, errors)
        }
        // Someone who can't change modes sees only the ones that are set.
        val rows = ChannelModeForm.rows(spec).filter { access.canEditModes || live.row(it.letter).on }
        val named = rows.filter { it.name != null }
        val other = rows.filter { it.name == null }
        val readOnly = if (access.canEditModes) null else SettingsFooter("Only channel operators can change modes.")
        if (named.isEmpty() && other.isEmpty()) {
            out += SettingsSection("modes", "Modes", readOnly, listOf(SettingsItem.Notice("none", "No modes set.")))
            return withErrors(out, errors)
        }
        if (named.isNotEmpty()) {
            out += SettingsSection("modes", "Modes", if (other.isEmpty()) readOnly else null, named.flatMap { items(it, live, drafts, access) })
        }
        if (other.isNotEmpty()) {
            // Letters with no name we can vouch for, apart, so twenty of them don't bury the ones that
            // mean something.
            out += SettingsSection("other", "Other Modes", readOnly, other.flatMap { items(it, live, drafts, access) })
        }
        return withErrors(out, errors)
    }

    /** A row's switch, and — while it's on — its value. */
    fun items(row: ChannelModeForm.Row, live: ChannelModeForm.Live, drafts: ChannelModeDrafts, access: ChannelAccess): List<SettingsItem> {
        val shown = drafts.shown(row.letter, live = live)
        val label = row.name ?: "+${row.letter}"
        val out = mutableListOf<SettingsItem>(SettingsItem.Toggle(row.letter, label, shown.on, access.canEditModes))
        if (row.kind == ChannelModeForm.RowKind.Flag || !shown.on) return out
        val isKey = row.kind == ChannelModeForm.RowKind.Key
        val keySet = isKey && live.row("k").on
        out += SettingsItem.Value(
            letter = row.letter,
            label = if (isKey) "Key" else if (row.letter == "l") "Limit" else "Value",
            value = shown.value,
            isKey = isKey,
            // A +k channel whose key we never learned: the field is empty, and the channel still has
            // one. Typing replaces it; switching off removes it.
            placeholder = if (keySet) "Key is set" else "Required",
            enabled = access.canEditModes,
            keySet = keySet,
        )
        return out
    }

    /** The Save's refusal under the last section, whichever that is — red, after any standing note. */
    fun withErrors(sections: List<SettingsSection>, errors: List<String>): List<SettingsSection> {
        if (errors.isEmpty() || sections.isEmpty()) return sections
        val last = sections.last()
        val text = (listOfNotNull(last.footer?.text) + errors).joinToString("\n")
        return sections.dropLast(1) + last.copy(footer = SettingsFooter(text, isError = true))
    }
}

/**
 * When to ask the network config for the channel's key — iOS's `keyAsked`/`keyLookup`/`configKey`.
 *
 * The key lives only in the network config, and the copy any other screen read is as old as that
 * screen — so it's asked afresh, once per stretch of the channel being keyed. Asked whenever the
 * channel IS keyed and hasn't been asked — not just on open, since the channel's modes may land after
 * the page does, or turn `+k` while it's up.
 *
 * Each read carries a generation; one that comes back after the answer stopped applying (the channel
 * lost `+k`, the socket resynced, a newer read went out) is dropped, so a slow read can't put back a
 * key the channel has since moved on from.
 */
class KeyLookup {
    /** The key the server holds, as the config said when last asked. */
    var configKey: String? = null
        private set
    private var asked = false
    private var generation = 0

    /**
     * The channel's set modes, on every render. Returns the generation to ask the config with, or
     * null when there's nothing to ask.
     */
    fun onModes(modes: String): Int? {
        if (!modes.contains("k")) {
            if (asked) generation += 1
            asked = false
            configKey = null
            return null
        }
        if (asked) return null
        asked = true
        generation += 1
        return generation
    }

    /** A read came back. True when it was still the one that may answer, and was taken. */
    fun answer(generation: Int, key: String?): Boolean {
        if (generation != this.generation) return false
        configKey = key
        return true
    }

    /**
     * A new socket: whatever changed in the gap came as backlog, not live rows, so a `±k` seen before
     * may be stale — forget the answer, and ask again.
     */
    fun resynced() {
        configKey = null
        asked = false
        generation += 1
    }
}
