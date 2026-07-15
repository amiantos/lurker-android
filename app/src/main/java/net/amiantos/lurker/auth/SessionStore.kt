// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.amiantos.lurker.model.Backend
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What we persist to survive a relaunch: enough to reconnect without re-login. */
data class PersistedSession(
    val backend: Backend,
    val server: String,
    val token: String,
)

/**
 * Persists the session token at rest, encrypted with an AES-256-GCM key held in
 * the Android Keystore (the key never leaves the secure hardware; only ciphertext
 * lands in SharedPreferences). Rolled directly on the Keystore rather than the
 * deprecated `security-crypto` library.
 *
 * The token is a bearer credential, so losing the key (e.g. the user clears
 * credentials / resets biometrics, which invalidates Keystore keys) simply means
 * we can't decrypt — we treat that as "no session" and fall back to sign-in.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(session: PersistedSession) {
        prefs.edit().putString(KEY_SESSION, encrypt(encodeSession(session))).apply()
    }

    fun load(): PersistedSession? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        val json = runCatching { decrypt(stored) }.getOrNull()
        if (json == null) {
            // Undecryptable (key invalidated / corrupted) — drop it and start clean.
            clear()
            return null
        }
        return decodeSession(json)
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
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
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "${b64(cipher.iv)}:${b64(ciphertext)}"
    }

    private fun decrypt(stored: String): String? {
        val parts = stored.split(":")
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, unb64(parts[0])))
        return String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "lurker_session"
        const val KEY_SESSION = "session"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lurker_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

// --- Pure codec (unit-tested; no Android/Keystore) ------------------------------

internal fun encodeSession(session: PersistedSession): String =
    JSONObject()
        .put("backend", session.backend.name)
        .put("server", session.server)
        .put("token", session.token)
        .toString()

/** Tolerant of corrupt/legacy blobs: any malformed field yields null → treat as no session. */
internal fun decodeSession(json: String): PersistedSession? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val backend = runCatching { Backend.valueOf(obj.optString("backend")) }.getOrNull() ?: return null
    val server = obj.optString("server")
    val token = obj.optString("token")
    if (server.isEmpty() || token.isEmpty()) return null
    return PersistedSession(backend, server, token)
}
