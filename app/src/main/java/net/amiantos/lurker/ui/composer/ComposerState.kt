// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.amiantos.lurker.platform.AppEvent
import net.amiantos.lurker.platform.LocalAppEvents
import net.amiantos.lurker.platform.findActivity
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.Drafts
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.NickCompletion
import net.amiantos.lurkerkit.model.OutgoingTyping
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.SpeakerMap
import net.amiantos.lurkerkit.model.StatusNotification
import net.amiantos.lurkerkit.model.StatusToast
import net.amiantos.lurkerkit.model.TabCompletion
import net.amiantos.lurkerkit.session.ChatViewModel
import java.time.Instant

/**
 * One buffer's composer: the field, the pending reply, the suggestions and the chrome, and every
 * conversation the composer has with the kit — send, the refused-line restore, the synced draft,
 * the typing signal, `/back`. lurker-ios's `ComposerBar` state plus the composer half of
 * `ChatViewController` (MARKs "Typing (#61)", "Replies (iOS #184)", "Draft (iOS #188)", `send`,
 * `restoreRefusedSend`, `viewWillDisappear`'s flush). The decisions are [ComposerModel]'s; this
 * keeps the bookkeeping that tells one kind of change from another.
 *
 * **Three kinds of change, three hooks**, as iOS keeps them apart:
 *  - *the text changed* → the typing signal (`onDraftChange`): for the user's edits and the
 *    composer's own (a completion, a send's clear) — they change what's being composed too — but
 *    ⚠⚠ never a [restore]: telling the CHANNEL you resumed typing because the server handed your
 *    own line back would be a lie;
 *  - *the user changed the field* → the synced draft (`onEdit`): its text, or whether an IME is
 *    composing in it. Again never a restore — the composer put that text there and knows what it
 *    is — and not deduped against what the channel was told, which a restore leaves behind;
 *  - *the caret moved* → the completion under it.
 *
 * The field is observed (`snapshotFlow`), which arrives a frame late and can't say who changed it,
 * so every programmatic edit runs [fieldChanged] itself, synchronously, and leaves the trackers
 * agreeing with the field — the late observation then finds nothing new. A restore sets the
 * trackers WITHOUT running the hooks, which is the whole of what makes it silent.
 *
 * IME: a `TextFieldState` handles composition natively (no `v-model` freeze, the web's lurker bug),
 * and the keys the composer does act on — a hardware Enter sending, Tab completing
 * (lurker-android#63) — are left alone while the IME is composing (`ComposerKeys`), so nothing here
 * can commit or cut a word the IME still holds. "Enter to send" (lurker-android#64) is the IME's own
 * action key, which it fires only once it has committed.
 */
@Stable
internal class ComposerState(
    private val model: ChatViewModel,
    val key: BufferKey,
    val kind: BufferKind,
    private val scope: CoroutineScope,
    /**
     * The field — saved by the screen (`rememberComposerState`), so a rotation or a process death
     * hands back what was typed, caret included. Opened on the buffer's draft ([openField]).
     */
    val field: TextFieldState = openField(model, key),
    /** Whether the colour editor is up — the screen's, saved, so a rotation doesn't close it. */
    editorOpenState: MutableState<Boolean> = mutableStateOf(false),
    /** The [pen] — the screen's, saved with the field it was picked in ([penSaver]). */
    penState: MutableState<ComposerColors.Pen?> = mutableStateOf(null),
) {
    // MARK: - What the screen hands in (set every composition — see `rememberComposerState`)

    /** About to send: put the list where the send should leave it (`ComposerModel.sendScroll`). */
    internal var onWillSend: () -> Unit = {}

    /**
     * `/msg` / `/query` to a channel asks to switch to it. To a nick they ask nothing: the kit lands on
     * the DM once its row is in (`ChatViewModel.openAndShow`, lurker-ios#201).
     */
    internal var onOpenBuffer: (BufferKey) -> Unit = {}

    /** `/whois` — open the profile rather than leaving the numerics as the only answer. */
    internal var onShowProfile: (networkId: Int, nick: String) -> Unit = { _, _ -> }

    internal var keyboard: SoftwareKeyboardController? = null

    /** A passing notice (`AppEvent.Notice`) — the status row's, when it fits, else the app's snackbar. */
    internal var notice: (String) -> Unit = {}

    // MARK: - What the bar draws

    private val initialDraft: ComposerDraft? = model.draft(key)

    /** The stored draft as the field shows it: its text, and its colour (lurker#1117). */
    private val initialRead = ComposerColors.read(initialDraft?.body ?: "")

    init {
        // Where drafts sync, the stored draft is the truth and the saved field only lends its caret:
        // leaving flushes what was typed, so a rebuilt composer opens on the draft as a fresh one
        // would — another device's edit, or its clear or send (no draft at all), included. Saved
        // text typed over either would come back and sync the deleted words back up. Where they
        // agree the caret stands. After a process death nothing is held yet and the field opens
        // empty until the server's draft repaints it, as it always did. Buffers whose drafts don't
        // sync (the Lurker console, a server log) have only what was saved, and keep it.
        if (Drafts.syncs(key)) {
            val stored = initialRead.first
            if (stored != field.text.toString()) field.setTextAndPlaceCursorAtEnd(stored)
        }
    }

    // MARK: - Colour (lurker#1117)

    /**
     * The field's colour and the text it was last fitted to — one value, so the two are never read
     * apart. Fitted in [fieldChanged], from [typed] when the user made the edit; until then [colorsFor]
     * answers for the text on screen, which is what lets the field paint a keystroke's colour in the
     * frame it's typed.
     */
    private class Painted(val text: String, val colors: ComposerColors)

    /** A user edit's fitting, made from the field's own report of it ([colorInput]), and the pen after it. */
    private class Typed(val painted: Painted, val pen: ComposerColors.Pen?)

    private var typed: Typed? by mutableStateOf(null)

    /**
     * Fits the colour to each edit the user makes, from Compose's report of exactly what changed —
     * the one source that can tell `a` typed before `ab` from `a` typed after the first `a`, which a
     * diff of the two texts can't. Changes no text. Both fields that show this one (the bar's and the
     * colour editor's) carry it.
     *
     * ⚠ `TextFieldBuffer.changes` is still marked experimental (foundation 1.10). It's the only report
     * of where an edit landed, and the alternative — guessing from the texts — is the bug it fixes.
     */
    @OptIn(ExperimentalFoundationApi::class)
    val colorInput = InputTransformation {
        val old = originalText.toString()
        val new = asCharSequence().toString()
        if (old == new) return@InputTransformation
        val changes = (0 until this.changes.changeCount).map { index ->
            val original = this.changes.getOriginalRange(index)
            val range = this.changes.getRange(index)
            ComposerColors.Change(original.min, original.max, range.min, range.max)
        }
        // Against what's on screen now: another keystroke may already be waiting to be committed.
        // Its pen too — spent already, maybe, which a fallback to the stored one would bring back.
        val previous = typed?.takeIf { it.painted.text == old }
        val pen = if (previous != null) previous.pen else this@ComposerState.pen
        typed = Typed(Painted(new, colorsFor(old).edited(changes, new.length, pen)), pen?.afterTyping(changes))
    }

    private var painted: Painted by mutableStateOf(
        Painted(
            field.text.toString(),
            if (field.text.toString() == initialRead.first) initialRead.second else ComposerColors.plain(field.text.length),
        ),
    )

    /**
     * A colour picked at a bare caret and not yet typed with — what the next keystroke writes in, as
     * UIKit's typing attributes are on iOS. Spent by the edit it colours, and dropped by a caret move,
     * so it lasts exactly as long as the caret it was picked at.
     */
    var pen: ComposerColors.Pen? by penState
        private set

    /** Where the caret was at the last [fieldChanged] — a move without an edit drops the [pen]. */
    private var lastSelection: TextRange = field.selection

    /** Set while the colour editor covers the composer: nothing may take the keyboard back to the bar. */
    var editorOpen: Boolean by editorOpenState

    /** The colour on [text] — the field's, fitted to it when the field has moved on since it was last. */
    fun colorsFor(text: String): ComposerColors {
        val now = painted
        if (now.text == text) return now.colors
        typed?.painted?.takeIf { it.text == text }?.let { return it.colors }
        return now.colors.followed(now.text, text)
    }

    /**
     * What the field holds as a line, its colour written in (`ColorMarkup.encode`) — the draft that
     * syncs and the line a send sends. Plain text is itself.
     */
    val line: String get() {
        val text = this.field.text.toString() // `this.`: bare `field` is the backing field
        val now = painted
        if (now.text != text) return ComposerColors.line(text, colorsFor(text))
        // Written once per fitting rather than per read — it's read on every caret move and again by
        // the draft save, and writing it walks the whole line.
        lineCache?.let { (fitted, written) -> if (fitted === now) return written }
        return ComposerColors.line(text, now.colors).also { lineCache = now to it }
    }

    private var lineCache: Pair<Painted, String>? = null

    /**
     * The pair the selection — or the caret, or the pen — is in, for the palette to ring.
     */
    val currentColors: Int get() {
        val text = this.field.text.toString()
        val colors = colorsFor(text)
        val selection = this.field.selection
        return when {
            !selection.collapsed -> colors.at(selection.min)
            else -> pen?.takeIf { it.at == selection.min }?.pair ?: colors.at(selection.min - 1)
        }
    }

    /**
     * The palette's pick: [layer] of the selection set to [slot] (null takes it off), or — with only a
     * caret — of what's typed next.
     */
    fun pickColor(slot: Int?, layer: ComposerColors.Layer) {
        val selection = field.selection
        if (selection.collapsed) {
            pen = ComposerColors.Pen(ComposerColors.with(currentColors, slot, layer), at = selection.min)
            return
        }
        val text = field.text.toString()
        painted = Painted(text, colorsFor(text).painted(slot, layer, selection.min, selection.max))
        // Recolouring is an edit the synced draft has to hear; nothing else changed.
        reportEdit(snapshot())
    }

    val focusRequester = FocusRequester()

    /**
     * The reply this composer is writing, shown above the field. The next plain line or `/me` goes
     * out as it. Part of the draft (lurker-ios#188), so it syncs and comes back with the text.
     */
    var reply: PendingReply? by mutableStateOf(initialDraft?.reply)
        private set

    // MARK: - The status row (lurker#1098, lurker#1099)

    /** Who's typing here (lurker-ios#61), as the row shows them — the screen's own stream feeds it. */
    var typists: List<String> by mutableStateOf(model.state.typists(key))
        internal set

    /** Where you are and how the connection is doing — the row's resting words. */
    var location: Location? by mutableStateOf(ComposerModel.location(model.state, key, kind))
        internal set

    /** Highlights waiting in other buffers (lurker#1099); 0 hides the count. */
    var otherHighlights: Int by mutableIntStateOf(ComposerModel.highlightCount(model.state, key))
        internal set

    /** The toast showing in the row now, mirrored from [toasts] so the bar can observe it. */
    var activeToast: StatusToast? by mutableStateOf(null)
        private set

    /**
     * Whether the row can be seen — on screen with nothing over it. Asked before each toast: one
     * shown under a dialog, or after the screen has gone, would expire unseen while its announcement
     * spoke over somewhere else. The screen's answer.
     */
    internal var canShowToasts: () -> Boolean = { true }

    /**
     * Whether a notice fits the one-line row without being cut off — the bar's answer, measured
     * against the row's width. A notice that would truncate floats instead, where it can wrap.
     */
    internal var noticeFits: (String) -> Boolean = { true }

    /**
     * A notice that no longer fits the row by the time its turn comes (a narrower column, a larger
     * text size), handed back to be shown somewhere it can wrap.
     */
    internal var onNoticeOverflow: (String) -> Unit = {}

    /** A toast went up (`isNew`), or the one up was updated in place — the bar's haptic and announcement. */
    internal var onToastShown: (toast: StatusToast, isNew: Boolean) -> Unit = { _, _ -> }

    /** A notification went up, or was updated in place — its sound (`ToastCenter.shown`). The screen's. */
    internal var onNotificationShown: (StatusNotification) -> Unit = {}

    /**
     * The toast showing now, and the ones waiting their turn. Never over the completion chips —
     * they're what you're tapping — so the queue waits for them to close.
     */
    private val toasts = StatusToastPresenter(scope).apply {
        isReady = { suggestions.isEmpty() }
        isVisible = { canShowToasts() }
        shouldPresent = { toast ->
            if (toast is StatusToast.Notice && !noticeFits(toast.message)) {
                onNoticeOverflow(toast.message)
                false
            } else {
                true
            }
        }
        onChange = { activeToast = active }
        onShow = { toast, isNew ->
            onToastShown(toast, isNew)
            if (toast is StatusToast.Notification) onNotificationShown(toast.notification)
        }
    }

    /** Show a toast in the row for a few seconds, in turn (`StatusToastQueue`). */
    fun showToast(toast: StatusToast) = toasts.show(toast)

    /**
     * Say something that didn't work, in the row — this screen's one place for passing news. False
     * when it doesn't fit the one-line row; the caller floats it where it can wrap.
     */
    fun showNotice(message: String): Boolean {
        if (!noticeFits(message)) return false
        toasts.show(StatusToast.Notice(message))
        return true
    }

    /**
     * The toast showing was tapped: down it comes, and a notification is returned to go to. The
     * caller acts on it, then calls [nextToast] — after, so a tap that leaves the screen doesn't
     * start the next toast on the way out.
     */
    fun tapToast(): StatusNotification? = (toasts.takeActive() as? StatusToast.Notification)?.notification

    fun nextToast() = toasts.presentNext()

    /** The row was covered or uncovered, or came on screen: toasts that waited for it may go up now. */
    internal fun toastSurfaceChanged() = toasts.presentNext()

    private var suggestionsState: List<Suggestion> by mutableStateOf(emptyList())

    /**
     * The chips in the status row, best first. The chips own the whole row while they're up: a toast
     * showing goes back to the front of the queue, to be shown in full once they close, rather than
     * running out its time underneath them — and toasts that arrived mid-completion wait for them.
     */
    var suggestions: List<Suggestion>
        get() = suggestionsState
        private set(value) {
            val wereUp = suggestionsState.isNotEmpty()
            suggestionsState = value
            val up = value.isNotEmpty()
            if (up == wereUp) return
            if (up) toasts.requeueActive() else toasts.presentNext()
        }

    /** Who you speak as, and whether you're away — from state, not typing. */
    var chrome: ComposerChrome by mutableStateOf(ComposerChrome.of(ComposerChrome.Inputs.of(model.state, key, kind)))
        internal set

    /**
     * Whether the field has focus — what the screen's keyboard-arrival scroll asks, since the keyboard
     * coming up for some other field (a dialog's, side by side) is no reason to move the conversation.
     */
    var isFocused: Boolean by mutableStateOf(false)
        internal set

    /** Whether a held refused line could come back now — observed, so freeing the field retries it. */
    internal val canRestoreRefused: Boolean get() = ComposerModel.canRestoreRefused(this.field.text.toString(), reply)

    /** Whether an IME is mid-composition — marked text the keyboard hasn't committed yet. */
    val isComposing: Boolean get() = this.field.composition != null // `this.`: bare `field` is the backing field

    // MARK: - Bookkeeping

    /** The field as the trackers last saw it — what tells a text change from a caret move. */
    private var lastSeenText: String = field.text.toString()

    /** The field as the draft hook last saw it — as a line, colour and all — so a caret move isn't an edit. */
    private var lastEdit: Pair<String, Boolean> = line to false

    /**
     * The completion last computed — in a holder, so "not computed yet" differs from "none". Seeded
     * from the draft the field opened on, as a restore seeds it: a half-typed `/join #li` coming back
     * when the buffer opens is not someone in the middle of typing it, and mustn't pop the pills.
     */
    private var lastCompletion: Array<Completion?>? = arrayOf(ComposerModel.completion(lastSeenText, lastSeenText.length, lastSeenText.length))

    /** The completion the pills were built for — what a pick inserts into. */
    private var activeCompletion: Completion? = null

    /** Set while the composer puts a draft in itself, so the reply that comes with it isn't saved back. */
    private var isShowingDraft = false

    /** The stored draft as this composer last saw it, so only a change to it repaints the field. */
    private var lastSeenDraft: ComposerDraft? = model.state.drafts[key.id]

    private val typing = ComposerTyping { signal -> model.setTyping(key, signal) }

    /** Fires once the draft has sat untouched long enough to downgrade `active` → `paused`. */
    private var typingIdle: Job? = null

    /** The resolved `input.completion.nick_suffix`, read off the store at the moment it's used. */
    private val punctuation: String get() = NickCompletion.addressPunctuation(model.state.settings)

    // MARK: - The field changed

    /** A snapshot of the field, as the trackers compare it. */
    internal data class Snapshot(val text: String, val selection: TextRange, val composing: Boolean)

    internal fun snapshot() = Snapshot(field.text.toString(), field.selection, field.composition != null)

    /** The field changed — by the user (observed) or by the composer itself (called directly). */
    internal fun fieldChanged(now: Snapshot) {
        // The colour first, so everything after reads it fitted to this text.
        if (now.text != painted.text) {
            val user = typed?.takeIf { it.painted.text == now.text }
            if (user != null) {
                painted = user.painted
                pen = user.pen
            } else {
                // The composer's own edit (or an undo, which Compose reports to nobody either).
                val old = painted.text
                painted = Painted(now.text, colorsFor(now.text))
                pen = pen?.after(old, now.text)
            }
            typed = null
        } else if (now.selection != lastSelection) {
            pen = null
        }
        lastSelection = now.selection
        // Any edit or caret move Tab didn't make ends its session, as any other key does on the web —
        // `continues` alone would revive it after a letter typed and deleted.
        tabCompletion?.let { session ->
            if (now.selection.min != now.selection.max || !session.continues(text = now.text, caret = now.selection.min)) {
                tabCompletion = null
            }
        }
        val textChanged = now.text != lastSeenText
        lastSeenText = now.text
        // What the channel was told is `ComposerTyping`'s to dedupe against — and to forget when typing
        // ends. A restore is never a text change here: it sets `lastSeenText` itself.
        if (textChanged) draftChanged(now.text)
        emitCompletion(now)
        reportEdit(now)
    }

    /**
     * Recompute the completion under the caret, and the pills, only when it changed.
     *
     * Not gated on [Snapshot.composing], unlike iOS's marked text: Gboard and most Latin keyboards
     * hold the word being typed as the composing region, so the gate would switch bare-word nicks
     * (#57) off for nearly everyone. The text is in the field either way, and a pick ends the
     * composition the way any edit does.
     */
    private fun emitCompletion(now: Snapshot) {
        // A live Tab completion owns the field: no pills while it cycles (the web closes its pickers
        // on Tab), or a mid-sentence one would float them over the nick it just finished.
        val computed = if (tabCompletion != null) null else ComposerModel.completion(now.text, now.selection.min, now.selection.max)
        val last = lastCompletion
        if (last != null && last[0] == computed) return
        lastCompletion = arrayOf(computed)
        activeCompletion = computed
        suggestions = suggestionsFor(computed)
    }

    /**
     * The candidates' sources moved — someone joined or left, a nick changed, a rule was added, a
     * channel opened, a line arrived. Rebuild the pills showing NOW, for the same token; nothing at all
     * while none are, so a restored draft's pills stay suppressed until the caret gives a reason.
     */
    internal fun refreshSuggestions() {
        val completion = activeCompletion ?: return
        suggestions = suggestionsFor(completion)
    }

    private fun suggestionsFor(completion: Completion?): List<Suggestion> =
        ComposerModel.suggestions(
            completion,
            channels = { query -> ComposerModel.channelCandidates(model.state.buffers.values, key.networkId, query) },
            nicks = { query -> nickCandidates(query) },
        )

    /**
     * Who a nick query offers here: the status row's ten, or every match for Tab to cycle through, as
     * the web's does. One call, so the two can't disagree about who's here.
     */
    private fun nickCandidates(query: String, limit: Int = ComposerModel.NICK_LIMIT): List<String> {
        val state = model.state
        return NickCompletion.candidates(
            speakers = state.speakers[key.id] ?: SpeakerMap(),
            members = state.visibleMembers(key),
            selfNick = key.networkId?.let { state.networks[it]?.nick },
            query = query,
            isChannel = kind == BufferKind.Channel,
            limit = limit,
            ignores = state.ignores,
            networkId = key.networkId,
            channel = key.target,
        )
    }

    /** Tell the draft the field changed, if it did. */
    private fun reportEdit(now: Snapshot) {
        // The line, colour and all: recolouring is an edit the synced draft has to hear.
        val edit = line to now.composing
        if (edit == lastEdit) return
        lastEdit = edit
        saveDraft()
    }

    /** Swap the field's text for [edit] and run the hooks — every programmatic edit but a restore. */
    private fun apply(edit: FieldEdit) {
        field.edit {
            replace(0, length, edit.text)
            selection = TextRange(edit.caret)
        }
        fieldChanged(snapshot())
    }

    /**
     * Put a refused line back, as typed (lurker-ios#128) — or a draft, which may be empty: another
     * device emptied it (lurker-ios#188). Caret at the end, so carrying on writing needs no tap.
     *
     * ⚠⚠ Silently: no typing signal (the channel would hear you resumed typing because the server
     * handed your own message back), no draft write (the caller decides), and no pills (they'd pop
     * over a restored `/msg bob hi` nobody is in the middle of writing). It does END a typing claim,
     * though: what the channel was told about is gone, and a `paused` arriving after it would describe
     * text nobody is writing any more — so `done` goes out, as for any other line that stops being
     * composed.
     *
     * ⚠⚠ And it does NOT raise the keyboard. Nothing the user did asked for this — shoving the
     * keyboard up over a conversation they may have gone back to reading is the app talking over
     * them. The text appearing in the field is the whole message.
     */
    private fun restore(line: String) {
        endTyping()
        // Colour comes back as colour; a line with formatting the field can't show stays raw. A refused
        // coloured line is the same string the field held (`ColorMarkup`'s one format), so it comes
        // back exactly as it was typed.
        val (text, colors) = ComposerColors.read(line)
        field.edit {
            replace(0, length, text)
            selection = TextRange(text.length)
        }
        painted = Painted(text, colors)
        pen = null
        lastSelection = field.selection
        lastSeenText = text
        lastEdit = this.line to false
        lastCompletion = arrayOf(ComposerModel.completion(text, text.length, text.length))
        activeCompletion = null
        suggestions = emptyList()
    }

    // MARK: - Typing (lurker-ios#61)

    /** The draft changed — tell the network, and re-arm the idle downgrade. */
    private fun draftChanged(draft: String) {
        val arm = typing.draftChanged(draft, Instant.now()) ?: return
        typingIdle?.cancel()
        typingIdle = null
        if (!arm) return
        typingIdle = scope.launch {
            delay(OutgoingTyping.idle.toMillis())
            typingIdle = null
            // The draft captured here is the latest one: any change re-arms this timer.
            typing.idled(draft, Instant.now())
        }
    }

    /** Downgrade an `active` claim to `paused` now — see [leave]. Silent when we weren't. */
    private fun pauseTyping() {
        typingIdle?.cancel()
        typingIdle = null
        typing.idled(field.text.toString(), Instant.now())
    }

    /** Stop claiming to type — on send, and on leaving. Silent when we weren't. */
    private fun endTyping() {
        typingIdle?.cancel()
        typingIdle = null
        typing.ended()
    }

    // MARK: - Draft (lurker-ios#188)

    /** Set while one action changes the field and the reply together — see [batch]. */
    private var batching = false
    private var batchDirty = false

    /** The composer changed — save it as this buffer's draft. */
    private fun saveDraft(composing: Boolean = isComposing) {
        if (isShowingDraft) return
        if (batching) {
            batchDirty = true
            return
        }
        model.editDraft(key, ComposerDraft(body = line, reply = reply), composing = composing)
    }

    /**
     * Run an action that changes the reply and the text together — a send, a restore, a Reply, a
     * cancel — and save the draft ONCE, at the end, as it then stands. Saved piecemeal, the reply's
     * change went out with the old text (a sent line was briefly the draft, with its reply gone), and
     * a draft that syncs is a draft another device can repaint from in that window.
     */
    private inline fun batch(block: () -> Unit) {
        batching = true
        try {
            block()
        } finally {
            batching = false
            if (batchDirty) {
                batchDirty = false
                saveDraft()
            }
        }
    }

    private fun changeReply(next: PendingReply?) {
        if (next == reply) return
        reply = next
        saveDraft()
    }

    /** Fill the composer with a draft: its text and its reply. Null empties both. */
    private fun showDraft(draft: ComposerDraft?) {
        isShowingDraft = true
        try {
            val shown = draft ?: ComposerDraft()
            if (line != shown.body) restore(shown.body)
            changeReply(shown.reply)
        } finally {
            isShowingDraft = false
        }
    }

    /**
     * The stored draft changed — another device's write, a snapshot, or this composer's own flush
     * coming back (which matches the field, and changes nothing). Asks the view model whether a write
     * is being held off rather than trusting the store's copy, which the hold never touched.
     */
    internal fun storedDraftChanged(stored: ComposerDraft?) {
        val repaint = ComposerModel.repaintsDraft(stored, lastSeenDraft, protected = model.isDraftProtected(key))
        lastSeenDraft = stored
        if (!repaint) return
        showDraft(stored)
        // Another device emptying the draft frees the field for a line that's been waiting.
        restoreRefused()
    }

    /**
     * The composer is going away, or the app is — iOS's `viewWillDisappear`. The channel stops
     * hearing you're mid-sentence now rather than in 30 seconds, and what was typed goes to the server
     * now rather than half a second after you've gone.
     *
     * Ends the edit too — see [endEditing].
     */
    internal fun leave(changingConfiguration: Boolean = false) {
        // A rotation isn't leaving: the same text is back in the field a frame later, and telling the
        // channel `done` mid-sentence on every turn of the phone was a lie. It does end THIS state's
        // claim, though — the rebuilt composer starts knowing nothing was said, and could never end
        // an `active` left standing. `paused` is what's true of text sitting in the field, and it
        // lapses on its own; the next keystroke claims `active` again.
        if (changingConfiguration) pauseTyping() else endTyping()
        endEditing()
    }

    /** Leaving the field is the end of an edit, as leaving the buffer is: the web's blur. */
    internal fun focusLost() {
        endEditing()
    }

    /**
     * The edit is over — the field lost focus, or the composer (or the app) is going. Record the field
     * as it stands, END any composition, and flush.
     *
     * ⚠⚠ Recorded here, not left to the observer. The field's observer runs a frame behind, so the
     * last keystroke may not have reached it yet — and on dispose it's already cancelled, so it never
     * will. `flushDraft` sends only what was recorded, so that edit would simply be lost; and a
     * composition in flight would never be reported over, leaving the kit's `DraftSync.composing`
     * pinned to this buffer — the draft held back, and every remote write to it dropped as
     * "protected" — until the app next backgrounds. So the kit hears the field as it stands, preedit
     * included (it is what's on screen, and what the IME commits), with `composing = false`, and the
     * flush follows at once. The trackers record it, so the observer's late copy reads as nothing new
     * to the draft. (The typing signal is the observer's business: leaving has already ended it, and
     * a blur with a late keystroke still announces it when it lands.)
     */
    private fun endEditing() {
        val now = line to false
        if (now != lastEdit) {
            lastEdit = now
            saveDraft(composing = false)
        }
        model.flushDraft(key)
    }

    // MARK: - Send

    /**
     * The send button — iOS's `send(_:)`. The field is cleared before the line is handed over, and
     * every outcome leaves it clear but a refusal, which comes back into it: a command's answer (an
     * error included) prints in the buffer as a local line, which is where the kit puts it.
     */
    fun send() {
        val plain = field.text.toString()
        // Trimmed as characters, BEFORE the colour is written: trimming the written line would strand
        // the code for a trailing coloured space at its end. And emptiness from the characters too — a
        // field of coloured spaces is empty, though its line isn't.
        val sendable = ComposerModel.sendable(plain) ?: return
        val text = ComposerColors.line(sendable, colorsFor(plain))
        // Before the send, not after: the line itself is the end of composing, and a `done` trailing
        // it would be a second, redundant tag. The clear below re-enters `draftChanged` with an empty
        // field, a no-op once this has run.
        endTyping()
        onWillSend()
        // ⚠⚠ Cleared and flushed BEFORE the line goes to the view model, not after. Sending can take
        // the user somewhere inside that call — `/query` to a DM we hold, `/join` to a channel we're
        // in — and cleared after, a composer on its way out could save the command as this buffer's
        // draft, which syncs to every device (lurker-ios#188, lurker-ios#201). A line refused inside
        // the call comes back into this cleared field, and never travels with a switch: one that went
        // nowhere goes nowhere (see `ChatViewModel.run`).
        //
        // The reply is taken for the send first, and a spent one cleared in the same batch as the
        // field: the batch saves the draft, and the flush below sends it, so clearing it after left
        // an empty draft on the server still carrying the reply this line used up. Spent if the line
        // went out as it — a plain line or a `/me`. Any other command leaves it pending, as the web
        // does. A refusal brings it back with the line (`restoreRefused`), as it always has.
        val lineReply = reply
        batch {
            if (Replies.consumes(text)) changeReply(null)
            apply(FieldEdit("", 0))
        }
        // Emptied on the server now, not on the debounce: a quick close or a switch to another
        // device would otherwise find the line just sent still waiting there.
        model.flushDraft(key)
        val outcome = model.send(key, text, lineReply)
        // The field is free again, so anything still waiting can come back. Without this a second
        // refused line sat in the hold until the composer next appeared, which for someone staying
        // in one conversation is never.
        restoreRefused()
        when (outcome) {
            is ChatViewModel.SendOutcome.Activate -> onOpenBuffer(outcome.key)
            is ChatViewModel.SendOutcome.ShowProfile -> {
                // The keyboard is ALWAYS up here — the command was just typed — and a sheet would land
                // behind it.
                keyboard?.hide()
                onShowProfile(outcome.networkId, outcome.nick)
            }
            ChatViewModel.SendOutcome.None -> Unit
        }
    }

    /**
     * Put a refused line back in the composer, if one is waiting for this buffer and the field is
     * free for it (`ComposerModel.canRestoreRefused`) — otherwise it stays held for a later moment.
     * With the reply it went out as, and straight to the server as the draft: a restore isn't an
     * edit the field reports, and leaving it on the debounce could lose what just came back.
     *
     * Says nothing: the text appearing in the field is the whole message.
     *
     * Asked whenever the field might have freed up: a refusal arriving, the composer appearing, a send,
     * a cancelled reply, another device emptying the draft — and, for everything the user does by hand
     * (deleting the text), whenever eligibility itself turns true (`rememberComposerState`).
     */
    internal fun restoreRefused() {
        if (!ComposerModel.canRestoreRefused(field.text.toString(), reply)) return
        val line = model.takeUnsent(key) ?: return
        batch {
            restore(line.text)
            changeReply(line.reply)
            saveDraft()
        }
        model.flushDraft(key)
    }

    // MARK: - Suggestions

    /** A pill was tapped: insert it the way its context says, and keep the keyboard where it was. */
    fun pick(suggestion: Suggestion) {
        val selection = field.selection
        val edit = ComposerModel.pick(
            field.text.toString(), selection.min, selection.max, activeCompletion, suggestion.value, punctuation,
        ) ?: return
        apply(edit)
    }

    /**
     * Shift+Enter from a keyboard: a newline over the selection, as typing one would. The composer's,
     * because Compose's field doesn't map a shifted Enter (see `ComposerKeys`).
     */
    fun insertNewline() {
        val selection = field.selection
        field.edit {
            replace(selection.min, selection.max, "\n")
            this.selection = TextRange(selection.min + 1)
        }
        fieldChanged(snapshot())
    }

    // MARK: - Tab completion (lurker-android#63)

    /** The completion Tab last applied — what another Tab cycles, while the field still shows it. */
    private var tabCompletion: TabCompletion? = null

    /**
     * Tab (or Shift+Tab, [backward]) from a hardware keyboard: cycle the completion the field still
     * shows, else start one for the word under the caret — the web's Tab. Independent of the pills: the
     * edit is an ordinary one, so they re-evaluate after it as they would after typing. Nothing to
     * complete leaves the field alone (the key is still taken — see `ComposerKeys`).
     */
    fun tabComplete(backward: Boolean) {
        // A selection has no caret to complete at: Tab is still taken, and changes nothing.
        if (!field.selection.collapsed) return
        val text = field.text.toString()
        val caret = field.selection.min
        val session = tabCompletion
        val edit = if (session != null && session.continues(text = text, caret = caret)) {
            session.cycle(backward = backward)
        } else {
            val fresh = TabCompletion.begin(
                text = text,
                caret = caret,
                nicks = { query -> nickCandidates(query, limit = Int.MAX_VALUE) },
                channels = { ComposerModel.tabChannels(model.state.buffers.values, key.networkId, key) },
                punctuation = punctuation,
            )
            tabCompletion = fresh
            fresh?.edit ?: return
        }
        apply(FieldEdit(edit.text, edit.caret))
    }

    // MARK: - Replies (lurker-ios#184)

    /**
     * Reply to [message] — the seam U6's message actions call (lurker-ios#60). Pass the line as the
     * list SHOWS it (a relayed line as the person inside it), since that's whom the Reply addresses
     * and a cancel un-addresses. See `ComposerModel.replyPlan` for the rules.
     */
    fun startReply(message: Message) {
        val plan = ComposerModel.replyPlan(message, key, model.state, reply) ?: return
        var focuses = false
        batch {
            if (plan.cancelFirst) cancelReply()
            plan.start?.let(::changeReply)
            val nick = plan.address
            if (nick != null) {
                val addressed = ComposerModel.address(field.text.toString(), nick, punctuation)
                if (addressed != null) {
                    apply(addressed.first)
                    if (addressed.second && plan.marksAddressed) reply?.let { changeReply(it.copy(addressed = true)) }
                }
                // The tap that got here was a request to write something.
                focuses = true
            } else if (plan.start != null) {
                focuses = true
            }
        }
        if (focuses) focus()
    }

    /**
     * The strip's ✕ (or Escape): drop the pending reply, and take back the `nick: ` its Reply put in
     * the draft — only if the Reply put it there; one the user typed stays.
     */
    fun cancelReply() {
        val cancelled = reply ?: return
        batch {
            changeReply(null)
            if (cancelled.addressed) {
                ComposerModel.removeAddress(field.text.toString(), cancelled.nick, punctuation)?.let(::apply)
            }
        }
        // A reply on your own line, or in a DM, leaves the field empty — and a refused line was
        // waiting only because the reply was there. After the batch, so it's saved as its own draft.
        restoreRefused()
    }

    // MARK: - Insert (lurker-ios#14)

    /**
     * Drop [text] in from outside the field — a finished upload's link, the uploads browser's Add to
     * Message, a share's text (`ComposerInserts`). See [ComposerInsert] for where it goes and when the
     * keyboard comes up. An edit like any other the composer makes: the draft hears it, and so does the
     * typing signal — the field genuinely holds a line being composed now.
     */
    fun insert(text: String, atCaret: Boolean) {
        val before = field.text.toString()
        val current = field.selection
        val result = ComposerInsert.insert(before, current.min, current.max, text, atCaret)
        val composing = field.composition != null
        field.edit {
            if (atCaret) {
                replace(0, length, result.text)
                selection = TextRange(result.selectionStart, result.selectionEnd)
            } else {
                // ⚠ Appended, never the whole field rewritten: a later link of a run lands while the
                // user may be mid-word in the caption, and a replace would drop the IME's composition
                // under them. Nor is the caret moved while they're composing — the word stays theirs.
                append(result.text.substring(before.length))
                if (!composing) selection = TextRange(result.selectionStart, result.selectionEnd)
            }
        }
        fieldChanged(snapshot())
        if (result.focuses) focus()
    }

    private fun focus() {
        // The colour editor is where the user is writing; a finished upload's link lands without
        // pulling the keyboard back to the bar under it.
        if (editorOpen) return
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // MARK: - Away (lurker-ios#135)

    /**
     * The away strip's Back: `/back` on this network, scoped as a typed `/back` is (lurker#994). No
     * local change — the strip comes down when the server's `away-state` echo folds in, on every
     * device at once.
     *
     * False from the kit when it couldn't reach the server — it asks both connection signals and the
     * send. Nothing retries a Back, so say so, or the strip staying put reads as a Back that ignored
     * you.
     */
    fun back() {
        if (model.setBack(key.networkId)) return
        notice("Not connected — you're still away")
    }
}

/** A pen as the saved state holds it: its pair and its caret. */
private val penSaver: Saver<ComposerColors.Pen?, Any> = Saver(
    save = { pen -> pen?.let { arrayListOf(it.pair, it.at) } },
    restore = { saved -> (saved as List<*>).let { ComposerColors.Pen(it[0] as Int, it[1] as Int) } },
)

/** A field opened on [key]'s draft: this device's unflushed edit, else the server's. */
internal fun openField(model: ChatViewModel, key: BufferKey) =
    TextFieldState(initialText = ComposerColors.read(model.draft(key)?.body ?: "").first)

/**
 * The composer for [key], kept for the life of the conversation screen, with the effects that keep
 * it current: the field's own changes, the stored draft, the chrome, the refused-line nudge, and
 * the flush on leaving.
 *
 * @param onWillSend put the list where a send should leave it, before the line goes.
 */
@Composable
internal fun rememberComposerState(
    model: ChatViewModel,
    key: BufferKey,
    kind: BufferKind,
    onWillSend: () -> Unit,
    onOpenBuffer: (BufferKey) -> Unit,
    onShowProfile: (networkId: Int, nick: String) -> Unit,
): ComposerState {
    val scope = rememberCoroutineScope()
    // Saved, not just remembered: a rotation, a theme or font-size change, or a fold rebuilds the
    // screen, and a remembered field came back as the stored draft — empty in the Lurker console and a
    // server log, whose drafts don't sync, and with the caret thrown to the end everywhere else.
    val field = rememberSaveable(saver = TextFieldState.Saver) { openField(model, key) }
    val editorOpen = rememberSaveable { mutableStateOf(false) }
    val pen = rememberSaveable(stateSaver = penSaver) { mutableStateOf<ComposerColors.Pen?>(null) }
    val state = remember(model, key) { ComposerState(model, key, kind, scope, field, editorOpen, pen) }
    val activity = LocalContext.current.findActivity()
    val keyboard = LocalSoftwareKeyboardController.current
    val events = LocalAppEvents.current
    SideEffect {
        state.onWillSend = onWillSend
        state.onOpenBuffer = onOpenBuffer
        state.onShowProfile = onShowProfile
        state.keyboard = keyboard
        state.notice = { text -> events?.send(AppEvent.Notice(text)) }
    }

    // The user's edits, caret moves and compositions. The composer's own edits have already been
    // seen by the time these arrive, and dedupe away.
    LaunchedEffect(state) {
        snapshotFlow { state.snapshot() }.collect(state::fieldChanged)
    }
    // The draft another device wrote (lurker-ios#188). Its own stream: it moves the composer and
    // nothing else.
    LaunchedEffect(state) {
        model.statePublisher.map { it.drafts[key.id] }.distinctUntilChanged().conflate().collect(state::storedDraftChanged)
    }
    // The prompt and the away strip (lurker-ios#135). Two dedupes: the raw inputs first, which costs
    // next to nothing when they haven't moved, and only what gets past that searches the nicklist —
    // someone else's away-notify flip moves the list, not our modes.
    LaunchedEffect(state) {
        model.statePublisher
            .conflate()
            .map { ComposerChrome.Inputs.of(it, key, kind) }
            .distinctUntilChanged(ComposerChrome.Inputs::same)
            .map(ComposerChrome::of)
            .distinctUntilChanged()
            .collect { state.chrome = it }
    }
    // The chips' sources, all off the store: the nicklist, the rules, the network's channels, your nick
    // and who has spoken lately. A chip for someone who just left, or who was just ignored, mustn't stay
    // tappable — nor someone who just joined stay missing.
    LaunchedEffect(state) {
        model.statePublisher
            .conflate()
            .map { CandidateSources.of(it, key) }
            .distinctUntilChanged(CandidateSources::same)
            .collect { state.refreshSuggestions() }
    }
    // Who's typing (lurker-ios#61). Typing turns over with nothing else changing, and now touches only
    // the status row — so its own stream, kept out of the rows' gate, whose every pass is a rebuild of
    // the list. Compared as the rendered list: a peer re-sends `active` every ~3s with a fresh lease,
    // which changes nothing on screen.
    LaunchedEffect(state) {
        model.statePublisher.conflate().map { it.typists(key) }.distinctUntilChanged().collect { state.typists = it }
    }
    // A typing entry's lease expires by the clock, not by a frame — the last thing a peer sends is
    // `active`, and what happens next is nothing — so while anybody is typing the list is re-read once
    // a second. One second is well inside the shortest lease (6s), and coarse enough to be free.
    val someoneTyping = state.typists.isNotEmpty()
    LaunchedEffect(state, someoneTyping) {
        while (someoneTyping) {
            delay(1_000)
            state.typists = model.state.typists(key)
        }
    }
    // Where you are, and how the connection is doing: a DM peer's presence turns over with nothing else
    // changing, and moves nothing else.
    LaunchedEffect(state) {
        model.statePublisher.conflate().map { ComposerModel.location(it, key, kind) }.distinctUntilChanged().collect { state.location = it }
    }
    // The row's count is about every OTHER buffer, which is exactly what the rows' gate filters out —
    // so it gets its own stream, on the one number it shows (lurker#1099).
    LaunchedEffect(state) {
        model.statePublisher.conflate().map { ComposerModel.highlightCount(it, key) }.distinctUntilChanged().collect { state.otherHighlights = it }
    }
    // A held refused line comes back when the field frees up by hand — the text deleted — as well as on
    // the composer's own moves (each of which asks itself).
    LaunchedEffect(state) {
        snapshotFlow { state.canRestoreRefused }.distinctUntilChanged().collect { free -> if (free) state.restoreRefused() }
    }
    // A line the server refused, for this buffer, while the composer is here.
    LaunchedEffect(state, events) {
        events?.refusals?.collect { refused -> if (refused.id == key.id) state.restoreRefused() }
    }
    // Coming into view drains a refusal that landed while this composer didn't exist or wasn't
    // looked at (iOS's `viewDidAppear`); leaving — the buffer, or the app — ends typing and flushes.
    LifecycleStartEffect(state) {
        state.restoreRefused()
        onStopOrDispose { state.leave(changingConfiguration = activity?.isChangingConfigurations == true) }
    }
    return state
}
