// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * How much of a preview image the app will decode.
 *
 * The relay streams origin bytes unresized under an 8 MB byte cap, and bytes say nothing about
 * pixels: a uniform-colour PNG whose canvas is 20,000 on a side compresses to a few hundred KB
 * and decodes to 1.6 GB, which is jetsam for every reader whose client paints it — and, since a
 * still is painted during backlog restore, jetsam again on relaunch while that row is in the
 * backlog. So a still's canvas is judged from its header before a pixel is decoded, and a canvas
 * past the bound is refused, however few bytes it took (lurker-ios#245).
 *
 * Port note: `Int` here is 32-bit where the Swift's is 64-bit; the division keeps the overflow
 * out either way, and a header past 2^31 reads as absent before it gets here (`Json`). The app's
 * own decode budgets (`ui/media/DecodeSize`, `PreviewImageLoader`) predate this and are stricter;
 * this is the kit's rule, for parity and for the loader to adopt.
 */
object PreviewImageBudget {
    /** The most pixels a still may decode to: 50 MP, a 200 MB canvas as ARGB — a phone's photo passes, a bomb doesn't. */
    const val maxPixels: Int = 50_000_000

    /** The longest edge a decoded still is sized down to, for the row's picture. */
    const val maxPixelSize: Int = 2048

    fun admits(pixelWidth: Int, pixelHeight: Int): Boolean {
        if (pixelWidth <= 0 || pixelHeight <= 0) return false
        // Division, not a product: a lying header can declare two dimensions whose product
        // overflows `Int`, and an overflow would be a crash (or a wrap) of our own making.
        return pixelHeight <= maxPixels / pixelWidth
    }
}
