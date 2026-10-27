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
 * Installed as an application interceptor AND a network interceptor. The first runs once per call,
 * before the cache, so a refused address is never looked up in it and no connection is made; the
 * second runs once per hop, which is the only place a redirect's follow-up passes — OkHttp builds
 * it below the application interceptors, so an `https://` answer of `302 Location: http://…` to a
 * public host would otherwise be followed in the clear, Bearer header and all on a same-host hop.
 * ⚠ Neither layer sees a WebSocket's follow-up (OkHttp runs no network interceptors for a socket
 * call), so the socket's client follows no redirects at all — `LurkerClient.socketConfiguration`.
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
