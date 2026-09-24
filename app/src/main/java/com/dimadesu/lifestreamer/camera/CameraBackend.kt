package com.dimadesu.lifestreamer.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.util.Log
import android.util.Range
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One camera the controls act on, whatever its kind. */
sealed interface CameraBackend {
    val kind: CameraKind

    /** Which physical camera: what its remembered values are keyed by. */
    val cameraKey: String

    /** The source it drives; a new source (a camera swapped in) gets a new backend. */
    val source: Any

    /**
     * Whether the camera is open. Nothing is written before: writing a setting to a closed
     * StreamPack camera opens it, which with the preview off would turn a camera on from a slider.
     */
    val isActiveFlow: StateFlow<Boolean>
}

/**
 * A phone camera, through its StreamPack [ICameraSource]. Every setting lives in that source's
 * request builder: it survives session restarts (a preview resize) but not a new source, which
 * is why the manager re-applies on each activation.
 */
@SuppressLint("MissingPermission") // The camera is open, so the permission was granted
class Camera2Backend(val cameraSource: ICameraSource) : CameraBackend {
    override val kind = CameraKind.CAMERA2
    val cameraId: String = cameraSource.cameraId
    override val cameraKey = CameraControlStore.camera2Key(cameraId)
    override val source: Any get() = cameraSource
    private val settings get() = cameraSource.settings
    override val isActiveFlow: StateFlow<Boolean> get() = settings.isActiveFlow

    /** Read from the characteristics, which need no open camera. */
    val caps: Camera2Caps by lazy { readCaps(settings.characteristics, settings.zoom.availableRatioRange) }

    /** The tap whose focus was last triggered: a new one triggers again, a held one does not. */
    private var triggeredTap: TapFocus? = null

    /**
     * What auto-exposure used on the latest frame; null when no frame comes (nothing is shown).
     * Its ISO includes the digital boost it adds after the sensor, which manual exposure leaves
     * off: without it, switching to manual at "the same" ISO turns the picture darker.
     */
    suspend fun exposureReading(): ExposureReading? = withTimeoutOrNull(READ_TIMEOUT_MS) {
        val result = settings.captureResultFlow.first()
        val iso = result[CaptureResult.SENSOR_SENSITIVITY]
        val exposureNs = result[CaptureResult.SENSOR_EXPOSURE_TIME]
        val boost = result[CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST] ?: 100
        if (iso != null && exposureNs != null) ExposureReading(iso * boost / 100, exposureNs) else null
    }

    /**
     * Writes the whole [plan] and applies it at once. With [settle], the exposure and white-balance
     * locks wait until auto-exposure and auto white balance have converged: a lock taken on the
     * first frames of a camera that just opened would hold its dark, blue start.
     */
    suspend fun apply(plan: Camera2Plan, settle: Boolean) {
        if (!isActiveFlow.value) return
        val locks = plan.aeLock || plan.awbLock
        write(plan, locks = !(settle && locks))
        if (settle && locks) {
            waitForConvergence()
            if (isActiveFlow.value) write(plan, locks = true)
        }
        if (!isActiveFlow.value) return
        if (plan.tap != null && plan.tap != triggeredTap && plan.afMode == CameraMetadata.CONTROL_AF_MODE_AUTO) {
            triggerFocus()
        }
        triggeredTap = plan.tap
        // Through StreamPack's zoom, so its own idea of the ratio (used before API 30) follows.
        // It waits for a capture result there, which never comes without frames: bounded.
        withTimeoutOrNull(ZOOM_TIMEOUT_MS) { settings.zoom.setZoomRatio(plan.zoom) }
            ?: Log.d(TAG, "Zoom on camera $cameraId not confirmed (no frames?)")
    }

    private suspend fun write(plan: Camera2Plan, locks: Boolean) {
        val manual = plan.aeMode == CameraMetadata.CONTROL_AE_MODE_OFF
        settings.set(CaptureRequest.CONTROL_AE_MODE, plan.aeMode)
        if (manual) {
            plan.iso?.let { settings.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            plan.exposureNs?.let { settings.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            plan.frameDurationNs?.let { settings.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
        } else {
            settings.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, plan.evIndex)
        }
        if (caps.aeLockAvailable) settings.set(CaptureRequest.CONTROL_AE_LOCK, plan.aeLock && locks)
        settings.set(CaptureRequest.CONTROL_AF_MODE, plan.afMode)
        settings.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        writeRegions(plan, manual)
        plan.lensDiopters?.let { settings.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        settings.set(CaptureRequest.CONTROL_AWB_MODE, plan.awbMode)
        if (caps.awbLockAvailable) settings.set(CaptureRequest.CONTROL_AWB_LOCK, plan.awbLock && locks)
        if (caps.torch) {
            settings.set(
                CaptureRequest.FLASH_MODE,
                if (plan.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
            )
        }
        // Optical stabilization when there is one; video stabilization otherwise, alone only
        if (caps.ois) {
            settings.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (plan.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        if (caps.eis) {
            settings.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (plan.eis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
        settings.applyRepeatingSession()
    }

    /**
     * Focus and exposure on the tapped point, or on the whole picture again. Exposure only while
     * it is automatic and unlocked, focus only while it is not set by hand.
     */
    private suspend fun writeRegions(plan: Camera2Plan, manual: Boolean) {
        // Before Android 11 the zoom crops the sensor itself, and the regions must follow it
        val zoom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) 1f else plan.zoom
        val region = plan.tap?.let { MeteringMath.regionFor(it, caps, zoom) }
            ?.let {
                arrayOf(
                    android.hardware.camera2.params.MeteringRectangle(
                        android.graphics.Rect(it.left, it.top, it.right, it.bottom),
                        android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
                    )
                )
            }
        if (caps.maxRegionsAf > 0) {
            settings.set(
                CaptureRequest.CONTROL_AF_REGIONS,
                region.takeIf { plan.afMode != CameraMetadata.CONTROL_AF_MODE_OFF }
            )
        }
        if (caps.maxRegionsAe > 0) {
            settings.set(CaptureRequest.CONTROL_AE_REGIONS, region.takeIf { !manual && !plan.aeLock })
        }
    }

    /**
     * Starts one focus on the regions just written. The trigger goes out with one frame; left in
     * the repeating request it would restart the focus on every frame.
     */
    private suspend fun triggerFocus() {
        Log.i(TAG, "Camera $cameraId: focusing on ${settings.get(CaptureRequest.CONTROL_AF_REGIONS)?.firstOrNull()}")
        settings.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        withTimeoutOrNull(TRIGGER_TIMEOUT_MS) { settings.applyRepeatingSessionSync() }
        settings.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        settings.applyRepeatingSession()
    }

    private suspend fun waitForConvergence() {
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            settings.captureResultFlow.first { result ->
                val ae = result[CaptureResult.CONTROL_AE_STATE]
                val awb = result[CaptureResult.CONTROL_AWB_STATE]
                (ae == null || ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                        ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED) &&
                        (awb == null || awb == CameraMetadata.CONTROL_AWB_STATE_CONVERGED)
            }
        } ?: Log.d(TAG, "Camera $cameraId did not settle before locking")
    }

    companion object {
        private const val TAG = "CameraControls"
        private const val READ_TIMEOUT_MS = 500L
        private const val ZOOM_TIMEOUT_MS = 1_000L
        private const val SETTLE_TIMEOUT_MS = 1_500L
        private const val TRIGGER_TIMEOUT_MS = 1_000L

        fun readCaps(c: CameraCharacteristics, zoomRange: Range<Float>): Camera2Caps {
            val capabilities = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toList().orEmpty()
            val aeModes = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.toList().orEmpty()
            val evRange = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
            val evStep = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]
            val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
            val exposureRange = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
            val activeArray = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
            return Camera2Caps(
                zoomMin = zoomRange.lower,
                zoomMax = zoomRange.upper,
                evMin = evRange?.lower ?: 0,
                evMax = evRange?.upper ?: 0,
                evStep = evStep?.toFloat() ?: 1f,
                aeLockAvailable = c[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true,
                awbLockAvailable = c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true,
                awbModes = c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.toList().orEmpty(),
                afModes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.toList().orEmpty(),
                minFocusDiopters = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f,
                manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities &&
                        CameraMetadata.CONTROL_AE_MODE_OFF in aeModes &&
                        isoRange != null && exposureRange != null,
                isoMin = isoRange?.lower ?: 100,
                isoMax = isoRange?.upper ?: 100,
                exposureMinNs = exposureRange?.lower ?: 0,
                exposureMaxNs = exposureRange?.upper ?: 0,
                torch = c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true,
                ois = c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                eis = c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]
                    ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true,
                sensorOrientation = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
                isFront = c[CameraCharacteristics.LENS_FACING] == CameraMetadata.LENS_FACING_FRONT,
                activeArrayWidth = activeArray?.width() ?: 0,
                activeArrayHeight = activeArray?.height() ?: 0,
                maxRegionsAf = c[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0,
                maxRegionsAe = c[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0,
            )
        }
    }
}

/**
 * A USB camera, through its UVC controls. They exist only while the camera is open, and the
 * camera only while the app's screen is (the helper lives there): the target goes inactive when
 * it closes. What it can do is read when it opens.
 */
class UvcBackend(val uvcSource: com.dimadesu.lifestreamer.uvc.UvcVideoSource) : CameraBackend {
    override val kind = CameraKind.UVC

    /** The camera model, known once it opens; the same model shares its settings. */
    override val cameraKey: String get() = CameraControlStore.uvcKey(uvcSource.deviceKey ?: "unknown")
    override val source: Any get() = uvcSource
    override val isActiveFlow: StateFlow<Boolean> get() = uvcSource.readyFlow

    /** Read when the camera opens; null until then. */
    @Volatile
    var caps: UvcCaps? = null

    suspend fun readCaps(): UvcCaps? = uvcSource.withControl { c ->
        val controls = mutableMapOf<String, UvcLimits>()
        fun range(key: String, enabled: Boolean, limits: () -> IntArray?) {
            if (!enabled) return
            val l = limits() ?: return
            if (l.size >= 3 && l[1] > l[0]) controls[key] = UvcLimits(l[0], l[1], l[2].coerceIn(l[0], l[1]))
        }
        if (c.isFocusAutoEnable) controls[UvcKeys.FOCUS_AUTO] = UvcLimits(0, 1, 1)
        if (c.isWhiteBalanceAutoEnable) controls[UvcKeys.WB_AUTO] = UvcLimits(0, 1, 1)
        if (c.isAutoExposureModeEnable) controls[UvcKeys.AE_AUTO] = UvcLimits(0, 1, 1)
        range(UvcKeys.ZOOM, c.isZoomAbsoluteEnable) { c.updateZoomAbsoluteLimit() }
        range(UvcKeys.FOCUS, c.isFocusAbsoluteEnable) { c.updateFocusAbsoluteLimit() }
        range(UvcKeys.WB, c.isWhiteBalanceEnable) { c.updateWhiteBalanceLimit() }
        range(UvcKeys.EXPOSURE, c.isExposureTimeAbsoluteEnable) { c.updateExposureTimeAbsoluteLimit() }
        range(UvcKeys.BRIGHTNESS, c.isBrightnessEnable) { c.updateBrightnessLimit() }
        range(UvcKeys.CONTRAST, c.isContrastEnable) { c.updateContrastLimit() }
        range(UvcKeys.SATURATION, c.isSaturationEnable) { c.updateSaturationLimit() }
        range(UvcKeys.SHARPNESS, c.isSharpnessEnable) { c.updateSharpnessLimit() }
        range(UvcKeys.GAMMA, c.isGammaEnable) { c.updateGammaLimit() }
        range(UvcKeys.GAIN, c.isGainEnable) { c.updateGainLimit() }
        range(UvcKeys.BACKLIGHT, c.isBacklightCompEnable) { c.updateBacklightCompLimit() }
        range(UvcKeys.HUE, c.isHueEnable) { c.updateHueLimit() }
        if (c.isPowerlineFrequencyEnable) {
            c.updatePowerlineFrequencyLimit()?.takeIf { it.size >= 3 }?.let {
                controls[UvcKeys.POWERLINE] = UvcLimits(it[0], it[1], it[2].coerceIn(it[0], it[1]))
            }
        }
        UvcCaps(controls)
    }

    /**
     * Writes what was chosen: the automatic switches first (a manual value is ignored while its
     * automatic is on), then the values. Nothing chosen is left as the camera has it.
     */
    suspend fun apply(values: CameraControlValues) {
        val uvc = values.uvc
        if (uvc.isEmpty()) return
        uvcSource.withControl { c ->
            uvc[UvcKeys.FOCUS_AUTO]?.let { c.setFocusAuto(it != 0) }
            uvc[UvcKeys.WB_AUTO]?.let { c.setWhiteBalanceAuto(it != 0) }
            uvc[UvcKeys.AE_AUTO]?.let { c.setExposureTimeAuto(it != 0) }
            uvc[UvcKeys.POWERLINE]?.let { c.setPowerlineFrequency(it) }
            uvc[UvcKeys.FOCUS]?.let { c.setFocusAbsolute(it) }
            uvc[UvcKeys.WB]?.let { c.setWhiteBalance(it) }
            uvc[UvcKeys.EXPOSURE]?.let { c.setExposureTimeAbsolute(it) }
            uvc[UvcKeys.ZOOM]?.let { c.setZoomAbsolute(it) }
            uvc[UvcKeys.BRIGHTNESS]?.let { c.setBrightness(it) }
            uvc[UvcKeys.CONTRAST]?.let { c.setContrast(it) }
            uvc[UvcKeys.SATURATION]?.let { c.setSaturation(it) }
            uvc[UvcKeys.SHARPNESS]?.let { c.setSharpness(it) }
            uvc[UvcKeys.GAMMA]?.let { c.setGamma(it) }
            uvc[UvcKeys.GAIN]?.let { c.setGain(it) }
            uvc[UvcKeys.BACKLIGHT]?.let { c.setBacklightComp(it) }
            uvc[UvcKeys.HUE]?.let { c.setHue(it) }
        }
    }

    /** Back to the camera's own defaults, for what it has. */
    suspend fun reset() {
        val has = caps?.controls?.keys ?: return
        uvcSource.withControl { c ->
            if (UvcKeys.FOCUS_AUTO in has) c.resetFocusAuto()
            if (UvcKeys.WB_AUTO in has) c.resetWhiteBalanceAuto()
            if (UvcKeys.AE_AUTO in has) c.resetAutoExposureMode()
            if (UvcKeys.POWERLINE in has) c.resetPowerlineFrequency()
            if (UvcKeys.FOCUS in has) c.resetFocusAbsolute()
            if (UvcKeys.WB in has) c.resetWhiteBalance()
            if (UvcKeys.EXPOSURE in has) c.resetExposureTimeAbsolute()
            if (UvcKeys.ZOOM in has) c.resetZoomAbsolute()
            if (UvcKeys.BRIGHTNESS in has) c.resetBrightness()
            if (UvcKeys.CONTRAST in has) c.resetContrast()
            if (UvcKeys.SATURATION in has) c.resetSaturation()
            if (UvcKeys.SHARPNESS in has) c.resetSharpness()
            if (UvcKeys.GAMMA in has) c.resetGamma()
            if (UvcKeys.GAIN in has) c.resetGain()
            if (UvcKeys.BACKLIGHT in has) c.resetBacklightComp()
            if (UvcKeys.HUE in has) c.resetHue()
        }
    }
}
