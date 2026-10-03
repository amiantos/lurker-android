// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurker.ui.media.MediaViewerModel.PlayerFailure
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer's paging, zoom and dismissal rules, and how it saves — lurker-ios's `MediaViewerController`, minus the views. */
class MediaViewerModelTest {

    private fun image(n: Int) = LinkPreview(
        url = "https://example.com/$n.png", status = LinkPreview.Status.Ok, kind = PreviewKind.Image, src = "/p/$n",
        title = "picture \"$n\"", thumbWidth = 640, thumbHeight = 480,
    )

    private val clip = LinkPreview(url = "https://example.com/c.mp4", status = LinkPreview.Status.Ok, kind = PreviewKind.Video, mime = "video/mp4")

    @Test
    fun aGalleryOpensWhereItWasAskedClampedIntoRange() {
        val previews = listOf(image(1), image(2), clip)
        assertEquals(1, Gallery.of(previews, 1)?.start)
        assertEquals(2, Gallery.of(previews, 9)?.start)
        assertEquals(0, Gallery.of(previews, -3)?.start)
        assertNull(Gallery.of(emptyList(), 0))
    }

    @Test
    fun aGallerySurvivesBeingSaved() {
        val gallery = Gallery.of(listOf(image(1), clip), 1)
        assertEquals(gallery, Gallery.restore(Gallery.save(gallery)))
        assertEquals("", Gallery.save(null))
        assertNull(Gallery.restore(""))
        assertNull(Gallery.restore("1\nnot json"))
    }

    @Test
    fun theCounterShowsOnlyOverMoreThanOne() {
        assertEquals("2 of 3", MediaViewerModel.counter(1, 3))
        assertNull(MediaViewerModel.counter(0, 1))
    }

    @Test
    fun videoAndAudioArePlayersPicturesAreNot() {
        assertTrue(MediaViewerModel.isPlayer(clip))
        assertTrue(MediaViewerModel.isPlayer(clip.copy(kind = PreviewKind.Audio)))
        assertFalse(MediaViewerModel.isPlayer(image(1)))
    }

    @Test
    fun aPictureIsFittedWholeOnItsPage() {
        // A 4000x1000 panorama on a 400x800 page: full width, a quarter as tall.
        assertEquals(400f to 100f, MediaViewerModel.fitted(4000f, 1000f, 400f, 800f))
        // A 1000x4000 strip: full height, a quarter as wide.
        assertEquals(200f to 800f, MediaViewerModel.fitted(1000f, 4000f, 400f, 800f))
        // Not known yet: the page.
        assertEquals(400f to 800f, MediaViewerModel.fitted(0f, 0f, 400f, 800f))
    }

    @Test
    fun aZoomedPictureFillingItsPageCanTravelHalfWhatTheZoomAdded() {
        // A picture filling a 400x800 page, at 2x: 200 sideways, 400 up and down, each way.
        assertEquals(200f to -400f, MediaViewerModel.clampOffset(999f, -999f, 2f, 400f, 800f, 400f, 800f))
        assertEquals(50f to 60f, MediaViewerModel.clampOffset(50f, 60f, 2f, 400f, 800f, 400f, 800f))
        // At fit it's centred, whatever the fingers did.
        assertEquals(0f to 0f, MediaViewerModel.clampOffset(50f, 60f, 1f, 400f, 800f, 400f, 800f))
    }

    @Test
    fun aPanoramaPansSidewaysButNeverIntoItsLetterbox() {
        // Fitted 400x100 on a 400x800 page. At 4x it's 1600x400: 600 of travel each way sideways, and
        // still shorter than the page, so it stays centred vertically.
        val (w, h) = MediaViewerModel.fitted(4000f, 1000f, 400f, 800f)
        assertEquals(600f to 0f, MediaViewerModel.clampOffset(999f, 999f, 4f, 400f, 800f, w, h))
        assertEquals(-600f to 0f, MediaViewerModel.clampOffset(-999f, -999f, 4f, 400f, 800f, w, h))
    }

    @Test
    fun aTallPortraitPansUpAndDownButOnlySidewaysOnceItOverhangs() {
        // Fitted 200x800. At 1.5x it's 300 wide — still inside the 400 page, so no sideways travel; 200
        // up and down. At 3x it's 600 wide: 100 each way.
        val (w, h) = MediaViewerModel.fitted(1000f, 4000f, 400f, 800f)
        assertEquals(0f to 200f, MediaViewerModel.clampOffset(999f, 999f, 1.5f, 400f, 800f, w, h))
        assertEquals(100f to -800f, MediaViewerModel.clampOffset(999f, -999f, 3f, 400f, 800f, w, h))
    }

    @Test
    fun aDoubleTapKeepsThePointUnderTheFinger() {
        // Tapping the centre zooms about the centre.
        assertEquals(0f to 0f, MediaViewerModel.zoomOffset(200f, 400f, 2.5f, 400f, 800f, 400f, 800f))
        // Left of centre moves the picture right, as far as the edge allows.
        val (x, y) = MediaViewerModel.zoomOffset(100f, 400f, 2.5f, 400f, 800f, 400f, 800f)
        assertEquals(150f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
        val (edgeX, _) = MediaViewerModel.zoomOffset(0f, 400f, 2.5f, 400f, 800f, 400f, 800f)
        assertEquals(300f, edgeX, 0.001f)
    }

    @Test
    fun aDoubleTapInAPanoramasLetterboxDoesntMoveItOffTheCentreLine() {
        val (w, h) = MediaViewerModel.fitted(4000f, 1000f, 400f, 800f)
        // A tap near the top, in the black: vertically it stays centred.
        val (_, y) = MediaViewerModel.zoomOffset(200f, 50f, 2.5f, 400f, 800f, w, h)
        assertEquals(0f, y, 0.001f)
    }

    @Test
    fun aHairOverFitIsNotZoomedIn() {
        assertFalse(MediaViewerModel.isZoomed(1.005f))
        assertTrue(MediaViewerModel.isZoomed(1.2f))
    }

    @Test
    fun aSwipeDownDismissesPastItsDistanceOrItsSpeed() {
        assertFalse(MediaViewerModel.dismisses(100f, 200f))
        assertTrue(MediaViewerModel.dismisses(121f, 0f))
        assertTrue(MediaViewerModel.dismisses(20f, 901f))
    }

    @Test
    fun theGroundFadesAsTheSwipeTravelsButNeverBelowFortyPercent() {
        assertEquals(1f, MediaViewerModel.groundAlpha(0f), 0.001f)
        assertEquals(0.75f, MediaViewerModel.groundAlpha(100f), 0.001f)
        assertEquals(0.4f, MediaViewerModel.groundAlpha(1_000f), 0.001f)
        assertEquals(1f, MediaViewerModel.groundAlpha(-50f), 0.001f)
    }

    @Test
    fun reachingAFileAndDecodingItAreDifferentFailures() {
        // 2001 network failure, 2004 bad HTTP status, 3001 malformed container: couldn't be loaded.
        assertEquals(PlayerFailure.Unreachable, MediaViewerModel.failure(2001))
        assertEquals(PlayerFailure.Unreachable, MediaViewerModel.failure(2004))
        assertEquals(PlayerFailure.Unreachable, MediaViewerModel.failure(3001))
        // 3003 no extractor, 4001–4005 decoders: the format.
        assertEquals(PlayerFailure.Format, MediaViewerModel.failure(3003))
        assertEquals(PlayerFailure.Format, MediaViewerModel.failure(4001))
        assertEquals(PlayerFailure.Format, MediaViewerModel.failure(4005))
        assertEquals(PlayerFailure.Unreachable, MediaViewerModel.failure(1000))
    }

    @Test
    fun theFailureSentencesMatchIos() {
        assertEquals("There's nothing to play here.", PlayerFailure.Nothing.message)
        assertEquals("This couldn't be loaded.", PlayerFailure.Unreachable.message)
        assertEquals("This format can't be played on Android.", PlayerFailure.Format.message)
    }
}
