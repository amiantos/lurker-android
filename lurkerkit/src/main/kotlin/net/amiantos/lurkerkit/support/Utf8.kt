// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import okio.ByteString
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * `String(data:encoding: .utf8)`: the bytes as UTF-8, or null where they are not — never a
 * U+FFFD in place of a bad byte, which is what `ByteString.utf8()` would give.
 */
internal fun ByteString.utf8OrNull(): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(asByteBuffer())
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
