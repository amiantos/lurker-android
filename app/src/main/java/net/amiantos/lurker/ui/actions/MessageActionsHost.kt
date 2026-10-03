// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.LocalAppEvents
import net.amiantos.lurker.ui.message.RowPress
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageActionScope
import net.amiantos.lurkerkit.session.ChatViewModel

/** Which of a conversation's message sheets is up. */
internal sealed interface ActionSheet {
    data class Actions(val subject: ActionSubject, val header: ActionHeader, val rows: List<ActionRow>) : ActionSheet

    /** The reaction sheet: who reacted, and a way to add yours. */
    data class Reactions(val message: Message) : ActionSheet

    data class Ignore(val subject: String, val defaultMask: String) : ActionSheet
}

/**
 * One conversation's message sheets — the actions sheet, the reaction sheet, the Ignore dialog — and
 * the decision of which a long press opens. lurker-ios's `ChatViewController` MARK "Per-message
 * actions (#60)". One at a time: a press while a sheet is up is a mis-tap through a dimmed screen.
 *
 * Not saved across a configuration change: a rotation closes the sheet (the line it was about is
 * still there to press again), where saving it would mean saving a message.
 */
@Stable
internal class MessageActionsState(private val model: ChatViewModel, private val key: BufferKey) {
    var sheet: ActionSheet? by mutableStateOf(null)
        private set

    /**
     * A long press landed — open what it's about, and say whether anything opened. The scope is read
     * HERE, at the press, so Save/Remove reflects the store as of the press; it's then carried to the
     * run rather than re-read there (`MessageActionsModel.run`).
     */
    fun press(press: RowPress): Boolean {
        if (sheet != null) return false
        sheet = when (press) {
            is RowPress.Reactions -> ActionSheet.Reactions(press.message)
            is RowPress.Link -> actions(ActionSubject.Link(press.url))
            is RowPress.Line -> actions(
                ActionSubject.Line(
                    press.message,
                    MessageActionScope(
                        networkId = key.networkId,
                        isBookmarked = model.isBookmarked(press.message.id),
                        target = key.target,
                        canReact = model.state.canReact(key.networkId),
                    ),
                ),
            )
        }
        return sheet != null
    }

    /** Nil when the subject offers nothing — no empty sheet, as iOS's failable init. */
    private fun actions(subject: ActionSubject): ActionSheet.Actions? {
        val rows = MessageActionsModel.rows(subject)
        if (rows.isEmpty()) return null
        return ActionSheet.Actions(subject, MessageActionsModel.header(subject), rows)
    }

    /** The add chip, or a chip that can't toggle: the reaction sheet for that line. */
    fun showReactions(message: Message) {
        if (sheet == null) sheet = ActionSheet.Reactions(message)
    }

    fun showIgnore(message: Message, subject: String) {
        sheet = ActionSheet.Ignore(subject, MessageActionsModel.defaultIgnoreMask(message, subject))
    }

    fun dismiss() {
        sheet = null
    }
}

@Composable
internal fun rememberMessageActionsState(model: ChatViewModel, key: BufferKey): MessageActionsState =
    remember(model, key) { MessageActionsState(model, key) }

/**
 * Draws whichever sheet is up, and runs what it picked — the platform half: the composer, the
 * clipboard, the share sheet, the browser, the profile.
 *
 * @param onReply the composer's Reply (`ComposerState.startReply`), given the line as the list shows it.
 * @param onShowProfile the profile dialog, for this buffer's network.
 */
@Composable
internal fun MessageActionsHost(
    state: MessageActionsState,
    model: ChatViewModel,
    key: BufferKey,
    onReply: (Message) -> Unit,
    onShowProfile: (networkId: Int, nick: String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val events = LocalAppEvents.current
    val scope = rememberCoroutineScope()
    fun notice(text: String) {
        events?.send(AppEvent.Notice(text))
    }

    fun copy(label: String, text: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
    }

    val effects = MessageActionsModel.LineEffects(
        reply = onReply,
        copy = { text -> copy("Message", text) },
        setBookmark = { id, saved ->
            // ⚠ A WRITE. Nothing retries it, so a send that went nowhere says so rather than nothing.
            if (!model.setBookmark(messageId = id, saved = saved)) notice(ReactionSheetModel.NOT_CONNECTED)
        },
        showProfile = { nick -> key.networkId?.let { onShowProfile(it, nick) } },
        react = state::showReactions,
        ignore = state::showIgnore,
    )

    when (val sheet = state.sheet) {
        null -> Unit
        is ActionSheet.Actions -> MessageActionsSheet(
            header = sheet.header,
            rows = sheet.rows,
            onDismiss = state::dismiss,
            onPick = { picked ->
                state.dismiss()
                // Act once the sheet's window is gone (the frame after it leaves composition): Reply
                // raises the keyboard, which doesn't take while the sheet still holds the focus.
                scope.launch {
                    repeat(2) { withFrameNanos {} }
                    MessageActionsModel.run(
                        picked, sheet.subject, effects,
                        open = { url -> openLink(context, url) },
                        copyLink = { url -> copy("Link", url) },
                        share = { url -> share(context, url) },
                    )
                }
            },
        )
        is ActionSheet.Reactions -> {
            val message = sheet.message
            val flow = remember(model, key, message) {
                model.statePublisher.conflate().map { ReactionSheetInputs.of(it, message, key) }.distinctUntilChanged()
            }
            val inputs by flow.collectAsStateWithLifecycle(initialValue = ReactionSheetInputs.of(model.state, message, key))
            ReactionSheet(
                message = message,
                target = key.target,
                inputs = inputs,
                onChoose = { value ->
                    // Re-checked against the store at the tap, not trusted from the frame the buttons
                    // were drawn on: the network may have dropped since. ⚠ A WRITE.
                    when {
                        !ReactionSheetInputs.of(model.state, message, key).canReact -> ReactionChoice.Refused
                        model.toggleReaction(messageId = message.id, value = value) -> ReactionChoice.Sent
                        else -> ReactionChoice.NotConnected
                    }
                },
                onDismiss = state::dismiss,
            )
        }
        is ActionSheet.Ignore -> IgnoreDialog(
            subject = sheet.subject,
            defaultMask = sheet.defaultMask,
            onIgnore = { command ->
                state.dismiss()
                // The kit's `/ignore`: parsed, scoped, sent, and its receipt (or "Not sent — you're
                // not connected") printed in this buffer. ⚠ A WRITE.
                model.send(key, command)
            },
            onDismiss = state::dismiss,
        )
    }
}

/** Open Link: the browser, or whatever takes the address — and nothing at all, rather than a crash, when nothing does. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Log.w("Lurker", "no app opens $url", e)
    }
}

/** Share Link: the system share sheet. */
private fun share(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: ActivityNotFoundException) {
        Log.w("Lurker", "nothing shares $url", e)
    }
}
