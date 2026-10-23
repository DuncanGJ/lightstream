package com.thelightphone.music.art

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iPod look is one pure function over luminance: toe → autocontrast → Atkinson error
 * diffusion onto four grays. These pin the behaviours chosen on the contact sheets
 * (.scratch/dithered-album-art/): black stays black, midtones become a pattern, edges survive.
 */
class DitherTest {

    private fun flat(w: Int, h: Int, v: Int) = Gray(w, h, IntArray(w * h) { v })

    @Test
    fun `everything at or below the toe is pure black, so a dark vignette cannot ring`() {
        val out = dither(flat(16, 16, DITHER_TOE), levels = 4)
        assertTrue(out.pixels.all { it == 0 }, "toe-level input must not produce a single lit pixel")
    }

    @Test
    fun `output only uses the palette levels`() {
        val ramp = Gray(64, 4, IntArray(256) { (it % 64) * 4 })
        val out = dither(ramp, levels = 4)
        assertEquals(setOf(0, 85, 170, 255), out.pixels.toSet() intersect setOf(0, 85, 170, 255))
        assertTrue(out.pixels.all { it in setOf(0, 85, 170, 255) }, "got ${out.pixels.toSet()}")
    }

    @Test
    fun `mid gray becomes a pattern whose average is still mid gray`() {
        val out = dither(flat(32, 32, 128), levels = 4)
        val mean = out.pixels.average()
        assertTrue(mean in 110.0..146.0, "error diffusion must preserve the tone; mean was $mean")
        assertTrue(out.pixels.toSet().size >= 2, "a flat midtone must dither, not posterise")
    }

    @Test
    fun `a hard edge between black and white is preserved exactly`() {
        val w = 32; val h = 8
        val img = Gray(w, h, IntArray(w * h) { i -> if (i % w < w / 2) 0 else 255 })
        val out = dither(img, levels = 4)
        assertContentEquals(img.pixels, out.pixels, "shapes must stay recognisable")
    }

    @Test
    fun `dithering is deterministic`() {
        val img = Gray(24, 24, IntArray(576) { (it * 37) % 256 })
        assertContentEquals(dither(img, 4).pixels, dither(img, 4).pixels)
    }
}
