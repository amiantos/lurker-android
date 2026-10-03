// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every glyph's path data parses. The icons are built lazily, so a typo in one would throw the first
 * time a screen drew it — on a device, in front of someone — rather than at build time.
 */
class LurkerIconsTest {
    @Test
    fun everyIconBuilds() {
        val icons = listOf(
            LurkerIcons.Pencil, LurkerIcons.MoreVert, LurkerIcons.Add, LurkerIcons.Close, LurkerIcons.ArrowBack,
            LurkerIcons.Keyboard, LurkerIcons.Visibility, LurkerIcons.VisibilityOff, LurkerIcons.Search, LurkerIcons.KeyboardArrowUp,
            LurkerIcons.KeyboardArrowDown, LurkerIcons.Warning, LurkerIcons.Language, LurkerIcons.ChevronRight, LurkerIcons.Check,
            LurkerIcons.ArrowDropDown, LurkerIcons.Remove,
            LurkerIcons.Info, LurkerIcons.Group, LurkerIcons.AccountCircle, LurkerIcons.ContentCopy, LurkerIcons.Refresh,
            LurkerIcons.ChatBubble, LurkerIcons.Tune, LurkerIcons.ListBullet, LurkerIcons.HelpOutline, LurkerIcons.MoreHoriz,
            LurkerIcons.CheckCircle, LurkerIcons.Shield, LurkerIcons.Memory, LurkerIcons.Antenna,
            LurkerIcons.Reply, LurkerIcons.Smile, LurkerIcons.BookmarkBorder, LurkerIcons.BookmarkFilled, LurkerIcons.AlternateEmail, LurkerIcons.BookmarkRemove, LurkerIcons.OpenInNew,
            LurkerIcons.Share, LurkerIcons.Block,
        )
        for (icon in icons) assertTrue(icon.name, icon.root.iterator().hasNext())
    }
}
