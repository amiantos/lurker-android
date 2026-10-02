// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkProxy
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.model.SecretEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // MARK: - Nameless networks (lurker-ios#136)

    // MARK: - Roster membership

    // MARK: - Config rows

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

    // Waiting on FrameParser, ServerFrame: testAStateEventCarriesTheConnectionAndItsNick,
    // testAStateEventWithoutANickSaysNothingAboutIt, testAStateEventCarriesNoTargetAndIsParsedAnyway,
    // testARosterRowWithNoNameParsesAsNameless
    //
    // Waiting on FrameParser (reading a config row; and the `row` fixture they share):
    // testAConfigRowReadsEveryFieldTheFormEdits, testAnAbsentBlockedFlagReadsAsNotBlocked,
    // testAMissingPortFallsBackToTheServersDefault, testAnUnreadableBodyIsNotAnEmptyRoster,
    // testAnEmptyListIsStillAnAnswer, testACreateReplyCarriesTheSavedRow,
    // testAnAbsentVerifyFlagReadsAsVerifying, testTurningVerificationOffRoundTripsThroughTheForm,
    // testEditingADraftStartsBothSecretsUnchanged
    //
    // Waiting on LurkerStore, Network (several through FrameParser too):
    // testTheStoreAppliesTheStateAndTheNick, testADisconnectKeepsTheNick,
    // testTheRosterNameSurvivesAStateEvent, testASnapshotForAnUnknownNetworkLeavesItNameless,
    // testTheRosterFillsInANamelessNetwork, testAStateEventMaterializesANetworkCreatedSinceWeConnected,
    // testTheRosterRemovesANetworkItNoLongerNames, testRemovingANetworkTakesItsBuffersWithIt,
    // testAnUnreadableRosterDoesNotWipeTheNetworks, testAnEmptyRosterIsStillAnAnswer
    //
    // Waiting on Network: testANamelessNetworkStillRendersAsSomething

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
        val moved = untouched.copy(proxy = untouched.proxy.copy(port = 9150))
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
        val off = untouched.copy(proxy = untouched.proxy.copy(enabled = false))
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
