// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import android.icu.text.SimpleDateFormat
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.uploads.AttachButton
import net.amiantos.lurker.ui.uploads.Attachments
import net.amiantos.lurker.ui.uploads.UploadBatchPosition
import net.amiantos.lurker.ui.uploads.UploadPhase
import net.amiantos.lurker.ui.uploads.UploadReadout
import net.amiantos.lurker.ui.uploads.UploadStatusView
import net.amiantos.lurker.ui.uploads.receivesPastedImages
import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.AwayStrip
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * The message composer — lurker-ios's `ComposerBar`, in Material's terms: a field that grows with
 * the text up to five lines and then scrolls, a round send button that lights up in the accent
 * when there's something to send, and above them one strip that says either what you're replying
 * to or that you're away. iOS floats three glass pills over the conversation; here the bar sits on
 * the log's own ground at the bottom of the screen and the list's reservation includes it, which is
 * the same arrangement without a material Android doesn't have.
 *
 * The on-screen keyboard's return inserts a newline, so a multi-line message is something you can
 * actually type — unless "Enter to send" is on (lurker-android#64), when it reads "Send" and sends.
 * A hardware keyboard's Enter always sends and Shift+Enter is the newline; Tab completes a nick or a
 * channel in place, the web's way; Escape cancels a pending reply (lurker-android#63, `ComposerKeys`).
 * An on-screen key is left alone while an IME is composing; a hardware key, which reaches the field
 * only after the IME passed on it, isn't (see `ComposerKeys`).
 *
 * Rides the keyboard: the bar pads itself by the IME (and the navigation bar under it), so it sits
 * on top of whichever is taller, and the list above — laid out in reverse, item 0 at the bottom —
 * keeps its newest row anchored as the bar and the keyboard grow.
 *
 * The paperclip (iOS's `onAttach`) leads the row and an image pasted into the field uploads
 * (`onPasteImage`) — both from [attachments], which is null in the system buffer (nothing to attach)
 * and drops the paperclip there. While a run is under way its readout sits above everything else in
 * the bar (`UploadStatusView`).
 */
@Composable
internal fun ComposerBar(
    state: ComposerState,
    autocapitalizes: StateFlow<Boolean>,
    /** "Enter to send" (`UiPreferences.composerEnterSends`). */
    enterSends: StateFlow<Boolean>,
    /** Changes when the day or the zone does — "Away since 2:32 PM" stops being true at midnight. */
    clockKey: Any?,
    modifier: Modifier = Modifier,
    attachments: Attachments? = null,
) {
    val capitalizes by autocapitalizes.collectAsStateWithLifecycle()
    val sends by enterSends.collectAsStateWithLifecycle()
    val away = rememberAwayStrip(state.chrome.away, clockKey)
    ComposerBarContent(
        field = state.field,
        placeholder = ComposerModel.placeholder(state.chrome, state.key, state.kind),
        strip = ComposerModel.strip(state.reply, away),
        capitalizes = capitalizes,
        enterSends = sends,
        focusRequester = state.focusRequester,
        onSend = state::send,
        onTab = state::tabComplete,
        onCancelReply = state::cancelReply,
        onBack = state::back,
        onFocusChange = { focused ->
            state.isFocused = focused
            if (!focused) state.focusLost()
        },
        isComposing = { state.isComposing },
        modifier = modifier,
        leading = if (attachments != null) { size -> AttachButton(attachments, size) } else null,
        above = { UploadStatusView(attachments?.readout, onCancel = { attachments?.cancel() }) },
        fieldModifier = Modifier.receivesPastedImages(attachments),
    )
}

/** The bar itself, stateless — for previews, and so what it draws is only what it's given. */
@Composable
internal fun ComposerBarContent(
    field: TextFieldState,
    placeholder: String,
    strip: Strip,
    capitalizes: Boolean,
    /** The on-screen keyboard's return sends rather than starting a new line. */
    enterSends: Boolean,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    /** Tab from a hardware keyboard: complete in place, backward with Shift. */
    onTab: (backward: Boolean) -> Unit,
    onCancelReply: () -> Unit,
    onBack: () -> Unit,
    /** The field gained (true) or lost (false) focus — only on a change, never for the initial state. */
    onFocusChange: (Boolean) -> Unit,
    isComposing: () -> Boolean,
    modifier: Modifier = Modifier,
    /** Leads the row, sized to the collapsed field: the paperclip. */
    leading: (@Composable (size: Dp) -> Unit)? = null,
    /** Above the strip: an upload's readout. */
    above: @Composable () -> Unit = {},
    /** The field's extra behaviour: taking a pasted image as an upload. */
    fieldModifier: Modifier = Modifier,
) {
    val colors = LurkerTheme.colors
    val collapsed = collapsedHeight()
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            // Match the message rows' horizontal inset, so the bar's edges line up with the column of
            // text above it.
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        above()
        when (strip) {
            Strip.None -> Unit
            is Strip.Reply -> ReplyStrip(strip, onCancelReply)
            is Strip.Away -> AwayStripRow(strip, onBack)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // The button sits at the BOTTOM, beside the last line as the field grows upward.
            verticalAlignment = Alignment.Bottom,
        ) {
            leading?.invoke(collapsed)
            Field(
                field, placeholder, capitalizes, enterSends, collapsed, focusRequester, onFocusChange,
                remember(onSend, onTab, onCancelReply) { FieldKeys(onSend, onTab, onCancelReply) },
                isComposing, strip is Strip.Reply, fieldModifier,
            )
            // Derived, so the bar recomposes when the answer flips rather than on every keystroke.
            val canSend by remember(field) { derivedStateOf { ComposerModel.sendable(field.text.toString()) != null } }
            SendButton(enabled = canSend, size = collapsed, onClick = onSend)
        }
    }
}

/**
 * The height of the collapsed field: exactly one line of body text plus its inset — the field's
 * floor *and* the send button's size, so the empty bar and the one-line bar are the same height.
 * Follows the font scale, as iOS's follows Dynamic Type. The jump-to-latest pill matches it.
 */
@Composable
internal fun collapsedHeight(): Dp {
    val line = MaterialTheme.typography.bodyLarge.lineHeight
    return with(LocalDensity.current) { line.toDp() } + FIELD_INSET_VERTICAL * 2
}

private val FIELD_INSET_VERTICAL = 10.dp
private val FIELD_INSET_HORIZONTAL = 14.dp

/** What the field's keys do — see `ComposerKeys`. */
private class FieldKeys(val onSend: () -> Unit, val onTab: (backward: Boolean) -> Unit, val onCancelReply: () -> Unit)

@Composable
private fun androidx.compose.foundation.layout.RowScope.Field(
    field: TextFieldState,
    placeholder: String,
    capitalizes: Boolean,
    enterSends: Boolean,
    collapsed: Dp,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    actions: FieldKeys,
    isComposing: () -> Boolean,
    replyPending: Boolean,
    modifier: Modifier,
) {
    val colors = LurkerTheme.colors
    val text = MaterialTheme.typography.bodyLarge
    val focused = remember { booleanArrayOf(false) }
    val keys = remember { ComposerKeys() }
    BasicTextField(
        state = field,
        modifier = Modifier
            .weight(1f)
            .then(modifier)
            .heightIn(min = collapsed)
            // A fixed radius, not a capsule: half the one-line height, so it's a capsule when short
            // and a rounded rectangle when tall, rather than arcs that clip the text as it grows.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(collapsed / 2))
            .focusRequester(focusRequester)
            .onFocusChanged { focus ->
                if (focused[0] != focus.isFocused) {
                    focused[0] = focus.isFocused
                    onFocusChange(focus.isFocused)
                }
            }
            .onPreviewKeyEvent { event ->
                // Enter sends, Tab completes, Escape cancels a pending reply — each only when
                // `ComposerKeys` says so, and never mid-composition, where the key is the IME's.
                // Anything it passes does whatever it would.
                val down = when (event.type) {
                    KeyEventType.KeyDown -> true
                    KeyEventType.KeyUp -> false
                    else -> return@onPreviewKeyEvent false
                }
                val key = composerKey(event)
                val action = keys.onKey(
                    ComposerKeys.Event(
                        key = key,
                        down = down,
                        shift = event.isShiftPressed,
                        otherModifier = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed,
                        // Of every key: it decides Enter, and whether a composition holds any key back.
                        hardware = isHardwareKey(event),
                        composing = isComposing(),
                        enterSends = enterSends,
                        replyPending = replyPending,
                    ),
                )
                when (action) {
                    ComposerKeys.Action.Pass -> return@onPreviewKeyEvent false
                    ComposerKeys.Action.Send -> actions.onSend()
                    ComposerKeys.Action.Complete -> actions.onTab(false)
                    ComposerKeys.Action.CompleteBackward -> actions.onTab(true)
                    ComposerKeys.Action.CancelReply -> actions.onCancelReply()
                    ComposerKeys.Action.Swallow -> Unit
                }
                true
            },
        textStyle = text.copy(color = colors.fg),
        cursorBrush = SolidColor(colors.accent),
        // Capitals per the device setting (`UiPreferences.composerAutocapitalizes`); autocorrect left
        // at the keyboard's own default — the user's system-wide preference stays the boss.
        //
        // "Enter to send" (`UiPreferences.composerEnterSends`) asks for the Send action: the field stays
        // multi-line (a hardware Shift+Enter, or a pasted line break, still makes one), but the
        // keyboard's return key reads "Send" and fires `onKeyboardAction` instead of typing a newline.
        // Off, the action is left at Default, which a multi-line field turns into a plain newline key.
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalizes) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            imeAction = if (enterSends) ImeAction.Send else ImeAction.Default,
        ),
        // The Send button's own path, checks and all: an empty draft does nothing — and, the default
        // action never being run, no newline goes in either.
        onKeyboardAction = if (enterSends) KeyboardActionHandler { actions.onSend() } else null,
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = ComposerModel.MAX_LINES),
        decorator = { inner ->
            Box(Modifier.padding(horizontal = FIELD_INSET_HORIZONTAL, vertical = FIELD_INSET_VERTICAL)) {
                // At the text's own origin, in its own style, so it's indistinguishable from a caret on
                // an empty line. Gone the moment there's text.
                if (field.text.isEmpty()) {
                    Text(placeholder, style = text, color = colors.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
}

/** The key as `ComposerKeys` names it — the numpad's Enter is Enter. */
private fun composerKey(event: KeyEvent): ComposerKeys.Key = when (event.key) {
    Key.Enter, Key.NumPadEnter -> ComposerKeys.Key.Enter
    Key.Tab -> ComposerKeys.Key.Tab
    Key.Escape -> ComposerKeys.Key.Escape
    else -> ComposerKeys.Key.Other
}

/**
 * Whether [event] came from a physical keyboard. ⚠ Some on-screen keyboards send a real
 * `KEYCODE_ENTER` rather than text or an editor action, and that Enter must follow "Enter to send"
 * rather than always sending. Theirs arrive from a virtual device: `VIRTUAL_KEYBOARD` (-1), and
 * `InputDevice.isVirtual` is exactly "a negative id", so the id alone answers it.
 */
private fun isHardwareKey(event: KeyEvent): Boolean = event.nativeKeyEvent.deviceId >= 0

/**
 * Round, and lit in the accent when there's something to send — the "lights up when it goes live"
 * Messages' send button does — with a white arrow on it; clear and disabled when there isn't.
 */
@Composable
private fun SendButton(enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val colors = LurkerTheme.colors
    Box(
        Modifier
            .size(size)
            .background(if (enabled) colors.accent else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "Send"
                role = Role.Button
                if (enabled) onClick { onClick(); true } else disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            LurkerIcons.ArrowUpward,
            contentDescription = null,
            tint = if (enabled) Color.White else colors.fgMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The strip's shape and ground — one slot, whichever of the two it says. */
@Composable
private fun StripRow(label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit, button: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(22.dp))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label()
        button()
    }
}

/**
 * "Replying to alice: what she said  ⊗" (lurker-ios#184). Where Messages, Discord and Telegram all
 * put it — attached to the thing it changes.
 */
@Composable
private fun ReplyStrip(strip: Strip.Reply, onCancel: () -> Unit) {
    val colors = LurkerTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    StripRow(
        label = {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = muted)) { append("Replying to ") }
                    withStyle(SpanStyle(color = colors.fg, fontWeight = FontWeight.Bold)) { append(strip.name) }
                    if (strip.excerpt.isNotEmpty()) withStyle(SpanStyle(color = muted)) { append(": " + strip.excerpt) }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 7.dp)
                    .clearAndSetSemantics { contentDescription = strip.accessibility },
            )
        },
        button = {
            // The only way to cancel by touch, so a full 48dp target, inside the strip (which it sizes).
            Box(
                Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .clearAndSetSemantics {
                        contentDescription = "Cancel reply"
                        role = Role.Button
                        onClick { onCancel(); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(LurkerIcons.Cancel, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
            }
        },
    )
}

/**
 * "**Away** since 2:32 PM · lunch   Back" (lurker-ios#135). The indicator and the way out are one
 * control, so getting back doesn't depend on remembering `/back`.
 */
@Composable
private fun AwayStripRow(strip: Strip.Away, onBack: () -> Unit) {
    val colors = LurkerTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    StripRow(
        label = {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.fg, fontWeight = FontWeight.Bold)) { append(strip.lead) }
                    withStyle(SpanStyle(color = muted)) { append(strip.detail) }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 7.dp)
                    .clearAndSetSemantics { contentDescription = strip.accessibility },
            )
        },
        button = {
            Box(
                Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                    // TalkBack: "Back, double-tap to clear your away status" — iOS's hint.
                    .clickable(role = Role.Button, onClickLabel = "clear your away status", onClick = onBack)
                    // Clear of the strip's rounded end, which a bare title would crowd.
                    .padding(start = 10.dp, end = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Back", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = colors.accent)
            }
        },
    )
}

/**
 * The away strip's words for [away], or null when you aren't away. Rebuilt when the away changes,
 * when [clockKey] does (midnight, a time-zone change: "since 2:32 PM" needs the date once it's
 * another day, and another hour in another zone), and when the locale or the 24-hour setting
 * does — not on every recomposition, since formatting means a date formatter.
 */
@Composable
private fun rememberAwayStrip(away: AwayState?, clockKey: Any?): AwayStrip? {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24 = DateFormat.is24HourFormat(context)
    return remember(away, clockKey, locale, is24) {
        AwayStrip.make(away) { instant, since -> formatSince(locale, is24, instant, since) }
    }
}

/**
 * The strip's time, from the kit's skeleton (`AwayStrip.Since`). The skeleton's `j` is "the locale's
 * hour"; the device's 24-hour switch outranks the locale, as everywhere else on the phone, so it's
 * swapped for `H` or `h` before the locale picks the pattern.
 */
private fun formatSince(locale: Locale, is24: Boolean, instant: Instant, since: AwayStrip.Since): String {
    val skeleton = since.skeleton.replace("j", if (is24) "H" else "h")
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date.from(instant))
}

// MARK: - Previews

@Composable
private fun ComposerPreview(
    dark: Boolean,
    text: String,
    strip: Strip,
    placeholder: String = "@amiantos",
    attaches: Boolean = false,
    readout: UploadReadout? = null,
) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(LurkerTheme.colors.bg)) {
            ComposerBarContent(
                field = remember { TextFieldState(text) },
                placeholder = placeholder,
                strip = strip,
                capitalizes = true,
                enterSends = false,
                focusRequester = remember { FocusRequester() },
                onSend = {},
                onTab = {},
                onCancelReply = {},
                onBack = {},
                onFocusChange = {},
                isComposing = { false },
                leading = if (attaches) { size -> PreviewPaperclip(size) } else null,
                above = { UploadStatusView(readout, onCancel = {}) },
            )
        }
    }
}

/** The paperclip as drawn, without the run behind it. */
@Composable
private fun PreviewPaperclip(size: Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
        Icon(LurkerIcons.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

private val previewReply = Strip.Reply(name = "alice", excerpt = "anyone tried the new build on a Pixel?")
private val previewAway = Strip.Away(lead = "Away", detail = " since 2:32 PM · lunch")

@Preview(name = "Composer, empty — light", widthDp = 360)
@Composable
private fun EmptyLight() = ComposerPreview(dark = false, text = "", strip = Strip.None)

@Preview(name = "Composer, empty — dark", widthDp = 360)
@Composable
private fun EmptyDark() = ComposerPreview(dark = true, text = "", strip = Strip.None)

@Preview(name = "Composer, typing a reply — light", widthDp = 360)
@Composable
private fun ReplyLight() = ComposerPreview(dark = false, text = "alice: yes, on a 9 — works", strip = previewReply)

@Preview(name = "Composer, typing a reply — dark", widthDp = 360)
@Composable
private fun ReplyDark() = ComposerPreview(dark = true, text = "alice: yes, on a 9 — works", strip = previewReply)

@Preview(name = "Composer, away — light", widthDp = 360)
@Composable
private fun AwayLight() = ComposerPreview(dark = false, text = "", strip = previewAway)

@Preview(name = "Composer, away — dark", widthDp = 360)
@Composable
private fun AwayDark() = ComposerPreview(dark = true, text = "", strip = previewAway)

@Preview(name = "Composer, five lines — light", widthDp = 360)
@Composable
private fun TallLight() = ComposerPreview(
    dark = false,
    text = "one\ntwo\nthree\nfour\nfive\nsix — past the cap, so it scrolls",
    strip = Strip.None,
)

@Preview(name = "Composer, five lines — dark", widthDp = 360)
@Composable
private fun TallDark() = ComposerPreview(
    dark = true,
    text = "one\ntwo\nthree\nfour\nfive\nsix — past the cap, so it scrolls",
    strip = Strip.None,
)

@Preview(name = "Composer, system buffer — light", widthDp = 360)
@Composable
private fun ConsoleLight() = ComposerPreview(dark = false, text = "", strip = Strip.None, placeholder = "Type a command…")

@Preview(name = "Composer, system buffer — dark", widthDp = 360)
@Composable
private fun ConsoleDark() = ComposerPreview(dark = true, text = "", strip = Strip.None, placeholder = "Type a command…")

private val previewReadout = UploadReadout(UploadPhase.Uploading(0.42), UploadBatchPosition(2, 4))

@Preview(name = "Composer, uploading — light", widthDp = 360)
@Composable
private fun UploadingLight() = ComposerPreview(dark = false, text = "", strip = Strip.None, attaches = true, readout = previewReadout)

@Preview(name = "Composer, uploading — dark", widthDp = 360)
@Composable
private fun UploadingDark() = ComposerPreview(dark = true, text = "", strip = Strip.None, attaches = true, readout = previewReadout)
