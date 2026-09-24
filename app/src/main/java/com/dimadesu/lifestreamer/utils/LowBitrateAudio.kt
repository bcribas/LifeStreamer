package com.dimadesu.lifestreamer.utils

/**
 * The highest sample rate AAC-LC keeps up with at the low audio bitrates.
 *
 * Measured on the SM-G780F (c2.sec.aac.encoder), mono. At 44.1 kHz, 32 kb/s makes the encoder
 * stop taking input altogether: it never emits a frame, and a receiver such as MediaMTX then
 * drops the connection waiting for the announced audio track. 48 kb/s at 44.1 kHz comes out
 * choppy. 32 kb/s at 16 kHz and 48 kb/s at 32 kHz are clean.
 *
 * Nothing public documents the Samsung encoder, so the rest follows the recommended AAC-LC
 * configurations in fdk-aac's aacenc_lib.h (the same limits AWS MediaConvert/MediaLive enforce):
 * mono needs 56 kb/s for 44.1/48 kHz and 32 kb/s for 32 kHz; stereo needs 96 kb/s for
 * 44.1/48 kHz and 40 kb/s for 32 kHz. The measurements put this encoder's floor at about one bit
 * per sample per channel, so the stereo cells stay at or above that rather than at FDK's edge:
 * 48 kb/s stereo gets 24 kHz, not 32. Stereo is inferred, not measured.
 */
object LowBitrateAudio {
    /** The cap for [bitrate] in b/s over [channels], or null when it needs none. */
    fun maxSampleRate(bitrate: Int, channels: Int): Int? = if (channels <= 1) {
        when {
            bitrate <= 32_000 -> 16_000
            bitrate < 56_000 -> 32_000
            else -> null
        }
    } else {
        when {
            bitrate <= 32_000 -> 16_000
            bitrate <= 48_000 -> 24_000
            bitrate < 112_000 -> 32_000
            else -> null
        }
    }

    /** [sampleRate], lowered to the cap for [bitrate] and [channels] when it is above it. */
    fun coerceSampleRate(bitrate: Int, channels: Int, sampleRate: Int): Int =
        maxSampleRate(bitrate, channels)?.let { minOf(sampleRate, it) } ?: sampleRate
}
