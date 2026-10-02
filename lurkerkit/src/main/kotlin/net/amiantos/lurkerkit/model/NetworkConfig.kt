// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.trimmingWhitespacesAndNewlines
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One row of `GET /api/networks` — the network as it is *configured*, which is a different
 * object from the network as it is *rendered* (`Network`).
 *
 * ⚠⚠ Deliberately not merged into `Network`. That one is the roster the store holds: id,
 * name, connection state, nick, away — read on the hot path of every frame the reducer
 * touches, and kept small for that reason. This one is a form's backing model, fetched when
 * someone opens the networks screen and thrown away when they leave. Folding them together
 * would put hostnames and credential flags in the message path, and would make the frame
 * reducer responsible for fields no frame carries.
 *
 * Of the `channels` array the endpoint also returns, only each channel's stored key is read
 * (`channelKeys`) — the channel settings screen's key field (lurker#727). The form edits
 * autojoin channels only at create time, via `default_channel`.
 */
data class NetworkConfig(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    /**
     * Whether the TLS certificate has to be a valid, trusted one.
     *
     * ⚠⚠ It reads like a permission to accept anything and it is the opposite: the server
     * passes it straight to `rejectUnauthorized` (`ircConnection.ts:4040`, and the column
     * defaults to 1), so **true means verify**. Turning it off is what lets a self-signed
     * certificate through. The default therefore has to be true — a draft that defaulted it
     * false would silently disable certificate verification on every network created from
     * this app, which is a security decision no default gets to make on the user's behalf.
     */
    // True = verify, matching the column's own default. See the property's note.
    val trustedCertificates: Boolean = true,
    val nick: String,
    val username: String? = null,
    val realname: String? = null,
    val autoconnect: Boolean = false,
    val saslAccount: String? = null,
    /** Raw lines sent after registration, newline-separated, as the server stores them. */
    val connectCommands: String? = null,
    /**
     * Whether a server password is *set*. The password itself is never returned — see
     * `SecretEdit` for what that costs a form that wants to edit it.
     */
    val hasPassword: Boolean = false,
    /** Whether a SASL password is set. Same rules as `hasPassword`. */
    val hasSaslPassword: Boolean = false,
    /**
     * True when the instance admin's allowlist excludes this network's host (lurker#298).
     *
     * The row survives untouched — it just can't connect — so this is the difference
     * between a Connect button that fails with a reason and one that appears to do nothing.
     * The server refuses the connect itself; this only lets the client say why first.
     */
    val blocked: Boolean = false,
    /**
     * The TLS client certificate this network presents (CertFP, lurker#459), or null when it
     * has none.
     */
    val clientCertificate: ClientCertificate? = null,
    /**
     * The proxy details saved for this network (lurker#303), or null when none ever were. Not
     * the same as a proxy that's switched off, whose details stay saved while the network dials
     * direct.
     */
    val proxy: NetworkProxy? = null,
    /**
     * The key the server holds for each channel, by lowercased name — the one it joins with,
     * kept current by every live `±k`. Only channels that have one are listed.
     *
     * ⚠⚠ The ONLY place a channel key reaches this client: channel state and every broadcast
     * carry the `k` letter alone. Read-only here; nothing writes it back.
     */
    val channelKeys: Map<String, String> = emptyMap(),
) {
    /**
     * The stored key for `channel`, if there is one.
     *
     * Port note: `lowercase()` writes a word-final `Σ` as `ς`, the way the server's JavaScript
     * does; Swift's `lowercased()` writes `σ`. The map's keys are lowercased by this same
     * function on the way in, so a name always finds its own key on either platform — the two
     * differ only in whether `#σας` and `#σασ` are the same channel.
     */
    fun key(channel: String): String? = channelKeys[channel.lowercase()]
}

/**
 * What a form is asking to happen to one stored secret.
 *
 * ⚠⚠ Passwords are never returned by the API — a row carries `has_password`, not the
 * password. So an empty text field is ambiguous in exactly the way that matters: it is both
 * what "leave the existing password alone" looks like and what "remove the password" looks
 * like. A form that sent the field's contents on every save would silently clear a password
 * the user never touched.
 *
 * Three states, so the ambiguity can't exist: `unchanged` omits the key from the body
 * entirely (the server patches only what it's given), `set` sends the new value, and
 * `cleared` sends null. The UI owes the user a visible way to reach `cleared` — an empty
 * field is not it.
 *
 * Port note: the `set` case keeps its name, which puts a `Set` inside this interface; within
 * its body the builtin would be written `kotlin.collections.Set`. Nothing outside is affected.
 */
sealed interface SecretEdit {
    data object Unchanged : SecretEdit
    data class Set(val value: String) : SecretEdit
    data object Cleared : SecretEdit
}

/**
 * The body of a create or update. Every non-secret field is always sent: the form shows all
 * of them, so "what's on screen" and "what's stored" are the same set, and a partial PATCH
 * would only reintroduce the question of which fields the form is authoritative for.
 *
 * The proxy is the exception: it's sent only when the user changed something about it. See
 * `applyProxy`.
 *
 * `defaultChannel` is create-only, matching the server: it seeds autojoin rows rather than
 * updating a column, and there is nothing for it to mean on an edit. So is `certificate` —
 * see its note.
 *
 * Port note: a form model the UI edits field by field — every property is a `var` in
 * LurkerKit. It has no `mutating` method, so here it is an immutable `data class` and an edit
 * is a `copy(…)` (PORTING.md, structs that mutate, case 1): `draft = draft.copy(tls = false)`,
 * and for the nested proxy `draft = draft.copy(proxy = draft.proxy.edited(port = 9150))`.
 */
data class NetworkDraft(
    val name: String = "",
    val host: String = "",
    val port: Int = 6697,
    val tls: Boolean = true,
    // ⚠⚠ True = verify the certificate. See `NetworkConfig.trustedCertificates`: this
    // reads like the permissive option and is the strict one, and the server's own
    // default for a new network is the same. Matches the web's add form.
    val trustedCertificates: Boolean = true,
    val nick: String = "",
    val username: String? = null,
    val realname: String? = null,
    val autoconnect: Boolean = true,
    val saslAccount: String? = null,
    val connectCommands: String? = null,
    val password: SecretEdit = SecretEdit.Unchanged,
    val saslPassword: SecretEdit = SecretEdit.Unchanged,
    /**
     * Comma- or whitespace-separated channel list, create only. The server accepts both
     * separators (`parseChannelList`), matching IRC's own `JOIN #a,#b` syntax.
     */
    val defaultChannel: String? = null,
    /** The proxy section (lurker#303). */
    val proxy: ProxyDraft = ProxyDraft(),
    /**
     * The proxy the network being edited has saved, as it was read. Set by the `editing`
     * constructor.
     *
     * What `proxy` is measured against, so a proxy nobody touched isn't sent at all.
     */
    val savedProxy: NetworkProxy? = null,
    /**
     * A certificate to attach as the network is created (CertFP, lurker#459). Create only.
     *
     * It rides the create request rather than following it because the server attaches it
     * BEFORE the first dial, and that first connection is the one the user registers the
     * certificate from. Attached afterwards, it would miss it. An edit uses the certificate
     * routes instead.
     */
    val certificate: CertificateSource? = null,
) {
    /**
     * A draft pre-filled from a stored row, for the edit form. Every secret starts
     * `unchanged` — the values were never sent to us, so anything else would be a guess.
     */
    constructor(editing: NetworkConfig) : this(
        name = editing.name,
        host = editing.host,
        port = editing.port,
        tls = editing.tls,
        trustedCertificates = editing.trustedCertificates,
        nick = editing.nick,
        username = editing.username,
        realname = editing.realname,
        autoconnect = editing.autoconnect,
        saslAccount = editing.saslAccount,
        connectCommands = editing.connectCommands,
        proxy = editing.proxy?.let { ProxyDraft(editing = it) } ?: ProxyDraft(),
        savedProxy = editing.proxy,
    )

    /**
     * Whether a save has anything to say about the proxy: a saved one changed, or a new one
     * switched on.
     *
     * Port note: Swift compares the two drafts' strings by canonical equivalence, so there a
     * host or username retyped in a different normalisation (`é` as one code point or as
     * `e` + U+0301) is not an edit. Here it is one, and the proxy is sent.
     */
    private val proxyIsEdited: Boolean
        get() {
            val savedProxy = savedProxy ?: return proxy.enabled
            return proxy != ProxyDraft(editing = savedProxy)
        }

    /**
     * Why this draft can't be sent, or null when it can.
     *
     * ⚠⚠ Enforced here, not only in the form. `POST` is validated server-side — 400 on a
     * missing name, host or nick — but `PATCH` is **not**: it sets whatever keys it is
     * given. So an edit that blanks the name stores an empty one, and the roster then reads
     * it back as no name at all — which is lurker-ios#136's "we haven't heard the name" state,
     * so the network renders as "Unnamed network" and re-triggers the roster read for good.
     * This layer is the only guard both paths share, and a gate you have to remember to apply
     * isn't one.
     */
    val validationError: String?
        get() {
            if (trimmed(name).isEmpty()) return "Give this network a name."
            if (trimmed(host).isEmpty()) return "A server address is required."
            if (trimmed(nick).isEmpty()) return "A nickname is required."
            if (port !in 1..65535) return "Port must be between 1 and 65535."
            // Only a proxy that will be sent: edited and on. An untouched saved one goes nowhere,
            // whatever its columns hold. The server checks the rest (credentials, a space in the
            // address) against the row as it will be stored.
            if (proxyIsEdited && proxy.enabled) {
                if (trimmed(proxy.host).isEmpty()) return "A proxy needs an address."
                if (proxy.port !in 1..65535) return "Proxy port must be between 1 and 65535."
            }
            if (certificate != null && !tls) return "A client certificate needs TLS."
            return null
        }

    /**
     * The JSON body for `POST /api/networks` (`creating`) or `PATCH /api/networks/:id`.
     *
     * `creating` adds the create-only keys: the default channels and a staged certificate.
     * Secrets appear only when the user actually decided something about them: `unchanged`
     * omits the key, `cleared` sends an explicit null (which is what the column stores for "no
     * password", so null is a value here and not an absence).
     */
    fun jsonBody(creating: Boolean): JsonObject = buildJsonObject {
        // Trimmed on the way out, so a name that is only spaces can't slip past a check that
        // read it untrimmed — and so a host with a stray trailing space isn't a host nothing
        // resolves.
        put("name", trimmed(name))
        put("host", trimmed(host))
        put("port", port)
        put("tls", tls)
        put("trusted_certificates", trustedCertificates)
        put("nick", trimmed(nick))
        put("autoconnect", autoconnect)
        // Optional text fields send null rather than "" when empty: the column is nullable and
        // the server's own defaults (username from nick, realname from nick) key off null, so
        // an empty string would store a real, empty value and defeat them.
        put("username", if (!username.isNullOrEmpty()) JsonPrimitive(username) else JsonNull)
        put("realname", if (!realname.isNullOrEmpty()) JsonPrimitive(realname) else JsonNull)
        put("sasl_account", if (!saslAccount.isNullOrEmpty()) JsonPrimitive(saslAccount) else JsonNull)
        put("connect_commands", if (!connectCommands.isNullOrEmpty()) JsonPrimitive(connectCommands) else JsonNull)
        apply(password, "server_password", this)
        apply(saslPassword, "sasl_password", this)
        if (creating) {
            if (!defaultChannel.isNullOrEmpty()) {
                put("default_channel", defaultChannel)
            }
            when (certificate) {
                CertificateSource.Generate ->
                    put("generate_client_cert", true)
                is CertificateSource.Imported -> {
                    put("client_cert", certificate.cert)
                    put("client_key", certificate.key)
                }
                null -> {}
            }
        }
        applyProxy(this)
    }

    /**
     * The proxy columns, only when the proxy was edited (`proxyIsEdited`).
     *
     * ⚠⚠ Untouched, nothing is sent — not even what was read. The form shows the columns
     * normalized (trimmed, and an unknown type or impossible port read as a default, since
     * archive import writes them verbatim), so resending what's shown would rewrite them on a
     * rename, and a locked-down instance refuses a rename that changes a proxy.
     *
     * Edited and on, all of it goes, as shown. Edited and off, only the switch, since the
     * details aren't on screen — and off only counts as an edit for a saved proxy.
     */
    private fun applyProxy(body: JsonObjectBuilder) {
        if (!proxyIsEdited) return
        if (!proxy.enabled) {
            body.put("proxy_enabled", false)
            return
        }
        body.put("proxy_enabled", true)
        body.put("proxy_type", proxy.type.rawValue)
        body.put("proxy_host", trimmed(proxy.host))
        body.put("proxy_port", proxy.port)
        val username = trimmed(proxy.username ?: "")
        body.put("proxy_username", if (username.isEmpty()) JsonNull else JsonPrimitive(username))
        apply(proxy.password, "proxy_password", body)
    }

    private companion object {
        /**
         * Foundation's set, not `trim()`'s: it takes a zero-width space, the one a host name
         * pasted out of a web page can carry.
         */
        fun trimmed(value: String): String = value.trimmingWhitespacesAndNewlines()

        fun apply(edit: SecretEdit, key: String, body: JsonObjectBuilder) {
            when (edit) {
                SecretEdit.Unchanged -> {}
                is SecretEdit.Set -> body.put(key, edit.value)
                SecretEdit.Cleared -> body.put(key, JsonNull)
            }
        }
    }
}

/**
 * The outcome of creating or updating a network.
 *
 * The saved row comes back rather than just a success flag: the server fills in what the
 * draft left out (an omitted username defaults from the nick) and normalizes what it was
 * given, so the row it returns is the one the screen should show. Re-listing to find that
 * out would be a round trip for something the reply already carried.
 */
sealed interface NetworkSaveResult {
    data class Saved(val config: NetworkConfig) : NetworkSaveResult

    /**
     * The write landed but its reply couldn't be read.
     *
     * ⚠ Its own case rather than a `failure` with careful wording, because the distinction
     * is one the caller has to *act* on, not report: a form that treats this as a refusal
     * keeps itself open with Save re-enabled, and the next tap creates the network a second
     * time. Prose in a message string cannot stop that. Dismiss on this, the way you would
     * on `saved`; the roster has already been re-read, so the network is there to see.
     */
    data object SavedWithoutDetail : NetworkSaveResult

    /**
     * The server's own wording wherever it gave any — a blocked host, a missing field, a
     * paused account — because it knows why it refused and this client is guessing.
     */
    data class Failure(val message: String) : NetworkSaveResult
}
