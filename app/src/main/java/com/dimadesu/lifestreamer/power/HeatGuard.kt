package com.dimadesu.lifestreamer.power

import android.util.Log
import com.dimadesu.lifestreamer.bitrate.BitrateCeiling
import com.dimadesu.lifestreamer.composition.CompositionController
import com.dimadesu.lifestreamer.diagnostics.DiagnosticsLog
import com.dimadesu.lifestreamer.sources.SourceController
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.ICompositeVideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gives up what costs the most heat, in steps, before Samsung's own guard closes the app (it did
 * on 2026-10-05, with the battery at 55 °C and the live gone). The operator chose what may go:
 *
 * 1. (battery ≥ 44 °C) the cameras at 24 fps, the bitrate at most 4 Mbps, the preview off;
 * 2. (≥ 47 °C) every camera layer but the 🔊 one shows "Celular quente" instead, 20 fps, 3 Mbps;
 * 3. (≥ 50 °C) the composition off: one source.
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
) {
    private val ladder = HeatLadder()
    private val mutex = Mutex()

    private val _rung = MutableStateFlow(0)

    /** The step in force, 0 when cool; the preview follows it (see ThermalPolicy). */
    val rung: StateFlow<Int> = _rung.asStateFlow()

    /** A battery reading to use instead of the real one, for the bench (debug builds only). */
    @Volatile
    var batteryOverrideC: Float? = null

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
        val from = _rung.value
        val to = if (isEnabled()) ladder.update(batteryC, System.currentTimeMillis()) else 0
        if (to == from) {
            // A camera opened since (a source switch) starts at its configured rate
            if (fpsCap != null) applyFps()
            return@withLock
        }
        _rung.value = to
        journal.event("heat", "step $from -> $to batC=$batteryC${if (batteryOverrideC != null) " (override)" else ""}")
        Log.i(TAG, "Heat step $from -> $to at $batteryC °C")
        apply(from, to)
    }

    private suspend fun apply(from: Int, to: Int) {
        fpsCap = when {
            to >= 2 -> FPS_HOT
            to >= 1 -> FPS_WARM
            else -> null
        }
        applyFps()

        val ceiling = when {
            to >= 2 -> CEILING_HOT_BPS
            to >= 1 -> CEILING_WARM_BPS
            else -> null
        }
        BitrateCeiling.bps = ceiling
        if (ceiling != null) clampEncoder(ceiling) else restoreEncoder()

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
            when (to) {
                0 -> "Phone has cooled down: back to full quality"
                1 -> "Phone is warm: ${FPS_WARM} fps, at most ${CEILING_WARM_BPS / 1_000_000} Mbps, preview off"
                2 -> "Phone is hot: second camera paused, ${FPS_HOT} fps, at most ${CEILING_HOT_BPS / 1_000_000} Mbps"
                else -> "Phone is very hot: composition off to keep the live going"
            }
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
    private fun cameras(): List<ICameraSource> = when (val source = videoSource()) {
        is ICameraSource -> listOf(source)
        is ICompositeVideoSource -> source.layoutFlow.value.layers.mapNotNull { source.childSource(it.id) as? ICameraSource }
        else -> emptyList()
    }.filter { (it as? io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal)?.isStreamingFlow?.value == true }

    private companion object {
        const val TAG = "HeatGuard"
        const val TICK_MS = 10_000L
        const val FPS_WARM = 24
        const val FPS_HOT = 20
        const val CEILING_WARM_BPS = 4_000_000
        const val CEILING_HOT_BPS = 3_000_000
    }
}
