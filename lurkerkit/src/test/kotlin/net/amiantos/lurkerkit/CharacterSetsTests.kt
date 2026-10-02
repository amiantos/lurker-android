// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.support.isInWhitespaces
import net.amiantos.lurkerkit.support.isInWhitespacesAndNewlines
import net.amiantos.lurkerkit.support.isSwiftWhitespace
import net.amiantos.lurkerkit.support.trimmingWhitespaces
import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-only. The three sets, member for member, as a compiled Swift program enumerated them
 * from Foundation and the standard library. `Character.getType` is the host JDK's Unicode
 * tables here and ICU's on a device, so this is also the test to run there.
 */
class CharacterSetsTests {

    private fun members(predicate: (Char) -> Boolean): String =
        (0..0xFFFF).filter { predicate(it.toChar()) }.joinToString(" ") { "%04X".format(it) }

    private val whitespaces =
        "0009 0020 00A0 1680 2000 2001 2002 2003 2004 2005 2006 2007 2008 2009 200A 200B 202F 205F 3000"

    private val whitespacesAndNewlines =
        "0009 000A 000B 000C 000D 0020 0085 00A0 1680 2000 2001 2002 2003 2004 2005 2006 2007 2008 " +
            "2009 200A 200B 2028 2029 202F 205F 3000"

    @Test
    fun testWhitespacesIsFoundationsSet() {
        assertEquals(whitespaces, members { it.isInWhitespaces() })
    }

    @Test
    fun testWhitespacesAndNewlinesIsFoundationsSet() {
        assertEquals(whitespacesAndNewlines, members { it.isInWhitespacesAndNewlines() })
    }

    @Test
    fun testSwiftWhitespaceIsWhiteSpaceWhichLeavesOutTheZeroWidthSpace() {
        assertEquals(whitespacesAndNewlines.replace("200B ", ""), members { it.isSwiftWhitespace() })
    }

    @Test
    fun testTheTrimsDifferFromKotlinsOwnExactlyWhereTheSetsDo() {
        // A zero-width space and a NEL: trimmed by Foundation, kept by `trim()`.
        assertEquals("x", "\u200Bx\u0085".trimmingWhitespacesAndNewlines())
        assertEquals("\u200Bx\u0085", "\u200Bx\u0085".trim())
        // A C0 separator: kept by Foundation, trimmed by `trim()`.
        assertEquals("\u001Cx", "\u001Cx".trimmingWhitespacesAndNewlines())
        assertEquals("x", "\u001Cx".trim())
        // `.whitespaces` leaves a newline alone.
        assertEquals("x\n", " \tx\n".trimmingWhitespaces())
    }
}
