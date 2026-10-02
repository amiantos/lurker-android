// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.client.bool
import net.amiantos.lurkerkit.client.int
import net.amiantos.lurkerkit.client.intOrNull
import net.amiantos.lurkerkit.client.string
import net.amiantos.lurkerkit.client.stringOrNull

/**
 * A network the add-network flow can offer to set up for you: one of the bundled catalogue,
 * or one this instance's admin defined (lurker#298).
 *
 * Deliberately one shape for both, so the picker merges them into a single list instead of
 * branching per row — the same call the web makes.
 */
data class NetworkPreset(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    /**
     * True when a client connecting from a datacenter IP likely needs SASL — Libera and
     * friends refuse unauthenticated cloud addresses. Advisory: it changes what the form
     * says, never what it sends.
     */
    val saslLikelyRequired: Boolean = false,
    /**
     * The network's own documented main channel, where we can name one. Absent is the honest
     * and common case: a wrong name lands a new user in a channel that doesn't exist, which
     * is worse than landing them nowhere.
     */
    val defaultChannel: String? = null,
    /**
     * An admin's recommended channels, on instance presets only. Plural, unlike a builtin's
     * single `defaultChannel`: an admin knows their own network and can reasonably say "join
     * #general and #random"; for a public network we only ever claim to know its one channel.
     */
    val recommendedChannels: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** True for an admin-defined preset. They pin above the builtins and carry a badge. */
    val isInstance: Boolean = false,
    /**
     * The `instance_network` row id, on instance presets only — a genuinely unique key for a
     * list, since nothing stops an admin listing the same host twice (two ports, or a TLS
     * and a plaintext entry).
     */
    val instanceID: Int? = null,
) {
    /**
     * The channels we can actually stand behind for this network, in the order to offer them.
     *
     * For an instance preset it's whatever the admin listed — they run the place, so their
     * word is the last word, and we don't second-guess it with #lurker. For a builtin it's
     * #lurker where there's an active one, then the network's own documented channel.
     * #lurker leads because a new user is better served by the room where they can get help
     * with the client than by a network's general chat.
     *
     * Port note: LurkerKit tells "the network's own channel is #lurker" with Foundation's
     * `caseInsensitiveCompare`; here it is `equals(ignoreCase = true)`, which folds one UTF-16
     * unit at a time. The only thing ever on the other side is `#lurker`, which is ASCII, and
     * over the catalogue and a corpus of odd spellings (the Kelvin sign for `k`, a long `ſ`,
     * a trailing combining mark) the two gave the same answer every time.
     */
    val suggestedChannels: List<String>
        get() {
            if (isInstance) return recommendedChannels
            val channels = mutableListOf<String>()
            if (tags.contains(BuiltinNetworks.lurkerTag)) channels.add(BuiltinNetworks.lurkerChannel)
            val own = defaultChannel
            if (own != null && channels.none { it.equals(own, ignoreCase = true) }) {
                channels.add(own)
            }
            return channels
        }

    /**
     * A draft prefilled from this preset — the whole point of the picker. Everything the
     * preset knows is filled in; the nick is what's left for the user.
     *
     * The channel list is seeded rather than left blank so a new user lands in a
     * conversation instead of an empty server log, which is the difference between the app
     * working and the app looking broken on first run. It's a prefilled, editable field, not
     * a silent join: where we know nothing, `#chat` is offered as the guess it is.
     */
    fun draft(): NetworkDraft {
        val draft = NetworkDraft(name = name, host = host, port = port, tls = tls)
        val channels = suggestedChannels
        return draft.copy(
            defaultChannel = if (channels.isEmpty()) {
                BuiltinNetworks.fallbackChannel
            } else {
                channels.joinToString(", ")
            },
        )
    }
}

/**
 * The bundled catalogue, and the two channel names the flow leans on.
 *
 * ⚠ The JSON is a **copy** of `vue_client/src/utils/builtinNetworks.json` in the lurker
 * repo, where it is hand-maintained (seeded from the netsplit.de top 100, connection details
 * verified per network). It will drift, and that's accepted: the alternative is an endpoint
 * shipping 24 KB the client could have had in its bundle. If you're updating one, update
 * both.
 *
 * Port note: and now a third copy — `src/main/resources/net/amiantos/lurkerkit/builtinNetworks.json`
 * here is byte for byte LurkerKit's `Resources/builtinNetworks.json`, which is itself the copy
 * the paragraph above describes.
 */
object BuiltinNetworks {
    /**
     * Networks carrying this tag have an active #lurker channel. A marker, not a browse
     * category — it floats a network to the top and picks its default channel.
     */
    const val lurkerTag = "lurker"
    const val lurkerChannel = "#lurker"

    /**
     * Where we know nothing about a network, this is the guess. It stays a *guess*: it is
     * only ever prefilled into a field the user reviews before connecting, never joined on
     * their behalf.
     */
    const val fallbackChannel = "#chat"

    /**
     * The catalogue, #lurker-friendly networks first and most-popular-first within each
     * group — so the picker's default order is meaningful before anyone types.
     */
    val all: List<NetworkPreset> = load()

    /**
     * Port note: `Bundle.module` in LurkerKit; here the JSON is a classpath resource, read
     * through the class loader, which finds it both on the host JVM and inside an Android app
     * (a JVM library's resources are packaged into the APK as Java resources).
     */
    private const val resource = "net/amiantos/lurkerkit/builtinNetworks.json"

    /** What `load` sorts: a named tuple in LurkerKit. */
    private data class Entry(val preset: NetworkPreset, val users: Int?)

    private fun load(): List<NetworkPreset> {
        val text = BuiltinNetworks::class.java.classLoader?.getResourceAsStream(resource)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return emptyList()
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return emptyList()
        }
        val rows = objects(parsed) ?: return emptyList()
        return rows
            .map { row ->
                Entry(
                    preset = NetworkPreset(
                        name = row.string("name"),
                        host = row.string("host"),
                        port = row.int("port", 6697),
                        tls = row.bool("tls", true),
                        saslLikelyRequired = row.bool("saslLikelyRequired"),
                        defaultChannel = row.stringOrNull("defaultChannel"),
                        tags = strings(row, "tags") ?: emptyList(),
                    ),
                    users = row.intOrNull("users"),
                )
            }
            // Port note: LurkerKit sorts with one "comes before" closure; this is the same
            // order as a comparator. `sortedWith` is stable, so entries that tie stay in file
            // order — which is what Swift's sort does too, though it does not promise to.
            .sortedWith { left, right ->
                val leftLurker = left.preset.tags.contains(lurkerTag)
                val rightLurker = right.preset.tags.contains(lurkerTag)
                if (leftLurker != rightLurker) {
                    if (leftLurker) -1 else 1
                } else {
                    (right.users ?: -1).compareTo(left.users ?: -1)
                }
            }
            .map { it.preset }
    }

    /**
     * Port note: Swift's `as? [[String: Any]]` on the parsed file — all-or-nothing, so one
     * element that isn't an object means no catalogue rather than a shorter one.
     */
    private fun objects(root: JsonElement): List<JsonObject>? {
        val array = root as? JsonArray ?: return null
        return array.map { it as? JsonObject ?: return null }
    }

    /**
     * Port note: Swift's `as? [String]`, all-or-nothing in the same way: one tag that isn't a
     * string and the row has no tags.
     */
    private fun strings(row: JsonObject, key: String): List<String>? {
        val array = row[key] as? JsonArray ?: return null
        return array.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        }
    }
}

/**
 * What `GET /api/network-presets` says: the networks this instance recommends, and whether
 * users may connect to anything else.
 */
data class NetworkPresets(
    val instance: List<NetworkPreset>,
    /**
     * ⚠ Permissive by default. An un-fetched or older server must not present itself as a
     * locked-down instance and hide the custom-server path — that would be an app that
     * can't add a network at all, which is the failure this whole issue is about.
     */
    val allowUserDefined: Boolean = true,
) {
    /**
     * Instance presets first — the admin's own networks are the ones their users came for —
     * then the catalogue, minus any host the admin already lists, so a locked-down instance
     * doesn't offer the same server twice under two names.
     */
    val offered: List<NetworkPreset>
        get() {
            // ⚠⚠ A locked-down instance offers its own networks and NOTHING else. The policy is
            // an allowlist of hosts (`networkPolicy.hostAllowedChecker`): with
            // `allowUserDefined` off, the enabled instance presets are the entire allowed set,
            // so every builtin here would be a row whose only outcome is a 403 — the same
            // mistake as offering Connect on a blocked network, one screen earlier.
            if (!allowUserDefined) return instance
            val claimed = instance.map { it.host.lowercase() }.toSet()
            return instance + BuiltinNetworks.all.filter { !claimed.contains(it.host.lowercase()) }
        }
}
