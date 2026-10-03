// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.shell

import net.amiantos.lurkerkit.client.Incompatibility
import net.amiantos.lurkerkit.model.ConnectionBannerState
import net.amiantos.lurkerkit.model.StatusLight

/**
 * Lurker's own connection, in words — lurker-ios's `ConnectionBanner` copy, with the light's
 * "Connected" for the state the banner hides in. Words, so the state never rests on a colour or a
 * spinner alone (e-ink).
 */
internal fun connectionWords(state: ConnectionBannerState): String =
    when (state) {
        ConnectionBannerState.Hidden -> StatusLight.Good.subtitle(detail = null)
        ConnectionBannerState.Connecting -> "Connecting…"
        ConnectionBannerState.Reconnecting -> "Reconnecting…"
        ConnectionBannerState.Offline -> "No internet connection"
        is ConnectionBannerState.Incompatible -> when (state.incompatibility) {
            Incompatibility.AppTooOld -> "Update the app to connect"
            Incompatibility.ServerTooOld -> "The server needs an update"
        }
    }
