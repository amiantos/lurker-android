// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.SettingDependency
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.store.SettingsCache
import net.amiantos.lurkerkit.store.DefaultsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Settings sync (lurker-ios#65): the bootstrap parse, the live change frame, and the layering
 * that makes a read correct.
 *
 * The layering is the part worth pinning down. `/api/settings/bootstrap` returns *stored*
 * values only — a user who has never opened settings gets `{}` — so every read has to fall
 * through to the registry default. Get that wrong and each bool reads false on a fresh
 * account, silently turning features off.
 */
class SettingsTests {

    private val bootstrapJSON = """
    {
      "registry": [
        {"key":"chat.consolidate_joins","label":"Consolidate joins","category":"chat","group":"noise",
         "type":"bool","default":true,"description":"Merge runs of join/part."},
        {"key":"chat.consolidate_max_names","label":"Max names","category":"chat","group":"noise",
         "type":"int","default":5,"min":1,"max":20,"description":"Names before \"and N others\"."},
        {"key":"look.message.layout","label":"Layout","category":"look","group":"message",
         "type":"enum","default":"auto","choices":["auto","standard","compact"],"description":"Row layout."},
        {"key":"chat.quit_message","label":"Quit message","category":"chat","group":"composing",
         "type":"string","default":"","description":"Sent with QUIT."}
      ],
      "values": {"chat.consolidate_joins": false, "chat.consolidate_max_names": 9}
    }
    """.trimIndent()

    private fun bootstrapped(): ChatState =
        LurkerStore.reduce(ChatState(), FrameParser.parseSettingsBootstrap(bootstrapJSON))

    // MARK: - Parsing

    @Test
    fun testBootstrapParsesRegistryAndValues() {
        val frame = FrameParser.parseSettingsBootstrap(bootstrapJSON)
        if (frame !is ServerFrame.SettingsBootstrap) fail("expected settingsBootstrap")
        val (registry, values) = frame
        assertEquals(4, registry.size)
        assertEquals(SettingType.Bool, registry["chat.consolidate_joins"]?.type)
        assertEquals(SettingValue.Bool(true), registry["chat.consolidate_joins"]?.default)
        assertEquals(1, registry["chat.consolidate_max_names"]?.min)
        assertEquals(20, registry["chat.consolidate_max_names"]?.max)
        assertEquals(listOf("auto", "standard", "compact"), registry["look.message.layout"]?.choices)
        // Only what the user actually stored.
        assertEquals(2, values.size)
        assertEquals(SettingValue.Bool(false), values["chat.consolidate_joins"])
    }

    /**
     * On iOS `JSONSerialization` decodes both `true` and `1` into `NSNumber`, so a naive cast
     * claims booleans as ints and any nonzero int as a boolean. Confusing them would turn
     * every bool setting into `Int(1)` and every write back into a type error.
     */
    @Test
    fun testBoolsAndIntsDoNotBleedIntoEachOther() {
        assertEquals(SettingValue.Bool(true), SettingValue.from(JsonPrimitive(true)))
        assertEquals(SettingValue.Bool(false), SettingValue.from(JsonPrimitive(false)))
        assertEquals(SettingValue.Int(1), SettingValue.from(JsonPrimitive(1)))
        assertEquals(SettingValue.Int(0), SettingValue.from(JsonPrimitive(0)))
    }

    @Test
    fun testSettingValueDecodesEveryWireShape() {
        assertEquals(SettingValue.String("hello"), SettingValue.from(JsonPrimitive("hello")))
        assertEquals(
            SettingValue.StringList(listOf("a", "b")),
            SettingValue.from(Json.parseToJsonElement("""["a", "b"]""")),
        )
        assertNull(SettingValue.from(JsonNull))
    }

    /**
     * A registry entry we can't represent is skipped rather than half-built: an option with
     * no usable default would make `effective` return null for an unset key, and every caller
     * would silently fall through to its own fallback — the drift this layer prevents.
     */
    @Test
    fun testUnusableRegistryEntriesAreSkipped() {
        val json = """
        {"registry":[
          {"key":"ok","label":"L","type":"bool","default":true,"description":"","category":"c","group":"g"},
          {"key":"no-type","label":"L","default":true,"description":"","category":"c","group":"g"},
          {"key":"no-default","label":"L","type":"bool","description":"","category":"c","group":"g"},
          {"key":"","label":"L","type":"bool","default":true,"description":"","category":"c","group":"g"}
        ],"values":{}}
        """.trimIndent()
        val frame = FrameParser.parseSettingsBootstrap(json)
        if (frame !is ServerFrame.SettingsBootstrap) fail("expected settingsBootstrap")
        assertEquals(listOf("ok"), frame.registry.keys.toList())
    }

    @Test
    fun testSettingsChangeFrameParses() {
        val frame = FrameParser.parseWs("""{"kind":"settings","changes":{"chat.smart_filter":true}}""")
        assertEquals(
            ServerFrame.SettingsChanged(mapOf("chat.smart_filter" to SettingValue.Bool(true)), uploadLimits = UploadLimits.unstated),
            frame,
        )
    }

    /**
     * The server sends `changes || {}`, so an empty patch is legal and must be a no-op rather
     * than anything that could be mistaken for "everything was cleared".
     */
    @Test
    fun testEmptyChangeFrameIsANoOp() {
        var state = bootstrapped()
        state = LurkerStore.reduce(state, FrameParser.parseWs("""{"kind":"settings","changes":{}}"""))
        assertEquals(false, state.settings.bool("chat.consolidate_joins", default = true))
        assertEquals(9, state.settings.int("chat.consolidate_max_names", default = 5))
    }

    // MARK: - Layering: stored → registry default → caller fallback

    @Test
    fun testStoredValueWins() {
        val state = bootstrapped()
        assertEquals(false, state.settings.bool("chat.consolidate_joins", default = true))
        assertEquals(9, state.settings.int("chat.consolidate_max_names", default = 5))
    }

    /**
     * The trap. `values` holds nothing for this key, so a read that didn't fall through to
     * the registry would report `false` for a setting the server considers `true`.
     */
    @Test
    fun testUnsetKeyFallsThroughToTheRegistryDefault() {
        val state = bootstrapped()
        assertEquals(SettingValue.String("auto"), state.settings.effective("look.message.layout"))
        assertEquals("auto", state.settings.string("look.message.layout", default = "zzz"))
    }

    /**
     * Before bootstrap lands there is no registry either, so the caller's fallback is the
     * only answer — and it must be the registry's own default, or behavior shifts under the
     * user a moment after launch.
     */
    @Test
    fun testCallerFallbackAppliesBeforeBootstrap() {
        val fresh = ChatState()
        assertFalse(fresh.settings.loaded)
        assertEquals(true, fresh.settings.bool("chat.consolidate_joins", default = true))
        assertEquals(5, fresh.settings.int("chat.consolidate_max_names", default = 5))
    }

    /**
     * A self-hosted server can legitimately be older than the app, so a key it has never
     * heard of has to degrade to the caller's fallback rather than to a null-shaped hole.
     */
    @Test
    fun testUnknownKeyFallsBackToTheCaller() {
        val state = bootstrapped()
        assertNull(state.settings.effective("chat.setting_from_the_future"))
        assertTrue(state.settings.bool("chat.setting_from_the_future", default = true))
    }

    /**
     * A read of the wrong type must not coerce — it degrades to the fallback, so a server
     * that changes a setting's type can't make the app render nonsense.
     */
    @Test
    fun testTypeMismatchFallsBackRatherThanCoercing() {
        val state = bootstrapped()
        assertEquals(7, state.settings.int("chat.consolidate_joins", default = 7))
        assertEquals("x", state.settings.string("chat.consolidate_max_names", default = "x"))
    }

    // MARK: - Live updates

    @Test
    fun testChangeFramePatchesOneKeyAndLeavesTheRest() {
        var state = bootstrapped()
        state = LurkerStore.reduce(
            state,
            ServerFrame.SettingsChanged(mapOf("chat.consolidate_joins" to SettingValue.Bool(true)), uploadLimits = UploadLimits.unstated),
        )
        assertTrue(state.settings.bool("chat.consolidate_joins", default = false))
        // The other stored value survives — a patch, not a replace.
        assertEquals(9, state.settings.int("chat.consolidate_max_names", default = 5))
    }

    @Test
    fun testChangeFrameCanSetAPreviouslyUnsetKey() {
        var state = bootstrapped()
        state = LurkerStore.reduce(
            state,
            ServerFrame.SettingsChanged(mapOf("look.message.layout" to SettingValue.String("compact")), uploadLimits = UploadLimits.unstated),
        )
        assertEquals("compact", state.settings.string("look.message.layout", default = "auto"))
    }

    @Test
    fun testBootstrapReplacesRatherThanMerges() {
        var state = bootstrapped()
        state = LurkerStore.reduce(
            state,
            ServerFrame.SettingsChanged(mapOf("look.message.layout" to SettingValue.String("compact")), uploadLimits = UploadLimits.unstated),
        )
        // A reconnect re-bootstraps: the server's stored set is authoritative, so a value that
        // is no longer stored must revert to its default rather than linger from the old map.
        state = LurkerStore.reduce(state, FrameParser.parseSettingsBootstrap(bootstrapJSON))
        assertEquals("auto", state.settings.string("look.message.layout", default = "auto"))
    }

    // MARK: - REST `{values}` replaces rather than merges

    /**
     * The server drops a row when a key is set back to its default — "no override"
     * (`settingsService.ts:72`) — so a `PATCH` returning to the default comes back as an
     * ABSENCE. Merged, the cleared override would survive locally *and* get persisted to the
     * cache, leaving a setting stuck at a value the user just cleared.
     */
    @Test
    fun testRestValuesRemoveAKeyThatIsNoLongerStored() {
        var state = bootstrapped()
        assertEquals(false, state.settings.bool("chat.consolidate_joins", default = true))
        // The user switches it back on: `true` == the registry default, so the server deletes
        // the row and its reply omits the key entirely.
        state = LurkerStore.reduce(state, ServerFrame.SettingsValues(mapOf("chat.consolidate_max_names" to SettingValue.Int(9))))
        assertTrue(
            state.settings.bool("chat.consolidate_joins", default = true),
            "a key absent from the full stored set must revert to its default",
        )
        assertNull(state.settings.values["chat.consolidate_joins"])
        // …and the untouched one is still there.
        assertEquals(9, state.settings.int("chat.consolidate_max_names", default = 5))
    }

    /**
     * The WS echo of the same write is a patch and arrives separately; applying it after the
     * replace must not resurrect anything.
     */
    @Test
    fun testEchoAfterReplaceIsIdempotent() {
        var state = bootstrapped()
        state = LurkerStore.reduce(state, ServerFrame.SettingsValues(mapOf("chat.consolidate_max_names" to SettingValue.Int(9))))
        state = LurkerStore.reduce(
            state,
            ServerFrame.SettingsChanged(mapOf("chat.consolidate_joins" to SettingValue.Bool(true)), uploadLimits = UploadLimits.unstated),
        )
        assertTrue(state.settings.bool("chat.consolidate_joins", default = false))
    }

    @Test
    fun testReplaceLeavesTheRegistryAlone() {
        var state = bootstrapped()
        state = LurkerStore.reduce(state, ServerFrame.SettingsValues(emptyMap()))
        assertEquals(4, state.settings.registry.size)
        // Everything falls back to its default, which is exactly "nothing overridden".
        assertEquals(5, state.settings.int("chat.consolidate_max_names", default = 0))
    }

    /**
     * A mixed array can't be represented exactly, and picking the strings out of it would
     * mean writing back a different list than the server sent.
     */
    @Test
    fun testMixedArrayDoesNotDecodeToAPartialList() {
        assertNull(SettingValue.from(Json.parseToJsonElement("""["a", 1, "b"]""")))
        assertEquals(
            SettingValue.StringList(listOf("a", "b")),
            SettingValue.from(Json.parseToJsonElement("""["a", "b"]""")),
        )
    }

    @Test
    fun testUnrepresentableValuesAreSkippedNotGuessed() {
        val values = FrameParser.parseSettingValues(
            buildJsonObject {
                put("good", "yes")
                putJsonArray("bad") {
                    add("a")
                    add(1)
                }
            },
        )
        assertEquals(mapOf("good" to SettingValue.String("yes")), values)
    }

    // MARK: - The values cache

    /**
     * Its own store, so tests never scribble on the app's.
     *
     * Port note: LurkerKit gives each test a fresh `UserDefaults` suite; here a map behind the
     * kit's `DefaultsStorage`.
     */
    private fun isolatedCache(): SettingsCache = SettingsCache(defaults = InMemoryDefaultsStorage())

    /** `DefaultsStorage` over a map — the in-memory stand-in for `UserDefaults`. */

    @Test
    fun testCacheRoundTripsEveryValueKind() {
        val cache = isolatedCache()
        val values: Map<String, SettingValue> = mapOf(
            "a.bool" to SettingValue.Bool(false),
            "a.int" to SettingValue.Int(9),
            "a.string" to SettingValue.String("compact"),
            "a.list" to SettingValue.StringList(listOf("x", "y")),
        )
        cache.save(values)
        assertEquals(values, cache.load())
    }

    @Test
    fun testCacheStartsEmptyAndClears() {
        val cache = isolatedCache()
        assertEquals(emptyMap(), cache.load())
        cache.save(mapOf("a.bool" to SettingValue.Bool(true)))
        cache.clear()
        assertEquals(emptyMap(), cache.load())
    }

    /**
     * A save replaces rather than merges: it mirrors the server's stored set, and a setting
     * reset to its default disappears from that set. Merging would keep the old value alive
     * locally forever.
     */
    @Test
    fun testCacheSaveReplacesRatherThanMerges() {
        val cache = isolatedCache()
        cache.save(mapOf("a" to SettingValue.Bool(true), "b" to SettingValue.Int(1)))
        cache.save(mapOf("a" to SettingValue.Bool(true)))
        assertEquals(mapOf("a" to SettingValue.Bool(true)), cache.load())
    }

    /**
     * The reason the cache exists. With no registry at all — bootstrap failed, or hasn't
     * landed — a cached `false` must still be what a privacy gate reads, because the fallback
     * on `chat.send_typing_notifications` is `true` and the user turned it off.
     */
    @Test
    fun testCachedValueOverridesTheFallbackWithNoRegistry() {
        var state = ChatState()
        state = LurkerStore.reduce(
            state,
            ServerFrame.SettingsChanged(
                mapOf("chat.send_typing_notifications" to SettingValue.Bool(false)), uploadLimits = UploadLimits.unstated,
            ),
        )
        assertFalse(state.settings.loaded, "values alone must not claim a real bootstrap")
        assertFalse(
            state.settings.bool("chat.send_typing_notifications", default = true),
            "a cached off must beat the default-on fallback",
        )
    }

    /**
     * Seeding from cache supplies values but no registry, so the settings *screen* can still
     * tell "we have nothing to render controls from" apart from "this server has no settings".
     */
    @Test
    fun testSeedingFromCacheLeavesLoadedFalse() {
        val state = LurkerStore.reduce(
            ChatState(),
            ServerFrame.SettingsChanged(mapOf("a" to SettingValue.Bool(true)), uploadLimits = UploadLimits.unstated),
        )
        assertFalse(state.settings.loaded)
        assertTrue(state.settings.registry.isEmpty())
    }

    // MARK: - Write encoding

    @Test
    fun testValuesRoundTripToJSON() {
        assertEquals(JsonPrimitive(true), SettingValue.Bool(true).jsonValue)
        assertEquals(JsonPrimitive(9), SettingValue.Int(9).jsonValue)
        assertEquals(JsonPrimitive("compact"), SettingValue.String("compact").jsonValue)
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), SettingValue.StringList(listOf("a")).jsonValue)
        // The whole point: what we send has to survive serialization unchanged.
        //
        // Port note: LurkerKit asks `JSONSerialization.isValidJSONObject`. There is no such
        // question to ask of a `JsonObject` — it is valid by construction — so this reads the
        // bytes that would go out instead.
        val body = buildJsonObject {
            putJsonObject("changes") {
                put("chat.consolidate_joins", SettingValue.Bool(false).jsonValue)
                put("chat.consolidate_max_names", SettingValue.Int(3).jsonValue)
            }
        }
        assertEquals(
            """{"changes":{"chat.consolidate_joins":false,"chat.consolidate_max_names":3}}""",
            body.toString(),
        )
    }

    // MARK: - dependsOn (lurker#666)

    /**
     * The registry describes its own dependencies now, so the phone greys out exactly what
     * the web does. This screen used to carry a hand-written map of one entry; the event
     * tier added eight more, which is more than a hardcoded copy survives.
     */
    private val dependencyJSON = """
    {
      "registry": [
        {"key":"chat.events","label":"Event noise","category":"chat","group":"events",
         "type":"enum","default":"all","choices":["all","smart","none"],"description":"Tier."},
        {"key":"chat.events.mobile","label":"Event noise (mobile)","category":"chat","group":"events",
         "type":"enum","default":"all","choices":["all","smart","none"],"description":"Tier."},
        {"key":"chat.consolidate_joins","label":"Consolidate","category":"chat","group":"noise",
         "type":"bool","default":true,"description":"Merge runs.",
         "dependsOn":[{"key":"chat.events","in":["all","smart"]},
                      {"key":"chat.events.mobile","in":["all","smart"]}]},
        {"key":"chat.consolidate_max_names","label":"Max names","category":"chat","group":"noise",
         "type":"int","default":5,"min":1,"max":20,"description":"Names.",
         "dependsOn":[{"key":"chat.consolidate_joins","in":[true]}]}
      ],
      "values": {}
    }
    """.trimIndent()

    private fun withDependencies(values: Map<String, SettingValue> = emptyMap()): Settings {
        var state = LurkerStore.reduce(ChatState(), FrameParser.parseSettingsBootstrap(dependencyJSON))
        state = LurkerStore.reduce(state, ServerFrame.SettingsChanged(values, uploadLimits = UploadLimits.unstated))
        return state.settings
    }

    /**
     * Choice wording comes from the server so the phone and the browser can't say different
     * things about the same value — and so the stored values stay ids, which is what keeps a
     * rewording from being a settings migration.
     */
    @Test
    fun testParsesChoiceLabelsAndFallsBackToTheRawValue() {
        var state = LurkerStore.reduce(
            ChatState(),
            FrameParser.parseSettingsBootstrap(
                """
                {
                  "registry": [
                    {"key":"chat.events.mobile","label":"Event filter","category":"events",
                     "group":"event-filter","type":"enum","default":"all",
                     "choices":["all","smart","none"],
                     "choiceLabels":{"all":"No filter","smart":"Smart filter"},
                     "description":"Filter."}
                  ],
                  "values": {}
                }
                """.trimIndent(),
            ),
        )
        val option = state.settings.registry["chat.events.mobile"]
        assertEquals("No filter", option?.label("all"))
        assertEquals("Smart filter", option?.label("smart"))
        // Unlabelled — including everything an older server sends, which has no
        // `choiceLabels` at all — shows the raw value rather than nothing.
        assertEquals("none", option?.label("none"))
        @Suppress("UNUSED_VALUE") // As in LurkerKit: the last fold is never read.
        state = LurkerStore.reduce(state, ServerFrame.SettingsChanged(emptyMap(), uploadLimits = UploadLimits.unstated))
    }

    @Test
    fun testParsesDependsOnFromTheRegistry() {
        val settings = withDependencies()
        assertEquals(
            listOf(SettingDependency(key = "chat.consolidate_joins", values = listOf(SettingValue.Bool(true)))),
            settings.registry["chat.consolidate_max_names"]?.dependsOn,
        )
        assertEquals(emptyList(), settings.registry["chat.events"]?.dependsOn)
    }

    @Test
    fun testSettingsWithoutDependenciesAreAlwaysActive() {
        assertTrue(withDependencies().isActive("chat.events"))
        // A key this server has never heard of can't be gated on anything.
        assertTrue(withDependencies().isActive("chat.nonexistent"))
    }

    /**
     * Clauses are ORed: a phone set to `none` must not grey out consolidation knobs the
     * user's desktop is actively using.
     */
    @Test
    fun testClausesAreOred() {
        val phoneOnly = withDependencies(
            mapOf("chat.events" to SettingValue.String("all"), "chat.events.mobile" to SettingValue.String("none")),
        )
        assertTrue(phoneOnly.isActive("chat.consolidate_joins"))

        val bothOff = withDependencies(
            mapOf("chat.events" to SettingValue.String("none"), "chat.events.mobile" to SettingValue.String("none")),
        )
        assertFalse(bothOff.isActive("chat.consolidate_joins"))
    }

    /**
     * Transitive: max-names names only `consolidate_joins`, so the tier condition has to
     * arrive through it. Without this the stepper stays live under a tier that renders no
     * events for it to cap.
     */
    @Test
    fun testResolutionIsTransitive() {
        val bothOff = withDependencies(
            mapOf("chat.events" to SettingValue.String("none"), "chat.events.mobile" to SettingValue.String("none")),
        )
        assertFalse(bothOff.isActive("chat.consolidate_max_names"))

        val consolidationOff = withDependencies(mapOf("chat.consolidate_joins" to SettingValue.Bool(false)))
        assertFalse(consolidationOff.isActive("chat.consolidate_max_names"))

        assertTrue(withDependencies().isActive("chat.consolidate_max_names"))
    }

    // Port-only: what `SettingValue.from` does where this platform differs from LurkerKit's.

    /**
     * The same bleed as above, from the side kotlinx opens: `JsonPrimitive.boolean` and `.int`
     * read a *quoted* `"true"` and `"1"` as a boolean and a number. A string is a string.
     */
    @Test
    fun testQuotedBoolsAndNumbersStayStrings() {
        assertEquals(SettingValue.String("true"), SettingValue.from(JsonPrimitive("true")))
        assertEquals(SettingValue.String("1"), SettingValue.from(JsonPrimitive("1")))
    }

    /**
     * LurkerKit's `Int` is 64-bit; this one is 32. A number that doesn't fit is skipped, not
     * wrapped into some other number — and a fraction truncates, as `NSNumber.intValue` does.
     */
    @Test
    fun testANumberThatDoesNotFitIsSkippedNotWrapped() {
        assertNull(SettingValue.from(JsonPrimitive(5_000_000_000)))
        assertNull(SettingValue.from(JsonPrimitive(-5_000_000_000)))
        assertEquals(SettingValue.Int(Int.MAX_VALUE), SettingValue.from(JsonPrimitive(Int.MAX_VALUE)))
        assertEquals(SettingValue.Int(1), SettingValue.from(JsonPrimitive(1.9)))
        assertEquals(SettingValue.Int(-1), SettingValue.from(JsonPrimitive(-1.9)))
    }

    /**
     * `SettingsCache` reads whatever its store hands back through `SettingValue.from`, as
     * LurkerKit reads what `UserDefaults` hands back: a value it can't represent is skipped,
     * and the rest still load. What the store holds is the app's to write, so the kit can't
     * assume it is only ever what `save` put there.
     */
    @Test
    fun testTheCacheSkipsAStoredValueItCannotRepresent() {
        val storage = InMemoryDefaultsStorage()
        storage.set(
            buildJsonObject {
                put("good", true)
                putJsonArray("bad") {
                    add("a")
                    add(1)
                }
                put("gone", JsonNull)
            },
            key = "lurker.settings.values",
        )
        assertEquals(mapOf("good" to SettingValue.Bool(true)), SettingsCache(defaults = storage).load())
    }
}
