package com.dimadesu.lifestreamer.power

/**
 * How hot the phone is, in rungs the app acts on, from the battery's temperature.
 *
 * Calibrated on the S20 FE: on 2026-10-05 Samsung's own heat guard closed the app with the
 * battery at 55 °C, after climbing about half a degree a minute. The rungs start ten degrees and
 * some ten minutes before that, so what the app gives up has time to bend the curve.
 *
 * Up at once, to the highest rung the temperature reaches. Down one rung at a time, only after
 * [coolHoldMs] at [coolMarginC] below the rung: a camera going off and on is worse than staying
 * degraded. Pure, so it is tested on the JVM.
 */
class HeatLadder(
    private val thresholdsC: List<Float> = THRESHOLDS_C,
    private val coolMarginC: Float = 3f,
    private val coolHoldMs: Long = 3 * 60_000L,
) {
    var rung = 0
        private set

    private var coolSinceMs: Long? = null

    /** The rung for [batteryC] at [nowMs]; an unknown temperature changes nothing. */
    fun update(batteryC: Float?, nowMs: Long): Int {
        if (batteryC == null) return rung
        val reached = thresholdsC.count { batteryC >= it }
        if (reached > rung) {
            rung = reached
            coolSinceMs = null
            return rung
        }
        if (rung > 0 && batteryC <= thresholdsC[rung - 1] - coolMarginC) {
            val since = coolSinceMs ?: nowMs.also { coolSinceMs = it }
            if (nowMs - since >= coolHoldMs) {
                rung--
                coolSinceMs = null
            }
        } else {
            coolSinceMs = null
        }
        return rung
    }

    companion object {
        /** Battery °C for rungs 1, 2 and 3. */
        val THRESHOLDS_C = listOf(44f, 47f, 50f)
    }
}
