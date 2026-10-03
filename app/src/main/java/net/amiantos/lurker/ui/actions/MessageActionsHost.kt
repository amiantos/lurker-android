// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    fun showReactions(message: Message): Boolean {
        if (sheet != null) return false
        sheet = ActionSheet.Reactions(message)
        return true
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
 * Composed inside the conversation's `LocalUriHandler` provider, so Open Link goes through the same
 * opener as a tap on a link (`SafeUriHandler`), with the same failure policy.
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
    val uriHandler = LocalUriHandler.current
    val window = LocalWindowInfo.current
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
            if (!model.setBookmark(messageId = id, saved = saved)) notice(MessageActionsModel.BOOKMARK_NOT_SENT)
        },
        showProfile = { nick -> key.networkId?.let { onShowProfile(it, nick) } },
        react = { message -> state.showReactions(message) },
        ignore = state::showIgnore,
    )

    // A picked action, waiting for the sheet to be gone. Dismiss first, act second: Reply raises the
    // keyboard, which doesn't take while the sheet's window still holds the focus — so this waits for
    // the real event, the sheet out of composition AND this window focused again, rather than a guess
    // at how many frames that takes. Bounded, so an app that lost focus meanwhile (a split-screen
    // neighbour tapped) still gets its copy; and dropped if the conversation goes first.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(pending) {
        val action = pending ?: return@LaunchedEffect
        withTimeoutOrNull(FOCUS_WAIT_MS) {
            snapshotFlow { state.sheet == null && window.isWindowFocused }.first { it }
        }
        pending = null
        action()
    }

    when (val sheet = state.sheet) {
        null -> Unit
        is ActionSheet.Actions -> MessageActionsSheet(
            header = sheet.header,
            rows = sheet.rows,
            onDismiss = state::dismiss,
            onPick = { picked ->
                pending = {
                    MessageActionsModel.run(
                        picked, sheet.subject, effects,
                        open = uriHandler::openUri,
                        copyLink = { url -> copy("Link", url) },
                        share = { url -> share(context, url) },
                    )
                }
                state.dismiss()
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

/** How long a picked action waits for the sheet's window to hand the focus back. */
private const val FOCUS_WAIT_MS = 1_000L

/** Share Link: the system share sheet. */
private fun share(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: ActivityNotFoundException) {
        Log.w("Lurker", "nothing shares $url", e)
    }
}
