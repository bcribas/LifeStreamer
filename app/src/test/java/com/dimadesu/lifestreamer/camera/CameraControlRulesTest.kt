package com.dimadesu.lifestreamer.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlRulesTest {
    private val caps = Camera2Caps(
        zoomMin = 0.6f, zoomMax = 10f,
        evMin = -12, evMax = 12, evStep = 1f / 6,
        aeLockAvailable = true, awbLockAvailable = true,
        awbModes = listOf(
            CameraMetadata.CONTROL_AWB_MODE_OFF, CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        ),
        afModes = listOf(
            CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        ),
        minFocusDiopters = 10f,
        manualSensor = true, isoMin = 50, isoMax = 3200,
        exposureMinNs = 10_000, exposureMaxNs = 500_000_000,
        torch = true, ois = false, eis = true,
    )
    private val auto = CameraControlValues()

    private fun change(values: CameraControlValues, key: String, raw: Any?, fps: Int = 30) =
        CameraControlRules.applyChange(values, key, raw, caps, fps).getOrThrow()

    @Test
    fun `setting the ISO turns manual exposure on, with a shutter of at most one frame`() {
        val values = change(auto, ControlKeys.ISO, 800)
        assertEquals(true, values.manualExposure)
        assertEquals(800, values.iso)
        assertTrue(values.exposureNs!! <= CameraControlRules.frameDurationNs(30))
        val plan = CameraControlRules.resolveCamera2(values, RuntimeControls(), caps, 30, concurrent = false)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_OFF, plan.aeMode)
        assertEquals(CameraControlRules.frameDurationNs(30), plan.frameDurationNs)
    }

    @Test
    fun `manual exposure starts from what auto-exposure was using`() {
        val values = CameraControlRules.applyChange(
            auto, ControlKeys.EXPOSURE_MODE, "manual", caps, 30, ExposureReading(iso = 610, exposureNs = 16_000_000)
        ).getOrThrow()
        assertEquals(640, values.iso)
        assertEquals(1_000_000_000L / 60, values.exposureNs)
    }

    @Test
    fun `switching to manual again starts from the current auto exposure, not a stale one`() {
        val stale = change(auto, ControlKeys.ISO, 100).copy(manualExposure = null)
        val values = CameraControlRules.applyChange(
            stale, ControlKeys.EXPOSURE_MODE, "manual", caps, 30, ExposureReading(iso = 1600, exposureNs = 33_000_000)
        ).getOrThrow()
        assertEquals(1600, values.iso)
        // Without a reading, what was remembered
        assertEquals(100, change(stale, ControlKeys.EXPOSURE_MODE, "manual").iso)
    }

    @Test
    fun `a faster live shortens the shutter`() {
        val values = change(auto, ControlKeys.SHUTTER, 1_000_000_000L / 30)
        assertEquals(1_000_000_000L / 30, values.exposureNs)
        val at60 = CameraControlRules.sanitize(values, caps, 60)
        assertTrue(at60.exposureNs!! <= CameraControlRules.frameDurationNs(60))
        assertTrue(CameraControlRules.shutterChoices(caps, 60).all { it <= CameraControlRules.frameDurationNs(60) })
    }

    @Test
    fun `compensation and the exposure lock wait while exposure is manual`() {
        val manual = change(auto, ControlKeys.ISO, 400)
        assertTrue(CameraControlRules.applyChange(manual, ControlKeys.EV, 3, caps, 30).isFailure)
        val controls = CameraControlRules.describeCamera2(caps, manual, RuntimeControls(), 30).associateBy { it.key }
        assertFalse(controls.getValue(ControlKeys.EV).enabled)
        assertFalse(controls.getValue(ControlKeys.AE_LOCK).enabled)
        // Back on auto they apply again, the ISO still remembered
        val back = change(manual, ControlKeys.EXPOSURE_MODE, "auto")
        assertNull(back.manualExposure)
        assertEquals(400, back.iso)
        assertEquals(3, change(back, ControlKeys.EV, 3).evIndex)
    }

    @Test
    fun `a white balance preset releases the lock, which only works on auto`() {
        val locked = change(auto, ControlKeys.AWB_LOCK, true)
        assertEquals(true, locked.awbLock)
        val daylight = change(locked, ControlKeys.AWB_MODE, "daylight")
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, daylight.awbMode)
        assertNull(daylight.awbLock)
        assertTrue(CameraControlRules.applyChange(daylight, ControlKeys.AWB_LOCK, true, caps, 30).isFailure)
        // OFF is never offered: there are no colour gains to set
        assertTrue(CameraControlRules.applyChange(auto, ControlKeys.AWB_MODE, "0", caps, 30).isFailure)
    }

    @Test
    fun `setting the distance focuses by hand, and a fixed lens has no focus controls`() {
        val values = change(auto, ControlKeys.LENS, 0.5)
        assertEquals(CameraMetadata.CONTROL_AF_MODE_OFF, values.afMode)
        val plan = CameraControlRules.resolveCamera2(values, RuntimeControls(), caps, 30, false)
        assertEquals(0.5f, plan.lensDiopters)

        val fixed = caps.copy(minFocusDiopters = 0f, afModes = listOf(CameraMetadata.CONTROL_AF_MODE_OFF))
        val keys = CameraControlRules.describeCamera2(fixed, auto, RuntimeControls(), 30).map { it.key }
        assertFalse(ControlKeys.AF_MODE in keys)
        assertFalse(ControlKeys.LENS in keys)
    }

    @Test
    fun `back to automatic is the camera's own defaults`() {
        val plan = CameraControlRules.resolveCamera2(auto, RuntimeControls(), caps, 30, false)
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, plan.aeMode)
        assertEquals(0, plan.evIndex)
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan.afMode)
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertEquals(1f, plan.zoom)
        assertNull(plan.lensDiopters)
        assertFalse(plan.torch)
    }

    @Test
    fun `values another camera cannot use are dropped`() {
        val remembered = auto.copy(manualExposure = true, iso = 800, exposureNs = 10_000_000, zoom = 20f, evIndex = 30)
        val basic = caps.copy(manualSensor = false, zoomMax = 5f, evMax = 4)
        val sanitized = CameraControlRules.sanitize(remembered, basic, 30)
        assertNull(sanitized.manualExposure)
        assertNull(sanitized.iso)
        assertEquals(5f, sanitized.zoom)
        assertEquals(4, sanitized.evIndex)
    }

    @Test
    fun `page values are parsed, and nonsense is refused`() {
        assertEquals(2.5f, change(auto, ControlKeys.ZOOM, "2.5").zoom)
        assertEquals(10f, change(auto, ControlKeys.ZOOM, 99.0).zoom)
        assertEquals(true, change(auto, ControlKeys.AE_LOCK, "true").aeLock)
        assertTrue(CameraControlRules.applyChange(auto, ControlKeys.ZOOM, "wide", caps, 30).isFailure)
        assertTrue(CameraControlRules.applyChange(auto, ControlKeys.AE_LOCK, 3.2, caps, 30).isSuccess)
        assertTrue(CameraControlRules.applyChange(auto, "exposure", 1, caps, 30).isFailure)
        assertTrue(CameraControlRules.applyChange(auto, ControlKeys.AF_MODE, "sideways", caps, 30).isFailure)
    }

    @Test
    fun `video stabilization is left out when another camera runs alongside`() {
        assertTrue(CameraControlRules.resolveCamera2(auto, RuntimeControls(), caps, 30, concurrent = false).eis)
        assertFalse(CameraControlRules.resolveCamera2(auto, RuntimeControls(), caps, 30, concurrent = true).eis)
        val withOis = caps.copy(ois = true)
        val plan = CameraControlRules.resolveCamera2(auto, RuntimeControls(), withOis, 30, concurrent = true)
        assertTrue(plan.ois)
        assertFalse(plan.eis)
    }

    @Test
    fun `a tap focuses once and holds, but never overrides manual focus`() {
        val tap = RuntimeControls(tapFocus = TapFocus(0.3f, 0.6f))
        assertEquals(
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraControlRules.resolveCamera2(auto, tap, caps, 30, false).afMode
        )
        val manualFocus = change(auto, ControlKeys.LENS, 2)
        assertEquals(
            CameraMetadata.CONTROL_AF_MODE_OFF,
            CameraControlRules.resolveCamera2(manualFocus, tap, caps, 30, false).afMode
        )
    }

    @Test
    fun `the quick buttons cycle through the offered modes`() {
        val next = CameraControlRules.cycle(auto, ControlKeys.AWB_MODE, caps, 30).getOrThrow()
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, next.awbMode)
        val afNext = CameraControlRules.cycle(auto, ControlKeys.AF_MODE, caps, 30).getOrThrow()
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, afNext.afMode)
        assertEquals(1.25f, CameraControlRules.nudgeZoom(auto, 1.25f, caps, 30).getOrThrow().zoom)
    }

    @Test
    fun `choices come from the camera's range`() {
        assertEquals(50, CameraControlRules.isoChoices(caps).first())
        assertEquals(3200, CameraControlRules.isoChoices(caps).last())
        assertEquals("1/60", CameraControlRules.shutterLabel(1_000_000_000L / 60))
        assertEquals(1_000_000_000L / 30, CameraControlRules.shutterChoices(caps, 30).first())
    }

    @Test
    fun `usb controls clamp, and a manual value takes its control off automatic`() {
        val uvcCaps = UvcCaps(
            mapOf(
                UvcKeys.FOCUS_AUTO to UvcLimits(0, 1, 1),
                UvcKeys.FOCUS to UvcLimits(0, 250, 0),
                UvcKeys.BRIGHTNESS to UvcLimits(-64, 64, 0),
            )
        )
        val focused = CameraControlRules.applyUvcChange(auto, UvcKeys.FOCUS, 400, uvcCaps).getOrThrow()
        assertEquals(250, focused.uvc[UvcKeys.FOCUS])
        assertEquals(0, focused.uvc[UvcKeys.FOCUS_AUTO])
        assertTrue(CameraControlRules.applyUvcChange(auto, UvcKeys.GAMMA, 1, uvcCaps).isFailure)
        val controls = CameraControlRules.describeUvc(uvcCaps, auto).associateBy { it.key }
        assertEquals(true, controls.getValue(UvcKeys.FOCUS_AUTO).value)
        assertFalse(controls.getValue(UvcKeys.FOCUS).enabled)
        assertTrue(ControlKeys.RESET in controls)
    }
}
