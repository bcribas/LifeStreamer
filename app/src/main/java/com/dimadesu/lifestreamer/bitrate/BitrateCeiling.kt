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
}
