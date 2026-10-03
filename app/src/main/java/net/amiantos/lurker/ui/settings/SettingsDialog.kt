// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.settings

import net.amiantos.lurker.ui.networks.FormErrorRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import net.amiantos.lurker.platform.findActivity
import net.amiantos.lurker.prefs.UiPreferences
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FormActionRow
import net.amiantos.lurker.ui.networks.FormInset
import net.amiantos.lurker.ui.networks.FormSectionFooter
import net.amiantos.lurker.ui.networks.FormSectionHeader
import net.amiantos.lurker.ui.networks.FormSwitchRow
import net.amiantos.lurker.ui.networks.FormValueRow
import net.amiantos.lurker.ui.networks.FullScreenDialog
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.EventFilter
import net.amiantos.lurkerkit.model.ServerAddress
import net.amiantos.lurkerkit.model.SettingDependency
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.session.ChatViewModel

/**
 * The settings screen (lurker-android#21) — lurker-ios's `SettingsViewController`, as a full-screen
 * dialog. The rules are [SettingsModel]'s.
 *
 * A dialog, as iOS presents a sheet: Settings is somewhere you visit and leave, not somewhere the
 * navigator should hold on to. Its one way deeper — Networks — opens the networks dialog over this
 * one ([onOpenNetworks]), and closing that comes back here: iOS pushes the networks screen inside the
 * Settings sheet, and the way out is Back and then Done, which this keeps.
 *
 * ⚠ The store is read through [SettingsInputs], mapped and distinct, never raw: the server's values
 * change under this screen whenever another device edits them, and that should move the control here
 * — but nothing else in a frame should rebuild it.
 *
 * @param onSignOut runs once the user has confirmed; the caller closes this dialog and ends the session.
 */
@Composable
internal fun SettingsDialog(
    model: ChatViewModel,
    uiPreferences: UiPreferences,
    onDismiss: () -> Unit,
    onOpenNetworks: () -> Unit,
    onSignOut: () -> Unit,
) {
    val inputsFlow = remember(model) {
        model.statePublisher
            .conflate()
            .map { SettingsInputs.of(it, linkPreviews = model.features.linkPreviews) }
            .distinctUntilChanged()
    }
    val initialInputs = remember(model) { SettingsInputs.of(model.state, linkPreviews = model.features.linkPreviews) }
    val inputs by inputsFlow.collectAsStateWithLifecycle(initialValue = initialInputs)

    // Kept in an activity-scoped store under a saved token, so a rotation keeps the writer and what it
    // has on screen — see `SettingsWriterStore`. `Main`, not `Main.immediate`: built in composition.
    // Never cancelled — every write runs to its reply, and a scope with nothing in it holds nothing.
    val token = rememberSaveable { UUID.randomUUID().toString() }
    val store: SettingsWriterStore = viewModel()
    val writer = store.writer(token) {
        SettingsWriter(CoroutineScope(SupervisorJob() + Dispatchers.Main)) { changes -> model.updateSettings(changes) }
    }
    val activity = LocalContext.current.findActivity()
    // ⚠ Discarded when Settings goes — dismissed, signed out of, or closed by a join landing — but NOT
    // across a configuration change, the one disposal the store exists to survive.
    DisposableEffect(token) {
        onDispose { if (activity?.isChangingConfigurations != true) store.discard(token) }
    }
    // Any settings change — the echo of our own write, or another device's — retires a rejection.
    LaunchedEffect(writer, inputs.settings) { writer.observe(inputs.settings) }

    val autocapitalizes by uiPreferences.composerAutocapitalizes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val about = remember(context) { AboutLines(version = versionLine(context), server = serverLine(uiPreferences)) }

    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }

    FullScreenDialog(onDismissRequest = onDismiss) {
        SettingsContent(
            sections = SettingsModel.sections(inputs),
            settings = inputs.settings,
            edits = writer.edits,
            autocapitalizes = autocapitalizes,
            about = about,
            actions = SettingsActions(
                onClose = onDismiss,
                onOpenNetworks = onOpenNetworks,
                onSet = writer::set,
                onStep = writer::step,
                // No write error to report and no echo to wait for: this lands in the preferences
                // synchronously, so the switch is already telling the truth.
                onAutocapitalize = uiPreferences::setComposerAutocapitalizes,
                onSignOut = { confirmingSignOut = true },
            ),
        )
        if (confirmingSignOut) {
            // Sign-out asks first. It's one tap from a settings list, it ends the session on the
            // server, and the way back in is a password the user may not have to hand. iOS's copy.
            AlertDialog(
                onDismissRequest = { confirmingSignOut = false },
                title = { Text("Sign out of Lurker?") },
                text = { Text("You'll need your password to sign back in.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmingSignOut = false
                            // A stepper value still settling goes out first, while the session it
                            // belongs to still exists — the dispose-time flush would race the logout.
                            writer.flush()
                            onSignOut()
                        },
                    ) { Text("Sign Out", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel") } },
            )
        }
    }
}

/** The About section's two lines: the app's version, and the server this session is on. */
internal data class AboutLines(val version: String, val server: String?)

/** "Version 1.0 (1)", from the package — iOS reads the bundle's short version and build. */
private fun versionLine(context: Context): String {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    }.getOrNull()
    return SettingsModel.versionString(versionName = info?.versionName, versionCode = info?.longVersionCode)
}

/**
 * The server this session is on. Not on iOS, and not something the kit exposes — but the app
 * remembers it: `LurkerApp` writes `lastServerURL` only when a sign-in succeeds, and nothing else
 * signs in, so for a signed-in session it is the server in use. Normalized, as the sign-in made it,
 * so it reads as the address actually being talked to rather than as it was typed.
 */
private fun serverLine(uiPreferences: UiPreferences): String? =
    ServerAddress.normalize(uiPreferences.lastServerURL).ifEmpty { null }

/** What the screen's touches do. One object so the content stays stateless (previews). */
internal class SettingsActions(
    val onClose: () -> Unit,
    val onOpenNetworks: () -> Unit,
    /** A toggle or a pull-down, written now. */
    val onSet: (key: String, value: SettingValue) -> Unit,
    /** A stepper tap, written once the run settles. */
    val onStep: (key: String, value: SettingValue) -> Unit,
    val onAutocapitalize: (Boolean) -> Unit,
    val onSignOut: () -> Unit,
) {
    companion object {
        val None = SettingsActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {})
    }
}

@Composable
private fun SettingsContent(
    sections: List<SettingsSection>,
    settings: Settings,
    edits: SettingsEdits,
    autocapitalizes: Boolean,
    about: AboutLines,
    actions: SettingsActions,
) {
    DialogPage(title = "Settings", exit = PageExit.Close, onExit = actions.onClose) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            sections.forEachIndexed { index, section ->
                section(index, section, settings, edits, autocapitalizes, about, actions)
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** One section's items: its header (or the gap that stands for one), its rows, its footer. */
private fun LazyListScope.section(
    index: Int,
    section: SettingsSection,
    settings: Settings,
    edits: SettingsEdits,
    autocapitalizes: Boolean,
    about: AboutLines,
    actions: SettingsActions,
) {
    val id = section::class.simpleName ?: "section$index"
    val header = SettingsModel.header(section)
    item(key = "$id.header") {
        when {
            header != null -> FormSectionHeader(header)
            // A grouped list's gap between sections, for the ones with no header. None above the first.
            index > 0 -> Spacer(Modifier.height(20.dp))
        }
    }
    when (section) {
        SettingsSection.Networks -> item(key = id) { NetworksRow(onClick = actions.onOpenNetworks) }
        is SettingsSection.Chat -> settingRows(id, section.rows, settings, edits, actions)
        is SettingsSection.Events -> settingRows(id, section.rows, settings, edits, actions)
        is SettingsSection.SmartFilter -> settingRows(id, section.rows, settings, edits, actions)
        is SettingsSection.Appearance -> settingRows(id, section.rows, settings, edits, actions)
        is SettingsSection.Unavailable -> item(key = id) { UnavailableRow(loaded = section.loaded) }
        SettingsSection.Device -> item(key = id) {
            // U9: notification preferences, if the slice adds any, are device rows too — iOS has none
            // here yet (permission is asked for on its own, not toggled in Settings).
            FormSwitchRow(
                label = SettingsModel.AUTOCAPITALIZE_LABEL,
                checked = autocapitalizes,
                onCheckedChange = actions.onAutocapitalize,
            )
        }
        SettingsSection.Account -> item(key = id) {
            FormActionRow(title = "Sign Out", onClick = actions.onSignOut, destructive = true)
        }
        SettingsSection.About -> item(key = id) { AboutRows(about) }
    }
    val footer = SettingsModel.footer(section)
    if (footer != null) item(key = "$id.footer") { FormSectionFooter(footer) }
}

private fun LazyListScope.settingRows(
    sectionId: String,
    rows: List<SettingRow>,
    settings: Settings,
    edits: SettingsEdits,
    actions: SettingsActions,
) {
    for (row in rows) {
        item(key = "$sectionId.${row.option.key}") {
            SettingRowView(SettingsModel.rowState(row, settings, edits), actions)
        }
    }
}

/** The account's IRC networks — a door, not a control. */
@Composable
private fun NetworksRow(onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(LurkerIcons.Language, contentDescription = null) },
        headlineContent = { Text("Networks") },
        trailingContent = { Icon(LurkerIcons.ChevronRight, contentDescription = null) },
    )
}

@Composable
private fun UnavailableRow(loaded: Boolean) {
    ListItem(
        // One stop for the title and its explanation, as a state view is (#20).
        modifier = Modifier.semantics(mergeDescendants = true) {},
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(SettingsModel.unavailableTitle(loaded), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        supportingContent = { Text(SettingsModel.unavailableSubtitle(loaded)) },
    )
}

@Composable
private fun AboutRows(about: AboutLines) {
    Column {
        ListItem(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("Lurker") },
            supportingContent = { Text(about.version) },
        )
        if (about.server != null) FormValueRow(label = "Server", value = about.server)
    }
}

/** A server setting's row: its label, its control, and the server's refusal under it if it was the one refused. */
@Composable
private fun SettingRowView(row: SettingRowState, actions: SettingsActions) {
    Column(Modifier.fillMaxWidth()) {
        when (val control = row.control) {
            is SettingControl.Toggle -> FormSwitchRow(
                label = row.label,
                checked = control.isOn,
                enabled = row.enabled,
                onCheckedChange = { actions.onSet(row.key, SettingValue.Bool(it)) },
            )
            is SettingControl.Stepper -> StepperRow(row.label, control, row.enabled) { next ->
                actions.onStep(row.key, SettingValue.Int(next))
            }
            is SettingControl.Menu -> MenuRow(row.label, control, row.enabled) { choice ->
                actions.onSet(row.key, SettingsModel.menuValue(choice))
            }
            SettingControl.Plain -> Text(
                row.label,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FormInset, vertical = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        // The network forms' refusal row — one look, and one "Error" for TalkBack, for every refusal.
        // There with or without one, so a write refused while Settings is open is read out.
        FormErrorRow(row.error)
    }
}

/**
 * An int between the registry's bounds: the number itself, and − / +. iOS's `UIStepper` with its
 * label beside it — a stepper alone shows you nothing. Material has no stepper; two icon buttons
 * that disable at the bounds are its shape.
 */
@Composable
private fun StepperRow(label: String, control: SettingControl.Stepper, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = FormInset, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label and its number read as one ("Max consolidated nicks, 5"); the buttons are their own.
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                control.value.toString(),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        RepeatingStepButton(enabled = enabled && control.canDecrement, onStep = { onChange(control.stepped(-1)) }) {
            Icon(LurkerIcons.Remove, contentDescription = "Decrease $label")
        }
        RepeatingStepButton(enabled = enabled && control.canIncrement, onStep = { onChange(control.stepped(1)) }) {
            Icon(LurkerIcons.Add, contentDescription = "Increase $label")
        }
    }
}

/**
 * One stepper button: a step on press, then steps repeating while it's held — iOS's `UIStepper`
 * autorepeat. Without it the smart filter's windows (0–1440 minutes) were most of their range out of
 * reach: 105 taps from 15 to 120. The writer's settle (`SettingsWriter`) turns a held run into one
 * write.
 *
 * Each repeat reads the latest [onStep], which the step itself recomposed with the new value, and
 * stops at the bound, where [enabled] goes false. TalkBack gets a plain button: one step per
 * activation.
 */
@Composable
private fun RepeatingStepButton(enabled: Boolean, onStep: () -> Unit, content: @Composable () -> Unit) {
    val currentStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                role = Role.Button
                if (enabled) onClick { currentStep(); true } else disabled()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    currentStep()
                    val repeating = scope.launch {
                        delay(STEP_REPEAT_DELAY_MS)
                        while (currentEnabled) {
                            currentStep()
                            delay(STEP_REPEAT_INTERVAL_MS)
                        }
                    }
                    waitForUpOrCancellation()
                    repeating.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** How long a press is held before it starts repeating, and how fast it repeats then (iOS's feel). */
private const val STEP_REPEAT_DELAY_MS = 400L
private const val STEP_REPEAT_INTERVAL_MS = 70L

/**
 * A pull-down showing the value in force — iOS's menu button. A menu rather than a segmented control:
 * the event tier's choices are full phrases ("Hide from quiet users"), and three of those never fit a
 * phone's width without truncating to uselessness. The row shows the current choice, which is what
 * it needs to say when nothing is being touched.
 *
 * The choice the menu marks follows the tap at once (the writer's pending value), as iOS's
 * `changesSelectionAsPrimaryAction` does; the authoritative value arrives back on the store.
 */
@Composable
private fun MenuRow(label: String, control: SettingControl.Menu, enabled: Boolean, onPick: (MenuChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val valueColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
            .heightIn(min = 56.dp)
            .padding(horizontal = FormInset)
            // The value in force, in words a screen reader will actually say — the nick suffix's
            // samples differ only by a trailing mark it may not read.
            .semantics(mergeDescendants = true) { stateDescription = control.spokenTitle },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // The menu anchors to the value, at the row's end, where the finger is.
        Box {
            // Read through `stateDescription` above, not as text.
            Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    control.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = valueColor,
                )
                Icon(LurkerIcons.ArrowDropDown, contentDescription = null, tint = valueColor)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                control.choices.forEach { choice ->
                    val selected = choice.value == control.current
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        leadingIcon = {
                            if (selected) Icon(LurkerIcons.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                        },
                        modifier = Modifier.semantics {
                            choice.spoken?.let { contentDescription = it }
                            if (selected) stateDescription = "Selected"
                        },
                        onClick = {
                            expanded = false
                            if (!selected) onPick(choice)
                        },
                    )
                }
            }
        }
    }
}

// MARK: - Previews

private fun previewSettings(): Settings {
    fun bool(key: String, default: Boolean, dependsOn: List<SettingDependency> = emptyList()) =
        SettingOption(key, key, "", SettingType.Bool, SettingValue.Bool(default), dependsOn = dependsOn)
    fun int(key: String, default: Int, min: Int, max: Int, dependsOn: List<SettingDependency> = emptyList()) =
        SettingOption(key, key, "", SettingType.Int, SettingValue.Int(default), min = min, max = max, dependsOn = dependsOn)
    val joins = listOf(SettingDependency("chat.consolidate_joins", listOf(SettingValue.Bool(true))))
    val options = listOf(
        bool("chat.send_typing_notifications", true),
        bool("chat.keep_position_on_send", false),
        SettingOption("input.completion.nick_suffix", "", "", SettingType.String, SettingValue.String(":")),
        SettingOption(
            EventFilter.modeKey, "", "", SettingType.Enum, SettingValue.String("all"),
            choices = listOf("all", "smart", "none"),
            choiceLabels = mapOf("all" to "Show all", "smart" to "Hide from quiet users", "none" to "Hide all"),
        ),
        bool("chat.consolidate_joins", false),
        int("chat.consolidate_max_names", 5, min = 1, max = 20, dependsOn = joins),
        bool("chat.show_event_host", false),
        bool("chat.smart_filter_join", true),
        int("chat.smart_filter_delay", 15, min = 1, max = 120),
        bool("look.nick.show_mode_prefix", false),
    )
    return Settings(registry = options.associateBy { it.key }, values = mapOf("chat.keep_position_on_send" to SettingValue.Bool(true)))
}

@Composable
private fun SettingsPreview(dark: Boolean, settings: Settings, edits: SettingsEdits = SettingsEdits()) {
    LurkerTheme(darkTheme = dark) {
        SettingsContent(
            sections = SettingsModel.sections(SettingsInputs(settings, linkPreviews = false)),
            settings = settings,
            edits = edits,
            autocapitalizes = true,
            about = AboutLines(version = "Version 1.0 (1)", server = "https://app.lurker.chat"),
            actions = SettingsActions.None,
        )
    }
}

@Preview(name = "Settings — light", heightDp = 1400)
@Composable
private fun SettingsPreviewLight() = SettingsPreview(dark = false, settings = previewSettings())

@Preview(name = "Settings — dark", heightDp = 1400)
@Composable
private fun SettingsPreviewDark() = SettingsPreview(dark = true, settings = previewSettings())

@Preview(name = "Settings, refused — light", heightDp = 640)
@Composable
private fun RefusedPreviewLight() = SettingsPreview(
    dark = false,
    settings = previewSettings(),
    edits = SettingsEdits(error = WriteError("chat.keep_position_on_send", "Something went wrong saving that.")),
)

@Preview(name = "Settings, refused — dark", heightDp = 640)
@Composable
private fun RefusedPreviewDark() = SettingsPreview(
    dark = true,
    settings = previewSettings(),
    edits = SettingsEdits(error = WriteError("chat.keep_position_on_send", "Something went wrong saving that.")),
)

@Preview(name = "Settings, not loaded — light")
@Composable
private fun UnavailablePreviewLight() = SettingsPreview(dark = false, settings = Settings())

@Preview(name = "Settings, not loaded — dark")
@Composable
private fun UnavailablePreviewDark() = SettingsPreview(dark = true, settings = Settings())
