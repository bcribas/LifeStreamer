package com.dimadesu.lifestreamer.power

import android.util.Log
import com.dimadesu.lifestreamer.bitrate.BitrateCeiling
import com.dimadesu.lifestreamer.composition.CompositionController
import com.dimadesu.lifestreamer.diagnostics.DiagnosticsLog
import com.dimadesu.lifestreamer.sources.SourceController
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The heat step in force in this process, for screens with no line to the service (Settings). */
object HeatStatus {
    @Volatile
    var step = 0

    @Volatile
    var batteryC: Float? = null

    @Volatile
    var text: String = HeatConfig().describe(0)
}

/**
 * Gives up what costs the most heat, in steps, before Samsung's own guard closes the app (it did
 * on 2026-10-05, with the battery at 55 °C and the live gone). The operator chose what may go,
 * and sets where and how much in Settings > Power ([HeatConfig]):
 *
 * 1. the cameras at a lower frame rate, a bitrate ceiling, the preview off;
 * 2. every camera layer but the 🔊 one shows "Celular quente" instead, a lower rate and ceiling;
 * 3. the composition off: one source.
 *
 * It acts on a change of step only, never re-asserting: an operator who turns a camera back on
 * is obeyed until the next step. Coming back is slow on purpose (see [HeatLadder]).
 */
class HeatGuard(
    private val scope: CoroutineScope,
    private val readBatteryC: () -> Float?,
    private val composition: CompositionController,
    private val sources: SourceController,
    private val videoSource: () -> IVideoSource?,
    /** The frame rate the cameras were configured with, to give back. */
    private val configuredFps: () -> Int,
    /** Sets the encoder's bitrate down to the ceiling at once (the regulator follows it later). */
    private val clampEncoder: (Int) -> Unit,
    /** The ceiling is gone: the encoder's own bitrate back, when no regulator raises it. */
    private val restoreEncoder: () -> Unit,
    /** The "thermal backoff" setting: off, nothing here acts. */
    private val isEnabled: () -> Boolean,
    private val journal: DiagnosticsLog,
    private val tell: (String) -> Unit,
    /** The steps as set now; read at every check. */
    private val config: () -> HeatConfig = { HeatConfig() },
) {
    private val ladder = HeatLadder()
    private val mutex = Mutex()

    private val _rung = MutableStateFlow(0)

    /** The step in force, 0 when cool; the preview follows it (see ThermalPolicy). */
    val rung: StateFlow<Int> = _rung.asStateFlow()

    /** A battery reading to use instead of the real one, for the bench (debug builds only). */
    @Volatile
    var batteryOverrideC: Float? = null

    /** The battery's temperature at the last check, °C. */
    @Volatile
    var lastBatteryC: Float? = null
        private set

    /** What the step in force gives up, in the operator's words. */
    fun describeNow(): String = config().normalized().describe(_rung.value)

    private var fpsCap: Int? = null
    private var compositionOffForHeat = false

    private val jobs = mutableListOf<kotlinx.coroutines.Job>()

    fun start() {
        jobs += scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Log.w(TAG, "Heat check failed: ${it.message}", it) }
                delay(TICK_MS)
            }
        }
        // A camera that opens again starts at its configured rate: the cap goes back on
        jobs += scope.launch {
            composition.sourcesVersion.collect { runCatching { applyFps() } }
        }
    }

    /**
     * Stops for good, with the service. One left running after the service went (its scope was
     * never cancelled) changed the frame rate of a camera the old service had released: that
     * opened camera 0 again and the camera service took it from the live, on 2026-10-05.
     */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        BitrateCeiling.bps = null
    }

    /** Checks now, e.g. after a new override. */
    fun checkNow() {
        scope.launch { runCatching { tick() } }
    }

    private suspend fun tick() = mutex.withLock {
        val batteryC = batteryOverrideC ?: readBatteryC()
        lastBatteryC = batteryC
        val steps = config().normalized()
        ladder.config = steps
        val from = _rung.value
        val to = if (isEnabled()) ladder.update(batteryC, System.currentTimeMillis()) else 0
        HeatStatus.batteryC = batteryC
        HeatStatus.step = to
        HeatStatus.text = steps.describe(to)
        if (to == from) {
            // A camera opened since (a source switch) starts at its configured rate; the settings
            // may have changed the step's rate or ceiling
            if (to > 0) applyLimits(to, steps)
            return@withLock
        }
        _rung.value = to
        journal.event("heat", "step $from -> $to batC=$batteryC${if (batteryOverrideC != null) " (override)" else ""}")
        Log.i(TAG, "Heat step $from -> $to at $batteryC °C")
        apply(from, to, steps)
    }

    /** The frame rate and bitrate ceiling of [step]; nothing changes when they are already set. */
    private suspend fun applyLimits(step: Int, steps: HeatConfig) {
        val fps = when {
            step >= 2 -> steps.hotFps
            step >= 1 -> steps.warmFps
            else -> null
        }
        if (fps != fpsCap || fps != null) {
            fpsCap = fps
            applyFps()
        }
        val ceiling = when {
            step >= 2 -> steps.hotKbps * 1000
            step >= 1 -> steps.warmKbps * 1000
            else -> null
        }
        if (ceiling != BitrateCeiling.bps) {
            BitrateCeiling.bps = ceiling
            if (ceiling != null) clampEncoder(ceiling) else restoreEncoder()
        }
    }

    private suspend fun apply(from: Int, to: Int, steps: HeatConfig) {
        applyLimits(to, steps)

        if (to >= 3 && from < 3 && composition.isCompositionActive) {
            sources.disableComposition()
                .onSuccess {
                    compositionOffForHeat = true
                    journal.event("heat", "composition off")
                }
        }
        if (to < 3 && from >= 3 && compositionOffForHeat) {
            compositionOffForHeat = false
            sources.enableComposition().onSuccess { journal.event("heat", "composition back on") }
        }
        if (to >= 2 && (from < 2 || from >= 3)) {
            composition.pauseCamerasForHeat()
                .onSuccess { if (it.isNotEmpty()) journal.event("heat", "cameras paused: $it") }
        }
        if (to < 2 && from >= 2) {
            composition.resumeCamerasAfterHeat()
            journal.event("heat", "cameras resumed")
        }

        tell(
            if (to == 0) "Phone has cooled down: back to full quality"
            else "Heat step $to (battery ${steps.stepsC[to - 1]} °C): ${steps.describe(to)}"
        )
    }

    /** The cap on every camera in use, or the configured rate back. */
    private suspend fun applyFps() {
        val fps = fpsCap ?: configuredFps()
        cameras().forEach { camera ->
            runCatching { camera.settings.setFrameRate(fps) }
                .onFailure { Log.w(TAG, "Could not set camera ${camera.cameraId} to $fps fps: ${it.message}") }
        }
    }

    /** The cameras running now; one that is not is left alone. */
    private fun cameras(): List<ICameraSource> = com.dimadesu.lifestreamer.sources.camerasIn(videoSource()).filter { (it as? io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal)?.isStreamingFlow?.value == true }

    private companion object {
        const val TAG = "HeatGuard"
        const val TICK_MS = 10_000L
    }
}
