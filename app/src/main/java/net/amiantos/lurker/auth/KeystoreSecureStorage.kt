// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.amiantos.lurkerkit.session.SecureStorage
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The kit's [SecureStorage] over the Android Keystore: each blob is encrypted with an
 * AES-256-GCM key the Keystore holds — non-exportable, hardware-backed where the device
 * supports it — and only ciphertext lands in SharedPreferences. Rolled directly on the Keystore
 * rather than the deprecated `security-crypto` library.
 *
 * The blob is a bearer credential, so losing the key (the user clears credentials or resets
 * biometrics, which invalidates Keystore keys) simply means we can't decrypt — [read] answers
 * null, the kit treats that as "no session", and the user signs in again.
 *
 * What iOS asks of the Keychain (`AfterFirstUnlockThisDeviceOnly`) splits in two here: the key
 * needs no user authentication, so the blob is readable after the first unlock post-boot; and
 * the preferences file is excluded from Auto Backup and device transfer in
 * `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`, so a restored backup
 * cannot carry a live session onto new hardware.
 *
 * Best-effort throughout, as the contract requires: nothing here throws. The prototype's
 * password-era session lived under the same preferences file and key (`"session"`), which is
 * the kit's `legacyAccount`, so `takeLegacySession` can find and end it.
 */
class KeystoreSecureStorage(context: Context) : SecureStorage {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(account: String): ByteString? {
        val stored = prefs.getString(account, null) ?: return null
        val plain = runCatching { decrypt(stored) }.getOrNull()
        if (plain == null) {
            // Undecryptable (key invalidated or corrupted) — drop it and start clean.
            delete(account)
            return null
        }
        return plain
    }

    override fun write(account: String, data: ByteString) {
        runCatching {
            prefs.edit().putString(account, encrypt(data)).apply()
        }
    }

    override fun delete(account: String) {
        runCatching { prefs.edit().remove(account).apply() }
    }

    // --- Keystore AES-GCM --------------------------------------------------------

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Returns `base64(iv):base64(ciphertext)` — GCM needs its per-encryption IV kept. */
    private fun encrypt(plain: ByteString): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray())
        return "${b64(cipher.iv)}:${b64(ciphertext)}"
    }

    private fun decrypt(stored: String): ByteString? {
        val parts = stored.split(":")
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, unb64(parts[0])))
        return cipher.doFinal(unb64(parts[1])).toByteString()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "lurker_session"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lurker_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
