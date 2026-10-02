// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
            ServerFrame.SettingsChanged(mapOf("chat.smart_filter" to SettingValue.Bool(true)), maxUploadBytes = null),
            frame,
        )
    }

    // MARK: - Layering: stored → registry default → caller fallback

    // MARK: - Live updates

    // MARK: - REST `{values}` replaces rather than merges

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

    // Waiting on LurkerStore, ChatState (with the `dependencyJSON` fixture and the `bootstrapped()` /
    // `withDependencies()` helpers):
    // testEmptyChangeFrameIsANoOp, testStoredValueWins,
    // testUnsetKeyFallsThroughToTheRegistryDefault, testCallerFallbackAppliesBeforeBootstrap,
    // testUnknownKeyFallsBackToTheCaller, testTypeMismatchFallsBackRatherThanCoercing,
    // testChangeFramePatchesOneKeyAndLeavesTheRest, testChangeFrameCanSetAPreviouslyUnsetKey,
    // testBootstrapReplacesRatherThanMerges, testRestValuesRemoveAKeyThatIsNoLongerStored,
    // testEchoAfterReplaceIsIdempotent, testReplaceLeavesTheRegistryAlone,
    // testCachedValueOverridesTheFallbackWithNoRegistry, testSeedingFromCacheLeavesLoadedFalse,
    // testParsesChoiceLabelsAndFallsBackToTheRawValue, testParsesDependsOnFromTheRegistry,
    // testSettingsWithoutDependenciesAreAlwaysActive, testClausesAreOred,
    // testResolutionIsTransitive
    //
    // Waiting on SettingsCache: testCacheRoundTripsEveryValueKind, testCacheStartsEmptyAndClears,
    // testCacheSaveReplacesRatherThanMerges

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
}
