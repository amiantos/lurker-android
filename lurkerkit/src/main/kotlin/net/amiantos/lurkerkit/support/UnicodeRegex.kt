// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import java.util.regex.Pattern

/**
 * The one way this module compiles a regular expression. Port-only.
 *
 * ⚠⚠ Three engines are in play and they do not agree by default. LurkerKit's patterns were
 * written for ICU (`NSRegularExpression`), where `\w`, `\d`, `\s` and `\b` are Unicode-aware.
 * Android's `java.util.regex` is ICU underneath and behaves the same. The host JVM these
 * tests run on is OpenJDK's engine, where those classes are **ASCII-only** unless
 * `UNICODE_CHARACTER_CLASS` is set — so a bare `Regex(pattern)` would pass every test here
 * and then match differently on the device.
 *
 * Setting the flag aligns the host with the other two (Android accepts it as a no-op, since
 * it is always on there). It does not make the engines identical: keep patterns to the
 * common subset, and prefer an explicit class (`[A-Za-z0-9]`) over a shorthand wherever the
 * Swift does.
 */
fun unicodeRegex(pattern: String, ignoreCase: Boolean = false): Regex {
    var flags = Pattern.UNICODE_CHARACTER_CLASS
    // UNICODE_CASE with it: ICU's case-insensitive matching folds beyond ASCII, the JDK's
    // does not unless asked.
    if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    return Pattern.compile(pattern, flags).toRegex()
}
