// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.amiantos.lurkerkit.support.utf8OrNull
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** What we persist to survive a relaunch: enough to reconnect without signing in again. */
@Serializable
data class PersistedSession(
    val server: String,
    val token: String,
)

/**
 * Where `SessionStore` keeps its blobs: the three Keychain calls LurkerKit makes, and no more.
 * The kit holds the rules; `:app` implements this over the Android Keystore, and the tests over
 * a map.
 *
 * Port-only: LurkerKit calls the Keychain itself (PORTING.md, the module boundary). The
 * implementation owns what LurkerKit sets as an item attribute, and must keep it: the item is
 * readable after the first unlock post-boot — it survives a locked screen, which a background
 * reconnect (lurker-ios#4) will need — and it stays on this device: it is kept out of backups
 * and off a migrated device, so a restored backup can't silently carry a live session onto new
 * hardware (the user re-signs-in there). That is iOS's
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; on Android, a Keystore key that needs no
 * user authentication, and storage excluded from Auto Backup and device transfer.
 *
 * Best-effort, as the Keychain calls are: no method may throw. A failure is a no-op for
 * [write] and [delete] and `null` for [read] — at worst the user signs in again next launch,
 * and it must never crash sign-in.
 */
interface SecureStorage {
    /** The blob stored under [account], or null when there is none (or it can't be read). */
    fun read(account: String): ByteString?

    /** Store [data] under [account], replacing whatever was there. */
    fun write(account: String, data: ByteString)

    /** Remove whatever is stored under [account]; nothing there is not an error. */
    fun delete(account: String)
}

/**
 * Persists the session token in secure storage — the Keychain on iOS, which encrypts at rest and
 * is scoped to the app — so a relaunch reconnects without re-login. On iOS the Keychain *is*
 * the encrypted store, so this just reads/writes a JSON blob as a generic-password item; here
 * it reads and writes the same blob through [SecureStorage], whose `:app` implementation does
 * the encryption over the Keystore.
 *
 * Best-effort throughout: a storage failure simply means we don't remember the session (the
 * user signs in again next launch) — it must never crash sign-in. The codec is split out
 * (`SessionCodec`) so the parsing is unit-tested without touching the storage.
 *
 * Port note: LurkerKit's `service` (the Keychain item's service name, injectable so tests get
 * their own) has no counterpart: [storage] is the whole namespace, and a test passes its own.
 */
class SessionStore(private val storage: SecureStorage) {

    fun save(session: PersistedSession) {
        val data = SessionCodec.encode(session) ?: return
        // Replace any existing item. Port note: on iOS a Keychain add fails on a duplicate, so
        // LurkerKit deletes first; `SecureStorage.write` replaces, so one call does both.
        storage.write(account, data)
    }

    fun load(): PersistedSession? {
        val data = storage.read(account) ?: return null
        val session = SessionCodec.decode(data)
        if (session == null) {
            // Corrupt blob — drop it and start clean.
            clear()
            return null
        }
        return session
    }

    /**
     * The session a password sign-in left behind, deleted as it's read, so only the first
     * launch after the move to OAuth finds one.
     */
    internal fun takeLegacySession(): PersistedSession? {
        val data = storage.read(legacyAccount) ?: return null
        storage.delete(legacyAccount)
        return SessionCodec.decode(data)
    }

    fun clear() {
        storage.delete(account)
    }

    internal companion object {
        /**
         * A new name for the OAuth sign-in's session, so the password sign-in's is never restored.
         * Stored under the same name it would decode fine, since the codec ignores its `backend`.
         */
        const val account = "oauth-session"

        /** Where the password sign-in kept its session. */
        const val legacyAccount = "session"
    }
}

/** Pure JSON codec for a `PersistedSession` — no storage, so it's unit-tested directly. */
internal object SessionCodec {
    /** The password sign-in's sessions carried a `backend`, which `JSONDecoder` skips. */
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(session: PersistedSession): ByteString? =
        json.encodeToString(PersistedSession.serializer(), session).encodeUtf8()

    /**
     * Tolerant of corrupt blobs: a malformed payload or an empty server/token yields null
     * → treat as no session.
     *
     * Port note: kotlinx is the decoder, not `JSONDecoder` (PORTING.md, JSON); where the two
     * read a malformed blob differently, `SessionCodecTests` pins kotlinx's answer. Bytes that
     * are not UTF-8 are no session, as `JSONDecoder` refuses them.
     */
    fun decode(data: ByteString): PersistedSession? {
        val text = data.utf8OrNull() ?: return null
        val session = try {
            json.decodeFromString(PersistedSession.serializer(), text)
        } catch (_: IllegalArgumentException) {
            // `SerializationException` is one.
            return null
        }
        if (session.server.isEmpty() || session.token.isEmpty()) return null
        return session
    }
}
