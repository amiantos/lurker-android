// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.list

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferListPlaceholder
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.FriendPresence
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer list's rules, pinned against `ChatState`s built the way the kit's own tests build
 * them. Every rule here is one lurker-ios's `BufferListViewController` carries a comment for — most
 * of them describe a bug that shipped.
 */
class BufferListModelTest {

    // MARK: - Builders

    private fun network(id: Int, name: String?, position: Int = id, state: ConnectionState = ConnectionState.Connected) =
        Network(id = id, name = name, position = position, state = state)

    private fun buffer(networkId: Int, target: String, unread: Int = 0, highlights: Int = 0, joined: Boolean = true) =
        Buffer(
            networkId = networkId,
            target = target,
            kind = BufferKind.of(networkId = networkId, target = target),
            unread = unread,
            highlights = highlights,
            joined = joined,
        )

    private fun server(networkId: Int, unread: Int = 0) = buffer(networkId, Buffer.serverTarget(networkId), unread = unread)

    private fun favorite(networkId: Int, target: String, bufferId: Int) =
        FavoriteEntry(networkId = networkId, target = target, bufferId = bufferId)

    private fun state(
        networks: List<Network> = listOf(network(1, "Libera")),
        buffers: List<Buffer> = emptyList(),
        favorites: List<FavoriteEntry> = emptyList(),
        pinned: Map<Int, List<String>> = emptyMap(),
        peerPresence: Map<Int, Map<String, PresenceState>> = emptyMap(),
        ignores: IgnoreSet = IgnoreSet.empty,
        drafts: Map<String, ComposerDraft> = emptyMap(),
        connection: SocketStatus = SocketStatus.Connected,
        reachable: Boolean = true,
        backlogComplete: Boolean = true,
    ) = ChatState(
        connection = connection,
        reachable = reachable,
        snapshotSinceOpen = true,
        networks = networks.associateBy { it.id },
        buffers = buffers.associateBy { it.key.id },
        backlogComplete = backlogComplete,
        favorites = favorites,
        peerPresence = peerPresence,
        pinned = pinned,
        ignores = ignores,
        drafts = drafts,
    )

    private fun sections(state: ChatState, optimistic: OptimisticFavorites? = null) =
        BufferListModel.buildSections(BufferListInputs.of(state), optimistic)

    private fun Section.names() = rows.map { it.buffer.target }

    private fun List<Section>.section(id: SectionId) = first { it.id == id }

    // MARK: - Favorites

    @Test
    fun aFavoriteIsRelocatedOutOfItsNetworkNotPrintedTwice() {
        val sections = sections(
            state(
                buffers = listOf(server(1), buffer(1, "#lurker"), buffer(1, "#swift"), buffer(1, "alice")),
                favorites = listOf(favorite(1, "alice", 10), favorite(1, "#lurker", 11)),
            ),
        )
        assertEquals(listOf("alice"), sections.section(SectionId.Friends).names())
        assertEquals(listOf("#lurker"), sections.section(SectionId.Favorites).names())
        assertEquals(listOf("#swift"), sections.section(SectionId.Network(1)).names())
        val everyRow = sections.flatMap { it.rows }.map { it.buffer.key.id }
        assertEquals(everyRow.size, everyRow.toSet().size)
    }

    @Test
    fun friendsComeBeforeFavoritesBeforeNetworks() {
        val sections = sections(
            state(
                buffers = listOf(buffer(1, "#lurker"), buffer(1, "alice"), buffer(1, "#swift")),
                // The channel first in the global order: the sections still read Friends first.
                favorites = listOf(favorite(1, "#lurker", 11), favorite(1, "alice", 10)),
            ),
        )
        assertEquals(listOf(SectionId.Friends, SectionId.Favorites, SectionId.Network(1)), sections.map { it.id })
        assertEquals("Friends", sections[0].header.title)
        assertEquals("Favorites", sections[1].header.title)
    }

    @Test
    fun aFavoritedChannelOfAnySigilIsAFavoriteNotAFriend() {
        val sections = sections(
            state(
                buffers = listOf(buffer(1, "&local"), buffer(1, "+modeless"), buffer(1, "!safe"), buffer(1, "#hash"), buffer(1, "bob")),
                favorites = listOf(
                    favorite(1, "&local", 1),
                    favorite(1, "+modeless", 2),
                    favorite(1, "!safe", 3),
                    favorite(1, "#hash", 4),
                    favorite(1, "bob", 5),
                ),
            ),
        )
        assertEquals(listOf("bob"), sections.section(SectionId.Friends).names())
        assertEquals(listOf("&local", "+modeless", "!safe", "#hash"), sections.section(SectionId.Favorites).names())
        assertTrue(sections.section(SectionId.Friends).rows.all { it.isFriend })
        assertTrue(sections.section(SectionId.Favorites).rows.none { it.isFriend })
    }

    @Test
    fun aFriendTheStoreHasNotMaterializedIsSynthesizedAndFlagged() {
        val sections = sections(state(favorites = listOf(favorite(1, "carol", 7))))
        val row = sections.section(SectionId.Friends).rows.single()
        assertEquals("carol", row.buffer.target)
        assertEquals(BufferKind.Dm, row.buffer.kind)
        assertTrue(row.isFriend)
        assertEquals("Libera", row.networkName)
    }

    @Test
    fun theDuplicateFavoriteCollisionYieldsUniqueKeys() {
        // Friends with alice and bob; bob renames to alice. For a frame the store holds two
        // favorites under one key, until `favorites-changed` dedupes them.
        val sections = sections(
            state(
                buffers = listOf(buffer(1, "alice")),
                favorites = listOf(favorite(1, "alice", 10), favorite(1, "alice", 11)),
            ),
        )
        val friends = sections.section(SectionId.Friends)
        assertEquals(1, friends.rows.size)
        assertEquals(TreeGuide.Elbow, friends.rows.single().guide)
        val keys = sections.flatMap { section -> section.entries.map { it.first.lazyKey } }
        assertEquals(keys.size, keys.toSet().size)
    }

    // MARK: - Networks

    @Test
    fun networksSitInServerOrderThenById() {
        val sections = sections(
            state(
                networks = listOf(network(1, "Zeta", position = 2), network(2, "Alpha", position = 0), network(3, "Mid", position = 2)),
                buffers = listOf(buffer(1, "#a"), buffer(2, "#b"), buffer(3, "#c")),
            ),
        )
        assertEquals(
            listOf(SectionId.Network(2), SectionId.Network(1), SectionId.Network(3)),
            sections.map { it.id },
        )
        assertEquals(listOf("Alpha", "Zeta", "Mid"), sections.map { it.header.title })
    }

    @Test
    fun theServerLogIsTheHeaderNeverARow() {
        val sections = sections(state(buffers = listOf(server(1, unread = 4), buffer(1, "#lurker"))))
        val libera = sections.section(SectionId.Network(1))
        assertEquals(BufferKind.Server, libera.header.log?.buffer?.kind)
        assertEquals(4, libera.header.log?.displayUnread)
        assertTrue(libera.rows.none { it.buffer.kind == BufferKind.Server })
        // The header item takes the log's key, so marking the log open marks the header.
        assertEquals(server(1).key.id, libera.headerItem.key)
    }

    @Test
    fun theLogIsSynthesizedWhenTheNetworkHasNone() {
        val libera = sections(state(buffers = listOf(buffer(1, "#lurker")))).section(SectionId.Network(1))
        assertEquals(Buffer.serverTarget(1), libera.header.log?.buffer?.target)
    }

    @Test
    fun aNetworkWhoseEveryBufferIsFavoritedStillGetsItsHeader() {
        val sections = sections(
            state(
                buffers = listOf(buffer(1, "#lurker"), buffer(1, "alice")),
                favorites = listOf(favorite(1, "#lurker", 1), favorite(1, "alice", 2)),
            ),
        )
        val libera = sections.section(SectionId.Network(1))
        assertTrue(libera.rows.isEmpty())
        assertEquals("Libera", libera.header.title)
        assertNotNull(libera.header.log)
    }

    @Test
    fun aNetworkWithNothingOpenHasNoSection() {
        val sections = sections(
            state(networks = listOf(network(1, "Libera"), network(2, "OFTC")), buffers = listOf(buffer(1, "#lurker"))),
        )
        assertEquals(listOf(SectionId.Network(1)), sections.map { it.id })
    }

    @Test
    fun unrosteredBuffersGetAnUnnamedGroupSortedById() {
        val sections = sections(
            state(
                networks = listOf(network(1, "Libera")),
                buffers = listOf(buffer(9, "#late"), buffer(1, "#lurker"), buffer(4, "#early")),
            ),
        )
        assertEquals(
            listOf(SectionId.Network(1), SectionId.Unrostered(4), SectionId.Unrostered(9)),
            sections.map { it.id },
        )
        val unrostered = sections.section(SectionId.Unrostered(4))
        assertEquals(Network.unnamedDisplayName, unrostered.header.title)
        assertEquals("Unnamed network", unrostered.header.title)
        // Its log is synthesized too, so the header still opens something — but with no roster
        // entry there is no state to light.
        assertEquals(Buffer.serverTarget(4), unrostered.header.log?.buffer?.target)
        assertNull(unrostered.header.light)
    }

    @Test
    fun aNamelessNetworkIsUnnamedNetwork() {
        val sections = sections(state(networks = listOf(network(1, null)), buffers = listOf(buffer(1, "#a"))))
        assertEquals("Unnamed network", sections.single().header.title)
    }

    // MARK: - Pins and guides

    @Test
    fun pinnedComeFirstWithABreakWhenRowsFollow() {
        val libera = sections(
            state(
                buffers = listOf(buffer(1, "#a"), buffer(1, "#b"), buffer(1, "#c"), buffer(1, "#d")),
                pinned = mapOf(1 to listOf("#d", "#b")),
            ),
        ).section(SectionId.Network(1))
        assertEquals(listOf("#d", "#b", "#a", "#c"), libera.names())
        assertEquals(2, libera.pinnedCount)
        assertTrue(libera.hasPinBreak)
        val kinds = libera.entries.map { it.second }
        assertTrue(kinds[0] is Entry.HeaderEntry)
        assertEquals(Entry.PinBreak, kinds[3])
        assertEquals(1, kinds.count { it == Entry.PinBreak })
    }

    @Test
    fun noBreakWhenEverythingIsPinned() {
        val libera = sections(
            state(buffers = listOf(buffer(1, "#a"), buffer(1, "#b")), pinned = mapOf(1 to listOf("#b", "#a"))),
        ).section(SectionId.Network(1))
        assertEquals(listOf("#b", "#a"), libera.names())
        assertFalse(libera.hasPinBreak)
        assertTrue(libera.entries.none { it.second == Entry.PinBreak })
    }

    @Test
    fun noBreakWithNoPins() {
        val libera = sections(state(buffers = listOf(buffer(1, "#a"), buffer(1, "#b")))).section(SectionId.Network(1))
        assertFalse(libera.hasPinBreak)
    }

    @Test
    fun theLastRowIsAnElbowAndTheRestAreTees() {
        val libera = sections(
            state(
                buffers = listOf(buffer(1, "#a"), buffer(1, "#b"), buffer(1, "#c")),
                pinned = mapOf(1 to listOf("#c")),
            ),
        ).section(SectionId.Network(1))
        // The last PINNED row keeps `├─` when rows follow the break: the spine runs on through it.
        assertEquals(listOf(TreeGuide.Tee, TreeGuide.Tee, TreeGuide.Elbow), libera.rows.map { it.guide })
        val single = sections(state(buffers = listOf(buffer(1, "#only")))).section(SectionId.Network(1))
        assertEquals(listOf(TreeGuide.Elbow), single.rows.map { it.guide })
    }

    // MARK: - Headers

    @Test
    fun everyHeaderButTheFirstDrawsTheRule() {
        val sections = sections(
            state(
                networks = listOf(network(1, "Libera"), network(2, "OFTC")),
                buffers = listOf(buffer(1, "#a"), buffer(2, "#b"), buffer(1, "alice")),
                favorites = listOf(favorite(1, "alice", 1)),
            ),
        )
        assertEquals(listOf(false, true, true), sections.map { it.header.ruleAbove })
    }

    @Test
    fun stateWordsOnlyWhenTheAppIsUpAndTheNetworkIsNot() {
        fun header(connection: SocketStatus, reachable: Boolean, network: ConnectionState) =
            sections(
                state(
                    networks = listOf(network(1, "Libera", state = network)),
                    buffers = listOf(buffer(1, "#a")),
                    connection = connection,
                    reachable = reachable,
                ),
            ).single().header

        val connected = header(SocketStatus.Connected, true, ConnectionState.Connected)
        assertNull(connected.state)
        assertEquals(StatusLight.Good, connected.light)

        val down = header(SocketStatus.Connected, true, ConnectionState.Disconnected)
        assertEquals("offline", down.state)
        assertEquals(StatusLight.Bad, down.light)

        assertEquals("connecting…", header(SocketStatus.Connected, true, ConnectionState.Connecting).state)
        assertEquals("reconnecting…", header(SocketStatus.Connected, true, ConnectionState.Reconnecting).state)

        // Lurker's own connection is the problem: the banner says so once, and the light is
        // layered outside-in — never a stale green.
        val appReconnecting = header(SocketStatus.Reconnecting, true, ConnectionState.Disconnected)
        assertNull(appReconnecting.state)
        assertEquals(StatusLight.Warn, appReconnecting.light)
        val noPath = header(SocketStatus.Connected, false, ConnectionState.Connected)
        assertNull(noPath.state)
        assertEquals(StatusLight.Bad, noPath.light)
        assertNull(header(SocketStatus.Incompatible(Incompatibility.AppTooOld), true, ConnectionState.Disconnected).state)
    }

    @Test
    fun friendsAndFavoritesHeadersHaveNoLightAndNoLog() {
        val sections = sections(
            state(buffers = listOf(buffer(1, "alice"), buffer(1, "#a")), favorites = listOf(favorite(1, "alice", 1), favorite(1, "#a", 2))),
        )
        for (id in listOf(SectionId.Friends, SectionId.Favorites)) {
            val header = sections.section(id).header
            assertNull(header.light)
            assertNull(header.log)
            assertEquals(ItemId.HEADER_KEY, sections.section(id).headerItem.key)
        }
    }

    // MARK: - Hints

    @Test
    fun networkHintsOnlyOnInGroupCollisions() {
        val sections = sections(
            state(
                networks = listOf(network(1, "Libera"), network(2, "OFTC"), network(3, "Rizon")),
                buffers = listOf(buffer(1, "alice"), buffer(2, "Alice"), buffer(3, "bob"), buffer(1, "#x"), buffer(3, "#x")),
                favorites = listOf(favorite(1, "alice", 1), favorite(2, "Alice", 2), favorite(3, "bob", 3), favorite(1, "#x", 4)),
            ),
        )
        val friends = sections.section(SectionId.Friends).rows
        assertEquals(listOf("l", "o", null), friends.map { it.networkHint })
        // #x on Rizon is in its network's group, not under Favorites: no collision within a group.
        assertNull(sections.section(SectionId.Favorites).rows.single().networkHint)
        assertTrue(sections.filter { !it.id.reorderable }.flatMap { it.rows }.all { it.networkHint == null })
    }

    @Test
    fun noHintsWithOneNetwork() {
        val rows = BufferListModel.addNetworkHints(
            mapOf(1 to "l"),
            listOf(Row(buffer(1, "alice"), networkName = "Libera"), Row(buffer(1, "alice"), networkName = "Libera")),
        )
        assertTrue(rows.all { it.networkHint == null })
    }

    // MARK: - Rows

    @Test
    fun aMutedRowCountsHighlightsOnly() {
        val ignores = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 1, channels = listOf("#busy"), levels = listOf("NOUNREAD")))))
        val libera = sections(
            state(buffers = listOf(buffer(1, "#busy", unread = 40, highlights = 2), buffer(1, "#calm", unread = 5)), ignores = ignores),
        ).section(SectionId.Network(1))
        val busy = libera.rows.first { it.buffer.target == "#busy" }
        val calm = libera.rows.first { it.buffer.target == "#calm" }
        assertTrue(busy.muted)
        assertEquals(2, busy.displayUnread)
        assertFalse(calm.muted)
        assertEquals(5, calm.displayUnread)
    }

    @Test
    fun partedPresenceAndDraftsRideTheRow() {
        val libera = sections(
            state(
                buffers = listOf(buffer(1, "#left", joined = false), buffer(1, "bob"), buffer(1, "#here")),
                peerPresence = mapOf(1 to mapOf("bob" to PresenceState.Away)),
                drafts = mapOf(BufferKey(1, "#here").id to ComposerDraft(body = "hi")),
            ),
        ).section(SectionId.Network(1))
        val left = libera.rows.first { it.buffer.target == "#left" }
        val bob = libera.rows.first { it.buffer.target == "bob" }
        val here = libera.rows.first { it.buffer.target == "#here" }
        assertTrue(left.parted)
        assertNull(left.presence)
        assertEquals(FriendPresence.Away, bob.presence)
        assertTrue(here.hasDraft)
        assertFalse(left.hasDraft)
    }

    @Test
    fun aSynthesizedFavoriteChannelIsNotParted() {
        // Its synthesized `joined` is the initializer's false — a default, not a statement.
        val row = sections(state(favorites = listOf(favorite(1, "#gone", 1)))).section(SectionId.Favorites).rows.single()
        assertFalse(row.parted)
    }

    @Test
    fun rowDescriptionsReadLikeIos() {
        val row = Row(
            buffer = buffer(1, "#general", unread = 3, highlights = 1),
            networkName = "Libera",
            presence = null,
            parted = true,
            hasDraft = true,
        )
        assertEquals("#general, Libera, not joined, draft, 3 unread, mentioned", BufferListModel.rowDescription(row))
        val dm = Row(buffer(1, "bob", unread = 2), networkName = null, presence = FriendPresence.Offline)
        assertEquals("bob, offline, 2 unread", BufferListModel.rowDescription(dm))
        val online = Row(buffer(1, "bob"), networkName = null, presence = FriendPresence.Online)
        assertEquals("bob", BufferListModel.rowDescription(online))
    }

    @Test
    fun headerDescriptionsReadOnlyWhatsShown() {
        val log = Row(server(1, unread = 2), networkName = null)
        assertEquals("Libera, 2 unread", BufferListModel.headerDescription(Header("Libera", log = log)))
        assertEquals("Libera, offline", BufferListModel.headerDescription(Header("Libera", state = "offline", log = log)))
        assertEquals("Friends", BufferListModel.headerDescription(Header("Friends")))
    }

    @Test
    fun unreadSignalIsGoldForAMentionNeverRed() {
        assertNull(BufferListModel.unreadSignal(0, 0))
        assertNull(BufferListModel.unreadSignal(0, 3))
        assertEquals(UnreadSignal.Unread, BufferListModel.unreadSignal(4, 0))
        assertEquals(UnreadSignal.Mentioned, BufferListModel.unreadSignal(4, 1))
    }

    // MARK: - Optimistic order and dragging

    @Test
    fun theOptimisticOrderAppliesUntilAnyFavoritesChange() {
        val favorites = listOf(favorite(1, "#a", 1), favorite(1, "#b", 2), favorite(1, "#c", 3))
        val optimistic = OptimisticFavorites(order = listOf(3, 1, 2), favoritesAtDrop = favorites)
        assertEquals(listOf(3, 1, 2), BufferListModel.orderedFavorites(favorites, optimistic).map { it.bufferId })
        val drawn = sections(state(favorites = favorites), optimistic)
        assertEquals(listOf("#c", "#a", "#b"), drawn.section(SectionId.Favorites).names())

        // The echo — or anyone's edit — is authoritative, even one that lands on another order.
        val echoed = listOf(favorite(1, "#a", 1), favorite(1, "#c", 3), favorite(1, "#b", 2))
        assertFalse(optimistic.isCurrent(echoed))
        assertEquals(echoed, BufferListModel.orderedFavorites(echoed, optimistic))
        assertEquals(listOf("#a", "#c", "#b"), sections(state(favorites = echoed), optimistic).section(SectionId.Favorites).names())
    }

    @Test
    fun anOptimisticOrderMissingAnEntryKeepsItAtTheEnd() {
        val favorites = listOf(favorite(1, "#a", 1), favorite(1, "#b", 2), favorite(1, "bob", 3))
        val optimistic = OptimisticFavorites(order = listOf(2, 1), favoritesAtDrop = favorites)
        assertEquals(listOf(2, 1, 3), BufferListModel.orderedFavorites(favorites, optimistic).map { it.bufferId })
    }

    @Test
    fun aDropSendsTheFullGlobalOrderMappedThroughTheGroup() {
        // Global order interleaves people and channels; Favorites shows the channel slice.
        val favorites = listOf(favorite(1, "#a", 1), favorite(1, "bob", 2), favorite(1, "#b", 3), favorite(1, "#c", 4))
        val built = sections(state(favorites = favorites))
        val session = DragSession.begin(built, SectionId.Favorites, BufferKey(1, "#c").id, favorites)!!
        val moved = session.moved(BufferKey(1, "#c").id, BufferKey(1, "#a").id)
        assertEquals(listOf("#c", "#a", "#b"), moved.rendered().section(SectionId.Favorites).names())
        // bob keeps his slot; the channels are dealt back into theirs.
        assertEquals(listOf(4, 2, 1, 3), moved.dropOrder(favorites, optimistic = null))
    }

    @Test
    fun aDropIsRefusedIfTheFavoritesMovedWhileTheRowWasInTheAir() {
        val favorites = listOf(favorite(1, "#a", 1), favorite(1, "#b", 2))
        val session = DragSession.begin(sections(state(favorites = favorites)), SectionId.Favorites, BufferKey(1, "#b").id, favorites)!!
            .moved(BufferKey(1, "#b").id, BufferKey(1, "#a").id)
        val elsewhere = favorites + favorite(1, "#new", 3)
        assertNull(session.dropOrder(elsewhere, optimistic = null))
    }

    @Test
    fun aDropThatChangesNothingSendsNothing() {
        val favorites = listOf(favorite(1, "#a", 1), favorite(1, "#b", 2))
        val session = DragSession.begin(sections(state(favorites = favorites)), SectionId.Favorites, BufferKey(1, "#a").id, favorites)!!
        assertNull(session.dropOrder(favorites, optimistic = null))
    }

    @Test
    fun aSecondDropBeforeTheEchoDiffsAgainstTheScreenNotTheStore() {
        // First drop moved #b above #a; its echo hasn't landed. The second drag puts them back.
        val favorites = listOf(favorite(1, "#a", 1), favorite(1, "#b", 2))
        val optimistic = OptimisticFavorites(order = listOf(2, 1), favoritesAtDrop = favorites)
        val onScreen = sections(state(favorites = favorites), optimistic)
        val session = DragSession.begin(onScreen, SectionId.Favorites, BufferKey(1, "#b").id, favorites)!!
            .moved(BufferKey(1, "#b").id, BufferKey(1, "#a").id)
        assertEquals(listOf(1, 2), session.dropOrder(favorites, optimistic))
    }

    @Test
    fun aDropDuringTheRenameCollisionSendsNothing() {
        // bob renamed to alice: two favorites under one key until favorites-changed dedupes them.
        val favorites = listOf(favorite(1, "alice", 10), favorite(1, "alice", 11), favorite(1, "carol", 12))
        val order = BufferListModel.droppedOrder(
            favorites,
            optimistic = null,
            visible = listOf(BufferKey(1, "alice").id, BufferKey(1, "carol").id),
            from = 1,
            to = 0,
        )
        // Refused rather than mapped: a key→id map would send 10 twice and drop 11.
        assertNull(order)
    }

    @Test
    fun aDragStaysInItsOwnGroup() {
        val favorites = listOf(favorite(1, "#a", 1), favorite(1, "#b", 2), favorite(1, "bob", 3))
        val built = sections(state(favorites = favorites))
        val session = DragSession.begin(built, SectionId.Favorites, BufferKey(1, "#a").id, favorites)!!
        // A Friends key is no move at all.
        assertEquals(session, session.moved(BufferKey(1, "#a").id, BufferKey(1, "bob").id))
        // Network rows can't be lifted.
        assertNull(DragSession.begin(built, SectionId.Network(1), BufferKey(1, "#a").id, favorites))
        // While dragging, everything but the dragged group is the frozen list.
        val moved = session.moved(BufferKey(1, "#a").id, BufferKey(1, "#b").id)
        assertEquals(built.section(SectionId.Friends), moved.rendered().section(SectionId.Friends))
        assertEquals(listOf(TreeGuide.Tee, TreeGuide.Elbow), moved.rendered().section(SectionId.Favorites).rows.map { it.guide })
        assertEquals(listOf("#b", "#a"), moved.rendered().section(SectionId.Favorites).names())
    }

    // MARK: - Menu and swipe

    @Test
    fun theMenuHasIosFourTitles() {
        val s = state(
            buffers = listOf(buffer(1, "#a"), buffer(1, "#b"), buffer(1, "bob"), buffer(1, "carol")),
            favorites = listOf(favorite(1, "#a", 1), favorite(1, "bob", 2)),
        )
        fun menu(target: String) = BufferListModel.rowMenu(s, s.buffer(BufferKey(1, target)))!!
        assertEquals("Remove from Favorites", menu("#a").favoriteTitle)
        assertEquals("Add to Favorites", menu("#b").favoriteTitle)
        assertEquals("Remove from Friends", menu("bob").favoriteTitle)
        assertEquals("Add to Friends", menu("carol").favoriteTitle)
        assertTrue(menu("bob").favoriteDestructive)
        assertFalse(menu("#a").favoriteDestructive)
        assertEquals("Leave", menu("#a").closeTitle)
        assertEquals("Close", menu("bob").closeTitle)
        assertNull(menu("#a").joinEnabled)
    }

    @Test
    fun aDccChatHasNoFavoriteToggle() {
        val s = state(buffers = listOf(buffer(1, "=bob")))
        val menu = BufferListModel.rowMenu(s, s.buffer(BufferKey(1, "=bob")))!!
        assertNull(menu.favoriteTitle)
        assertEquals("Close", menu.closeTitle)
    }

    @Test
    fun aPartedChannelLeadsWithJoinEnabledOnlyWhileItsNetworkIsUp() {
        val up = state(buffers = listOf(buffer(1, "#left", joined = false)))
        val upMenu = BufferListModel.rowMenu(up, up.buffer(BufferKey(1, "#left")))!!
        assertEquals(true, upMenu.joinEnabled)
        assertEquals("Close", upMenu.closeTitle)
        val down = state(
            networks = listOf(network(1, "Libera", state = ConnectionState.Disconnected)),
            buffers = listOf(buffer(1, "#left", joined = false)),
        )
        assertEquals(false, BufferListModel.rowMenu(down, down.buffer(BufferKey(1, "#left")))!!.joinEnabled)
        // The network's last-known state says connected, but Lurker's own socket is down: a JOIN
        // now goes nowhere.
        val stale = state(buffers = listOf(buffer(1, "#left", joined = false)), connection = SocketStatus.Reconnecting)
        assertEquals(false, BufferListModel.rowMenu(stale, stale.buffer(BufferKey(1, "#left")))!!.joinEnabled)
        val offline = state(buffers = listOf(buffer(1, "#left", joined = false)), reachable = false)
        assertEquals(false, BufferListModel.rowMenu(offline, offline.buffer(BufferKey(1, "#left")))!!.joinEnabled)
    }

    @Test
    fun theSystemBufferAndServerLogsHaveNoMenu() {
        val s = state(buffers = listOf(server(1)))
        assertNull(BufferListModel.rowMenu(s, Buffer.system))
        assertNull(BufferListModel.rowMenu(s, server(1)))
    }

    @Test
    fun onlyNetworkRowsSwipe() {
        val channel = Row(buffer(1, "#a"), networkName = null)
        assertEquals("Leave", BufferListModel.swipeTitle(SectionId.Network(1), channel))
        assertEquals("Close", BufferListModel.swipeTitle(SectionId.Network(1), channel.copy(parted = true)))
        assertEquals("Close", BufferListModel.swipeTitle(SectionId.Unrostered(1), Row(buffer(1, "bob"), networkName = null)))
        assertNull(BufferListModel.swipeTitle(SectionId.Favorites, channel))
        assertNull(BufferListModel.swipeTitle(SectionId.Friends, Row(buffer(1, "bob"), networkName = "Libera")))
        assertNull(BufferListModel.swipeTitle(SectionId.Network(1), Row(server(1), networkName = null)))
    }

    // MARK: - Gate, placeholder, title

    @Test
    fun theBurstGateWaitsForTheRosterOnlyOnce() {
        assertFalse(BufferListModel.drawsList(rosterSettled = false, hasRenderedList = false))
        assertTrue(BufferListModel.drawsList(rosterSettled = true, hasRenderedList = false))
        // A resync re-opens the burst over a populated screen; the list stays.
        assertTrue(BufferListModel.drawsList(rosterSettled = false, hasRenderedList = true))
    }

    @Test
    fun thePlaceholderTellsLoadingFromEmpty() {
        val loading = BufferListInputs.of(state(networks = emptyList(), backlogComplete = false))
        assertEquals(BufferListPlaceholder.Loading, BufferListModel.placeholder(loading, emptyList(), draws = true))
        val noNetworks = BufferListInputs.of(state(networks = emptyList()))
        assertEquals(BufferListPlaceholder.NoNetworks, BufferListModel.placeholder(noNetworks, emptyList(), draws = true))
        val noBuffers = BufferListInputs.of(state())
        assertEquals(BufferListPlaceholder.NoBuffers, BufferListModel.placeholder(noBuffers, emptyList(), draws = true))
        val some = BufferListInputs.of(state(buffers = listOf(buffer(1, "#a"))))
        assertEquals(BufferListPlaceholder.None, BufferListModel.placeholder(some, BufferListModel.buildSections(some), draws = true))
    }

    @Test
    fun aShutGateIsLoadingEvenWithTheBacklogLatched() {
        // A list first composed mid-resync: `backlogComplete` is still true from the first burst,
        // nothing is drawn, and the account is full — "No buffers yet" would be a lie.
        val resyncing = BufferListInputs.of(state(buffers = listOf(buffer(1, "#a"))))
        assertEquals(BufferListPlaceholder.Loading, BufferListModel.placeholder(resyncing, emptyList(), draws = false))
    }

    @Test
    fun theTitleIsLurkerFollowingTheSocket() {
        val title = BufferListModel.statusTitle(BufferListInputs.of(state()))
        assertEquals("Lurker", title.title)
        assertEquals("Connected", title.subtitle)
        assertEquals("Connecting…", BufferListModel.statusTitle(BufferListInputs.of(state(connection = SocketStatus.Reconnecting))).subtitle)
        assertEquals("Disconnected", BufferListModel.statusTitle(BufferListInputs.of(state(reachable = false))).subtitle)
    }

    // MARK: - Inputs

    @Test
    fun inputsIgnoreWhatTheListDoesNotDraw() {
        val base = state(buffers = listOf(buffer(1, "#a")), drafts = mapOf(BufferKey(1, "#a").id to ComposerDraft(body = "h")))
        val a = BufferListInputs.of(base)
        // A draft's text changing while you type, or a message landing, moves nothing drawn.
        assertTrue(BufferListInputs.same(a, BufferListInputs.of(base.copy(drafts = mapOf(BufferKey(1, "#a").id to ComposerDraft(body = "hi"))))))
        assertTrue(BufferListInputs.same(a, BufferListInputs.of(base.copy(maxEventId = 99))))
    }

    @Test
    fun inputsSeeEveryFrameThatMovesOnlyOneListField() {
        val base = state(buffers = listOf(buffer(1, "#a")))
        val a = BufferListInputs.of(base)
        val changed = listOf(
            base.copy(pinned = mapOf(1 to listOf("#a"))),
            base.copy(backlogComplete = false),
            base.copy(snapshotSinceOpen = false),
            base.copy(favorites = listOf(favorite(1, "#a", 1))),
            base.copy(peerPresence = mapOf(1 to mapOf("bob" to PresenceState.Away))),
            base.copy(drafts = mapOf(BufferKey(1, "#a").id to ComposerDraft(body = "x"))),
            base.copy(reachable = false),
            base.copy(connection = SocketStatus.Reconnecting),
            base.copy(networks = mapOf(1 to network(1, "Libera", state = ConnectionState.Disconnected))),
            base.copy(buffers = mapOf(BufferKey(1, "#a").id to buffer(1, "#a", unread = 1))),
            // Equal rules, a different set: compared by identity, so it redraws.
            base.copy(ignores = IgnoreSet()),
        )
        for ((index, next) in changed.withIndex()) {
            assertFalse("change #$index", BufferListInputs.same(a, BufferListInputs.of(next)))
        }
    }

    // MARK: - Keys

    @Test
    fun lazyKeysSplitBackApartWhateverTheTargetHolds() {
        val id = ItemId(SectionId.Network(3), BufferKey(3, "#foo|bar").id)
        assertEquals("net:3", ItemId.sectionTokenOf(id.lazyKey))
        assertEquals(BufferKey(3, "#foo|bar").id, ItemId.rowKeyOf(id.lazyKey))
    }

    @Test
    fun everyKeyInAPopulatedListIsUnique() {
        val sections = sections(
            state(
                networks = listOf(network(1, "Libera"), network(2, "OFTC")),
                buffers = listOf(server(1), server(2), buffer(1, "#a"), buffer(1, "#b"), buffer(2, "#a"), buffer(1, "alice"), buffer(2, "alice")),
                favorites = listOf(favorite(1, "alice", 1), favorite(2, "#a", 2)),
                pinned = mapOf(1 to listOf("#b"), 2 to listOf("#a")),
            ),
        )
        val keys = sections.flatMap { section -> section.entries.map { it.first.lazyKey } }
        assertEquals(keys.size, keys.toSet().size)
    }
}
