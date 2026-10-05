// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ChannelModeState
import net.amiantos.lurkerkit.model.ClientCertificate
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.DraftEntry
import net.amiantos.lurkerkit.model.DraftReply
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.FavoriteEntry
import net.amiantos.lurkerkit.model.FeedCursor
import net.amiantos.lurkerkit.model.FeedReaction
import net.amiantos.lurkerkit.model.HighlightItem
import net.amiantos.lurkerkit.model.HighlightsPage
import net.amiantos.lurkerkit.model.ISOTime
import net.amiantos.lurkerkit.model.IgnorePatternKind
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.ModeChange
import net.amiantos.lurkerkit.model.ModeChangeKind
import net.amiantos.lurkerkit.model.ModeListEntry
import net.amiantos.lurkerkit.model.ModeSpec
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkPreset
import net.amiantos.lurkerkit.model.NetworkPresets
import net.amiantos.lurkerkit.model.NetworkProxy
import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.PrefixMode
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.model.ReactionChange
import net.amiantos.lurkerkit.model.RelayBot
import net.amiantos.lurkerkit.model.ReplyContext
import net.amiantos.lurkerkit.model.ReplyParent
import net.amiantos.lurkerkit.model.SettingDependency
import net.amiantos.lurkerkit.model.SettingOption
import net.amiantos.lurkerkit.model.SettingType
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Speaker
import net.amiantos.lurkerkit.model.SystemLevel
import net.amiantos.lurkerkit.model.TypingActivity
import net.amiantos.lurkerkit.model.UploadItem
import net.amiantos.lurkerkit.model.UploadsPage
import net.amiantos.lurkerkit.model.WhoisResult
import net.amiantos.lurkerkit.support.graphemeBoundaries
import net.amiantos.lurkerkit.support.swiftInt
import net.amiantos.lurkerkit.support.utf8OrNull
import okio.ByteString
import java.time.Instant

/**
 * Turns raw server JSON into typed `ServerFrame`s. The one place that knows the wire
 * format. Kinds the 1.0 foundation doesn't consume yet parse to `Ignored` rather
 * than failing. Pure and synchronous, so it runs on whatever thread the socket
 * callback arrives on.
 */
internal object FrameParser {

    /** Parse one WS text frame (discriminated by `kind`). */
    fun parseWs(text: String): ServerFrame {
        val obj = `object`(text) ?: return ServerFrame.Ignored
        when (obj.string("kind")) {
            "snapshot" -> return parseSnapshot(obj)
            // Carries no state of its own — its arrival *is* the message.
            "backlog-complete" -> return ServerFrame.BacklogComplete
            "backlog" -> return parseBacklog(obj)
            "history" -> return parseHistory(obj)
            "irc" -> return parseLive(obj)
            "read-state" -> {
                val target = obj.string("target")
                return if (target.isEmpty()) {
                    ServerFrame.Ignored
                } else {
                    ServerFrame.ReadState(
                        networkId = obj.intOrNull("networkId"),
                        target = target,
                        lastReadId = obj.long("lastReadId"),
                        unread = obj.int("unread"),
                        highlights = obj.int("highlights"),
                    )
                }
            }
            "send-result" ->
                return ServerFrame.SendResult(
                    clientId = obj.stringOrNull("clientId"),
                    ok = obj.bool("ok"),
                    error = obj.stringOrNull("error"),
                )
            "settings" ->
                // `changes` carries only what moved. An empty object is legal (the server sends
                // `changes || {}`) and simply patches nothing.
                //
                // The upload limits are present only when one of them was touched — absent means
                // unchanged, which is why they stay nullable all the way to the store.
                return ServerFrame.SettingsChanged(
                    changes = parseSettingValues(obj["changes"]),
                    uploadLimits = advertisedUploadLimits(obj),
                )
            "buffer-cleared" -> {
                // The `/clear` marker's fan-out — this device's own ack AND every other device's
                // notice, which is the whole reason the marker is server-side (lurker-ios#121).
                val target = obj.string("target")
                val marker = clearedMarker(obj)
                return if (target.isEmpty()) {
                    ServerFrame.Ignored
                } else {
                    ServerFrame.BufferCleared(
                        networkId = obj.intOrNull("networkId"),
                        target = target,
                        clearedBeforeId = marker.beforeId,
                        clearedAt = marker.at,
                    )
                }
            }
            "error" -> return ServerFrame.ServerError(obj.string("text"))
            "favorites-changed" ->
                // The FULL global order, replace wholesale — the same frame seeds the
                // connect burst, so this one handler covers seed and every correction.
                return ServerFrame.FavoritesChanged(
                    obj.objects("favorites").map { entry ->
                        FavoriteEntry(
                            networkId = entry.int("networkId"),
                            target = entry.string("target"),
                            bufferId = entry.int("bufferId"),
                        )
                    },
                )
            "draft-snapshot" ->
                return ServerFrame.DraftSnapshot(obj.objects("drafts").mapNotNull(::parseDraftEntry))
            "draft-updated" ->
                return parseDraftEntry(obj)?.let { ServerFrame.DraftUpdated(it) } ?: ServerFrame.Ignored
            "bookmark-updated" -> {
                // The fan-out for a save/unsave made anywhere on the account, including this
                // device — the server echoes to every socket, so it's the one source of truth
                // and nothing renders a toggle optimistically. A zero id can't address a row.
                val messageId = obj.long("messageId")
                return if (messageId == 0L) {
                    ServerFrame.Ignored
                } else {
                    ServerFrame.BookmarkUpdated(messageId = messageId, saved = obj.bool("saved"))
                }
            }
            "reaction" -> {
                // Its own frame rather than an `irc` row (wsHub): no decoration, no unread bump, and
                // it never reopens a closed buffer. A frame that can't name its line, its network or
                // its value addresses nothing.
                val messageId = obj.long("messageId")
                val value = obj.string("value")
                if (messageId == 0L) return ServerFrame.Ignored
                val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
                if (value.isEmpty()) return ServerFrame.Ignored
                return ServerFrame.Reaction(
                    ReactionChange(
                        networkId = networkId,
                        target = obj.string("target"),
                        messageId = messageId,
                        nick = obj.string("nick"),
                        value = value,
                        isSelf = obj.bool("self"),
                        remove = obj.bool("remove"),
                        toSelf = obj.bool("toSelf"),
                    ),
                )
            }
            "reactions-sync" -> {
                // ⚠ Only ids the frame NAMES are authoritative, so a frame without a readable list
                // says nothing — not "every line has none".
                val ids = obj.longs("messageIds") ?: return ServerFrame.Ignored
                val reactions = mutableMapOf<Long, List<MessageReaction>>()
                for ((key, value) in (obj["reactions"] as? JsonObject) ?: emptyMap()) {
                    val id = swiftInt(key) ?: continue
                    val list = value.asObjects() ?: continue
                    val parsed = parseReactions(list)
                    if (parsed.isNotEmpty()) reactions[id] = parsed
                }
                return ServerFrame.ReactionsSync(messageIds = ids, reactions = reactions)
            }
            "upload-progress" -> {
                // Same trust posture as its siblings. A frame with no token can't be matched to
                // the upload it describes, and an unrecognized phase is a server saying something
                // this build has no rendering for — in both cases the readout is better off on the
                // indeterminate fallback it already shows than acting on a payload we can't read.
                //
                // `percent` is legitimately null (the phase has no number), which is NOT the same
                // as absent-and-therefore-zero: `int()` would read a missing key as 0% and freeze
                // the bar there for the whole send. `intOrNull` keeps the two apart.
                val token = obj.stringOrNull("token") ?: return ServerFrame.Ignored
                val phase = UploadServerProgress.Phase.fromRawValue(obj.string("phase"))
                    ?: return ServerFrame.Ignored
                return ServerFrame.UploadProgress(
                    token = token,
                    progress = UploadServerProgress(
                        phase = phase,
                        percent = obj.intOrNull("percent"),
                        destination = obj.stringOrNull("destination"),
                    ),
                )
            }
            "ignore-list-updated" -> {
                // A frame with no usable `masks` array is dropped rather than read as "this scope
                // now has no rules". `objects()` answers an empty list for a missing, null or
                // mistyped key, and the store treats the payload as complete-for-that-scope — so
                // without this guard one malformed frame silently deletes every rule in the
                // bucket, live, and nothing re-seeds them short of a reconnect. A hide feature
                // must not fail open. Both siblings in this `when` refuse a payload they can't
                // trust the same way (`buffer-closed` on an empty target).
                //
                // The check is on the raw value, not on emptiness: `masks: []` is a legitimate
                // "the last rule was removed" and has to keep working.
                if (obj["masks"] !is JsonArray) return ServerFrame.Ignored
                // `networkId` is nullable and its null means the GLOBAL bucket — not the system
                // buffer, which is what a null networkId means on every other frame. `intOrNull`
                // keeps the two apart; `int()` would fold global onto network 0.
                return ServerFrame.IgnoreListUpdated(
                    networkId = obj.intOrNull("networkId"),
                    rules = obj.objects("masks").map(::parseIgnoreRule),
                )
            }
            "relay-bot-updated" -> {
                // A mark is about one connection and one nick; neither can be inferred, and a frame
                // missing either would key a mark on nothing (network 0, or the empty nick — which
                // would then "match" every nick-less line). Refused rather than folded, the same
                // posture the siblings above take toward a payload they can't trust.
                val nick = obj.string("nick")
                val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
                if (nick.isEmpty()) return ServerFrame.Ignored
                return ServerFrame.RelayBotUpdated(
                    networkId = networkId,
                    nick = nick,
                    marked = obj.bool("marked"),
                    pattern = obj.string("pattern"),
                )
            }
            "nick-note-updated" -> {
                // Refused on a missing half for the same reason as the relay mark above: a note
                // keyed on network 0, or on the empty nick, is a note about nobody that every
                // nick-less lookup would then find.
                val noteNick = obj.string("nick")
                val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
                if (noteNick.isEmpty()) return ServerFrame.Ignored
                // ⚠⚠ `note` must be PRESENT, not merely readable. An empty note is a delete, and
                // `string("note")` folds a missing or non-string field to `""` — so a malformed
                // frame would silently destroy something the user typed. An absent field is not a
                // statement that the note is empty (the same rule `InstanceFeatures` follows), and
                // the asymmetry decides it: refusing costs a missed update, accepting costs the
                // note. A real clear still passes, because `""` is present.
                if (!obj.has("note")) return ServerFrame.Ignored
                val note = obj["note"].asString() ?: return ServerFrame.Ignored
                return ServerFrame.NickNoteUpdated(
                    networkId = networkId,
                    nick = noteNick,
                    note = note,
                    updatedAt = ISOTime.parse(obj.stringOrNull("updatedAt")),
                )
            }
            "buffer-renamed" -> {
                // Same trust posture as buffer-closed below: empty names can't
                // identify anything, so refuse rather than rename an arbitrary
                // buffer. networkId stays null-distinct for the same BufferKey
                // reason.
                val from = obj.string("from")
                val to = obj.string("to")
                return if (from.isEmpty() || to.isEmpty()) {
                    ServerFrame.Ignored
                } else {
                    ServerFrame.BufferRenamed(
                        networkId = obj.intOrNull("networkId"),
                        from = from,
                        to = to,
                        bufferId = obj.intOrNull("bufferId"),
                        merged = obj.bool("merged"),
                        mergedFromBufferId = obj.intOrNull("mergedFromBufferId"),
                    )
                }
            }
            "pins-changed" -> {
                // `pinned` is the ordered target list; `pinnedIds` rides alongside it,
                // parallel-indexed, and is ignored here — this client addresses buffers by
                // (networkId, target) everywhere else, and reading both would be two spellings of
                // one list to keep in step.
                val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
                return ServerFrame.PinsChanged(networkId = networkId, pinned = obj.strings("pinned") ?: emptyList())
            }
            "buffer-closed" -> {
                // `networkId` is genuinely nullable here (the system buffer), so read it as
                // nullable rather than defaulting to 0 — `intOrNull` keeps a null distinct from
                // network 0 the way BufferKey needs. An empty target can't identify a buffer.
                val target = obj.string("target")
                return if (target.isEmpty()) {
                    ServerFrame.Ignored
                } else {
                    ServerFrame.BufferClosed(networkId = obj.intOrNull("networkId"), target = target)
                }
            }
            else -> return ServerFrame.Ignored
        }
    }

    /**
     * Parse REST `GET /api/networks` into the roster (id → name).
     *
     * ⚠⚠ An unreadable body is `Ignored`, NOT an empty roster. `applyNetworks` is
     * authoritative over membership — it removes networks the list doesn't name — so
     * reporting "we couldn't read the answer" as "you have no networks" would wipe every
     * network the user has, buffers included.
     */
    fun parseNetworks(body: String): ServerFrame {
        val obj = `object`(body) ?: return ServerFrame.Ignored
        // REST carries no live state; the WS snapshot fills state/nick in.
        // `position` is the user's own ordering, which this endpoint is already sorted by.
        // Read rather than inferred from the array index: the array is a snapshot of one
        // response, and the store holds networks in a map that has no order at all.
        // Absent (an older server) reads as `Int.MAX_VALUE`, so those networks sort last instead
        // of all colliding at 0 and re-sorting by id.
        // `blocked` is the third REST-only field, read as `parseNetworkConfigs` reads it: absent
        // means "not blocked", the honest answer on a server with no allowlist.
        val networks = obj.objects("networks").map {
            Network(
                id = it.int("id"),
                name = it.stringOrNull("name"),
                position = it.int("position", Int.MAX_VALUE),
                blocked = it.bool("blocked"),
            )
        }
        return ServerFrame.Networks(networks)
    }

    /**
     * Parse REST `GET /api/networks` into the editable configuration rows (lurker-ios#11).
     *
     * Same response as `parseNetworks` above, read for a different purpose: that one takes
     * the two fields the roster needs, this one takes everything the networks screen and its
     * form do. Two readers rather than one union type, because the roster is reduced into
     * long-lived state on every connect and this is fetched, shown, and dropped.
     * ⚠ Null for an unreadable body, never an empty list — the same distinction
     * `parseNetworks` draws, for the caller's benefit rather than the store's. "No networks"
     * is a real answer and the screen's empty state invites you to add your first one;
     * "we couldn't read the reply" is an error. An empty list for both would show a fresh
     * account's welcome to someone whose request failed.
     */
    fun parseNetworkConfigs(body: String): List<NetworkConfig>? {
        val obj = `object`(body) ?: return null
        return obj.objects("networks").map(::parseNetworkConfig)
    }

    fun parseNetworkConfig(obj: JsonObject): NetworkConfig =
        NetworkConfig(
            id = obj.int("id"),
            name = obj.string("name"),
            host = obj.string("host"),
            // A row predating a port column, or one a hand-written client POSTed without one,
            // reads 0 — which is not a port. The server's own default is the honest stand-in.
            port = if (obj.int("port") == 0) 6697 else obj.int("port"),
            tls = obj.bool("tls"),
            // ⚠ Defaults TRUE, unlike every other flag here. It means `rejectUnauthorized`,
            // so an absent value read as false would report a network as not verifying its
            // certificate when the server's column says it does — and the form would then
            // save that misreading back.
            trustedCertificates = obj.bool("trusted_certificates", true),
            nick = obj.string("nick"),
            username = obj.stringOrNull("username"),
            realname = obj.stringOrNull("realname"),
            autoconnect = obj.bool("autoconnect"),
            saslAccount = obj.stringOrNull("sasl_account"),
            connectCommands = obj.stringOrNull("connect_commands"),
            hasPassword = obj.bool("has_password"),
            hasSaslPassword = obj.bool("has_sasl_password"),
            // Absent on a server older than the allowlist (lurker#298), and absent must read as
            // "not blocked": an older server has no allowlist to be excluded from, and
            // defaulting the other way would grey out every network on it.
            blocked = obj.bool("blocked"),
            clientCertificate = parseClientCertificate(obj["client_cert"]),
            proxy = parseProxy(obj["proxy"]),
            // Port note: `lowercase()` applies final sigma where Swift's `lowercased()` does not,
            // so a channel named `#ΟΔΟΣ` is keyed `#οδος` here and `#οδοσ` on iOS. Whoever looks
            // a key up folds the same way, so the two never meet.
            channelKeys = buildMap {
                for (channel in obj.objects("channels")) {
                    val name = channel.string("name")
                    val key = channel.stringOrNull("key")
                    if (name.isNotEmpty() && key != null) put(name.lowercase(), key)
                }
            },
        )

    /** One network row from a create/update reply — `{network: {...}}`. */
    fun parseNetworkReply(body: String): NetworkConfig? {
        val obj = `object`(body) ?: return null
        val row = obj["network"] as? JsonObject ?: return null
        return parseNetworkConfig(row)
    }

    /**
     * `client_cert` on a network row (lurker#459). Null when it's null or missing — no
     * certificate — and `Unusable` when one is attached that the server couldn't parse.
     */
    fun parseClientCertificate(value: JsonElement?): ClientCertificate? {
        val obj = value as? JsonObject ?: return null
        if (obj.bool("unusable")) return ClientCertificate.Unusable
        return ClientCertificate.Usable(expires = ISOTime.parse(obj.stringOrNull("validTo")))
    }

    /**
     * `proxy` on a network row (lurker#303). Null when it's null or missing: no proxy details
     * were ever saved.
     */
    fun parseProxy(value: JsonElement?): NetworkProxy? {
        val obj = value as? JsonObject ?: return null
        // The columns hold whatever was written — archive import writes them verbatim — so an
        // unknown type or an impossible port reads as a default rather than failing the row.
        // The default is only shown: nothing is saved over the columns until the proxy itself
        // is edited (`NetworkDraft.applyProxy`).
        val type = ProxyType.fromRawValue(obj.string("type").lowercase()) ?: ProxyType.Socks5
        val port = obj.int("port")
        return NetworkProxy(
            enabled = obj.bool("enabled"),
            type = type,
            host = obj.string("host"),
            port = if (port in 1..65535) port else type.defaultPort,
            username = obj.stringOrNull("username"),
            hasPassword = obj.bool("has_password"),
        )
    }

    /**
     * Parse REST `GET /api/network-presets` — the networks this instance recommends, plus
     * whether users may connect to anything else (lurker#298).
     *
     * ⚠ `allowUserDefined` defaults TRUE on a missing key. A server predating the lockdown
     * has no such policy, and reading its silence as "locked down" would hide the
     * custom-server path on every older instance — leaving an app that can't add a network,
     * which is the whole failure lurker-ios#11 exists to fix.
     */
    fun parseNetworkPresets(body: String): NetworkPresets? {
        val obj = `object`(body) ?: return null
        return NetworkPresets(
            instance = obj.objects("presets").map { preset ->
                NetworkPreset(
                    name = preset.string("name"),
                    host = preset.string("host"),
                    port = preset.int("port", 6697),
                    tls = preset.bool("tls", true),
                    saslLikelyRequired = preset.bool("saslLikelyRequired"),
                    recommendedChannels = preset.strings("channels") ?: emptyList(),
                    isInstance = true,
                    instanceID = preset.intOrNull("id"),
                )
            },
            allowUserDefined = obj.bool("allowUserDefined", true),
        )
    }

    /**
     * Parse REST `GET /api/highlights` into a page. Each item is a `MessageEvent` spread
     * flat (so `parseEvent` reads it, same as a backlog line) plus the buffer address
     * (`networkId`/`target`) and a resolved `networkName`. `nextBefore` is the cursor for
     * the next older page, null at the end — carried through as nullable so `hasMore` can
     * distinguish "no more" from "more, cursor 0".
     */
    fun parseHighlights(body: String): HighlightsPage {
        val obj = `object`(body) ?: return HighlightsPage(items = emptyList(), nextBefore = null)
        return HighlightsPage(
            items = obj.objects("items").map(::parseFeedItem),
            nextBefore = obj.longOrNull("nextBefore"),
        )
    }

    /**
     * Parse REST `GET /api/activity` (lurker-ios#183): highlights and other people's reactions
     * to your lines, newest first, with a cursor per source in `next` (null at the end).
     *
     * A highlight row is a feed row like any other. A reaction row is reshaped into one: its
     * `message` is the reaction as a line (reactor, value, reaction time) carrying your line's
     * id, and `reaction` keeps the value and your line's text for the row to draw.
     */
    fun parseActivity(body: String): HighlightsPage {
        val obj = `object`(body) ?: return HighlightsPage(items = emptyList(), next = null)
        val items: List<HighlightItem> = obj.objects("items").mapNotNull { item ->
            if (item.string("kind") != "reaction") return@mapNotNull parseFeedItem(item)
            val value = item.string("value")
            val reactionId = item.long("reactionId")
            if (value.isEmpty() || reactionId == 0L) return@mapNotNull null
            val time = item.stringOrNull("time")
            HighlightItem(
                message = Message(
                    id = item.long("id"),
                    type = EventType.Message,
                    nick = item.stringOrNull("nick"),
                    text = value,
                    time = time,
                    date = ISOTime.parse(time),
                    userhost = item.stringOrNull("userhost"),
                ),
                networkId = item.intOrNull("networkId"),
                target = item.string("target"),
                networkName = item.stringOrNull("networkName"),
                reaction = FeedReaction(
                    reactionId = reactionId,
                    value = value,
                    lineText = item.stringOrNull("text"),
                ),
            )
        }
        val next = (obj["next"] as? JsonObject)?.let {
            FeedCursor(
                beforeMessage = it.longOrNull("beforeMessage"),
                beforeReaction = it.longOrNull("beforeReaction"),
            )
        }
        return HighlightsPage(items = items, next = next)
    }

    /**
     * Parse REST `GET /api/uploads` into a page of history rows (lurker-ios#138).
     *
     * ⚠ No `nextBefore` in this envelope, unlike the three message feeds — the caller pages on
     * the last row's id and reads a short page as the end. `UploadsRequest.hasMore` holds that
     * rule, along with the starred view's exception to it.
     *
     * ⚠⚠ `removed` is not a flag on an otherwise-normal row. The server sends a moderated
     * takedown as a tombstone — no `can_delete`, no thumbnail — because the bytes are gone, so
     * everything the row would otherwise offer (view, share, copy, insert) is dead for one. Read
     * back as false/null rather than defaulted into something that looks live.
     */
    fun parseUploads(body: String): UploadsPage {
        val obj = `object`(body) ?: return UploadsPage(items = emptyList())
        return UploadsPage(items = obj.objects("items").map(::parseUpload))
    }

    private fun parseUpload(row: JsonObject): UploadItem =
        UploadItem(
            id = row.int("id"),
            url = row.string("url"),
            filename = row.stringOrNull("filename"),
            mime = row.stringOrNull("mime"),
            byteSize = row.longOrNull("byte_size"),
            createdAt = ISOTime.parse(row.stringOrNull("created_at")),
            favorite = row.bool("favorite"),
            canDelete = row.bool("can_delete"),
            thumbnailPath = row.stringOrNull("thumbnail_url"),
            removed = row.bool("removed"),
        )

    /**
     * One cross-buffer feed row: a `MessageEvent` spread flat (so `parseEvent` reads it, same
     * as a backlog line) plus the buffer address (`networkId`/`target`) and a server-resolved
     * `networkName`. Shared by highlights, bookmarks and search.
     */
    private fun parseFeedItem(item: JsonObject): HighlightItem =
        HighlightItem(
            message = parseEvent(item),
            networkId = item.intOrNull("networkId"),
            target = item.string("target"),
            networkName = item.stringOrNull("networkName"),
        )

    // MARK: - Private

    /**
     * Port note: LurkerKit reads through Foundation's `JSONSerialization`; this reads through
     * kotlinx.serialization, and kotlinx's answer is the one kept. The two agree on every frame
     * the server can write (`JSON.stringify`) and part company only on text that isn't JSON or
     * sits at an edge of it. The differences are pinned in `FrameParserTests` (port-only), every
     * Foundation answer there taken from the real Swift over the same text:
     *
     * - ⚠ A lone surrogate written as an escape (`"\ud83d"`, which is what `JSON.stringify`
     *   writes for half an emoji left by a `slice`): Foundation fails the whole document, so
     *   LurkerKit's `JSONTextRepair` rewrites the escape to `\ufffd` first (lurker-ios#195), and
     *   on iOS the frame is read with a U+FFFD in place of the half. Here the frame is read and
     *   the string keeps the lone unit the server sent.
     * - Foundation drops one leading U+FEFF from every string it decodes, so `JSONTextRepair`
     *   writes a second one for it to eat (lurker-ios#196). kotlinx keeps it, so here, as on iOS,
     *   the web and the server's database, a message that begins with one keeps it.
     *   `JSONTextRepair` is a workaround for Foundation's decoder alone, so it is not ported.
     * - A byte-order mark before the document: Foundation steps over one; kotlinx fails it.
     * - A trailing comma (`[1,]`): Foundation reads past it; kotlinx fails the document.
     * - A repeated key: Foundation keeps the first value, kotlinx the last.
     * - An unquoted token that isn't JSON (`1d`, `hello`), or a raw control character inside a
     *   string, fails the document in Foundation; kotlinx reads on, and `Json.kt` then sees a
     *   literal that is not a number, or a string with the character in it.
     * - A number no `Double` holds (`1e400`): Foundation fails the document for most spellings;
     *   here the frame reads and the number is not an integer.
     *
     * Two things it does regardless: it never throws — a document kotlinx cannot read is null,
     * as it is in LurkerKit — and it bounds the nesting, because kotlinx reads nested arrays by
     * plain recursion and overflows the stack a few thousand deep. The bound is Foundation's own
     * limit, 512 containers, so the two drop the same frames (bar a 513th that opens only to
     * close again, which Foundation lets through); no real frame is deeper than six.
     */
    private fun `object`(text: String): JsonObject? {
        if (nestsTooDeep(text)) return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            // `SerializationException` is one, and it is what every malformed document throws.
            null
        } catch (_: StackOverflowError) {
            // The backstop: a thread whose stack cannot follow even 512 levels drops the frame
            // here rather than taking the app with it.
            null
        }
    }

    /**
     * Whether [text] has more than [MAX_DEPTH] containers open at once. Only strings and
     * brackets are followed, which is exact for any document that is JSON — and one that isn't
     * is refused by the parser, whatever was counted here.
     */
    private fun nestsTooDeep(text: String): Boolean {
        var depth = 0
        var inString = false
        var index = 0
        while (index < text.length) {
            val unit = text[index]
            if (inString) {
                when (unit) {
                    // The escaped unit is not looked at: `\"` does not end the string.
                    '\\' -> index += 1
                    '"' -> inString = false
                }
            } else {
                when (unit) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_DEPTH) return true
                    '}', ']' -> depth -= 1
                }
            }
            index += 1
        }
        return false
    }

    private fun parseSnapshot(obj: JsonObject): ServerFrame {
        val networks = obj.objects("networks").map { network ->
            NetworkSnapshot(
                id = network.int("networkId"),
                state = ConnectionState.from(network.stringOrNull("state")),
                nick = network.string("nick"),
                channels = network.objects("channels").map(::parseChannel),
                peerPresence = parsePeerPresence(network["peerPresence"] as? JsonObject),
                ignoredMasks = network.objects("ignoredMasks").map(::parseIgnoreRule),
                relayBots = network.objects("relayBots").mapNotNull(::parseRelayBot),
                nickNotes = network.objects("nickNotes").mapNotNull(::parseNickNote),
                away = parseAwayState(network["away"]),
                pinned = network.strings("pinned") ?: emptyList(),
                dccChats = nonEmptyStrings(network["dccChats"]),
                dccChatOffers = nonEmptyStrings(network["dccChatOffers"]),
                canReact = network.bool("canReact"),
                modeSpec = parseModeSpec(network["modeSpec"]),
            )
        }
        return ServerFrame.Snapshot(
            networks = networks,
            globalIgnores = obj.objects("globalIgnores").map(::parseIgnoreRule),
            uploadLimits = advertisedUploadLimits(obj),
            cursor = obj.longOrNull("cursor"),
        )
    }

    /**
     * A string array off the wire with anything that isn't a non-empty string dropped. An
     * empty peer names no one, and keying a live chat or an offer on it would be a row for
     * nobody.
     */
    private fun nonEmptyStrings(value: JsonElement?): List<String> =
        ((value as? JsonArray) ?: emptyList()).mapNotNull { element ->
            element.asString()?.let { if (it.isEmpty()) null else it }
        }

    /**
     * The advertised upload limits off a frame that may carry them, each null for "didn't say".
     *
     * ⚠ A non-positive number is read as "didn't say" too. The server never sends one, and a
     * limit nothing can satisfy is not a statement about anything — a zero cap taken at face
     * value would send every video down the preset ladder to `.cannotCompressEnough`, which
     * reads to the user as the app refusing to upload rather than as a server that answered
     * nonsense; a zero dimension would ask for an image with no pixels. Same discipline as an
     * absent field: only a real answer is an answer.
     */
    private fun advertisedUploadLimits(obj: JsonObject): UploadLimits {
        // Two reads where Swift has one `positive`: the byte count is `Long` on this side and
        // the dimension `Int` (PORTING.md, Types).
        fun positiveLong(key: String): Long? = obj.longOrNull(key)?.takeIf { it > 0 }
        fun positiveInt(key: String): Int? = obj.intOrNull(key)?.takeIf { it > 0 }
        return UploadLimits(
            maxUploadBytes = positiveLong("maxUploadBytes"),
            maxStaticImageDimension = positiveInt("maxStaticImageDimension"),
        )
    }

    /**
     * One stored ignore rule. Shared by the snapshot's two seeds (per-network `ignoredMasks`
     * and the frame-level `globalIgnores`) and by `ignore-list-updated`, which is the same
     * row shape in all three.
     *
     * Absent optional fields read as "unconstrained", which is what the matcher wants: no
     * mask is anyone, no channels is everywhere, no pattern is any body. `levels` arrives
     * already canonicalized by the server, so the irssi alias spellings never reach here.
     */
    private fun parseIgnoreRule(obj: JsonObject): IgnoreRule =
        IgnoreRule(
            id = obj.int("id"),
            mask = obj.stringOrNull("mask"),
            channels = obj.strings("channels"),
            pattern = obj.stringOrNull("pattern"),
            patternKind = IgnorePatternKind.from(obj.stringOrNull("patternKind")),
            levels = obj.strings("levels") ?: emptyList(),
            isExcept = obj.bool("isExcept"),
            // Parsed here rather than compared as a string at match time: expiry is checked
            // once per rule per rendered row, and `Date.parse` on every one of those would be
            // the most expensive thing in the filter.
            expiresAt = ISOTime.parse(obj.stringOrNull("expiresAt")),
        )

    /**
     * One relay-bot mark (lurker#277) — the same `{nick, pattern}` row in the snapshot's
     * per-network `relayBots` and in a `relay-bot-updated` frame.
     *
     * A row with no nick addresses nobody, so it's dropped rather than becoming a mark keyed on
     * the empty string — which would match every nick-less line the client ever renders. An
     * absent `pattern` is the built-in formats, which is what the server stores for a bare mark.
     */
    private fun parseRelayBot(obj: JsonObject): RelayBot? {
        val nick = obj.string("nick")
        return if (nick.isEmpty()) null else RelayBot(nick = nick, pattern = obj.string("pattern"))
    }

    /**
     * One stored nick note (lurker-ios#12) — the same `{nick, note, updatedAt}` row in the
     * snapshot's per-network `nickNotes` and in a `nick-note-updated` frame.
     *
     * A row with no nick is about nobody; an empty note is the server's spelling of "no note"
     * (`set_nick_note` deletes the row rather than storing a blank), so neither becomes an
     * entry. Dropping the blank here is what keeps `hasNote` from answering yes to a note that
     * was cleared.
     */
    private fun parseNickNote(obj: JsonObject): NickNote? {
        val nick = obj.string("nick")
        val note = obj.string("note")
        if (nick.isEmpty() || note.isEmpty()) return null
        return NickNote(nick = nick, note = note, updatedAt = ISOTime.parse(obj.stringOrNull("updatedAt")))
    }

    /**
     * The `whois` payload of a `whois_result` frame (lurker-ios#12).
     *
     * ⚠⚠ The field names are **irc-framework's**, not Lurker's: the server does not reshape
     * this object, it forwards the one irc-framework assembled from the RPL_WHOIS* numerics
     * (`ircConnection.ts:2912`). `real_name`, `actual_ip`, `server_info` and
     * `registered_nick` are its spellings and are load-bearing here.
     *
     * ⚠⚠ `idle` and `logon` are **numeric-valued strings**, not numbers. irc-framework assigns
     * them straight off `command.params` (`user.js:238`), and IRC parameters are text — so a
     * bare integer read gives null on every real reply. `numericField` takes either.
     */
    private fun parseWhois(obj: JsonObject): WhoisResult =
        WhoisResult(
            nick = obj.string("nick"),
            ident = obj.stringOrNull("ident"),
            hostname = obj.stringOrNull("hostname"),
            realName = obj.stringOrNull("real_name"),
            actualHostname = obj.stringOrNull("actual_hostname"),
            actualIP = obj.stringOrNull("actual_ip"),
            server = obj.stringOrNull("server"),
            serverInfo = obj.stringOrNull("server_info"),
            account = obj.stringOrNull("account"),
            channelsLine = obj.stringOrNull("channels"),
            modes = obj.stringOrNull("modes"),
            isOperator = obj.stringOrNull("operator"),
            helpop = obj.stringOrNull("helpop"),
            bot = obj.stringOrNull("bot"),
            registeredNick = obj.stringOrNull("registered_nick"),
            isSecure = obj.bool("secure"),
            certfp = obj.stringOrNull("certfp"),
            away = obj.stringOrNull("away"),
            idleSeconds = numericField(obj["idle"]),
            // Unix seconds. Distinguished from "absent" rather than defaulted, because a
            // signon at the epoch would render as 1970 — plausible-looking and wrong, where a
            // missing row simply doesn't draw.
            signedOn = numericField(obj["logon"])?.let(::instantOfEpochSecond),
            error = obj.stringOrNull("error"),
        )

    /**
     * Port note: an `Instant` stops about a billion years either side of the epoch and
     * `ofEpochSecond` throws past that, where a Swift `Date` is a `Double` and takes any number
     * of seconds. A signon out there reads as absent here — the row doesn't draw — rather than
     * as a date nothing could format.
     */
    private fun instantOfEpochSecond(seconds: Long): Instant? =
        if (seconds < Instant.MIN.epochSecond || seconds > Instant.MAX.epochSecond) {
            null
        } else {
            Instant.ofEpochSecond(seconds)
        }

    /** A wire number that may have arrived as a string. See `parseWhois` for why that happens. */
    private fun numericField(value: JsonElement?): Long? {
        value.asLong()?.let { return it }
        value.asString()?.let { return swiftInt(it) }
        return null
    }

    /**
     * The snapshot's `peerPresence` blob — `lowercased nick → {nick, state, stateAt,
     * awayMessage}` — flattened to the one field the client acts on. Entries whose `state`
     * isn't a known value (or is null) are dropped, which reads as `unknown`, exactly as a
     * live null-state event would.
     *
     * Port note: the nick is folded with `lowercase()`, which applies final sigma where Swift's
     * `lowercased()` does not (see `parseNetworkConfig`).
     */
    private fun parsePeerPresence(blob: JsonObject?): Map<String, PresenceState> {
        if (blob == null) return emptyMap()
        val out = mutableMapOf<String, PresenceState>()
        for ((nick, value) in blob) {
            val entry = value as? JsonObject ?: continue
            val raw = entry["state"].asString() ?: continue
            val state = PresenceState.fromRawValue(raw) ?: continue
            out[nick.lowercase()] = state
        }
        return out
    }

    /**
     * The `away` blob — `{active, since, message, autoSet, backAt}` — shared by the network
     * snapshot and the live `away-state` event, which carry the identical shape.
     *
     * A null (or missing, or mistyped) blob is null, not a default-constructed state: the
     * server sends `away: null` for an account with nothing on record, and inventing an
     * `active: false` there would be a claim that the user *returned* rather than that we
     * were never told anything. A blob carrying no readable `since` is null for the same
     * reason — see below.
     */
    private fun parseAwayState(raw: JsonElement?): AwayState? {
        val obj = raw as? JsonObject ?: return null
        // `since` is the field the whole feature hangs on — both markers are placed from it, and
        // the server itself treats it as the existence test (`away = a.since ? {…} : null`, in
        // both the snapshot and `publishAwayState`). So a blob we can't read one out of is a
        // blob no marker can be placed from, and reading it as an away with no beginning would
        // put a value in the store that nothing can use and nothing can retract.
        //
        // `active` and `autoSet` keep the file's ordinary `bool()` default. Nothing in the
        // placement logic reads either — `MessageRows` works from `since` and `backAt` alone —
        // so a defaulted `false` can't manufacture a marker: only a parsed `backAt` does that.
        val since = ISOTime.parse(obj.stringOrNull("since")) ?: return null
        return AwayState(
            active = obj.bool("active"),
            message = obj.stringOrNull("message"),
            since = since,
            autoSet = obj.bool("autoSet"),
            backAt = ISOTime.parse(obj.stringOrNull("backAt")),
        )
    }

    private fun parseChannel(channel: JsonObject): ChannelSnapshot =
        ChannelSnapshot(
            name = channel.string("name"),
            topic = channel.stringOrNull("topic"),
            members = channel.objects("members").map(::parseMember),
            modeState = ChannelModeState(
                modes = channel.string("modes"),
                params = parseModeParams(channel["modeParams"]),
                createdAt = ISOTime.parse(channel.stringOrNull("createdAt")),
                topicSetBy = channel.stringOrNull("topicSetBy"),
                topicSetAt = ISOTime.parse(channel.stringOrNull("topicSetAt")),
            ),
            membersPending = channel.bool("membersPending"),
        )

    /**
     * `modeParams` — `{"l": "50"}`. Anything that isn't a one-letter key with a string value is
     * dropped; a number is taken as its digits, since a limit is a number to anyone writing JSON.
     *
     * Port note: "one letter" is one grapheme cluster, as LurkerKit's `key.count == 1` is.
     */
    private fun parseModeParams(raw: JsonElement?): Map<String, String> {
        val obj = raw as? JsonObject ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for ((key, value) in obj) {
            if (graphemeBoundaries(key).size != 1) continue
            val string = value.asString()
            if (string != null) {
                out[key] = string
            } else {
                value.asLong()?.let { out[key] = it.toString() }
            }
        }
        return out
    }

    /**
     * The network's `modeSpec` (§5.1), or null for null — which is the server saying the burst
     * hasn't ended, and must stay "unknown" rather than become a default.
     *
     * A prefix entry that isn't one letter is dropped, as the server's own parser drops it. A
     * `maxModes` of null is "no limit", which is why it stays nullable rather than defaulting.
     *
     * Port note: "one letter" is one grapheme cluster here too, as `mode.count == 1` is.
     */
    fun parseModeSpec(raw: JsonElement?): ModeSpec? {
        val obj = raw as? JsonObject ?: return null
        val prefix: List<PrefixMode> = obj.objects("prefix").mapNotNull { entry ->
            val mode = entry.string("mode")
            if (graphemeBoundaries(mode).size != 1) return@mapNotNull null
            PrefixMode(mode = mode, symbol = entry.string("symbol"))
        }
        return ModeSpec(
            list = obj.string("list"),
            always = obj.string("always"),
            onSet = obj.string("onSet"),
            flags = obj.string("flags"),
            prefix = prefix,
            maxModes = obj.intOrNull("maxModes")?.let { if (it > 0) it else null },
            topicLen = obj.intOrNull("topicLen")?.let { if (it > 0) it else null },
        )
    }

    /**
     * The server's `memberSnapshot` shape — identical on a snapshot channel, a `names`
     * broadcast, and a `member-update` patch, so all three parse through here.
     */
    private fun parseMember(member: JsonObject): Member =
        Member(
            nick = member.string("nick"),
            modes = member.strings("modes") ?: emptyList(),
            away = member.bool("away"),
            user = member.stringOrNull("user"),
            host = member.stringOrNull("host"),
        )

    /**
     * Decode a REST body to a JSON object, for the callers that need a field this file has no
     * dedicated parse for (`PATCH /api/settings` → `{values}`). Still routed through here so
     * the JSON decoder stays behind the one type that knows the wire format.
     */
    fun jsonObject(text: String): JsonObject? = `object`(text)

    /**
     * `jsonObject(text)` for a body that arrives as bytes.
     *
     * Port note: LurkerKit routes these bytes through `JSONTextRepair` first, a workaround for
     * `JSONSerialization` (a lone surrogate escape, a leading U+FEFF); kotlinx needs neither,
     * so they are read as they came (see `object`). A body that is not UTF-8 is no object here;
     * `JSONSerialization` would also try UTF-16 and UTF-32.
     */
    fun jsonObject(data: ByteString): JsonObject? = data.utf8OrNull()?.let { `object`(it) }

    /**
     * The `error` string from a REST failure body (`{error, key}`), when there is one. Lives
     * here rather than at the call site because this is the one place that knows the wire
     * format — and the server's own wording ("must be one of …", "out of range") is more use
     * to the user than anything the caller could invent.
     */
    fun errorMessage(text: String): String? = `object`(text)?.stringOrNull("error")

    /**
     * A `{key: value}` blob of settings — the `values` half of bootstrap, and the `changes`
     * of a live update. Anything whose value doesn't decode to a `SettingValue` is skipped
     * rather than guessed at: a key we can't represent is one we also can't write back.
     */
    fun parseSettingValues(raw: JsonElement?): Map<String, SettingValue> {
        val obj = raw as? JsonObject ?: return emptyMap()
        val out = mutableMapOf<String, SettingValue>()
        for ((key, value) in obj) {
            SettingValue.from(value)?.let { out[key] = it }
        }
        return out
    }

    /**
     * A registry entry's `dependsOn` clauses (lurker#666), if it carries any.
     *
     * A clause whose `in` list holds nothing this app can represent is dropped rather than
     * kept empty: an empty value list can never match, so keeping it would permanently grey
     * out a control on the strength of a value we simply failed to parse.
     */
    private fun parseDependencies(raw: JsonElement?): List<SettingDependency> {
        val entries = raw?.asObjects() ?: return emptyList()
        return entries.mapNotNull { entry ->
            val key = entry.string("key")
            val values = ((entry["in"] as? JsonArray) ?: emptyList()).mapNotNull { SettingValue.from(it) }
            if (key.isEmpty() || values.isEmpty()) return@mapNotNull null
            SettingDependency(key = key, values = values)
        }
    }

    /** What [parseVerbReply] returns: LurkerKit's `(clientId: String, reply: VerbReply)` tuple. */
    data class ParsedVerbReply(val clientId: String, val reply: VerbReply)

    /**
     * A `send-result` read in full, for a verb whose answer rides its `data` — `get-mode-list`,
     * `set-channel-modes`, `set-topic` (§6). Null for anything that isn't a `send-result` with a
     * `clientId`, since nothing could be waiting on it.
     *
     * Separate from `parseWs` because `ServerFrame` stays free of untyped payloads: the client
     * re-reads only a reply it is actually holding a caller for.
     */
    fun parseVerbReply(text: String): ParsedVerbReply? {
        val obj = `object`(text) ?: return null
        if (obj.string("kind") != "send-result") return null
        val clientId = obj.stringOrNull("clientId") ?: return null
        val data = (obj["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val entries: List<ModeListEntry>? = data["entries"]?.asObjects()?.mapNotNull { entry ->
            val mask = entry.string("mask")
            if (mask.isEmpty()) return@mapNotNull null
            ModeListEntry(
                mask = mask,
                setBy = entry.stringOrNull("setBy"),
                setAt = ISOTime.parse(entry.stringOrNull("setAt")),
            )
        }
        // `numeric` is a string on the wire ("482"); taken as a number too, in case.
        val numeric = data.stringOrNull("numeric") ?: data.longOrNull("numeric")?.toString()
        return ParsedVerbReply(
            clientId,
            VerbReply(
                ok = obj.bool("ok"),
                error = obj.stringOrNull("error") ?: data.stringOrNull("error"),
                numeric = numeric,
                text = data.stringOrNull("text"),
                entries = entries,
            ),
        )
    }

    /**
     * `GET /api/settings/bootstrap` → `{registry, values}`.
     *
     * The registry is an ARRAY of options on the wire (it's `REGISTRY` verbatim), keyed here
     * so lookups are by key rather than a linear scan per read.
     */
    fun parseSettingsBootstrap(text: String): ServerFrame {
        val obj = `object`(text) ?: return ServerFrame.Ignored
        val registry = mutableMapOf<String, SettingOption>()
        for (entry in obj["registry"]?.asObjects() ?: emptyList()) {
            val key = entry.string("key")
            if (key.isEmpty()) continue
            val type = SettingType.fromRawValue(entry.string("type")) ?: continue
            // A registry entry with no usable default is unusable: `effective` would return null
            // for an unset key and every caller would silently fall through to its own
            // fallback, which is the drift this whole layer exists to prevent.
            val defaultValue = entry["default"]?.let { SettingValue.from(it) } ?: continue
            registry[key] = SettingOption(
                key = key,
                label = entry.string("label"),
                description = entry.string("description"),
                type = type,
                default = defaultValue,
                choices = entry.strings("choices") ?: emptyList(),
                choiceLabels = entry.stringMap("choiceLabels") ?: emptyMap(),
                min = entry.intOrNull("min"),
                max = entry.intOrNull("max"),
                dependsOn = parseDependencies(entry["dependsOn"]),
            )
        }
        return ServerFrame.SettingsBootstrap(registry = registry, values = parseSettingValues(obj["values"]))
    }

    private fun parseBacklog(obj: JsonObject): ServerFrame {
        val target = obj.string("target")
        val marker = clearedMarker(obj)
        if (target.isEmpty()) return ServerFrame.Ignored
        val networkId = obj.intOrNull("networkId")
        val events = obj.objects("events")
        // A shell is `events: []` + `hasMoreOlder: true` — the "unhydrated, fetch on
        // open" marker. Any real events, or a frame that isn't claiming more older,
        // means we have this buffer's history. Read `hasMoreOlder` once with a `true`
        // fallback (matching Buffer's default): if a server ever omits it on an empty
        // frame, treating the buffer as a shell (→ fetch) is safe; the `false` fallback
        // would mislabel it hydrated and it would render empty forever.
        val hasMoreOlder = obj.bool("hasMoreOlder", true)
        val hydrated = events.isNotEmpty() || !hasMoreOlder
        // For a *network* buffer, only a resume slice carries a `reset` field. reset:false
        // means "these are just the events past ?since" → append; reset:true (oversized
        // gap) and a plain full/latest backlog (no field) → replace.
        //
        // The system buffer is the exception, and reading it the same way corrupts it: the
        // server's `buildSystemBacklog` hardcodes `reset: false` on EVERY connect, because
        // it always ships a full latest slice and expects the client to reconcile it — it
        // is never a resume delta. Appending it instead would (a) splice the whole history
        // *after* any live system line that beat the backlog in, and (b) leave a permanent
        // hole when a reconnect gap exceeds the server's slice cap. Replacing is what the
        // server means, and the replace path already preserves live events past the tail.
        val append = networkId != null && obj.has("reset") && !obj.bool("reset")
        val buffer = Buffer(
            networkId = networkId,
            target = target,
            kind = BufferKind.of(networkId = networkId, target = target),
            unread = obj.int("unread"),
            highlights = obj.int("highlights"),
            lastReadId = obj.long("lastReadId"),
            joined = obj.bool("joined"),
            hydrated = hydrated,
            // Read from the FIELD'S PRESENCE, not its value: `long()` reads a missing
            // `lastReadId` as 0, which is also a legitimate "read nothing". Only a frame that
            // actually carried the pointer may claim to have stated it — see
            // `Buffer.readStateKnown`.
            readStateKnown = obj.has("lastReadId"),
            hasMoreOlder = hasMoreOlder,
            clearedBeforeId = marker.beforeId,
            clearedAt = marker.at,
            // The connect burst doubles as the id directory (§5.2): every
            // backlog frame carries the buffer's stable id.
            bufferId = obj.intOrNull("bufferId"),
        )
        return ServerFrame.Backlog(
            buffer = buffer,
            messages = events.map(::parseEvent),
            hydrated = hydrated,
            append = append,
            speakers = parseSpeakers(obj),
        )
    }

    /** What [clearedMarker] returns: LurkerKit's `(beforeId: Int, at: Date?)` tuple. */
    private data class ClearedMarker(val beforeId: Long, val at: Instant?)

    /**
     * The `/clear` marker off a frame that carries one (lurker-ios#121) — both halves, or
     * neither.
     *
     * ⚠ Negatives and zero are "never cleared". The server treats `<= 0` as no marker
     * (`bufferReads.ts`: "boundary id <= 0 clears the marker"), and a negative reaching the
     * row filter would compare against every id and hide nothing anyway.
     *
     * ⚠⚠ A boundary with no readable instant is discarded WHOLE. Those two states are one
     * fact, and half of it hides every row while drawing no divider to undo with — the server
     * allows a null `cleared_at` and its rename/case-fold merges carry the columns
     * independently, so this is reachable from the wire rather than only from a bug here.
     */
    private fun clearedMarker(obj: JsonObject): ClearedMarker {
        val beforeId = obj.long("clearedBeforeId")
        if (beforeId <= 0) return ClearedMarker(0, null)
        val at = ISOTime.parse(obj.stringOrNull("clearedAt")) ?: return ClearedMarker(0, null)
        return ClearedMarker(beforeId, at)
    }

    private fun parseLive(obj: JsonObject): ServerFrame {
        // `peer-presence` is network-scoped state routed by `nick`, not a buffer line — its
        // `:server:<id>` target is only a carrier. Handle it before the target guard below so
        // the presence handler never depends on an unrelated field: no id, nothing to render,
        // and `state` may be null → null → `unknown`.
        if (obj.string("type") == "peer-presence") {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            val nick = obj.string("nick")
            if (nick.isEmpty()) return ServerFrame.Ignored
            return ServerFrame.PeerPresence(
                networkId = networkId,
                nick = nick,
                state = PresenceState.fromRawValue(obj.string("state")),
            )
        }
        // `away-state` is network-scoped state like `peer-presence`, and its `:server:<id>`
        // target is the same kind of carrier — so it's handled above the target guard too,
        // and for the extra reason that its payload is legitimately *null*: an account with
        // no away on record sends `away: null`, which is a value to store rather than a frame
        // to drop.
        if (obj.string("type") == "away-state") {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            return ServerFrame.AwayState(networkId = networkId, away = parseAwayState(obj["away"]))
        }
        // The three DCC chat ephemerals (lurker#270) are network-scoped state in the same way:
        // their `:server:<id>` target is a carrier, and the peer they are about rides in `from`.
        // Lifted out here because below the guard they'd fold to `Other` and land in the server
        // log as lines with no text — the offer as nothing, where it is a decision to make.
        when (obj.string("type")) {
            "dcc-chat-offer", "dcc-chat-offer-closed", "dcc-chat-state" -> {
                val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
                val nick = obj.string("from")
                if (nick.isEmpty()) return ServerFrame.Ignored
                return when (obj.string("type")) {
                    "dcc-chat-offer" ->
                        ServerFrame.DccChatOffer(networkId = networkId, nick = nick, passive = obj.bool("passive"))
                    "dcc-chat-offer-closed" ->
                        ServerFrame.DccChatOfferClosed(networkId = networkId, nick = nick)
                    else ->
                        ServerFrame.DccChatState(networkId = networkId, nick = nick, live = obj.bool("live"))
                }
            }
            else -> {}
        }
        // The `invite` that names us (lurker#261) is the same shape again: a `:server:<id>`
        // carrier, the inviter in `from`, the channel in `channel`. Below the guard it has no
        // `nick` or `invited`, so `isRenderable` drops it — and with it the only chance to offer
        // a Join. The other `invite`, someone else invited on a channel we're in, is a real
        // channel line with neither field, and falls through.
        val inviter = obj.stringOrNull("from")
        if (obj.string("type") == "invite" && !inviter.isNullOrEmpty()) {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            val channel = obj.string("channel")
            if (channel.isEmpty()) return ServerFrame.Ignored
            return ServerFrame.Invited(networkId = networkId, channel = channel, from = inviter)
        }
        // `react-support` is network-scoped state on a `:server:<id>` carrier, like those above.
        if (obj.string("type") == "react-support") {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            return ServerFrame.ReactSupport(networkId = networkId, canReact = obj.bool("canReact"))
        }
        // …and so is `mode-spec`. Below the guard it would land in the server log as a line with
        // no text, and the channel settings would wait forever for a vocabulary that came.
        if (obj.string("type") == "mode-spec") {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            return ServerFrame.ModeSpec(networkId = networkId, spec = parseModeSpec(obj["modeSpec"]))
        }
        // `own-nick` is network-scoped state too, and carries no target at all — the visible
        // line is the ordinary `nick` event fanned out per channel, which arrives separately.
        // Below the target guard it would be dropped, leaving `Network.nick` pinned to whatever
        // the connect snapshot said for the rest of the session.
        if (obj.string("type") == "own-nick") {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            val nick = obj.string("nick")
            if (nick.isEmpty()) return ServerFrame.Ignored
            return ServerFrame.OwnNick(networkId = networkId, nick = nick)
        }
        // `state` is the connection indicator's only live source, and it was being DROPPED.
        //
        // The server publishes one on every transition (`ircConnection.setState`,
        // unconditionally — the comment there says it exists to keep a late-attaching client
        // in sync). Without a case here it folded to `Other`, and since it carries no `text`
        // it rendered nowhere either: the per-network `state` the app showed came only from
        // the connect `snapshot` and was then frozen for the session. So a network that
        // dropped still read as connected, one that came back still read as disconnected,
        // and under lurker-ios#11 the Connect button would have appeared to do nothing.
        //
        // `nick` rides along on the connect transition only, so it's nullable here — an
        // absent one must not blank the nick the snapshot gave us.
        if (obj.string("type") == "state") {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            val nick = obj.string("nick")
            return ServerFrame.NetworkState(
                networkId = networkId,
                state = ConnectionState.from(obj.stringOrNull("state")),
                nick = if (nick.isEmpty()) null else nick,
            )
        }
        // `whois_result` is about a *person on a network*, not about a conversation, so it
        // carries no target at all — like `own-nick`, and unlike the `:server:<id>` carriers
        // above. Below the guard it folds to `Other` and is discarded, which is why `/whois`
        // has never done anything on this client but write numerics to the server buffer.
        //
        // With no `nick` there is nothing to key it on. The server can't send one — even a
        // miss carries it, synthesized at RPL_ENDOFWHOIS — and a reply we can't address is one
        // no screen can be waiting for.
        if (obj.string("type") == "whois_result") {
            val networkId = obj.intOrNull("networkId") ?: return ServerFrame.Ignored
            val payload = obj["whois"] as? JsonObject ?: return ServerFrame.Ignored
            if (payload.string("nick").isEmpty()) return ServerFrame.Ignored
            return ServerFrame.WhoisResult(networkId = networkId, whois = parseWhois(payload))
        }
        val target = obj.string("target")
        if (target.isEmpty()) return ServerFrame.Ignored
        // `channel-topic` rides the `irc` kind like everything else, but it isn't an event
        // in the sense the rest of this function means: no id, nothing to render, and its
        // payload is in `topic` rather than `text`. Left to `parseEvent` it would become an
        // `Other` Message appended to the buffer, carrying the topic in a field nothing
        // reads.
        //
        // The setter and time ride along when the server knows to send them (333). Their KEY's
        // presence is what counts — `setBy: null` is the server saying it doesn't know who, which
        // replaces a stale setter; an absent key says nothing about the setter at all.
        if (obj.string("type") == "channel-topic") {
            return ServerFrame.ChannelTopic(
                networkId = obj.intOrNull("networkId"),
                target = target,
                topic = obj.stringOrNull("topic"),
                meta = if (obj.containsKey("setBy") || obj.containsKey("setAt")) {
                    TopicMeta(
                        setBy = obj.stringOrNull("setBy"),
                        setAt = ISOTime.parse(obj.stringOrNull("setAt")),
                    )
                } else {
                    null
                },
            )
        }
        // `channel-modes` is state too: the channel's whole mode string after any change, so the
        // settings screen reads modes the way the topic bar reads the topic. No id, nothing to draw.
        if (obj.string("type") == "channel-modes") {
            return ServerFrame.ChannelModes(
                networkId = obj.intOrNull("networkId"),
                target = target,
                modes = obj.string("modes"),
                params = parseModeParams(obj["modeParams"]),
                createdAt = ISOTime.parse(obj.stringOrNull("createdAt")),
            )
        }
        // Membership, for the same reason: no id, nothing to render, a row to mark rather than a
        // line to append. Falling through, each became an `Other` Message that `applyLive`
        // minted a row for — so a forward's part for a channel we never had conjured one.
        if (obj.string("type") == "channel-joined") {
            return ServerFrame.ChannelJoined(networkId = obj.intOrNull("networkId"), target = target)
        }
        if (obj.string("type") == "channel-parted") {
            return ServerFrame.ChannelParted(networkId = obj.intOrNull("networkId"), target = target)
        }
        // A refused join (+i, banned, a bad key, too many channels): ephemeral, and aimed at the
        // channel it refused, which we're not in — so its own frame, never a line for `applyLive`,
        // or it becomes a row (lurker-ios#168). `text` is the server's sentence for the refusal;
        // `reason`, the IRC server's own, is the fallback.
        if (obj.string("type") == "join-error") {
            val text = if (obj.string("text").isEmpty()) obj.string("reason") else obj.string("text")
            return ServerFrame.JoinError(
                networkId = obj.intOrNull("networkId"),
                target = target,
                reason = if (text.isEmpty()) "The server refused the join." else text,
            )
        }
        // `names` and `member-update` are state-only for the same reason as
        // `channel-topic`: no id, nothing to render, payload in fields `parseEvent`
        // doesn't read. Left to fall through they'd become `Other` Messages that
        // carry the member data in no field at all.
        if (obj.string("type") == "names") {
            return ServerFrame.ChannelMembers(
                networkId = obj.intOrNull("networkId"),
                target = target,
                members = obj.objects("members").map(::parseMember),
                pending = obj.bool("membersPending"),
            )
        }
        // `typing` is ephemeral state like the three around it — no id, nothing to render —
        // and its payload lives in `state`, which `parseEvent` doesn't read. Note this sits
        // BELOW the target guard: unlike `peer-presence`, a typing tag is meaningless without
        // knowing which conversation it's about.
        if (obj.string("type") == "typing") {
            val nick = obj.string("nick")
            // Nobody to attribute it to. (The server always sends one; a malformed frame
            // shouldn't become an entry keyed on the empty string.)
            if (nick.isEmpty()) return ServerFrame.Ignored
            return ServerFrame.Typing(
                networkId = obj.intOrNull("networkId"),
                target = target,
                nick = nick,
                activity = TypingActivity.from(obj.stringOrNull("state")),
                userhost = obj.stringOrNull("userhost"),
            )
        }
        if (obj.string("type") == "member-update") {
            // A patch with no nick has nobody to apply to.
            val member = obj["member"] as? JsonObject ?: return ServerFrame.Ignored
            if (member.string("nick").isEmpty()) return ServerFrame.Ignored
            return ServerFrame.MemberUpdate(
                networkId = obj.intOrNull("networkId"),
                target = target,
                member = parseMember(member),
            )
        }
        return ServerFrame.Live(networkId = obj.intOrNull("networkId"), target = target, message = parseEvent(obj))
    }

    private fun parseHistory(obj: JsonObject): ServerFrame {
        val target = obj.string("target")
        if (target.isEmpty()) return ServerFrame.Ignored
        val mode = HistoryMode.fromRawValue(obj.string("mode")) ?: HistoryMode.Before
        // `hasMore` is a legacy alias for `hasMoreOlder`; prefer the explicit field.
        return ServerFrame.History(
            networkId = obj.intOrNull("networkId"),
            target = target,
            events = obj.objects("events").map(::parseEvent),
            mode = mode,
            hasMoreOlder = obj.bool("hasMoreOlder", obj.bool("hasMore")),
            hasMoreNewer = obj.bool("hasMoreNewer"),
            speakers = parseSpeakers(obj),
        )
    }

    /**
     * The server's recent-speakers list, or null if the frame didn't carry one.
     *
     * Read from the field's PRESENCE rather than from an empty parse, because the wire draws
     * the distinction and this type mirrors the wire (see `ServerFrame.Backlog`): a frame that
     * shipped `speakers: []` is saying nobody has spoken, and one that shipped nothing is
     * saying nothing at all.
     *
     * `lastTime` is epoch milliseconds. Entries missing either half are dropped rather than
     * defaulted: a speaker at the epoch reads as infinitely stale, which is the same as being
     * absent but harder to notice.
     */
    private fun parseSpeakers(obj: JsonObject): List<Speaker>? {
        if (!obj.has("speakers")) return null
        return obj.objects("speakers").mapNotNull { entry ->
            val nick = entry.string("nick")
            val lastTime = entry.long("lastTime")
            if (nick.isEmpty() || lastTime <= 0) return@mapNotNull null
            Speaker(nick = nick, lastSpoke = Instant.ofEpochMilli(lastTime))
        }
    }

    /**
     * MessageEvent → domain `Message`. Events are spread flat on the frame, so the
     * same reader handles both a backlog array element and a live `irc` frame.
     *
     * `level` and `originNetworkId` are only ever set on system-buffer lines, and are
     * null everywhere else — severity there is a sibling field, not a `type`.
     */
    private fun parseEvent(event: JsonObject): Message {
        val time = event.stringOrNull("time")
        val type = EventType.from(event.stringOrNull("type"))
        return Message(
            id = event.long("id"),
            type = type,
            nick = event.stringOrNull("nick"),
            text = event.stringOrNull("text"),
            isSelf = event.bool("self"),
            time = time,
            date = ISOTime.parse(time),
            matched = event.bool("matched"),
            level = if (type == EventType.System) SystemLevel.from(event.stringOrNull("level")) else null,
            // Gated like `level`, matching the server: `systemLineToEvent` is the only
            // producer of this field and only ever builds `type: "system"` events, so
            // reading it anywhere else would be inventing a meaning the wire doesn't have.
            originNetworkId = if (type == EventType.System) event.intOrNull("originNetworkId") else null,
            // The server's `extractExtras` spreads these onto the event for exactly one
            // type each — `newNick` on nick, `kicked` on kick, `invited` on invite, `modes`
            // on mode, `newIdent`/`newHost` on chghost, `account` on join. Reading them
            // unconditionally is harmless (they're absent otherwise), and the renderer only
            // reaches for the one its type implies.
            newNick = event.stringOrNull("newNick"),
            kicked = event.stringOrNull("kicked"),
            invited = event.stringOrNull("invited"),
            modes = event.objects("modes").map {
                ModeChange(
                    mode = it.string("mode"),
                    param = it.stringOrNull("param"),
                    // Absent on rows older than the server-side stamp, and on any server
                    // that doesn't send it. Unknown values decode to null for the same
                    // reason: an unclassified change is shown, never guessed at.
                    kind = it.stringOrNull("kind")?.let { kind -> ModeChangeKind.fromRawValue(kind) },
                )
            },
            newIdent = event.stringOrNull("newIdent"),
            newHost = event.stringOrNull("newHost"),
            // Not an extra — a real column on the messages table, so it survives backlog for
            // every event type that had one (`server/db/messages.ts:157`).
            userhost = event.stringOrNull("userhost"),
            account = event.stringOrNull("account"),
            // Absent means unsaved — the server omits the field rather than sending false,
            // since nearly every row in every backlog is unsaved. See Message.bookmarked
            // for why the store's id set, not this, is what the UI reads.
            bookmarked = event.bool("bookmarked"),
            msgid = event.stringOrNull("msgid"),
            isE2E = event.bool("e2e"),
            // Absent means none stand — the server omits the field rather than sending `[]`.
            reactions = event["reactions"]?.asObjects()?.let(::parseReactions),
            replyTo = parseReplyContext(event["replyTo"]),
            replyToSelf = event.bool("replyToSelf"),
            // Only a 421 carries it, naming the verb the ircd didn't know.
            unknownCommand = if (type == EventType.Error) event.stringOrNull("unknownCommand") else null,
        )
    }

    /**
     * A row's `replyTo`: `{ msgid, parent }`, `parent` null when the server found no line. With
     * no msgid it names nothing; a parent with no id has nowhere to jump and reads as unavailable.
     */
    fun parseReplyContext(raw: JsonElement?): ReplyContext? {
        val obj = raw as? JsonObject ?: return null
        val msgid = obj.stringOrNull("msgid") ?: return null
        return ReplyContext(msgid = msgid, parent = parseReplyParent(obj["parent"]))
    }

    /**
     * One buffer's draft. Refused without a network or a target: it would be a draft for
     * nowhere, and a frame that says nothing usable must not clear one we hold.
     *
     * ⚠ `reply` absent and `reply: null` are different statements on `draft-updated` — absent
     * is a server from before replies, and leaves ours alone (`carriesReply`).
     */
    fun parseDraftEntry(obj: JsonObject): DraftEntry? {
        val target = obj.string("target")
        val networkId = obj.intOrNull("networkId") ?: return null
        if (networkId == 0 || target.isEmpty()) return null
        return DraftEntry(
            networkId = networkId,
            target = target,
            body = obj.string("body"),
            reply = parseDraftReply(obj["reply"]),
            // Not `has`, which reads a null as absent: `reply: null` clears.
            carriesReply = obj.containsKey("reply"),
        )
    }

    /** A draft's reply: `{ messageId, addressed, parent }`. No line id, no reply. */
    fun parseDraftReply(raw: JsonElement?): DraftReply? {
        val obj = raw as? JsonObject ?: return null
        val messageId = obj.longOrNull("messageId") ?: return null
        if (messageId == 0L) return null
        return DraftReply(
            messageId = messageId,
            addressed = obj.bool("addressed"),
            parent = parseReplyParent(obj["parent"]),
        )
    }

    fun parseReplyParent(raw: JsonElement?): ReplyParent? {
        val parent = raw as? JsonObject ?: return null
        val id = parent.longOrNull("id") ?: return null
        if (id == 0L) return null
        return ReplyParent(
            id = id,
            nick = parent.string("nick"),
            type = EventType.from(parent.stringOrNull("type")),
            text = parent.string("text"),
            userhost = parent.stringOrNull("userhost"),
            isSelf = parent.bool("self"),
        )
    }

    /** A row's `reactions`, oldest first. An entry with no value or no nick is nothing to show. */
    private fun parseReactions(list: List<JsonObject>): List<MessageReaction> =
        list.mapNotNull { entry ->
            val nick = entry.string("nick")
            val value = entry.string("value")
            if (nick.isEmpty() || value.isEmpty()) return@mapNotNull null
            MessageReaction(nick = nick, value = value, isSelf = entry.bool("self"))
        }
}

/** Foundation's nesting limit, and the crash guard here — see [FrameParser.object]. */
private const val MAX_DEPTH = 512
