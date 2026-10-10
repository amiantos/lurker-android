// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import android.icu.text.SimpleDateFormat
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import net.amiantos.lurker.ui.message.MessageText
import net.amiantos.lurker.ui.message.MessageTextStyle
import net.amiantos.lurker.ui.message.rememberMessageTextStyle
import net.amiantos.lurker.ui.message.typingGlyph
import net.amiantos.lurker.ui.theme.LurkerIcons
import net.amiantos.lurker.ui.theme.LurkerTheme
import net.amiantos.lurker.ui.shell.CHROME_ALPHA
import net.amiantos.lurker.ui.theme.monoTextStyle
import net.amiantos.lurker.ui.uploads.Attachments
import net.amiantos.lurker.ui.uploads.UploadBatchPosition
import net.amiantos.lurker.ui.uploads.UploadPhase
import net.amiantos.lurker.ui.uploads.UploadReadout
import net.amiantos.lurker.ui.uploads.UploadStatusView
import net.amiantos.lurker.ui.uploads.receivesPastedImages
import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.AwayStrip
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.StatusNotification
import net.amiantos.lurkerkit.model.StatusToast
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * The message composer — lurker-ios's `ComposerBar`, in Material's terms: one block with a status row
 * on top and the field beside a round send button below, after the web client's status bar and input
 * as one piece. iOS draws it as a floating glass slab; here it's an edge-to-edge, square block —
 * Android's motif — laid over the list rather than beside it, translucent so the rows scroll on under
 * it (`FloatingChrome`), which is the glass arrangement without a material Android doesn't have.
 *
 * **The status row** is exactly the one-line field's height and uses the message list's fixed-width
 * face, as the field now does. Nothing moves when its content changes. Left side, one thing at a time:
 *
 * 1. Completion chips while a nick, command or channel is being typed (they take the whole row).
 * 2. A notification from elsewhere (lurker#1098), or a notice about something you just did, for a few
 *    seconds — never over the chips; it waits.
 * 3. The pending reply (lurker-ios#184): "↩ alice: excerpt  ✕".
 * 4. Where you are, with who's typing (lurker-ios#61) appended — out of the message list, so it's
 *    visible at any scroll position.
 *
 * At the right end, a count of highlights waiting in other buffers (lurker#1099), in the roster's
 * count colour; a tap goes back to the list. Hidden beside the list, where the rows themselves show it.
 *
 * Away keeps its own strip above the slab (lurker-ios#135): being away is worth being nagged about,
 * and the row has no room for the time.
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
 * The send button is also the composer's menu (lurker#1117): a long press offers Send, Attach Photo,
 * Take Photo, Attach File and Edit Color, and over an empty field — nothing to send, and where you'd
 * start an attachment — it shows a `+` that opens the menu on a tap. That and an image pasted into the
 * field uploading (`onPasteImage`) come from [attachments], which is null in the system buffer
 * (nothing to attach or colour), where the button is only a send button. While a run is under way its
 * readout sits above everything else in the bar (`UploadStatusView`).
 *
 * Colour is shown as it will be sent: [ComposerState.colorsFor] painted over the field by an
 * `OutputTransformation` ([rememberColorOutput]). The field's text never holds a control code.
 *
 * @param sideBySide the list is beside this screen: the highlight count is hidden.
 * @param onHighlightCountTap the count was tapped — back to the list.
 * @param onToastTap a notification showing in the row was tapped — go to its line.
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
    sideBySide: Boolean = false,
    onHighlightCountTap: () -> Unit = {},
    onToastTap: (StatusNotification) -> Unit = {},
) {
    val capitalizes by autocapitalizes.collectAsStateWithLifecycle()
    val sends by enterSends.collectAsStateWithLifecycle()
    val away = rememberAwayStrip(state.chrome.away, clockKey)
    val colorOutput = rememberColorOutput(state)
    val lead = ComposerModel.statusLead(state.activeToast, state.reply, state.location, state.typists)
    val highlightCount = if (sideBySide) 0 else state.otherHighlights

    // Whether a notice fits the one-line row: measured against the row's width, less the inset and
    // the count, in the row's own face. iOS's `fitsAsNotice`.
    val measurer = rememberTextMeasurer()
    val mono = monoTextStyle()
    val density = LocalDensity.current
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    SideEffect {
        state.noticeFits = { message ->
            val count = ComposerModel.highlightCountLabel(highlightCount)
            val countWidth = if (count == null) 0 else measurer.measure(count, mono, softWrap = false).size.width + with(density) { 8.dp.roundToPx() }
            val room = rowWidthPx - with(density) { (FIELD_INSET_HORIZONTAL * 2 + NOTICE_GLYPH + 6.dp).roundToPx() } - countWidth
            // Not laid out yet (a notice racing the screen open): the row is almost always wide enough,
            // so it's taken rather than floated past a composer with room for it.
            rowWidthPx == 0 || measurer.measure(message, mono, softWrap = false).size.width <= room
        }
        state.onToastShown = { toast, isNew ->
            // A notice gets a tap of feedback — the only feedback its action has. The row's text
            // changing in place is read out by TalkBack through the lead's live region.
            if (isNew && toast is StatusToast.Notice) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }

    ComposerBarContent(
        field = state.field,
        placeholder = ComposerModel.placeholder(state.chrome, state.key, state.kind),
        strip = ComposerModel.strip(away),
        lead = lead,
        replyPending = state.reply != null,
        suggestions = state.suggestions,
        onPick = state::pick,
        highlightCount = highlightCount,
        onHighlightCountTap = onHighlightCountTap,
        onToastTap = {
            // A notification goes somewhere when tapped — and the navigation is queued, not done, so
            // nothing comes on after it: the next toast would flash in a screen about to leave. A
            // notice just clears, and the next one comes on.
            val notification = state.tapToast()
            if (notification != null) onToastTap(notification) else state.nextToast()
        },
        onRowWidth = { rowWidthPx = it },
        capitalizes = capitalizes,
        enterSends = sends,
        focusRequester = state.focusRequester,
        onSend = state::send,
        onTab = state::tabComplete,
        onNewline = state::insertNewline,
        onCancelReply = state::cancelReply,
        onBack = state::back,
        onFocusChange = { focused ->
            state.isFocused = focused
            if (!focused) state.focusLost()
        },
        isComposing = { state.isComposing },
        modifier = modifier,
        above = { UploadStatusView(attachments?.readout, onCancel = { attachments?.cancel() }) },
        fieldModifier = Modifier.receivesPastedImages(attachments),
        inputTransformation = state.colorInput,
        outputTransformation = colorOutput,
        sendButton = if (attachments != null) {
            { canSend, size -> SendMenuButton(canSend, size, attachments, state::send, onEditColor = { state.editorOpen = true }) }
        } else {
            null
        },
    )
    if (state.editorOpen) {
        ColorEditor(state, colorOutput, capitalizes, attachments, onClose = { state.editorOpen = false })
    }
}

/**
 * The colour on the field, painted — [ComposerState.colorsFor] fitted to the text being shown, so a
 * keystroke is drawn in its colour in the frame it's typed, before the composer has caught up with it.
 * Reads composer state, which the field's transformation tracks, so a pick repaints too. Shared by the
 * bar and the colour editor: they show the same field.
 */
@Composable
internal fun rememberColorOutput(state: ComposerState): OutputTransformation {
    val palette = LurkerTheme.colors.mirc
    return remember(state, palette) {
        OutputTransformation {
            val text = asCharSequence().toString()
            for (run in state.colorsFor(text).runs()) {
                addStyle(
                    SpanStyle(
                        color = run.fg?.let(palette::getOrNull) ?: Color.Unspecified,
                        background = run.bg?.let(palette::getOrNull) ?: Color.Unspecified,
                    ),
                    run.start,
                    minOf(run.end, length),
                )
            }
        }
    }
}

/** The bar itself, stateless — for previews, and so what it draws is only what it's given. */
@Composable
internal fun ComposerBarContent(
    field: TextFieldState,
    placeholder: String,
    strip: Strip,
    /** The status row's left side; the chips in [suggestions] cover it while they're up. */
    lead: StatusLead,
    /** A reply is pending — whatever the row shows over it. Escape cancels it (`ComposerKeys`). */
    replyPending: Boolean,
    suggestions: List<Suggestion>,
    onPick: (Suggestion) -> Unit,
    /** Highlights waiting in other buffers; 0 hides the count. */
    highlightCount: Int,
    onHighlightCountTap: () -> Unit,
    onToastTap: () -> Unit,
    /** The status row's width, in pixels, for a notice's fit. */
    onRowWidth: (Int) -> Unit,
    capitalizes: Boolean,
    /** The on-screen keyboard's return sends rather than starting a new line. */
    enterSends: Boolean,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    /** Tab from a hardware keyboard: complete in place, backward with Shift. */
    onTab: (backward: Boolean) -> Unit,
    /** Shift+Enter: a newline at the caret (`ComposerKeys` says why the field can't). */
    onNewline: () -> Unit,
    onCancelReply: () -> Unit,
    onBack: () -> Unit,
    /** The field gained (true) or lost (false) focus — only on a change, never for the initial state. */
    onFocusChange: (Boolean) -> Unit,
    isComposing: () -> Boolean,
    modifier: Modifier = Modifier,
    /** Above the strip: an upload's readout. */
    above: @Composable () -> Unit = {},
    /** The field's extra behaviour: taking a pasted image as an upload. */
    fieldModifier: Modifier = Modifier,
    /** Fits the field's colour to the user's edits, and paints it (lurker#1117). */
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    /** The send button with its menu, where there is one; else a plain [SendButton]. */
    sendButton: (@Composable (canSend: Boolean, size: Dp) -> Unit)? = null,
) {
    val colors = LurkerTheme.colors
    val collapsed = collapsedHeight()
    Column(modifier.fillMaxWidth()) {
        // Above the block, over the list: an upload's readout and the away strip, in the rows' gutter.
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            above()
            when (strip) {
                Strip.None -> Unit
                is Strip.Away -> Box(Modifier.padding(bottom = 6.dp)) { AwayStripRow(strip, onBack) }
            }
        }
        // The block: edge to edge and square, Android's motif rather than iOS's floating glass, holding
        // the status row on top and the field + send below, with a hairline along its top. Translucent,
        // so the rows scroll on under it (`FloatingChrome`); it pads itself for the keyboard and the
        // navigation bar, which is why its height is what the list reserves.
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CHROME_ALPHA))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            HorizontalDivider(thickness = Dp.Hairline, color = colors.border)
            StatusRow(
                lead = lead,
                suggestions = suggestions,
                onPick = onPick,
                highlightCount = highlightCount,
                onHighlightCountTap = onHighlightCountTap,
                onToastTap = onToastTap,
                onCancelReply = onCancelReply,
                modifier = Modifier.fillMaxWidth().height(collapsed).onSizeChanged { onRowWidth(it.width) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = FIELD_INSET_HORIZONTAL),
                thickness = Dp.Hairline,
                color = colors.border,
            )
            Row(
                // The button sits at the BOTTOM, beside the last line as the field grows upward.
                verticalAlignment = Alignment.Bottom,
            ) {
                Field(
                    field, placeholder, capitalizes, enterSends, collapsed, focusRequester, onFocusChange,
                    remember(onSend, onTab, onNewline, onCancelReply) { FieldKeys(onSend, onTab, onNewline, onCancelReply) },
                    isComposing, replyPending, fieldModifier, inputTransformation, outputTransformation,
                )
                // Derived, so the bar recomposes when the answer flips rather than on every keystroke.
                val canSend by remember(field) { derivedStateOf { ComposerModel.sendable(field.text.toString()) != null } }
                // Sized to sit inside the one-line field with an even margin.
                val send = collapsed - SEND_INSET * 2
                Box(Modifier.padding(SEND_INSET)) {
                    if (sendButton != null) sendButton(canSend, send) else SendButton(enabled = canSend, size = send, onClick = onSend)
                }
            }
        }
    }
}

/**
 * The height of the collapsed field: exactly one line of the fixed-width face plus its inset — the
 * field's floor, the status row's height, and what the send button is sized from, so the empty bar
 * and the one-line bar are the same height. Follows the font scale, as iOS's follows Dynamic Type.
 * The jump-to-latest pill matches it.
 */
@Composable
internal fun collapsedHeight(): Dp {
    val line = monoTextStyle().lineHeight
    return with(LocalDensity.current) { line.toDp() } + FIELD_INSET_VERTICAL * 2
}

private val FIELD_INSET_VERTICAL = 10.dp
private val FIELD_INSET_HORIZONTAL = 14.dp

/** How far the send circle sits in from the slab's edge. */
private val SEND_INSET = 5.dp

/** The away strip's corner radius: a rounded rectangle, floating above the block. */
private val SLAB_RADIUS = 20.dp

/** The notice glyph's size, a little under the text's. */
private val NOTICE_GLYPH = 16.dp

// MARK: - The status row

/**
 * The row across the top of the slab. Always there, at a fixed height, so nothing it shows or hides
 * ever moves the conversation. The chips own the whole row while they're up: they're about the word
 * under the caret, and they're gone the moment it's finished.
 */
@Composable
private fun StatusRow(
    lead: StatusLead,
    suggestions: List<Suggestion>,
    onPick: (Suggestion) -> Unit,
    highlightCount: Int,
    onHighlightCountTap: () -> Unit,
    onToastTap: () -> Unit,
    onCancelReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (suggestions.isNotEmpty()) {
            SuggestionsView(suggestions, onPick, Modifier.fillMaxWidth().fillMaxHeight())
            return@Box
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(start = FIELD_INSET_HORIZONTAL), verticalAlignment = Alignment.CenterVertically) {
            StatusLeadText(lead, onToastTap, Modifier.weight(1f))
            if (lead is StatusLead.Reply) {
                // Drawn at the text's size, but taking touches across the row's full height (and wider
                // than it draws): a target that short is easy to miss.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .defaultMinSize(minWidth = 40.dp)
                        .clickable(role = Role.Button, onClick = onCancelReply)
                        .clearAndSetSemantics {
                            contentDescription = "Cancel reply"
                            role = Role.Button
                            onClick { onCancelReply(); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(LurkerIcons.Cancel, contentDescription = null, tint = LurkerTheme.colors.fgMuted, modifier = Modifier.size(18.dp))
                }
            }
            val label = ComposerModel.highlightCountLabel(highlightCount)
            if (label != null) HighlightCount(highlightCount, label, onHighlightCountTap)
        }
    }
}

/**
 * The status row's left side, in the message list's face and the app's own palette: a toast (a tap on
 * a notification goes to it, on a notice clears it), the pending reply, or where you are and who's
 * typing. One line, cut at the end.
 */
@Composable
private fun StatusLeadText(lead: StatusLead, onToastTap: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LurkerTheme.colors
    val style = rememberMessageTextStyle()
    val mono = monoTextStyle()
    val muted = SpanStyle(color = colors.fgMuted)
    when (lead) {
        is StatusLead.Toast -> {
            val toast = lead.toast
            val text = toastText(toast, style)
            // Said as well as shown, as the floating toast was: the row's text changes in place, which
            // TalkBack doesn't announce on its own.
            val semantics = Modifier.clearAndSetSemantics {
                contentDescription = text.text
                liveRegion = LiveRegionMode.Polite
                if (toast is StatusToast.Notification) {
                    role = Role.Button
                    onClick(label = "Open the conversation") { onToastTap(); true }
                }
            }
            Row(
                modifier.fillMaxHeight().clickable(onClick = onToastTap).then(semantics),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // A notice is in the error colour behind a glyph, so "Not connected" can't be read as
                // someone's line.
                if (toast is StatusToast.Notice) {
                    Icon(LurkerIcons.ErrorOutline, contentDescription = null, tint = colors.bad, modifier = Modifier.size(NOTICE_GLYPH))
                }
                Text(text, style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        is StatusLead.Reply -> Text(
            buildAnnotatedString {
                withStyle(muted) { append("↩ ") }
                withStyle(SpanStyle(color = colors.fg)) { append(lead.name) }
                if (lead.excerpt.isNotEmpty()) withStyle(muted) { append(": " + lead.excerpt) }
            },
            style = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.clearAndSetSemantics { contentDescription = lead.accessibility },
        )
        is StatusLead.Where -> {
            // Where you are, then who's typing there: "#lurker ⌨ alice, bob". The keyboard glyph is the
            // separator; a status reads in parentheses, "(Offline)", "(Away)".
            val location = lead.location
            val text = buildAnnotatedString {
                if (location != null) {
                    withStyle(muted) {
                        append(location.name)
                        val connection = location.connection
                        if (connection != null) append(" $connection")
                        val detail = location.detail
                        if (!detail.isNullOrEmpty()) append(" ($detail)")
                    }
                }
                if (lead.typists.isNotEmpty()) {
                    if (length > 0) append(" ")
                    append(MessageText.renderCompactTyping(lead.typists, style, inline = true) ?: AnnotatedString(""))
                }
            }
            val spoken = lead.accessibility
            Text(
                text,
                style = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                inlineContent = if (lead.typists.isNotEmpty()) typingGlyph(colors.fgMuted) else emptyMap(),
                modifier = modifier.clearAndSetSemantics { spoken?.let { contentDescription = it } },
            )
        }
    }
}

/**
 * Who and what — "bob: are you around?" — the way the line reads in the buffer; where it happened is
 * the tap's job. A kick has no speaker worth naming, so it says where. A friend coming online has no
 * line. A notice is just its words, in the error colour. iOS's `StatusToast.attributedText`.
 */
internal fun toastText(toast: StatusToast, style: MessageTextStyle): AnnotatedString {
    val colors = style.colors
    val muted = SpanStyle(color = colors.fgMuted)
    return buildAnnotatedString {
        when (toast) {
            is StatusToast.Notice -> withStyle(SpanStyle(color = colors.bad)) { append(toast.message) }
            is StatusToast.Notification -> {
                val notification = toast.notification
                if (notification.kind == StatusNotification.Kind.Kicked) {
                    withStyle(muted) { append("Kicked from " + notification.key.target) }
                } else {
                    val nick = notification.nick ?: "?"
                    withStyle(SpanStyle(color = MessageText.hashedColor(nick, style))) { append(nick) }
                    if (notification.kind == StatusNotification.Kind.FriendOnline) withStyle(muted) { append(" came online") }
                }
                if (notification.text.isNotEmpty()) withStyle(SpanStyle(color = colors.fg)) { append(": " + notification.text) }
            }
        }
    }
}

/**
 * The status row's right end: highlights waiting in other buffers, a plain number in the roster's
 * count colour, like a buffer row's. Tapping it goes back to the list.
 */
@Composable
private fun HighlightCount(count: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$count highlight" + (if (count == 1) "" else "s") + " in other buffers"
                role = Role.Button
                onClick(label = "Back to the buffer list") { onClick(); true }
            }
            // A few points past the text inset: the slab's corner curves in at the right end.
            .padding(start = 8.dp, end = FIELD_INSET_HORIZONTAL + 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = monoTextStyle(), color = LurkerTheme.colors.warn, maxLines = 1)
    }
}

/** What the field's keys do — see `ComposerKeys`. */
private class FieldKeys(
    val onSend: () -> Unit,
    val onTab: (backward: Boolean) -> Unit,
    val onNewline: () -> Unit,
    val onCancelReply: () -> Unit,
)

@Composable
private fun RowScope.Field(
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
    inputTransformation: InputTransformation?,
    outputTransformation: OutputTransformation?,
) {
    val colors = LurkerTheme.colors
    // The message list's fixed-width face, like the status row above it — the slab reads as one
    // terminal-ish piece, the way the web's input and status bar do.
    val text = monoTextStyle()
    val focused = remember { booleanArrayOf(false) }
    val keys = remember { ComposerKeys() }
    BasicTextField(
        state = field,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        modifier = Modifier
            .weight(1f)
            .then(modifier)
            .heightIn(min = collapsed)
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
                    ComposerKeys.Action.Newline -> actions.onNewline()
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
 * Messages' send button does — with a white arrow on it; clear and disabled when there isn't. It
 * can't be raised on its own — a circle inside the slab — so it's a fill: the slab's own over an
 * empty field, the accent when there's something to send.
 */
@Composable
private fun SendButton(enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val colors = LurkerTheme.colors
    Box(
        Modifier
            .size(size)
            .background(if (enabled) colors.accent else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
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
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The send button as the composer's menu (lurker#1117) — iOS's `sendMenu`. With something to send it's
 * the lit arrow and a tap sends; a long press opens the menu either way, and over an empty field the
 * button is a `+` whose tap opens it: an empty field is where an attachment starts, and a disabled
 * button couldn't open anything.
 *
 * The attach items grey out while a run is under way rather than doing nothing when picked. Take Photo
 * is left out on a device without a camera.
 */
@Composable
private fun SendMenuButton(
    canSend: Boolean,
    size: Dp,
    attachments: Attachments,
    onSend: () -> Unit,
    onEditColor: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val open = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        expanded = true
    }
    Box {
        SendFace(
            canSend = canSend,
            size = size,
            modifier = Modifier
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (canSend) onSend() else expanded = true },
                    onLongClick = open,
                )
                .clearAndSetSemantics {
                    contentDescription = if (canSend) "Send" else "Add"
                    role = Role.Button
                    onClick { if (canSend) onSend() else expanded = true; true }
                    onLongClick(label = "Attachments and color") { expanded = true; true }
                },
        )
        // ⚠ Not focusable: a focusable popup takes the window's focus, and the IME goes down with it —
        // so opening the menu mid-sentence closed the keyboard. Outside taps still dismiss it; only the
        // back key no longer does, and a tap anywhere is the same gesture.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false),
        ) {
            @Composable
            fun item(title: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
                text = { Text(title) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    action()
                },
            )
            if (canSend) item("Send", LurkerIcons.ArrowUpward, action = onSend)
            item("Attach Photo", LurkerIcons.PhotoLibrary, enabled = !attachments.busy, action = attachments::pickPhotos)
            if (attachments.hasCamera) {
                item("Take Photo", LurkerIcons.PhotoCamera, enabled = !attachments.busy, action = attachments::takePhoto)
            }
            item("Attach File", LurkerIcons.AttachFile, enabled = !attachments.busy, action = attachments::pickFiles)
            item("Edit Color", LurkerIcons.Palette, action = onEditColor)
        }
    }
}

/** The send button as drawn: the lit arrow with something to send, else the `+` that opens the menu. */
@Composable
private fun SendFace(canSend: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val colors = LurkerTheme.colors
    Box(
        Modifier
            .size(size)
            .background(if (canSend) colors.accent else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (canSend) LurkerIcons.ArrowUpward else LurkerIcons.Add,
            contentDescription = null,
            tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * "**Away** since 2:32 PM · lunch   Back" (lurker-ios#135), in its own strip above the slab, the
 * slab's shape. The indicator and the way out are one control, so getting back doesn't depend on
 * remembering `/back`.
 */
@Composable
private fun AwayStripRow(strip: Strip.Away, onBack: () -> Unit) {
    val colors = LurkerTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(SLAB_RADIUS))
            .padding(start = FIELD_INSET_HORIZONTAL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.fg)) { append(strip.lead) }
                withStyle(SpanStyle(color = muted)) { append(strip.detail) }
            },
            style = monoTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 7.dp)
                .clearAndSetSemantics { contentDescription = strip.accessibility },
        )
        Box(
            Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                // TalkBack: "Back, double-tap to clear your away status" — iOS's hint.
                .clickable(role = Role.Button, onClickLabel = "clear your away status", onClick = onBack)
                // Clear of the strip's rounded end, which a bare title would crowd.
                .padding(start = 10.dp, end = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Back", style = monoTextStyle(), color = colors.accent)
        }
    }
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
    strip: Strip = Strip.None,
    lead: StatusLead = StatusLead.Where(Location("#lurker", null, null), emptyList()),
    suggestions: List<Suggestion> = emptyList(),
    highlightCount: Int = 0,
    placeholder: String = "> @amiantos",
    attaches: Boolean = false,
    readout: UploadReadout? = null,
) {
    LurkerTheme(darkTheme = dark) {
        Box(Modifier.background(LurkerTheme.colors.bg).padding(top = 12.dp)) {
            ComposerBarContent(
                field = remember { TextFieldState(text) },
                placeholder = placeholder,
                strip = strip,
                lead = lead,
                replyPending = lead is StatusLead.Reply,
                suggestions = suggestions,
                onPick = {},
                highlightCount = highlightCount,
                onHighlightCountTap = {},
                onToastTap = {},
                onRowWidth = {},
                capitalizes = true,
                enterSends = false,
                focusRequester = remember { FocusRequester() },
                onSend = {},
                onTab = {},
                onNewline = {},
                onCancelReply = {},
                onBack = {},
                onFocusChange = {},
                isComposing = { false },
                above = { UploadStatusView(readout, onCancel = {}) },
                sendButton = if (attaches) { canSend, size -> SendFace(canSend, size) } else null,
            )
        }
    }
}

private val previewReply = StatusLead.Reply(name = "alice", excerpt = "anyone tried the new build on a Pixel?")
private val previewAway = Strip.Away(lead = "Away", detail = " since 2:32 PM · lunch")
private val previewTyping = StatusLead.Where(Location("#lurker", null, null), listOf("alice", "bob"))
private val previewOffline = StatusLead.Where(Location("#lurker", "(Offline)", null), emptyList())
private val previewToast = StatusLead.Toast(
    StatusToast.Notification(
        StatusNotification(
            kind = StatusNotification.Kind.Highlight, key = BufferKey(networkId = 1, target = "#android"), nick = "bob",
            text = "are you around?", messageId = 1, date = Instant.EPOCH,
        ),
    ),
)
private val previewNotice = StatusLead.Toast(StatusToast.Notice("Not connected — bookmark unchanged"))
private val previewNicks = listOf("alice", "bob", "carol_", "dave", "erin").map(Suggestion::nick)

@Preview(name = "Composer, empty — light", widthDp = 360)
@Composable
private fun EmptyLight() = ComposerPreview(dark = false, text = "")

@Preview(name = "Composer, empty — dark", widthDp = 360)
@Composable
private fun EmptyDark() = ComposerPreview(dark = true, text = "")

@Preview(name = "Composer, typing a reply — light", widthDp = 360)
@Composable
private fun ReplyLight() = ComposerPreview(dark = false, text = "alice: yes, on a 9 — works", lead = previewReply, highlightCount = 3)

@Preview(name = "Composer, typing a reply — dark", widthDp = 360)
@Composable
private fun ReplyDark() = ComposerPreview(dark = true, text = "alice: yes, on a 9 — works", lead = previewReply, highlightCount = 3)

@Preview(name = "Composer, someone typing — light", widthDp = 360)
@Composable
private fun TypingLight() = ComposerPreview(dark = false, text = "", lead = previewTyping)

@Preview(name = "Composer, offline — dark", widthDp = 360)
@Composable
private fun OfflineDark() = ComposerPreview(dark = true, text = "", lead = previewOffline)

@Preview(name = "Composer, a toast — light", widthDp = 360)
@Composable
private fun ToastLight() = ComposerPreview(dark = false, text = "", lead = previewToast, highlightCount = 1)

@Preview(name = "Composer, a notice — dark", widthDp = 360)
@Composable
private fun NoticeDark() = ComposerPreview(dark = true, text = "", lead = previewNotice)

@Preview(name = "Composer, nick chips — light", widthDp = 360)
@Composable
private fun ChipsLight() = ComposerPreview(dark = false, text = "hey al", suggestions = previewNicks)

@Preview(name = "Composer, nick chips — dark", widthDp = 360)
@Composable
private fun ChipsDark() = ComposerPreview(dark = true, text = "hey al", suggestions = previewNicks)

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
)

@Preview(name = "Composer, five lines — dark", widthDp = 360)
@Composable
private fun TallDark() = ComposerPreview(
    dark = true,
    text = "one\ntwo\nthree\nfour\nfive\nsix — past the cap, so it scrolls",
)

@Preview(name = "Composer, system buffer — light", widthDp = 360)
@Composable
private fun ConsoleLight() = ComposerPreview(dark = false, text = "", placeholder = "Type a command…", lead = StatusLead.Where(Location("Lurker", null, null), emptyList()))

@Preview(name = "Composer, system buffer — dark", widthDp = 360)
@Composable
private fun ConsoleDark() = ComposerPreview(dark = true, text = "", placeholder = "Type a command…", lead = StatusLead.Where(Location("Lurker", null, null), emptyList()))

private val previewReadout = UploadReadout(UploadPhase.Uploading(0.42), UploadBatchPosition(2, 4))

@Preview(name = "Composer, uploading — light", widthDp = 360)
@Composable
private fun UploadingLight() = ComposerPreview(dark = false, text = "", attaches = true, readout = previewReadout)

@Preview(name = "Composer, uploading — dark", widthDp = 360)
@Composable
private fun UploadingDark() = ComposerPreview(dark = true, text = "", attaches = true, readout = previewReadout)
