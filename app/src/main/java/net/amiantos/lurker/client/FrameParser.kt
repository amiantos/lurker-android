// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.client

import net.amiantos.lurker.model.Buffer
import net.amiantos.lurker.model.BufferKind
import net.amiantos.lurker.model.ConnectionState
import net.amiantos.lurker.model.EventType
import net.amiantos.lurker.model.Member
import net.amiantos.lurker.model.Message
import net.amiantos.lurker.model.Network
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns raw server JSON into typed [ServerFrame]s. The one place that knows the
 * wire format. Kinds the 1.0 foundation doesn't consume yet parse to
 * [ServerFrame.Ignored] rather than failing.
 */
object FrameParser {

    /** Parse one WS text frame (discriminated by `kind`). */
    fun parseWs(text: String): ServerFrame {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return ServerFrame.Ignored
        return when (obj.optString("kind")) {
            "snapshot" -> parseSnapshot(obj)
            "backlog" -> parseBacklog(obj)
            "irc" -> parseLive(obj)
            "send-result" -> ServerFrame.SendResult(
                clientId = obj.stringOrNull("clientId"),
                ok = obj.optBoolean("ok", false),
                error = obj.stringOrNull("error"),
            )
            "error" -> ServerFrame.ServerError(obj.optString("text"))
            else -> ServerFrame.Ignored
        }
    }

    /** Parse REST `GET /api/networks` into the roster (id → name). */
    fun parseNetworks(body: String): ServerFrame {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return ServerFrame.Networks(emptyList())
        val arr = obj.optJSONArray("networks") ?: JSONArray()
        return ServerFrame.Networks(
            arr.objects().map { n ->
                // REST carries no live state; the WS snapshot fills state/nick in.
                Network(id = n.optInt("id"), name = n.optString("name", "network"))
            },
        )
    }

    private fun parseSnapshot(obj: JSONObject): ServerFrame {
        val networks = (obj.optJSONArray("networks") ?: JSONArray()).objects().map { n ->
            NetworkSnapshot(
                id = n.optInt("networkId"),
                state = ConnectionState.from(n.stringOrNull("state")),
                nick = n.optString("nick"),
                channels = (n.optJSONArray("channels") ?: JSONArray()).objects().map(::parseChannel),
            )
        }
        return ServerFrame.Snapshot(networks)
    }

    private fun parseChannel(c: JSONObject): ChannelSnapshot =
        ChannelSnapshot(
            name = c.optString("name"),
            topic = c.stringOrNull("topic"),
            members = (c.optJSONArray("members") ?: JSONArray()).objects().map { m ->
                Member(
                    nick = m.optString("nick"),
                    modes = (m.optJSONArray("modes") ?: JSONArray()).strings(),
                    away = m.optBoolean("away", false),
                    user = m.stringOrNull("user"),
                    host = m.stringOrNull("host"),
                )
            },
        )

    private fun parseBacklog(obj: JSONObject): ServerFrame {
        val target = obj.optString("target")
        if (target.isEmpty()) return ServerFrame.Ignored
        val networkId = obj.intOrNull("networkId")
        val events = obj.optJSONArray("events") ?: JSONArray()
        // A shell is `events: []` + `hasMoreOlder: true` — the "unhydrated, fetch on
        // open" marker. Any real events, or a frame that isn't claiming more older,
        // means we have this buffer's history.
        val hydrated = events.length() > 0 || !obj.optBoolean("hasMoreOlder", false)
        val buffer = Buffer(
            networkId = networkId,
            target = target,
            kind = BufferKind.of(networkId, target),
            unread = obj.optInt("unread", 0),
            highlights = obj.optInt("highlights", 0),
            lastReadId = obj.optLong("lastReadId", 0),
            joined = obj.optBoolean("joined", false),
            hydrated = hydrated,
        )
        return ServerFrame.Backlog(buffer, events.objects().mapNotNull(::parseEvent), hydrated)
    }

    private fun parseLive(obj: JSONObject): ServerFrame {
        val target = obj.optString("target")
        if (target.isEmpty()) return ServerFrame.Ignored
        val message = parseEvent(obj) ?: return ServerFrame.Ignored
        return ServerFrame.Live(obj.intOrNull("networkId"), target, message)
    }

    /**
     * MessageEvent → domain [Message]. Events are spread flat on the frame, so the
     * same reader handles both a backlog array element and a live `irc` frame.
     */
    private fun parseEvent(e: JSONObject): Message? =
        Message(
            id = e.optLong("id", 0),
            type = EventType.from(e.stringOrNull("type")),
            nick = e.stringOrNull("nick"),
            text = e.stringOrNull("text"),
            self = e.optBoolean("self", false),
            time = e.stringOrNull("time"),
            matched = e.optBoolean("matched", false),
        )
}
