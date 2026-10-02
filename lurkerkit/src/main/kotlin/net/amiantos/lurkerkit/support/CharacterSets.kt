// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

/**
 * Whether this is in Foundation's `CharacterSet.whitespaces`: Unicode space separators (Zs)
 * and the tab. Port-only.
 *
 * For `trimmingCharacters(in: .whitespaces)`, which deliberately leaves newlines alone.
 * `Char.isWhitespace()` is not it — that also takes the line and paragraph separators and
 * the C0 separators, so a trim built on it removes characters the Swift keeps.
 */
fun Char.isInWhitespaces(): Boolean =
    this == '\t' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()
