// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferOrder
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.LurkerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The buffer list's order, where the order is the user's own: their network arrangement and
 * their pins, both made on the web and only rendered here.
 */
class BufferOrderTests {

    private fun network(id: Int, name: String, position: Int): Network =
        Network(id = id, name = name, position = position)

    private fun buffer(target: String, kind: BufferKind = BufferKind.Channel): Buffer =
        Buffer(networkId = 1, target = target, kind = kind)

    // MARK: - Networks

    @Test
    fun testNetworksFollowTheUsersOrderNotTheAlphabet() {
        val networks = mapOf(
            2 to network(2, "Aardvark", position = 1),
            1 to network(1, "Zulu", position = 0),
        )
        assertEquals(listOf(1, 2), BufferOrder.networks(networks).map { it.id })
    }

    @Test
    fun testTiesBreakOnIdTheWayTheServerBreaksThem() {
        // `position` is only densified when something is reordered, so ties are normal — and
        // the server's own `ORDER BY position ASC, id ASC` is what the two clients have to
        // agree on. Name would be the wrong tiebreak: two networks would swap on a rename.
        val networks = mapOf(
            9 to network(9, "Aardvark", position = 0),
            3 to network(3, "Zulu", position = 0),
        )
        assertEquals(listOf(3, 9), BufferOrder.networks(networks).map { it.id })
    }

    @Test
    fun testANetworkWeHaveNoRosterRowForSortsLast() {
        // Defaulted position, from a network the snapshot materialized before the roster
        // landed (lurker-ios#136). Last is the less startling of the two ways to be wrong for
        // the moment before the re-fetch names it — it doesn't shove itself to the top.
        val networks = mapOf(
            1 to network(1, "Libera", position = 5),
            2 to Network(id = 2, name = null),
        )
        assertEquals(listOf(1, 2), BufferOrder.networks(networks).map { it.id })
    }

    // MARK: - Pins

    @Test
    fun testPinnedBuffersComeBackInTheUsersPinOrder() {
        val buffers = listOf(buffer("#aardvark"), buffer("#zulu"), buffer("#middle"))
        val split = BufferOrder.split(buffers, pinned = listOf("#zulu", "#middle"))
        assertEquals(listOf("#zulu", "#middle"), split.pinned.map { it.target })
        assertEquals(listOf("#aardvark"), split.rest.map { it.target })
    }

    @Test
    fun testTheRestKeepsTheOrdinaryOrder() {
        val buffers = listOf(buffer("bob", kind = BufferKind.Dm), buffer("#zulu"), buffer("#aardvark"))
        val split = BufferOrder.split(buffers, pinned = listOf("#zulu"))
        // Channels before DMs, alphabetical within — unchanged, just without the pinned one.
        assertEquals(listOf("#aardvark", "bob"), split.rest.map { it.target })
    }

    @Test
    fun testAPinnedOnlyNetworkHasNothingLeftOver() {
        // The section the caller drops — and the case that would lose the network's
        // connection state if only the unpinned header carried it.
        val split = BufferOrder.split(listOf(buffer("#a"), buffer("#b")), pinned = listOf("#a", "#b"))
        assertEquals(2, split.pinned.size)
        assertTrue(split.rest.isEmpty())
    }

    @Test
    fun testAPinWithNoOpenBufferContributesNothing() {
        // ⚠ A pin row outlives its buffer being parted or closed, so the pin list is a
        // superset of what can be shown. Mapping it blindly would render rows for buffers
        // that aren't there.
        val split = BufferOrder.split(listOf(buffer("#here")), pinned = listOf("#gone", "#here"))
        assertEquals(listOf("#here"), split.pinned.map { it.target })
        assertTrue(split.rest.isEmpty())
    }

    @Test
    fun testASigilIsPartOfTheTargetNotNoiseToFoldAway() {
        // ⚠⚠ Keyed on `target.lowercase()` — `BufferKey.id`'s rule — and NOT on
        // `ChannelName.fold`, which is the autocomplete fold and drops a leading sigil. Under
        // that key `#ops` and `&ops` collide: the pin could render the wrong one, and the
        // filter for "everything else", matching the same collided key, would drop the loser
        // out of the list altogether.
        val split = BufferOrder.split(listOf(buffer("#ops"), buffer("&ops")), pinned = listOf("&ops"))
        assertEquals(listOf("&ops"), split.pinned.map { it.target })
        assertEquals(listOf("#ops"), split.rest.map { it.target })
    }

    @Test
    fun testPinsMatchTargetsCaseInsensitively() {
        // The pin is stored under the spelling the server last saw and the buffer under the
        // one this client holds; IRC lets those differ. An exact match would silently drop a
        // pin after a CASEMAPPING refold.
        val split = BufferOrder.split(listOf(buffer("#Zulu"), buffer("#aardvark")), pinned = listOf("#ZULU"))
        assertEquals(listOf("#Zulu"), split.pinned.map { it.target })
    }

    @Test
    fun testADuplicatedPinDoesNotPrintTheBufferTwice() {
        val split = BufferOrder.split(listOf(buffer("#a"), buffer("#b")), pinned = listOf("#a", "#a"))
        assertEquals(listOf("#a"), split.pinned.map { it.target })
        assertEquals(listOf("#b"), split.rest.map { it.target })
    }

    @Test
    fun testNoPinsIsJustTheOrdinaryOrder() {
        val buffers = listOf(buffer("#zulu"), buffer("bob", kind = BufferKind.Dm), buffer("#aardvark"))
        val split = BufferOrder.split(buffers, pinned = emptyList())
        assertTrue(split.pinned.isEmpty())
        assertEquals(listOf("#aardvark", "#zulu", "bob"), split.rest.map { it.target })
    }

    // MARK: - What a network section lists

    @Test
    fun testAFavoritedBufferIsNotAlsoListedUnderItsNetwork() {
        // ⚠⚠ A favorite is a relocation, not a shortcut. This used to be true of favorited
        // DMs alone, so a favorited *channel* appeared twice — and since the buffers people
        // favorite are the ones they look at most, the list they scan most was the one with
        // every row they cared about duplicated in it.
        val favorite = buffer("#kept")
        val ordinary = buffer("#other")
        val grouped = BufferOrder.byNetwork(
            listOf(favorite, ordinary), excluding = setOf(favorite.key.id)
        )
        assertEquals(listOf("#other"), grouped[1]?.map { it.target })
    }

    @Test
    fun testAFavoriteMatchesRegardlessOfItsSpelling() {
        // The exclusion set is keyed by `BufferKey.id`, which lowercases the target — so a
        // favorite recorded as "#Kept" still hides the buffer held as "#kept".
        val held = Buffer(networkId = 1, target = "#Kept", kind = BufferKind.Channel)
        val grouped = BufferOrder.byNetwork(
            listOf(held), excluding = setOf(BufferKey(networkId = 1, target = "#kept").id)
        )
        assertNull(grouped[1])
    }

    @Test
    fun testTheSystemBufferHasNoNetworkSection() {
        // It has no network and no row of its own — it's opened from the buffer list's menu.
        val grouped = BufferOrder.byNetwork(listOf(Buffer.system, buffer("#chan")), excluding = emptySet())
        assertEquals(1, grouped.size)
        assertEquals(listOf("#chan"), grouped[1]?.map { it.target })
    }

    // MARK: - The server log

    @Test
    fun testANetworkAlwaysHasItsServerLog() {
        // ⚠⚠ The row used to appear only if the server had sent one, and `pruneToBurst` drops
        // it whenever the connect burst doesn't name it — so a network's log was present on
        // one launch and gone on the next, with nothing the user did to explain it.
        val rows = BufferOrder.withServerLog(listOf(buffer("#chan")), networkId = 1)
        assertEquals(listOf("#chan", ":server:1"), rows.map { it.target })
        assertEquals(BufferKind.Server, rows.lastOrNull()?.kind)
    }

    @Test
    fun testAnExistingServerLogIsNotDuplicated() {
        // The store's own row wins — it carries the read state and unread count a synthesized
        // one wouldn't have.
        val real = Buffer(networkId = 1, target = ":server:1", kind = BufferKind.Server, unread = 7)
        val rows = BufferOrder.withServerLog(listOf(real, buffer("#chan")), networkId = 1)
        assertEquals(1, rows.filter { it.kind == BufferKind.Server }.size)
        assertEquals(7, rows.firstOrNull { it.kind == BufferKind.Server }?.unread)
    }

    @Test
    fun testANetworkWhoseOnlyBufferIsFavoritedKeepsItsSection() {
        // ⚠⚠ Favorites are lifted out of their network's section, so this network contributes
        // no rows — and with its `:server:` row not yet in the store it would vanish from the
        // list entirely: no header, no connection state, no way into its log. "In use" is
        // asked before the favorites exclusion for exactly this.
        val rows = BufferOrder.withServerLog(emptyList(), networkId = 4, networkHasOpenBuffers = true)
        assertEquals(listOf(":server:4"), rows.map { it.target })
    }

    @Test
    fun testANetworkWithNoRowsAtAllGetsNoRowInvented() {
        // ⚠⚠ This fills a gap in a section, it does not conjure one. Synthesizing here would
        // give every network a section forever, which kills two of the buffer list's states:
        // "Loading buffers…" never shows (the roster is fetched before the socket opens, so a
        // section would always exist during the connect window), and "No buffers yet" becomes
        // unreachable (having a network would imply having a row). Making every network
        // appear is a good idea and a separate one.
        assertTrue(BufferOrder.withServerLog(emptyList(), networkId = 4).isEmpty())
    }

    @Test
    fun testTheServerLogSortsLastWithinItsNetwork() {
        val rows = BufferOrder.withServerLog(
            listOf(buffer("bob", kind = BufferKind.Dm), buffer("#chan")), networkId = 1
        )
        assertEquals(
            listOf("#chan", "bob", ":server:1"),
            BufferOrder.split(rows, pinned = emptyList()).rest.map { it.target },
        )
    }

    @Test
    fun testTheSynthesizedTargetMatchesTheServers() {
        // `:server:<id>`, the same string the server and the web use — a client-built target
        // that didn't match would open a second, empty buffer beside the real one.
        assertEquals(":server:2", Buffer.serverTarget(2))
        assertEquals(BufferKind.Server, BufferKind.of(networkId = 2, target = Buffer.serverTarget(2)))
    }

    // MARK: - The ordinary order (moved here from the view controller)

    @Test
    fun testChannelsThenDmsThenTheServerLog() {
        val buffers = listOf(
            buffer(":server:", kind = BufferKind.Server),
            buffer("bob", kind = BufferKind.Dm),
            buffer("#chan"),
        )
        assertEquals(
            listOf("#chan", "bob", ":server:"),
            BufferOrder.split(buffers, pinned = emptyList()).rest.map { it.target },
        )
    }

    @Test
    fun testTheAlphabeticalKeyStripsEverySigil() {
        // All four, via `ChannelName.stripSigils`: a hand-written `#&` floated `+`/`!`
        // channels above every named one until lurker-ios#98, which the web never did.
        val buffers = listOf(buffer("#zebra"), buffer("!aardvark"), buffer("+middle"), buffer("&bear"))
        assertEquals(
            listOf("!aardvark", "&bear", "+middle", "#zebra"),
            BufferOrder.split(buffers, pinned = emptyList()).rest.map { it.target },
        )
    }

    // MARK: - Store

    @Test
    fun testTheSnapshotSeedsPinsAndReplacesThemWholesale() {
        val store = LurkerStore()
        store.apply(
            FrameParser.parseWs(
                """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[],"pinned":["#a","#b"]}]}""",
            ),
        )
        assertEquals(listOf("#a", "#b"), store.state.pinned[2])
        // A pin dropped from the web while this device was away has to disappear here rather
        // than survive as a leftover.
        store.apply(
            FrameParser.parseWs(
                """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[],"pinned":["#b"]}]}""",
            ),
        )
        assertEquals(listOf("#b"), store.state.pinned[2])
    }

    @Test
    fun testPinsChangedReplacesOneNetworksList() {
        val store = LurkerStore()
        store.apply(ServerFrame.PinsChanged(networkId = 1, pinned = listOf("#a")))
        store.apply(ServerFrame.PinsChanged(networkId = 2, pinned = listOf("#b")))
        store.apply(ServerFrame.PinsChanged(networkId = 1, pinned = emptyList()))
        assertEquals(emptyList(), store.state.pinned[1])
        // Sent per network, so it says nothing about the others.
        assertEquals(listOf("#b"), store.state.pinned[2])
    }

    @Test
    fun testPinsChangedParsesItsOrderedList() {
        val frame = FrameParser.parseWs(
            """{"kind":"pins-changed","networkId":4,"pinned":["#b","#a"],"pinnedIds":[7,3]}""",
        )
        if (frame !is ServerFrame.PinsChanged) fail("expected pinsChanged, got $frame")
        val (networkId, pinned) = frame
        assertEquals(4, networkId)
        assertEquals(listOf("#b", "#a"), pinned)
    }

    @Test
    fun testDeletingANetworkTakesItsPinsWithIt() {
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera", position = 0))))
        store.apply(ServerFrame.PinsChanged(networkId = 1, pinned = listOf("#a")))
        store.apply(ServerFrame.Networks(emptyList()))
        assertNull(store.state.pinned[1])
    }

    @Test
    fun testTheRosterMergeCarriesThePositionOntoAnExistingNetwork() {
        // ⚠ `position` is REST-only data exactly like `name`, so the merge has to take both.
        // Taking only the name left every network the snapshot materialized stuck at the
        // default — sorting by id for the life of the process, on any launch where the roster
        // read lost its race with the socket.
        val store = LurkerStore()
        store.apply(
            FrameParser.parseWs(
                """{"kind":"snapshot","networks":[{"networkId":7,"state":"connected","nick":"me","channels":[]}]}""",
            ),
        )
        assertEquals(Int.MAX_VALUE, store.state.networks[7]?.position)
        store.apply(ServerFrame.Networks(listOf(Network(id = 7, name = "Libera", position = 2))))
        assertEquals(2, store.state.networks[7]?.position)
        assertEquals("Libera", store.state.networks[7]?.name)
        // …and the live state the snapshot set is still there.
        assertEquals(ConnectionState.Connected, store.state.networks[7]?.state)
    }

    @Test
    fun testTheRosterCarriesThePosition() {
        val frame = FrameParser.parseNetworks("""{"networks":[{"id":1,"name":"Libera","position":3}]}""")
        if (frame !is ServerFrame.Networks) fail("expected networks, got $frame")
        assertEquals(3, frame.networks.firstOrNull()?.position)
    }

    @Test
    fun testAnOlderServerWithNoPositionSortsLast() {
        val frame = FrameParser.parseNetworks("""{"networks":[{"id":1,"name":"Libera"}]}""")
        if (frame !is ServerFrame.Networks) fail("expected networks, got $frame")
        assertEquals(Int.MAX_VALUE, frame.networks.firstOrNull()?.position)
    }
}
