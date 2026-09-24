package com.dimadesu.lifestreamer.utils

/**
 * The highest sample rate AAC keeps up with at the low audio bitrates.
 *
 * Measured on the SM-G780F (c2.sec.aac.encoder, AAC-LC, mono). At 44.1 kHz, 32 kb/s makes the
 * encoder stop taking input altogether: it never emits a frame, and a receiver such as MediaMTX
 * then drops the connection waiting for the announced audio track. 48 kb/s at 44.1 kHz comes
 * out choppy. 32 kb/s at 16 kHz and 48 kb/s at 32 kHz are clean. From 64 kb/s up nothing is
 * capped.
 */
object LowBitrateAudio {
    /** The cap for [bitrate] in b/s, or null when it needs none. */
    fun maxSampleRate(bitrate: Int): Int? = when {
        bitrate <= 32_000 -> 16_000
        bitrate <= 48_000 -> 32_000
        else -> null
    }

    /** [sampleRate], lowered to the cap for [bitrate] when it is above it. */
    fun coerceSampleRate(bitrate: Int, sampleRate: Int): Int =
        maxSampleRate(bitrate)?.let { minOf(sampleRate, it) } ?: sampleRate
}
