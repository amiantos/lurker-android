// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.ChannelSnapshot
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.AvatarInk
import net.amiantos.lurkerkit.model.AvatarSet
import net.amiantos.lurkerkit.model.AvatarSetManifest
import net.amiantos.lurkerkit.model.AvatarSets
import net.amiantos.lurkerkit.model.CompactDensity
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.MetadataLimits
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NickCompletion
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.SpeakerMap
import net.amiantos.lurkerkit.model.UploadedAvatarSet
import net.amiantos.lurkerkit.model.UserMetadata
import net.amiantos.lurkerkit.rendering.AvatarTiles
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * IRCv3 metadata display names and avatars (lurker#607, Mobile lurker#1203): the frames, the
 * store, the tile hash and the set pointer.
 */
class MetadataAndAvatarsTests {

    // MARK: - Parser

    @Test
    fun testSnapshotSeedsUserMetadataAndLimits() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[],"userMetadata":{"alice":{"displayName":"\u000304Alice\u0003","avatarUrl":"/api/link-preview/avatar/abc","pronouns":"she/her","homepage":"https://a.example","status":"busy"},"bob":{"avatarUrl":null}},"metadataLimits":{"maxSubs":10,"maxKeys":20,"maxValueBytes":300,"beforeConnect":true}}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        val net = frame.networks[0]
        assertEquals(2, net.userMetadata.size)
        assertEquals("\u000304Alice\u0003", net.userMetadata["alice"]?.displayName)
        assertEquals("/api/link-preview/avatar/abc", net.userMetadata["alice"]?.avatarUrl)
        assertEquals("she/her", net.userMetadata["alice"]?.pronouns)
        assertEquals("https://a.example", net.userMetadata["alice"]?.homepage)
        assertEquals("busy", net.userMetadata["alice"]?.status)
        // A null avatar is null: the server can't serve one, so the generated tile stands in.
        assertNull(net.userMetadata["bob"]?.avatarUrl)
        assertEquals(true, net.userMetadata["bob"]?.isEmpty)
        assertEquals(MetadataLimits(maxSubs = 10, maxKeys = 20, maxValueBytes = 300, beforeConnect = true), net.metadataLimits)
    }

    @Test
    fun testSnapshotWithoutMetadataIsAnOlderServer() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[]}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        assertTrue(frame.networks[0].userMetadata.isEmpty())
        assertNull(frame.networks[0].metadataLimits)
    }

    @Test
    fun testNullLimitsIsANetworkWithoutMetadata() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":2,"state":"connected","nick":"me","channels":[],"metadataLimits":null}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("expected snapshot, got $frame")
        assertNull(frame.networks[0].metadataLimits)
    }

    @Test
    fun testUserMetadataRidesIrcWithServerTarget() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":":server:2","type":"user-metadata","nick":"Alice","values":{"displayName":"Wonderland Alice","avatarUrl":null}}""",
        )
        if (frame !is ServerFrame.UserMetadata) fail("expected userMetadata, got $frame")
        val (networkId, nick, values) = frame
        assertEquals(2, networkId)
        assertEquals("Alice", nick)
        assertEquals("Wonderland Alice", values?.displayName)
        assertNull(values?.avatarUrl)
    }

    @Test
    fun testUserMetadataWithNullValuesForgetsTheNick() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":":server:2","type":"user-metadata","nick":"alice","values":null}""",
        )
        if (frame !is ServerFrame.UserMetadata) fail("expected userMetadata, got $frame")
        assertNull(frame.values)
    }

    @Test
    fun testUserMetadataWithoutNetworkOrNickIsIgnored() {
        for (text in listOf(
            """{"kind":"irc","target":":server:2","type":"user-metadata","nick":"alice","values":{}}""",
            """{"kind":"irc","networkId":2,"target":":server:2","type":"user-metadata","values":{}}""",
        )) {
            val frame = FrameParser.parseWs(text)
            if (frame !is ServerFrame.Ignored) fail("expected ignored, got $frame")
        }
    }

    /**
     * ⚠ The regression this whole seam guards: left to fall through, both metadata events became
     * `Other` lines — an empty row in the server buffer on every profile change, and a channel
     * row's worth of nothing on a channel-metadata.
     */
    @Test
    fun testMetadataEventsNeverBecomeALine() {
        val store = LurkerStore()
        store.apply(ServerFrame.SocketOpen)
        store.apply(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 2, state = ConnectionState.Connected, nick = "me",
                        channels = listOf(ChannelSnapshot(name = "#lurker", topic = null, members = emptyList())),
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        val before = store.state.messages.mapValues { it.value.size }
        store.apply(
            FrameParser.parseWs(
                """{"kind":"irc","networkId":2,"target":":server:2","type":"user-metadata","nick":"alice","values":{"displayName":"A"}}""",
            ),
        )
        val channel = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"target":"#lurker","type":"channel-metadata","values":{"displayName":"The Lurkers","avatarUrl":null}}""",
        )
        if (channel !is ServerFrame.Ignored) fail("channel-metadata should be ignored, got $channel")
        store.apply(channel)
        assertEquals(before, store.state.messages.mapValues { it.value.size }, "no row anywhere")
        assertEquals("A", store.state.metadata(networkId = 2, nick = "alice")?.displayName)
    }

    @Test
    fun testAvatarSetsChangedIsItsOwnFrame() {
        val frame = FrameParser.parseWs("""{"kind":"avatar-sets-changed"}""")
        if (frame !is ServerFrame.AvatarSetsChanged) fail("expected avatarSetsChanged, got $frame")
    }

    // MARK: - Store

    private fun network(id: Int, metadata: Map<String, UserMetadata> = emptyMap(), limits: MetadataLimits? = null): NetworkSnapshot =
        NetworkSnapshot(
            id = id, state = ConnectionState.Connected, nick = "me", channels = emptyList(),
            userMetadata = metadata, metadataLimits = limits,
        )

    private fun snapshot(vararg networks: NetworkSnapshot): ServerFrame =
        ServerFrame.Snapshot(networks.toList(), globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated)

    @Test
    fun testLiveMetadataReplacesWholeAndNullForgets() {
        val store = LurkerStore()
        store.apply(snapshot(network(2, metadata = mapOf("alice" to UserMetadata(displayName = "A", pronouns = "she")))))
        // Whole record: a frame without pronouns is a record without pronouns.
        store.apply(ServerFrame.UserMetadata(networkId = 2, nick = "Alice", values = UserMetadata(displayName = "B")))
        assertEquals("B", store.state.metadata(networkId = 2, nick = "ALICE")?.displayName)
        assertNull(store.state.metadata(networkId = 2, nick = "alice")?.pronouns)
        store.apply(ServerFrame.UserMetadata(networkId = 2, nick = "alice", values = null))
        assertNull(store.state.metadata(networkId = 2, nick = "alice"))
        assertEquals(0, store.state.userMetadata.count(networkId = 2))
        // The patch path folds its one key like the seed folds all of them.
        store.apply(ServerFrame.UserMetadata(networkId = 2, nick = "ΟΔΥΣΣΕΥΣ", values = UserMetadata(status = "x")))
        assertEquals("x", store.state.metadata(networkId = 2, nick = "οδυσσευς")?.status)
    }

    @Test
    fun testMetadataIsPerNetworkAndSnapshotReplacesWholesale() {
        val store = LurkerStore()
        store.apply(
            snapshot(
                network(2, metadata = mapOf("alice" to UserMetadata(displayName = "Two"), "bob" to UserMetadata(status = "x"))),
                network(3, metadata = mapOf("alice" to UserMetadata(displayName = "Three"))),
            ),
        )
        assertEquals("Two", store.state.metadata(networkId = 2, nick = "alice")?.displayName)
        assertEquals("Three", store.state.metadata(networkId = 3, nick = "alice")?.displayName)
        assertNull(store.state.metadata(networkId = null, nick = "alice"))
        store.apply(snapshot(network(2, metadata = mapOf("alice" to UserMetadata(displayName = "Two")))))
        assertNull(store.state.metadata(networkId = 2, nick = "bob"), "a reconnect's snapshot is the whole map")
    }

    @Test
    fun testMetadataKeysFoldLikeTheWeb() {
        // The server keys with JavaScript's toLowerCase(), which lowers a final capital sigma to ς;
        // a lookup here has to land on the same key (lurker-ios#199 for the colour).
        val store = LurkerStore()
        store.apply(snapshot(network(2, metadata = mapOf("οδυσσευς" to UserMetadata(displayName = "O")))))
        assertEquals("O", store.state.metadata(networkId = 2, nick = "ΟΔΥΣΣΕΥΣ")?.displayName)
    }

    @Test
    fun testMetadataSetIsReplacedNotMutated() {
        val store = LurkerStore()
        store.apply(snapshot(network(2)))
        val before = store.state.userMetadata
        store.apply(ServerFrame.UserMetadata(networkId = 2, nick = "alice", values = UserMetadata(displayName = "A")))
        assertFalse(before === store.state.userMetadata)
        val after = store.state.userMetadata
        store.apply(ServerFrame.AvatarSetsChanged)
        assertTrue(after === store.state.userMetadata, "a frame that changes nothing keeps the pointer")
    }

    @Test
    fun testDroppingANetworkDropsItsMetadataAndLimits() {
        val store = LurkerStore()
        val limits = MetadataLimits(maxSubs = 1, maxKeys = 1, maxValueBytes = 1, beforeConnect = false)
        store.apply(snapshot(network(2, metadata = mapOf("alice" to UserMetadata(displayName = "A")), limits = limits)))
        assertEquals(limits, store.state.networks[2]?.metadataLimits)
        // A roster that no longer names the network drops it, metadata and all.
        store.apply(ServerFrame.Networks(listOf(Network(id = 3, name = "Other"))))
        assertNull(store.state.metadata(networkId = 2, nick = "alice"))
        assertEquals(0, store.state.userMetadata.count(networkId = 2))
    }

    @Test
    fun testLimitsClearWhenTheLinkDropsLikeModeSpec() {
        val store = LurkerStore()
        val limits = MetadataLimits(maxSubs = 1, maxKeys = 1, maxValueBytes = 1, beforeConnect = false)
        store.apply(snapshot(network(2, limits = limits)))
        store.apply(ServerFrame.NetworkState(networkId = 2, state = ConnectionState.Disconnected, nick = null))
        assertNull(store.state.networks[2]?.metadataLimits)
    }

    // MARK: - Display names

    private fun storeWith(settings: Map<String, SettingValue>, metadata: Map<String, UserMetadata>): LurkerStore {
        val store = LurkerStore()
        store.apply(ServerFrame.SettingsBootstrap(registry = emptyMap(), values = settings))
        store.apply(snapshot(network(2, metadata = metadata)))
        return store
    }

    @Test
    fun testDisplayNameShowsRawAndPlainAndKeepsTheNickAsIdentity() {
        val store = storeWith(settings = emptyMap(), metadata = mapOf("alice" to UserMetadata(displayName = " \u000304Wonderland\u0003 Alice ")))
        assertEquals(" \u000304Wonderland\u0003 Alice ", store.state.displayName(networkId = 2, nick = "Alice"))
        assertEquals("Wonderland Alice", store.state.plainDisplayName(networkId = 2, nick = "alice"))
        assertEquals("Wonderland Alice", store.state.label(networkId = 2, nick = "alice"))
        assertEquals("bob", store.state.label(networkId = 2, nick = "bob"))
        assertNull(store.state.displayName(networkId = 3, nick = "alice"))
    }

    @Test
    fun testANameThatStripsToNothingIsNoName() {
        val store = storeWith(
            settings = emptyMap(),
            metadata = mapOf(
                "alice" to UserMetadata(displayName = "\u000304\u0003  "),
                "bob" to UserMetadata(displayName = ""),
                "carol" to UserMetadata(pronouns = "they"),
            ),
        )
        for (nick in listOf("alice", "bob", "carol")) {
            assertNull(store.state.displayName(networkId = 2, nick = nick), nick)
            assertEquals(nick, store.state.label(networkId = 2, nick = nick))
        }
    }

    @Test
    fun testTheSettingOffPrintsNicksEverywhereButKeepsTheRecord() {
        val store = storeWith(
            settings = mapOf(ChatState.displayNamesKey to SettingValue.Bool(false)),
            metadata = mapOf("alice" to UserMetadata(displayName = "Wonderland", pronouns = "she")),
        )
        assertNull(store.state.displayName(networkId = 2, nick = "alice"))
        assertEquals("alice", store.state.label(networkId = 2, nick = "alice"))
        assertEquals("she", store.state.metadata(networkId = 2, nick = "alice")?.pronouns, "the profile still reads it")
    }

    // MARK: - Tiles

    @Test
    fun testTileIndexMatchesTheWebsVectors() {
        // Computed from shared/avatarTiles.ts (node) — every client must agree.
        val vectors: List<Triple<String, Int, Int>> = listOf(
            Triple("bob", 140, 4), Triple("Bob", 140, 4), Triple("bob_", 140, 4), Triple("bob|away", 140, 4), Triple("_bob", 140, 70),
            Triple("alice", 140, 11), Triple("amiantos", 140, 138), Triple("ñandú", 140, 71), Triple("[bracket]", 140, 94),
            Triple("Σίσυφος", 140, 94), Triple("ΟΔΥΣΣΕΥΣ", 140, 44), Triple("🙂face", 140, 75),
            Triple("bob", 7, 4), Triple("bob", 1, 0), Triple("_", 10, 2),
            Triple("a", 100, 64), Triple("Zed", 100, 23), Triple("someone_long_nick", 100, 14),
        )
        for ((nick, count, expected) in vectors) {
            assertEquals(expected, AvatarTiles.index(nick = nick, count = count), "$nick in $count")
        }
        assertEquals(4, AvatarTiles.index(nick = "bob-x", count = 140, stopChars = setOf('-'.code)))
    }

    @Test
    fun testTileIndexIsDeterministicInsideTheSetAndSpreads() {
        for (nick in listOf("a", "Zed", "someone_long_nick", "ñandú", "[bracket]")) {
            val i = AvatarTiles.index(nick = nick, count = 7)
            assertNotNull(i)
            assertTrue((0 until 7).contains(i), nick)
            assertEquals(i, AvatarTiles.index(nick = nick, count = 7))
        }
        val seen = mutableSetOf<Int>()
        for (i in 0 until 400) seen.add(AvatarTiles.index(nick = "nick$i", count = 32) ?: -1)
        assertEquals(32, seen.size)
    }

    @Test
    fun testTileIndexAnswersNilWhenThereIsNothingToPick() {
        assertNull(AvatarTiles.index(nick = "", count = 10))
        assertNull(AvatarTiles.index(nick = null, count = 10))
        assertNull(AvatarTiles.index(nick = "bob", count = 0))
        // A nick that is all stop characters keeps them, as the colour does.
        assertNotNull(AvatarTiles.index(nick = "_", count = 10))
    }

    @Test
    fun testTileUrlNumbersFrom000() {
        assertEquals("/avatars/ealain/000.webp", AvatarTiles.url(base = "/avatars/ealain", index = 0, ext = "webp"))
        assertEquals("/avatars/ealain/042.png", AvatarTiles.url(base = "/avatars/ealain/", index = 42, ext = "png"))
        assertEquals("/api/avatar-sets/7/123.webp", AvatarTiles.url(base = "/api/avatar-sets/7", index = 123, ext = "webp"))
    }

    // MARK: - Sets

    private fun settings(values: Map<String, SettingValue>): Settings =
        Settings(registry = emptyMap(), values = values)

    @Test
    fun testThePointerResolvesAndADanglingOneFallsBackToInitialDark() {
        var sets = AvatarSets()
        assertEquals("initial-dark", sets.active(settings = settings(emptyMap())).id)
        assertEquals("plain-light", sets.active(settings = settings(mapOf(AvatarSets.settingKey to SettingValue.String("plain-light")))).id)
        assertEquals("initial-dark", sets.active(settings = settings(mapOf(AvatarSets.settingKey to SettingValue.String("7")))).id, "unfetched upload")
        assertEquals("initial-dark", sets.active(settings = settings(mapOf(AvatarSets.settingKey to SettingValue.String("nope")))).id)
        sets = sets.copy(uploaded = listOf(UploadedAvatarSet(id = 7, name = "Cats", count = 12)))
        val active = sets.active(settings = settings(mapOf(AvatarSets.settingKey to SettingValue.String("7"))))
        assertEquals("Cats", active.name)
        assertEquals(AvatarSet.Kind.Tiles(baseUrl = "/api/avatar-sets/7", count = 12, ext = "webp"), active.kind)
        assertFalse(active.builtin)
    }

    @Test
    fun testTilePathNeedsAManifestAndAnInitialSetHasNone() {
        var sets = AvatarSets()
        val ealain = settings(mapOf(AvatarSets.settingKey to SettingValue.String("ealain")))
        assertNull(sets.tilePath(nick = "bob", settings = ealain), "no manifest yet: colour only")
        sets = sets.copy(manifests = sets.manifests + ("ealain" to AvatarSetManifest(count = 140)))
        assertEquals("/avatars/ealain/004.webp", sets.tilePath(nick = "bob", settings = ealain))
        assertNull(sets.tilePath(nick = "bob", settings = settings(emptyMap())), "initial-dark draws a letter")
        assertEquals(AvatarInk.Dark, sets.active(settings = settings(emptyMap())).ink)
        assertEquals(AvatarInk.Light, sets.active(settings = ealain).ink)
        // The stop chars follow the colour's setting.
        sets = sets.copy(manifests = sets.manifests + ("ealain" to AvatarSetManifest(count = 140)))
        val dash = settings(mapOf(AvatarSets.settingKey to SettingValue.String("ealain"), "look.nick.color_stop_chars" to SettingValue.String("-")))
        assertEquals("/avatars/ealain/004.webp", sets.tilePath(nick = "bob-x", settings = dash))
    }

    @Test
    fun testListAndManifestDecode() {
        val list = AvatarSets.parseList("""{"items":[{"id":7,"name":"Cats","count":12,"bytes":1,"createdAt":"x"},{"name":"no id"}]}""")
        assertEquals(listOf(UploadedAvatarSet(id = 7, name = "Cats", count = 12)), list)
        assertNull(AvatarSets.parseList("""{"nope":true}"""), "wrong shape keeps what we had")
        assertEquals(AvatarSetManifest(count = 140, ext = "webp"), AvatarSets.parseManifest("""{"count":140,"ext":"webp"}"""))
        assertEquals(AvatarSetManifest(count = 1, ext = "webp"), AvatarSets.parseManifest("""{"count":1}"""))
        assertNull(AvatarSets.parseManifest("""{"count":0}"""))
        assertNull(AvatarSets.parseManifest("""{"count":"many"}"""))
    }

    @Test
    fun testBuiltinsMatchTheWeb() {
        assertEquals(listOf("ealain", "plain-light", "plain-dark", "initial-light", "initial-dark"), AvatarSets.builtins.map { it.id })
        assertEquals(listOf("ealain", "plain-light", "plain-dark"), AvatarSets.builtinTileSetIds)
        assertEquals("initial-dark", AvatarSets.defaultSetId)
    }

    // MARK: - Density

    @Test
    fun testTheGutterIsTheIndentOnlyWhereItIsDrawn() {
        // The setting reads as stored whatever the avatar switch says — the feed shares it and
        // draws no gutter; the list takes `flushBodies` where it draws the column.
        val density = CompactDensity.of(settings(mapOf(CompactDensity.indentKey to SettingValue.Int(4), CompactDensity.avatarsKey to SettingValue.Bool(true))))
        assertEquals(4, density.indent)
        assertEquals(CompactDensity.of(groupGap = 10, lineHeightPercent = 155, indent = 0), density.flushBodies)
        assertTrue(CompactDensity.avatarsOn(settings(mapOf(CompactDensity.avatarsKey to SettingValue.Bool(true)))))
        assertFalse(CompactDensity.avatarsOn(settings(emptyMap())))
    }

    @Test
    fun testASetAnswersTilePathsOnItsOwn() {
        var sets = AvatarSets()
        sets = sets.copy(manifests = sets.manifests + ("ealain" to AvatarSetManifest(count = 140)))
        val active = sets.active(settings = settings(mapOf(AvatarSets.settingKey to SettingValue.String("ealain"))))
        assertEquals("/avatars/ealain/004.webp", active.tilePath(nick = "bob", stopChars = AvatarTiles.defaultStopChars))
        assertEquals("/avatars/ealain/004.webp", active.tilePath(nick = "bob-x", stopChars = setOf('-'.code)))
        assertNull(active.tilePath(nick = null, stopChars = emptySet()))
        assertNull(sets.active(settings = settings(emptyMap())).tilePath(nick = "bob", stopChars = emptySet()), "an Initial set draws a letter")
        assertEquals(setOf('-'.code), AvatarSets.stopChars(settings = settings(mapOf("look.nick.color_stop_chars" to SettingValue.String("-")))))
    }

    // MARK: - Completion

    @Test
    fun testCompletionMatchesTheNameAndItsWordsAndInsertsTheNick() {
        val members = listOf(Member(nick = "amiantos"), Member(nick = "brad_"), Member(nick = "carol"))
        val names = mapOf("amiantos" to "Brad Root")
        // Port note: the parameter is `table` where the Swift's is `names` — a Kotlin default
        // can't name the outer `names` through a parameter of the same name.
        fun rows(query: String, table: Map<String, String>? = names): List<NickCompletion.Candidate> =
            NickCompletion.rows(
                speakers = SpeakerMap(), members = members, selfNick = "me", query = query, isChannel = true,
                displayName = table?.let { t -> { nick: String -> t[nick] } },
            )
        assertEquals(
            listOf(
                NickCompletion.Candidate(nick = "amiantos", displayName = "Brad Root"),
                NickCompletion.Candidate(nick = "brad_"),
            ),
            rows("bra"),
        )
        assertEquals(listOf("amiantos"), rows("root").map { it.nick })
        assertEquals(listOf("amiantos"), rows("ami").map { it.nick })
        assertEquals(listOf(NickCompletion.Candidate(nick = "carol")), rows("car"))
        // Without a name source it is the old matcher.
        assertEquals(listOf("brad_"), rows("bra", table = null).map { it.nick })
        assertEquals(
            listOf("brad_"),
            NickCompletion.candidates(speakers = SpeakerMap(), members = members, selfNick = "me", query = "bra", isChannel = true),
        )
    }
}
