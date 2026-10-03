// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.prefs

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // MARK: - Composer

    private val autocapitalizes = MutableStateFlow(readComposerAutocapitalizes())

    /**
     * Whether the composer capitalizes sentences as you type. On by default.
     *
     * It was off outright for the iOS app's first releases, on the reasoning that IRC is
     * lowercase-native — nicks, `/commands`, `#channels`. That's true of the *first token of a line*
     * and not of the prose after it, and it made the composer the one field on the phone that
     * behaved unlike every other one, with no way to say otherwise. So: capitals by default, and off
     * is something you ask for.
     *
     * Device-local, unlike the settings it sits under on the settings screen. It configures this
     * phone's keyboard, and there is nothing on the other end of a sync to configure — the web
     * client can't offer the choice at all (Safari re-applies sentence caps whenever autocorrect is
     * on, which is why its settings couple the two).
     *
     * A flow rather than a plain property, because the composer is already on screen when the value
     * changes: Settings is a dialog over the conversation, nothing under it is rebuilt when it
     * closes, and there's no server frame to ride in on the way every other setting does. iOS posts
     * `composerKeyboardPreferencesDidChange` for the same reason.
     *
     * U3: the composer reads this into its `KeyboardOptions.capitalization` (Sentences / None).
     */
    val composerAutocapitalizes: StateFlow<Boolean> = autocapitalizes.asStateFlow()

    fun setComposerAutocapitalizes(on: Boolean) {
        prefs.putString(COMPOSER_AUTOCAPITALIZATION, on.toString())
        autocapitalizes.value = on
    }

    /**
     * Absent is on — the default iOS registers, since a missing bool there (and here) would
     * otherwise read as off, the opposite of what this one means when it hasn't been set. So is
     * anything unreadable: a stored value that isn't a bool is not a request to turn capitals off.
     */
    private fun readComposerAutocapitalizes(): Boolean = prefs.getString(COMPOSER_AUTOCAPITALIZATION) != "false"

    /**
     * The buffer that was on screen when the app was last used, so a relaunch lands where you
     * left off (lurker-ios#49). Written when a conversation appears and forgotten when the reader
     * backs out of one to the list (`MainScaffold`), read at launch, and chased through renames by
     * [rewriteBuffer].
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

        /** iOS's key, kept so the two apps' preference files read the same. */
        private const val COMPOSER_AUTOCAPITALIZATION = "composerAutocapitalization"
    }
}

/**
 * The app's [UiPreferences], for screens below `MainScaffold` that need one it doesn't pass down —
 * the settings dialog, opened from the buffer list. Provided by `AppRoot`, which is handed the
 * instance by `LurkerApp`, so what the app proper reads and writes is still visible at the root
 * rather than looked up from the Application inside composition.
 */
val LocalUiPreferences = staticCompositionLocalOf<UiPreferences> { error("No UiPreferences provided") }
