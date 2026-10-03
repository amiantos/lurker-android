// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.amiantos.lurkerkit.client.OAuth
import net.amiantos.lurkerkit.client.PKCE
import net.amiantos.lurkerkit.client.asString
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.support.unicodeRegex
import okhttp3.Request
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The OAuth sign-in's requests and replies, against the shapes lurker's server sends and
 * accepts (`server/services/oauth.ts`, `server/routes/oauth.ts`).
 */
class OAuthTests {

    /** The body a request carries, read back as JSON (`JSONSerialization.jsonObject(with: httpBody)`). */
    private fun body(request: Request): JsonObject {
        val buffer = Buffer()
        assertNotNull(request.body).writeTo(buffer)
        return assertNotNull(Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject)
    }

    private fun data(text: String): ByteString = text.encodeUtf8()

    // MARK: - PKCE

    @Test
    fun testTheChallengeMatchesRFC7636sExample() {
        val pkce = PKCE(verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
    }

    /** The server's rules: a verifier is 43–128 of `[A-Za-z0-9._~-]`, a challenge 43 of base64url. */
    @Test
    fun testAFreshPairIsOneTheServerAccepts() {
        val pkce = PKCE()
        assertNotNull(unicodeRegex("^[A-Za-z0-9._~-]{43,128}$").find(pkce.verifier))
        assertNotNull(unicodeRegex("^[A-Za-z0-9_-]{43}$").find(pkce.challenge))
        assertNotEquals(pkce.verifier, PKCE().verifier)
    }

    // MARK: - Register

    @Test
    fun testRegistrationNamesTheAppAndItsRedirect() {
        val request = assertNotNull(
            OAuth.registrationRequest(server = "https://app.lurker.chat", clientName = "Lurker for iPhone"),
        )
        assertEquals("POST", request.method)
        assertEquals("https://app.lurker.chat/api/oauth/register", request.url.toString())
        assertEquals("application/json", request.header("Content-Type"))
        val body = body(request)
        assertEquals("Lurker for iPhone", body["client_name"].asString())
        assertEquals("https://lurker.chat", body["client_uri"].asString())
        assertEquals(JsonArray(listOf(JsonPrimitive("chat.lurker:/oauth"))), body["redirect_uris"])
    }

    @Test
    fun testARegistrationYieldsItsClientId() {
        val data = data("""{"client_id":"abc_123","client_name":"Lurker for iPhone"}""")
        assertEquals(OAuth.Registration.Registered(clientId = "abc_123"), OAuth.registration(status = 201, data = data))
    }

    @Test
    fun testARefusedRegistrationSaysWhy() {
        val old = OAuth.registration(status = 404, data = ByteString.EMPTY) as? OAuth.Registration.Failure ?: fail()
        assertTrue(old.message.contains("2.3.0"), "a server without OAuth should say which version has it")
        val busy = OAuth.registration(status = 429, data = ByteString.EMPTY) as? OAuth.Registration.Failure ?: fail()
        assertTrue(busy.message.contains("Try again"))
        // A 2xx that names no client isn't a registration.
        if (OAuth.registration(status = 201, data = data("{}")) !is OAuth.Registration.Failure) fail()
    }

    // MARK: - Authorize

    @Test
    fun testTheApprovalPageCarriesEveryParameter() {
        val url = assertNotNull(
            OAuth.authorizeURL(
                server = "https://app.lurker.chat",
                clientId = "roswell~a+b",
                challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                state = "st_1-x",
            ),
        )
        assertEquals("app.lurker.chat", url.host)
        assertEquals("/oauth/authorize", url.encodedPath)
        val query = url.queryParameterNames.associateWith { url.queryParameter(it) ?: "" }
        assertEquals(
            mapOf(
                "response_type" to "code",
                "client_id" to "roswell~a+b",
                "redirect_uri" to "chat.lurker:/oauth",
                "code_challenge" to "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method" to "S256",
                "state" to "st_1-x",
            ),
            query,
        )
        // The server's query parser reads a bare `+` as a space.
        assertTrue(url.toString().contains("client_id=roswell~a%2Bb"))
    }

    /** The redirect exactly as the server's `withQuery` builds it, which escapes `~`. */
    @Test
    fun testTheServersRedirectYieldsItsCode() {
        val url = "chat.lurker:/oauth?code=roswell%7Ea_b-c&state=st_1-x"
        assertEquals(OAuth.Callback.Code("roswell~a_b-c"), OAuth.callback(url, state = "st_1-x"))
    }

    @Test
    fun testDenyIsItsOwnAnswer() {
        val url = "chat.lurker:/oauth?error=access_denied&state=st_1-x"
        assertEquals(OAuth.Callback.Denied, OAuth.callback(url, state = "st_1-x"))
    }

    @Test
    fun testAnAnswerToAnotherAttemptIsRefused() {
        val otherState = "chat.lurker:/oauth?code=abc&state=other"
        assertEquals(OAuth.Callback.Invalid, OAuth.callback(otherState, state = "st_1-x"))
        val noState = "chat.lurker:/oauth?code=abc"
        assertEquals(OAuth.Callback.Invalid, OAuth.callback(noState, state = "st_1-x"))
        val noCode = "chat.lurker:/oauth?state=st_1-x"
        assertEquals(OAuth.Callback.Invalid, OAuth.callback(noCode, state = "st_1-x"))
    }

    // MARK: - Exchange

    @Test
    fun testTheExchangeSendsWhatTheServerChecks() {
        val request = assertNotNull(
            OAuth.tokenRequest(
                server = "https://app.lurker.chat", clientId = "cid", code = "roswell~code", verifier = "ver",
            ),
        )
        assertEquals("POST", request.method)
        assertEquals("https://app.lurker.chat/api/oauth/token", request.url.toString())
        assertEquals("application/json", request.header("Content-Type"))
        val body = body(request).mapValues { (_, value) -> value.asString() ?: fail("not a string: $value") }
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "cid",
                "code" to "roswell~code",
                "redirect_uri" to "chat.lurker:/oauth",
                "code_verifier" to "ver",
            ),
            body,
        )
    }

    @Test
    fun testATokenReplyYieldsTheToken() {
        val data = data("""{"access_token":"roswell~tok","token_type":"Bearer","created_at":1757462400}""")
        assertEquals(OAuth.TokenGrant.Token("roswell~tok"), OAuth.tokenGrant(status = 200, data = data))
    }

    /** `invalid_client` means register again, which a plain retry would never do. */
    @Test
    fun testAnUnknownClientIsItsOwnAnswer() {
        val data = data("""{"error":"invalid_client","error_description":"unknown client_id"}""")
        assertEquals(OAuth.TokenGrant.UnknownClient, OAuth.tokenGrant(status = 401, data = data))
    }

    @Test
    fun testASpentCodeIsAFailure() {
        val data = data("""{"error":"invalid_grant","error_description":"the code is invalid"}""")
        assertEquals(OAuth.TokenGrant.Failure("Sign-in didn't finish. Try again."), OAuth.tokenGrant(status = 400, data = data))
        if (OAuth.tokenGrant(status = 200, data = data("{}")) !is OAuth.TokenGrant.Failure) fail()
    }

    // MARK: - Check a saved registration

    @Test
    fun testTheCheckAsksRevokeWithATokenThatCantExist() {
        val request = assertNotNull(OAuth.clientCheckRequest(server = "https://app.lurker.chat", clientId = "cid"))
        assertEquals("POST", request.method)
        assertEquals("https://app.lurker.chat/api/oauth/revoke", request.url.toString())
        assertEquals("application/json", request.header("Content-Type"))
        val body = body(request).mapValues { (_, value) -> value.asString() ?: fail("not a string: $value") }
        assertEquals("cid", body["client_id"])
        val token = assertNotNull(body["token"])
        // Port note: `String.count` counts characters; a base64url token is ASCII, so its
        // UTF-16 length is the same number.
        assertEquals(43, token.length)
        // lurker.chat forwards a revoke to a cell only for a `<cell>~` token; this one must go nowhere.
        assertFalse(token.contains("~"))
    }

    @Test
    fun testTheCheckTellsKnownFromUnknownFromNoAnswer() {
        assertEquals(true, OAuth.clientKnown(status = 200, data = data("{}")))
        val unknown = data("""{"error":"invalid_client","error_description":"unknown client_id"}""")
        assertEquals(false, OAuth.clientKnown(status = 401, data = unknown))
        // No OAuth routes at all, so registering again gets to say the server is too old.
        assertEquals(false, OAuth.clientKnown(status = 404, data = ByteString.EMPTY))
        // A throttled, failing or unreachable server says nothing about the registration.
        assertNull(OAuth.clientKnown(status = 429, data = ByteString.EMPTY))
        assertNull(OAuth.clientKnown(status = 503, data = ByteString.EMPTY))
        assertNull(OAuth.clientKnown(status = 0, data = ByteString.EMPTY))
    }

    // MARK: - Saved registrations

    @Test
    fun testRegistrationsAreKeptPerServer() {
        // Port note: a fresh in-memory store stands in for a defaults suite removed afterwards.
        val defaults = InMemoryDefaultsStorage()
        val clients = OAuthClients(defaults)

        assertNull(clients.clientId(server = "https://app.lurker.chat"))
        clients.save("hosted", server = "https://app.lurker.chat")
        clients.save("home", server = "http://xerxes.local:8010")
        clients.forget("https://app.lurker.chat")
        assertNull(clients.clientId(server = "https://app.lurker.chat"))
        assertEquals("home", clients.clientId(server = "http://xerxes.local:8010"))
    }

    // Port-only:

    /**
     * The saved ids are read the way `as? [String: String]` reads them: one value that isn't a
     * string and none of them is there.
     */
    @Test
    fun testSavedRegistrationsAreReadAllOrNothing() {
        val defaults = InMemoryDefaultsStorage()
        defaults.set(
            JsonObject(mapOf("https://a" to JsonPrimitive("one"), "https://b" to JsonPrimitive(2))),
            key = "lurker.oauth.clientIds",
        )
        assertNull(OAuthClients(defaults).clientId(server = "https://a"))
        OAuthClients(defaults).save("three", server = "https://c")
        assertEquals(JsonObject(mapOf("https://c" to JsonPrimitive("three"))), defaults.dictionary("lurker.oauth.clientIds"))
    }

    /**
     * `URLComponents.queryItems` leaves a `+` as a `+`, and decodes `%2B` to one; `HttpUrl`'s
     * own reader would make the first a space. A code carrying either reaches the exchange as
     * the server minted it.
     */
    @Test
    fun testTheCallbackReadsAPlusAsAPlus() {
        assertEquals(
            OAuth.Callback.Code("a+b"),
            OAuth.callback("chat.lurker:/oauth?code=a+b&state=s", state = "s"),
        )
        assertEquals(
            OAuth.Callback.Code("a+b"),
            OAuth.callback("chat.lurker:/oauth?code=a%2Bb&state=s", state = "s"),
        )
        // A fragment is not part of the query, and the first of a repeated name wins.
        assertEquals(
            OAuth.Callback.Code("one"),
            OAuth.callback("chat.lurker:/oauth?code=one&code=two&state=s#state=t", state = "s"),
        )
        // A malformed escape is no value, so no code.
        assertEquals(OAuth.Callback.Invalid, OAuth.callback("chat.lurker:/oauth?code=%E&state=s", state = "s"))
    }

    /** LurkerKit's rule written out: standard base64 with `-` and `_`, and no padding. */
    @Test
    fun testBase64URLDropsThePadding() {
        assertEquals("-_8", OAuth.base64URL(okio.ByteString.of(0xFB.toByte(), 0xFF.toByte())))
        assertEquals("AA", OAuth.base64URL(okio.ByteString.of(0)))
        assertEquals("", OAuth.base64URL(ByteString.EMPTY))
    }

    /** Only http(s) servers make a request, as everywhere `HttpUrl` stands in for `URL`. */
    @Test
    fun testANonHttpServerMakesNoRequest() {
        assertNull(OAuth.registrationRequest(server = "chat.lurker.example", clientName = "x"))
        assertNull(OAuth.tokenRequest(server = "", clientId = "c", code = "c", verifier = "v"))
        assertNull(OAuth.authorizeURL(server = "ftp://h", clientId = "c", challenge = "c", state = "s"))
    }
}
