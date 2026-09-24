package com.dimadesu.lifestreamer.camera

import android.hardware.camera2.CameraMetadata

/**
 * The per-camera controls: what a camera can do, what the operator chose for it, and the generic
 * description both the phone's panel and the remote page are built from.
 *
 * Plain values only (no Android objects), so the rules run in JVM tests. The CaptureRequest
 * constants used here are compile-time ints.
 */

enum class CameraKind { CAMERA2, UVC }

/** What a phone camera can do, read from its characteristics without opening it. */
data class Camera2Caps(
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val evMin: Int = 0,
    val evMax: Int = 0,
    /** One exposure compensation index, in EV (1/3, 1/6, 1/2...). */
    val evStep: Float = 1f,
    val aeLockAvailable: Boolean = false,
    val awbLockAvailable: Boolean = false,
    val awbModes: List<Int> = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    val afModes: List<Int> = emptyList(),
    /** The closest focus, in diopters; 0 for a fixed-focus lens. */
    val minFocusDiopters: Float = 0f,
    /** Sensitivity and exposure time can be set by hand (MANUAL_SENSOR, and AE can be off). */
    val manualSensor: Boolean = false,
    val isoMin: Int = 100,
    val isoMax: Int = 100,
    val exposureMinNs: Long = 0,
    val exposureMaxNs: Long = 0,
    val torch: Boolean = false,
    val ois: Boolean = false,
    val eis: Boolean = false,
    val sensorOrientation: Int = 0,
    val isFront: Boolean = false,
    val activeArrayWidth: Int = 0,
    val activeArrayHeight: Int = 0,
    val maxRegionsAf: Int = 0,
    val maxRegionsAe: Int = 0,
)

/** A UVC control's range as the camera reports it. */
data class UvcLimits(val min: Int, val max: Int, val default: Int)

/**
 * What a USB camera can do: each control it has, by key (see [UvcKeys]), with its range. The
 * automatic switches are 0..1.
 */
data class UvcCaps(val controls: Map<String, UvcLimits> = emptyMap())

object UvcKeys {
    const val ZOOM = "uvc.zoom"
    const val FOCUS_AUTO = "uvc.focusAuto"
    const val FOCUS = "uvc.focus"
    const val WB_AUTO = "uvc.wbAuto"
    const val WB = "uvc.wb"
    const val AE_AUTO = "uvc.aeAuto"
    const val EXPOSURE = "uvc.exposure"
    const val BRIGHTNESS = "uvc.brightness"
    const val CONTRAST = "uvc.contrast"
    const val SATURATION = "uvc.saturation"
    const val SHARPNESS = "uvc.sharpness"
    const val GAMMA = "uvc.gamma"
    const val GAIN = "uvc.gain"
    const val BACKLIGHT = "uvc.backlight"
    const val HUE = "uvc.hue"
    const val POWERLINE = "uvc.powerline"

    /** The automatic switches, and the manual control each one takes over. */
    val AUTO_OF = mapOf(FOCUS to FOCUS_AUTO, WB to WB_AUTO, EXPOSURE to AE_AUTO)
    val AUTOS = AUTO_OF.values.toSet()
}

/**
 * What the operator chose for one camera, remembered across restarts. Null means automatic, or
 * never touched: nothing is written for it.
 */
data class CameraControlValues(
    val zoom: Float? = null,
    /** Exposure compensation, in the camera's index steps (see [Camera2Caps.evStep]). */
    val evIndex: Int? = null,
    val aeLock: Boolean? = null,
    val manualExposure: Boolean? = null,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    /** A CONTROL_AF_MODE_* value. */
    val afMode: Int? = null,
    val lensDiopters: Float? = null,
    /** A CONTROL_AWB_MODE_* value; never OFF. */
    val awbMode: Int? = null,
    val awbLock: Boolean? = null,
    /** A USB camera's controls, by [UvcKeys] key. */
    val uvc: Map<String, Int> = emptyMap(),
) {
    val isAutomatic: Boolean get() = this == CameraControlValues()
}

/** A point the operator tapped, normalized to the camera's frame as delivered (0..1, y down). */
data class TapFocus(val x: Float, val y: Float)

/** What is never remembered: the torch, and a focus held on a tapped point. */
data class RuntimeControls(val torch: Boolean = false, val tapFocus: TapFocus? = null)

/** What auto-exposure is doing right now, to start manual exposure from it. */
data class ExposureReading(val iso: Int, val exposureNs: Long)

enum class ControlType { CHOICE, TOGGLE, RANGE, ACTION }

data class ControlOption(val value: String, val label: String)

object ControlKeys {
    const val ZOOM = "zoom"
    const val EV = "ev"
    const val AE_LOCK = "aeLock"
    const val EXPOSURE_MODE = "exposureMode"
    const val ISO = "iso"
    const val SHUTTER = "shutter"
    const val AF_MODE = "afMode"
    const val LENS = "lens"
    const val FOCUS_RESUME = "focusResume"
    const val AWB_MODE = "awbMode"
    const val AWB_LOCK = "awbLock"
    const val TORCH = "torch"
    const val RESET = "reset"
}

object ControlGroup {
    const val ZOOM = "zoom"
    const val EXPOSURE = "exposure"
    const val FOCUS = "focus"
    const val WHITE_BALANCE = "wb"
    const val IMAGE = "image"
    const val LIGHT = "light"
    const val GENERAL = "general"
}

/** How a RANGE value reads: [PLAIN] is value × scale + unit. */
object ControlFormat {
    const val PLAIN = "plain"
    /** "2.5×" */
    const val ZOOM = "zoom"
    /** value × scale as "+0.7 EV" */
    const val EV = "ev"
    /** Diopters as a distance: 0 is "∞", 2 is "0.5 m" */
    const val DISTANCE = "distance"
}

/**
 * One control, described for a screen or a page to draw: a CHOICE has [options] and a string
 * value, a TOGGLE a boolean, a RANGE a number between [min] and [max], an ACTION no value.
 */
data class ControlDescriptor(
    val key: String,
    val label: String,
    val group: String,
    val type: ControlType,
    val value: Any? = null,
    val options: List<ControlOption>? = null,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val format: String = ControlFormat.PLAIN,
    val scale: Float = 1f,
    val unit: String? = null,
    val enabled: Boolean = true,
    val reason: String? = null,
)

/** The capture request keys for a phone camera, resolved from its values. */
data class Camera2Plan(
    /** CONTROL_AE_MODE_ON or _OFF */
    val aeMode: Int,
    val evIndex: Int,
    val aeLock: Boolean,
    /** Set with [aeMode] OFF only */
    val iso: Int?,
    val exposureNs: Long?,
    val frameDurationNs: Long?,
    val afMode: Int,
    /** Set with [afMode] OFF only */
    val lensDiopters: Float?,
    val awbMode: Int,
    val awbLock: Boolean,
    val zoom: Float,
    val torch: Boolean,
    val ois: Boolean,
    val eis: Boolean,
    val tap: TapFocus?,
)
