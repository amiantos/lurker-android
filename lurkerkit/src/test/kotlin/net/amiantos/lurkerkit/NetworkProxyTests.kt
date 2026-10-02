// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.model.CertificateSource
import net.amiantos.lurkerkit.model.NetworkConfig
import net.amiantos.lurkerkit.model.NetworkDraft
import net.amiantos.lurkerkit.model.NetworkProxy
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

    private fun row(proxy: String): NetworkConfig? =
        FrameParser.parseNetworkReply(
            """{"network":{"id":1,"name":"n","host":"h","tls":true,"nick":"me","proxy":$proxy}}""",
        )

    private val saved =
        """{"enabled":true,"type":"http","host":"127.0.0.1","port":3128,"username":"me","has_password":true}"""

    /** A fresh network, or one being edited whose `proxy` is `json`. */
    private fun draft(editing: String? = null): NetworkDraft {
        val config = editing?.let { row(proxy = it) }
            ?: return NetworkDraft(name = "Libera", host = "irc.libera.chat", port = 6697, tls = true, nick = "me")
        return NetworkDraft(editing = config)
    }

    private fun proxyKeys(body: JsonObject): Set<String> = body.keys.filter { it.startsWith("proxy_") }.toSet()

    // MARK: - Reading a row

    @Test
    fun testASavedProxyReadsEveryPart() {
        assertEquals(
            NetworkProxy(enabled = true, type = ProxyType.Http, host = "127.0.0.1", port = 3128, username = "me", hasPassword = true),
            row(proxy = saved)?.proxy,
        )
    }

    @Test
    fun testNullAndAbsentBothMeanNoProxy() {
        assertNull(row(proxy = "null")?.proxy)
        val absent = FrameParser.parseNetworkReply("""{"network":{"id":1,"name":"n","host":"h"}}""")
        assertNotNull(absent)
        assertNull(absent.proxy)
    }

    @Test
    fun testASwitchedOffProxyIsStillASavedOne() {
        // `enabled` is the only part the dial reads; the details stay saved while it's off.
        val proxy = row(
            proxy = """{"enabled":false,"type":"socks5","host":"127.0.0.1","port":9050,"username":null,"has_password":false}""",
        )?.proxy
        assertNotNull(proxy)
        assertEquals(false, proxy.enabled)
        assertEquals(9050, proxy.port)
    }

    @Test
    fun testAnImpossiblePortReadsAsTheTypesDefault() {
        assertEquals(3128, row(proxy = """{"enabled":false,"type":"http","host":"h","port":null}""")?.proxy?.port)
    }

    // MARK: - What a draft sends

    @Test
    fun testEditingStartsFromTheSavedProxyButNotItsPassword() {
        val d = draft(editing = saved)
        assertNotNull(d.savedProxy)
        assertEquals(row(proxy = saved)?.proxy, d.savedProxy)
        assertEquals(ProxyDraft(enabled = true, type = ProxyType.Http, host = "127.0.0.1", port = 3128, username = "me"), d.proxy)
        assertEquals(SecretEdit.Unchanged, d.proxy.password)
    }

    @Test
    fun testAnOrdinaryNetworkSendsNoProxyKeys() {
        // ⚠ Not even `proxy_enabled: false`. A save with nothing to say about a proxy says nothing,
        // so a rename carries nothing for a locked-down instance's proxy rule to weigh.
        assertEquals(emptySet(), proxyKeys(draft().jsonBody(creating = true)))
        assertEquals(emptySet(), proxyKeys(draft(editing = "null").jsonBody(creating = false)))
    }

    @Test
    fun testSwitchingASavedProxyOffSendsOnlyTheSwitch() {
        // Turning a proxy off must always be possible, so it's sent on its own — nothing else in
        // the body for the server to refuse.
        var d = draft(editing = saved)
        d = d.copy(proxy = d.proxy.edited(enabled = false))
        val body = d.jsonBody(creating = false)
        assertEquals(setOf("proxy_enabled"), proxyKeys(body))
        assertEquals(JsonPrimitive(false), body["proxy_enabled"])
    }

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
    fun testAnUntouchedProxyIsNotSent() {
        // ⚠⚠ Not even resent as read. The form shows the columns normalized — trimmed, and an
        // unknown type or impossible port read as a default — so resending what's shown would
        // rewrite whatever archive import left there, on a rename, which a locked-down instance
        // then refuses as a proxy change.
        assertEquals(emptySet(), proxyKeys(draft(editing = saved).jsonBody(creating = false)))
        val odd = """{"enabled":true,"type":"socks4","host":" 127.0.0.1 ","port":99999,"username":" me "}"""
        assertEquals(emptySet(), proxyKeys(draft(editing = odd).jsonBody(creating = false)))
    }

    @Test
    fun testAnUntouchedProxyIsNotValidated() {
        // Nothing of it is sent, so whatever the columns hold mustn't block saving the rest.
        assertNull(draft(editing = """{"enabled":true,"type":"socks5","host":"","port":1080}""").validationError)
    }

    @Test
    fun testEditingASavedProxySendsTheWholeSetAsShown() {
        var d = draft(editing = """{"enabled":true,"type":"socks5","host":" 127.0.0.1 ","port":9050,"username":" me "}""")
        d = d.copy(proxy = d.proxy.edited(port = 9150))
        val body = d.jsonBody(creating = false)
        assertEquals(JsonPrimitive(true), body["proxy_enabled"])
        assertEquals(JsonPrimitive("socks5"), body["proxy_type"])
        assertEquals(JsonPrimitive("127.0.0.1"), body["proxy_host"])
        assertEquals(JsonPrimitive(9150), body["proxy_port"])
        assertEquals(JsonPrimitive("me"), body["proxy_username"])
    }

    @Test
    fun testTurningAProxyOffAndOnAgainIsNoChange() {
        var d = draft(editing = saved)
        d = d.copy(proxy = d.proxy.edited(enabled = false))
        d = d.copy(proxy = d.proxy.edited(enabled = true))
        assertEquals(emptySet(), proxyKeys(d.jsonBody(creating = false)))
    }

    @Test
    fun testTheProxyPasswordFollowsSecretEdit() {
        var d = draft(editing = saved)
        d = d.copy(proxy = d.proxy.edited(password = SecretEdit.Set("hunter2")))
        assertEquals(JsonPrimitive("hunter2"), d.jsonBody(creating = false)["proxy_password"])
        d = d.copy(proxy = d.proxy.edited(password = SecretEdit.Cleared))
        assertTrue(d.jsonBody(creating = false)["proxy_password"] is JsonNull)
    }

    @Test
    fun testAnEnabledProxyNeedsAnAddressAndAPort() {
        var d = draft().copy(proxy = ProxyDraft(enabled = true, host = "  "))
        assertNotNull(d.validationError)
        d = d.copy(proxy = d.proxy.edited(host = "127.0.0.1"))
        assertNull(d.validationError)
        d = d.copy(proxy = d.proxy.edited(port = 0))
        assertNotNull(d.validationError)
        d = d.copy(proxy = d.proxy.edited(port = 70000))
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
        assertEquals(ProxyDraft(type = ProxyType.Http, port = 3128), after)
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
