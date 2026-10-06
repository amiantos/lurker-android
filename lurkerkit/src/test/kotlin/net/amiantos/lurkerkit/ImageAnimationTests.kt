// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.ImageAnimation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// "Image animation (sweep L13)"
// Port note: MIME types where the Swift has UTIs; `public.avis` has no MIME and isn't in the set
// (see `ImageAnimation`), so the first test checks the other four.
class ImageAnimationTests {
    // "the formats that animate play when they hold several images"
    @Test
    fun animationsPlay() {
        for (type in listOf("image/gif", "image/png", "image/webp", "image/heic-sequence")) {
            assertTrue(ImageAnimation.plays(frameCount = 12, typeIdentifier = type), type)
        }
    }

    // "⚠ a still with several images never plays"
    @Test
    fun stillsWithSeveralImagesDont() {
        // An Ultra HDR photo reads as the photo and its gain map; played, it flickered between them.
        assertFalse(ImageAnimation.plays(frameCount = 2, typeIdentifier = "image/jpeg"))
        assertFalse(ImageAnimation.plays(frameCount = 2, typeIdentifier = "image/mpo"))
        assertFalse(ImageAnimation.plays(frameCount = 3, typeIdentifier = "image/tiff"))
        assertFalse(ImageAnimation.plays(frameCount = 2, typeIdentifier = "image/x-icon"))
        assertFalse(ImageAnimation.plays(frameCount = 3, typeIdentifier = "image/heic"))
        assertFalse(ImageAnimation.plays(frameCount = 2, typeIdentifier = "image/avif"))
    }

    // "one image, or a type ImageIO couldn't name, is a still"
    @Test
    fun oneImageOrNoTypeIsAStill() {
        assertFalse(ImageAnimation.plays(frameCount = 1, typeIdentifier = "image/gif"))
        assertFalse(ImageAnimation.plays(frameCount = 12, typeIdentifier = null))
    }
}
