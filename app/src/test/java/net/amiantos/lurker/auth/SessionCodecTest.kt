// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.auth

import net.amiantos.lurker.model.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The persisted-session JSON codec is pure (no Keystore), so it's unit-testable.
 * The AES-GCM encrypt/decrypt around it is Android-only and covered by manual QA.
 */
class SessionCodecTest {

    @Test
    fun `round-trips a session`() {
        val original = PersistedSession(Backend.Hosted, "https://app.lurker.chat", "tok-123")
        val decoded = decodeSession(encodeSession(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips the self-hosted backend too`() {
        val original = PersistedSession(Backend.SelfHosted, "http://10.0.2.2:8010", "abc")
        assertEquals(original, decodeSession(encodeSession(original)))
    }

    @Test
    fun `malformed json decodes to null, not a crash`() {
        assertNull(decodeSession("not json"))
        assertNull(decodeSession(""))
    }

    @Test
    fun `an unknown backend name decodes to null`() {
        assertNull(decodeSession("""{"backend":"Telepathy","server":"x","token":"y"}"""))
    }

    @Test
    fun `a blob missing the token or server decodes to null`() {
        assertNull(decodeSession("""{"backend":"Hosted","server":"x"}"""))
        assertNull(decodeSession("""{"backend":"Hosted","token":"y"}"""))
        assertNull(decodeSession("""{"backend":"Hosted","server":"","token":"y"}"""))
    }
}
