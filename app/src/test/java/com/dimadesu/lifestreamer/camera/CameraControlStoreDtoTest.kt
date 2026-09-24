package com.dimadesu.lifestreamer.camera

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraControlStoreDtoTest {
    private val gson = Gson()

    @Test
    fun `values survive the round trip through json`() {
        val values = CameraControlValues(
            zoom = 2.5f, evIndex = -3, aeLock = true, manualExposure = true, iso = 800,
            exposureNs = 16_666_666, afMode = 0, lensDiopters = 0.2f, awbMode = 5, awbLock = null,
            uvc = mapOf(UvcKeys.BRIGHTNESS to 12),
        )
        val json = gson.toJson(CameraControlStore.toDto(values))
        assertEquals(values, CameraControlStore.fromJson(gson, json))
    }

    @Test
    fun `a newer or broken value falls back to automatic`() {
        assertNull(CameraControlStore.fromJson(gson, """{"v":99,"zoom":2.0}"""))
        assertNull(CameraControlStore.fromJson(gson, "not json"))
        assertEquals(CameraControlValues(), CameraControlStore.fromJson(gson, "{}"))
    }
}
