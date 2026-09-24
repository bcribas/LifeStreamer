package com.dimadesu.lifestreamer.camera

import kotlin.math.roundToInt

/**
 * From a point on the upright frame to a metering region on the sensor. Pure, so it is tested
 * on the JVM; follows StreamPack's FocusMetering, whose own region is clamped with its bounds
 * reversed and collapses into a corner.
 */
object MeteringMath {

    data class Point(val x: Float, val y: Float)

    /** A region in active-array pixels, left < right and top < bottom. */
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** How far the sensor is turned from the frame as shown (as StreamPack computes it). */
    fun relativeRotation(sensorOrientation: Int, displayRotation: Int, isFront: Boolean): Int =
        if (isFront) (sensorOrientation + displayRotation) % 360
        else (sensorOrientation - displayRotation + 360) % 360

    /** [x], [y] on the upright frame (0..1) to the sensor's orientation (0..1). */
    fun frameToSensor(x: Float, y: Float, relativeRotation: Int, isFront: Boolean): Point {
        val rotated = when (relativeRotation) {
            90 -> Point(y, 1 - x)
            180 -> Point(1 - x, 1 - y)
            270 -> Point(1 - y, x)
            else -> Point(x, y)
        }
        if (!isFront) return rotated
        // A front sensor is a mirror image of what is shown
        return if (relativeRotation == 90 || relativeRotation == 270) Point(rotated.x, 1 - rotated.y)
        else Point(1 - rotated.x, rotated.y)
    }

    /**
     * A sensor-oriented point (0..1 over what the frame shows) to active-array pixels. The frame
     * shows a centred crop of the array with its own shape, and 1/[zoom] of that when the zoom
     * crops the sensor itself (before Android 11; after, regions follow the zoomed view).
     */
    fun toArray(
        point: Point,
        frameAspectOnSensor: Float,
        arrayWidth: Int,
        arrayHeight: Int,
        zoom: Float = 1f,
    ): Point {
        val arrayAspect = arrayWidth.toFloat() / arrayHeight
        var coverX = 1f
        var coverY = 1f
        if (frameAspectOnSensor > arrayAspect) coverY = arrayAspect / frameAspectOnSensor
        else coverX = frameAspectOnSensor / arrayAspect
        coverX /= zoom.coerceAtLeast(1f)
        coverY /= zoom.coerceAtLeast(1f)
        return Point(
            (0.5f + (point.x - 0.5f) * coverX) * arrayWidth,
            (0.5f + (point.y - 0.5f) * coverY) * arrayHeight,
        )
    }

    /** A square-ish region of [fraction] of the array around [center], kept inside the array. */
    fun region(center: Point, arrayWidth: Int, arrayHeight: Int, fraction: Float = 1f / 6): Region {
        val halfW = (arrayWidth * fraction / 2).roundToInt().coerceAtLeast(1)
        val halfH = (arrayHeight * fraction / 2).roundToInt().coerceAtLeast(1)
        val cx = center.x.roundToInt().coerceIn(halfW, (arrayWidth - halfW).coerceAtLeast(halfW))
        val cy = center.y.roundToInt().coerceIn(halfH, (arrayHeight - halfH).coerceAtLeast(halfH))
        return Region(
            (cx - halfW).coerceAtLeast(0), (cy - halfH).coerceAtLeast(0),
            (cx + halfW).coerceAtMost(arrayWidth), (cy + halfH).coerceAtMost(arrayHeight),
        )
    }

    /** The whole way from a tap on the upright frame to a region on [caps]' sensor. */
    fun regionFor(tap: TapFocus, caps: Camera2Caps, zoom: Float): Region? {
        if (caps.activeArrayWidth <= 0 || caps.activeArrayHeight <= 0) return null
        val rotation = relativeRotation(caps.sensorOrientation, tap.displayRotation, caps.isFront)
        val onSensor = frameToSensor(tap.x, tap.y, rotation, caps.isFront)
        val aspect = if (rotation == 90 || rotation == 270) 1f / tap.frameAspect else tap.frameAspect
        val pixel = toArray(onSensor, aspect, caps.activeArrayWidth, caps.activeArrayHeight, zoom)
        return region(pixel, caps.activeArrayWidth, caps.activeArrayHeight)
    }
}
