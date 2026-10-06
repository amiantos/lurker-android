// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.push.DeviceKeys

/**
 * RFC 8291 encryption for the tests that need a body the decryptor will authenticate: a hostile
 * plaintext the server never sends but a compromised one could. [recordSize] is the header's;
 * a body bigger than the 4 KB a push can be still declares a record that fits it.
 */
internal fun encryptForTest(plaintext: ByteArray, to: DeviceKeys, recordSize: Int = 4096): ByteArray {
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
