// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.ProxyDraft
import net.amiantos.lurkerkit.model.ProxyType
import net.amiantos.lurkerkit.model.SecretEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proxies (lurker#303) below the form: reading a saved proxy, what a draft sends and when, and
 * the port that follows the type.
 */
class NetworkProxyTests {

    /**
     * A fresh network.
     *
     * Port note: in LurkerKit this helper is `draft(editing json: String? = nil)` and, given a
     * `proxy` JSON, returns a draft of a row read through `FrameParser.parseNetworkReply`. Only
     * the fresh half is here; the editing half, and the `row(proxy:)`, `saved` and `proxyKeys`
     * helpers beside it, come back with the tests that wait on `FrameParser`.
     */
    private fun draft(): NetworkDraft =
        NetworkDraft(name = "Libera", host = "irc.libera.chat", port = 6697, tls = true, nick = "me")

    // MARK: - Reading a row

    // MARK: - What a draft sends

    @Test
    fun testAnEnabledProxySendsTheWholeSet() {
        val d = draft().copy(proxy = ProxyDraft(enabled = true, host = " 127.0.0.1 ", port = 9050))
        val body = d.jsonBody(creating = true)
        assertEquals(JsonPrimitive(true), body["proxy_enabled"])
        assertEquals(JsonPrimitive("socks5"), body["proxy_type"])
        assertEquals(JsonPrimitive("127.0.0.1"), body["proxy_host"])
        assertEquals(JsonPrimitive(9050), body["proxy_port"])
        // Null rather than "", matching the other optional text columns.
        assertTrue(body["proxy_username"] is JsonNull)
        assertNull(body["proxy_password"])
    }

    @Test
    fun testAnEnabledProxyNeedsAnAddressAndAPort() {
        var d = draft().copy(proxy = ProxyDraft(enabled = true, host = "  "))
        assertNotNull(d.validationError)
        d = d.copy(proxy = d.proxy.copy(host = "127.0.0.1"))
        assertNull(d.validationError)
        d = d.copy(proxy = d.proxy.copy(port = 0))
        assertNotNull(d.validationError)
        d = d.copy(proxy = d.proxy.copy(port = 70000))
        assertNotNull(d.validationError)
    }

    @Test
    fun testASwitchedOffProxyIsNotValidated() {
        // None of it is sent, so a half-finished one mustn't block saving everything else.
        val d = draft().copy(proxy = ProxyDraft(enabled = false, host = "", port = 0))
        assertNull(d.validationError)
    }

    @Test
    fun testTheBodyIsEncodable() {
        // Port note: `JSONSerialization.isValidJSONObject` in LurkerKit, which a `JsonObject`
        // cannot fail. See `NetworkConfigTests.testTheBodyIsEncodable`.
        val d = draft().copy(
            proxy = ProxyDraft(enabled = true, host = "127.0.0.1", password = SecretEdit.Cleared),
            certificate = CertificateSource.Imported(cert = "c", key = "k"),
        )
        val body = d.jsonBody(creating = true)
        assertEquals(body, Json.parseToJsonElement(body.toString()))
    }

    // MARK: - Type and port

    @Test
    fun testANewProxyStartsOnTheSocksPort() {
        assertEquals(1080, ProxyDraft().port)
    }

    @Test
    fun testAnUntouchedDefaultPortFollowsTheType() {
        // Otherwise picking HTTP would quietly keep SOCKS's 1080.
        var proxy = ProxyDraft()
        proxy = proxy.setType(ProxyType.Http)
        assertEquals(3128, proxy.port)
        proxy = proxy.setType(ProxyType.Socks5)
        assertEquals(1080, proxy.port)
    }

    @Test
    fun testAChosenPortSurvivesATypeChange() {
        var proxy = ProxyDraft(port = 9050)
        proxy = proxy.setType(ProxyType.Http)
        assertEquals(ProxyType.Http, proxy.type)
        assertEquals(9050, proxy.port)
    }

    // Waiting on FrameParser (every one reads a row through `parseNetworkReply`):
    // testASavedProxyReadsEveryPart, testNullAndAbsentBothMeanNoProxy,
    // testASwitchedOffProxyIsStillASavedOne, testAnImpossiblePortReadsAsTheTypesDefault,
    // testEditingStartsFromTheSavedProxyButNotItsPassword, testAnOrdinaryNetworkSendsNoProxyKeys,
    // testSwitchingASavedProxyOffSendsOnlyTheSwitch, testAnUntouchedProxyIsNotSent,
    // testAnUntouchedProxyIsNotValidated, testEditingASavedProxySendsTheWholeSetAsShown,
    // testTurningAProxyOffAndOnAgainIsNoChange, testTheProxyPasswordFollowsSecretEdit

    // Port-only: Swift's `mutating func` on a `var` copy gets this for free. Here `setType`
    // returns the changed draft and must leave the one it was called on alone — a form holding
    // the old value has to keep seeing the old value.

    @Test
    fun testSetTypeLeavesTheOriginalAlone() {
        val before = ProxyDraft()
        val after = before.setType(ProxyType.Http)
        assertEquals(ProxyType.Socks5, before.type)
        assertEquals(1080, before.port)
        assertEquals(ProxyType.Http, after.type)
        assertEquals(3128, after.port)
        // Everything but the type and the port it brought along is carried over.
        assertEquals(before.copy(type = ProxyType.Http, port = 3128), after)
    }

    // Port-only: LurkerKit's initialiser takes `port: Int? = nil` and falls back to the type's
    // default; here that is a default argument reading the `type` beside it.

    @Test
    fun testAnUnnamedPortIsTheTypesDefault() {
        assertEquals(3128, ProxyDraft(type = ProxyType.Http).port)
        assertEquals(1080, ProxyDraft(type = ProxyType.Socks5).port)
        assertEquals(8080, ProxyDraft(type = ProxyType.Http, port = 8080).port)
    }
}
