package com.dimadesu.lifestreamer.settings

/**
 * The rules a setting's value must follow, whoever edits it: the settings screen or the remote
 * page. Plain Kotlin, no Android types, so they are tested on the JVM.
 */
object SettingsRules {

    /** A width and height; android.util.Size has no behaviour in JVM tests. */
    data class Dimensions(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
        override fun toString() = "${width}x$height"

        companion object {
            fun parse(text: String?): Dimensions? {
                val parts = text?.trim()?.split('x', 'X', '*') ?: return null
                if (parts.size != 2) return null
                val width = parts[0].trim().toIntOrNull() ?: return null
                val height = parts[1].trim().toIntOrNull() ?: return null
                return if (width > 0 && height > 0) Dimensions(width, height) else null
            }
        }
    }

    /**
     * Snaps a bitrate in kb/s to a round value: 50 kb/s steps below 1 Mb/s, where a low-bandwidth
     * link needs the precision, and 500 kb/s steps from there up. Rounds to the nearest step.
     */
    fun snapBitrateKbps(value: Int): Int {
        val step = if (value < 1000) 50 else 500
        return ((value + step / 2) / step) * step
    }

    /**
     * Keeps a regulator's minimum at or below its target, moving the one that was not just set.
     *
     * @return (minimum, target)
     */
    fun coupleRegulator(minKbps: Int, targetKbps: Int, targetWasSet: Boolean): Pair<Int, Int> =
        when {
            minKbps <= targetKbps -> minKbps to targetKbps
            targetWasSet -> targetKbps to targetKbps
            else -> minKbps to minKbps
        }

    fun port(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** SRT latency in ms: SRT itself accepts 0, but anything under 20 ms cannot survive a real link. */
    fun latencyMs(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 20..60_000 }

    /** A host name or address: something, with no spaces or scheme. */
    fun host(text: String?): String? = text?.trim()?.takeIf {
        it.isNotEmpty() && it.none(Char::isWhitespace) && "://" !in it
    }

    /** H.264/HEVC bits per pixel per frame below which the picture visibly breaks into blocks. */
    const val MIN_BITS_PER_PIXEL = 0.02

    /** The frame rate suggested when a smaller resolution is not enough. */
    const val LOW_FPS = 15

    /**
     * What is wrong with a bitrate for a resolution and frame rate, and the nearest fix.
     *
     * @param neededKbps about what the resolution and frame rate need
     * @param smaller the largest offered resolution (same shape first) that fits at [fps], or
     * null when none does
     * @param smallerAt15Fps with [smaller] null, the largest that fits at [LOW_FPS]
     */
    data class LowBitrate(
        val neededKbps: Int,
        val smaller: Dimensions?,
        val smallerAt15Fps: Dimensions?,
    )

    /**
     * Null when [bitrateBps] gives at least [MIN_BITS_PER_PIXEL] per pixel per frame at
     * [resolution] and [fps]; otherwise how far off it is and the closest fix among [offered].
     */
    fun lowBitrate(
        bitrateBps: Int,
        resolution: Dimensions,
        fps: Int,
        offered: List<Dimensions>,
    ): LowBitrate? {
        fun fits(size: Dimensions, atFps: Int) =
            bitrateBps.toDouble() / (size.pixels * atFps) >= MIN_BITS_PER_PIXEL

        if (fps <= 0 || fits(resolution, fps)) return null
        val neededKbps =
            Math.round(resolution.pixels.toDouble() * fps * MIN_BITS_PER_PIXEL / 1000 / 50).toInt() * 50
        val shape = resolution.width.toDouble() / resolution.height
        // Same shape first (sortedBy is stable, so each group keeps its size order)
        val smaller = offered
            .filter { it.pixels < resolution.pixels }
            .sortedByDescending { it.pixels }
            .sortedBy { if (kotlin.math.abs(it.width.toDouble() / it.height - shape) < 0.05) 0 else 1 }
        val atFps = smaller.firstOrNull { fits(it, fps) }
        val at15 = if (atFps == null && fps > LOW_FPS) {
            (listOf(resolution) + smaller).firstOrNull { fits(it, LOW_FPS) }
        } else null
        return LowBitrate(neededKbps, atFps, at15)
    }

    /** Whether two resolutions have the same shape (a recording shares the live's canvas). */
    fun sameShape(a: Dimensions, b: Dimensions): Boolean =
        kotlin.math.abs(a.width.toDouble() / a.height - b.width.toDouble() / b.height) < 0.02
}
