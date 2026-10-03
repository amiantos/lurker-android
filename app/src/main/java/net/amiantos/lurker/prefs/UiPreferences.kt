// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.prefs

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ServerAddress

/**
 * Non-secret UI state that has to outlive the process. The session token is a secret and lives
 * in the Keystore (`KeystoreSecureStorage`), never here; drafts are the kit's, synced through the
 * server. lurker-ios's `UserPreferences`, minus what Android never had (the legacy favorites list
 * and its migration).
 */
class UiPreferences(private val prefs: StringPrefs) {

    /**
     * The server to prefill on the sign-in screen: the last one used, else lurker.chat's. As on
     * iOS, the full `https://app.lurker.chat` — what `ServerAddress` normalizes to — so the field
     * shows exactly where it will go.
     */
    var lastServerURL: String
        get() = prefs.getString(LAST_SERVER_URL) ?: ServerAddress.lurkerChat
        set(value) = prefs.putString(LAST_SERVER_URL, value)

    /**
     * The buffer that was on screen when the app was last used, so a relaunch lands where you
     * left off (lurker-ios#49). Written by the buffer list and the conversation (U1/U2), read at
     * launch by U1, and chased through renames by [rewriteBuffer].
     *
     * Stored as its parts rather than as a `BufferKey.id`, because `id` lower-cases the target and
     * this one is *reconstructed* into a buffer at launch — before any frame has arrived to correct
     * the case. A list that only ever looks keys up in state can be lossy; this can't.
     *
     * A null `networkId` is the system buffer, and is stored by *absence*.
     */
    val lastOpenBufferKey: BufferKey?
        get() {
            val target = prefs.getString(LAST_BUFFER_TARGET)
            if (target.isNullOrEmpty()) return null
            // Absent is the system buffer; present but unreadable is no key at all — a stale or
            // foreign value must not land on a system buffer named after a channel.
            val stored = prefs.getString(LAST_BUFFER_NETWORK_ID)
            val networkId = if (stored == null) null else (stored.toIntOrNull() ?: return null)
            return BufferKey(networkId = networkId, target = target)
        }

    fun recordLastOpenBuffer(key: BufferKey) {
        prefs.putString(LAST_BUFFER_TARGET, key.target)
        val networkId = key.networkId
        if (networkId != null) {
            prefs.putString(LAST_BUFFER_NETWORK_ID, networkId.toString())
        } else {
            prefs.remove(LAST_BUFFER_NETWORK_ID)
        }
    }

    /**
     * Forgotten on sign-out. Restoration is the one preference here that *synthesizes* a buffer
     * rather than looking one up, so a stale entry doesn't quietly fail to resolve — signing in as
     * somebody else would land them in a channel from the previous account.
     */
    fun forgetLastOpenBuffer() {
        prefs.remove(LAST_BUFFER_TARGET)
        prefs.remove(LAST_BUFFER_NETWORK_ID)
    }

    /**
     * Forgotten when the buffer itself is closed from this device — the one case of "the buffer is
     * gone" the client can prove. Matched on `id`: the stored target keeps its original case while
     * the store row carries whatever the server last said, and `#Lurker` is `#lurker`.
     */
    fun forgetLastOpenBuffer(ifMatching: BufferKey) {
        if (lastOpenBufferKey?.id == ifMatching.id) forgetLastOpenBuffer()
    }

    /**
     * Follow a buffer rename (`ChatViewModel.onBufferRenamed`). A casing-only rename still
     * rewrites, because this record keeps the display casing it will synthesize a buffer from.
     */
    fun rewriteBuffer(from: BufferKey, to: BufferKey) {
        if (lastOpenBufferKey?.id == from.id) recordLastOpenBuffer(to)
    }

    companion object {
        /**
         * The preferences file. Backed up, so a restored phone still prefills the server: nothing
         * here is a secret, and the last buffer is forgotten by any signed-out launch, which a
         * restored device's first one always is (its session never travels).
         */
        const val FILE = "lurker_ui"

        private const val LAST_SERVER_URL = "lastServerURL"
        private const val LAST_BUFFER_TARGET = "lastBufferTarget"
        private const val LAST_BUFFER_NETWORK_ID = "lastBufferNetworkId"
    }
}
