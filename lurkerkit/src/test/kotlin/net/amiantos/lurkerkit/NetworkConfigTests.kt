// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkProxy
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.model.SecretEdit
import net.amiantos.lurkerkit.store.LurkerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Network management (lurker-ios#11) below the UI: the live `state` event the client used to
 * drop, the nameless-network rule that replaced lurker-ios#136's placeholder, and the config
 * rows and request bodies the networks screen is built on.
 *
 * Port note: LurkerKit reads a body with `body["key"] as? String` and the like. Here a value is
 * compared as a `JsonElement`, which holds it to its JSON type as well as its value — a `true`
 * is not a `1` and a `"6697"` is not a `6697`. `assertNull(body["key"])` is "the key is absent",
 * as it is in the Swift: a key sent as null is `JsonNull`, which is not null.
 */
class NetworkConfigTests {

    // MARK: - The `state` event

    @Test
    fun testAStateEventCarriesTheConnectionAndItsNick() {
        // The connect transition, and the only one that carries a nick.
        val frame = FrameParser.parseWs(
            """{"kind":"irc","networkId":2,"type":"state","state":"connected","nick":"me"}""",
        )
        if (frame !is ServerFrame.NetworkState) fail("expected networkState, got $frame")
        val (networkId, state, nick) = frame
        assertEquals(2, networkId)
        assertEquals(ConnectionState.Connected, state)
        assertEquals("me", nick)
    }

    @Test
    fun testAStateEventWithoutANickSaysNothingAboutIt() {
        // Every transition but `connected` sends `{type:'state', state}` and nothing else. An
        // absent nick has to read as "unchanged" — as "" it would blank the nick on every
        // disconnect, and everything that asks "is this me?" would start answering wrong.
        val frame = FrameParser.parseWs("""{"kind":"irc","networkId":2,"type":"state","state":"reconnecting"}""")
        if (frame !is ServerFrame.NetworkState) fail("expected networkState, got $frame")
        assertEquals(ConnectionState.Reconnecting, frame.state)
        assertNull(frame.nick)
    }

    @Test
    fun testAStateEventCarriesNoTargetAndIsParsedAnyway() {
        // `setState` publishes without a target, so this has to be handled above the target
        // guard the buffer-scoped events sit behind. Dropped there, the app's connection
        // state would stay frozen at whatever the connect snapshot said — which is exactly
        // the bug this frame was added to fix.
        val frame = FrameParser.parseWs("""{"kind":"irc","networkId":2,"type":"state","state":"disconnected"}""")
        if (frame !is ServerFrame.NetworkState) fail("expected networkState, got $frame")
    }

    @Test
    fun testTheStoreAppliesTheStateAndTheNick() {
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 2, name = "Libera"))))
        store.apply(ServerFrame.NetworkState(networkId = 2, state = ConnectionState.Connected, nick = "me"))
        assertEquals(ConnectionState.Connected, store.state.networks[2]?.state)
        assertEquals("me", store.state.networks[2]?.nick)
    }

    @Test
    fun testADisconnectKeepsTheNick() {
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 2, name = "Libera"))))
        store.apply(ServerFrame.NetworkState(networkId = 2, state = ConnectionState.Connected, nick = "me"))
        store.apply(ServerFrame.NetworkState(networkId = 2, state = ConnectionState.Disconnected, nick = null))
        assertEquals(ConnectionState.Disconnected, store.state.networks[2]?.state)
        assertEquals("me", store.state.networks[2]?.nick)
    }

    @Test
    fun testTheRosterNameSurvivesAStateEvent() {
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 2, name = "Libera"))))
        store.apply(ServerFrame.NetworkState(networkId = 2, state = ConnectionState.Connected, nick = "me"))
        assertEquals("Libera", store.state.networks[2]?.name)
    }

    // MARK: - Nameless networks (lurker-ios#136)

    @Test
    fun testASnapshotForAnUnknownNetworkLeavesItNameless() {
        // ⚠⚠ The whole of lurker-ios#136. The snapshot carries no names, so a network the
        // roster hasn't named arrives with nothing to call it. It used to arrive called
        // "network" — a placeholder nothing downstream could tell from a real name, which is
        // why the app displayed it for the life of the process instead of re-fetching.
        val store = LurkerStore()
        store.apply(
            FrameParser.parseWs(
                """{"kind":"snapshot","networks":[{"networkId":7,"state":"connected","nick":"me","channels":[]}]}""",
            ),
        )
        assertNotNull(store.state.networks[7])
        assertNull(store.state.networks[7]?.name)
    }

    @Test
    fun testTheRosterFillsInANamelessNetwork() {
        val store = LurkerStore()
        store.apply(
            FrameParser.parseWs(
                """{"kind":"snapshot","networks":[{"networkId":7,"state":"connected","nick":"me","channels":[]}]}""",
            ),
        )
        store.apply(ServerFrame.Networks(listOf(Network(id = 7, name = "Libera"))))
        assertEquals("Libera", store.state.networks[7]?.name)
        // And the live state the snapshot set is still there — the roster carries none of it.
        assertEquals(ConnectionState.Connected, store.state.networks[7]?.state)
        assertEquals("me", store.state.networks[7]?.nick)
    }

    @Test
    fun testARosterRowWithNoNameParsesAsNameless() {
        // Belt to the snapshot's braces: the endpoint always sends a name today, and a row
        // without one must still not invent the word "network".
        val frame = FrameParser.parseNetworks("""{"networks":[{"id":3}]}""")
        if (frame !is ServerFrame.Networks) fail("expected networks, got $frame")
        assertNull(frame.networks.firstOrNull()?.name)
    }

    @Test
    fun testANamelessNetworkStillRendersAsSomething() {
        assertEquals("Unnamed network", Network(id = 1, name = null).displayName)
        assertEquals("Libera", Network(id = 1, name = "Libera").displayName)
    }

    @Test
    fun testAStateEventMaterializesANetworkCreatedSinceWeConnected() {
        // ⚠⚠ `POST /api/networks` starts the connection BEFORE it answers, so the new
        // network's `connecting` can beat the roster re-read that would otherwise create its
        // row. Dropped, the network then appeared at `ConnectionState`'s default and read
        // "offline" while genuinely connected, with no further transition coming to fix it.
        val store = LurkerStore()
        store.apply(ServerFrame.NetworkState(networkId = 12, state = ConnectionState.Connecting, nick = null))
        assertEquals(ConnectionState.Connecting, store.state.networks[12]?.state)
        assertNull(store.state.networks[12]?.name)
    }

    // MARK: - Roster membership

    @Test
    fun testTheRosterRemovesANetworkItNoLongerNames() {
        // The roster is the whole set, so it decides membership too. Without this a deleted
        // network kept its section header, its join-menu entry and its `on:` name for the
        // life of the process — `pruneToBurst` prunes buffers only, and the snapshot merges.
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera"), Network(id = 2, name = "OFTC"))))
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera"))))
        assertNotNull(store.state.networks[1])
        assertNull(store.state.networks[2])
    }

    @Test
    fun testRemovingANetworkTakesItsBuffersWithIt() {
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 2, name = "OFTC"))))
        store.apply(FrameParser.parseWs("""{"kind":"backlog","networkId":2,"target":"#chan","joined":true,"events":[]}"""))
        val key = BufferKey(networkId = 2, target = "#chan").id
        assertNotNull(store.state.buffers[key])
        store.apply(ServerFrame.Networks(emptyList()))
        assertNull(store.state.networks[2])
        // A buffer whose network is gone has no section to sit under and nothing to send to.
        assertNull(store.state.buffers[key])
        assertNull(store.state.messages[key])
    }

    @Test
    fun testAnUnreadableRosterDoesNotWipeTheNetworks() {
        // ⚠⚠ The hazard the removal above creates. "We couldn't read the answer" is not "you
        // have no networks", and reading it that way would delete every network and buffer
        // the user has on one malformed response.
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera"))))
        store.apply(FrameParser.parseNetworks("not json"))
        assertEquals("Libera", store.state.networks[1]?.name)
    }

    @Test
    fun testAnEmptyRosterIsStillAnAnswer() {
        // A user who deleted their last network really does have none.
        val store = LurkerStore()
        store.apply(ServerFrame.Networks(listOf(Network(id = 1, name = "Libera"))))
        store.apply(FrameParser.parseNetworks("""{"networks":[]}"""))
        assertTrue(store.state.networks.isEmpty())
    }

    // MARK: - Config rows

    private val row = """
    {"networks":[{
      "id":4,"name":"Libera","host":"irc.libera.chat","port":6697,"tls":true,
      "trusted_certificates":false,"nick":"me","username":"meuser","realname":"Me",
      "autoconnect":true,"sasl_account":"me","connect_commands":"/msg NickServ help",
      "has_password":true,"has_sasl_password":true,"blocked":true
    }]}
    """.trimIndent()

    @Test
    fun testAConfigRowReadsEveryFieldTheFormEdits() {
        val config = FrameParser.parseNetworkConfigs(row)?.firstOrNull()
        assertEquals(4, config?.id)
        assertEquals("Libera", config?.name)
        assertEquals("irc.libera.chat", config?.host)
        assertEquals(6697, config?.port)
        assertEquals(true, config?.tls)
        assertEquals(false, config?.trustedCertificates)
        assertEquals("me", config?.nick)
        assertEquals("meuser", config?.username)
        assertEquals("Me", config?.realname)
        assertEquals(true, config?.autoconnect)
        assertEquals("me", config?.saslAccount)
        assertEquals("/msg NickServ help", config?.connectCommands)
        assertEquals(true, config?.hasPassword)
        assertEquals(true, config?.hasSaslPassword)
        assertEquals(true, config?.blocked)
    }

    @Test
    fun testAnAbsentBlockedFlagReadsAsNotBlocked() {
        // A server predating the admin allowlist (lurker#298) sends no `blocked`. It has no
        // allowlist to be excluded from, so defaulting the other way would grey out every
        // network on every older server.
        val config = FrameParser.parseNetworkConfigs("""{"networks":[{"id":1,"name":"n","host":"h"}]}""")?.firstOrNull()
        assertEquals(false, config?.blocked)
    }

    @Test
    fun testAMissingPortFallsBackToTheServersDefault() {
        // `int()` reads an absent port as 0, which is not a port anything can connect to.
        val config = FrameParser.parseNetworkConfigs("""{"networks":[{"id":1,"name":"n","host":"h"}]}""")?.firstOrNull()
        assertEquals(6697, config?.port)
    }

    @Test
    fun testAnUnreadableBodyIsNotAnEmptyRoster() {
        // The screen shows a fresh account "add your first network" on an empty list, so an
        // unreadable reply reported as an empty list would greet a failed request with a welcome.
        assertNull(FrameParser.parseNetworkConfigs("not json"))
        assertNull(FrameParser.parseNetworkReply("not json"))
    }

    @Test
    fun testAnEmptyListIsStillAnAnswer() {
        assertEquals(0, FrameParser.parseNetworkConfigs("""{"networks":[]}""")?.size)
    }

    @Test
    fun testACreateReplyCarriesTheSavedRow() {
        val config = FrameParser.parseNetworkReply("""{"network":{"id":9,"name":"OFTC","host":"irc.oftc.net","port":6697,"tls":true,"nick":"me"}}""")
        assertEquals(9, config?.id)
        assertEquals("OFTC", config?.name)
    }

    // MARK: - Request bodies

    private fun draft(): NetworkDraft =
        NetworkDraft(name = "Libera", host = "irc.libera.chat", port = 6697, tls = true, nick = "me")

    @Test
    fun testAnUnchangedSecretIsNotSentAtAll() {
        // ⚠⚠ The reason `SecretEdit` exists. The API never returns a password, so an empty
        // field is both "leave it alone" and "remove it"; sending the field's contents on
        // every save would clear a password the user never touched. Omitted key = untouched
        // column, because the server patches only what it is given.
        val body = draft().jsonBody(creating = false)
        assertNull(body["server_password"])
        assertNull(body["sasl_password"])
    }

    @Test
    fun testAClearedSecretSendsAnExplicitNull() {
        val d = draft().copy(password = SecretEdit.Cleared)
        val body = d.jsonBody(creating = false)
        assertTrue(body["server_password"] is JsonNull)
    }

    @Test
    fun testASetSecretSendsTheValue() {
        val d = draft().copy(saslPassword = SecretEdit.Set("hunter2"))
        assertEquals(JsonPrimitive("hunter2"), d.jsonBody(creating = false)["sasl_password"])
    }

    @Test
    fun testDefaultChannelsRideOnCreateOnly() {
        val d = draft().copy(defaultChannel = "#lurker,#libera")
        assertEquals(JsonPrimitive("#lurker,#libera"), d.jsonBody(creating = true)["default_channel"])
        assertNull(d.jsonBody(creating = false)["default_channel"])
    }

    @Test
    fun testEmptyOptionalTextIsNullRatherThanEmpty() {
        // The columns are nullable and the server derives username/realname from the nick when
        // they are null. An empty string is a real value and would defeat that.
        val d = draft().copy(username = "", realname = null)
        val body = d.jsonBody(creating = true)
        assertTrue(body["username"] is JsonNull)
        assertTrue(body["realname"] is JsonNull)
    }

    @Test
    fun testTheBodyIsEncodable() {
        // On iOS everything here goes through JSONSerialization, which throws on a value it
        // doesn't know. NSNull is fine; a Swift `nil` in an `Any` is not, which is why the
        // optional fields are written out explicitly.
        //
        // Port note: `JSONSerialization.isValidJSONObject` has nothing left to refuse here — a
        // `JsonObject` cannot hold a value that is not JSON. What this can still check is that
        // the body survives being written out and read back.
        val d = draft().copy(password = SecretEdit.Cleared, defaultChannel = "#lurker")
        val body = d.jsonBody(creating = true)
        assertEquals(body, Json.parseToJsonElement(body.toString()))
    }

    // MARK: - Certificate verification

    @Test
    fun testANewDraftVerifiesItsCertificate() {
        // ⚠⚠ `trusted_certificates` reads like permission to accept anything and is the
        // opposite: the server hands it straight to `rejectUnauthorized`, and its column
        // defaults to 1. A draft defaulting it false would silently turn off certificate
        // verification for every network created from this app.
        assertTrue(NetworkDraft().trustedCertificates)
        assertEquals(
            JsonPrimitive(true),
            NetworkDraft(name = "n", host = "h", nick = "n")
                .jsonBody(creating = true)["trusted_certificates"],
        )
    }

    @Test
    fun testAnAbsentVerifyFlagReadsAsVerifying() {
        // Defaults true where every other flag here defaults false, for the same reason: read
        // as false, a row would report a network as not verifying when the column says it
        // does — and the edit form would then save that misreading back.
        val config = FrameParser.parseNetworkConfigs("""{"networks":[{"id":1,"name":"n","host":"h"}]}""")?.firstOrNull()
        assertEquals(true, config?.trustedCertificates)
    }

    @Test
    fun testTurningVerificationOffRoundTripsThroughTheForm() {
        // The one case that must survive an edit: a self-signed server the user deliberately
        // accepted must not be silently re-secured (or, worse, the other way) by opening the
        // form and saving it unchanged.
        val config = FrameParser.parseNetworkConfigs(
            """{"networks":[{"id":1,"name":"n","host":"h","trusted_certificates":false}]}""",
        )!!.first()
        assertFalse(config.trustedCertificates)
        val body = NetworkDraft(editing = config).jsonBody(creating = false)
        assertEquals(JsonPrimitive(false), body["trusted_certificates"])
    }

    // MARK: - Validation

    @Test
    fun testADraftMissingAnIdentityFieldIsRefused() {
        // ⚠⚠ `PATCH` validates nothing server-side — it sets whatever keys it is given — so
        // this layer is the only guard an edit passes through. A network saved with an empty
        // name reads back as *no* name, which is lurker-ios#136's state: it renders "Unnamed
        // network" and re-triggers the roster read for good.
        assertNotNull(NetworkDraft(name = "", host = "h", nick = "n").validationError)
        assertNotNull(NetworkDraft(name = "n", host = "", nick = "n").validationError)
        assertNotNull(NetworkDraft(name = "n", host = "h", nick = "").validationError)
        assertNull(NetworkDraft(name = "n", host = "h", nick = "n").validationError)
    }

    @Test
    fun testWhitespaceIsNotAName() {
        assertNotNull(NetworkDraft(name = "   ", host = "h", nick = "n").validationError)
    }

    @Test
    fun testAPortOutsideTheRangeIsRefused() {
        assertNotNull(NetworkDraft(name = "n", host = "h", port = 0, nick = "n").validationError)
        assertNotNull(NetworkDraft(name = "n", host = "h", port = 70000, nick = "n").validationError)
        assertNull(NetworkDraft(name = "n", host = "h", port = 6667, nick = "n").validationError)
    }

    @Test
    fun testIdentityFieldsAreSentTrimmed() {
        val body = NetworkDraft(name = " Libera ", host = " irc.libera.chat ", nick = " me ")
            .jsonBody(creating = false)
        assertEquals(JsonPrimitive("Libera"), body["name"])
        assertEquals(JsonPrimitive("irc.libera.chat"), body["host"])
        assertEquals(JsonPrimitive("me"), body["nick"])
    }

    @Test
    fun testEditingADraftStartsBothSecretsUnchanged() {
        // The values were never sent to us. Anything but `unchanged` would be a guess, and the
        // guess that loses a password is the one that costs the user their connection.
        val config = FrameParser.parseNetworkConfigs(row)!!.first()
        val d = NetworkDraft(editing = config)
        assertEquals(SecretEdit.Unchanged, d.password)
        assertEquals(SecretEdit.Unchanged, d.saslPassword)
        assertEquals("Libera", d.name)
        assertEquals("irc.libera.chat", d.host)
        assertEquals("me", d.nick)
        // Create-only, and this draft is for an edit.
        assertNull(d.defaultChannel)
    }

    // Port-only: the whole body, key for key, as LurkerKit builds it (the expected JSON is the
    // Swift's own output for the same draft). The suite above reads a body one key at a time,
    // which cannot see a key that should not be there, or a number sent as a string. The server
    // tells all of these apart: an absent key is left alone, a null clears the column, and ""
    // stores an empty value.

    @Test
    fun testACreateBodyIsExactlyWhatLurkerKitSends() {
        val d = NetworkDraft(
            name = " Libera ",
            host = "irc.libera.chat",
            nick = "me",
            username = "",
            saslAccount = "me",
            saslPassword = SecretEdit.Set("hunter2"),
            defaultChannel = "#lurker, #libera",
        )
        assertEquals(
            Json.parseToJsonElement(
                """
                {"autoconnect":true,"connect_commands":null,"default_channel":"#lurker, #libera",
                 "host":"irc.libera.chat","name":"Libera","nick":"me","port":6697,"realname":null,
                 "sasl_account":"me","sasl_password":"hunter2","tls":true,
                 "trusted_certificates":true,"username":null}
                """,
            ),
            d.jsonBody(creating = true),
        )
        // The same draft as an edit: no default channel, and nothing else moves.
        assertEquals(
            Json.parseToJsonElement(
                """
                {"autoconnect":true,"connect_commands":null,
                 "host":"irc.libera.chat","name":"Libera","nick":"me","port":6697,"realname":null,
                 "sasl_account":"me","sasl_password":"hunter2","tls":true,
                 "trusted_certificates":true,"username":null}
                """,
            ),
            d.jsonBody(creating = false),
        )
    }

    // Port-only: an edit, start to finish, without the parser — the config row is built by hand
    // where `NetworkProxyTests` will read one off the wire once `FrameParser` is ported. An
    // untouched saved proxy sends nothing; one whose port moved sends the whole set, trimmed,
    // and still says nothing about a password nobody typed. Expected bodies are the Swift's.

    @Test
    fun testAnEditOfASavedProxyIsExactlyWhatLurkerKitSends() {
        val config = NetworkConfig(
            id = 4,
            name = "Libera",
            host = "irc.libera.chat",
            port = 6697,
            tls = true,
            trustedCertificates = false,
            nick = "me",
            username = "meuser",
            hasPassword = true,
            proxy = NetworkProxy(
                enabled = true, type = ProxyType.Socks5, host = " 127.0.0.1 ", port = 9050,
                username = " me ", hasPassword = true,
            ),
        )
        val untouched = NetworkDraft(editing = config)
        assertEquals(
            Json.parseToJsonElement(
                """
                {"autoconnect":false,"connect_commands":null,"host":"irc.libera.chat","name":"Libera",
                 "nick":"me","port":6697,"realname":null,"sasl_account":null,"tls":true,
                 "trusted_certificates":false,"username":"meuser"}
                """,
            ),
            untouched.jsonBody(creating = false),
        )
        val moved = untouched.copy(proxy = untouched.proxy.edited(port = 9150))
        assertEquals(
            Json.parseToJsonElement(
                """
                {"autoconnect":false,"connect_commands":null,"host":"irc.libera.chat","name":"Libera",
                 "nick":"me","port":6697,"proxy_enabled":true,"proxy_host":"127.0.0.1",
                 "proxy_port":9150,"proxy_type":"socks5","proxy_username":"me","realname":null,
                 "sasl_account":null,"tls":true,"trusted_certificates":false,"username":"meuser"}
                """,
            ),
            moved.jsonBody(creating = false),
        )
        val off = untouched.copy(proxy = untouched.proxy.edited(enabled = false))
        assertEquals(
            Json.parseToJsonElement(
                """
                {"autoconnect":false,"connect_commands":null,"host":"irc.libera.chat","name":"Libera",
                 "nick":"me","port":6697,"proxy_enabled":false,"realname":null,"sasl_account":null,
                 "tls":true,"trusted_certificates":false,"username":"meuser"}
                """,
            ),
            off.jsonBody(creating = false),
        )
    }
}
