// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import net.amiantos.lurkerkit.model.UploadItem
import net.amiantos.lurkerkit.model.UploadKind
import net.amiantos.lurkerkit.model.UploadsFilter
import net.amiantos.lurkerkit.model.UploadsPage
import net.amiantos.lurkerkit.model.UploadsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The uploads browser's list as a value — lurker-ios's `UploadsViewController` loading and row actions. */
class UploadsGridTest {
    private fun item(id: Int, favorite: Boolean = false, removed: Boolean = false, canDelete: Boolean = false, mime: String? = "image/png") =
        UploadItem(id = id, url = "https://u/$id", filename = "f$id.png", mime = mime, favorite = favorite, canDelete = canDelete, removed = removed)

    private fun page(vararg ids: Int) = UploadsPage(ids.map { item(it) })

    private fun loaded(vararg ids: Int, filter: UploadsFilter = UploadsFilter()): UploadsGrid {
        val (loading, request) = UploadsGrid().reload(filter)
        return loading.firstPage(request, page(*ids)).first
    }

    @Test
    fun aFirstPageFillsTheGridAndSetsTheCursor() {
        val (loading, request) = UploadsGrid().reload()
        assertTrue(loading.isLoading)
        assertEquals(UploadsRequest.pageSize, request.limit)
        assertNull(request.before)
        val (grid, toTop) = loading.firstPage(request, page(9, 8, 7))
        assertFalse(grid.isLoading)
        assertEquals(7, grid.cursor)
        // A short page is everything.
        assertFalse(grid.hasMore)
        assertFalse(toTop)
    }

    @Test
    fun aFullPageMeansAskAgainFromTheLastId() {
        val ids = (100 downTo 51).toList().toIntArray()
        val grid = loaded(*ids)
        assertTrue(grid.hasMore)
        val (more, request) = grid.loadMore()!!
        assertEquals(51, request.before)
        // Nothing else while that's out.
        assertNull(more.loadMore())
        val next = more.nextPage(request, page(50, 49))
        assertEquals(52, next.items.size)
        assertEquals(49, next.cursor)
        assertFalse(next.hasMore)
    }

    @Test
    fun aSupersededPageChangesNothing() {
        val (first, oldRequest) = UploadsGrid().reload()
        val (second, _) = first.reload(UploadsFilter(query = "cat"))
        val (after, _) = second.firstPage(oldRequest, page(1, 2))
        assertEquals(second, after)
        // Including `isLoading`, which would let a scroll page the old cursor into the new list.
        assertTrue(after.isLoading)
    }

    @Test
    fun aNewQuestionsAnswerGoesBackToTheTop() {
        val grid = loaded(3, 2, 1)
        val (loading, request) = grid.reload(UploadsFilter(query = "cat"))
        // The old rows stay up while the new question is out.
        assertEquals(3, loading.items.size)
        val (answered, toTop) = loading.firstPage(request, page(2))
        assertTrue(toTop)
        assertEquals(UploadsFilter(query = "cat"), answered.shownFilter)
    }

    @Test
    fun aFailedNewQuestionTakesTheOldRowsWithIt() {
        val grid = loaded(3, 2, 1)
        val (loading, request) = grid.reload(UploadsFilter(kind = UploadKind.Video))
        val (failed, _) = loading.firstPage(request, null)
        assertTrue(failed.loadFailed)
        assertTrue(failed.items.isEmpty())
        assertNull(failed.cursor)
        assertFalse(failed.hasMore)
        assertEquals(UploadsPlaceholder("Couldn't load uploads", "Pull to try again."), failed.placeholder)
    }

    @Test
    fun aFailedRefreshKeepsItsRows() {
        val grid = loaded(3, 2, 1)
        val (loading, request) = grid.reload(byPull = true)
        assertTrue(loading.refreshing)
        val (failed, _) = loading.firstPage(request, null)
        assertEquals(3, failed.items.size)
        assertFalse(failed.refreshing)
        assertNull(failed.placeholder)
    }

    @Test
    fun aFailedContinuationLeavesTheGridAloneAndOffersATryAgain() {
        val ids = (100 downTo 51).toList().toIntArray()
        val (more, request) = loaded(*ids).loadMore()!!
        val after = more.nextPage(request, null)
        assertEquals(50, after.items.size)
        assertFalse(after.isLoading)
        assertTrue(after.hasMore)
        assertTrue(after.pageInFailed)
        // Try Again asks from the same cursor, and the foot clears while it's out.
        val (retrying, again) = after.loadMore()!!
        assertEquals(51, again.before)
        assertFalse(retrying.pageInFailed)
        // A new question clears it too.
        assertFalse(after.reload().first.pageInFailed)
    }

    @Test
    fun theStarredViewArrivesWholeAndSaysWhenItWasCut() {
        val starred = UploadsFilter(favoritesOnly = true)
        val (loading, request) = UploadsGrid().reload(starred)
        assertEquals(UploadsRequest.favoritesLimit, request.limit)
        val full = UploadsPage((1..UploadsRequest.favoritesLimit).map { item(it, favorite = true) })
        val (grid, _) = loading.firstPage(request, full)
        assertFalse(grid.hasMore)
        assertTrue(grid.isTruncated)
        assertEquals("Showing your 200 most recently starred uploads.", grid.footer)
        // A new question clears the disclosure at once — it described the previous list.
        assertFalse(grid.reload(UploadsFilter()).first.isTruncated)
    }

    @Test
    fun aStarFlipsAtOnceAndARefusalPutsItBack() {
        val grid = loaded(3, 2, 1)
        val (starred, revert) = grid.star(grid.items[1])!!
        assertTrue(starred.items[1].favorite)
        val reverted = starred.revert(revert)
        assertFalse(reverted.items[1].favorite)
    }

    @Test
    fun unstarringInTheStarredViewRemovesTheRowAndARefusalRestoresItInPlace() {
        val starred = UploadsFilter(favoritesOnly = true)
        val (loading, request) = UploadsGrid().reload(starred)
        val grid = loading.firstPage(request, UploadsPage(listOf(item(3, true), item(2, true), item(1, true)))).first
        val (gone, revert) = grid.star(grid.items[1])!!
        assertEquals(listOf(3, 1), gone.items.map { it.id })
        assertEquals(listOf(3, 2, 1), gone.revert(revert).items.map { it.id })
    }

    @Test
    fun aRevertNeverLandsInAListThatReplacedItsOwn() {
        val grid = loaded(3, 2, 1)
        val (starred, revert) = grid.star(grid.items[0])!!
        val (video, request) = starred.reload(UploadsFilter(kind = UploadKind.Video))
        val replaced = video.firstPage(request, page(9)).first
        assertEquals(replaced, replaced.revert(revert))
    }

    @Test
    fun aDeleteRemovesTheRowAndMovesTheCursor() {
        val grid = loaded(3, 2, 1).deleted(1)
        assertEquals(listOf(3, 2), grid.items.map { it.id })
        assertEquals(2, grid.cursor)
    }

    @Test
    fun theNextPageIsAskedForBeforeTheBottom() {
        val grid = loaded(*(100 downTo 51).toList().toIntArray())
        assertFalse(grid.wantsMore(10))
        assertTrue(grid.wantsMore(50 - UploadsGrid.PREFETCH))
    }

    @Test
    fun anEmptyGridNamesWhyInWords() {
        assertEquals("Loading uploads…", UploadsGrid().reload().first.placeholder!!.title)
        assertEquals(
            UploadsPlaceholder("No uploads yet", "Files you send with the paperclip in a conversation are kept here."),
            loaded().placeholder,
        )
        assertEquals(
            UploadsPlaceholder("No matches", "No starred image uploads match “march”."),
            loaded(filter = UploadsFilter(query = "march", kind = UploadKind.Image, favoritesOnly = true)).placeholder,
        )
        assertEquals(
            UploadsPlaceholder("No starred uploads", "Press and hold an upload, then Star, to keep it here."),
            loaded(filter = UploadsFilter(favoritesOnly = true)).placeholder,
        )
        assertEquals(
            UploadsPlaceholder("No video uploads", "Nothing you've uploaded is under this filter."),
            loaded(filter = UploadsFilter(kind = UploadKind.Video)).placeholder,
        )
    }

    @Test
    fun addToMessageOnlyWhereThereIsAComposer() {
        val file = item(1, canDelete = true)
        assertEquals(
            listOf(UploadAction.OpenInBrowser, UploadAction.AddToMessage, UploadAction.Star, UploadAction.CopyLink, UploadAction.Share, UploadAction.Delete),
            UploadTiles.actions(file, canInsert = true),
        )
        assertFalse(UploadAction.AddToMessage in UploadTiles.actions(file, canInsert = false))
        assertEquals(UploadAction.View, UploadTiles.actions(file, canInsert = false, viewable = true).first())
    }

    @Test
    fun deleteOnlyWhereTheBytesCanReallyGo() {
        assertFalse(UploadAction.Delete in UploadTiles.actions(item(1, canDelete = false), canInsert = true))
    }

    @Test
    fun aTombstoneOffersOnlyToClearAStarItAlreadyHas() {
        assertEquals(emptyList<UploadAction>(), UploadTiles.actions(item(1, removed = true), canInsert = true))
        assertEquals(listOf(UploadAction.Unstar), UploadTiles.actions(item(1, removed = true, favorite = true), canInsert = true))
    }

    @Test
    fun theMetaLineIsWhenAndHowBig() {
        val dated = UploadItem(id = 1, url = "u", createdAt = Instant.EPOCH, byteSize = 2048)
        assertEquals("5m · 2 KB", UploadTiles.metaLine(dated, relative = { "5m" }, bytes = { "${it / 1024} KB" }))
        assertEquals("Removed", UploadTiles.metaLine(dated.copy(removed = true), relative = { "5m" }, bytes = { "x" }))
        assertEquals("", UploadTiles.metaLine(UploadItem(id = 1, url = "u"), relative = { "5m" }, bytes = { "x" }))
        assertEquals("(pasted), starred, 5m", UploadTiles.accessibility(UploadItem(id = 1, url = "u", favorite = true), "5m"))
    }

    @Test
    fun aTileWithoutAPictureGetsItsKindsGlyph() {
        assertEquals(UploadTiles.Glyph.Video, UploadTiles.glyph(item(1, mime = "video/mp4")))
        assertEquals(UploadTiles.Glyph.Text, UploadTiles.glyph(item(1, mime = "application/json")))
        assertEquals(UploadTiles.Glyph.File, UploadTiles.glyph(item(1, mime = "application/pdf")))
        assertEquals(UploadTiles.Glyph.Removed, UploadTiles.glyph(item(1, removed = true)))
    }

    @Test
    fun neverFewerThanTwoColumns() {
        assertEquals(2, UploadTiles.columns(200f))
        assertEquals(2, UploadTiles.columns(338f))
        assertEquals(5, UploadTiles.columns(778f))
    }

    @Test
    fun ageIsInTheLargestUnitThatFits() {
        val now = Instant.parse("2026-10-03T12:00:00Z")
        assertEquals(RelativeAge.Now, RelativeAge.of(now, now))
        // A clock a hair ahead of ours still reads as now, never "in 0 sec."
        assertEquals(RelativeAge.Now, RelativeAge.of(now.plusMillis(300), now))
        assertEquals(RelativeAge.Ago(45, RelativeAge.Span.Seconds), RelativeAge.of(now.minusSeconds(45), now))
        assertEquals(RelativeAge.Ago(5, RelativeAge.Span.Minutes), RelativeAge.of(now.minusSeconds(300), now))
        assertEquals(RelativeAge.Ago(2, RelativeAge.Span.Days), RelativeAge.of(now.minusSeconds(2 * 86_400), now))
        assertEquals(RelativeAge.Ago(3, RelativeAge.Span.Weeks), RelativeAge.of(now.minusSeconds(21 * 86_400), now))
        assertEquals(RelativeAge.Ago(1, RelativeAge.Span.Years), RelativeAge.of(now.minusSeconds(400 * 86_400), now))
    }
}
