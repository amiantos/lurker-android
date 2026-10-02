// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import java.time.Instant
import java.time.ZoneId

/**
 * `Calendar.startOfDay(for:)`: the first instant of `date`'s day in `zone`. Port-only.
 *
 * Checked against the Swift either side of every transition from 1990 to 2037 in every zone
 * the JDK knows — a midnight that does not exist, one that runs twice, a day skipped whole.
 * They differ only where their time-zone data does (a rule change one has and the other has
 * not yet). Depends on that data; verify on a device.
 */
internal fun startOfDay(date: Instant, zone: ZoneId): Instant =
    date.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
