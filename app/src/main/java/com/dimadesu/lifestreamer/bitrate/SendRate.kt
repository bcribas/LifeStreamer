package com.dimadesu.lifestreamer.bitrate

/**
 * What the live really sends, from the socket's byte counter between two readings.
 *
 * The bitrate on screen was the encoder's target. With no picture to encode nothing pushed back,
 * the regulator raised it to the top, and the phone said "4 Mbps" while sound alone went out.
 * Plain Kotlin, tested on the JVM.
 */
class SendRate {
    private var lastBytes = -1L
    private var lastMs = 0L

    /** Bits per second since the last reading, or null for the first one. */
    fun update(nowMs: Long, totalBytes: Long?): Int? {
        if (totalBytes == null) {
            reset()
            return null
        }
        val previous = lastBytes
        val since = lastMs
        lastBytes = totalBytes
        lastMs = nowMs
        // A new socket starts its counter again
        if (previous < 0 || totalBytes < previous || nowMs <= since) return null
        return ((totalBytes - previous) * 8_000 / (nowMs - since)).toInt()
    }

    fun reset() {
        lastBytes = -1L
        lastMs = 0L
    }
}
