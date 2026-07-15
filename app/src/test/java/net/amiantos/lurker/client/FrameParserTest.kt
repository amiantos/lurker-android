// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.client

import net.amiantos.lurker.model.BufferKind
import net.amiantos.lurker.model.ConnectionState
import net.amiantos.lurker.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the parser to the real wire contract (mapped from the server source):
 * flat-spread `irc` events, `events:[] + hasMoreOlder:true` shells, names living
 * only in the REST roster, etc.
 */
class FrameParserTest {

    @Test
    fun `a channel backlog shell parses as unhydrated with no messages`() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","events":[],
               "hasMoreOlder":true,"joined":true,"unread":3,"lastReadId":42}""",
        )
        assertTrue(frame is ServerFrame.Backlog)
        frame as ServerFrame.Backlog
        assertFalse("events:[] + hasMoreOlder:true is a shell", frame.hydrated)
        assertEquals(0, frame.messages.size)
        assertEquals(BufferKind.Channel, frame.buffer.kind)
        assertEquals(3, frame.buffer.unread)
        assertEquals(42L, frame.buffer.lastReadId)
    }

    @Test
    fun `a hydrated backlog parses its events`() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":1,"target":"#lurker","hasMoreOlder":false,
               "events":[
                 {"id":1,"type":"message","nick":"alice","text":"hi","self":false},
                 {"id":2,"type":"action","nick":"bob","text":"waves","self":true}
               ]}""",
        )
        frame as ServerFrame.Backlog
        assertTrue(frame.hydrated)
        assertEquals(2, frame.messages.size)
        assertEquals(EventType.Message, frame.messages[0].type)
        assertEquals("hi", frame.messages[0].text)
        assertEquals(EventType.Action, frame.messages[1].type)
        assertTrue(frame.messages[1].self)
    }

    @Test
    fun `a live irc frame reads the event spread flat on the frame`() {
        val frame = FrameParser.parseWs(
            """{"kind":"irc","id":7,"networkId":1,"target":"#lurker",
               "type":"message","nick":"carol","text":"yo","self":false,"matched":true}""",
        )
        assertTrue(frame is ServerFrame.Live)
        frame as ServerFrame.Live
        assertEquals(1, frame.networkId)
        assertEquals("#lurker", frame.target)
        assertEquals(7L, frame.message.id)
        assertEquals("carol", frame.message.nick)
        assertTrue(frame.message.matched)
    }

    @Test
    fun `snapshot parses networks, channels, and members - but no name`() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[
                 {"networkId":1,"state":"connected","nick":"me","channels":[
                    {"name":"#lurker","topic":"hi","members":[
                       {"nick":"alice","modes":["o"],"away":false},
                       {"nick":"bob","modes":[],"away":true}
                    ]}
                 ]}
               ]}""",
        )
        frame as ServerFrame.Snapshot
        val net = frame.networks.single()
        assertEquals(1, net.id)
        assertEquals(ConnectionState.Connected, net.state)
        assertEquals("me", net.nick)
        val channel = net.channels.single()
        assertEquals("#lurker", channel.name)
        assertEquals(listOf("alice", "bob"), channel.members.map { it.nick })
        assertEquals(listOf("o"), channel.members[0].modes)
        assertTrue(channel.members[1].away)
    }

    @Test
    fun `send-result carries clientId, ok, and error`() {
        val frame = FrameParser.parseWs("""{"kind":"send-result","clientId":"c1","ok":false,"error":"unknown-network"}""")
        frame as ServerFrame.SendResult
        assertEquals("c1", frame.clientId)
        assertFalse(frame.ok)
        assertEquals("unknown-network", frame.error)
    }

    @Test
    fun `REST networks parse id and name`() {
        val frame = FrameParser.parseNetworks("""{"networks":[{"id":1,"name":"Libera"},{"id":2,"name":"OFTC"}]}""")
        frame as ServerFrame.Networks
        assertEquals(listOf(1 to "Libera", 2 to "OFTC"), frame.networks.map { it.id to it.name })
    }

    @Test
    fun `an unknown frame kind is ignored, not an error`() {
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("""{"kind":"draft-snapshot","drafts":{}}"""))
        assertEquals(ServerFrame.Ignored, FrameParser.parseWs("not json at all"))
    }

    @Test
    fun `the system buffer is classified as System, not a DM`() {
        val frame = FrameParser.parseWs(
            """{"kind":"backlog","networkId":null,"target":":system:","hasMoreOlder":false,"events":[]}""",
        )
        frame as ServerFrame.Backlog
        assertEquals(BufferKind.System, frame.buffer.kind)
        assertEquals(null, frame.buffer.networkId)
    }
}
