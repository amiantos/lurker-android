// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.settings

import net.amiantos.lurkerkit.model.EventFilter
import net.amiantos.lurkerkit.model.NickCompletion
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.store.ChatState

/**
 * What the settings screen reads from the store: the settings themselves, and whether the instance
 * has link previews — the one feature flag that hides rows.
 *
 * ⚠ Compared by value, as iOS's `removeDuplicates()` on `\.settings` does: every frame publishes the
 * whole state, and the screen rebuilds only when a setting (or the registry) actually moves —
 * including the echo of our own write, and another device's edit, which should move the control here
 * too.
 *
 * `linkPreviews` isn't in `ChatState` (it's `ChatViewModel.features`, re-read on every reconnect), so
 * it's sampled on each frame, as iOS reads it on each rebuild.
 */
data class SettingsInputs(val settings: Settings, val linkPreviews: Boolean) {
    companion object {
        fun of(state: ChatState, linkPreviews: Boolean) = SettingsInputs(state.settings, linkPreviews)
    }
}

/** One row a server setting earns: the label this screen writes, and the registry entry describing how to edit it. */
data class SettingRow(val label: String, val option: SettingOption)

/**
 * The screen's sections, in display order. lurker-ios's `SettingsViewController.Section`.
 *
 * [Networks] is first, above every preference: it is the only thing on this screen that decides
 * whether the app can do anything at all, and the rest are adjustments to an app that is already
 * working. Unconditional, like [Device], because it needs no registry — a server too old to describe
 * its settings still has networks.
 */
sealed interface SettingsSection {
    data object Networks : SettingsSection
    data class Chat(val rows: List<SettingRow>) : SettingsSection
    data class Events(val rows: List<SettingRow>) : SettingsSection
    data class SmartFilter(val rows: List<SettingRow>) : SettingsSection
    data class Appearance(val rows: List<SettingRow>) : SettingsSection

    /** Bootstrap hasn't landed (or this server offers none of the keys), so there's nothing to build controls from. */
    data class Unavailable(val loaded: Boolean) : SettingsSection
    data object Device : SettingsSection
    data object Account : SettingsSection
    data object About : SettingsSection
}

/**
 * The most recent failed write, shown under the offending row: the server explains itself ("must be
 * one of…", "out of range") and that wording is more use than a generic alert, which would also cover
 * the control the user is trying to fix.
 */
data class WriteError(val key: String, val message: String)

/**
 * The screen's own state over the store's: values the user has just set whose write hasn't come
 * back, and the last refusal.
 *
 * **The optimism rule (iOS's).** A control moves the moment it's touched — a `UISwitch` flips under
 * the finger, a stepper's number echoes locally, a pull-down tracks its selection — and the store's
 * value takes over once the write lands. The client applies the server's reply to the store *before*
 * `updateSettings` returns (`LurkerClient.updateSettings`), so by the time a write is [finished] the
 * store already holds whatever the server accepted, and dropping the pending value leaves the row on
 * it. A rejected write leaves the store where it was, so the row goes back to the value the server
 * still holds rather than showing a state it never accepted.
 *
 * A pending value is dropped only by ITS write: a second tap before the first reply lands replaces
 * it, and the first reply then leaves the newer value alone.
 *
 * Port note: on iOS any settings change rebuilds the table, which snaps every control — including one
 * whose own write is still in flight — back to the store. Here a write in flight keeps its control
 * where the user put it until its own reply settles it; a moment later, either way.
 */
data class SettingsEdits(
    val pending: Map<String, SettingValue> = emptyMap(),
    val error: WriteError? = null,
) {
    /** The user set [key] to [value]; its write is about to go (or, for a stepper, to settle first). */
    fun began(key: String, value: SettingValue): SettingsEdits = copy(pending = pending + (key to value))

    /**
     * The write of [value] to [key] came back: [failure] is the server's reason, or null for success.
     *
     * Success clears any rejection on screen, whichever row it was under — iOS's rule, where any
     * settings change rebuilds the table. That covers the write that changed nothing — setting a
     * value it already held: the store doesn't move, so the settings change that would otherwise
     * retire the old rejection never comes.
     */
    fun finished(key: String, value: SettingValue, failure: String?): SettingsEdits = SettingsEdits(
        pending = if (pending[key] == value) pending - key else pending,
        error = failure?.let { WriteError(key, it) },
    )

    /**
     * A settings change from anywhere retires a previous rejection: the value on screen is now the
     * server's, so a red "out of range" pinned under a control that's since become correct is just a
     * lie that never expires.
     */
    fun settingsChanged(): SettingsEdits = if (error == null) this else copy(error = null)
}

/**
 * One value a pull-down offers: what it stores, what the row shows, and what TalkBack says when the
 * visible label can't be read aloud. [spoken] is null when [label] speaks for itself — the event
 * tier's choices are full phrases.
 */
data class MenuChoice(val value: String, val label: String, val spoken: String? = null)

/** The control a row carries, decided by the registry's type. */
sealed interface SettingControl {
    data class Toggle(val isOn: Boolean) : SettingControl

    /** An int, between the registry's bounds — so the control can't offer a value the server would reject. */
    data class Stepper(val value: Int, val min: Int, val max: Int) : SettingControl {
        val canDecrement: Boolean get() = value > min
        val canIncrement: Boolean get() = value < max

        /** One step, held inside the bounds. */
        fun stepped(delta: Int): Int = (value + delta).coerceIn(min, max)
    }

    /** A pull-down showing the value in force ([current]), offering [choices]. */
    data class Menu(val current: String, val choices: List<MenuChoice>) : SettingControl {
        val selected: MenuChoice? get() = choices.firstOrNull { it.value == current }

        /** What the row shows: the selected choice's label, or the raw value if none matches. */
        val title: String get() = selected?.label ?: current

        /** What TalkBack reads for the value in force — the same words its menu item would. */
        val spokenTitle: String get() = selected?.spoken ?: title
    }

    /** A type this screen builds no control for: a plain row rather than a control that silently does nothing. */
    data object Plain : SettingControl
}

/** A server setting's row, ready to draw. */
data class SettingRowState(
    val key: String,
    val label: String,
    val control: SettingControl,
    /** From the registry's `dependsOn` — see [SettingsModel.rowState]. */
    val enabled: Boolean,
    val error: String?,
)

/**
 * The settings screen's rules (lurker-ios#20 / lurker-android#21), pure so they can be pinned in JVM
 * tests. lurker-ios's `SettingsViewController`, minus the table.
 *
 * Two kinds of thing live on the screen and they behave differently, which is the whole design.
 *
 * **Server settings** — the ones that change how the app behaves — are stored on the server and
 * shared with every other client. A change writes through `PATCH /api/settings` and comes back as a
 * `settings` frame, so the phone and the browser can never disagree about what the rules are. They're
 * listed because *if a server setting applies to the phone, it should be changeable from the phone*:
 * honoring a rule you can't see or reach makes the app's behavior unexplainable from the device it's
 * happening on.
 *
 * **Device preferences and actions** — autocapitalization, sign out, the version — belong to this
 * install and don't sync.
 *
 * Labels are ours, not the registry's, and there is no help text. The registry's `label` and
 * `description` are written for a desktop settings pane with room to explain itself — full sentences,
 * sometimes a worked example — and on a phone that buries three switches under a wall of prose. A
 * short label a person can scan beats an accurate one they won't read. What still comes from the
 * registry is everything a *control* needs to be correct: the type, the default, and an int's bounds,
 * so a stepper can't offer a value the server would reject.
 *
 * Behavior and appearance are separate sections — what the app *does* with a message is a different
 * question from what the message looks like, and mixing them makes both lists harder to scan.
 */
object SettingsModel {

    /**
     * The server settings under Chat, in display order, with the label to show.
     *
     * iOS's rule is **add a key only when the app honors it**; this list is iOS's, key for key, so the
     * two apps offer the same screen. Where this app's consumer is a later slice, it's marked at the
     * key: a control for a setting the app ignores is worse than none, so each of these must be
     * honored by the time it ships.
     */
    val chatSettings: List<Pair<String, String>> = listOf(
        // U3: the composer's typing signal reads this (the kit's `setTyping` already gates on it).
        "chat.send_typing_notifications" to "Send typing notifications",
        // U2b/U3: the message list's scroll rule on send (iOS `keepsPositionWhileReading`) reads this.
        "chat.keep_position_on_send" to "Stay put when you send",
        // Composing rather than reading, which is the one row here that isn't about what the app does
        // with a message that ARRIVES. It sits under Chat anyway: one row is not a section, and an
        // "Input" header over a single pull-down would be filing for its own sake.
        // U3: the @ picker and Reply read it (`NickCompletion.addressPunctuation`).
        "input.completion.nick_suffix" to "Address nicks with",
    )

    /**
     * Join/part/quit/nick/host-change/mode lines: whether you see them, how they're folded, and how
     * much detail each carries. Its own section, mirroring the web's Events category (#666). Ordered as
     * they narrow: the filter, then how the survivors are folded, then what each surviving line shows.
     *
     * The filter reads the MOBILE key — the tier is split by device class (and only the tier is), and
     * this device is never the desktop case.
     *
     * The settings under it do NOT grey out when this phone is set to "Hide all", and that is
     * deliberate. Their registry `dependsOn` is ORed across both device classes, so the desktop clause
     * — a key this screen doesn't list — keeps them live. They are shared settings, not per-device
     * ones: disabling them here would stop you managing your desktop's consolidation from your phone.
     */
    val eventSettings: List<Pair<String, String>> = listOf(
        EventFilter.modeKey to "Event filter",
        "chat.consolidate_joins" to "Consolidate events",
        "chat.consolidate_max_names" to "Max consolidated nicks",
        "chat.show_event_host" to "Show user@host on events",
        "chat.show_join_account" to "Show account on joins",
    )

    /**
     * The `smart` rung's tuning (#63): its own section under Events, because these apply on ONE rung,
     * and left in that list they'd read as more general event options. Ordered as the feature is
     * explained: WHAT it hides first, then HOW LONG it remembers someone.
     */
    val smartFilterSettings: List<Pair<String, String>> = listOf(
        "chat.smart_filter_join" to "Filter joins",
        "chat.smart_filter_quit" to "Filter parts and quits",
        "chat.smart_filter_nick" to "Filter nick changes",
        // Its shorter label is deliberate: the registry's description carries the rule that a ban
        // riding along shows the whole line.
        "chat.smart_filter_mode" to "Filter op and voice changes",
        "chat.smart_filter_delay" to "\"Recently spoke\" window (min)",
        "chat.smart_filter_join_unmask" to "Reveal join on speaking (min)",
    )

    /**
     * Settings that change how the conversation *looks*. The two preview toggles are two rather than
     * one because wanting your friends' screenshots to show is a different appetite from wanting every
     * article to sprout a card. Both default off.
     */
    val appearanceSettings: List<Pair<String, String>> = listOf(
        "look.nick.show_mode_prefix" to "Show mode prefix on nicks",
        // U8: inline media and link previews render from these.
        "chat.inline_media.enabled" to "Inline media",
        "chat.link_previews.enabled" to "Link previews",
    )

    /**
     * Keys that only mean anything when the instance has link previews enabled (`LURKER_LINK_PREVIEWS`).
     * Held here rather than read off the registry because the server doesn't send the flag on the wire.
     */
    val requiresLinkPreviews: Set<String> = setOf("chat.inline_media.enabled", "chat.link_previews.enabled")

    /**
     * The sections, from what the store holds.
     *
     * Only what this server actually knows about: a self-hosted instance updates on its owner's
     * schedule and can legitimately be older than the app, so a key it has never heard of gets no row
     * rather than a control whose write would be rejected. A setting belonging to a disabled instance
     * feature gets no row at all either — with link previews off the routes aren't even mounted.
     *
     * Chat is the test for a usable registry. With none of its keys, the screen says so rather than
     * rendering Networks, Sign Out and a version number — which reads as "this app has no settings"
     * instead of "we couldn't load them". The other optional sections are dropped when empty rather
     * than drawn blank: a server predating the event filter knows the consolidation keys but not
     * `chat.events`, and one from before #63 has no Smart Filter keys at all — a header over nothing
     * would advertise a rung it can't serve. Device, Account and About are unconditional: they need no
     * registry, and they still work on a server too old (or too unreachable) to describe itself.
     */
    fun sections(inputs: SettingsInputs): List<SettingsSection> {
        val registry = inputs.settings.registry
        fun resolve(entries: List<Pair<String, String>>): List<SettingRow> = entries.mapNotNull { (key, label) ->
            val option = registry[key] ?: return@mapNotNull null
            if (key in requiresLinkPreviews && !inputs.linkPreviews) return@mapNotNull null
            SettingRow(label, option)
        }
        val chat = resolve(chatSettings)
        val tail = listOf(SettingsSection.Device, SettingsSection.Account, SettingsSection.About)
        if (chat.isEmpty()) {
            return listOf(SettingsSection.Networks, SettingsSection.Unavailable(loaded = inputs.settings.loaded)) + tail
        }
        val events = resolve(eventSettings)
        val smart = resolve(smartFilterSettings)
        val appearance = resolve(appearanceSettings)
        return buildList {
            add(SettingsSection.Networks)
            add(SettingsSection.Chat(chat))
            if (events.isNotEmpty()) add(SettingsSection.Events(events))
            if (smart.isNotEmpty()) add(SettingsSection.SmartFilter(smart))
            if (appearance.isNotEmpty()) add(SettingsSection.Appearance(appearance))
            addAll(tail)
        }
    }

    /** A section's header, or null for the ones that stand without one. */
    fun header(section: SettingsSection): String? = when (section) {
        is SettingsSection.Chat, is SettingsSection.Unavailable -> "Chat"
        is SettingsSection.Events -> "Events"
        is SettingsSection.SmartFilter -> "Smart Filter"
        is SettingsSection.Appearance -> "Appearance"
        SettingsSection.Device -> "This Device"
        SettingsSection.Networks, SettingsSection.Account, SettingsSection.About -> null
    }

    /**
     * Footers, on the two sections that can't be understood from their rows alone.
     *
     * **This Device** — the rest of this screen follows you between clients and this doesn't. Phrased
     * about its own section rather than as "the settings above are shared", which is a lie in the
     * no-registry branch, where the only thing above it is the notice saying the settings couldn't be
     * loaded.
     *
     * **Smart Filter** — ⚠ its rows do nothing on the other two rungs, and nothing on screen says so.
     * Dimming can't carry it: `dependsOn` is ORed across device classes, so with a desktop on Smart
     * these rows stay live on a phone set to Show all — correctly, since that phone is editing the
     * desktop's filter. A section that is live, editable, and inert on the device you're holding has
     * to explain itself.
     */
    fun footer(section: SettingsSection): String? = when (section) {
        SettingsSection.Device -> "Applies to this device only — not shared with your other Lurker clients."
        is SettingsSection.SmartFilter -> "Used when Event filter is set to Smart."
        else -> null
    }

    /** The no-registry notice's title. `loaded` tells "this server has none of these" from "we couldn't ask". */
    fun unavailableTitle(loaded: Boolean): String =
        if (loaded) "No chat settings available on this server" else "Couldn't load settings"

    fun unavailableSubtitle(loaded: Boolean): String = if (loaded) {
        // A server older than the app genuinely may not have these keys.
        "This server doesn't offer the settings this app can change."
    } else {
        "Check your connection and reopen Settings."
    }

    /** The one device-local row — iOS's `DeviceSetting.autocapitalize`. */
    const val AUTOCAPITALIZE_LABEL = "Autocapitalize messages"

    /**
     * The value a row shows: the user's pending choice while its write is out, else the value in force
     * (the stored one, else the registry default).
     */
    fun displayed(option: SettingOption, settings: Settings, edits: SettingsEdits): SettingValue? =
        edits.pending[option.key] ?: settings.effective(option.key)

    /**
     * Curated choices for a `string` key offered as a pull-down.
     *
     * A `string` setting is free-form on the web, where `/set` takes any value; the phone has no `/set`.
     * Offering the values people actually pick — with labels this screen writes — is the phone-shaped
     * half of a free-form key, and a value from outside the list is still shown honestly.
     *
     * `normalize` is how the FEATURE reads the stored value, so the control matches it the same way:
     * a suffix the web stored as `", "` is the `","` choice, not a custom value beside it.
     */
    private class StringChoices(
        val values: List<Pair<String, String>>,
        val normalize: (String) -> String,
        val spoken: (String) -> String,
    )

    private val stringChoices: Map<String, StringChoices> = mapOf(
        // Labelled as the form each one produces rather than by naming the punctuation: the question
        // is what your line will look like, and the sample answers it. Which is exactly why the key
        // needs a `spoken` — the four differ ONLY by a trailing mark, which a screen reader doesn't
        // reliably speak, so read aloud they'd be four identical "nick"s.
        "input.completion.nick_suffix" to StringChoices(
            values = listOf(":" to "nick:", "," to "nick,", ";" to "nick;", "" to "nick"),
            normalize = NickCompletion::addressPunctuation,
            spoken = NickCompletion::spokenPunctuation,
        ),
    )

    /**
     * A server setting's row: its control from the registry's type, whether it's live, and the
     * refusal pinned under it, if this is the row that was refused.
     *
     * Live is the registry's own `dependsOn` (#666) rather than a table kept here, so the phone greys
     * out exactly what the web does and a new dependency needs no app change.
     */
    fun rowState(row: SettingRow, settings: Settings, edits: SettingsEdits): SettingRowState {
        val option = row.option
        val value = displayed(option, settings, edits)
        val control = when (option.type) {
            SettingType.Bool -> SettingControl.Toggle(isOn = value?.boolValue ?: option.default.boolValue ?: false)
            SettingType.Int -> {
                val min = option.min ?: 0
                val max = option.max ?: 100
                SettingControl.Stepper(value = value?.intValue ?: option.default.intValue ?: 0, min = min, max = max)
            }
            // Every choice is offered, with the registry's own wording (`SettingOption.label`), so the
            // phone says what the web says without a second copy to keep in step.
            SettingType.Enum -> SettingControl.Menu(
                current = value?.stringValue ?: option.default.stringValue ?: "",
                choices = option.choices.map { MenuChoice(value = it, label = option.label(it)) },
            )
            SettingType.String -> stringMenu(option, value)
            else -> SettingControl.Plain
        }
        return SettingRowState(
            key = option.key,
            label = row.label,
            control = control,
            // Over the pending values too: a dependent row follows the switch the user just flipped,
            // rather than waiting out its write — and greys back if that write is refused.
            enabled = settings.apply(edits.pending).isActive(option.key),
            error = edits.error?.takeIf { it.key == option.key }?.message,
        )
    }

    /**
     * A `string` key's pull-down, or a plain row for one this screen has no curated choices for.
     *
     * A value the web set that isn't one of ours is shown as itself and checked, never silently
     * rounded to a neighbour: the row has to say what is actually in force, and picking one of the
     * offered forms is how you leave it. Read aloud through the SAME function as the four, so the one
     * value this control can't otherwise explain is not the one it declines to name.
     */
    private fun stringMenu(option: SettingOption, value: SettingValue?): SettingControl {
        val curated = stringChoices[option.key] ?: return SettingControl.Plain
        val stored = value?.stringValue ?: option.default.stringValue ?: ""
        val current = curated.normalize(stored)
        val values = if (curated.values.any { it.first == current }) {
            curated.values
        } else {
            curated.values + (current to "nick$current")
        }
        return SettingControl.Menu(
            current = current,
            choices = values.map { (stored, label) -> MenuChoice(value = stored, label = label, spoken = curated.spoken(stored)) },
        )
    }

    /** What a pick from a row's pull-down writes. Both pull-down types store a string. */
    fun menuValue(choice: MenuChoice): SettingValue = SettingValue.String(choice.value)

    /** How long a run of stepper taps is allowed to settle before it's sent. */
    const val STEPPER_DEBOUNCE_MILLIS = 400L

    /**
     * The About row's version line — iOS's `versionString`, from the package's versionName and
     * versionCode, with iOS's "—" for a part the platform didn't give.
     */
    fun versionString(versionName: String?, versionCode: Long?): String =
        "Version ${versionName ?: "—"} (${versionCode?.toString() ?: "—"})"
}
