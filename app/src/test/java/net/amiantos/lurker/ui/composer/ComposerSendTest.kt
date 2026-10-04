// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.JoinNotice
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.session.OAuthClients
import net.amiantos.lurkerkit.session.SecureStorage
import net.amiantos.lurkerkit.session.SessionStore
import net.amiantos.lurkerkit.store.DefaultsStorage
import net.amiantos.lurkerkit.store.SettingsCache
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composer's send order (lurker-ios#201, iOS's d906b98): the field is cleared and its draft flushed
 * BEFORE the line goes to the view model, since the send itself can take the user somewhere — `/query`
 * to a DM we hold, `/join` to a channel we're in — and a composer cleared after that saved the command
 * as the old buffer's draft, which syncs to every device.
 */
class ComposerSendTest {

    private class MapSecure : SecureStorage {
        val stored = mutableMapOf<String, ByteString>()
        override fun read(account: String): ByteString? = stored[account]
        override fun write(account: String, data: ByteString) {
            stored[account] = data
        }
        override fun delete(account: String) {
            stored.remove(account)
        }
    }

    private class MapDefaults : DefaultsStorage {
        val stored = mutableMapOf<String, JsonObject>()
        override fun dictionary(key: String): JsonObject? = stored[key]
        override fun set(value: JsonObject, key: String) {
            stored[key] = value
        }
        override fun removeObject(key: String) {
            stored.remove(key)
        }
    }

    private val scope = TestScope()

    /** Signed out, socketless: every write goes nowhere, and a `/join` says so inside the send. */
    private val model = ChatViewModel(
        scope = scope,
        sessions = SessionStore(MapSecure()),
        settingsCache = SettingsCache(MapDefaults()),
        oauthClients = OAuthClients(MapDefaults()),
        formatExpiry = { it.toString() },
    )

    private val chat = BufferKey(networkId = 1, target = "#chat")

    @Test
    fun theFieldAndItsDraftAreEmptyBeforeTheLineGoesIn() {
        val composer = ComposerState(model, chat, BufferKind.Channel, scope)
        composer.field.setTextAndPlaceCursorAtEnd("/join #other")
        composer.fieldChanged(composer.snapshot())
        assertEquals("the typed command is this buffer's draft", "/join #other", model.draft(chat)?.body)

        // The join can't go out, so the kit says so from inside `send` — the moment a landing
        // would switch the screen.
        var duringSend: Pair<String, String?>? = null
        model.onJoinNotice = { notice ->
            if (notice is JoinNotice.NotConnected) duringSend = composer.field.text.toString() to model.draft(chat)?.body
        }
        composer.send()

        assertEquals("cleared, and the draft with it, before the send ran", "" to "", duringSend)
    }
}
