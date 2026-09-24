package com.dimadesu.lifestreamer.camera

import android.content.Context
import android.util.Log
import com.dimadesu.lifestreamer.composition.CompositionController
import com.dimadesu.lifestreamer.composition.ZoomState
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.ICompositeVideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/**
 * The controls of every camera in use (each camera layer of a composition, or the one camera),
 * whoever changes them: the phone's buttons and panel, or the remote page.
 *
 * Lives in the service, so the page can set a camera with the app's screen gone. It keeps what
 * the operator wants per physical camera ([CameraControlStore]) and applies it whenever that
 * camera turns on: a composition switched on or off, a layer given another camera, a live started
 * with the preview off. Values are never read back from the camera: before it opens they cannot
 * be, and what the operator asked for is steadier to show than what a camera reports.
 */
class CameraControlManager(
    private val context: Context,
    parentScope: CoroutineScope,
    /** The streamer's video source, as it changes. */
    private val videoSourceFlow: () -> Flow<IVideoSource?>?,
    private val videoSource: () -> IVideoSource?,
    private val composition: CompositionController,
    /** The frame rate the cameras run at, which bounds a manual exposure. */
    private val fps: () -> Int,
) {
    data class CameraTargetState(
        /** A layer id in a composition, or [SINGLE] for the one camera. */
        val id: String,
        val label: String,
        val cameraKey: String,
        val kind: CameraKind,
        val active: Boolean,
        val controls: List<ControlDescriptor>,
        val values: CameraControlValues,
        val runtime: RuntimeControls,
        val zoom: ZoomState?,
    )

    data class State(val targets: List<CameraTargetState> = emptyList()) {
        fun target(id: String?): CameraTargetState? = targets.firstOrNull { it.id == id }
    }

    /** Everything about one camera in use, touched on the manager's thread only. */
    private class Target(val backend: CameraBackend) {
        var id: String = SINGLE
        var runtime = RuntimeControls()
        val applyRequests = Channel<Unit>(Channel.CONFLATED)

        /** The next apply is the first since the camera opened. */
        @Volatile
        var settle = false
        var jobs: List<Job> = emptyList()
    }

    private var managerThread: Thread? = null
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            r.run()
        }, "CameraControls").apply {
            isDaemon = true
            managerThread = this
        }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(parentScope.coroutineContext + dispatcher)

    private val store = CameraControlStore(context)

    /** Remembered values by camera key, loaded on first use. */
    private val values = mutableMapOf<String, CameraControlValues>()
    private val saveJobs = mutableMapOf<String, Job>()

    /** Targets by source object: a source keeps its target (and its watchers) while it lives. */
    private val targets = IdentityHashMap<Any, Target>()

    /** In the order they are listed. */
    private var ordered: List<Target> = emptyList()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        val sources = videoSourceFlow() ?: return
        scope.launch {
            // What can change which cameras are in use: the source itself, a layer's source swapped
            // (the composite does not announce that), or the set of layers. Not every drag.
            merge(
                sources,
                composition.sourcesVersion,
                sources.flatMapLatest { source ->
                    (source as? ICompositeVideoSource)?.layoutFlow
                        ?.map { layout -> layout.layers.map { it.id }.toSet() }
                        ?.distinctUntilChanged()
                        ?: emptyFlow()
                },
            ).conflate().collect { refreshTargets() }
        }
    }

    // region targets

    /** The cameras in use and the target id of each, in the layout's order (stable across swaps). */
    private fun enumerate(): List<Pair<String, Any>> {
        val source = videoSource()
        if (source is ICompositeVideoSource) {
            return source.layoutFlow.value.layers.mapNotNull { layer ->
                (source.childSource(layer.id) as? ICameraSource)?.let { layer.id to it }
            }
        }
        return listOfNotNull((source as? ICameraSource)?.let { SINGLE to it })
    }

    private fun refreshTargets() {
        val found = runCatching { enumerate() }.getOrElse {
            Log.w(TAG, "Could not list the cameras in use: ${it.message}")
            return
        }
        val alive = found.map { it.second }.toSet()
        // Gone: stop watching them
        targets.keys.filter { key -> alive.none { it === key } }.forEach { key ->
            targets.remove(key)?.jobs?.forEach { it.cancel() }
        }
        ordered = found.mapNotNull { (id, source) ->
            val target = targets[source] ?: backendFor(source)?.let { Target(it) }?.also {
                targets[source] = it
                watch(it)
            }
            target?.also { it.id = id }
        }
        publish()
    }

    private fun backendFor(source: Any): CameraBackend? = when (source) {
        is ICameraSource -> Camera2Backend(source)
        else -> null
    }

    private fun watch(target: Target) {
        target.jobs = listOf(
            scope.launch {
                target.backend.isActiveFlow.collect { active ->
                    if (active) {
                        // A new session: the torch is off, no focus is held
                        target.runtime = RuntimeControls()
                        target.settle = true
                        target.applyRequests.trySend(Unit)
                        Log.i(TAG, "${target.id}: ${target.backend.cameraKey} on, applying ${valuesOf(target.backend.cameraKey)}")
                    }
                    publish()
                }
            },
            scope.launch {
                for (request in target.applyRequests) applyNow(target)
            },
        )
    }

    private suspend fun applyNow(target: Target) {
        val backend = target.backend
        if (!backend.isActiveFlow.value) return
        val settle = target.settle
        target.settle = false
        runCatching {
            when (backend) {
                is Camera2Backend -> {
                    val plan = CameraControlRules.resolveCamera2(
                        valuesOf(backend.cameraKey), target.runtime, backend.caps, fps(), concurrent()
                    )
                    backend.apply(plan, settle)
                }
            }
        }.onFailure { Log.w(TAG, "Could not apply to ${target.id}: ${it.message}") }
    }

    /** Another camera runs alongside, which rules out video stabilization. */
    private fun concurrent() = ordered.count { it.backend is Camera2Backend } > 1

    // endregion

    // region values

    private fun valuesOf(cameraKey: String): CameraControlValues =
        values.getOrPut(cameraKey) { store.load(cameraKey) }

    private fun setValues(target: Target, next: CameraControlValues) {
        val key = target.backend.cameraKey
        values[key] = next
        saveJobs.remove(key)?.cancel()
        saveJobs[key] = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            store.save(key, next)
        }
    }

    /**
     * The target [id] names: a layer, [SINGLE], or null for the main one (the primary layer, or
     * the one camera). A layer id without a composition, as an older page sends, is the camera.
     */
    private fun resolve(id: String?): Target? {
        ordered.firstOrNull { it.id == id }?.let { return it }
        val primary = composition.composite?.layoutFlow?.value?.primaryLayer?.id
        return ordered.firstOrNull { it.id == primary } ?: ordered.firstOrNull()?.takeIf {
            id == null || composition.composite == null
        }
    }

    private suspend fun <T> onManager(block: () -> T): T =
        if (Thread.currentThread() === managerThread) block() else withContext(dispatcher) { block() }

    /**
     * Changes [key] of [targetId]'s camera to [raw] (a number, string or boolean from a screen or
     * the page). Refused with the reason when the camera cannot do it. The camera itself is set
     * when it is on; otherwise the value waits for it.
     */
    suspend fun set(targetId: String?, key: String, raw: Any?): Result<Unit> {
        val (target, startsManual) = onManager {
            resolve(targetId)?.let { it to (key in MANUAL_KEYS && valuesOf(it.backend.cameraKey).manualExposure != true) }
        } ?: return Result.failure(NoSuchElementException("No camera ${targetId ?: ""}".trim()))
        // Starting manual exposure from what auto-exposure is doing needs a frame: read it first,
        // without holding the manager's thread
        val reading = (target.backend as? Camera2Backend)
            ?.takeIf { startsManual && it.isActiveFlow.value }
            ?.exposureReading()
        return onManager {
            when (key) {
                ControlKeys.TORCH -> changeRuntime(target) { it.copy(torch = raw == true || raw == "true") }
                ControlKeys.FOCUS_RESUME -> changeRuntime(target) { it.copy(tapFocus = null) }
                ControlKeys.RESET -> {
                    reset(target)
                    Result.success(Unit)
                }
                else -> changeValues(target) { current ->
                    when (val backend = target.backend) {
                        is Camera2Backend ->
                            CameraControlRules.applyChange(current, key, raw, backend.caps, fps(), reading)
                    }
                }.onSuccess {
                    // Choosing a focus mode releases a tapped focus
                    if (key == ControlKeys.AF_MODE || key == ControlKeys.LENS) {
                        target.runtime = target.runtime.copy(tapFocus = null)
                    }
                }
            }
        }
    }

    /** The next focus or white-balance mode, for the phone's quick buttons. */
    suspend fun cycle(targetId: String?, key: String): Result<Unit> = onManager {
        val target = resolve(targetId) ?: return@onManager Result.failure(NoSuchElementException("No camera"))
        changeValues(target) { current ->
            when (val backend = target.backend) {
                is Camera2Backend -> CameraControlRules.cycle(current, key, backend.caps, fps())
            }
        }
    }

    fun setZoom(targetId: String?, ratio: Float) {
        scope.launch { set(targetId, ControlKeys.ZOOM, ratio) }
    }

    fun nudgeZoom(targetId: String?, factor: Float) {
        scope.launch {
            val target = resolve(targetId) ?: return@launch
            changeValues(target) { current ->
                when (val backend = target.backend) {
                    is Camera2Backend -> CameraControlRules.nudgeZoom(current, factor, backend.caps, fps())
                }
            }
        }
    }

    /** Called when the cameras' frame rate changes: a manual exposure may no longer fit a frame. */
    fun onFrameRateChanged() {
        scope.launch {
            ordered.forEach { it.applyRequests.trySend(Unit) }
            publish()
        }
    }

    private fun changeValues(
        target: Target,
        change: (CameraControlValues) -> Result<CameraControlValues>,
    ): Result<Unit> {
        val current = valuesOf(target.backend.cameraKey)
        return change(current).map { next ->
            if (next != current) {
                setValues(target, next)
                target.applyRequests.trySend(Unit)
                publish()
            }
        }
    }

    private fun changeRuntime(target: Target, change: (RuntimeControls) -> RuntimeControls): Result<Unit> {
        val next = change(target.runtime)
        if (next != target.runtime) {
            target.runtime = next
            target.applyRequests.trySend(Unit)
            publish()
        }
        return Result.success(Unit)
    }

    private fun reset(target: Target) {
        val key = target.backend.cameraKey
        values[key] = CameraControlValues()
        saveJobs.remove(key)?.cancel()
        store.save(key, CameraControlValues())
        target.runtime = target.runtime.copy(tapFocus = null)
        target.applyRequests.trySend(Unit)
        publish()
        Log.i(TAG, "${target.id}: $key back to automatic")
    }

    // endregion

    // region state

    private fun label(target: Target): String {
        val name = when (val backend = target.backend) {
            is Camera2Backend -> composition.displayName(backend.cameraId)
        }
        if (target.id == SINGLE) return name
        val position = composition.positionName(target.id).replaceFirstChar { it.uppercase() }
        return "$position · $name"
    }

    private fun publish() {
        _state.value = State(ordered.map { target ->
            val backend = target.backend
            val current = valuesOf(backend.cameraKey)
            when (backend) {
                is Camera2Backend -> {
                    val caps = backend.caps
                    val sanitized = CameraControlRules.sanitize(current, caps, fps())
                    CameraTargetState(
                        id = target.id,
                        label = label(target),
                        cameraKey = backend.cameraKey,
                        kind = backend.kind,
                        active = backend.isActiveFlow.value,
                        controls = CameraControlRules.describeCamera2(caps, current, target.runtime, fps()),
                        values = sanitized,
                        runtime = target.runtime,
                        zoom = if (caps.zoomMax > caps.zoomMin) {
                            ZoomState(caps.zoomMin, caps.zoomMax, (sanitized.zoom ?: 1f).coerceIn(caps.zoomMin, caps.zoomMax))
                        } else null,
                    )
                }
            }
        })
    }

    /** The zoom of [layerId]'s camera, for the layers the page draws; known before it opens. */
    fun zoomState(layerId: String): ZoomState? = _state.value.target(layerId)?.zoom

    // endregion

    companion object {
        private const val TAG = "CameraControls"

        /** The target id of the one camera when there is no composition. */
        const val SINGLE = "camera"

        private const val SAVE_DEBOUNCE_MS = 500L

        private val MANUAL_KEYS = setOf(ControlKeys.EXPOSURE_MODE, ControlKeys.ISO, ControlKeys.SHUTTER)
    }
}
