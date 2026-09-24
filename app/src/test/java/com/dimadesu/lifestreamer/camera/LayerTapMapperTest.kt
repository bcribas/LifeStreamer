package com.dimadesu.lifestreamer.camera

import com.dimadesu.lifestreamer.camera.LayerTapMapper.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayerTapMapperTest {
    private val wide = 16f / 9
    private val main = Layer("main", 0f, 0f, 1f, 1f, z = 0)
    private val inset = Layer("pip", 0.6f, 0.6f, 0.95f, 0.95f, z = 1)

    @Test
    fun `the topmost visible layer under the finger wins`() {
        assertEquals("pip", LayerTapMapper.hit(listOf(main, inset), 0.8f, 0.8f, wide)!!.layerId)
        assertEquals("main", LayerTapMapper.hit(listOf(main, inset.copy(visible = false)), 0.8f, 0.8f, wide)!!.layerId)
        assertEquals("main", LayerTapMapper.hit(listOf(main, inset), 0.2f, 0.2f, wide)!!.layerId)
        assertNull(LayerTapMapper.hit(listOf(inset), 0.2f, 0.2f, wide))
    }

    @Test
    fun `a point on a full layer is the same point on its frame`() {
        val hit = LayerTapMapper.hit(listOf(main), 0.25f, 0.75f, wide)!!
        assertEquals(0.25f, hit.x, 1e-4f)
        assertEquals(0.75f, hit.y, 1e-4f)
    }

    @Test
    fun `filling crops the frame, so its edge is further in`() {
        // A 4:3 frame filling the whole 16:9 canvas: the sides show, the top and bottom are cut
        val hit = LayerTapMapper.hit(listOf(main), 0.5f, 0f, wide) { 4f / 3 }!!
        assertEquals(0.5f, hit.x, 1e-4f)
        assertEquals(0.125f, hit.y, 1e-4f)
    }

    @Test
    fun `a tap in the bars of a fitted layer falls through to the one below`() {
        // Half the canvas wide, all of it high: a 16:9 frame fitted in it has bars above and below
        val left = Layer("pip", 0f, 0f, 0.5f, 1f, z = 1, scaleMode = "FIT")
        assertEquals("main", LayerTapMapper.hit(listOf(main, left), 0.25f, 0.05f, wide)!!.layerId)
        val onPicture = LayerTapMapper.hit(listOf(main, left), 0.25f, 0.5f, wide)!!
        assertEquals("pip", onPicture.layerId)
        assertEquals(0.5f, onPicture.x, 1e-4f)
        assertEquals(0.5f, onPicture.y, 1e-4f)
    }

    @Test
    fun `mirroring flips across, and a half turn swaps both`() {
        val mirrored = LayerTapMapper.hit(listOf(main.copy(mirror = true)), 0.2f, 0.3f, wide)!!
        assertEquals(0.8f, mirrored.x, 1e-4f)
        assertEquals(0.3f, mirrored.y, 1e-4f)
        val turned = LayerTapMapper.hit(listOf(main.copy(rotation = 180)), 0.2f, 0.3f, wide)!!
        assertEquals(0.8f, turned.x, 1e-4f)
        assertEquals(0.7f, turned.y, 1e-4f)
    }
}
