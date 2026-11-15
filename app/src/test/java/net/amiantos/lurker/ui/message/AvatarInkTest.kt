// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import net.amiantos.lurkerkit.model.AvatarInk
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ink a tile takes from the Compose colour under it ([AvatarColumn.inkOn]). */
class AvatarInkTest {
    @Test
    fun aLightColourTakesDarkInkAndADarkOneLight() {
        assertEquals(AvatarInk.Dark, AvatarColumn.inkOn(Color(0xFFFFD866)))
        assertEquals(AvatarInk.Dark, AvatarColumn.inkOn(Color.White))
        assertEquals(AvatarInk.Light, AvatarColumn.inkOn(Color(0xFF3163C0)))
        assertEquals(AvatarInk.Light, AvatarColumn.inkOn(Color.Black))
    }

    @Test
    fun eitherSideOfTheLineInTheBuiltInPalettes() {
        // The colours nearest the line: the dark palette's darkest (#ed6c89, 0.305) and the light
        // palette's lightest (#409ba9, 0.274), around 0.28. The kit's test walks both whole.
        assertEquals(AvatarInk.Dark, AvatarColumn.inkOn(Color(0xFFED6C89)))
        assertEquals(AvatarInk.Light, AvatarColumn.inkOn(Color(0xFF409BA9)))
    }

    @Test
    fun theMutedGreyUnderAServerMarkKeepsDarkInkOnTheDarkTheme() {
        assertEquals(AvatarInk.Dark, AvatarColumn.inkOn(Color(0xFF939293)))
    }

    @Test
    fun aColourCarriedInAnotherSpaceIsReadAsSrgb() {
        val yellow = Color(0xFFFFD866)
        assertEquals(AvatarColumn.inkOn(yellow), AvatarColumn.inkOn(yellow.convert(ColorSpaces.DisplayP3)))
        val blue = Color(0xFF3163C0)
        assertEquals(AvatarColumn.inkOn(blue), AvatarColumn.inkOn(blue.convert(ColorSpaces.DisplayP3)))
    }
}
