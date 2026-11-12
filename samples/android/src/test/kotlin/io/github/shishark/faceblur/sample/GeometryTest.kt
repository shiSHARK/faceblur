// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeometryTest {
    private val delta = 0.001f

    @Test
    fun `a wide image in a square view is letterboxed at the top and bottom`() {
        val fit = ImageFit(imageWidth = 400, imageHeight = 200, viewWidth = 200, viewHeight = 200)
        assertEquals(0.5f, fit.scale, delta)
        assertEquals(0f, fit.offsetX, delta)
        assertEquals(50f, fit.offsetY, delta)
        val v = fit.toView(Box(100f, 50f, 300f, 150f))
        assertEquals(Box(50f, 75f, 150f, 125f), v)
    }

    @Test
    fun `an upright portrait image keeps its place for an image whose stored frame was rotated`() {
        // The library reports the upright frame (EXIF orientation applied), so a 300 x 400 portrait is described by
        // width 300 and height 400 and the overlay needs no rotation of its own.
        val fit = ImageFit(imageWidth = 300, imageHeight = 400, viewWidth = 600, viewHeight = 400)
        assertEquals(1f, fit.scale, delta)
        assertEquals(150f, fit.offsetX, delta)
        assertEquals(0f, fit.offsetY, delta)
        val v = fit.toView(Box(0f, 0f, 300f, 400f))
        assertEquals(Box(150f, 0f, 450f, 400f), v)
    }

    @Test
    fun `a view without size maps to scale zero instead of failing`() {
        val fit = ImageFit(100, 100, 0, 0)
        assertEquals(0f, fit.scale, delta)
    }

    @Test
    fun `an image without size is refused`() {
        assertFailsWith<IllegalArgumentException> { ImageFit(0, 10, 10, 10) }
    }

    @Test
    fun `a box reports its size and centre`() {
        val b = Box(10f, 20f, 30f, 60f)
        assertEquals(20f, b.width, delta)
        assertEquals(40f, b.height, delta)
        assertEquals(20f, b.centerX, delta)
        assertEquals(40f, b.centerY, delta)
    }

    @Test
    fun `the starting box is a square in the middle`() {
        val box = Placement.initial(1_000, 500)
        assertEquals(100f, box.width, delta)
        assertEquals(100f, box.height, delta)
        assertEquals(500f, box.centerX, delta)
        assertEquals(250f, box.centerY, delta)
    }

    @Test
    fun `moving steps by two percent of the long side and stops at the image edge`() {
        val start = Placement.initial(1_000, 500)
        val right = Placement.move(start, 1, 0, 1_000, 500)
        assertEquals(start.left + 20f, right.left, delta)
        assertEquals(start.top, right.top, delta)
        val far = Placement.move(start, 1_000, -1_000, 1_000, 500)
        assertEquals(1_000f, far.right, delta)
        assertEquals(0f, far.top, delta)
        assertEquals(start.width, far.width, delta)
    }

    @Test
    fun `resizing scales around the centre and stays inside the image`() {
        val start = Placement.initial(1_000, 500)
        val larger = Placement.resize(start, Placement.SIZE_FACTOR, 1_000, 500)
        assertEquals(start.centerX, larger.centerX, delta)
        assertTrue(larger.width > start.width)
        val huge = Placement.resize(start, 100f, 1_000, 500)
        assertEquals(1_000f, huge.width, delta)
        assertEquals(500f, huge.height, delta)
        val tiny = Placement.resize(start, 0.0001f, 1_000, 500)
        assertEquals(20f, tiny.width, delta)
    }

    @Test
    fun `a box at the edge stays inside the image when it grows`() {
        val corner = Box(0f, 0f, 100f, 100f)
        val larger = Placement.resize(corner, 2f, 1_000, 500)
        assertEquals(0f, larger.left, delta)
        assertEquals(0f, larger.top, delta)
        assertEquals(200f, larger.width, delta)
    }
}
