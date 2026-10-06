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
        val first = assertNotNull(RelayPushKeys(storage).loadOrCreate())
        val again = assertNotNull(RelayPushKeys(storage).loadOrCreate())
        assertEquals(first.p256dh, again.p256dh)
        assertEquals(first.auth, again.auth)
        // Unreadable keys are replaced, not a crash.
        storage.stored.keys.toList().forEach { storage.write(it, okio.ByteString.EMPTY) }
        assertNotNull(RelayPushKeys(storage).loadOrCreate())
    }

    @Test
    fun testKeysThatDontStoreAreNeverHandedOut() {
        // A Keystore that won't take the write: filing keys the messaging service can't load would
        // make every push to this phone unreadable.
        val refusing = object : net.amiantos.lurkerkit.session.SecureStorage {
            override fun read(account: String): okio.ByteString? = null
            override fun write(account: String, data: okio.ByteString) {}
            override fun delete(account: String) {}
        }
        assertNull(RelayPushKeys(refusing).loadOrCreate())
    }

    @Test
    fun testLoadedKeysAreCachedAndAWriteReplacesThem() {
        val storage = InMemorySecureStorage()
        val keys = RelayPushKeys(storage)
        val made = assertNotNull(keys.loadOrCreate())
        // Gone from storage (a Keystore reset), still the same in this process: what was filed stays
        // openable until the next launch re-files.
        storage.stored.clear()
        assertEquals(made.p256dh, keys.load()?.p256dh)
        // A fresh instance reads storage, finds nothing, and makes new keys — a different p256dh,
        // which is what tells the registrar to file again.
        val fresh = assertNotNull(RelayPushKeys(storage).loadOrCreate())
        assertTrue(fresh.p256dh != made.p256dh)
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
            assertEquals(PushRoute.Unsupported, RelayPush.route(PushConfig(key, listOf("webpush"), relay), false), relay)
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
            PushRoute.Unsupported,
            RelayPush.route(PushConfig(key, listOf("webpush"), "http://relay.local:8030"), allowAnyHttpsRelay = true),
        )
    }

    @Test
    fun testNoKeyNoRelay() {
        // A relay advertised without the key the endpoint needs is a relay this app can't use.
        for (publicKey in listOf(null, "", "  ")) {
            assertEquals(PushRoute.Unsupported, RelayPush.route(PushConfig(publicKey, listOf("webpush"), "https://push.lurker.chat"), false))
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

    // MARK: - Hostile bodies, properly encrypted

    /**
     * RFC 8291 encryption for the tests that need a body the decryptor will authenticate: a hostile
     * plaintext the server never sends but a compromised one could. [recordSize] is the header's;
     * a body bigger than the 4 KB a push can be still declares a record that fits it.
     */
    private fun encrypt(plaintext: ByteArray, to: DeviceKeys, recordSize: Int = 4096): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            javax.crypto.Mac.getInstance("HmacSHA256").run {
                init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val sender = generator.generateKeyPair()
        val w = (sender.public as java.security.interfaces.ECPublicKey).w
        fun fixed(n: java.math.BigInteger) = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        val senderPoint = byteArrayOf(0x04) + fixed(w.affineX) + fixed(w.affineY)
        val receiver = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(
                java.security.spec.ECPoint(
                    java.math.BigInteger(1, to.publicKey.copyOfRange(1, 33)),
                    java.math.BigInteger(1, to.publicKey.copyOfRange(33, 65)),
                ),
                (sender.public as java.security.interfaces.ECPublicKey).params,
            ),
        )
        val shared = javax.crypto.KeyAgreement.getInstance("ECDH").run {
            init(sender.private)
            doPhase(receiver, true)
            generateSecret()
        }
        val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val ikm = hmac(hmac(to.authSecret, shared), "WebPush: info".toByteArray() + byteArrayOf(0) + to.publicKey + senderPoint + byteArrayOf(1)).copyOf(32)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        val ciphertext = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(cek, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            doFinal(plaintext + byteArrayOf(0x02))
        }
        val rs = java.nio.ByteBuffer.allocate(4).putInt(recordSize).array()
        return salt + rs + byteArrayOf(65) + senderPoint + ciphertext
    }

    @Test
    fun testTheTestEncryptorRoundTrips() {
        val keys = DeviceKeys.generate()
        val body = encrypt("""{"title":"t","tag":"x"}""".toByteArray(), keys)
        assertEquals("""{"title":"t","tag":"x"}""", WebPushDecrypt.decrypt(body, keys)?.decodeToString())
    }

    @Test
    fun testAnAuthenticatedDeeplyNestedBodyIsDroppedNotACrash() {
        val keys = DeviceKeys.generate()
        val depth = 200_000
        val hostile = """{"title":"t","tag":"x","n":""" + "[".repeat(depth) + "]".repeat(depth) + "}"
        val body = encrypt(hostile.toByteArray(), keys, recordSize = hostile.length + 1024)
        val plain = assertNotNull(WebPushDecrypt.decrypt(body, keys)).decodeToString()
        // Parsing that may exhaust the stack; either way it's an answer, not a thrown error.
        val flat = RelayPush.flatten(plain)
        if (flat != null) assertEquals(mapOf("title" to "t", "tag" to "x"), flat)
    }

    @Test
    fun testOnlyTopLevelScalarsAreTaken() {
        // The body is flat; anything nested is dropped, not walked.
        val flat = assertNotNull(RelayPush.flatten("""{"title":"t","tag":"x","a":[1,[2]],"o":{"k":{"j":1}},"n":3}"""))
        assertEquals(mapOf("title" to "t", "tag" to "x", "n" to "3"), flat)
    }

    // MARK: - Debug relay origins

    @Test
    fun testAnIpv6RelayKeepsItsBrackets() {
        assertEquals(
            PushRoute.Relay("https://[::1]:8030", key),
            RelayPush.route(PushConfig(key, listOf("webpush"), "https://[::1]:8030"), allowAnyHttpsRelay = true),
        )
        assertEquals(
            PushRoute.Relay("https://[fd00::1]", key),
            RelayPush.route(PushConfig(key, listOf("webpush"), "https://[fd00::1]/"), allowAnyHttpsRelay = true),
        )
    }

    // MARK: - Concurrent recovery

    @Test
    fun testALoadRacingAReplacementCantDeleteIt() {
        // Android's secure storage deletes a blob it can't decrypt. A worker reading stale keys must
        // not delete the replacements the registrar wrote while it was reading.
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val storage = object : net.amiantos.lurkerkit.session.SecureStorage {
            val map = java.util.concurrent.ConcurrentHashMap<String, okio.ByteString>()
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            override fun read(account: String): okio.ByteString? {
                val blob = map[account] ?: return null
                if (blob.utf8() != "stale") return blob
                // The worker's read stalls mid-decrypt; any read after it fails at once.
                if (first.getAndSet(false)) {
                    entered.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                map.remove(account) // undecryptable: dropped, whatever is there by now
                return null
            }
            override fun write(account: String, data: okio.ByteString) { map[account] = data }
            override fun delete(account: String) { map.remove(account) }
        }
        storage.map["relay-push-keys"] = okio.ByteString.Companion.run { "stale".encodeUtf8() }
        val keys = RelayPushKeys(storage)
        val worker = Thread { keys.load() }.apply { start() }
        entered.await(5, java.util.concurrent.TimeUnit.SECONDS)
        var made: DeviceKeys? = null
        val registrar = Thread { made = keys.loadOrCreate() }.apply { start() }
        Thread.sleep(300) // the registrar, unserialized, would write its keys here
        release.countDown()
        worker.join(5_000)
        registrar.join(5_000)
        val stored = assertNotNull(RelayPushKeys(storage).load(), "the replacement keys survived")
        assertEquals(assertNotNull(made).p256dh, stored.p256dh)
    }
}
