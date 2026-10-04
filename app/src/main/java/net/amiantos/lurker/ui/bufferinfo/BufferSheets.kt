// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.bufferinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import net.amiantos.lurker.platform.MomentText
import net.amiantos.lurker.ui.channel.ChannelSettingsPage
import net.amiantos.lurker.ui.channel.ChannelSettingsState
import net.amiantos.lurker.ui.channel.ModeListPage
import net.amiantos.lurker.ui.channel.ModeListState
import net.amiantos.lurker.ui.members.MemberListPage
import net.amiantos.lurker.ui.members.MembersPageState
import net.amiantos.lurker.ui.networks.PageExit
import net.amiantos.lurker.ui.networks.PagedDialog
import net.amiantos.lurker.ui.networks.PagedFlow
import net.amiantos.lurker.ui.networks.rememberPagedFlow
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
 * ([dismiss], [dismissIfAbout]). A rename it follows instead ([follow]), so it keeps describing the
 * buffer it was opened on.
 *
 * One at a time — the dialog covers everything that could open another.
 */
@Stable
class BufferSheets internal constructor(
    private val open: MutableState<BufferSheetRequest?>,
    private val renames: MutableState<List<BufferRename>>,
) {
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
        renames.value = emptyList()
    }

    /**
     * The store rekeyed a buffer — a DM peer's nick change, a channel rename: the same events the
     * scaffold follows (`AppEvent.BufferRenamed`, the conversation's `onMoved`). The request moves, so
     * the dialog is still found as being about that buffer ([dismissIfAbout]) and a process death
     * restores it on the new name; and the open pages are told ([BufferFlow.follow]), since any of them
     * may be about it — a profile pushed from a member list, as much as the info page it started on.
     * Following the same rename twice (both events fire for the open conversation) moves nothing the
     * second time.
     */
    fun follow(from: BufferKey, to: BufferKey) {
        val request = open.value ?: return
        val rename = BufferRename(from, to)
        DialogRenames.request(request, rename)?.let { open.value = it }
        renames.value = renames.value + rename
    }

    /** Renames the open flow hasn't been told about yet, oldest first; taking them clears them. */
    internal val pendingRenames: List<BufferRename> get() = renames.value

    internal fun takeRenames(): List<BufferRename> = renames.value.also { renames.value = emptyList() }

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
    // Not saved: a rename the flow hasn't heard about yet is moot after a process death, whose fresh
    // flow is built from the (already followed) request.
    val renames = remember { mutableStateOf<List<BufferRename>>(emptyList()) }
    return remember(open) { BufferSheets(open, renames) }
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
 * subscriptions and requests run in — a [PagedFlow], kept in its store across a configuration change.
 *
 * A rotation must not drop a half-typed note or the channel settings' drafts — and the drafts can hold
 * a channel key, which has no business in a Bundle written to disk. The page states subscribe to the
 * store and to `channelEvents` in their own scopes rather than in composition, for the same reason: a
 * live `MODE ±k` that lands during a rotation still counts.
 */
internal class BufferFlow(private val model: ChatViewModel, request: BufferSheetRequest, private val moments: MomentText) :
    PagedFlow<BufferPage>() {
    init {
        val key = request.key
        when (request.start) {
            BufferSheetStart.Members -> pushMembers(key)
            BufferSheetStart.Info -> pages.add(info(key))
            BufferSheetStart.Profile -> request.networkId?.let { pushProfile(it, request.target) }
        }
    }

    /** A page's own scope: a child of the flow's, so popping the page stops its subscriptions. */
    private fun page(make: (CoroutineScope, Job) -> BufferPage): BufferPage {
        val job = SupervisorJob(scope.coroutineContext[Job])
        return make(CoroutineScope(scope.coroutineContext + job), job)
    }

    private fun push(make: (CoroutineScope, Job) -> BufferPage) {
        pages.add(page(make))
    }

    override fun popped(page: BufferPage) {
        page.job.cancel()
    }

    /** The row as the store has it, or synthesized: the page reads the live row anyway. */
    private fun info(key: BufferKey): BufferPage {
        val buffer = model.state.buffers[key.id] ?: Buffer(networkId = key.networkId, target = key.target, kind = BufferKind.of(key.networkId, key.target))
        return page { scope, job -> BufferPage.Info(BufferInfoState(model, buffer, scope), job) }
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
        pages.add(profile(networkId, nick))
    }

    private fun profile(networkId: Int, nick: String): BufferPage {
        val peer = DccChat.peer(nick)
        return page { scope, job -> BufferPage.Profile(ProfileState(model, networkId, peer, scope), job) }
    }

    fun pushNickNote(networkId: Int, nick: String) {
        push { _, job -> BufferPage.NickNote(NickNoteState(model, networkId, nick), job) }
    }

    fun pushChannelSettings(key: BufferKey) {
        pages.add(channelSettings(key))
    }

    private fun channelSettings(key: BufferKey): BufferPage =
        page { scope, job -> BufferPage.ChannelSettings(ChannelSettingsState(model, key, scope, moments::dateTime), job) }

    fun pushModeList(key: BufferKey, letter: String, name: String) {
        pages.add(modeList(key, letter, name))
    }

    private fun modeList(key: BufferKey, letter: String, name: String): BufferPage =
        page { scope, job -> BufferPage.ModeList(ModeListState(model, key, letter, name, scope), job) }

    /**
     * A buffer was renamed under the dialog: every page about it is rebuilt about the new name, in
     * place, and its old subscriptions stop. What the reader typed comes across — the member filter, a
     * note's draft. The channel settings and a list page start over: their state is the channel's, read
     * afresh under the new name (the list refetches, as it does after a reconnect), and a channel rename
     * is rare enough that redoing an unsaved toggle is the honest cost (see the project's note on
     * renames).
     */
    fun follow(rename: BufferRename) {
        for (index in pages.indices) {
            val old = pages[index]
            val next: BufferPage? = when (old) {
                is BufferPage.Members -> DialogRenames.key(old.state.key, rename)?.let { key ->
                    page { _, job -> BufferPage.Members(MembersPageState(key).also { it.query = old.state.query }, job) }
                }
                is BufferPage.Info -> DialogRenames.key(old.state.buffer.key, rename)?.let(::info)
                is BufferPage.Profile -> DialogRenames.nick(old.state.networkId, old.state.nick, rename)?.let { profile(old.state.networkId, it) }
                is BufferPage.NickNote -> DialogRenames.nick(old.state.networkId, old.state.nick, rename)?.let { nick ->
                    page { _, job -> BufferPage.NickNote(NickNoteState(model, old.state.networkId, nick).also { it.draft = old.state.draft }, job) }
                }
                is BufferPage.ChannelSettings -> DialogRenames.key(old.state.key, rename)?.let(::channelSettings)
                is BufferPage.ModeList -> DialogRenames.key(old.state.key, rename)?.let { modeList(it, old.state.letter, old.state.name) }
            }
            if (next != null) {
                old.job.cancel()
                pages[index] = next
            }
        }
    }
}

/**
 * Draws whichever buffer dialog [sheets] has open.
 *
 * A profile's Send Message goes nowhere from here: the kit lands on the DM once its row is in
 * (`ChatViewModel.openAndShow`, lurker-ios#201), and the landing (`AppEvent.OpenBuffer`) closes this
 * dialog before it navigates, as a join from a whois channel row does.
 *
 * @param onSearch open search seeded with a buffer's scope — the info page's "Search This
 *   Conversation" (U7). Closed first too, as iOS dismisses the sheet before presenting search.
 */
@Composable
fun BufferSheetsHost(sheets: BufferSheets, model: ChatViewModel, onSearch: (String) -> Unit = {}) {
    val request = sheets.current ?: return
    val context = LocalContext.current
    val moments = remember(context) { MomentText(context) }
    val flow = rememberPagedFlow(request.token, isOpen = { sheets.current?.token == request.token }) { BufferFlow(model, request, moments) }
    val renames = sheets.pendingRenames
    LaunchedEffect(flow, renames) {
        if (renames.isNotEmpty()) sheets.takeRenames().forEach(flow::follow)
    }
    BufferDialog(
        model = model,
        flow = flow,
        moments = moments,
        onDismiss = sheets::dismiss,
        onSearch = { scope ->
            sheets.dismiss()
            onSearch(scope)
        },
    )
}

/** A buffer dialog: its page stack, as a [PagedDialog] — the networks dialogs' mechanics. */
@Composable
private fun BufferDialog(
    model: ChatViewModel,
    flow: BufferFlow,
    moments: MomentText,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
) {
    // An empty stack — a profile request with no network, which nothing sends — has nothing to draw.
    if (flow.pages.isEmpty()) {
        DisposableEffect(Unit) {
            onDismiss()
            onDispose {}
        }
        return
    }
    PagedDialog(flow = flow, onDismiss = onDismiss, label = "buffer page") { depth, page ->
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
                        onSearch = onSearch,
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
                        // Mint or reopen the DM row, and go there once it's in the store — the way a
                        // channel row below goes once we're in it. ⚠ Not at once: `open-buffer` only
                        // queues a write, and a conversation that beat the row there found a settled
                        // roster without it and backed straight out to the list (lurker-ios#201).
                        // Going takes this dialog down first (`AppEvent.OpenBuffer`). ⚠ A WRITE when
                        // the row isn't held (the DM appears on every device).
                        model.openAndShow(BufferKey(networkId = state.networkId, target = state.nick))
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
