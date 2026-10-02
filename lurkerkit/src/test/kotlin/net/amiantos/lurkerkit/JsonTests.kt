// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.amiantos.lurkerkit.client.bool
import net.amiantos.lurkerkit.client.decodeEach
import net.amiantos.lurkerkit.client.has
import net.amiantos.lurkerkit.client.int
import net.amiantos.lurkerkit.client.intOrNull
import net.amiantos.lurkerkit.client.long
import net.amiantos.lurkerkit.client.longOrNull
import net.amiantos.lurkerkit.client.objects
import net.amiantos.lurkerkit.client.string
import net.amiantos.lurkerkit.client.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port-only. LurkerKit has no suite for its JSON reads because they are bare `as?` casts on
 * `JSONSerialization` values. These reads have to reproduce those casts by hand, so each
 * expectation here is what Foundation answered for the same JSON (probed with a compiled Swift
 * program): strings and numbers never cross, numbers and booleans do.
 */
class JsonTests {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun testStringOrNullCollapsesMissingNullAndEmpty() {
        val o = obj("""{"topic":"hello","empty":"","nothing":null}""")
        assertEquals("hello", o.stringOrNull("topic"))
        assertNull(o.stringOrNull("empty"))
        assertNull(o.stringOrNull("nothing"))
        assertNull(o.stringOrNull("absent"))
    }

    @Test
    fun testStringKeepsAnEmptyStringAndFallsBackOnlyWhenThereIsNone() {
        val o = obj("""{"empty":"","nothing":null}""")
        assertEquals("", o.string("empty", "fallback"))
        assertEquals("fallback", o.string("nothing", "fallback"))
        assertEquals("", o.string("absent"))
    }

    @Test
    fun testANumberIsNeverReadAsAStringNorAStringAsANumber() {
        // ⚠⚠ The coercions `JsonPrimitive.content` and `.int` would both perform.
        val o = obj("""{"id":3,"quoted":"3","flag":true,"word":"true","one":"1"}""")
        assertNull(o.stringOrNull("id"))
        assertEquals("", o.string("id"))
        assertNull(o.intOrNull("quoted"))
        assertEquals(7, o.int("quoted", 7))
        assertNull(o.longOrNull("quoted"))
        assertNull(o.stringOrNull("flag"))
        assertFalse(o.bool("word"))
        assertFalse(o.bool("one"))
    }

    @Test
    fun testIntOrNullDistinguishesAbsentFromZero() {
        val o = obj("""{"networkId":0,"nothing":null}""")
        assertEquals(0, o.intOrNull("networkId"))
        assertNull(o.intOrNull("nothing"))
        assertNull(o.intOrNull("absent"))
        assertEquals(0, o.int("absent"))
    }

    @Test
    fun testAFractionalOrOversizedNumberIsNotAnInt() {
        val o = obj("""{"half":1.5,"big":4294967296,"huge":1e30,"past":9223372036854775808}""")
        assertNull(o.intOrNull("half"))
        assertNull(o.longOrNull("half"))
        assertNull(o.longOrNull("huge"))
        assertNull(o.longOrNull("past"))
        // Past 32 bits: not an `Int`, but exactly what `long` is for.
        assertNull(o.intOrNull("big"))
        assertEquals(4_294_967_296L, o.long("big"))
    }

    @Test
    fun testAWholeNumberIsAnIntHoweverItIsWritten() {
        val o = obj("""{"plain":3,"point":3.0,"exp":1e3,"neg":-1}""")
        assertEquals(3, o.intOrNull("plain"))
        assertEquals(3, o.intOrNull("point"))
        assertEquals(1000, o.intOrNull("exp"))
        assertEquals(-1, o.intOrNull("neg"))
        assertEquals(3L, o.longOrNull("point"))
    }

    @Test
    fun testABooleanReadsAsOneOrZero() {
        // What `true as? Int` gives on iOS, where both are `NSNumber`s.
        val o = obj("""{"yes":true,"no":false}""")
        assertEquals(1, o.intOrNull("yes"))
        assertEquals(0, o.intOrNull("no"))
    }

    @Test
    fun testBoolReadsALiteralBooleanOrAZeroOrOne() {
        // ⚠ The 0/1 arm is the one that matters: a SQLite integer flag the server echoes without
        // normalising is a boolean on iOS, and reading it as "absent" here would be silent.
        val o = obj("""{"yes":true,"no":false,"one":1,"zero":0,"one0":1.0,"two":2,"half":0.5,"nothing":null}""")
        assertTrue(o.bool("yes"))
        assertFalse(o.bool("no", true))
        assertTrue(o.bool("one"))
        assertFalse(o.bool("zero", true))
        assertTrue(o.bool("one0"))
        // Not booleans: the fallback, whichever way it points.
        assertFalse(o.bool("two"))
        assertTrue(o.bool("two", true))
        assertFalse(o.bool("half"))
        assertTrue(o.bool("nothing", true))
        assertTrue(o.bool("absent", true))
    }

    @Test
    fun testObjectsIsAllOrNothing() {
        val o = obj("""{"good":[{"a":1},{"a":2}],"mixed":[{"a":1},2],"notArray":{"a":1}}""")
        assertEquals(2, o.objects("good").size)
        assertTrue(o.objects("mixed").isEmpty())
        assertTrue(o.objects("notArray").isEmpty())
        assertTrue(o.objects("absent").isEmpty())
    }

    @Test
    fun testHasCountsAFalseValueButNotANull() {
        val o = obj("""{"reset":false,"nothing":null}""")
        assertTrue(o.has("reset"))
        assertFalse(o.has("nothing"))
        assertFalse(o.has("absent"))
    }

    @Serializable
    private data class Card(val url: String, val width: Int)

    @Test
    fun testDecodeEachDropsOnlyTheElementItCannotRead() {
        val elements = obj(
            """{"previews":[{"url":"a","width":1},{"url":"b","width":"wide"},{"nope":true},{"url":"c","width":3}]}""",
        ).getValue("previews").jsonArray
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            listOf(Card("a", 1), Card("c", 3)),
            json.decodeEach(Card.serializer(), elements),
        )
    }
}
