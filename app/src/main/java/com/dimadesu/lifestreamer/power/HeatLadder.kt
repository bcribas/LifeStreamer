package com.dimadesu.lifestreamer.power

/**
 * How hot the phone is, in steps the app acts on, from the battery's temperature and the steps
 * in [config] (Settings > Power, see [HeatConfig]).
 *
 * Up at once, to the highest step the temperature reaches. Down one step at a time, only after
 * [HeatConfig.coolHoldMinutes] at [HeatConfig.coolMarginC] below the step: a camera going off and
 * on is worse than staying degraded. Pure, so it is tested on the JVM.
 */
class HeatLadder(
    /** Read on every update: a change in the settings applies at the next reading. */
    @Volatile var config: HeatConfig = HeatConfig(),
) {
    var rung = 0
        private set

    private var coolSinceMs: Long? = null

    /** The step for [batteryC] at [nowMs]; an unknown temperature changes nothing. */
    fun update(batteryC: Float?, nowMs: Long): Int {
        if (batteryC == null) return rung
        val steps = config.normalized()
        val thresholds = steps.stepsC
        val reached = thresholds.count { batteryC >= it }
        if (reached > rung) {
            rung = reached
            coolSinceMs = null
            return rung
        }
        if (rung > 0 && batteryC <= thresholds[rung - 1] - steps.coolMarginC) {
            val since = coolSinceMs ?: nowMs.also { coolSinceMs = it }
            if (nowMs - since >= steps.coolHoldMinutes * 60_000L) {
                rung--
                coolSinceMs = null
            }
        } else {
            coolSinceMs = null
        }
        return rung
    }
}
