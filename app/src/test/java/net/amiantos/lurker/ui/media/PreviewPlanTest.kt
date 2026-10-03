// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.PreviewKind
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which previews a row draws and which addresses its text drops — lurker-ios's `MessageListRenderer.previewPlan`. */
class PreviewPlanTest {

    private val both = PreviewToggles(inlineMedia = true, linkPreviews = true)

    private fun message(text: String, type: EventType = EventType.Message) =
        Message(id = 1, type = type, nick = "alice", text = text, msgid = "m1")

    private fun image(url: String) = LinkPreview(url = url, status = LinkPreview.Status.Ok, kind = PreviewKind.Image, src = "/p/${url.hashCode()}")

    private fun page(url: String) = LinkPreview(url = url, status = LinkPreview.Status.Ok, kind = PreviewKind.Page, title = "t")

    private fun plan(
        text: String,
        known: List<LinkPreview>,
        toggles: PreviewToggles = both,
        pending: Set<String> = emptySet(),
        type: EventType = EventType.Message,
    ): PreviewPlan {
        val byUrl = known.associateBy { it.url }
        return PreviewPlan.of(
            message(text, type),
            toggles,
            allSettled = { urls -> urls.none { it in pending } },
            preview = { byUrl[it] },
        )
    }

    @Test
    fun aMessageThatIsOnlyAPictureLosesItsAddress() {
        val url = "https://example.com/cat.png"
        val result = plan(url, listOf(image(url)))
        assertEquals(setOf(url), result.hidden)
        assertEquals(listOf(image(url)), result.resolved)
    }

    @Test
    fun aCardKeepsItsAddress() {
        val url = "https://example.com/article"
        val result = plan(url, listOf(page(url)))
        assertTrue(result.hidden.isEmpty())
        assertEquals(listOf(page(url)), result.resolved)
    }

    @Test
    fun aPictureMidSentenceKeepsItsAddress() {
        val url = "https://example.com/cat.png"
        val result = plan("look at $url isn't it great", listOf(image(url)))
        assertTrue(result.hidden.isEmpty())
        assertEquals(1, result.resolved.size)
    }

    @Test
    fun nothingShowsUntilEveryUrlHasSettled() {
        val a = "https://example.com/a.png"
        val b = "https://example.com/b.png"
        assertEquals(PreviewPlan.None, plan("$a $b", listOf(image(a)), pending = setOf(b)))
        assertEquals(2, plan("$a $b", listOf(image(a), image(b))).resolved.size)
    }

    @Test
    fun theServersAnswerDecidesWhichSettingGoverns() {
        // An extensionless link that turned out to be a picture is inline media: with media off it's
        // dropped even though the card setting asked for it.
        val url = "https://example.com/photo"
        val cardsOnly = PreviewToggles(inlineMedia = false, linkPreviews = true)
        assertTrue(plan(url, listOf(image(url)), toggles = cardsOnly).resolved.isEmpty())
    }

    @Test
    fun anUnavailableAnswerDrawsNothing() {
        val url = "https://example.com/gone.png"
        val dead = LinkPreview(url = url, status = LinkPreview.Status.Unavailable, kind = PreviewKind.Image)
        val result = plan(url, listOf(dead))
        assertTrue(result.resolved.isEmpty())
        assertTrue(result.hidden.isEmpty())
    }

    @Test
    fun aPosterlessClipKeepsItsAddress() {
        val url = "https://example.com/clip.mp4"
        val clip = LinkPreview(url = url, status = LinkPreview.Status.Ok, kind = PreviewKind.Video)
        val result = plan(url, listOf(clip))
        assertEquals(listOf(clip), result.resolved)
        assertTrue(result.hidden.isEmpty())
    }

    @Test
    fun linesThatArentSpeechHaveNoPreviews() {
        val url = "https://example.com/cat.png"
        assertEquals(PreviewPlan.None, plan(url, listOf(image(url)), type = EventType.Join))
    }

    @Test
    fun mentionsAnyReadsTheSameAddressesThePlanDoes() {
        val url = "https://example.com/cat.png"
        assertTrue(PreviewPlan.mentionsAny(message("see $url"), setOf(url), both))
        assertFalse(PreviewPlan.mentionsAny(message("see $url"), setOf("https://other.example/x.png"), both))
        assertFalse(PreviewPlan.mentionsAny(message("see $url"), setOf(url), PreviewToggles(inlineMedia = false, linkPreviews = true)))
        assertFalse(PreviewPlan.mentionsAny(message("no links here"), setOf(url), both))
    }

    @Test
    fun bothSettingsDefaultOffAndTheInstanceFlagGatesThem() {
        assertNull(PreviewToggles.resolve(instanceHasPreviews = true, settings = Settings()))
        val on = Settings(
            registry = emptyMap(),
            values = mapOf(PreviewToggles.INLINE_MEDIA_KEY to SettingValue.Bool(true)),
        )
        assertEquals(PreviewToggles(inlineMedia = true, linkPreviews = false), PreviewToggles.resolve(instanceHasPreviews = true, settings = on))
        // A `true` that travelled from an instance with previews to one without draws nothing.
        assertNull(PreviewToggles.resolve(instanceHasPreviews = false, settings = on))
    }
}
