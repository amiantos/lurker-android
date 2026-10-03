// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.bufferinfo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.amiantos.lurker.platform.MomentText
import net.amiantos.lurker.platform.findActivity
import net.amiantos.lurker.ui.channel.ChannelSettingsPage
import net.amiantos.lurker.ui.channel.ChannelSettingsState
import net.amiantos.lurker.ui.channel.ModeListPage
import net.amiantos.lurker.ui.channel.ModeListState
import net.amiantos.lurker.ui.members.MemberListPage
import net.amiantos.lurker.ui.members.MembersPageState
import net.amiantos.lurker.ui.networks.FullScreenDialog
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.profile.NickNotePage
import net.amiantos.lurker.ui.profile.NickNoteState
import net.amiantos.lurker.ui.profile.ProfileState
import net.amiantos.lurker.ui.profile.UserProfilePage
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.DccChat
import net.amiantos.lurkerkit.session.ChatViewModel
import java.util.UUID

/** Which page a buffer dialog opens on. */
internal enum class BufferSheetStart {
    /** The conversation's members button (channels). */
    Members,

    /** The conversation's info button (every buffer). */
    Info,

    /** Someone's profile on its own — U3's `/whois` and U6's message actions. */
    Profile,
}

/**
 * What a buffer dialog was opened for: which page, about which buffer (or, for [BufferSheetStart.Profile],
 * which nick — in [target]), and a token for its flow. Saved, so a rotation keeps the dialog up; the
 * flow itself is not (see [BufferFlow]).
 */
internal data class BufferSheetRequest(
    val start: BufferSheetStart,
    val token: String,
    val networkId: Int?,
    val target: String,
) {
    val key: BufferKey get() = BufferKey(networkId = networkId, target = target)

    companion object {
        private const val SEPARATOR = '\u0001'

        /**
         * One string, which a Bundle holds. The separator is the CTCP delimiter, which no nick or
         * channel name can contain.
         */
        val Saver: Saver<BufferSheetRequest?, String> = Saver(
            save = { request -> request?.let { listOf(it.start.name, it.token, it.networkId?.toString() ?: "", it.target).joinToString(SEPARATOR.toString()) } ?: "" },
            restore = { saved ->
                val parts = saved.split(SEPARATOR, limit = 4)
                val start = BufferSheetStart.entries.firstOrNull { it.name == parts.getOrNull(0) }
                if (parts.size < 4 || start == null) null else BufferSheetRequest(start, parts[1], parts[2].toIntOrNull(), parts[3])
            },
        )
    }
}

/**
 * The conversation's dialogs — the member list, the buffer info page, and a profile on its own — and
 * which one is up. The conversation's bar opens them; [BufferSheetsHost] draws them.
 *
 * Hosted by `MainScaffold`, not the conversation, for U4's reason: the conversation is rebuilt under a
 * new key whenever its buffer is renamed (a DM peer's nick change), and a dialog hosted inside it would
 * close mid-edit — a half-typed note or channel key thrown away for a rename the reader didn't do. iOS's
 * sheet stays up over a renamed chat too. What SHOULD close it is iOS's `land(on:)` — a join landing, a
 * DCC chat, Send Message, a notification — and the buffer it's about closing; the scaffold does both
 * ([dismiss], [dismissIfAbout]).
 *
 * One at a time — the dialog covers everything that could open another.
 */
@Stable
class BufferSheets internal constructor(private val open: MutableState<BufferSheetRequest?>) {
    /** The nick list — iOS's right-edge swipe and the info sheet's Members row. */
    fun showMembers(key: BufferKey) {
        open.value = BufferSheetRequest(BufferSheetStart.Members, UUID.randomUUID().toString(), key.networkId, key.target)
    }

    /** "About this one" — the info button, on every buffer. */
    fun showInfo(key: BufferKey) {
        open.value = BufferSheetRequest(BufferSheetStart.Info, UUID.randomUUID().toString(), key.networkId, key.target)
    }

    /** Someone's profile on its own dialog — iOS's `showProfile`, for `/whois` (U3) and a message's actions (U6). */
    fun showProfile(networkId: Int, nick: String) {
        open.value = BufferSheetRequest(BufferSheetStart.Profile, UUID.randomUUID().toString(), networkId, nick)
    }

    /** Close whatever is open, discarding its state — iOS's `dismissPresented`, run before `land(on:)`. */
    fun dismiss() {
        open.value = null
    }

    /** The buffer went away (closed here or on another device): a dialog about it has nothing left to describe. */
    fun dismissIfAbout(key: BufferKey) {
        val request = open.value ?: return
        if (request.start != BufferSheetStart.Profile && request.key.id == key.id) open.value = null
    }

    internal val current: BufferSheetRequest? get() = open.value
}

/** The dialogs' open/closed state, saved so a rotation keeps the dialog up. */
@Composable
fun rememberBufferSheets(): BufferSheets {
    val open = rememberSaveable(stateSaver = BufferSheetRequest.Saver) { mutableStateOf<BufferSheetRequest?>(null) }
    return remember(open) { BufferSheets(open) }
}

/** One page of a buffer dialog, holding its own state so the page under a pushed one keeps it. */
internal sealed interface BufferPage {
    /** Each page's own subscriptions, cancelled when it's popped. */
    val job: Job

    class Members(val state: MembersPageState, override val job: Job) : BufferPage

    class Info(val state: BufferInfoState, override val job: Job) : BufferPage

    class Profile(val state: ProfileState, override val job: Job) : BufferPage

    class NickNote(val state: NickNoteState, override val job: Job) : BufferPage

    class ChannelSettings(val state: ChannelSettingsState, override val job: Job) : BufferPage

    class ModeList(val state: ModeListState, override val job: Job) : BufferPage
}

/**
 * Everything one open buffer dialog holds: its page stack, each page's state, and the scope their
 * subscriptions and requests run in — iOS's sheet with its navigation controller.
 *
 * ⚠ Not `remember`ed and not saved: kept in [BufferFlowStore], which outlives a configuration change.
 * A rotation must not drop a half-typed note or the channel settings' drafts — and the drafts can hold
 * a channel key, which has no business in a Bundle written to disk. In memory for the life of the
 * dialog, then dropped. The page states subscribe to the store and to `channelEvents` here rather than
 * in composition, for the same reason: a live `MODE ±k` that lands during a rotation still counts.
 */
internal class BufferFlow(private val model: ChatViewModel, request: BufferSheetRequest, private val moments: MomentText) {
    // `Main`, not `Main.immediate`: the flow is built inside composition, and its pages start their
    // subscriptions and requests in their `init` — dispatched, they begin after the frame.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The page stack, root first. Never empty: back from the root dismisses the dialog instead. */
    val pages = mutableStateListOf<BufferPage>()

    init {
        val key = request.key
        when (request.start) {
            BufferSheetStart.Members -> pushMembers(key)
            BufferSheetStart.Info -> {
                // The row as the store has it, or synthesized: the page reads the live row anyway.
                val buffer = model.state.buffers[key.id] ?: Buffer(networkId = key.networkId, target = key.target, kind = BufferKind.of(key.networkId, key.target))
                push { scope, job -> BufferPage.Info(BufferInfoState(model, buffer, scope), job) }
            }
            BufferSheetStart.Profile -> request.networkId?.let { pushProfile(it, request.target) }
        }
    }

    /** A page's own scope: a child of the flow's, so popping the page stops its subscriptions. */
    private fun push(make: (CoroutineScope, Job) -> BufferPage) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        pages.add(make(CoroutineScope(scope.coroutineContext + job), job))
    }

    /** Pop the top page. False at the root, where back means dismissing the dialog. */
    fun back(): Boolean {
        if (pages.size <= 1) return false
        pages.removeAt(pages.lastIndex).job.cancel()
        return true
    }

    fun pushMembers(key: BufferKey) {
        push { _, job -> BufferPage.Members(MembersPageState(key), job) }
    }

    /**
     * ⚠ `nick` may be a `=bob` DCC chat's buffer name — the info page's Whois row passes its buffer's
     * target. A chat is with bob, so this is bob's profile: the title, the lookup, the note and Send
     * Message all mean him. Peeled here, the one door every profile comes through (lurker#270).
     */
    fun pushProfile(networkId: Int, nick: String) {
        val peer = DccChat.peer(nick)
        push { scope, job -> BufferPage.Profile(ProfileState(model, networkId, peer, scope), job) }
    }

    fun pushNickNote(networkId: Int, nick: String) {
        push { _, job -> BufferPage.NickNote(NickNoteState(model, networkId, nick), job) }
    }

    fun pushChannelSettings(key: BufferKey) {
        push { scope, job -> BufferPage.ChannelSettings(ChannelSettingsState(model, key, scope, moments::dateTime), job) }
    }

    fun pushModeList(key: BufferKey, letter: String, name: String) {
        push { scope, job -> BufferPage.ModeList(ModeListState(model, key, letter, name, scope), job) }
    }

    fun close() {
        scope.cancel()
    }
}

/**
 * The open buffer dialogs' state, kept across configuration changes — see [BufferFlow]. Keyed by the
 * token the opener saves, so a dialog restored after a rotation finds its flow, and one restored after
 * a process death (token saved, store empty) starts fresh at its first page.
 */
internal class BufferFlowStore : ViewModel() {
    private val flows = mutableMapOf<String, BufferFlow>()

    fun flow(token: String, create: () -> BufferFlow): BufferFlow = flows.getOrPut(token, create)

    fun discard(token: String) {
        flows.remove(token)?.close()
    }

    override fun onCleared() {
        flows.values.forEach { it.close() }
        flows.clear()
    }
}

/**
 * Draws whichever buffer dialog [sheets] has open.
 *
 * @param onOpenBuffer go to a conversation — a profile's Send Message. The dialog has closed by the
 *   time this runs, as iOS dismisses before navigating: a screen arriving under a dialog still on its
 *   way out is an animation fighting itself.
 */
@Composable
fun BufferSheetsHost(sheets: BufferSheets, model: ChatViewModel, onOpenBuffer: (BufferKey) -> Unit) {
    val request = sheets.current ?: return
    val context = LocalContext.current
    val moments = remember(context) { MomentText(context) }
    val store: BufferFlowStore = viewModel()
    val flow = store.flow(request.token) { BufferFlow(model, request, moments) }
    val activity = context.findActivity()
    // ⚠ Dropped when the dialog closes, and when this host leaves composition for good (sign-out swaps
    // the whole scaffold out) — but NOT across a configuration change, the one disposal the flow exists
    // to survive.
    DisposableEffect(request.token) {
        onDispose {
            if (sheets.current?.token != request.token || activity?.isChangingConfigurations != true) store.discard(request.token)
        }
    }
    BufferDialog(
        model = model,
        flow = flow,
        moments = moments,
        onDismiss = sheets::dismiss,
        onOpenBuffer = { key ->
            sheets.dismiss()
            onOpenBuffer(key)
        },
    )
}

/**
 * A buffer dialog: its page stack, drawn top page only. Back pops a pushed page (predictive back
 * included), then dismisses. Inner navigation is the flow's own, not the app's navigator — a detour
 * inside one dialog, as U4's networks dialog is.
 */
@Composable
private fun BufferDialog(
    model: ChatViewModel,
    flow: BufferFlow,
    moments: MomentText,
    onDismiss: () -> Unit,
    onOpenBuffer: (BufferKey) -> Unit,
) {
    // An empty stack — a profile request with no network, which nothing sends — has nothing to draw.
    if (flow.pages.isEmpty()) {
        DisposableEffect(Unit) {
            onDismiss()
            onDispose {}
        }
        return
    }
    FullScreenDialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = flow.pages.size > 1) { flow.back() }
        AnimatedContent(
            targetState = flow.pages.size to flow.pages.last(),
            transitionSpec = {
                // A push slides in from the end, a pop back from the start — the platform's forward and back.
                val forward = targetState.first >= initialState.first
                (slideInHorizontally { width -> if (forward) width / 4 else -width / 4 } + fadeIn())
                    .togetherWith(slideOutHorizontally { width -> if (forward) -width / 4 else width / 4 } + fadeOut())
            },
            label = "buffer page",
        ) { (depth, page) ->
            val exit = if (depth == 1) PageExit.Close else PageExit.Back
            val onExit: () -> Unit = { if (!flow.back()) onDismiss() }
            when (page) {
                is BufferPage.Members -> MemberListPage(
                    model = model,
                    state = page.state,
                    exit = exit,
                    onExit = onExit,
                    // Pushed into this dialog rather than presented, so the profile arrives inside the
                    // list you were scanning and Back returns to it.
                    onOpenProfile = page.state.key.networkId?.let { networkId -> { nick -> flow.pushProfile(networkId, nick) } },
                )
                is BufferPage.Info -> {
                    val key = page.state.buffer.key
                    BufferInfoPage(
                        state = page.state,
                        exit = exit,
                        onExit = onExit,
                        actions = BufferInfoActions(
                            onMembers = { flow.pushMembers(key) },
                            onWhois = { key.networkId?.let { flow.pushProfile(it, key.target) } },
                            onChannelSettings = { flow.pushChannelSettings(key) },
                            onModeList = { letter, name -> flow.pushModeList(key, letter, name) },
                            onNetworkVerb = page.state::perform,
                            onDccVerb = page.state::perform,
                        ),
                    )
                }
                is BufferPage.Profile -> {
                    val state = page.state
                    UserProfilePage(
                        state = state,
                        exit = exit,
                        onExit = onExit,
                        onEditNote = { flow.pushNickNote(state.networkId, state.nick) },
                        onSendMessage = {
                            // Mint or reopen the DM row first — the server refuses to activate a buffer
                            // that doesn't exist, and the same socket delivers the row before we ask to
                            // show it. ⚠ A WRITE (the DM appears on every device).
                            val dm = BufferKey(networkId = state.networkId, target = state.nick)
                            model.openBuffer(dm)
                            onOpenBuffer(dm)
                        },
                        onJoinChannel = { channel -> model.requestJoin(networkId = state.networkId, channel = channel, opens = true) },
                    )
                }
                is BufferPage.NickNote -> NickNotePage(state = page.state, onBack = { flow.back() }, onDone = { flow.back() })
                is BufferPage.ChannelSettings -> ChannelSettingsPage(state = page.state, onBack = { flow.back() })
                is BufferPage.ModeList -> ModeListPage(state = page.state, dateTime = moments::dateTime, onBack = { flow.back() })
            }
        }
    }
}
