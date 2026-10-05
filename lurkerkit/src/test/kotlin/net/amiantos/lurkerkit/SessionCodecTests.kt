// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.amiantos.lurkerkit.model.ServerAddress
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.PersistedSession
import net.amiantos.lurkerkit.session.SecureStorage
import net.amiantos.lurkerkit.session.SessionCodec
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.SettingsCache
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persisted session's JSON codec, tested without touching secure storage.
 * A malformed / legacy blob must decode to null (→ treated as no session) rather than
 * crashing. Replaces the July prototype's `SessionCodecTest`, which went with the prototype.
 */
class SessionCodecTests {

    @Test
    fun testRoundTrips() {
        val session = PersistedSession(server = "https://app.lurker.chat", token = "roswell~tok")
        val data = SessionCodec.encode(session)!!
        assertEquals(session, SessionCodec.decode(data))
    }

    @Test
    fun testGarbageDecodesToNil() {
        assertNull(SessionCodec.decode("not json at all".encodeUtf8()))
        assertNull(SessionCodec.decode(ByteString.EMPTY))
    }

    /**
     * The password sign-in's session, which carried a `backend`. It has to decode so the
     * upgrade can end it on its server (`SessionStore.takeLegacySession`).
     */
    @Test
    fun testAPasswordEraSessionDecodes() {
        val json = """{"backend":"hosted","server":"https://app.lurker.chat","token":"old"}"""
        assertEquals(
            PersistedSession(server = "https://app.lurker.chat", token = "old"),
            SessionCodec.decode(json.encodeUtf8()),
        )
    }

    @Test
    fun testEmptyServerOrTokenDecodesToNil() {
        val noServer = """{"server":"","token":"t"}"""
        val noToken = """{"server":"http://x","token":""}"""
        assertNull(SessionCodec.decode(noServer.encodeUtf8()))
        assertNull(SessionCodec.decode(noToken.encodeUtf8()))
    }

    // Port-only:

    /** Bytes that aren't UTF-8 are no session, as `JSONDecoder` refuses them. */
    @Test
    fun testBytesThatArentUtf8DecodeToNil() {
        val bytes = byteArrayOf(
            '{'.code.toByte(), '"'.code.toByte(), 0xFF.toByte(), '"'.code.toByte(), ':'.code.toByte(),
            '1'.code.toByte(), '}'.code.toByte(),
        )
        assertNull(SessionCodec.decode(bytes.toByteString()))
    }

    /**
     * A field of the wrong type, missing, or null is no session — `JSONDecoder`'s
     * `typeMismatch`, `keyNotFound` and `valueNotFound`, and kotlinx's answer too.
     */
    @Test
    fun testAFieldOfTheWrongShapeDecodesToNil() {
        assertNull(SessionCodec.decode("""{"server":1,"token":"t"}""".encodeUtf8()))
        assertNull(SessionCodec.decode("""{"server":"http://x"}""".encodeUtf8()))
        assertNull(SessionCodec.decode("""{"server":"http://x","token":null}""".encodeUtf8()))
        assertNull(SessionCodec.decode("""["http://x","t"]""".encodeUtf8()))
    }

    /** The store over its storage: the session is kept under `account`, and replaced there. */
    @Test
    fun testTheStoreSavesLoadsAndClears() {
        val storage = InMemorySecureStorage()
        val store = SessionStore(storage)
        assertNull(store.load())
        store.save(PersistedSession(server = "https://a", token = "one"))
        store.save(PersistedSession(server = "https://b", token = "two"))
        assertEquals(PersistedSession(server = "https://b", token = "two"), store.load())
        assertEquals(setOf("oauth-session"), storage.stored.keys)
        store.clear()
        assertNull(store.load())
        assertTrue(storage.stored.isEmpty())
    }

    /** A corrupt blob is dropped as it's found, so the next launch starts clean. */
    @Test
    fun testACorruptBlobIsCleared() {
        val storage = InMemorySecureStorage()
        storage.stored["oauth-session"] = "not json".encodeUtf8()
        assertNull(SessionStore(storage).load())
        assertTrue(storage.stored.isEmpty())
    }

    /**
     * The password sign-in's session is read once and gone, and never restored as the OAuth one.
     */
    @Test
    fun testTheLegacySessionIsTakenOnce() {
        val storage = InMemorySecureStorage()
        storage.stored["session"] = """{"backend":"hosted","server":"https://app.lurker.chat","token":"old"}""".encodeUtf8()
        val store = SessionStore(storage)
        assertNull(store.load())
        assertNotNull(store.takeLegacySession())
        assertNull(store.takeLegacySession())
        assertTrue(storage.stored.isEmpty())
    }

    /**
     * The view model's restore, which LurkerKit tests nowhere: a saved session goes `loggedIn`
     * before anything connects (the connect is queued, not run, in a scope nobody advances).
     */
    @Test
    fun testARestoredSessionIsLoggedInBeforeItConnects() {
        val storage = InMemorySecureStorage()
        SessionStore(storage).save(PersistedSession(server = "https://app.lurker.chat", token = "t"))
        val model = viewModel(storage)
        assertEquals(ChatViewModel.SessionState.LoggedIn, model.session)
        assertNotNull(SessionStore(storage).load())
    }

    /** A restore connects at once by default — what iOS does, and what a launch into the app wants. */
    @Test
    fun testARestoredSessionConnectsAtOnceInTheForeground() {
        val model = viewModel(storageWithSession())
        assertTrue(model.state.socketOpening)
    }

    /**
     * An Android process FCM started with no activity (lurker-android#16) holds a restored session's
     * connect until something comes to the foreground — no socket, no snapshot, for a phone in a
     * pocket — and then starts it as the restore would have.
     */
    @Test
    fun testARestoredSessionStartedInTheBackgroundConnectsOnForeground() {
        val model = viewModel(storageWithSession(), startsInForeground = false)
        assertEquals(ChatViewModel.SessionState.LoggedIn, model.session)
        assertFalse(model.state.socketOpening)
        // Going to the background first changes nothing: still nobody to connect for.
        model.enterBackground()
        assertFalse(model.state.socketOpening)
        // Not a kept socket: there wasn't one.
        assertFalse(model.enterForeground())
        assertTrue(model.state.socketOpening)
    }

    private fun storageWithSession(): InMemorySecureStorage {
        val storage = InMemorySecureStorage()
        SessionStore(storage).save(PersistedSession(server = "https://app.lurker.chat", token = "t"))
        return storage
    }

    /**
     * A session the transport policy now rejects is dropped and bounced to sign-in with the
     * policy's sentence, and a password-era session is taken whatever happens.
     */
    @Test
    fun testARestoredSessionThePolicyRejectsIsDropped() = runTest {
        val storage = InMemorySecureStorage()
        SessionStore(storage).save(PersistedSession(server = "http://example.com", token = "t"))
        storage.stored["session"] = """{"server":"https://app.lurker.chat","token":"old"}""".encodeUtf8()
        val model = viewModel(storage)
        assertEquals(ChatViewModel.SessionState.LoggedOut, model.session)
        assertEquals(ServerAddress.rejection(ServerAddress.normalize("http://example.com")), model.statusPublisher.first())
        assertNotNull(model.statusPublisher.first())
        assertTrue(storage.stored.isEmpty())
    }

    /** With no session there is nothing to send, and the background allowance is let go at once. */
    @Test
    fun testBackgroundingWithNoSessionFlushesAtOnce() {
        val model = viewModel(InMemorySecureStorage())
        var flushed = 0
        model.enterBackground { flushed += 1 }
        assertEquals(1, flushed)
        assertFalse(model.enterForeground())
    }

    private fun viewModel(storage: SecureStorage, startsInForeground: Boolean = true): ChatViewModel =
        ChatViewModel(
            scope = TestScope(),
            sessions = SessionStore(storage),
            settingsCache = SettingsCache(InMemoryDefaultsStorage()),
            oauthClients = OAuthClients(InMemoryDefaultsStorage()),
            formatExpiry = { it.toString() },
            startsInForeground = startsInForeground,
        )
}
