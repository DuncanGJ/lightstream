package com.thelightphone.music.art

import kotlin.math.roundToInt

/** An 8-bit luminance image, row-major. */
class Gray(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) { "pixel count must match dimensions" } }
}

/**
 * The gen-1 iPod look, chosen on the contact sheets in `.scratch/dithered-album-art/`:
 * Atkinson error diffusion (only 6/8 of the error is passed on, which is what keeps edges and
 * text crisp) onto [levels] evenly spaced grays, scanned serpentine to avoid directional worms.
 *
 * [toe] is applied first: everything at or below it becomes pure black and the rest is rescaled.
 * Error diffusion turns a near-black radial gradient into a visible ring wherever it crosses a
 * palette threshold; clipping *during* diffusion cannot fix that (the carried error re-lights the
 * band one row later), a curve *before* it can. 16 is the lowest value that cleared the test
 * covers, with no visible cost on the light ones.
 */
fun dither(image: Gray, levels: Int = DITHER_LEVELS, toe: Int = DITHER_TOE): Gray {
    require(levels >= 2) { "need at least black and white" }
    val w = image.width
    val h = image.height
    val scale = 255.0 / (255 - toe)
    val buf = DoubleArray(w * h) { i -> ((image.pixels[i] - toe) * scale).coerceAtLeast(0.0) }
    val out = IntArray(w * h)
    val step = 255.0 / (levels - 1)
    for (y in 0 until h) {
        val reversed = y % 2 == 1
        for (col in 0 until w) {
            val x = if (reversed) w - 1 - col else col
            val old = buf[y * w + x]
            val new = ((old / step).roundToInt() * step).coerceIn(0.0, 255.0)
            out[y * w + x] = new.roundToInt()
            val err = (old - new) / 8.0
            for ((dx, dy) in ATKINSON) {
                val xx = if (reversed) x - dx else x + dx
                val yy = y + dy
                if (xx in 0 until w && yy < h) buf[yy * w + xx] += err
            }
        }
    }
    return Gray(w, h, out)
}

/**
 * Stretch the histogram so the darkest [cutoff] fraction maps to 0 and the lightest to 255, as a
 * scanned cover rarely uses the full range. Flat images are returned unchanged.
 */
fun autocontrast(image: Gray, cutoff: Double = 0.01): Gray {
    val sorted = image.pixels.copyOf().also { it.sort() }
    val lo = sorted[(sorted.size * cutoff).toInt().coerceIn(0, sorted.lastIndex)]
    val hi = sorted[(sorted.size * (1 - cutoff)).toInt().coerceIn(0, sorted.lastIndex)]
    if (hi <= lo) return image
    val k = 255.0 / (hi - lo)
    return Gray(image.width, image.height, IntArray(image.pixels.size) { i ->
        ((image.pixels[i] - lo) * k).roundToInt().coerceIn(0, 255)
    })
}

/** Atkinson kernel: six neighbours, each receiving 1/8 of the error (2/8 is dropped on purpose). */
private val ATKINSON = listOf(1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 0 to 2)

const val DITHER_LEVELS = 4
const val DITHER_TOE = 16
