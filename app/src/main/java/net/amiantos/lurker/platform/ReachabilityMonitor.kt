// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * Reports whether the device has a network path at all, so the connection status can tell "you
 * have no internet" (yours to fix) apart from "we're reconnecting" (ours). The socket alone can't
 * distinguish those — a drop looks identical either way. lurker-ios's `ReachabilityMonitor`.
 *
 * Reachable means there is a default network, validated or not: iOS counts any path that isn't
 * `.unsatisfied`, so a captive portal or a VPN still coming up is "reachable" there too, and
 * the socket's own failure is what says the rest.
 *
 * Never stopped: it lives as long as the process, as the view model it feeds does (iOS stops its
 * monitor when the scene goes, because a scene can go and the app stay; an `Application` can't).
 */
class ReachabilityMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** The default network we last heard of, so a stale `onLost` can't undo a newer `onAvailable`. */
    private var current: Network? = null

    /**
     * Begin watching. [onChange] runs on the main thread: once now with the current answer, then on
     * every change, in the order they happened.
     *
     * The callback is registered with a main-looper `Handler`, so the platform delivers in order
     * on the main thread itself — a path that flaps offline → online → offline must arrive in that
     * order, which iOS gets from `DispatchQueue.main.async`.
     */
    fun start(onChange: (Boolean) -> Unit) {
        // Registered BEFORE the first sample. The other way round, a default that goes away
        // between the sample and the registration is a loss the callback never saw and never
        // reports, and the app would read "reachable" until the next change. Registration's own
        // `onAvailable` is queued behind this call on the handler, so it still lands in order.
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    current = network
                    onChange(true)
                }

                override fun onLost(network: Network) {
                    // On a switch (wifi → cellular) the new default can be announced before the
                    // old one's loss, so only losing the network we're on is losing the path.
                    if (network != current) return
                    current = null
                    onChange(false)
                }
            },
            Handler(Looper.getMainLooper()),
        )
        // Asked once up front: with no network at all the callback never fires, and the store's
        // default (reachable) would read "Connecting…" forever instead of "No internet connection".
        current = connectivity.activeNetwork
        onChange(current != null)
    }
}
