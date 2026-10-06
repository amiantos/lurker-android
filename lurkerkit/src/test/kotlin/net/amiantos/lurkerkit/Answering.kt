// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Port-only: shared by the suites that talk to a server (PendingRevokeTests, SettingsWriteTests),
// as LurkerKit's `LoopbackServer.swift` is. Where LurkerKit answers over a loopback socket, an
// interceptor answers here, on OkHttp's own threads, as a server's reply would.

/**
 * Answers every request with one status and body (or no answer at all), recording each. With a
 * [gate], each request waits for it to open before answering: the test decides how long a
 * request is in flight, not the clock.
 */
class Answering(val status: Int?, val body: String = "", val gate: CountDownLatch? = null) {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests.add("${request.method} ${request.url.encodedPath} ${request.header("Authorization")}")
            gate?.await(10, TimeUnit.SECONDS)
            if (status == null) throw IOException("no answer")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("canned")
                .body(body.toResponseBody(null))
                .build()
        }
        .build()
}
