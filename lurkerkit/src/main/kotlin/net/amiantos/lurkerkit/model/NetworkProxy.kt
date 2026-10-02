// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * The proxy protocols Lurker speaks (lurker#303), matching `shared/proxy.ts`. No SOCKS4: the
 * server refuses it — no authentication, and anything running SOCKS4 also speaks SOCKS5.
 */
enum class ProxyType(val rawValue: String) {
    Socks5("socks5"),
    Http("http");

    /**
     * 1080 is the registered SOCKS port; 3128 is squid's, and what WeeChat defaults an HTTP
     * proxy to. The server uses the same two.
     */
    val defaultPort: Int
        get() = when (this) {
            Socks5 -> 1080
            Http -> 3128
        }

    companion object {
        fun fromRawValue(raw: String): ProxyType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A network's saved proxy, as `GET /api/networks` describes it (lurker#303).
 *
 * Parts rather than a URL, with the password reduced to `hasPassword` — the contract the
 * server and SASL passwords already have. That's what lets the form change a port without
 * making you retype the password.
 */
data class NetworkProxy(
    /** ⚠ The only part the dial reads. The rest can sit saved while the network dials direct. */
    val enabled: Boolean,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val hasPassword: Boolean = false,
)

/**
 * The proxy part of a network form.
 *
 * Port note: a form model the UI edits field by field — `var` properties in LurkerKit. Here it
 * is immutable and an edit is a `copy(…)` (PORTING.md, structs that mutate, case 2: its one
 * `mutating func`, `setType`, returns nothing).
 *
 * Port note: LurkerKit's initialiser takes `port: Int? = nil` and stores
 * `port ?? type.defaultPort`. Here the parameter is not nullable and DEFAULTS to
 * `type.defaultPort`, which is the same thing for every call that names a port or leaves it
 * out; only "pass an explicit nil" has no spelling.
 */
data class ProxyDraft(
    val enabled: Boolean = false,
    /**
     * Changed through `setType`, which brings an untouched default port along.
     *
     * Port note: ⚠ `private(set)` in LurkerKit, which a data class cannot say — `copy(type = …)`
     * compiles here and leaves the port behind. Nothing enforces it: go through `setType`.
     */
    val type: ProxyType = ProxyType.Socks5,
    val host: String = "",
    val port: Int = type.defaultPort,
    val username: String? = null,
    val password: SecretEdit = SecretEdit.Unchanged,
) {
    /** A draft of a saved proxy. The password starts `unchanged` — it was never sent to us. */
    constructor(editing: NetworkProxy) : this(
        enabled = editing.enabled,
        type = editing.type,
        host = editing.host,
        port = editing.port,
        username = editing.username,
    )

    /**
     * Change the protocol. The port follows only if it was still the old protocol's default:
     * otherwise picking HTTP would keep SOCKS's 1080 without saying so, and a port the user
     * typed has to survive the change.
     *
     * Port note: returns the updated copy rather than mutating in place
     * (`proxy = proxy.setType(next)`).
     */
    fun setType(next: ProxyType): ProxyDraft =
        copy(type = next, port = if (port == type.defaultPort) next.defaultPort else port)
}
