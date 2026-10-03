// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.list

import net.amiantos.lurker.ui.shell.StatusTitle
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferListPlaceholder
import net.amiantos.lurkerkit.model.BufferOrder
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.FavoriteOrder
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NetworkAbbreviation
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus

/*
 * The buffer list's model: what the home screen shows, as plain data, built from the store by pure
 * functions. lurker-ios's `BufferListViewController` minus its views — `SectionID`, `ItemID`,
 * `Row`, `Header`, `Entry`, `Section` and `buildSections` with everything they call.
 *
 * No Compose in this file, so every rule runs in a plain JVM test (`BufferListModelTest`). The
 * screen (`BufferListScreen`) owns only what is genuinely view state — the burst latch, the live
 * drag, the optimistic favorites order — and hands it in.
 *
 * Drawn as the web sidebar's tree: Friends, Favorites, then each network, every group an uppercase
 * header over `├─`/`└─` rows. A network's header is its server log (the web's shape), so a network
 * has no "Server" row.
 */

/**
 * What a section *is*, independent of where it currently sits.
 *
 * ⚠⚠ The fix for a class of bug that cost a long QA session on iOS. Sections arrive in a different
 * order than they finally sit in: during the connect burst the list is `libera | …` and moments
 * later `Friends | Favorites | libera | …`, so index 0 stops being a network. Everything that
 * keyed off the *index* — which layout to build, which title to draw, whether a drag may land —
 * followed the position rather than the content, and a list caches geometry by position. The
 * result was a list whose rows were each correct and whose picture was not: headers between the
 * wrong rows, one drawn twice, networks apparently out of order. A `LazyColumn` caches item state
 * and measurements by key the same way, so identity is explicit here too.
 */
sealed interface SectionId {
    /**
     * Whether these rows can be dragged into a new order (lurker-ios#53). Friends and Favorites,
     * the two views of the server's one global favorites order (lurker#721) — not the networks,
     * which are the same sorted list this screen has always shown; a drag there would be undone by
     * the next rebuild.
     */
    val reorderable: Boolean get() = this == Friends || this == Favorites

    /**
     * The section's part of an item's `LazyColumn` key. Never contains `|`, which is what lets
     * [ItemId.rowKeyOf] split a key back apart.
     */
    val token: String

    data object Friends : SectionId {
        override val token: String get() = "friends"
    }

    data object Favorites : SectionId {
        override val token: String get() = "favorites"
    }

    /** A network's group: its header (the server log), its pinned buffers, then the rest. */
    data class Network(val networkId: Int) : SectionId {
        override val token: String get() = "net:$networkId"
    }

    /** Buffers whose network isn't in the roster yet (snapshot race). */
    data class Unrostered(val networkId: Int) : SectionId {
        override val token: String get() = "unrostered:$networkId"
    }
}

/**
 * One item's identity. Section-qualified because a `LazyColumn` requires keys unique across the
 * whole list — a repeated key crashes it, exactly as a repeated identifier crashed iOS's diffable
 * snapshot — and the header and pinned-break items every group can carry share a key from one
 * section to the next.
 */
data class ItemId(val section: SectionId, val key: String) {
    /**
     * The `LazyColumn` key: a `String`, because a lazy list's keys must be storable in a Bundle on
     * Android (it saves item state by key). `token|key`; the token never contains `|`, so the first
     * `|` always splits it, whatever the buffer key holds (`#foo|bar` is a legal channel).
     */
    val lazyKey: String get() = "${section.token}|$key"

    companion object {
        /**
         * The keys of the two items that aren't buffers. A `BufferKey.id` always starts with a
         * network id or `sys`, so these can't collide with one. A network's header takes its
         * server log's key instead — see [Header.log].
         */
        const val HEADER_KEY = "::header"
        const val PIN_BREAK_KEY = "::pins"

        /** The item key of a [lazyKey]: everything after the section's token. */
        fun rowKeyOf(lazyKey: String): String = lazyKey.substringAfter('|')

        /** The section token of a [lazyKey]. */
        fun sectionTokenOf(lazyKey: String): String = lazyKey.substringBefore('|')
    }
}

/** `├─` beside a row with more below it, `└─` beside the last, or the bare `│` through the pin break. */
enum class TreeGuide { Tee, Elbow, Spine }

data class Row(
    val buffer: Buffer,
    /**
     * The full network name. Friends and Favorites rows carry it for their accessibility label;
     * network rows leave it null, already sitting under their network's header.
     */
    val networkName: String?,
    /**
     * The short `li` disambiguator drawn after the name, set by `addNetworkHints` only on rows
     * whose name collides with another in the same group. Separate from [networkName] because the
     * two answer different questions: this one is "would you otherwise confuse this row with the
     * one beside it", and it's null far more often.
     */
    val networkHint: String? = null,
    /**
     * The peer's presence, set on every DM row: it mutes an away or offline name
     * (lurker-ios#167). Null for anything that isn't a DM. Compared with the row, so a presence
     * change redraws the one row.
     */
    val presence: FriendPresence? = null,
    /**
     * A Friends row — the one row kind whose buffer may be SYNTHESIZED (a favorite the store
     * hasn't materialized), so a tap must open-buffer first. An explicit flag, not "has
     * presence": presence is styling every DM row carries, not a fact about where the buffer came
     * from, and this gates a WRITE.
     */
    val isFriend: Boolean = false,
    /**
     * Whether an ignore rule mutes this buffer's plain-unread signal (lurker #359). Carried on the
     * row — and therefore compared with it — so muting or unmuting from another device redraws
     * the one row it affects.
     */
    val muted: Boolean = false,
    /**
     * A channel we hold a row for and aren't in (`ChatState.isParted`): drawn dimmed, and offered
     * Join on long-press. Read from the store, never from [buffer] — a favorite's row can carry a
     * synthesized buffer whose `joined` is a default, not a statement.
     */
    val parted: Boolean = false,
    /** `├─` or `└─`, set once the group's rows are known — see [Section.of]. */
    val guide: TreeGuide = TreeGuide.Tee,
    /**
     * Something half-written waits in this buffer's composer, here or on another device
     * (lurker-ios#188) — the pencil.
     */
    val hasDraft: Boolean = false,
) {
    /**
     * What the unread count counts.
     *
     * A muted buffer drops the plain-unread signal and shows highlights only, so ordinary traffic
     * stops moving the count while someone saying your name still does — which is the entire point
     * of muting a busy room you nonetheless follow. Highlights pass through untouched, and the
     * gold with them. Same downgrade the web applies in `BufferList.vue`'s `displayCount`.
     */
    val displayUnread: Int get() = if (muted) buffer.highlights else buffer.unread
}

/**
 * A group's header. For a network it's also the network's server log — tapping it opens the log,
 * as the web's header does — and [log] carries that buffer's row.
 */
data class Header(
    val title: String,
    /** The network's state as a dot, under this app's own connection. Null for Friends and Favorites. */
    val light: StatusLight? = null,
    /** The network's state in words, only when the network itself isn't connected. */
    val state: String? = null,
    val log: Row? = null,
    /** Every header but the first draws the rule between it and the group above. */
    val ruleAbove: Boolean = false,
)

/** Everything an item can be. The list is one flat sequence of these per group. */
sealed interface Entry {
    data class HeaderEntry(val header: Header) : Entry

    data class BufferEntry(val row: Row) : Entry

    data object PinBreak : Entry
}

@ConsistentCopyVisibility
data class Section private constructor(
    val id: SectionId,
    val header: Header,
    /** The buffer rows, pinned first. What a drag's index arithmetic works on. */
    val rows: List<Row>,
    /** How many of [rows] are pinned. A break is drawn after them when rows follow. */
    val pinnedCount: Int,
) {
    val hasPinBreak: Boolean get() = pinnedCount > 0 && pinnedCount < rows.size

    val headerItem: ItemId get() = ItemId(id, header.log?.buffer?.key?.id ?: ItemId.HEADER_KEY)

    /**
     * The header, then the rows, with the pinned break where it belongs. [rows] is unique by
     * construction — see [of].
     */
    val entries: List<Pair<ItemId, Entry>>
        get() {
            val out = ArrayList<Pair<ItemId, Entry>>(rows.size + 2)
            out.add(headerItem to Entry.HeaderEntry(header))
            for ((index, row) in rows.withIndex()) {
                if (hasPinBreak && index == pinnedCount) {
                    out.add(ItemId(id, ItemId.PIN_BREAK_KEY) to Entry.PinBreak)
                }
                out.add(ItemId(id, row.buffer.key.id) to Entry.BufferEntry(row))
            }
            return out
        }

    /** This section under a new header — the rule above, set once every section is known. */
    internal fun withHeader(header: Header): Section = copy(header = header)

    /**
     * This section with its rows in [keys]' order (a live drag), re-made through [of] so the
     * guides follow: the row that lands last becomes the `└─`, and the one that was last goes back
     * to `├─`. Only reorderable sections are re-ordered, and they never have pins. A key with no
     * row is skipped; a row [keys] doesn't name keeps its place at the end.
     */
    fun reordered(keys: List<String>): Section {
        val byKey = rows.associateBy { it.buffer.key.id }
        val placed = keys.mapNotNull { byKey[it] }
        val named = keys.toSet()
        return of(id = id, header = header, rows = placed + rows.filter { it.buffer.key.id !in named })
    }

    companion object {
        /**
         * ⚠⚠ De-duplicated once, HERE, so the model and the list cannot disagree.
         *
         * A `LazyColumn` throws on a repeated key — as iOS's diffable snapshot raised
         * `NSInternalInconsistencyException` on a repeated identifier — and the store can hold two
         * favorites under one key for a frame: the nick-change handler rewrites a favorite's target
         * by `bufferId` and leaves merge dedupe to the `favorites-changed` that follows, so being
         * friends with `alice` and `bob` and watching `bob` rename to `alice` collides them.
         *
         * iOS's first attempt de-duplicated the drawn items alone, which fixed the crash and bought
         * a subtler bug: the drag reorder does its index arithmetic against [rows], while the list
         * hands back positions in the de-duplicated items — so with a duplicate present the two
         * are off by one and a drop lands in the wrong place. One list, de-duplicated at the door,
         * and the question doesn't arise.
         *
         * The guides are set here too, since only the finished list knows which row is last. The
         * last pinned row keeps `├─` when rows follow the break, because the spine runs on through
         * it.
         */
        fun of(id: SectionId, header: Header, pinned: List<Row> = emptyList(), rows: List<Row>): Section {
            val seen = HashSet<String>()
            header.log?.let { seen.add(it.buffer.key.id) }
            val keptPinned = pinned.filter { seen.add(it.buffer.key.id) }
            val keptRest = rows.filter { seen.add(it.buffer.key.id) }
            val all = keptPinned + keptRest
            val guided = all.mapIndexed { index, row ->
                row.copy(guide = if (index == all.size - 1) TreeGuide.Elbow else TreeGuide.Tee)
            }
            return Section(id = id, header = header, rows = guided, pinnedCount = keptPinned.size)
        }
    }
}

/**
 * Exactly the part of `ChatState` the list draws from, compared field by field — lurker-ios's
 * `removeDuplicates` block, made a type.
 *
 * ⚠⚠ `statePublisher` publishes the whole state on every frame, and Compose state compares each new
 * value to the last with `equals`: a `ChatState` is a data class, so that would deep-compare every
 * buffer's messages, every frame. So the flow maps to this and is distinct by [same] before it
 * becomes state — and this is a plain class on purpose, so Compose's own comparison of two of them
 * is identity, and the one real comparison is [same]'s.
 *
 * Every field the list reads is here, and nothing else is: anything not compared would go stale
 * behind a frame [same] dropped as a duplicate. The kit's helpers (`rowPresence`, `buffer`,
 * `isParted`) are read off [state], a `ChatState` narrowed to these fields, so they answer from
 * exactly what was compared.
 *
 * Note: `ChatState.buffers` values carry no messages (those are `ChatState.messages`), so comparing
 * them is field-wise over small rows, and an unchanged map short-circuits on identity.
 */
class BufferListInputs private constructor(
    val networks: Map<Int, Network>,
    val buffers: Map<String, Buffer>,
    /** The Lurker title and the banner render it, and every status light is layered under it. */
    val connection: SocketStatus,
    /** As `connection`. */
    val reachable: Boolean,
    /**
     * A DM row's presence waits for the reconnect's snapshot (`rowPresence`), and that snapshot can
     * leave everything else here unchanged. Without this the rows would sit on "unknown" until some
     * unrelated frame let a rebuild through.
     */
    val snapshotSinceOpen: Boolean,
    /**
     * `backlog-complete` carries no state but this flag. On an account with nothing to list it
     * moves nothing else at all, so leaving it out would drop the frame as a duplicate and spin
     * "Loading buffers…" forever on exactly the account the empty state was written for.
     */
    val backlogComplete: Boolean,
    /**
     * Port addition — iOS reads this off the full state it keeps beside the filtered stream. The
     * burst gate reads it (`drawsList`), and the frame that ends a reconnect's burst can move
     * nothing else here (`backlogComplete` is already latched), so without it a list waiting on the
     * gate would wait for the fallback instead of the burst.
     */
    val rosterSettled: Boolean,
    /**
     * The Friends/Favorites sections render off this and [peerPresence]: the favorites list and
     * the per-nick presence DM names are styled by. A friend going away is a presence change with
     * no buffer change, so without these the name never dims.
     */
    val favorites: List<FavoriteEntry>,
    /** See [favorites]. */
    val peerPresence: Map<Int, Map<String, PresenceState>>,
    /**
     * Muting is an ignore rule (lurker #359), so a mute set on another device moves nothing else
     * on this screen — without this, a badge stays loud until some unrelated change happens to let
     * a rebuild through. Compared by identity (`===` is the right test — see `IgnoreSet`).
     */
    val ignores: IgnoreSet,
    /**
     * ⚠⚠ Pins order every network section, and a `pins-changed` frame moves NOTHING else in the
     * state — so without this the frame is dropped as a duplicate and a pin set on the web moves
     * nothing here until some unrelated message happens to let a rebuild through. Worse than a late
     * redraw on iOS: the screen's copy of the state was never updated either, so even its next
     * rebuild used the stale pins. Exactly the failure the favorites and ignores fields document.
     */
    val pinned: Map<Int, List<String>>,
    /**
     * The pencil (lurker-ios#188). Which buffers have a draft, not what's in them: a flush while
     * you type changes the text and nothing this list draws. `ChatState.drafts` is sparse (an empty
     * draft has no entry), so a key here is a buffer with a draft — the kit's `hasDraft`.
     */
    val draftedKeys: Set<String>,
) {
    /**
     * The kit's `ChatState` holding only these fields, so its helpers — `rowPresence`, `buffer`,
     * `isParted`, `isFavorite`, `ignores.mutesUnread` — are the kit's logic over exactly what was
     * compared. Built on first use: most frames are dropped by [same] before anyone asks.
     */
    val state: ChatState by lazy {
        ChatState(
            connection = connection,
            reachable = reachable,
            snapshotSinceOpen = snapshotSinceOpen,
            networks = networks,
            buffers = buffers,
            backlogComplete = backlogComplete,
            favorites = favorites,
            peerPresence = peerPresence,
            pinned = pinned,
            ignores = ignores,
        )
    }

    /** Whether this buffer has a draft waiting — the pencil. */
    fun hasDraft(key: BufferKey): Boolean = draftedKeys.contains(key.id)

    companion object {
        fun of(state: ChatState): BufferListInputs =
            BufferListInputs(
                networks = state.networks,
                buffers = state.buffers,
                connection = state.connection,
                reachable = state.reachable,
                snapshotSinceOpen = state.snapshotSinceOpen,
                backlogComplete = state.backlogComplete,
                rosterSettled = state.rosterSettled,
                favorites = state.favorites,
                peerPresence = state.peerPresence,
                ignores = state.ignores,
                pinned = state.pinned,
                draftedKeys = state.drafts.keys,
            )

        /** Whether the list would draw [a] and [b] identically. See each field for why it's here. */
        fun same(a: BufferListInputs, b: BufferListInputs): Boolean =
            a.networks == b.networks &&
                a.buffers == b.buffers &&
                a.connection == b.connection &&
                a.reachable == b.reachable &&
                a.snapshotSinceOpen == b.snapshotSinceOpen &&
                a.backlogComplete == b.backlogComplete &&
                a.rosterSettled == b.rosterSettled &&
                a.favorites == b.favorites &&
                a.peerPresence == b.peerPresence &&
                a.ignores === b.ignores &&
                a.pinned == b.pinned &&
                a.draftedKeys == b.draftedKeys
    }
}

/**
 * The just-dropped favorites order (bufferIds) awaiting its server echo, plus the store's
 * favorites it permutes — see [BufferListModel.orderedFavorites]. View state: the screen holds it.
 */
data class OptimisticFavorites(val order: List<Int>, val favoritesAtDrop: List<FavoriteEntry>) {
    /** Whether the store still holds the favorites this order was made from. */
    fun isCurrent(favorites: List<FavoriteEntry>): Boolean = favorites == favoritesAtDrop
}

/**
 * A live drag in Friends or Favorites (lurker-ios#53): the list as it stood when the row lifted,
 * and the order the finger has made of its group since.
 *
 * ⚠ The list is FROZEN for the drag's length — iOS's `rebuildDeferredByDrag`. Every frame that
 * arrives would rebuild it, the odds of one during the seconds a row is held are not small, and a
 * rebuild under a live drag resets what the drag is drawing against. Nothing is lost by waiting:
 * the inputs are already current, and the rebuild they feed is what's drawn the moment the hand
 * comes off.
 */
data class DragSession(
    val frozen: List<Section>,
    val sectionId: SectionId,
    val draggedKey: String,
    /** The group's row keys when the row lifted — what a drop's `from` and `visible` mean. */
    val startKeys: List<String>,
    /** The group's row keys now. */
    val liveKeys: List<String>,
    /**
     * The store's favorites when the row lifted. A drop is refused if they've moved since — see
     * [dropOrder].
     */
    val favoritesAtStart: List<FavoriteEntry>,
) {
    /** What to draw: the frozen list with the dragged group in its live order. */
    fun rendered(): List<Section> = frozen.map { if (it.id == sectionId) it.reordered(liveKeys) else it }

    /**
     * The row under [fromKey] moved to where [toKey] is. Rows reorder only within their own group
     * — a channel isn't a person — so a key from anywhere else is no move at all.
     */
    fun moved(fromKey: String, toKey: String): DragSession {
        val from = liveKeys.indexOf(fromKey)
        val to = liveKeys.indexOf(toKey)
        if (from < 0 || to < 0 || from == to) return this
        val next = liveKeys.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(liveKeys = next)
    }

    /**
     * The full favorites order (bufferIds) to send for this drop, or null to send nothing.
     *
     * ⚠ Refused outright if the favorites changed while the row was in the air — another device's
     * edit, say. The list is frozen during a drag, so the rows the finger was moving among are the
     * drag-start order while the store already holds the new one, and mapping that stale
     * arrangement onto the new order would send a reorder that overwrote the other edit. The row
     * goes home, and the list redraws from the current order.
     */
    fun dropOrder(favorites: List<FavoriteEntry>, optimistic: OptimisticFavorites?): List<Int>? {
        if (favorites != favoritesAtStart) return null
        // Resolved by KEY: the key is what the drag has carried all along, and the start order is
        // what `visible` means to `FavoriteOrder`.
        val from = startKeys.indexOf(draggedKey)
        val to = liveKeys.indexOf(draggedKey)
        if (from < 0 || to < 0) return null
        return BufferListModel.droppedOrder(favorites, optimistic, visible = startKeys, from = from, to = to)
    }

    companion object {
        /** A drag lifted from [sectionId]'s row [draggedKey], or null if that row can't be dragged. */
        fun begin(
            sections: List<Section>,
            sectionId: SectionId,
            draggedKey: String,
            favorites: List<FavoriteEntry>,
        ): DragSession? {
            if (!sectionId.reorderable) return null
            val section = sections.firstOrNull { it.id == sectionId } ?: return null
            val keys = section.rows.map { it.buffer.key.id }
            if (draggedKey !in keys) return null
            return DragSession(
                frozen = sections,
                sectionId = sectionId,
                draggedKey = draggedKey,
                startKeys = keys,
                liveKeys = keys,
                favoritesAtStart = favorites,
            )
        }
    }
}

/**
 * A row's long-press menu, read from the store as the menu opens — lurker-ios's context menu, item
 * for item. Null fields are items the menu leaves out.
 */
data class RowMenu(
    /**
     * "Join Channel", leading the menu for a parted channel: getting back in is the usual reason
     * to long-press one. Null when the row isn't parted; `false` when its network isn't connected
     * (a JOIN needs a live connection, and the section header already says why).
     */
    val joinEnabled: Boolean?,
    /** The Friends/Favorites toggle's title, or null for a `=nick` DCC chat. */
    val favoriteTitle: String?,
    /** Whether the toggle is destructive: removing a friend is losing a person's presence. */
    val favoriteDestructive: Boolean,
    /** Which way the toggle goes. */
    val isFavorite: Boolean,
    /** "Leave" for a joined channel, "Close" for everything else. */
    val closeTitle: String,
)

/** How a count says what's waiting: plain unread, or a mention. Null when nothing is. */
enum class UnreadSignal { Unread, Mentioned }

object BufferListModel {

    /**
     * Gives up waiting for `backlog-complete` and draws whatever has arrived, after this long.
     * Long enough that any burst worth waiting for lands first, short enough that a server which
     * never terminates one isn't a broken app. See `BufferListScreen`'s fallback.
     */
    const val BURST_WAIT_MS = 4_000L

    /**
     * ⚠⚠ Nothing is drawn until the connect burst has finished.
     *
     * The burst arrives a frame at a time and each one rebuilds the list, so it used to assemble
     * itself in front of the reader: a network's roster before its name, a favorite still sitting
     * in its network section until the favorites frame landed, a whole network appearing halfway
     * through. Eight visible states on the way to the right one, and only the last is true. The
     * spinner exists for exactly this and never got the chance — `BufferListPlaceholder.of` returns
     * `None` the moment any buffer exists, which during a burst is almost immediately.
     *
     * Only for the FIRST list of a session ([hasRenderedList]). After that a resync re-opens the
     * burst with a populated screen, and blanking it to a spinner because the server is re-sending
     * what we already have would be the same flicker wearing the opposite hat — the list stays true
     * while the burst runs and updates when it settles.
     */
    fun drawsList(rosterSettled: Boolean, hasRenderedList: Boolean): Boolean = rosterSettled || hasRenderedList

    /**
     * The centred placeholder when the list has no rows, so a blank screen always says which kind
     * of blank it is.
     *
     * Keyed on `backlogComplete` — the `backlog-complete` terminal frame — and neither on the
     * socket being up nor on the `snapshot` frame having arrived. Both of those are prefixes of the
     * answer rather than the answer, and each flashes the empty state on a different kind of
     * account; `ChatState.backlogComplete` spells out which and why.
     *
     * Whether the connection is the reason nothing has landed is the banner's question, not this
     * one's — so an offline launch shows the spinner *and* the banner, each answering its own.
     */
    fun placeholder(inputs: BufferListInputs, sections: List<Section>): BufferListPlaceholder =
        BufferListPlaceholder.of(
            hasBuffers = sections.isNotEmpty(),
            hasNetworks = inputs.networks.isNotEmpty(),
            backlogComplete = inputs.backlogComplete,
        )

    /**
     * Fixed except for the subtitle: this screen is the app, not a buffer, so it always reads
     * "Lurker" and follows the socket rather than any one network.
     */
    fun statusTitle(inputs: BufferListInputs): StatusTitle =
        StatusTitle(
            title = Buffer.system.displayName(),
            status = StatusLight.of(reachable = inputs.reachable, connection = inputs.connection, network = null),
            detail = null,
        )

    fun buildSections(inputs: BufferListInputs, optimistic: OptimisticFavorites? = null): List<Section> {
        val state = inputs.state
        // Partition the favorites list ONCE — the sections and the roster exclusion both derive
        // from the same two slices, so a future classification change can't update one walk and
        // miss another (the double-printed-DM bug the split exists to prevent).
        val ordered = orderedFavorites(inputs.favorites, optimistic)
        val friendEntries = ordered.filter(::isFriendEntry)
        val channelEntries = ordered.filterNot(::isFriendEntry)
        // ⚠⚠ A favorite is a RELOCATION, not a shortcut. Its row under Friends or Favorites is where
        // it lives, so it's hidden from its network's group rather than printed twice — matching the
        // web, where `isFavoriteBuf` filters favorites out of both the pinned and unpinned halves of
        // every network group.
        val favoriteKeys = ordered.mapTo(HashSet()) { it.key.id }

        val byNetwork = BufferOrder.byNetwork(state.buffers.values, excluding = favoriteKeys)
        // Before the favorites exclusion, because a network whose every open buffer is favorited
        // still exists and still has a log — see `withServerLog`.
        val networksInUse = state.buffers.values.mapNotNullTo(HashSet()) { it.networkId }

        val sections = ArrayList<Section>()

        // Abbreviations are per-account, so they're computed once and shared by both groups; they're
        // measured against every network the user has, not the ones on screen, which is also what
        // the web computes for the same rows (see `NetworkAbbreviation`).
        val abbreviations = NetworkAbbreviation.shortestUniquePrefixes(state.networks.mapValues { it.value.displayName })
        val friends = addNetworkHints(abbreviations, friendRows(friendEntries, inputs))
        val favorites = addNetworkHints(abbreviations, favoriteRows(channelEntries, inputs))
        // Friends first, then Favorites — the web sidebar's order (FRIENDS above FAVORITES), and the
        // two are one list on the server, so the halves reading top-to-bottom differently was a
        // needless thing to have to re-learn per client. People also earn the top slot on their
        // own: theirs are the rows whose presence changes while you look.
        //
        // Both are reorderable since lurker#721 — the order is the server's global favorites order,
        // shared with the web client.
        if (friends.isNotEmpty()) {
            sections.add(Section.of(id = SectionId.Friends, header = Header(title = "Friends"), rows = friends))
        }
        if (favorites.isNotEmpty()) {
            sections.add(Section.of(id = SectionId.Favorites, header = Header(title = "Favorites"), rows = favorites))
        }

        // The user's own order, not ours: they arranged their networks on the web, and a phone that
        // re-alphabetises them is a phone you have to re-read every time you pick it up. Same for
        // the pins inside each one.
        val seen = HashSet<Int>()
        for (network in BufferOrder.networks(state.networks)) {
            seen.add(network.id)
            // `withServerLog`, so a network in use always has its log and therefore a header — the
            // server row used to come and go with the connect burst's prune.
            val buffers = BufferOrder.withServerLog(
                byNetwork[network.id].orEmpty(),
                networkId = network.id,
                networkHasOpenBuffers = networksInUse.contains(network.id),
            )
            networkSection(SectionId.Network(network.id), network.id, network, buffers, inputs)?.let(sections::add)
        }
        // Buffers whose network isn't in the roster yet (snapshot race). Sorted, because a map's
        // order isn't stable from one rebuild to the next.
        //
        // With its log synthesized like a rostered network's, so the header is the way into the log
        // here too rather than a label you can't open.
        for (networkId in byNetwork.keys.sorted()) {
            if (seen.contains(networkId)) continue
            val buffers = BufferOrder.withServerLog(
                byNetwork[networkId].orEmpty(),
                networkId = networkId,
                networkHasOpenBuffers = true,
            )
            networkSection(SectionId.Unrostered(networkId), networkId, null, buffers, inputs)?.let(sections::add)
        }

        // The rule sits BETWEEN groups, so the first one doesn't draw it.
        for (index in 1 until sections.size) {
            sections[index] = sections[index].withHeader(sections[index].header.copy(ruleAbove = true))
        }
        return sections
    }

    /**
     * One friend per favorited DM — the DM slice of the server's favorites list, in the user's
     * global order (shared with the web client's FRIENDS section since lurker#721).
     */
    internal fun friendRows(entries: List<FavoriteEntry>, inputs: BufferListInputs): List<Row> =
        entries.map { entry ->
            // `buffer` resolves an existing DM (keeping its server-cased target and unread count)
            // or synthesizes an unhydrated one to open — the same handoff the join flow uses, so
            // tapping the row hydrates on the conversation.
            val buffer = inputs.state.buffer(entry.key)
            Row(
                buffer = buffer,
                networkName = inputs.networks[entry.networkId]?.displayName,
                presence = inputs.state.rowPresence(networkId = entry.networkId, nick = entry.target),
                isFriend = true,
                muted = isMuted(buffer, inputs),
                hasDraft = inputs.hasDraft(buffer.key),
            )
        }

    /**
     * A favorites entry that belongs under Friends: a DM, classified the way the server does (so
     * `&`/`+`/`!` channels never masquerade as people — a channel is `#&+!`, never just `#`).
     */
    internal fun isFriendEntry(entry: FavoriteEntry): Boolean =
        BufferKind.of(networkId = entry.networkId, target = entry.target) == BufferKind.Dm

    /**
     * Favorited channels — the channel slice of the server's global favorites order (lurker#721,
     * shared with the web client's FAVORITES section).
     */
    internal fun favoriteRows(entries: List<FavoriteEntry>, inputs: BufferListInputs): List<Row> =
        entries.mapNotNull { entry ->
            val buffer = inputs.state.buffer(entry.key)
            if (buffer.kind == BufferKind.System || buffer.kind == BufferKind.Server) return@mapNotNull null
            // No presence: this is the channel slice, and only a DM has a peer.
            Row(
                buffer = buffer,
                networkName = buffer.networkId?.let { inputs.networks[it]?.displayName },
                muted = isMuted(buffer, inputs),
                parted = inputs.state.isParted(buffer.key),
                hasDraft = inputs.hasDraft(buffer.key),
            )
        }

    /**
     * `favorites` with the just-dropped-but-not-yet-echoed order applied. A drop permutes the local
     * sections AND sends the reorder, but the frozen list is released the instant the drag ends —
     * rebuilding from the store's PRE-drop order, which snapped the row home for a round-trip and
     * made a quick second drag compute from the reverted base. The shadow order bridges the gap; ANY
     * favorites change (the echo, or another device's edit) is authoritative and drops it — the
     * screen clears its copy when [OptimisticFavorites.isCurrent] goes false, and this ignores a
     * stale one regardless.
     */
    fun orderedFavorites(favorites: List<FavoriteEntry>, optimistic: OptimisticFavorites?): List<FavoriteEntry> {
        if (optimistic == null || !optimistic.isCurrent(favorites)) return favorites
        val byId = LinkedHashMap<Int, FavoriteEntry>()
        for (entry in favorites) byId.putIfAbsent(entry.bufferId, entry)
        val out = optimistic.order.mapNotNullTo(ArrayList()) { byId[it] }
        val placed = optimistic.order.toSet()
        out.addAll(favorites.filter { it.bufferId !in placed })
        return out
    }

    /**
     * The full favorites order (bufferIds) a drop sends, or null when it changes nothing.
     *
     * The group shows a kind-filtered SUBSET of the server's one global favorites list (this
     * group's kinds only, and a favorite whose network is still connecting has a slot and no row)
     * — so the move is mapped onto the stored order rather than applied by index. `FavoriteOrder`
     * owns that, and answers the stored list unchanged for anything it can't interpret. The FULL
     * permuted list goes to the server (a subset would float to the front and demote everything
     * unmentioned — the other section included); the `favorites-changed` echo is the authoritative
     * rebuild.
     *
     * ⚠ The base is the order ON SCREEN — the store's with any unechoed drop applied — not the
     * store's alone. A second drag made before the first one's echo, putting the rows back where
     * the store still has them, diffed against the store as "no change": it sent nothing, snapped
     * back, and the first drop's echo then saved the order the user had just undone.
     */
    fun droppedOrder(
        favorites: List<FavoriteEntry>,
        optimistic: OptimisticFavorites?,
        visible: List<String>,
        from: Int,
        to: Int,
    ): List<Int>? {
        val stored = orderedFavorites(favorites, optimistic).map { it.key.id }
        val reordered = FavoriteOrder.moved(stored, visible = visible, from = from, to = to)
        if (reordered == stored) return null
        val idByKey = LinkedHashMap<String, Int>()
        for (entry in favorites) idByKey.putIfAbsent(entry.key.id, entry.bufferId)
        return reordered.mapNotNull { idByKey[it] }
    }

    internal fun rosterRow(buffer: Buffer, inputs: BufferListInputs): Row =
        Row(
            buffer = buffer,
            networkName = null,
            presence = peerPresence(buffer, inputs),
            muted = isMuted(buffer, inputs),
            parted = inputs.state.isParted(buffer.key),
            hasDraft = inputs.hasDraft(buffer.key),
        )

    /**
     * A DM's peer presence, null for anything that isn't a DM (lurker-ios#167). Every DM row reads
     * it, not just Friends: a person who's away or offline looks it wherever their DM sits.
     */
    internal fun peerPresence(buffer: Buffer, inputs: BufferListInputs): FriendPresence? {
        val networkId = buffer.networkId
        if (buffer.kind != BufferKind.Dm || networkId == null) return null
        return inputs.state.rowPresence(networkId = networkId, nick = buffer.target)
    }

    /**
     * Tag the rows whose names collide **within this one group** with a short `li` network hint.
     *
     * Per group, not pooled across Friends and Favorites: a group is the set you actually scan as
     * a set. Two identical names under one header are the confusion worth spending a label on; the
     * same name under two different headers already reads as two different things. And only on a
     * collision, because both groups are curated — you put each row there, so you know which
     * network it's on until two of them read alike.
     *
     * Gated on the ACCOUNT having more than one network, which only changes when you add or remove
     * one, so a label can't come and go under rows you never touched. The gate is a no-op for the
     * collision itself — two rows sharing a name in one group are necessarily on different
     * networks, a buffer key being network + target.
     *
     * [abbreviations] is computed once by the caller: it depends only on the account's networks,
     * and a rebuild runs on every state change. It carries exactly one entry per network (see
     * `NetworkAbbreviation`), which is what makes its size the gate above.
     *
     * Names compare folded the way `BufferKey.id` folds them (`lowercase()`), so a hint appears
     * exactly where two keys would differ only by network.
     */
    internal fun addNetworkHints(abbreviations: Map<Int, String>, rows: List<Row>): List<Row> {
        if (abbreviations.size <= 1) return rows
        val counts = HashMap<String, Int>()
        for (row in rows) counts.merge(row.buffer.target.lowercase(), 1, Int::plus)
        val hinted = counts.filterValues { it > 1 }.keys
        if (hinted.isEmpty()) return rows
        return rows.map { row ->
            val networkId = row.buffer.networkId
            val abbreviation = networkId?.let { abbreviations[it] }
            if (row.buffer.target.lowercase() in hinted && abbreviation != null) row.copy(networkHint = abbreviation) else row
        }
    }

    /**
     * Whether this buffer's plain-unread signal is muted (lurker #359).
     *
     * Mute isn't a flag on the buffer — it's an ignore rule carrying `NOUNREAD`, which is what lets
     * one rule mute a channel, a DM, or a whole network's worth of buffers at once. See
     * [Row.displayUnread] for what the count then shows.
     */
    internal fun isMuted(buffer: Buffer, inputs: BufferListInputs): Boolean =
        inputs.ignores.mutesUnread(networkId = buffer.networkId, target = buffer.target)

    /**
     * A network's group: its header, its pinned buffers, a break, then the rest. Null when it has
     * nothing to show.
     *
     * The server log isn't a row. It's the header — the web sidebar's shape — so the network's name
     * is the way into its log, and the list doesn't spend a row per network saying "Server" under a
     * header that already names the network.
     *
     * Pins are a dashed break inside the group rather than a section of their own, which is how the
     * web draws it.
     *
     * [network] is null for buffers whose network hasn't arrived in the roster yet. Its pins are
     * read by id regardless: they ride the snapshot, not the roster, and a group drawn unpinned
     * during the race would reshuffle the moment the roster landed.
     */
    internal fun networkSection(
        id: SectionId,
        networkId: Int,
        network: Network?,
        buffers: List<Buffer>,
        inputs: BufferListInputs,
    ): Section? {
        if (buffers.isEmpty()) return null
        val log = buffers.firstOrNull { it.kind == BufferKind.Server }
        val split = BufferOrder.split(
            buffers.filter { it.kind != BufferKind.Server },
            pinned = inputs.pinned[networkId].orEmpty(),
        )
        var header = Header(
            // NOT the literal "network" an unrostered group once said on iOS — that was #136's
            // placeholder surviving where its fix didn't reach, and it reads as a real name.
            title = network?.displayName ?: Network.unnamedDisplayName,
            log = log?.let { rosterRow(it, inputs) },
        )
        if (network != null) {
            // Layered outside-in like every other status light: while this app's own socket is
            // down, a network's last-known state is stale, and a green dot would be a claim we
            // can't make.
            val light = StatusLight.of(reachable = inputs.reachable, connection = inputs.connection, network = network.state)
            // In words only when it's the NETWORK that isn't connected — the network screen's
            // words, lowercased for a header that's otherwise uppercase. When Lurker's own
            // connection is the problem, the banner already says so once for every network.
            val appUp = StatusLight.of(reachable = inputs.reachable, connection = inputs.connection, network = null) ==
                StatusLight.Good
            val words = if (appUp && network.state != ConnectionState.Connected) network.state.label.lowercase() else null
            header = header.copy(light = light, state = words)
        }
        return Section.of(
            id = id,
            header = header,
            pinned = split.pinned.map { rosterRow(it, inputs) },
            rows = split.rest.map { rosterRow(it, inputs) },
        )
    }

    /**
     * The swipe action's title for a row in [section], or null when the row has none.
     *
     * Network rows only. Friends and Favorites get nothing: closing a favorite also unfavorites it,
     * and a full swipe fires the action outright — too easy a way to drop a friend. Their long-press
     * menu has Close. The server log and the system buffer can't be closed (and neither is ever a
     * row). A parted channel has nothing to leave, so it's Close — as on the long-press menu.
     */
    fun swipeTitle(section: SectionId, row: Row): String? {
        if (section.reorderable) return null
        val kind = row.buffer.kind
        if (kind == BufferKind.Server || kind == BufferKind.System) return null
        return if (kind == BufferKind.Channel && !row.parted) "Leave" else "Close"
    }

    /**
     * The long-press menu for [buffer], read from [state] as the menu opens: a drop while it sits
     * open leaves Join enabled, and that JOIN goes nowhere — as a typed `/join` would. Null on the
     * system buffer and a server log, which have no menu.
     *
     * One favorites flag, two vocabularies (matching the web client): a DM is a "Friend", a channel
     * a "Favorite". The server owns the list — no local mutation; the `favorites-changed` echo
     * rebuilds this screen (favoriting also drops any pin the web held on the buffer: one placement
     * per buffer).
     *
     * A `=nick` DCC chat is neither: the server refuses to favorite one (a Friend is a person whose
     * presence is tracked, and a DCC peer has none — the socket is the whole story), so the item
     * would be a tap that does nothing (lurker#270).
     *
     * ⚠⚠ Close belongs on THIS menu, not only on the network row's swipe. A favorited buffer has no
     * network row any more — its Favorites row is where it lives — so without this there is no way
     * to leave a favorited channel short of unfavoriting it first, and the buffers people favorite
     * are exactly the ones they keep. The web reaches the same conclusion by giving every row,
     * favorites included, one menu ending in Close.
     */
    fun rowMenu(state: ChatState, buffer: Buffer): RowMenu? {
        if (buffer.kind == BufferKind.System || buffer.kind == BufferKind.Server) return null
        val networkId = buffer.networkId ?: return null
        val isDm = buffer.kind == BufferKind.Dm
        val isFavorite = state.isFavorite(buffer.key)
        val parted = state.isParted(buffer.key)
        val favoriteTitle = when {
            buffer.kind == BufferKind.Dcc -> null
            isFavorite -> if (isDm) "Remove from Friends" else "Remove from Favorites"
            else -> if (isDm) "Add to Friends" else "Add to Favorites"
        }
        return RowMenu(
            joinEnabled = if (parted) state.networks[networkId]?.state == ConnectionState.Connected else null,
            favoriteTitle = favoriteTitle,
            favoriteDestructive = isFavorite && isDm,
            isFavorite = isFavorite,
            closeTitle = if (buffer.kind == BufferKind.Channel && !parted) "Leave" else "Close",
        )
    }

    /**
     * What a count says, shared by a row and a network header so the same state reads the same in
     * both, null when nothing is waiting. The web's two defaults: `look.color.buffer.unread` is the
     * accent, and `look.color.buffer.highlight` is `warn` — gold, not the `bad` red, which in this
     * list already means a network that's offline.
     *
     * [unread] is already the displayed count — a muted buffer's is its highlights alone.
     */
    fun unreadSignal(unread: Int, highlights: Int): UnreadSignal? {
        if (unread <= 0) return null
        return if (highlights > 0) UnreadSignal.Mentioned else UnreadSignal.Unread
    }

    /** The same, read aloud: appended to an accessibility label, or empty. */
    private fun unreadSummary(unread: Int, highlights: Int): String =
        when (unreadSignal(unread, highlights)) {
            null -> ""
            UnreadSignal.Mentioned -> ", $unread unread, mentioned"
            UnreadSignal.Unread -> ", $unread unread"
        }

    /**
     * One description for the whole row, so TalkBack reads "#general, libera, 3 unread" and
     * activates the row, rather than landing on the name, the hint and the count one at a time.
     * The network name is the full one (Friends and Favorites rows), never the short hint.
     */
    fun rowDescription(row: Row): String {
        val name = row.buffer.displayName()
        val summary = StringBuilder(row.networkName?.let { "$name, $it" } ?: name)
        if (row.parted) summary.append(", not joined")
        val presence = row.presence
        if (presence != null && presence.dimsName) summary.append(", ").append(presence.accessibilityLabel)
        if (row.hasDraft) summary.append(", draft")
        summary.append(unreadSummary(row.displayUnread, row.buffer.highlights))
        return summary.toString()
    }

    /** A header read aloud: only what's shown — the state, or else the log's count. */
    fun headerDescription(header: Header): String {
        val state = header.state
        if (state != null) return "${header.title}, $state"
        val log = header.log ?: return header.title
        return header.title + unreadSummary(log.displayUnread, log.buffer.highlights)
    }
}

// MARK: - Presence and network words

/*
 * How a peer's presence looks and reads, and a network's state in words — lurker-ios's
 * `PresenceStyle.swift` and `NetworkCopy.swift`, which are app-side there too (the kit has the
 * facts, the app has the copy). Here until a second screen reads them (the profile, the networks
 * screen), when they move somewhere shared — the step iOS took for the same reason.
 */

/**
 * Whether a DM's name steps down to the secondary colour: away or offline, the two the web mutes
 * (`BufferList.vue`'s `peer-away` and `peer-offline`). Online and unknown stay as they are —
 * unknown is the lack of a signal, not a signal.
 */
val FriendPresence.dimsName: Boolean get() = this == FriendPresence.Away || this == FriendPresence.Offline

/** Whether a DM's name is italic: offline only, the web's "offline tell". */
val FriendPresence.italicizesName: Boolean get() = this == FriendPresence.Offline

/** Lowercase, for appending to a longer accessibility summary ("alice, libera, online"). */
val FriendPresence.accessibilityLabel: String
    get() = when (this) {
        FriendPresence.Online -> "online"
        FriendPresence.Away -> "away"
        FriendPresence.Offline -> "offline"
        FriendPresence.Unknown -> "status unknown"
    }

/** A network's connection in words — the networks screen's (U4) and the server log's header. */
val ConnectionState.label: String
    get() = when (this) {
        ConnectionState.Connected -> "Connected"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Reconnecting -> "Reconnecting…"
        ConnectionState.Disconnected -> "Offline"
    }
