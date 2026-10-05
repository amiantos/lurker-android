// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import net.amiantos.lurker.ui.message.MessageListLayout
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferPlaceholder
import net.amiantos.lurkerkit.model.EventFilter
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.SpeakerMap
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import kotlin.math.abs

/**
 * What the rows are built under that belongs to the SCREEN rather than the store — the three values
 * lurker-ios's `ChatViewController` hands `MessageRows` from its own fields. A value, so a build
 * can carry the options it was made with and the screen can tell a stale build from a current one
 * ([BuiltRows.options] vs [ConversationScroll.options]). Serializable, because the screen saves it
 * (`ConversationScroll.Memory`).
 */
internal data class RowOptions(
    /**
     * The read boundary, latched the first time the server states it and held for the visit — the
     * divider must not move as the screen marks messages read live. Null is "not told yet", which
     * is not the same as 0, "nothing read". iOS's `dividerAfterId`.
     */
    val dividerAfterId: Long? = null,
    /**
     * The one message id the ignore filter must not drop — the latest jump's target, for the
     * screen's life (iOS's `jumpExemptId`). You reach one by naming a specific message, and the
     * rules can have changed since; filtering it out would land the reader on a row that isn't
     * drawn, which never resolves. Outlives the landing, so the row doesn't vanish from under the
     * reader the instant they arrive on it.
     */
    val keeping: Long? = null,
    /**
     * The reader is deliberately looking past the `/clear` marker — a jump landed on a row below
     * it (lurker-ios#121). See [ConversationScroll.onRows] for when it arms and retires.
     */
    val showsClearedHistory: Boolean = false,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * One build of the rows and everything derived from it — made off the main thread
 * (`ConversationModel.built`), so the screen's composition only draws and the per-frame
 * decisions below are lookups.
 *
 * @param seq the frame this build's state is — see [ReplyWatch.landedIn] for why a jump has to know.
 */
internal class BuiltRows(
    val inputs: ConversationInputs,
    val options: RowOptions,
    /** The filtered messages the rows were built from — iOS's `messages`. */
    val visible: List<Message>,
    val rows: List<MessageRow>,
    val seq: Long = 0,
) {
    /** The lazy list's key per row — `MessageListLayout.rowKeys`, built here once per build. */
    val keys: List<String> = MessageListLayout.rowKeys(rows)

    /** Row index by key, for resolving a laid-out item back to the row it draws. */
    val indexByKey: Map<String, Int> = HashMap<String, Int>(keys.size * 2).also { map ->
        keys.forEachIndexed { index, key -> map[key] = index }
    }

    /** The row the unread divider sits at — iOS caches the same for its scroll-tick hot path. */
    val dividerRow: Int? = rows.indexOf(MessageRow.UnreadDivider).takeIf { it >= 0 }

    /**
     * The first unread row — the one just below the divider. The single definition of "where the
     * unreads begin", for the banner, the jump target and the flash. iOS's `firstUnreadRow`.
     */
    val firstUnreadRow: Int? = dividerRow?.let { if (it + 1 < rows.size) it + 1 else null }

    /** The buffer is showing a slice below the live tail (lurker-ios#42). */
    val detached: Boolean get() = inputs.buffer?.hasMoreNewer == true

    /**
     * Whether what's loaded is the buffer's real history yet, rather than the few live events that
     * outran its backlog — `BufferPlaceholder.historyLanded`, never a bare `hydrated`: the off-demand
     * kinds never hydrate on their own, and a `hydrated`-keyed gate would hold forever for them.
     */
    val historyLanded: Boolean
        get() = BufferPlaceholder.historyLanded(
            hydrated = inputs.buffer?.hydrated == true,
            hydratesOnDemand = inputs.kind.hydratesOnDemand,
            bufferExists = inputs.buffer != null,
            rosterSettled = inputs.rosterSettled,
        )

    /**
     * The row that now stands for message [containing] — its own, or the summary whose span covers it after
     * a page merged it into a consolidated run. Resolved by MESSAGE ID on every call, never a cached
     * index: a rebuild between two passes shifts every index.
     */
    fun rowIndex(containing: Long): Int? = rows.indexOfFirst { it.represents(containing) }.takeIf { it >= 0 }

    /** The lazy list's item index for a row: the list is reverse-laid-out, newest row first. */
    fun itemIndex(row: Int): Int = rows.size - 1 - row
}

/** One laid-out item of the lazy list, in plain numbers — `LazyListItemInfo` minus Compose. */
internal data class VisibleItem(val key: Any, val index: Int, val offset: Int, val size: Int)

/**
 * What the layout says about where the reader is, read from the lazy list's MEASURED layout
 * (`layoutInfo`, after a pass) and never from anything that can describe a previous frame — iOS
 * learned that the hard way, reading realized cells from inside a scroll callback that fires before
 * the table re-lays out, which latched the banner's retirement against the wrong offset.
 *
 * Coordinates are the lazy list's logical ones: it's reverse-laid-out, so offset 0 is the bottom of
 * the content area (above the bottom reservation) and offsets grow upward; the content area the
 * reader can actually see runs from 0 to `contentEnd`. Rows behind the bottom reservation (the
 * system bar, later the composer) are laid out but aren't "on screen", as iOS's inset rect excludes
 * the rows behind its composer.
 */
internal data class LayoutFacts(
    /** Within ~80dp of the newest row — iOS's `isNearBottom`. */
    val nearBottom: Boolean,
    /** Within ~300dp of the oldest loaded row: time to page older (iOS's `contentOffset.y < 300`). */
    val pagesOlder: Boolean,
    /** Within ~300dp of the newest loaded row: time to page newer, if detached. */
    val pagesNewer: Boolean,
    /** The unread divider is on screen. */
    val dividerVisible: Boolean,
    /**
     * The divider is wholly ABOVE the viewport. Off screen isn't enough: the divider is latched for
     * the visit, so scrolling up past it into older history leaves it off screen *below*, and a
     * banner promising unread above would then take you down.
     */
    val dividerAbove: Boolean,
) {
    companion object {
        /**
         * The facts for one measured pass, or null when the pass doesn't describe [built] — the lazy
         * list hasn't measured these rows yet (a new build was published and the frame that lays it
         * out hasn't run), checked item by item by KEY, the way iOS checked its table's row count
         * before trusting `rectForRow`. Null also when nothing is laid out at all.
         *
         * Distances to an edge whose row isn't laid out are estimated from the rows that are (their
         * average height times the rows in between), because a lazy list only measures what's on
         * screen and iOS's thresholds are distances, not counts.
         */
        /**
         * Whether a measured layout is of [built] — every laid-out item carries the key [built]
         * puts at its index. False for an empty layout, and for one the list measured from an
         * earlier build: a new build is published a frame before the pass that lays it out, and
         * anything read from the layout in between describes rows that are no longer there.
         */
        fun describes(items: List<VisibleItem>, built: BuiltRows): Boolean {
            if (items.isEmpty() || built.rows.isEmpty()) return false
            val total = built.rows.size
            return items.all { item ->
                val row = total - 1 - item.index
                row in 0 until total && built.keys[row] == item.key
            }
        }

        fun of(
            items: List<VisibleItem>,
            built: BuiltRows,
            contentEnd: Int,
            nearBottomPx: Int,
            pagingPx: Int,
        ): LayoutFacts? {
            if (!describes(items, built)) return null
            val total = built.rows.size
            val lowest = items.minBy { it.index }
            val highest = items.maxBy { it.index }
            val average = maxOf(1, items.sumOf { it.size } / items.size)
            val fromBottom = maxOf(0, -lowest.offset) + lowest.index * average
            val fromTop = maxOf(0, highest.offset + highest.size - contentEnd) + (total - 1 - highest.index) * average

            var dividerVisible = false
            var dividerAbove = false
            built.dividerRow?.let { row ->
                val index = built.itemIndex(row)
                val laidOut = items.firstOrNull { it.index == index }
                if (laidOut != null) {
                    dividerVisible = laidOut.offset + laidOut.size > 0 && laidOut.offset < contentEnd
                    dividerAbove = laidOut.offset >= contentEnd
                } else {
                    dividerAbove = index > highest.index
                }
            }
            return LayoutFacts(
                nearBottom = fromBottom < nearBottomPx,
                pagesOlder = fromTop < pagingPx,
                pagesNewer = fromBottom < pagingPx,
                dividerVisible = dividerVisible,
                dividerAbove = dividerAbove,
            )
        }
    }
}

/**
 * A history fetch in flight — `around` for a jump, `latest` for a re-attach — and whether its reply
 * has landed, judged frame by frame from this buffer's message list. lurker-ios's
 * `aroundSliceArrived` and its baseline, made robust.
 *
 * ⚠ The kit says nothing when a `history` reply lands; the store just applies it. So the reply is
 * recognised by what it does to the list, against what a live event does:
 *
 *  - **A live append** keeps every held `Message` OBJECT and adds newer ones behind them
 *    (`existing + message`). So does a page that brought nothing new.
 *  - **A history reply** builds the list from the frame's freshly parsed messages (`around`
 *    replaces outright; `latest` is the slice plus whatever newer was held). Its prefix is new
 *    objects even where every id is one already held — which is the case iOS's "the ids held at
 *    request time are gone" test missed: a small, fully loaded buffer whose `around` reply lacks a
 *    pruned anchor is a superset of what was held, replaces nothing, and left the jump pending
 *    for good, holding off paging, the follow and the banner.
 *
 * So it has landed when the list changed and isn't an append of the previous frame's — or the
 * anchor appeared, or an unhydrated buffer hydrated (an empty reply into a shell changes no list
 * anyone could compare), or a detached buffer re-attached (an empty `latest` keeps the old
 * objects but clears the flag). NOT "the buffer has messages": a small connect backlog tripped
 * that and gave the jump up before its real slice arrived.
 *
 * Known limit: anything else that rebuilds the list in between reads as the reply — a `/clear`
 * dropping its ephemerals, a full backlog replacing a hydrated buffer during a burst (a resume's
 * gap slice appends; a fresh connect's rows are shells). The jump then gives up early and lands at
 * the bottom, which is the safe way to be wrong.
 *
 * Fed every frame, strictly in order ([ConversationScroll.onFrame]); [arrivedAtSeq] is the frame
 * the reply landed in, so a build of an earlier frame — still in the pipeline — isn't mistaken for
 * rows that show it ([landedIn]).
 */
internal class ReplyWatch(
    /** The burst the request went out in — a new burst voids it. */
    val generation: Int,
    private var last: List<Message>?,
    private var hydrated: Boolean,
    private var detached: Boolean,
    private val anchorId: Long? = null,
) {
    /** The frame the reply landed in, or null while it hasn't. */
    var arrivedAtSeq: Long? = null
        private set

    /**
     * One frame's list and row, in order. True when this frame is the reply landing — the caller
     * hands that change to no other watch ([rebase] them instead): one reply answers one request.
     */
    fun observe(list: List<Message>?, row: Buffer?, seq: Long): Boolean {
        if (arrivedAtSeq != null) return false
        val nowHydrated = row?.hydrated == true
        val nowDetached = row?.hasMoreNewer == true
        val landed = (!hydrated && nowHydrated) ||
            (detached && !nowDetached) ||
            (anchorId != null && list.orEmpty().any { it.id == anchorId }) ||
            (list !== last && !isAppend(last, list))
        rebase(list, row)
        if (landed) arrivedAtSeq = seq
        return landed
    }

    /**
     * Take this frame as the new starting point without judging it — an older request's reply
     * landed in it, and the server answers in order, so it isn't this one's.
     */
    fun rebase(list: List<Message>?, row: Buffer?) {
        last = list
        hydrated = row?.hydrated == true
        detached = row?.hasMoreNewer == true
    }

    /** Whether the reply has landed and [current] is a build of a frame at or after it. */
    fun landedIn(current: BuiltRows): Boolean = arrivedAtSeq?.let { current.seq >= it } == true

    companion object {
        /** A watch starting from the frame the request went out after. */
        fun from(state: ChatState, key: BufferKey, anchorId: Long? = null): ReplyWatch {
            val row = state.buffers[key.id]
            return ReplyWatch(
                generation = state.burstGeneration,
                last = state.messages[key.id],
                hydrated = row?.hydrated == true,
                detached = row?.hasMoreNewer == true,
                anchorId = anchorId,
            )
        }

        /**
         * Whether [now] is [previous] with things added behind it — every held object still there,
         * by identity, in place. An empty or absent previous list says nothing either way (the
         * hydrate flag does); a list that went away isn't an append.
         */
        fun isAppend(previous: List<Message>?, now: List<Message>?): Boolean {
            if (previous.isNullOrEmpty()) return true
            if (now == null || now.size < previous.size) return false
            for (index in previous.indices) {
                if (now[index] !== previous[index]) return false
            }
            return true
        }
    }
}

/**
 * Where the conversation's list is, and where it's going: lurker-ios's `ChatViewController` state
 * at the top of the file (`pendingJumpId`, `dividerAfterId`, `dividerSeen`, `needsInitialScroll`,
 * `wasDetached`, `newWhileDetached`, …) and every decision made from it, pure. The composable feeds
 * it frames, builds and measured layouts, and performs the scrolls it asks for; this decides.
 *
 * Every rule here is a bug that shipped on iOS, and the comments say which. What doesn't carry over
 * is UIKit's mechanics — and `LazyColumn` fails differently, which the notes below say where it does.
 *
 * **Paging older needs no anchoring code at all.** The list is reverse-laid-out, so the newest row
 * is item 0 and an older page lands at HIGHER item indices: every row the reader can see keeps its
 * item index and its key, and the lazy list holds the viewport by its first visible item — which a
 * prepend never touches. iOS's "scroll anchoring across a history prepend" (capture the top line,
 * restore it after the reload) solved a problem this list doesn't have. Appends below the reader
 * are the lazy list's key anchoring too, except where it can't find the key — see [hold].
 *
 * **The opening landing is free.** A reverse layout starts at item 0, the newest row, and a pass
 * that moves the insets keeps item 0 at offset 0 — so iOS's "land, then hold until a fresh pass
 * finds it at the bottom" has nothing to converge on. The landings that ARE asked for — a jump to a
 * middle row, a re-attach to a new tail — go through [landing].
 */
internal class ConversationScroll(private val kind: BufferKind, memory: Memory = Memory()) {

    /**
     * What survives the screen being recreated (a rotation): the latched boundary above all — by
     * the time a rotation happens this screen has marked the buffer read, so re-latching would
     * latch our own mark and lose the divider. A jump in flight doesn't survive; it lands at the
     * bottom instead, which is the safe direction. Which jump requests have been consumed isn't
     * here either: this screen leaves composition whenever another buffer is pushed over it, and
     * its saved state goes with it — so that's `JumpLedger`'s, held by the scaffold.
     */
    data class Memory(
        val options: RowOptions = RowOptions(),
        val dividerSeen: Boolean = false,
        val lastClearedBeforeId: Long = 0,
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** What the next build must use. Changes are announced by the steps that make them. */
    var options: RowOptions = memory.options
        private set

    /**
     * Latched once the divider has been on screen in the buffer's real history: the reader has seen
     * where they left off, so the banner is spent for the visit. Needed because the divider never
     * clears — read forward past it and it's above you again, which would otherwise put the banner
     * back up over messages you've read. iOS's `dividerSeen`.
     */
    var dividerSeen: Boolean = memory.dividerSeen
        private set

    private var lastClearedBeforeId: Long = memory.lastClearedBeforeId

    fun memory(): Memory = Memory(options, dividerSeen, lastClearedBeforeId)

    /**
     * A jump on its way: what it's anchored on and how far its fetch has got. One object, replaced
     * whole, so a new field can never be left stranded set from a previous jump — iOS's
     * `resetJumpState` exists because one was.
     */
    private class PendingJump(
        /** What the FETCH centres on — and, except for the unread jump, what it lands on. */
        val anchorId: Long,
        /**
         * Land on (and flash) the first unread row instead of the anchor (lurker-ios#45). The unread
         * jump anchors its fetch on the read boundary, which sits ABOVE the divider and can be a
         * frame this buffer never renders (`lastReadId` is a max over every stored id) — so the
         * scroll target is decoupled from the anchor: the first rendered row past the divider, which
         * always resolves.
         */
        val flashesFirstUnread: Boolean,
    ) {
        /**
         * The `around` fetch, once it's gone out — null while it hasn't (or a drop or a new burst
         * voided it), and for a jump that needs none.
         */
        var fetch: ReplyWatch? = null

        /** Its scrolls are running — a second landing mustn't start a competing chain. */
        var converging = false
    }

    private var jump: PendingJump? = null

    /**
     * A re-attach is waiting for its latest slice, to land at the new tail — jump-to-latest from a
     * detached slice. iOS re-arms `needsInitialScroll` for this.
     */
    private var landsAtTail = false

    /**
     * The re-attach's `latest` fetch, once it's gone out — re-armed by a drop or a new burst as the
     * `around` fetch is: a request written to a socket that was silently replaced is lost, and
     * without the re-arm the pending landing would hold off paging and the follow for good.
     */
    private var tailFetch: ReplyWatch? = null

    /**
     * Fetches still in the air whose requester is gone — a jump superseded by another, or cancelled
     * by jump-to-latest, before its reply landed. The reply lands anyway (the store applies it
     * whoever is waiting), so it's kept here to be recognised as ITS reply rather than taken for the
     * next request's: replies are matched to requests in order ([onFrame]), and a new jump doesn't
     * send its own fetch until these have landed ([requestAround]), so its watch starts after them.
     * Voided by a drop or a new burst, like any request.
     */
    private val strays = ArrayDeque<ReplyWatch>()

    /** Keep [watch]'s reply accounted for if it hasn't landed. */
    private fun retire(watch: ReplyWatch?) {
        if (watch != null && watch.arrivedAtSeq == null) strays.addLast(watch)
    }

    /** Whether the buffer was detached as of the previous build — iOS's `wasDetached`. */
    private var wasDetached = false

    /** What the previous build drew, for telling a real append from everything else. */
    private var previousVisible: List<Message>? = null

    /** The rule set the previous build was filtered by — identity is the whole comparison. */
    private var lastIgnores: IgnoreSet? = null

    /**
     * The jump-to-latest pill's badge: live messages that landed below while the reader was up in
     * history. Zeroed the moment they're back at the bottom, however they got there. iOS's
     * `newWhileDetached`.
     */
    var newWhileAway: Int = 0
        private set

    /** A jump hasn't settled yet. */
    val jumpPending: Boolean get() = jump != null

    /** Something asked for a landing that hasn't happened — a jump, or a re-attach. */
    val landingPending: Boolean get() = jump != null || landsAtTail

    /**
     * ⚠⚠ Whether the screen may mark the buffer read. Only once the boundary is latched: marking
     * pushes the server's pointer to the newest message, and that pointer is the ONLY record of
     * where the reader left off — mark first and the value latched afterwards is our own mark,
     * which drops the divider below everything unread and silently reports the lot as read.
     * Reachable on a cold launch straight into a buffer: live traffic lands before the backlog that
     * says where the boundary is. Self-clearing — the frame that carries the pointer latches it,
     * and the mark goes out on that same pass ([onFrame] latches before the screen marks).
     */
    val marksRead: Boolean get() = options.dividerAfterId != null

    // MARK: - Jump requests

    /**
     * Land on [messageId]: straight there when it's loaded, through an `around` slice centred on it
     * when it isn't (lurker-ios#42). Supersedes any jump or re-attach already pending. A route's
     * request reaches here only once per session — the screen asks the scaffold's `JumpLedger`.
     */
    fun jumpTo(messageId: Long) = begin(messageId, flashesFirstUnread = false)

    /**
     * The unread banner's tap (lurker-ios#45) — always through the jump machinery, never a raw
     * scroll. On a large unread every loaded row is newer than the boundary, so the divider is
     * pinned to the top of what's loaded (the web's #216) and the seam isn't loaded at all; the
     * FETCH centres on the boundary to bring it in, and the landing goes to the first unread row.
     * False when there's no boundary to go to.
     */
    fun jumpToFirstUnread(): Boolean {
        val boundary = options.dividerAfterId ?: return false
        if (boundary <= 0) return false
        begin(boundary, flashesFirstUnread = true)
        return true
    }

    private fun begin(anchorId: Long, flashesFirstUnread: Boolean) {
        retire(jump?.fetch)
        retire(tailFetch)
        landsAtTail = false
        tailFetch = null
        jump = PendingJump(anchorId, flashesFirstUnread)
        options = options.copy(keeping = anchorId)
    }

    /** What jump-to-latest does. */
    enum class ToLatest {
        /** Nothing to go to. */
        Nothing,

        /** Detached: there's no tail on screen to ride to — fetch the latest slice and land there. */
        Reattach,

        /** Ride down to the newest row, animated — a distance covered, not a teleport. */
        ScrollDown,
    }

    /**
     * The pill's tap. Detached, re-attach by fetching the latest slice ([requestLatest]) and land at
     * its bottom once it arrives ([landing]); otherwise ride down.
     *
     * Either way, any pending jump is CANCELLED — the reader has said where they want to be, and a
     * jump left pending would land them back in old history the moment its slice arrived. And when
     * that jump's `around` fetch is still in the air, riding down isn't enough: the reply replaces
     * the slice under the reader whether anyone is waiting for it or not, so it re-attaches instead,
     * with a `latest` that lands after it.
     *
     * Either way, returning to the tail also puts a `/clear` back: the reveal was on loan to land
     * one jump, and this is the reader saying they're done looking past it (the web re-applies it
     * on the same affordance).
     */
    fun jumpToLatest(hasRows: Boolean, detached: Boolean): ToLatest {
        if (!hasRows) return ToLatest.Nothing
        newWhileAway = 0
        val fetching = jump?.fetch?.let { it.arrivedAtSeq == null } == true
        retire(jump?.fetch)
        jump = null
        if (options.showsClearedHistory) options = options.copy(showsClearedHistory = false)
        if (detached || fetching) {
            landsAtTail = true
            retire(tailFetch)
            tailFetch = null
            return ToLatest.Reattach
        }
        return ToLatest.ScrollDown
    }

    // MARK: - Frames

    /** What a frame asks of the screen. */
    class FrameStep(
        /** Fetch an `around` slice centred on this message (`ChatViewModel.loadAround`). */
        val loadAround: Long?,
        /** Fetch the latest slice, to re-attach (`ChatViewModel.loadLatest`). */
        val loadLatest: Boolean,
        /** [options] changed — the rows must be rebuilt. */
        val optionsChanged: Boolean,
    )

    /**
     * Every frame, in iOS's `apply` order: the `around` request (re-armed across reconnects), the
     * watch for its reply, then the read-boundary latch — before the screen marks read on the same
     * frame — and the re-attach's request.
     *
     * Every frame, not just the ones that build rows: the replies are recognised by how this
     * buffer's message list changed from one frame to the next ([ReplyWatch]), which only works on
     * a stream that skips nothing.
     *
     * ⚠⚠ The latch waits for `Buffer.readStateKnown`, never a row merely existing or `hydrated`.
     * Three paths materialize a row carrying nothing but defaults (the connect `snapshot`, a live
     * event for an unseen target, a `history` reply), and under all of them `lastReadId` is 0 —
     * indistinguishable from "read nothing". Latching there pinned the divider at 0 for the visit:
     * no divider and no banner however many unreads. `hydrated` was the first fix and was inert:
     * `history mode:latest` sets it without carrying a pointer. Only `backlog` and `read-state` set
     * `readStateKnown`.
     */
    fun onFrame(state: ChatState, key: BufferKey, seq: Long): FrameStep {
        val list = state.messages[key.id]
        val row = state.buffers[key.id]
        // A drop or a new burst voids what was asked over the old socket — no reply is coming.
        if (state.connection != SocketStatus.Connected) {
            strays.clear()
        } else {
            strays.removeAll { it.generation != state.burstGeneration }
        }
        // Replies are handed out in the order the requests went: the oldest outstanding watch gets
        // this frame's change, and the rest take it as their new starting point.
        var answered = false
        for (watch in strays + listOfNotNull(jump?.fetch, tailFetch)) {
            if (answered) watch.rebase(list, row) else answered = watch.observe(list, row, seq)
        }
        strays.removeAll { it.arrivedAtSeq != null }
        // After the replies are accounted for, so a request made now watches from this frame on.
        val around = requestAround(state, key)
        var changed = false
        if (options.dividerAfterId == null && row != null && row.readStateKnown) {
            options = options.copy(dividerAfterId = row.lastReadId)
            changed = true
        }
        val latest = requestLatest(state, key)
        return FrameStep(loadAround = around, loadLatest = latest, optionsChanged = changed)
    }

    /**
     * The `around` fetch a pending jump needs, or null: skipped when the anchor is already held (the
     * landing goes straight to it), re-armed when the socket drops or a new burst voids the request
     * — a burst, not a flag, because a request written to a socket that's silently replaced is lost
     * with the connection never leaving Connected (`HydrateGate`'s reasoning). Only channels and DMs
     * hydrate on demand; a server log's or the system buffer's jump has nothing to fetch and lands
     * against what's loaded. iOS's `requestAroundIfNeeded`.
     *
     * Not asked of a buffer the server has already said is empty (hydrated, nothing held): there is
     * nothing for the slice to find, and an empty reply into an empty buffer changes nothing anyone
     * could see it by — [landing] lets go instead.
     *
     * [state] must be the last frame the screen processed — never a newer read of the store — so the
     * reply watch starts from exactly the list the next frame will be compared with.
     */
    fun requestAround(state: ChatState, key: BufferKey): Long? {
        val pending = jump ?: return null
        if (!kind.hydratesOnDemand) return null
        if (state.connection != SocketStatus.Connected) {
            pending.fetch = null
            return null
        }
        if (pending.fetch?.generation?.let { it != state.burstGeneration } == true) pending.fetch = null
        if (pending.fetch != null) return null
        // A superseded fetch is still in the air: wait for its reply before sending ours, or its
        // reply would be taken for ours.
        if (strays.isNotEmpty()) return null
        val held = state.messages[key.id].orEmpty()
        // A row the `/clear` marker hides is held but not drawn — `onRows`' reveal resolves that.
        if (held.any { it.id == pending.anchorId }) return null
        val row = state.buffers[key.id]
        if (held.isEmpty() && row?.hydrated == true) return null
        pending.fetch = ReplyWatch.from(state, key, anchorId = pending.anchorId)
        return pending.anchorId
    }

    /**
     * Whether to send the re-attach's `latest` fetch now: once when it's asked for, and again when
     * a drop or a new burst voids it (as [requestAround]). [state] is the last processed frame, as there.
     */
    fun requestLatest(state: ChatState, key: BufferKey): Boolean {
        if (!landsAtTail) return false
        if (state.connection != SocketStatus.Connected) {
            tailFetch = null
            return false
        }
        if (tailFetch?.generation?.let { it != state.burstGeneration } == true) tailFetch = null
        if (tailFetch != null) return false
        tailFetch = ReplyWatch.from(state, key)
        return true
    }

    // MARK: - Builds

    /** What a new build asks of the screen before it's drawn. */
    class RowsStep(
        /** Keep the newest row in view as these rows land. */
        val follow: Boolean,
        /** [options] changed — another build is needed (and this one is stale). */
        val optionsChanged: Boolean,
    )

    /**
     * A new build, before it's drawn. [wasNearBottom] is read from the layout of the PREVIOUS rows —
     * where the reader was when these arrived.
     *
     * In order:
     *
     * 1. **The `/clear` reveal** (lurker-ios#121). A new clear retires it — or `/clear` looks broken
     *    until the buffer is reopened. And a pending jump onto a row the marker hides arms it: the
     *    row sits in the store but is filtered out of the rows, so the landing would wait for a row
     *    that never exists. Run on every build while a jump is pending, because the anchor becomes
     *    held-and-hidden at two moments — already loaded at the tap, or arriving in the fetched
     *    slice (the common path, and the one a bookmark tap landed on a blank screen through). NOT
     *    gated on the buffer being attached: arming it here too means the reveal survives the
     *    re-attach that reading forward ends in, rather than every row below the marker vanishing
     *    from under the reader at once.
     *    The unread jump never arms it: its target is the first unread RENDERED row, which a clear
     *    can't hide — with a clear in force the divider sits under the clear's own divider.
     * 2. **The badge** — live appends only, while up in history; see the comments inline.
     * 3. **Follow the tail** — ⚠⚠ only `wasNearBottom && !wasDetached && !nowDetached`. At the
     *    bottom of a DETACHED slice what lands is a newer page the reader pulled by scrolling into
     *    it; following it down re-triggers the near-bottom `loadNewer`, which pages again, and walks
     *    the buffer to its end with the reader pinned to the bottom, never seeing a line of it. A
     *    page appends below them; leaving them there is what lets them read forward through it.
     *    `wasDetached` covers the re-attach build (the last `after` page appends and clears the flag
     *    at once). And nothing follows while a landing is pending — the landing places.
     */
    fun onRows(built: BuiltRows, wasNearBottom: Boolean): RowsStep {
        val changed = revealIfJumpTargetHidden(built.inputs)

        val nowDetached = built.detached
        // Sat out on the build the rules changed: an `/unignore` restores rows throughout the loaded
        // window, a count increase nothing arrived to cause — "12 new" for a conversation that hadn't
        // moved. Null before the first build, which is right: nothing to have counted against.
        val rulesChanged = lastIgnores !== built.inputs.ignores
        lastIgnores = built.inputs.ignores
        val previous = previousVisible
        // Appends only — the first id moving means older pages were pulled — and not while detached
        // (or just re-attached): a detached buffer holds live traffic out, so anything appended there
        // is a page the reader asked for, not a surprise from below.
        if (previous != null && !wasNearBottom && !wasDetached && !nowDetached && !rulesChanged &&
            built.visible.firstOrNull()?.id == previous.firstOrNull()?.id
        ) {
            // By id, not by how much longer the list got: a restored row lands in the middle. And
            // not ephemerals (id 0) — a local echo isn't something the reader is missing below.
            val tail = previous.lastOrNull { it.id != 0L }?.id ?: 0L
            val appended = built.visible.filter { it.id > tail }
            // What the reader will actually SEE arrive — the tier the list applies, so the two can't
            // disagree. At `none` a netsplit rejoin appends dozens of rows that build to nothing.
            newWhileAway += EventFilter.visible(
                appended,
                settings = built.inputs.settings,
                speakers = built.inputs.speakers ?: SpeakerMap(),
                ownNick = built.inputs.ownNick,
            ).size
        }
        previousVisible = built.visible

        val follow = !landingPending && wasNearBottom && !wasDetached && !nowDetached
        wasDetached = nowDetached
        return RowsStep(follow = follow, optionsChanged = changed)
    }

    private fun revealIfJumpTargetHidden(inputs: ConversationInputs): Boolean {
        val buffer = inputs.buffer
        val cleared = buffer?.clearedBeforeId ?: 0L
        var changed = false
        if (cleared > 0 && cleared != lastClearedBeforeId && options.showsClearedHistory) {
            options = options.copy(showsClearedHistory = false)
            changed = true
        }
        lastClearedBeforeId = cleared
        val pending = jump
        if (pending != null && !pending.flashesFirstUnread && buffer != null && !options.showsClearedHistory && cleared > 0 &&
            pending.anchorId <= cleared && inputs.messages.orEmpty().any { it.id == pending.anchorId }
        ) {
            options = options.copy(showsClearedHistory = true)
            changed = true
        }
        return changed
    }

    // MARK: - Landing

    /** What the screen should do about a pending landing, now. */
    sealed interface Landing {
        /** Nothing is pending. */
        data object Idle : Landing

        /** Something is pending and can't be placed yet. */
        data object Wait : Landing

        /** Land at the newest row — a re-attach's new tail, or a jump that gave up. */
        data object AtTail : Landing

        /**
         * Start converging on the jump's target — [jumpTargetRow], re-resolved every pass — and
         * call [finishJump] when done. [token] identifies the jump, so a superseded chain stops.
         */
        class Converge(val token: Any) : Landing
    }

    /**
     * The landing against [current] — the build on screen. iOS's `landInitialIfNeeded` and
     * `beginJumpLanding`.
     *
     * A jump waits while its build is stale (its options aren't the current ones — a reveal or the
     * jump's own exemption hasn't been built yet), while its target isn't there yet, and while its
     * fetch is in flight. It gives up — landing at the bottom, or letting go on an empty buffer —
     * only once nothing more is coming: the `around` reply came back without the target
     * (`anchorMissing`), or the anchor is held but doesn't render here and there's nothing to fetch,
     * or this kind can't fetch at all and its history has landed. A jump that waited on a reply
     * that had already arrived without its target would hold the screen "landing" forever, and a
     * pending jump gates the hydrate, the follow and the banner.
     */
    fun landing(current: BuiltRows): Landing {
        val pending = jump
        if (pending != null) {
            if (pending.converging) return Landing.Wait
            if (current.options != options) return Landing.Wait
            if (jumpTargetRow(current) == null) {
                if (!abandons(pending, current)) return Landing.Wait
                jump = null
                return if (current.rows.isNotEmpty()) Landing.AtTail else Landing.Idle
            }
            pending.converging = true
            return Landing.Converge(pending)
        }
        if (landsAtTail) {
            // Not until the latest slice has landed AND these rows are of it: a stray `around`
            // reply landing first leaves the buffer detached, and the `latest` behind it is still
            // to come.
            val fetch = tailFetch ?: return Landing.Wait
            if (!fetch.landedIn(current) || current.detached) return Landing.Wait
            landsAtTail = false
            tailFetch = null
            return Landing.AtTail
        }
        return Landing.Idle
    }

    private fun abandons(pending: PendingJump, current: BuiltRows): Boolean {
        // The reply landed — in these rows — without the target: `anchorMissing`, or a message
        // pruned since it was quoted.
        pending.fetch?.let { return it.landedIn(current) }
        // Nothing was fetched. Held but not drawn here, with nothing to fetch; a kind that can't
        // fetch, whose history has landed; or a buffer the server says is empty.
        val held = current.inputs.messages.orEmpty()
        if (held.any { it.id == pending.anchorId }) return true
        if (!kind.hydratesOnDemand) return current.historyLanded
        return held.isEmpty() && current.inputs.buffer?.hydrated == true
    }

    /**
     * The row a converging jump scrolls to and flashes: the first unread for the unread jump, else
     * the anchor's own row. Null until it both exists and can be trusted — for the unread jump,
     * not while its fetch is in flight, when the divider is top-pinned to the stale window.
     */
    fun jumpTargetRow(current: BuiltRows): Int? {
        val pending = jump ?: return null
        if (pending.flashesFirstUnread) {
            val fetch = pending.fetch
            if (fetch != null) return if (fetch.landedIn(current)) current.firstUnreadRow else null
            // No fetch: trustworthy only when none is needed — the seam (the boundary) is already
            // held, or this kind can't fetch. Otherwise the fetch hasn't gone out (offline, or a
            // superseded one is still in the air) or a drop voided it, and the divider is still
            // pinned to the top of the stale window; the reconnect asks again.
            val held = current.inputs.messages.orEmpty().any { it.id == pending.anchorId }
            return if (held || !kind.hydratesOnDemand) current.firstUnreadRow else null
        }
        return current.rowIndex(pending.anchorId)
    }

    /** Whether [token] is still the pending jump — false once it's finished or superseded. */
    fun isCurrent(token: Any): Boolean = jump === token

    /** How a jump ends. */
    sealed interface Finish {
        /** Flash this row — the one it landed on. */
        class Flash(val row: Int) : Finish

        /** The target vanished: land at the bottom. */
        data object AtTail : Finish

        /** Stopped by the reader's own scroll: leave them where they are. */
        data object Release : Finish
    }

    /**
     * Release the jump [token] and say how it ends, re-resolving its target against [current] one
     * last time before the state is cleared. [interrupted]: the reader took hold of the list
     * mid-convergence — their scroll wins, and the jump lets go without pulling them anywhere or
     * flashing anything.
     */
    fun finishJump(token: Any, current: BuiltRows, interrupted: Boolean): Finish {
        if (jump !== token) return Finish.Release
        val target = if (interrupted) null else jumpTargetRow(current)
        jump = null
        return when {
            // The reader's own scroll ended it: no pulse on a row they're dragging away from.
            interrupted || (target == null && current.rows.isEmpty()) -> Finish.Release
            target != null -> Finish.Flash(target)
            else -> Finish.AtTail
        }
    }

    /**
     * The reader started dragging. A converging jump lets go (the screen stops its passes and calls
     * [finishJump] interrupted) — a bounded convergence released by the user's drag.
     *
     * ⚠⚠ Only a converging one. A jump still waiting on its fetch, and a re-attach waiting on its
     * latest slice, were ASKED for, and a drag while the slice is in flight must not quietly drop
     * them — iOS's `scrollViewWillBeginDragging` guards on exactly that. (iOS also releases its
     * OPENING landing here; this list's opening landing is free — see the class note — so there's
     * nothing else to release.)
     */
    fun onUserDrag(): Boolean = jump?.converging == true

    // MARK: - Layout

    /** The floating controls. */
    data class Pills(
        /** The jump-to-latest pill. */
        val showsLatest: Boolean = false,
        /** Its badge. */
        val newCount: Int = 0,
        /** The unread banner. */
        val showsUnread: Boolean = false,
    )

    /** What a measured layout asks of the screen. */
    class LayoutStep(val pills: Pills, val loadOlder: Boolean, val loadNewer: Boolean)

    /**
     * A measured layout of [current]. iOS's `scrollViewDidScroll` and `updateFloatingPills`, which
     * ran on every apply and scroll tick — here on every pass that moves anything.
     *
     * - Back at the bottom, however you got there, means caught up: the badge clears.
     * - **`dividerSeen`** latches when the divider is on screen — ⚠ not while a jump is pending (a
     *   divider swept past on the way to a search hit wasn't seen by anyone; the pass after the jump
     *   releases latches it if it really landed on screen), and ⚠⚠ not before the buffer's HISTORY
     *   HAS LANDED. Opening a buffer before its backlog shows the few live events that outran it, all
     *   newer than the boundary, so the divider pins to the top of a four-row stub that fits on
     *   screen: visible by arithmetic, seen by nobody, and the latch spent before the real backlog
     *   parks the reader thousands of points below their first unread with no banner.
     * - **The jump-to-latest pill**: up in history, or anywhere on a detached slice — even its
     *   bottom, where `nearBottom` is true; there's no live tail on screen.
     * - **The unread banner** (iOS's `UnreadBanner`): a first unread exists, it hasn't been seen,
     *   it's ABOVE the viewport, no jump is pending (the tap hides it, and the convergence's scrolls
     *   would otherwise flap it back on), and the connection banner doesn't want the slot. Gated on
     *   the LATCHED divider, never the live unread count, which marking read zeroes on frame 1.
     * - **Paging**: near the top, older; near the bottom of a detached slice, newer — the kit guards
     *   both against re-entry and against there being nothing more. Port addition: not while a
     *   landing is pending (iOS paged from user scrolls, which a landing doesn't make; this fires on
     *   every pass, so a page could otherwise splice onto the slice a jump is replacing), not
     *   before the history has landed (likewise — an unhydrated stub belongs to the hydrate), and
     *   ⚠ never while [online] is false ([mayWrite]): the kit marks a page in flight before the
     *   client drops the request, and nothing clears that on reconnect — paging would stay blocked.
     */
    fun onLayout(
        facts: LayoutFacts,
        current: BuiltRows,
        detached: Boolean,
        connectionBannerShown: Boolean,
        online: Boolean,
    ): LayoutStep {
        if (facts.nearBottom) newWhileAway = 0
        if (!jumpPending && current.historyLanded && facts.dividerVisible) dividerSeen = true
        val hasRows = current.rows.isNotEmpty()
        val pills = Pills(
            showsLatest = (detached || !facts.nearBottom) && hasRows,
            newCount = newWhileAway,
            showsUnread = current.firstUnreadRow != null && !dividerSeen && facts.dividerAbove &&
                !jumpPending && !connectionBannerShown,
        )
        val pages = online && !landingPending && current.historyLanded && !current.inputs.messages.isNullOrEmpty()
        return LayoutStep(
            pills = pills,
            loadOlder = pages && facts.pagesOlder,
            loadNewer = pages && detached && facts.pagesNewer,
        )
    }

    /**
     * Whether to pull older history because the filters thinned what's loaded down to NOTHING drawn
     * while more exists — iOS's `topUpIfUnscrollable`, for the one case the layout can't see: with
     * no rows there's no list to measure, so no pass will ever say "near the top", and the screen
     * would sit on an empty state the server could disprove. (A thinned window that draws a few
     * rows is unscrollable, which puts the oldest of them within reach of [onLayout]'s paging.)
     * An ignored sender who dominates a channel gets here, and so do the `none` and `smart` tiers.
     * Gated on the raw list being non-empty: with nothing loaded this is an unhydrated buffer, which
     * the hydrate owns. And on being [online], for the paging reason in [onLayout].
     */
    fun wantsTopUp(current: BuiltRows, online: Boolean): Boolean {
        if (!online || jumpPending || current.rows.isNotEmpty()) return false
        val buffer = current.inputs.buffer ?: return false
        return buffer.hydrated && buffer.hasMoreOlder && !current.inputs.messages.isNullOrEmpty()
    }

    /** Where to put the list so the reader's line stays put. */
    data class Hold(val index: Int, val scrollOffset: Int, val thenScrollBy: Int = 0)

    companion object {
        /**
         * How far the lazy list looks for its first visible item's key when the data set changes
         * (foundation's `NearestItemsExtraItemCount`). Past it, it can't find the key and keeps the
         * INDEX instead — so the viewport jumps by however many rows landed below.
         */
        const val KEY_WINDOW = 100

        /**
         * Whether a request sent now can reach the server: the socket is up AND the device has a
         * path. ⚠ Asked BEFORE anything the kit bookkeeps per request. `loadOlder`/`loadNewer` set
         * their in-flight flags before the client drops a write onto a dead socket, and `markRead`
         * advances its dedupe mark once the write is taken — which a dropped-but-unnoticed socket
         * still does. None of those reset on reconnect, so a mark sent offline suppresses the same
         * mark later, and a page sent offline blocks paging for good. Both signals: airplane mode
         * flips `reachable` while the socket still reads Connected.
         */
        fun mayWrite(state: ChatState): Boolean = state.socketWritable

        /**
         * Whether the reader was parked at the newest row as [drawn] is replaced — iOS's
         * `wasNearBottom` — read from the layout only if it's a layout OF [drawn] (see
         * [LayoutFacts.describes]); otherwise the last answer a measured layout gave, [lastKnown].
         * A layout of an earlier build would answer for rows the reader isn't looking at.
         */
        fun nearBottomBefore(
            visible: List<VisibleItem>,
            drawn: BuiltRows,
            firstVisibleIndex: Int,
            firstVisibleOffset: Int,
            slopPx: Int,
            lastKnown: Boolean,
        ): Boolean {
            if (!LayoutFacts.describes(visible, drawn)) return lastKnown
            return ConversationModel.followsTail(firstVisibleIndex, firstVisibleOffset, slopPx)
        }

        /**
         * Where to put the list as [next] replaces [drawn], or null to leave it to the lazy list's
         * own key anchoring — the common case, and the one that doesn't cancel a fling.
         *
         * ⚠ The case `LazyColumn` fails differently from `UITableView`: its key anchoring looks for
         * the first visible item's key only near where it was ([KEY_WINDOW]). A detached reader at
         * the bottom of their slice pulls a newer page — up to a hundred messages, plus dividers —
         * that lands BELOW them, shifting their row's index by more than that; the list then keeps
         * the index, jumps to the newest rows of the page, finds itself near the bottom, and pages
         * again: the cascade the follow rule exists to stop, reintroduced by the list. So a shift
         * that large is held by hand.
         *
         * And when the first visible row went away entirely (an ignore rule from another device
         * removes rows anywhere), the reader is held on the first visible row that survived —
         * iOS's `visibleAnchors`, whose second choice is usually inches from the first.
         */
        fun hold(
            firstVisibleIndex: Int,
            firstVisibleOffset: Int,
            visible: List<VisibleItem>,
            drawn: BuiltRows,
            next: BuiltRows,
        ): Hold? {
            if (next.rows.isEmpty()) return null
            // The first visible index and its offset describe whatever the list last measured; only
            // if that was [drawn] do they say where the reader is in it.
            if (!LayoutFacts.describes(visible, drawn)) return null
            val firstRow = drawn.itemIndex(firstVisibleIndex)
            val firstKey = drawn.keys.getOrNull(firstRow) ?: return null
            val nextRow = next.indexByKey[firstKey]
            if (nextRow != null) {
                val nextIndex = next.itemIndex(nextRow)
                if (abs(nextIndex - firstVisibleIndex) <= KEY_WINDOW) return null
                return Hold(index = nextIndex, scrollOffset = firstVisibleOffset)
            }
            for (item in visible.sortedBy { it.index }) {
                if (item.index <= firstVisibleIndex) continue
                val row = (item.key as? String)?.let { next.indexByKey[it] } ?: continue
                // Item at the bottom edge, then back down by where it sat: the lazy list takes no
                // negative scroll offset.
                return Hold(index = next.itemIndex(row), scrollOffset = 0, thenScrollBy = -item.offset)
            }
            return null
        }
    }
}
