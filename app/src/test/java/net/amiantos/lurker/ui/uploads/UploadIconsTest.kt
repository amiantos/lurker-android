// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.uploads

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every upload glyph's path data parses — built lazily, a typo would throw on a device instead. */
class UploadIconsTest {
    @Test
    fun everyIconBuilds() {
        val icons = listOf(
            UploadIcons.AttachFile, UploadIcons.PhotoLibrary, UploadIcons.Star, UploadIcons.StarBorder, UploadIcons.Image,
            UploadIcons.Movie, UploadIcons.Audiotrack, UploadIcons.Description, UploadIcons.InsertDriveFile, UploadIcons.Delete,
            UploadIcons.FilterList, UploadIcons.Link,
        )
        for (icon in icons) assertTrue(icon.name, icon.root.iterator().hasNext())
    }
}
