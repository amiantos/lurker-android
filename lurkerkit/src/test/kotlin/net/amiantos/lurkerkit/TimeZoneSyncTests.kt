// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.session.ChatViewModel
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sweep L08: the phone writes its time zone to `system.timezone` when the server's differs, as the
 * web does, so push quiet hours and the auto-away stamp follow the phone rather than the last browser.
 *
 * Port note: each test builds its own view model over fresh in-memory storage (`testViewModel`), where
 * LurkerKit's share a `UserDefaults` suite it clears in `tearDown`.
 */
class TimeZoneSyncTests {

    private fun makeModel(): Pair<ChatViewModel, () -> List<String>> {
        val model = testViewModel()
        val written = mutableListOf<String>()
        model.timeZoneWriteSeam = { written.add(it) }
        return model to { written.toList() }
    }

    private val here: String get() = TimeZone.getDefault().id

    /** A zone no test machine is in, so it always differs from [here]. */
    private val elsewhere: String get() = if (here == "Pacific/Chatham") "Pacific/Kiritimati" else "Pacific/Chatham"

    private fun bootstrap(zone: String?) = ServerFrame.SettingsBootstrap(
        registry = emptyMap(),
        values = zone?.let { mapOf("system.timezone" to SettingValue.String(it)) } ?: emptyMap(),
    )

    @Test
    fun testABootstrapWithAnotherZoneWritesThePhones() {
        val (model, written) = makeModel()
        model.handle(bootstrap(elsewhere))
        assertEquals(listOf(here), written())
    }

    @Test
    fun testABootstrapWithNoZoneWritesThePhones() {
        val (model, written) = makeModel()
        model.handle(bootstrap(null))
        assertEquals(listOf(here), written())
    }

    @Test
    fun testABootstrapAlreadyInThisZoneWritesNothing() {
        val (model, written) = makeModel()
        model.handle(bootstrap(here))
        assertEquals(emptyList(), written())
    }

    /** Another device's write is never answered, so two devices in different zones can't trade it. */
    @Test
    fun testAnotherDevicesZoneIsNotAnswered() {
        val (model, written) = makeModel()
        model.handle(bootstrap(here))
        model.handle(ServerFrame.SettingsChanged(mapOf("system.timezone" to SettingValue.String(elsewhere)), UploadLimits()))
        assertEquals(emptyList(), written())
    }

    @Test
    fun testABootstrapWhileTheWriteIsOutSendsNoSecond() {
        val (model, written) = makeModel()
        model.handle(bootstrap(elsewhere))
        model.handle(bootstrap(elsewhere))
        assertEquals(listOf(here), written())
    }

    /** The write failed, and a bootstrap waited behind it: that bootstrap still gets its answer. */
    @Test
    fun testABootstrapSkippedForAWriteIsAnsweredWhenItLands() {
        val (model, written) = makeModel()
        model.handle(bootstrap(elsewhere))
        model.handle(bootstrap(elsewhere))
        model.timeZoneWriteFinished()
        assertEquals(listOf(here, here), written())
    }

    /** Nothing waited: a refused zone isn't asked again until the next bootstrap, never in a loop. */
    @Test
    fun testAWriteThatLandsWithNothingWaitingAsksNoMore() {
        val (model, written) = makeModel()
        model.handle(bootstrap(elsewhere))
        model.timeZoneWriteFinished()
        assertEquals(listOf(here), written())
    }

    /** The write landed (its echo stored this zone): the waiting bootstrap finds nothing to do. */
    @Test
    fun testAWaitingBootstrapIsJudgedAgainstWhatIsStoredWhenTheWriteLands() {
        val (model, written) = makeModel()
        model.handle(bootstrap(elsewhere))
        model.handle(bootstrap(elsewhere))
        model.handle(ServerFrame.SettingsChanged(mapOf("system.timezone" to SettingValue.String(here)), UploadLimits()))
        model.timeZoneWriteFinished()
        assertEquals(listOf(here), written())
    }

    @Test
    fun testSignOutForgetsAWriteOut() {
        val (model, written) = makeModel()
        model.handle(bootstrap(elsewhere))
        model.logout()
        model.handle(bootstrap(elsewhere))
        assertEquals(listOf(here, here), written())
    }

    @Test
    fun testAnEmptyZoneIsNeverWritten() {
        val (model, written) = makeModel()
        model.syncTimeZone("")
        assertEquals(emptyList(), written())
    }
}
