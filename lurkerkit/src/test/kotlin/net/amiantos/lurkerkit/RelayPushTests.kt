// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.amiantos.lurkerkit.push.DeviceKeys
import net.amiantos.lurkerkit.push.PushConfig
import net.amiantos.lurkerkit.push.PushRoute
import net.amiantos.lurkerkit.push.RelayPush
import net.amiantos.lurkerkit.push.RelayPushKeys
import net.amiantos.lurkerkit.push.WebPushDecrypt
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pushes relayed through push.lurker.chat (lurker-dev/RELAY_PLAN.md §6.2): the route, the endpoint,
 * the decryption, and the map the decrypted body becomes.
 *
 * `relayVectors.json` is a copy of lurker's `server/services/push/relayVectors.json`, which the
 * server's own suite rebuilds from its real push preparation and encryption — so passing here means
 * decrypting what the server actually sends. Copy it again when the server's changes.
 */
class RelayPushTests {

    private class Vector(
        val name: String,
        val plaintext: String,
        val uaPrivate: ByteArray,
        val uaPublic: ByteArray,
        val authSecret: ByteArray,
        val body: ByteArray,
    ) {
        val keys get() = DeviceKeys.fromRaw(uaPrivate, uaPublic, authSecret)
    }

    private val vectors: List<Vector> by lazy {
        val text = javaClass.getResourceAsStream("/relayVectors.json")!!.readBytes().decodeToString()
        Json.parseToJsonElement(text).jsonObject.getValue("vectors").jsonArray.map {
            val v = it.jsonObject
            fun s(key: String) = v.getValue(key).jsonPrimitive.content
            fun b(key: String) = Base64.getUrlDecoder().decode(s(key))
            Vector(s("name"), s("plaintext"), b("uaPrivate"), b("uaPublic"), b("authSecret"), b("body"))
        }
    }

    // MARK: - Decryption

    @Test
    fun testDecryptsEveryVectorTheServerBuilt() {
        assertTrue(vectors.size >= 4, "the RFC 8291 example plus the server's pushes")
        for (v in vectors) {
            val plain = assertNotNull(WebPushDecrypt.decrypt(v.body, v.keys), v.name)
            assertEquals(v.plaintext, plain.decodeToString(), v.name)
        }
    }

    @Test
    fun testATamperedByteAnywhereFailsCleanly() {
        val v = vectors.first { it.name == "dm" }
        // The header (salt, record size, key id) and the ciphertext and tag alike.
        for (i in listOf(0, 15, 20, 21, 50, 86, 100, v.body.size - 1)) {
            val tampered = v.body.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(WebPushDecrypt.decrypt(tampered, v.keys), "byte $i")
        }
    }

    @Test
    fun testTheWrongKeysFailCleanly() {
        val v = vectors.first { it.name == "dm" }
        val other = vectors.first { it.name == "highlight" }
        assertNull(WebPushDecrypt.decrypt(v.body, other.keys))
        assertNull(WebPushDecrypt.decrypt(v.body, DeviceKeys.generate()))
        // The right private key with the wrong auth secret.
        assertNull(WebPushDecrypt.decrypt(v.body, DeviceKeys.fromRaw(v.uaPrivate, v.uaPublic, ByteArray(16))))
    }

    @Test
    fun testATruncatedOrEmptyBodyFailsCleanly() {
        val v = vectors.first { it.name == "dm" }
        for (length in listOf(0, 1, 20, 21, 86, 100, v.body.size - 1)) {
            assertNull(WebPushDecrypt.decrypt(v.body.copyOf(length), v.keys), "length $length")
        }
    }

    @Test
    fun testAFreshDeviceRoundTripsThroughItsStoredForm() {
        val keys = DeviceKeys.generate()
        assertEquals(65, keys.publicKey.size)
        assertEquals(0x04.toByte(), keys.publicKey[0])
        assertEquals(16, keys.authSecret.size)
        // base64url, no padding: what the server's web-push expects for keys.p256dh and keys.auth.
        assertTrue(keys.p256dh.none { it == '=' || it == '+' || it == '/' })
        assertEquals(87, keys.p256dh.length)
        assertEquals(22, keys.auth.length)
        val back = assertNotNull(DeviceKeys.fromPersisted(keys.persisted()))
        assertContentEquals(keys.publicKey, back.publicKey)
        assertContentEquals(keys.authSecret, back.authSecret)
        assertContentEquals(keys.privateKey.encoded, back.privateKey.encoded)
    }

    @Test
    fun testKeysAreMadeOnceAndKept() {
        val storage = InMemorySecureStorage()
        val first = RelayPushKeys(storage).loadOrCreate()
        val again = RelayPushKeys(storage).loadOrCreate()
        assertEquals(first.p256dh, again.p256dh)
        assertEquals(first.auth, again.auth)
        // Unreadable keys are replaced, not a crash.
        storage.stored.keys.toList().forEach { storage.write(it, okio.ByteString.EMPTY) }
        assertNotNull(RelayPushKeys(storage).loadOrCreate())
    }

    // MARK: - The decrypted body as FCM's string map

    @Test
    fun testFlattensTheWayTheServerBuildsItsFcmMap() {
        // fcmSender.buildFcmMessage: String(value) for every value, nulls left out.
        val map = assertNotNull(
            RelayPush.flatten(
                """{"kind":"dm","networkId":3,"badge":0,"messageId":9001,"nick":null,"ok":true,"title":"bob (Libera)","x":1.5}""",
            ),
        )
        assertEquals(
            mapOf(
                "kind" to "dm",
                "networkId" to "3",
                "badge" to "0",
                "messageId" to "9001",
                "ok" to "true",
                "title" to "bob (Libera)",
                "x" to "1.5",
            ),
            map,
        )
    }

    @Test
    fun testEveryServerVectorFlattensToAMapWithItsRoutingIntact() {
        for (v in vectors.filter { it.name != "rfc8291-appendix-a" }) {
            val body = Json.parseToJsonElement(v.plaintext) as JsonObject
            val map = assertNotNull(RelayPush.flatten(v.plaintext), v.name)
            for (key in listOf("kind", "networkId", "target", "title", "body", "tag")) {
                assertEquals(body.getValue(key).jsonPrimitive.content, map[key], "${v.name}.$key")
            }
        }
    }

    @Test
    fun testFlattenRefusesWhatIsntAnObject() {
        assertNull(RelayPush.flatten("not json"))
        assertNull(RelayPush.flatten("[1,2]"))
        assertNull(RelayPush.flatten("\"a string\""))
    }

    // MARK: - The route

    private val key = vectors.first().uaPublic.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    @Test
    fun testAServerWithFcmIsDirect() {
        val config = PushConfig(key, listOf("webpush", "apns", "fcm"), relay = "https://push.lurker.chat")
        assertEquals(PushRoute.Native, RelayPush.route(config, allowAnyHttpsRelay = false))
    }

    @Test
    fun testTheRelayOnlyWhenTheAdminTurnedItOn() {
        // No `relay`: the admin hasn't opted in, and the app contacts nothing.
        assertEquals(PushRoute.None, RelayPush.route(PushConfig(key, listOf("webpush"), null), allowAnyHttpsRelay = true))
        val route = RelayPush.route(PushConfig(key, listOf("webpush"), "https://push.lurker.chat"), allowAnyHttpsRelay = false)
        assertEquals(PushRoute.Relay("https://push.lurker.chat", key), route)
    }

    @Test
    fun testAReleaseBuildTrustsOnlyPushLurkerChat() {
        for (relay in listOf(
            "https://evil.example",
            "https://push.lurker.chat.evil.example",
            "https://push.lurker.chat:8443",
            "http://push.lurker.chat",
            "https://push.lurker.chat/elsewhere",
            "https://user@push.lurker.chat",
            "not a url",
        )) {
            assertEquals(PushRoute.None, RelayPush.route(PushConfig(key, listOf("webpush"), relay), false), relay)
        }
        // Spelling differences that are still exactly the official origin.
        for (relay in listOf("https://PUSH.lurker.chat", "https://push.lurker.chat/", "https://push.lurker.chat:443")) {
            assertEquals(
                PushRoute.Relay("https://push.lurker.chat", key),
                RelayPush.route(PushConfig(key, listOf("webpush"), relay), false),
                relay,
            )
        }
    }

    @Test
    fun testADebugBuildTakesAnyHttpsRelayButNeverPlainHttp() {
        val local = RelayPush.route(PushConfig(key, listOf("webpush"), "https://relay.local:8030"), allowAnyHttpsRelay = true)
        assertEquals(PushRoute.Relay("https://relay.local:8030", key), local)
        assertEquals(
            PushRoute.None,
            RelayPush.route(PushConfig(key, listOf("webpush"), "http://relay.local:8030"), allowAnyHttpsRelay = true),
        )
    }

    @Test
    fun testNoKeyNoRelay() {
        for (publicKey in listOf(null, "", "  ")) {
            assertEquals(PushRoute.None, RelayPush.route(PushConfig(publicKey, listOf("webpush"), "https://push.lurker.chat"), false))
        }
    }

    @Test
    fun testTheEndpointIsTheTokenThenTheKeyVerbatim() {
        val route = PushRoute.Relay("https://push.lurker.chat", "BKey-_abc")
        assertEquals(
            "https://push.lurker.chat/relay-to/fcm/dX7:APA91b-x_y.z/BKey-_abc",
            route.endpoint("dX7:APA91b-x_y.z"),
        )
    }
}
