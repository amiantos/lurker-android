// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.push

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Web Push decryption for pushes relayed through push.lurker.chat (lurker-dev/RELAY_PLAN.md §6.2).
// The server encrypts each push for this device with RFC 8291 (`aes128gcm`, RFC 8188), the relay
// forwards the ciphertext untouched, and only this device's keys open it.
//
// Platform JCA only, at minSdk 28: ECDH and AES-GCM are there, HKDF isn't (a few lines on
// HmacSHA256 below). Tested on the host against the server's own vectors (`relayVectors.json`).

/** One device's Web Push keys: a P-256 keypair and a 16-byte auth secret, created once and kept. */
class DeviceKeys(
    val privateKey: PrivateKey,
    /** The public key as an uncompressed point (`0x04 || X || Y`, 65 bytes). */
    val publicKey: ByteArray,
    val authSecret: ByteArray,
) {
    /** What the server is given as `keys.p256dh`: base64url, no padding. */
    val p256dh: String get() = b64(publicKey)

    /** What the server is given as `keys.auth`. */
    val auth: String get() = b64(authSecret)

    companion object {
        /** A fresh keypair and auth secret. */
        fun generate(random: SecureRandom = SecureRandom()): DeviceKeys {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), random)
            val pair = generator.generateKeyPair()
            val auth = ByteArray(16).also(random::nextBytes)
            return DeviceKeys(pair.private, uncompressed(pair.public as ECPublicKey), auth)
        }

        /** Keys from a raw 32-byte private scalar — how test vectors give them. */
        fun fromRaw(privateScalar: ByteArray, publicKey: ByteArray, authSecret: ByteArray): DeviceKeys {
            val spec = ECPrivateKeySpec(BigInteger(1, privateScalar), WebPushDecrypt.p256)
            return DeviceKeys(KeyFactory.getInstance("EC").generatePrivate(spec), publicKey, authSecret)
        }

        /** Keys from their stored form ([PersistedForm]); null if anything about it is off. */
        fun fromPersisted(stored: PersistedForm): DeviceKeys? = runCatching {
            val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(stored.privateKey)))
            val public = unb64(stored.publicKey)
            val auth = unb64(stored.authSecret)
            if (public.size != 65 || public[0] != 0x04.toByte() || auth.size != 16) return null
            DeviceKeys(private, public, auth)
        }.getOrNull()
    }

    /** The form these keys are stored in: PKCS#8 for the private key, all base64url. */
    data class PersistedForm(val privateKey: String, val publicKey: String, val authSecret: String)

    fun persisted(): PersistedForm = PersistedForm(b64(privateKey.encoded), b64(publicKey), b64(authSecret))
}

object WebPushDecrypt {
    /** P-256's parameters, taken from a generated key: the one spelling every provider accepts. */
    internal val p256: ECParameterSpec by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        (generator.generateKeyPair().public as ECPublicKey).params
    }

    private const val HEADER_FIXED = 16 + 4 + 1 // salt, record size, key id length
    private const val TAG_BYTES = 16
    private const val POINT_BYTES = 65

    /**
     * The plaintext of one `aes128gcm` Web Push body, or null if it can't be: the wrong keys, a
     * tampered or truncated body, a header that isn't RFC 8291's, or more than one record. Never
     * throws — a push that can't be read is dropped, not a crash in the messaging service.
     */
    fun decrypt(body: ByteArray, keys: DeviceKeys): ByteArray? = runCatching { open(body, keys) }.getOrNull()

    private fun open(body: ByteArray, keys: DeviceKeys): ByteArray? {
        if (body.size < HEADER_FIXED) return null
        val salt = body.copyOfRange(0, 16)
        val recordSize = ((body[16].toLong() and 0xff) shl 24) or ((body[17].toLong() and 0xff) shl 16) or
            ((body[18].toLong() and 0xff) shl 8) or (body[19].toLong() and 0xff)
        val idLength = body[20].toInt() and 0xff
        // RFC 8291 §4: the key id is the sender's public key, uncompressed.
        if (idLength != POINT_BYTES) return null
        val start = HEADER_FIXED + idLength
        if (body.size < start + TAG_BYTES + 1) return null
        val senderPoint = body.copyOfRange(HEADER_FIXED, start)
        val ciphertext = body.copyOfRange(start, body.size)
        // One record: a push is a single record no bigger than the record size it declares.
        if (recordSize < TAG_BYTES + 1 || ciphertext.size > recordSize) return null

        val shared = ecdh(keys.privateKey, senderPoint) ?: return null
        // RFC 8291 §3.4: the auth secret salts the shared secret, bound to both public keys.
        val keyInfo = "WebPush: info".toByteArray() + 0 + keys.publicKey + senderPoint
        val ikm = hkdf(salt = keys.authSecret, ikm = shared, info = keyInfo, length = 32)
        // RFC 8188 §2.2–2.3: the content key and nonce, from the body's own salt.
        val prk = hmac(salt, ikm)
        val cek = expand(prk, "Content-Encoding: aes128gcm".toByteArray() + 0, 16)
        val nonce = expand(prk, "Content-Encoding: nonce".toByteArray() + 0, 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        val padded = cipher.doFinal(ciphertext)
        // The last (and only) record ends in a 0x02 delimiter, then any zero padding.
        var end = padded.size - 1
        while (end >= 0 && padded[end] == 0.toByte()) end--
        if (end < 0 || padded[end] != 0x02.toByte()) return null
        return padded.copyOfRange(0, end)
    }

    private fun ecdh(privateKey: PrivateKey, point: ByteArray): ByteArray? {
        if (point.size != POINT_BYTES || point[0] != 0x04.toByte()) return null
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        val public = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(public, true)
        return agreement.generateSecret()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-Expand (RFC 5869) for at most one block, which is all Web Push ever asks for. */
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray =
        hmac(prk, info + 1).copyOf(length)

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(hmac(salt, ikm), info, length)

    private operator fun ByteArray.plus(byte: Int): ByteArray = this + byteArrayOf(byte.toByte())
}

/** The uncompressed point of [key]: `0x04 || X || Y`, each coordinate left-padded to 32 bytes. */
internal fun uncompressed(key: ECPublicKey): ByteArray =
    byteArrayOf(0x04) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

private fun fixed32(n: BigInteger): ByteArray {
    val raw = n.toByteArray()
    // BigInteger adds a sign byte when the top bit is set, and drops leading zeros.
    val trimmed = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
    return ByteArray(32 - trimmed.size) + trimmed
}

internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Base64url, padding optional; throws on anything else. */
internal fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
