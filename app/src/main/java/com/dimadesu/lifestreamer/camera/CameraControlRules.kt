package com.dimadesu.lifestreamer.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The rules a camera's controls follow, whoever changes them (the phone or the remote page):
 * what a change means for the other controls, what the camera is then asked for, and how the
 * controls are described. Pure, so they are tested on the JVM.
 */
object CameraControlRules {

    private const val AF_OFF = CameraMetadata.CONTROL_AF_MODE_OFF
    private const val AF_AUTO = CameraMetadata.CONTROL_AF_MODE_AUTO
    private const val AF_CONTINUOUS_VIDEO = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    private const val AF_CONTINUOUS_PICTURE = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private const val AWB_AUTO = CameraMetadata.CONTROL_AWB_MODE_AUTO

    /** AF modes as the controls name them, in the order they are offered. */
    private val AF_NAMES = linkedMapOf(
        AF_CONTINUOUS_VIDEO to ("continuous" to "Continuous"),
        AF_CONTINUOUS_PICTURE to ("fast" to "Continuous, fast"),
        AF_AUTO to ("single" to "Once"),
        CameraMetadata.CONTROL_AF_MODE_MACRO to ("macro" to "Macro"),
        CameraMetadata.CONTROL_AF_MODE_EDOF to ("edof" to "Extended depth"),
        AF_OFF to ("manual" to "Manual"),
    )

    /** AWB modes as the controls name them; OFF is left out, as there is nothing to set by hand. */
    private val AWB_NAMES = linkedMapOf(
        AWB_AUTO to ("auto" to "Auto"),
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to ("daylight" to "Daylight"),
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to ("cloudy" to "Cloudy"),
        CameraMetadata.CONTROL_AWB_MODE_SHADE to ("shade" to "Shade"),
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to ("twilight" to "Twilight"),
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to ("fluorescent" to "Fluorescent"),
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to ("warm-fluorescent" to "Warm fluorescent"),
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to ("incandescent" to "Incandescent"),
    )

    /** ISO in third stops; a camera offers the ones in its range. */
    private val ISO_STOPS = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
        2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
    )

    /** Shutter speeds as 1/x of a second, besides 1/fps and 1/(2 fps). */
    private val SHUTTER_DENOMINATORS =
        listOf(30, 48, 50, 60, 100, 120, 125, 250, 500, 1000, 2000, 4000, 8000)

    /** The ISO manual exposure starts from when auto-exposure has not said what it uses. */
    private const val SEED_ISO = 400

    class Refused(message: String) : IllegalArgumentException(message)

    // region choices

    fun frameDurationNs(fps: Int): Long = 1_000_000_000L / fps.coerceAtLeast(1)

    /** The longest exposure: the camera's, and never more than a frame, or the frame rate drops. */
    private fun shutterMaxNs(caps: Camera2Caps, fps: Int): Long =
        minOf(caps.exposureMaxNs, frameDurationNs(fps)).coerceAtLeast(caps.exposureMinNs)

    /** Exposure times offered, longest first. */
    fun shutterChoices(caps: Camera2Caps, fps: Int): List<Long> {
        val max = shutterMaxNs(caps, fps)
        return (listOf(fps, 2 * fps) + SHUTTER_DENOMINATORS)
            .filter { it > 0 }
            .map { 1_000_000_000L / it }
            .filter { it in caps.exposureMinNs..max }
            .distinct()
            .sortedDescending()
    }

    fun isoChoices(caps: Camera2Caps): List<Int> =
        ISO_STOPS.filter { it in caps.isoMin..caps.isoMax }.ifEmpty { listOf(caps.isoMin) }

    private fun <T : Comparable<T>> nearest(choices: List<T>, value: T, distance: (T, T) -> Double): T =
        choices.minByOrNull { distance(it, value) } ?: value

    private fun nearestIso(caps: Camera2Caps, iso: Int) =
        nearest(isoChoices(caps), iso) { a, b -> abs(Math.log(a.toDouble() / b)) }

    private fun nearestShutter(caps: Camera2Caps, fps: Int, ns: Long): Long {
        val choices = shutterChoices(caps, fps)
        if (choices.isEmpty()) return ns.coerceIn(caps.exposureMinNs, shutterMaxNs(caps, fps))
        return nearest(choices, ns) { a, b -> abs(Math.log(a.toDouble() / b)) }
    }

    fun shutterLabel(ns: Long): String {
        val denominator = 1_000_000_000.0 / ns
        return if (denominator >= 1.5) "1/${denominator.roundToInt()}" else "%.1f s".format(ns / 1e9)
    }

    /** The AF modes a camera offers: none for a fixed-focus lens. */
    fun offeredAfModes(caps: Camera2Caps): List<Int> {
        if (caps.minFocusDiopters <= 0f) return emptyList()
        // Manual focus needs only a lens that moves
        return AF_NAMES.keys.filter { it in caps.afModes || it == AF_OFF }
    }

    fun offeredAwbModes(caps: Camera2Caps): List<Int> = AWB_NAMES.keys.filter { it in caps.awbModes }

    fun defaultAfMode(caps: Camera2Caps): Int =
        listOf(AF_CONTINUOUS_VIDEO, AF_CONTINUOUS_PICTURE, AF_AUTO).firstOrNull { it in caps.afModes } ?: AF_OFF

    fun afModeName(mode: Int): String = AF_NAMES[mode]?.first ?: mode.toString()
    fun awbModeName(mode: Int): String = AWB_NAMES[mode]?.first ?: mode.toString()

    // endregion

    // region values

    private fun Float.round2() = (this * 100).roundToInt() / 100f

    private fun number(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    private fun bool(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        "true", "on", "1" -> true
        "false", "off", "0" -> false
        else -> null
    }

    /** Drops or limits whatever [caps] cannot do: a stored value from another camera, or an older app. */
    fun sanitize(values: CameraControlValues, caps: Camera2Caps, fps: Int): CameraControlValues {
        val manual = values.manualExposure == true && caps.manualSensor
        val awbMode = values.awbMode?.takeIf { it in offeredAwbModes(caps) }
        return values.copy(
            zoom = values.zoom?.coerceIn(caps.zoomMin, caps.zoomMax)?.round2(),
            evIndex = values.evIndex?.coerceIn(caps.evMin, caps.evMax),
            aeLock = values.aeLock?.takeIf { caps.aeLockAvailable },
            manualExposure = if (manual) true else null,
            iso = if (caps.manualSensor) values.iso?.coerceIn(caps.isoMin, caps.isoMax) else null,
            exposureNs = if (caps.manualSensor) {
                values.exposureNs?.coerceIn(caps.exposureMinNs, shutterMaxNs(caps, fps))
            } else null,
            afMode = values.afMode?.takeIf { it in offeredAfModes(caps) },
            lensDiopters = values.lensDiopters?.takeIf { caps.minFocusDiopters > 0f }
                ?.coerceIn(0f, caps.minFocusDiopters),
            awbMode = awbMode,
            awbLock = values.awbLock?.takeIf {
                caps.awbLockAvailable && (awbMode ?: AWB_AUTO) == AWB_AUTO
            },
        ).let { sanitized ->
            // Manual needs both halves
            if (manual) fillManual(sanitized, caps, fps, null) else sanitized
        }
    }

    /** Fills whichever of ISO and exposure time is missing, from what auto-exposure used if known. */
    private fun fillManual(
        values: CameraControlValues,
        caps: Camera2Caps,
        fps: Int,
        reading: ExposureReading?,
    ): CameraControlValues = values.copy(
        manualExposure = true,
        iso = values.iso ?: nearestIso(caps, reading?.iso ?: SEED_ISO),
        exposureNs = values.exposureNs
            ?: nearestShutter(caps, fps, reading?.exposureNs ?: (frameDurationNs(fps) / 2)),
    )

    /**
     * [values] with [key] changed to [raw] (a web or screen value: number, string or boolean),
     * and what that change means for the other controls; a failure says why it was refused.
     */
    fun applyChange(
        values: CameraControlValues,
        key: String,
        raw: Any?,
        caps: Camera2Caps,
        fps: Int,
        reading: ExposureReading? = null,
    ): Result<CameraControlValues> = runCatching {
        val current = sanitize(values, caps, fps)
        val manual = current.manualExposure == true
        when (key) {
            ControlKeys.ZOOM -> {
                if (caps.zoomMax <= caps.zoomMin) throw Refused("This camera has no zoom")
                val zoom = number(raw) ?: throw Refused("Expected a number")
                current.copy(zoom = zoom.toFloat().coerceIn(caps.zoomMin, caps.zoomMax).round2())
            }

            ControlKeys.EV -> {
                if (caps.evMax <= caps.evMin) throw Refused("This camera has no exposure compensation")
                if (manual) throw Refused("Exposure is manual")
                val index = number(raw)?.roundToInt() ?: throw Refused("Expected a number")
                current.copy(evIndex = index.coerceIn(caps.evMin, caps.evMax))
            }

            ControlKeys.AE_LOCK -> {
                if (!caps.aeLockAvailable) throw Refused("This camera cannot lock exposure")
                if (manual) throw Refused("Exposure is manual")
                current.copy(aeLock = bool(raw) ?: throw Refused("Expected on or off"))
            }

            ControlKeys.EXPOSURE_MODE -> when (raw) {
                "auto" -> current.copy(manualExposure = null)
                "manual" -> {
                    if (!caps.manualSensor) throw Refused("This camera has no manual exposure")
                    fillManual(current, caps, fps, reading)
                }
                else -> throw Refused("Expected auto or manual")
            }

            ControlKeys.ISO -> {
                if (!caps.manualSensor) throw Refused("This camera has no manual exposure")
                val iso = number(raw)?.roundToInt() ?: throw Refused("Expected a number")
                fillManual(current.copy(iso = nearestIso(caps, iso)), caps, fps, reading)
            }

            ControlKeys.SHUTTER -> {
                if (!caps.manualSensor) throw Refused("This camera has no manual exposure")
                val ns = number(raw)?.roundToLong() ?: throw Refused("Expected a number")
                fillManual(current.copy(exposureNs = nearestShutter(caps, fps, ns)), caps, fps, reading)
            }

            ControlKeys.AF_MODE -> {
                val mode = AF_NAMES.entries.firstOrNull { it.value.first == raw }?.key
                    ?.takeIf { it in offeredAfModes(caps) }
                    ?: throw Refused("Not a focus mode this camera has")
                current.copy(
                    afMode = mode,
                    lensDiopters = if (mode == AF_OFF) current.lensDiopters ?: 0f else current.lensDiopters,
                )
            }

            ControlKeys.LENS -> {
                if (caps.minFocusDiopters <= 0f) throw Refused("This camera's focus is fixed")
                val diopters = number(raw)?.toFloat() ?: throw Refused("Expected a number")
                // Setting the distance is focusing by hand
                current.copy(afMode = AF_OFF, lensDiopters = diopters.coerceIn(0f, caps.minFocusDiopters))
            }

            ControlKeys.AWB_MODE -> {
                val mode = AWB_NAMES.entries.firstOrNull { it.value.first == raw }?.key
                    ?.takeIf { it in offeredAwbModes(caps) }
                    ?: throw Refused("Not a white balance this camera has")
                // A preset is already fixed: the lock only means something on auto
                current.copy(awbMode = mode, awbLock = if (mode == AWB_AUTO) current.awbLock else null)
            }

            ControlKeys.AWB_LOCK -> {
                if (!caps.awbLockAvailable) throw Refused("This camera cannot lock white balance")
                if ((current.awbMode ?: AWB_AUTO) != AWB_AUTO) throw Refused("Only with Auto white balance")
                current.copy(awbLock = bool(raw) ?: throw Refused("Expected on or off"))
            }

            else -> throw Refused("Not a control of this camera")
        }
    }

    /** The next AF or AWB mode after the current one, for the phone's quick buttons. */
    fun cycle(values: CameraControlValues, key: String, caps: Camera2Caps, fps: Int): Result<CameraControlValues> {
        val (modes, current, name) = when (key) {
            ControlKeys.AF_MODE -> Triple(offeredAfModes(caps), values.afMode ?: defaultAfMode(caps), ::afModeName)
            ControlKeys.AWB_MODE -> Triple(offeredAwbModes(caps), values.awbMode ?: AWB_AUTO, ::awbModeName)
            else -> return Result.failure(Refused("Only focus and white balance cycle"))
        }
        if (modes.size < 2) return Result.failure(Refused("Nothing to cycle"))
        val next = modes[(modes.indexOf(current) + 1).mod(modes.size)]
        return applyChange(values, key, name(next), caps, fps)
    }

    fun nudgeZoom(values: CameraControlValues, factor: Float, caps: Camera2Caps, fps: Int): Result<CameraControlValues> {
        val zoom = (values.zoom ?: 1f).coerceIn(caps.zoomMin, caps.zoomMax)
        return applyChange(values, ControlKeys.ZOOM, zoom * factor, caps, fps)
    }

    // endregion

    /**
     * What the camera is asked for. [concurrent]: another camera runs alongside, where video
     * stabilization may not fit the guaranteed configuration, so only the optical one is used.
     */
    fun resolveCamera2(
        values: CameraControlValues,
        runtime: RuntimeControls,
        caps: Camera2Caps,
        fps: Int,
        concurrent: Boolean,
    ): Camera2Plan {
        val v = sanitize(values, caps, fps)
        val manual = v.manualExposure == true
        val chosenAf = v.afMode ?: defaultAfMode(caps)
        // A tap focuses once and holds, unless the focus is set by hand
        val afMode = if (runtime.tapFocus != null && AF_AUTO in caps.afModes && chosenAf != AF_OFF) AF_AUTO else chosenAf
        return Camera2Plan(
            aeMode = if (manual) CameraMetadata.CONTROL_AE_MODE_OFF else CameraMetadata.CONTROL_AE_MODE_ON,
            evIndex = if (manual) 0 else v.evIndex ?: 0,
            aeLock = !manual && v.aeLock == true,
            iso = if (manual) v.iso else null,
            exposureNs = if (manual) v.exposureNs else null,
            frameDurationNs = if (manual) frameDurationNs(fps) else null,
            afMode = afMode,
            lensDiopters = if (afMode == AF_OFF && caps.minFocusDiopters > 0f) v.lensDiopters ?: 0f else null,
            awbMode = v.awbMode ?: AWB_AUTO,
            awbLock = v.awbLock == true,
            zoom = (v.zoom ?: 1f).coerceIn(caps.zoomMin, caps.zoomMax),
            torch = runtime.torch && caps.torch,
            ois = caps.ois,
            eis = !caps.ois && caps.eis && !concurrent,
            tap = runtime.tapFocus,
        )
    }

    /** The controls of a phone camera, as the panel and the page draw them. */
    fun describeCamera2(
        caps: Camera2Caps,
        values: CameraControlValues,
        runtime: RuntimeControls,
        fps: Int,
    ): List<ControlDescriptor> {
        val v = sanitize(values, caps, fps)
        val manual = v.manualExposure == true
        val controls = mutableListOf<ControlDescriptor>()

        if (caps.zoomMax > caps.zoomMin) {
            controls += ControlDescriptor(
                ControlKeys.ZOOM, "Zoom", ControlGroup.ZOOM, ControlType.RANGE,
                value = (v.zoom ?: 1f).coerceIn(caps.zoomMin, caps.zoomMax),
                min = caps.zoomMin, max = caps.zoomMax, step = 0.01f, format = ControlFormat.ZOOM,
            )
        }

        if (caps.manualSensor) {
            controls += ControlDescriptor(
                ControlKeys.EXPOSURE_MODE, "Exposure", ControlGroup.EXPOSURE, ControlType.CHOICE,
                value = if (manual) "manual" else "auto",
                options = listOf(ControlOption("auto", "Auto"), ControlOption("manual", "Manual")),
            )
        }
        if (caps.evMax > caps.evMin) {
            controls += ControlDescriptor(
                ControlKeys.EV, "Compensation", ControlGroup.EXPOSURE, ControlType.RANGE,
                value = v.evIndex ?: 0, min = caps.evMin.toFloat(), max = caps.evMax.toFloat(), step = 1f,
                format = ControlFormat.EV, scale = caps.evStep,
                enabled = !manual, reason = "Exposure is manual".takeIf { manual },
            )
        }
        if (caps.aeLockAvailable) {
            controls += ControlDescriptor(
                ControlKeys.AE_LOCK, "Lock exposure", ControlGroup.EXPOSURE, ControlType.TOGGLE,
                value = v.aeLock == true,
                enabled = !manual, reason = "Exposure is manual".takeIf { manual },
            )
        }
        if (caps.manualSensor) {
            controls += ControlDescriptor(
                ControlKeys.ISO, "ISO", ControlGroup.EXPOSURE, ControlType.CHOICE,
                value = v.iso?.takeIf { manual }?.toString(),
                options = isoChoices(caps).map { ControlOption("$it", "$it") },
            )
            controls += ControlDescriptor(
                ControlKeys.SHUTTER, "Shutter", ControlGroup.EXPOSURE, ControlType.CHOICE,
                value = v.exposureNs?.takeIf { manual }?.toString(),
                options = shutterChoices(caps, fps).map { ControlOption("$it", shutterLabel(it)) },
            )
        }

        val afModes = offeredAfModes(caps)
        if (afModes.isNotEmpty()) {
            val afMode = v.afMode ?: defaultAfMode(caps)
            controls += ControlDescriptor(
                ControlKeys.AF_MODE, "Focus", ControlGroup.FOCUS, ControlType.CHOICE,
                value = afModeName(afMode),
                options = afModes.map { ControlOption(AF_NAMES.getValue(it).first, AF_NAMES.getValue(it).second) },
            )
            controls += ControlDescriptor(
                ControlKeys.LENS, "Distance", ControlGroup.FOCUS, ControlType.RANGE,
                value = v.lensDiopters ?: 0f, min = 0f, max = caps.minFocusDiopters,
                format = ControlFormat.DISTANCE,
            )
            if (runtime.tapFocus != null) {
                controls += ControlDescriptor(
                    ControlKeys.FOCUS_RESUME, "Release the tapped focus", ControlGroup.FOCUS, ControlType.ACTION,
                )
            }
        }

        val awbModes = offeredAwbModes(caps)
        if (awbModes.size > 1) {
            controls += ControlDescriptor(
                ControlKeys.AWB_MODE, "White balance", ControlGroup.WHITE_BALANCE, ControlType.CHOICE,
                value = awbModeName(v.awbMode ?: AWB_AUTO),
                options = awbModes.map { ControlOption(AWB_NAMES.getValue(it).first, AWB_NAMES.getValue(it).second) },
            )
        }
        if (caps.awbLockAvailable) {
            val auto = (v.awbMode ?: AWB_AUTO) == AWB_AUTO
            controls += ControlDescriptor(
                ControlKeys.AWB_LOCK, "Lock white balance", ControlGroup.WHITE_BALANCE, ControlType.TOGGLE,
                value = v.awbLock == true, enabled = auto, reason = "Only with Auto".takeIf { !auto },
            )
        }

        if (caps.torch) {
            controls += ControlDescriptor(
                ControlKeys.TORCH, "Torch", ControlGroup.LIGHT, ControlType.TOGGLE,
                value = runtime.torch, reason = "Not remembered",
            )
        }
        controls += ControlDescriptor(ControlKeys.RESET, "Back to automatic", ControlGroup.GENERAL, ControlType.ACTION)
        return controls
    }

    // region USB cameras

    private val UVC_LABELS = linkedMapOf(
        UvcKeys.ZOOM to ("Zoom" to ControlGroup.ZOOM),
        UvcKeys.AE_AUTO to ("Auto exposure" to ControlGroup.EXPOSURE),
        UvcKeys.EXPOSURE to ("Exposure time" to ControlGroup.EXPOSURE),
        UvcKeys.GAIN to ("Gain" to ControlGroup.EXPOSURE),
        UvcKeys.BACKLIGHT to ("Backlight compensation" to ControlGroup.EXPOSURE),
        UvcKeys.FOCUS_AUTO to ("Auto focus" to ControlGroup.FOCUS),
        UvcKeys.FOCUS to ("Focus" to ControlGroup.FOCUS),
        UvcKeys.WB_AUTO to ("Auto white balance" to ControlGroup.WHITE_BALANCE),
        UvcKeys.WB to ("Temperature" to ControlGroup.WHITE_BALANCE),
        UvcKeys.BRIGHTNESS to ("Brightness" to ControlGroup.IMAGE),
        UvcKeys.CONTRAST to ("Contrast" to ControlGroup.IMAGE),
        UvcKeys.SATURATION to ("Saturation" to ControlGroup.IMAGE),
        UvcKeys.SHARPNESS to ("Sharpness" to ControlGroup.IMAGE),
        UvcKeys.GAMMA to ("Gamma" to ControlGroup.IMAGE),
        UvcKeys.HUE to ("Hue" to ControlGroup.IMAGE),
        UvcKeys.POWERLINE to ("Anti-flicker" to ControlGroup.IMAGE),
    )

    /** UVC power line frequency values. */
    private val POWERLINE_OPTIONS = listOf(
        ControlOption("0", "Off"), ControlOption("1", "50 Hz"),
        ControlOption("2", "60 Hz"), ControlOption("3", "Auto"),
    )

    fun sanitizeUvc(values: CameraControlValues, caps: UvcCaps): CameraControlValues =
        values.copy(uvc = values.uvc.mapNotNull { (key, value) ->
            caps.controls[key]?.let { key to value.coerceIn(it.min, it.max) }
        }.toMap())

    fun applyUvcChange(values: CameraControlValues, key: String, raw: Any?, caps: UvcCaps): Result<CameraControlValues> =
        runCatching {
            val limits = caps.controls[key] ?: throw Refused("Not a control of this camera")
            val value = when {
                key in UvcKeys.AUTOS -> if (bool(raw) ?: throw Refused("Expected on or off")) 1 else 0
                else -> number(raw)?.roundToInt() ?: throw Refused("Expected a number")
            }.coerceIn(limits.min, limits.max)
            val uvc = sanitizeUvc(values, caps).uvc.toMutableMap()
            uvc[key] = value
            // Setting a manual value takes it off automatic
            UvcKeys.AUTO_OF[key]?.takeIf { it in caps.controls }?.let { uvc[it] = 0 }
            values.copy(uvc = uvc)
        }

    fun describeUvc(caps: UvcCaps, values: CameraControlValues): List<ControlDescriptor> {
        val uvc = sanitizeUvc(values, caps).uvc
        fun current(key: String) = uvc[key] ?: caps.controls.getValue(key).default
        val controls = UVC_LABELS.mapNotNull { (key, labelAndGroup) ->
            val limits = caps.controls[key] ?: return@mapNotNull null
            val (label, group) = labelAndGroup
            when {
                key in UvcKeys.AUTOS -> ControlDescriptor(
                    key, label, group, ControlType.TOGGLE, value = current(key) != 0,
                )
                key == UvcKeys.POWERLINE -> ControlDescriptor(
                    key, label, group, ControlType.CHOICE, value = current(key).toString(),
                    options = POWERLINE_OPTIONS.filter { it.value.toInt() in limits.min..limits.max },
                )
                else -> {
                    val auto = UvcKeys.AUTO_OF[key]?.takeIf { it in caps.controls }?.let { current(it) != 0 } == true
                    ControlDescriptor(
                        key, label, group, ControlType.RANGE, value = current(key),
                        min = limits.min.toFloat(), max = limits.max.toFloat(), step = 1f,
                        // Exposure time is in 100 µs units
                        scale = if (key == UvcKeys.EXPOSURE) 0.1f else 1f,
                        unit = when (key) { UvcKeys.EXPOSURE -> "ms"; UvcKeys.WB -> "K"; else -> null },
                        enabled = !auto, reason = "Automatic".takeIf { auto },
                    )
                }
            }
        }
        return controls + ControlDescriptor(ControlKeys.RESET, "Back to automatic", ControlGroup.GENERAL, ControlType.ACTION)
    }

    // endregion
}
