// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.media

import net.amiantos.lurker.ui.media.AttachmentLayout.Block
import net.amiantos.lurker.ui.media.AttachmentLayout.CardShape
import net.amiantos.lurker.ui.media.AttachmentLayout.MosaicRow
import net.amiantos.lurker.ui.media.AttachmentLayout.Tap
import net.amiantos.lurkerkit.model.LinkPreview
import net.amiantos.lurkerkit.model.PreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a message's attachment block draws, and how big — lurker-ios's `MessageAttachmentsView`, minus the views. */
class AttachmentLayoutTest {

    private fun preview(
        name: String,
        kind: PreviewKind,
        src: String? = null,
        thumb: String? = null,
        w: Int? = null,
        h: Int? = null,
        title: String? = null,
        siteName: String? = null,
        author: String? = null,
        description: String? = null,
        url: String = "https://example.com/$name",
    ) = LinkPreview(
        url = url, status = LinkPreview.Status.Ok, kind = kind, src = src, thumb = thumb, thumbWidth = w, thumbHeight = h,
        title = title, siteName = siteName, author = author, description = description,
    )

    private fun image(n: Int) = preview("$n.png", PreviewKind.Image, src = "/p/$n")

    @Test
    fun oneImageIsAMediaBoxNotAMosaic() {
        assertEquals(listOf(Block.Media(image(1))), AttachmentLayout.blocks(listOf(image(1))))
    }

    @Test
    fun theMosaicLeadsAndEverythingElseFollowsInPostedOrder() {
        val card = preview("page", PreviewKind.Page, title = "t")
        val clip = preview("clip.mp4", PreviewKind.Video)
        val blocks = AttachmentLayout.blocks(listOf(card, image(1), clip, image(2)))
        assertEquals(
            listOf(
                Block.Mosaic(listOf(MosaicRow.Pair(image(1), image(2)))),
                Block.Card(card),
                Block.Media(clip),
            ),
            blocks,
        )
    }

    @Test
    fun videoAndAudioNeverGoInTheMosaic() {
        val clip = preview("a.mp4", PreviewKind.Video)
        val track = preview("a.mp3", PreviewKind.Audio)
        val blocks = AttachmentLayout.blocks(listOf(clip, track, image(1)))
        assertEquals(listOf(Block.Media(clip), Block.Media(track), Block.Media(image(1))), blocks)
    }

    @Test
    fun aVideoPageIsACard() {
        val embed = preview("watch", PreviewKind.VideoEmbed)
        assertEquals(listOf(Block.Card(embed)), AttachmentLayout.blocks(listOf(embed)))
    }

    @Test
    fun evenCountsArePairsAndOddOnesLeadWithTheThreeUp() {
        val images = (1..7).map(::image)
        assertEquals(listOf(MosaicRow.Pair(images[0], images[1])), AttachmentLayout.mosaicRows(images.take(2)))
        assertEquals(listOf(MosaicRow.ThreeUp(images[0], images[1], images[2])), AttachmentLayout.mosaicRows(images.take(3)))
        assertEquals(
            listOf(MosaicRow.Pair(images[0], images[1]), MosaicRow.Pair(images[2], images[3])),
            AttachmentLayout.mosaicRows(images.take(4)),
        )
        assertEquals(
            listOf(MosaicRow.ThreeUp(images[0], images[1], images[2]), MosaicRow.Pair(images[3], images[4]), MosaicRow.Pair(images[5], images[6])),
            AttachmentLayout.mosaicRows(images),
        )
    }

    @Test
    fun aMosaicsHeightComesFromItsCountAlone() {
        val images = (1..5).map(::image)
        // Three-up (160·2 + 4) + gap 4 + pair 160.
        assertEquals(488f, AttachmentLayout.mosaicHeight(AttachmentLayout.mosaicRows(images)), 0.001f)
        assertEquals(160f, AttachmentLayout.mosaicHeight(AttachmentLayout.mosaicRows(images.take(2))), 0.001f)
    }

    @Test
    fun aLoneImageIsShapedByItsDimensionsWithinBounds() {
        val landscape = preview("l.png", PreviewKind.Image, src = "/l", w = 1600, h = 900)
        assertEquals(180f, AttachmentLayout.mediaHeight(landscape, 320f), 0.001f)
        // A portrait screenshot clamps at the tallest box, a panorama at the shortest.
        val portrait = preview("p.png", PreviewKind.Image, src = "/p", w = 1080, h = 2400)
        assertEquals(AttachmentLayout.LONE_MAX_HEIGHT, AttachmentLayout.mediaHeight(portrait, 320f), 0.001f)
        val panorama = preview("w.png", PreviewKind.Image, src = "/w", w = 4000, h = 500)
        assertEquals(AttachmentLayout.LONE_MIN_HEIGHT, AttachmentLayout.mediaHeight(panorama, 320f), 0.001f)
    }

    @Test
    fun withoutDimensionsTheBoxIsTheFixedHeight() {
        assertEquals(AttachmentLayout.MEDIA_HEIGHT, AttachmentLayout.mediaHeight(image(1), 320f), 0.001f)
        val zero = preview("z.png", PreviewKind.Image, src = "/z", w = 0, h = 100)
        assertEquals(AttachmentLayout.MEDIA_HEIGHT, AttachmentLayout.mediaHeight(zero, 320f), 0.001f)
    }

    @Test
    fun aVideoPosterIsShapedButCoverArtIsNot() {
        val clip = preview("c.mp4", PreviewKind.Video, thumb = "/poster", w = 1080, h = 1920)
        assertEquals(AttachmentLayout.LONE_MAX_HEIGHT, AttachmentLayout.mediaHeight(clip, 320f), 0.001f)
        val track = preview("t.mp3", PreviewKind.Audio, thumb = "/art", w = 600, h = 600)
        assertEquals(AttachmentLayout.MEDIA_HEIGHT, AttachmentLayout.mediaHeight(track, 320f), 0.001f)
    }

    @Test
    fun aCardsPictureIsAChipBelowFourThreeAndAHeroOtherwise() {
        assertEquals(CardShape.None, AttachmentLayout.cardShape(preview("n", PreviewKind.Page)))
        // Logos (reddit, Ars Technica) and portraits (Wikipedia) are chips.
        assertEquals(CardShape.Chip, AttachmentLayout.cardShape(preview("r", PreviewKind.Page, thumb = "/t", w = 256, h = 256)))
        assertEquals(CardShape.Chip, AttachmentLayout.cardShape(preview("w", PreviewKind.Page, thumb = "/t", w = 869, h = 1200)))
        // GitHub's 2:1 is a hero, and so is a conventional 4:3 photo.
        assertEquals(CardShape.Hero, AttachmentLayout.cardShape(preview("g", PreviewKind.Page, thumb = "/t", w = 1200, h = 600)))
        assertEquals(CardShape.Hero, AttachmentLayout.cardShape(preview("p", PreviewKind.Page, thumb = "/t", w = 800, h = 600)))
    }

    @Test
    fun heroIsTheDefaultWhenNothingIsDeclared() {
        assertEquals(CardShape.Hero, AttachmentLayout.cardShape(preview("x", PreviewKind.Page, thumb = "/t")))
    }

    @Test
    fun aVideoPageAndAHeroStackAChipSitsBeside() {
        assertTrue(AttachmentLayout.stacksVertically(preview("v", PreviewKind.VideoEmbed)))
        assertTrue(AttachmentLayout.stacksVertically(preview("h", PreviewKind.Page, thumb = "/t")))
        assertFalse(AttachmentLayout.stacksVertically(preview("c", PreviewKind.Page, thumb = "/t", w = 64, h = 64)))
        assertFalse(AttachmentLayout.stacksVertically(preview("n", PreviewKind.Page)))
    }

    @Test
    fun theHeadingFallsBackToTheSiteThenTheHost() {
        assertEquals("Title", AttachmentLayout.heading(preview("a", PreviewKind.Page, title = "Title", siteName = "Site")))
        assertEquals("Site", AttachmentLayout.heading(preview("a", PreviewKind.Page, siteName = "Site")))
        assertEquals("example.com", AttachmentLayout.heading(preview("a", PreviewKind.Page)))
        assertNull(AttachmentLayout.heading(preview("a", PreviewKind.Page, url = "not a url")))
    }

    @Test
    fun theBylineNamesTheAuthorWhenThereIsOne() {
        assertEquals("GitHub · amiantos", AttachmentLayout.byline(preview("a", PreviewKind.Page, siteName = "GitHub", author = "amiantos")))
        assertEquals("GitHub", AttachmentLayout.byline(preview("a", PreviewKind.Page, siteName = "GitHub")))
        assertNull(AttachmentLayout.byline(preview("a", PreviewKind.Page, author = "amiantos")))
    }

    @Test
    fun spokenLabelsMatchIos() {
        val card = preview("a", PreviewKind.Page, siteName = "GitHub", title = "lurker", description = "an IRC client")
        assertEquals("GitHub, lurker, an IRC client", AttachmentLayout.cardLabel(card))
        assertEquals("lurker", AttachmentLayout.cardLabel(preview("a", PreviewKind.Page, title = "lurker")))
        assertEquals("Image", AttachmentLayout.mediaLabel(PreviewKind.Image))
        assertEquals("Animation", AttachmentLayout.mediaLabel(PreviewKind.Image, animated = true))
        assertEquals("Video", AttachmentLayout.mediaLabel(PreviewKind.Video))
        assertEquals("Audio", AttachmentLayout.mediaLabel(PreviewKind.Audio))
    }

    @Test
    fun aStillThatCantBeDrawnGetsAPhotoNotAPlayButton() {
        assertEquals(AttachmentLayout.Glyph.Photo, AttachmentLayout.glyph(PreviewKind.Image))
        assertEquals(AttachmentLayout.Glyph.Play, AttachmentLayout.glyph(PreviewKind.Video))
        assertEquals(AttachmentLayout.Glyph.Waveform, AttachmentLayout.glyph(PreviewKind.Audio))
    }

    @Test
    fun theGalleryIsEveryViewableItemInPostedOrder() {
        val noSrc = preview("broken.png", PreviewKind.Image)
        val clip = preview("c.mp4", PreviewKind.Video)
        val cleartext = preview("c2.mp4", PreviewKind.Video, url = "http://example.com/c2.mp4")
        val card = preview("p", PreviewKind.Page)
        val gallery = AttachmentLayout.gallery(listOf(image(1), noSrc, clip, cleartext, card, image(2)))
        assertEquals(listOf(image(1), clip, image(2)), gallery)
    }

    @Test
    fun aTapOpensTheViewerOnThatItemOrFallsBackToTheAddress() {
        val clip = preview("c.mp4", PreviewKind.Video)
        val gallery = listOf(image(1), clip)
        assertEquals(Tap.Viewer(gallery, 1), AttachmentLayout.tap(clip.url, gallery, canPresent = true))
        // Not in the gallery (a still the server couldn't draw), or nothing to present from.
        assertEquals(Tap.Open("https://example.com/x.png"), AttachmentLayout.tap("https://example.com/x.png", gallery, canPresent = true))
        assertEquals(Tap.Open(clip.url), AttachmentLayout.tap(clip.url, gallery, canPresent = false))
    }

    @Test
    fun blocksAndMosaicRowsAreKeyedByTheirAddresses() {
        val card = preview("page", PreviewKind.Page)
        assertEquals("card ${card.url}", AttachmentLayout.Block.Card(card).key)
        assertEquals("media ${image(1).url}", AttachmentLayout.Block.Media(image(1)).key)
        // A pair re-planned onto a different picture is a different row.
        val before = MosaicRow.Pair(image(1), image(2)).key
        val after = MosaicRow.Pair(image(1), image(3)).key
        assertTrue(before != after)
    }
}
