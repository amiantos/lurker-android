// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import net.amiantos.lurkerkit.model.LocalNetworking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * The one place plain `http` is refused on this platform: an OkHttp interceptor on the client
 * `LurkerClient.bearerOnlyConfiguration` builds, which every other client in the kit derives from
 * (`newBuilder()` keeps interceptors), so REST, the WebSocket upgrade, uploads, revokes and the
 * media session all pass through it.
 *
 * Port-only. On iOS App Transport Security refuses cleartext to anything but the local network no
 * matter what the app asks for, so a request the sign-in gate let through can't leave the device.
 * Android's network security config can't say "private addresses only" — it matches domain
 * names, not address ranges — so the app permits cleartext wholesale and this is the backstop
 * the OS isn't: whatever the sign-in gate (`ServerAddress`), the playback gate
 * (`playableMediaURL`) or a server-minted media address admit, nothing cleartext reaches a public
 * host from here. All three ask `LocalNetworking` the same question, so they can't disagree.
 *
 * An application interceptor, not a network one: it runs before the cache, so a refused address
 * is never looked up in it, and before any connection is made.
 */
internal object CleartextPolicy : Interceptor {
    /** Whether a request to [url] may go out: HTTPS anywhere, plain http only to the local network. */
    fun permits(url: HttpUrl): Boolean = url.isHttps || LocalNetworking.permitsCleartext(url.host)

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!permits(url)) throw IOException("Plain http is only allowed to a private address (${url.host}).")
        return chain.proceed(chain.request())
    }
}
