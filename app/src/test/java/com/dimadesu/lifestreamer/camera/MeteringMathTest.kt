package com.dimadesu.lifestreamer.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringMathTest {
    private val back = Camera2Caps(sensorOrientation = 90, isFront = false, activeArrayWidth = 4000, activeArrayHeight = 3000)

    @Test
    fun `in landscape the back camera's frame is the sensor, cropped to 16 by 9`() {
        // Display turned 90: the back sensor (mounted at 90) is upright
        assertEquals(0, MeteringMath.relativeRotation(90, 90, isFront = false))
        val region = MeteringMath.regionFor(TapFocus(0.5f, 0.5f, displayRotation = 90), back, zoom = 1f)!!
        assertEquals(2000, (region.left + region.right) / 2)
        assertEquals(1500, (region.top + region.bottom) / 2)
        // The top of a 16:9 frame is an eighth of the way down a 4:3 sensor
        val top = MeteringMath.toArray(MeteringMath.Point(0.5f, 0f), 16f / 9, 4000, 3000)
        assertEquals(375f, top.y, 0.5f)
    }

    @Test
    fun `in portrait the frame is turned against the sensor`() {
        assertEquals(90, MeteringMath.relativeRotation(90, 0, isFront = false))
        // The top-left of the upright frame is the sensor's bottom-left
        val p = MeteringMath.frameToSensor(0f, 0f, 90, isFront = false)
        assertEquals(0f, p.x, 1e-4f)
        assertEquals(1f, p.y, 1e-4f)
    }

    @Test
    fun `a front camera is a mirror image`() {
        val p = MeteringMath.frameToSensor(0.2f, 0.5f, 0, isFront = true)
        assertEquals(0.8f, p.x, 1e-4f)
    }

    @Test
    fun `the region always stays inside the array, the right way round`() {
        listOf(0f to 0f, 1f to 1f, 0f to 1f, 0.5f to 0.5f).forEach { (x, y) ->
            val r = MeteringMath.regionFor(TapFocus(x, y, displayRotation = 90), back, zoom = 1f)!!
            assertTrue("$r", r.left in 0 until r.right && r.right <= 4000)
            assertTrue("$r", r.top in 0 until r.bottom && r.bottom <= 3000)
        }
    }
}
