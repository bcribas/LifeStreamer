package com.dimadesu.lifestreamer.bitrate

/**
 * A ceiling on the video bitrate, over what the regulator wants: set while the phone is hot (see
 * HeatGuard), null otherwise. Process-wide, because the regulator is built anew on every
 * reconnection and must find the ceiling still there.
 */
object BitrateCeiling {
    @Volatile
    var bps: Int? = null

    /** [requested], or the ceiling when that is lower. */
    fun clamp(requested: Int): Int = bps?.let { minOf(it, requested) } ?: requested

    /**
     * Gives the regulator's target to the encoder, at most the ceiling. An encoder stopped under
     * the regulator's last tick (a live ending) throws from MediaCodec.setParameters; that ended
     * the whole app on 2026-10-05, so it is only logged.
     */
    fun apply(requested: Int, toEncoder: (Int) -> Unit) {
        runCatching { toEncoder(clamp(requested)) }
            .onFailure { android.util.Log.w("BitrateCeiling", "Bitrate not set, the encoder is gone: ${it.message}") }
    }
}
