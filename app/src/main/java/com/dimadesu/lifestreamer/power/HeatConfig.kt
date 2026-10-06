package com.dimadesu.lifestreamer.power

/**
 * What the app gives up as the phone heats up, step by step (see HeatGuard), as the operator set
 * it in Settings > Power. The battery's temperature decides the step; it follows the phone's
 * skin closely. Defaults from 2026-10-05 on the S20 FE: Samsung closed the app at 55 °C.
 *
 * - Step 1 ([stepsC][0]): the cameras at [warmFps], the bitrate at most [warmKbps], the preview off.
 * - Step 2 ([stepsC][1]): every camera layer but the 🔊 one paused ("Celular quente" on the live),
 *   the cameras at [hotFps], the bitrate at most [hotKbps].
 * - Step 3 ([stepsC][2]): the composition off, one source.
 *
 * Up at once; down one step after [coolHoldMinutes] at [coolMarginC] below the step.
 * Plain Kotlin, tested on the JVM.
 */
data class HeatConfig(
    val stepsC: List<Int> = DEFAULT_STEPS_C,
    val warmFps: Int = 24,
    val hotFps: Int = 20,
    val warmKbps: Int = 4000,
    val hotKbps: Int = 3000,
    val coolMarginC: Int = 3,
    val coolHoldMinutes: Int = 3,
) {
    /**
     * As it is used: the steps in range and each at least 1 °C above the one before (whatever was
     * stored), the hot step never giving more than the warm one.
     */
    fun normalized(): HeatConfig {
        val steps = stepsC.take(3).let { if (it.size < 3) DEFAULT_STEPS_C else it }
        val first = steps[0].coerceIn(MIN_STEP_C, MAX_STEP_C - 2)
        val second = steps[1].coerceIn(first + 1, MAX_STEP_C - 1)
        val third = steps[2].coerceIn(second + 1, MAX_STEP_C)
        val warm = warmFps.coerceIn(MIN_FPS, MAX_FPS)
        val warmBitrate = warmKbps.coerceIn(MIN_KBPS, MAX_KBPS)
        return copy(
            stepsC = listOf(first, second, third),
            warmFps = warm,
            hotFps = hotFps.coerceIn(MIN_FPS, warm),
            warmKbps = warmBitrate,
            hotKbps = hotKbps.coerceIn(MIN_KBPS, warmBitrate),
            coolMarginC = coolMarginC.coerceIn(1, 10),
            coolHoldMinutes = coolHoldMinutes.coerceIn(1, 15),
        )
    }

    /** What [step] gives up, in the operator's words. */
    fun describe(step: Int): String = when (step) {
        0 -> "Nothing given up"
        1 -> "Cameras at $warmFps fps, at most ${kbps(warmKbps)}, preview off"
        2 -> "Second camera paused (\"Celular quente\" on the live), cameras at $hotFps fps, at most ${kbps(hotKbps)}"
        else -> "Composition off: one source"
    }

    /** Every step in one text, for a settings screen. */
    fun summary(): String = (1..3).joinToString("\n") { step ->
        "Battery ≥ ${stepsC[step - 1]} °C: ${describe(step)}"
    } + "\nBack one step after $coolHoldMinutes min at $coolMarginC °C below it."

    companion object {
        val DEFAULT_STEPS_C = listOf(44, 47, 50)
        const val MIN_STEP_C = 35
        const val MAX_STEP_C = 60
        const val MIN_FPS = 10
        const val MAX_FPS = 30
        const val MIN_KBPS = 500
        const val MAX_KBPS = 10_000

        private fun kbps(value: Int) =
            if (value % 1000 == 0) "${value / 1000} Mbps" else "%.1f Mbps".format(java.util.Locale.US, value / 1000.0)

        /**
         * Keeps the three steps rising after [changed] (0, 1 or 2) was set to its value: the
         * others move out of its way, by at least 1 °C, as the regulator's minimum follows its
         * target. Returns the steps and whether any other moved.
         */
        fun orderSteps(steps: List<Int>, changed: Int): Pair<List<Int>, Boolean> {
            val result = steps.toMutableList()
            for (i in changed + 1 until result.size) {
                if (result[i] <= result[i - 1]) result[i] = result[i - 1] + 1
            }
            for (i in changed - 1 downTo 0) {
                if (result[i] >= result[i + 1]) result[i] = result[i + 1] - 1
            }
            return result to (result != steps)
        }
    }
}
