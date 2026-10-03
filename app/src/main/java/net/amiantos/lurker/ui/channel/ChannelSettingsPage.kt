// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.channel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurker.ui.networks.DialogPage
import net.amiantos.lurker.ui.networks.FormInset
import net.amiantos.lurker.ui.networks.FormSectionFooter
import net.amiantos.lurker.ui.networks.FormSectionHeader
import net.amiantos.lurker.ui.networks.FormSwitchRow
import net.amiantos.lurker.ui.networks.FormTextField
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ChannelModeDrafts
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ChannelRefusals
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.PrefixMode
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.ChatState
import java.time.Instant

/**
 * The channel settings page's state — everything `ChannelSettingsViewController` keeps beside its
 * table, and its two subscriptions, for as long as the page is on the stack (they run in the page's own
 * scope, so a rotation doesn't drop a mode row that names the key).
 *
 * ⚠⚠ The form holds only what the user TOUCHED (`ChannelModeDrafts`), never a copy of the channel
 * taken on open. Every row reads live state, and Save diffs the edits against the live state at that
 * moment — so another op's change shows up while this is open, and Save never reverts it. An edit goes
 * when the channel answers it, never on the ack: sends are TENTATIVE until answered.
 */
class ChannelSettingsState(
    private val model: ChatViewModel,
    val key: BufferKey,
    private val scope: CoroutineScope,
    /** How a moment reads — the topic setter's line. */
    private val dateTime: (Instant) -> String,
) {
    /** What the page draws. Rebuilt by [render] on every change to anything below or to the slice. */
    var screen by mutableStateOf(ChannelSettingsScreen())
        private set

    /** Whether the key field shows its value. */
    var keyRevealed by mutableStateOf(false)

    private var slice = ChannelSlice.of(model.state, key)
    private var drafts = ChannelModeDrafts()
    private val inFlight = EditsInFlight()
    private val keyLookup = KeyLookup()

    /**
     * This channel's live `mode` rows since the page opened (or the socket last reopened), off the
     * socket rather than out of the buffer's log: a detached buffer holds live lines out of its log, and
     * this page still needs them — the newest `±k` names the key.
     */
    private val modeRowsSeen = mutableListOf<Message>()

    /** The channel's error rows, as answers to a Save. */
    private var refusals = ChannelRefusals()
    private var saving = false
    private var saveError: String? = null

    init {
        // Only this channel's slice: the state moves on every line in every buffer, and nothing else
        // changes what this page draws.
        scope.launch {
            model.statePublisher.conflate().map { ChannelSlice.of(it, key) }.distinctUntilChanged().collect {
                slice = it
                render()
            }
        }
        scope.launch {
            model.channelEvents.collect { event ->
                when (event) {
                    ChatViewModel.ChannelEvent.Resynced -> {
                        // Whatever changed in the gap came as backlog, not live rows, so a `±k` seen
                        // before it may be stale: forget them, and ask the config again.
                        modeRowsSeen.clear()
                        keyLookup.resynced()
                    }
                    is ChatViewModel.ChannelEvent.Line -> {
                        if (event.key.id != key.id) return@collect
                        when (event.message.type) {
                            EventType.Mode -> modeRowsSeen += event.message
                            EventType.Error -> refusals = refusals.note(event.message.text ?: "")
                            else -> return@collect
                        }
                    }
                }
                render()
            }
        }
        render()
    }

    private val storedKey: String? get() = ChannelSettingsModel.storedKey(modeRowsSeen, keyLookup.configKey)

    private fun live() = ChannelSettingsModel.live(slice.modes, storedKey)

    private fun liveTopic(): String = slice.topic ?: ""

    /** The key lives only in the network config — asked once per stretch of the channel being keyed. */
    private fun askForKeyIfKeyed() {
        // Lost the network or the channel: the live `±k` rows are from before, and the config's answer
        // too. Both are forgotten, and asked for again once we're back in.
        if (keyLookup.linkMoved(slice.keyReady)) modeRowsSeen.clear()
        val generation = keyLookup.onModes(slice.modes?.modes ?: "") ?: return
        scope.launch {
            val stored = model.storedChannelKey(key)
            if (keyLookup.answer(generation, stored)) render()
        }
    }

    private fun render() {
        askForKeyIfKeyed()
        val live = live()
        // The kit's reconcile, then what it shouldn't have dropped put back — see `EditsInFlight`.
        drafts = inFlight.restore(drafts.reconcile(live = live, liveTopic = liveTopic()), live, liveTopic())
        val pending = ChannelSettingsModel.pending(slice.access, live, drafts, liveTopic())
        val errors = listOfNotNull(saveError) + refusals.current
        screen = ChannelSettingsScreen(
            sections = ChannelSettingsModel.build(slice, live, drafts, errors, dateTime),
            showsSave = ChannelSettingsModel.showsSave(slice.access),
            saveEnabled = ChannelSettingsModel.saveEnabled(saving, pending),
        )
    }

    fun setTopic(text: String) {
        drafts = drafts.setTopic(text)
        inFlight.editedTopic(text)
        render()
    }

    fun setOn(letter: String, on: Boolean) {
        drafts = drafts.setOn(letter, on, live = live())
        inFlight.edited(letter, drafts)
        render()
    }

    fun setValue(letter: String, value: String) {
        drafts = drafts.setValue(letter, value, live = live())
        inFlight.edited(letter, drafts)
        render()
    }

    /**
     * Send what the form amounts to. ⚠ WRITES: TOPIC and MODE to the channel, for everyone in it.
     *
     * Decided now, before any suspension: the fields stay editable while the answer is out, and what
     * goes out is what the user saved. Each half is recorded as sent only as it goes — the modes wait on
     * the topic, and a topic that fails takes them with it.
     */
    fun save() {
        if (saving) return
        val live = live()
        val topicWas = liveTopic()
        val pending = ChannelSettingsModel.pending(slice.access, live, drafts, topicWas)
        saveError = pending.error
        val topic = pending.topic
        val changes = pending.changes
        if (pending.error != null || (topic == null && changes.isEmpty())) {
            render()
            return
        }
        val sending = drafts.sending(changes, live = live)
        val letters = changes.map { it.letter }.toSet()
        refusals = refusals.arm()
        saving = true
        render()
        scope.launch {
            withContext(NonCancellable) {
                var failure: ChatViewModel.ChannelSaveFailure? = null
                if (topic != null) {
                    drafts = drafts.noteTopicSending(topic, liveTopic = topicWas)
                    inFlight.sent(emptyList(), live, topicWas = topicWas)
                    failure = model.setTopic(key, topic = topic)
                    val wentOut = failure?.certainlyUnsent != true
                    drafts = drafts.settleTopic(topic, wentOut = wentOut)
                    inFlight.settled(emptyList(), topic = true, wentOut = wentOut)
                }
                if (failure == null && changes.isNotEmpty()) {
                    drafts = drafts.noteSending(sending)
                    inFlight.sent(letters, live, topicWas = null)
                    failure = model.setChannelModes(key, changes = changes)
                    val wentOut = failure?.certainlyUnsent != true
                    drafts = drafts.settle(sending, wentOut = wentOut)
                    inFlight.settled(letters, topic = false, wentOut = wentOut)
                }
                saving = false
                saveError = failure?.message
                render()
            }
        }
    }
}

/** What the channel settings page draws, as one value. */
data class ChannelSettingsScreen(
    val sections: List<SettingsSection> = emptyList(),
    val showsSave: Boolean = false,
    val saveEnabled: Boolean = false,
)

/**
 * A channel's topic and modes (lurker-ios#187), pushed from the buffer info page. Its lists — bans and
 * the rest — are pages of their own (`ModeListPage`). The rows are `ChannelSettingsModel`'s.
 */
@Composable
internal fun ChannelSettingsPage(state: ChannelSettingsState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    ChannelSettingsContent(
        screen = state.screen,
        keyRevealed = state.keyRevealed,
        onBack = onBack,
        onSave = {
            focus.clearFocus()
            state.save()
        },
        onTopic = state::setTopic,
        onToggle = state::setOn,
        onValue = state::setValue,
        onToggleReveal = { state.keyRevealed = !state.keyRevealed },
    )
}

@Composable
private fun ChannelSettingsContent(
    screen: ChannelSettingsScreen,
    keyRevealed: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onTopic: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onValue: (String, String) -> Unit,
    onToggleReveal: () -> Unit,
) {
    DialogPage(
        title = ChannelSettingsModel.TITLE,
        exit = PageExit.Back,
        onExit = onBack,
        confirmTitle = if (screen.showsSave) "Save" else null,
        confirmEnabled = screen.saveEnabled,
        onConfirm = onSave,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            for (section in screen.sections) {
                section.header?.let { header -> item(key = "header:${section.id}") { FormSectionHeader(header) } }
                // Keyed by the row's identity, so a field keeps its focus and caret while the rows
                // around it come and go — a value row appearing under a switch, an error under Save.
                for (row in section.items) {
                    item(key = row.id) {
                        SettingsItemView(row, keyRevealed, onTopic, onToggle, onValue, onToggleReveal)
                    }
                }
                section.footer?.let { footer ->
                    item(key = "footer:${section.id}") {
                        if (footer.isError) {
                            Text(
                                footer.text,
                                // A save refused while the page is open is read out where it lands (#20).
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = FormInset, vertical = 4.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall,
                                color = LurkerTheme.colors.badText,
                            )
                        } else {
                            FormSectionFooter(footer.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsItemView(
    item: SettingsItem,
    keyRevealed: Boolean,
    onTopic: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onValue: (String, String) -> Unit,
    onToggleReveal: () -> Unit,
) {
    when (item) {
        // Prose: the keyboard's help on. Several lines, because topics run long; a pasted newline goes
        // out as a space (`ChannelModeForm.topicToSend`).
        is SettingsItem.TopicField -> FormTextField(
            label = "Topic",
            value = item.text,
            onValueChange = onTopic,
            placeholder = "No topic set.",
            singleLine = false,
            minLines = 2,
        )
        is SettingsItem.TopicText -> Text(
            item.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        is SettingsItem.Notice -> Text(
            item.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SettingsItem.Toggle -> FormSwitchRow(
            label = item.label,
            checked = item.on,
            onCheckedChange = { onToggle(item.letter, it) },
            enabled = item.enabled,
        )
        is SettingsItem.Value -> if (item.isKey) {
            KeyField(item, keyRevealed, onValue, onToggleReveal)
        } else {
            FormTextField(
                label = item.label,
                value = item.value,
                onValueChange = { onValue(item.letter, it) },
                placeholder = item.placeholder,
                identifier = true,
                keyboardType = if (item.letter == "l") KeyboardType.Number else KeyboardType.Ascii,
                enabled = item.enabled,
            )
        }
    }
}

/**
 * The channel key: masked, with an eye to show it. A `+k` channel whose key we never learned leaves the
 * field empty, and an outlined field's placeholder only shows while focused — so "Key is set" is a line
 * under the field, said whether or not it has focus, rather than a placeholder that would hide it.
 */
@Composable
private fun KeyField(item: SettingsItem.Value, revealed: Boolean, onValue: (String, String) -> Unit, onToggleReveal: () -> Unit) {
    val keySet = item.keySet
    OutlinedTextField(
        value = item.value,
        onValueChange = { onValue(item.letter, it) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = FormInset, vertical = 4.dp),
        label = { Text(item.label) },
        placeholder = if (keySet) null else ({ Text(item.placeholder) }),
        supportingText = if (keySet && item.value.isEmpty()) ({ Text(item.placeholder) }) else null,
        enabled = item.enabled,
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        trailingIcon = {
            // Only offered while there's a key in the field to show.
            if (item.value.isNotEmpty()) {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        if (revealed) LurkerIcons.VisibilityOff else LurkerIcons.Visibility,
                        contentDescription = if (revealed) "Hide key" else "Show key",
                    )
                }
            }
        },
    )
}

// MARK: - Previews

private val previewSpec = ModeSpec(
    list = "beIq",
    always = "k",
    onSet = "l",
    flags = "imnpstCR",
    prefix = listOf(PrefixMode("o", "@"), PrefixMode("v", "+")),
    maxModes = 4,
    topicLen = 390,
)

private fun previewScreen(op: Boolean): ChannelSettingsScreen {
    val key = BufferKey(networkId = 1, target = "#lurker")
    val state = ChatState(
        networks = mapOf(1 to Network(id = 1, name = "Libera", state = ConnectionState.Connected, nick = "me", modeSpec = previewSpec)),
        buffers = mapOf(key.id to Buffer(networkId = 1, target = "#lurker", kind = BufferKind.Channel, joined = true, topic = "Lurker — an IRC client")),
        members = mapOf(key.id to listOf(Member("me", modes = if (op) listOf("o") else emptyList()))),
        channelModes = mapOf(key.id to ChannelModeState(modes = "ntkl", params = mapOf("l" to "50"), topicSetBy = "alice", topicSetAt = Instant.ofEpochSecond(1_790_000_000))),
    )
    val slice = ChannelSlice.of(state, key)
    val live = ChannelSettingsModel.live(slice.modes, storedKey = null)
    return ChannelSettingsScreen(
        sections = ChannelSettingsModel.build(slice, live, ChannelModeDrafts(), emptyList()) { "Sep 21, 2026, 10:00" },
        showsSave = ChannelSettingsModel.showsSave(slice.access),
        saveEnabled = false,
    )
}

@Composable
private fun SettingsPreview(dark: Boolean, op: Boolean) {
    LurkerTheme(darkTheme = dark) {
        ChannelSettingsContent(previewScreen(op), keyRevealed = false, onBack = {}, onSave = {}, onTopic = {}, onToggle = { _, _ -> }, onValue = { _, _ -> }, onToggleReveal = {})
    }
}

@Preview(name = "Channel settings, op — light", heightDp = 1100)
@Composable
private fun SettingsOpPreviewLight() = SettingsPreview(dark = false, op = true)

@Preview(name = "Channel settings, op — dark", heightDp = 1100)
@Composable
private fun SettingsOpPreviewDark() = SettingsPreview(dark = true, op = true)

@Preview(name = "Channel settings, read-only — light")
@Composable
private fun SettingsReadOnlyPreviewLight() = SettingsPreview(dark = false, op = false)

@Preview(name = "Channel settings, read-only — dark")
@Composable
private fun SettingsReadOnlyPreviewDark() = SettingsPreview(dark = true, op = false)
