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

    /** What auto-exposure used on the latest frame; null when no frame comes (nothing is shown). */
    suspend fun exposureReading(): ExposureReading? = withTimeoutOrNull(READ_TIMEOUT_MS) {
        val result = settings.captureResultFlow.first()
        val iso = result[CaptureResult.SENSOR_SENSITIVITY]
        val exposureNs = result[CaptureResult.SENSOR_EXPOSURE_TIME]
        if (iso != null && exposureNs != null) ExposureReading(iso, exposureNs) else null
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
