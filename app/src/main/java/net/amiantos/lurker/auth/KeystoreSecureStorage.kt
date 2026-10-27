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
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.UnrecoverableEntryException
import javax.crypto.AEADBadTagException
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
 * The blob is a bearer credential, so losing the key simply means we can't decrypt — [read]
 * answers null, the kit treats that as "no session", and the user signs in again. The key asks
 * for no user authentication, so a changed lock screen or biometric enrolment never invalidates
 * it; what can is a Keystore reset or corruption (some OEMs, a factory-reset-then-restore), in
 * which case the alias is dropped and a fresh key made, so the next save works again.
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
 *
 * [prefsName] and [keyAlias] default to the session's. The relayed-push keys (`RelayPushKeys`) get
 * a file and Keystore key of their own, so neither secret's storage can disturb the other's — a
 * dropped push key must never sign the user out.
 */
class KeystoreSecureStorage(
    context: Context,
    prefsName: String = PREFS,
    private val keyAlias: String = KEY_ALIAS,
) : SecureStorage {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(account: String): ByteString? {
        val stored = prefs.getString(account, null) ?: return null
        val plain = try {
            decrypt(stored)
        } catch (e: Exception) {
            key = null
            // ⚠ Only a blob that will NEVER decrypt is dropped. Anything else is "unreadable NOW" —
            // a `KeyStoreException` or `ProviderException` while the Keystore daemon restarts, an
            // `IOException` loading it — and used to delete the blob, and with it the session,
            // the revokes still owed to a server, and a sign-in out in the browser. Those answer
            // "nothing readable" and leave the blob for the next read.
            if (!neverDecrypts(e)) return null
            null
        }
        if (plain == null) {
            // Undecryptable for good — the key changed under it, or it isn't a blob this class
            // wrote — so drop it and start clean.
            delete(account)
            return null
        }
        return plain
    }

    /**
     * Whether [error] says the blob can never decrypt: the tag fails under the key that exists (a
     * Keystore reset gave the alias a new key, or the ciphertext is corrupt), the text isn't
     * base64, or the IV isn't one this class wrote.
     */
    private fun neverDecrypts(error: Exception): Boolean =
        error is AEADBadTagException || error is IllegalArgumentException || error is InvalidAlgorithmParameterException

    override fun write(account: String, data: ByteString) {
        runCatching {
            prefs.edit().putString(account, encrypt(data)).apply()
        }.onFailure {
            // A key the Keystore no longer honours: forget it, so the next call regenerates.
            key = null
        }
    }

    override fun delete(account: String) {
        runCatching { prefs.edit().remove(account).apply() }
    }

    // --- Keystore AES-GCM --------------------------------------------------------

    /**
     * The key, fetched once: every Keystore call is a binder round-trip, and the first reads
     * happen on the main thread during the view model's init. Fetched under a lock, because the
     * relayed-push keys (`RelayPushKeys`) read theirs from FCM's worker thread while the main
     * thread may be generating the same alias.
     */
    private var key: SecretKey? = null

    @Synchronized
    private fun secretKey(): SecretKey {
        key?.let { return it }
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = try {
            (keystore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: UnrecoverableEntryException) {
            // An alias that exists but cannot be used (keystore reset or corruption): drop it, or
            // every save would fail silently for the rest of the install. ⚠ Only THAT: a
            // `KeyStoreException` while the daemon is restarting used to drop the alias too, and
            // the fresh key then failed the blob's tag, so a transient fault deleted the session.
            // It propagates instead, and `read` keeps the blob for a later try.
            runCatching { keystore.deleteEntry(keyAlias) }
            null
        }
        existing?.let { key = it; return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey().also { key = it }
    }

    /** Returns `base64(iv):base64(ciphertext)` — GCM needs its per-encryption IV kept. */
    private fun encrypt(plain: ByteString): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray())
        return "${b64(cipher.iv)}:${b64(ciphertext)}"
    }

    /** Null for a blob that isn't `iv:ciphertext` at all; throws for one that is but won't decrypt. */
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
