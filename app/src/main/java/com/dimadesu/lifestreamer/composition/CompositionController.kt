/*
 * Copyright (C) 2026 dimadesu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.composition

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Looper
import android.util.Log
import android.util.Size
import android.util.SizeF
import androidx.media3.exoplayer.ExoPlayer
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.sources.SourceChoice
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionPresets
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerRect
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerScaleMode
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayoutPreset
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.applyPreset
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.swapLayerOrder
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.CompositeVideoSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.ICompositeVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.LayerSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

data class CameraInfo(val id: String, val displayName: String, val facing: String)

data class ZoomState(val min: Float, val max: Float, val ratio: Float)

/**
 * Owns everything about a running composition that is not UI.
 *
 * It lives in the foreground service rather than the ViewModel because there are now two
 * consumers with different lifetimes: the on-device UI, which dies with the Activity, and the
 * remote control server, which must keep working precisely when it has — phone mounted, screen
 * off, Activity destroyed.
 *
 * Duplicating the rules into the server instead was the alternative, and the rules are not
 * incidental: [CompositionCapabilities.canRunTogether] exists so an impossible camera pairing is
 * refused *before* camera2 fails to open mid-stream, the concurrent resolution cap has to apply
 * to the whole pair, and snapping plus the debounced save are what make the on-device drag and
 * the remote drag agree. Two copies of that would drift in silence, and the drift shows up as a
 * broken stream.
 */
class CompositionController(
    private val context: Context,
    parentScope: CoroutineScope,
    private val videoSourceProvider: () -> IVideoSource?,
    /**
     * Replaces the whole video source. Supplied by the service, which owns the streamer; this is
     * what lets a composition be turned on and off without the app's screen.
     */
    private val videoSourceSwitcher: (suspend (IVideoSourceInternal.Factory) -> Unit)? = null,
    /** URL and playback buffer of RTMP source N (1-based), or null when it has no URL. */
    private val rtmpConfig: suspend (Int) -> Pair<String, Int>? = { null }
) {
    /**
     * The one thread every mutation and every camera read runs on.
     *
     * Single-threaded on purpose: it is what makes the unguarded read-modify-writes in the
     * composite source safe with two writers (the UI and the remote control). Kept off the main
     * thread so a busy UI cannot delay a remote command, and below video and audio priority so it
     * can never compete with what goes on air.
     */
    private var confinementThread: Thread? = null

    private val confinementExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            r.run()
        }, "CompositionController").apply {
            isDaemon = true
            confinementThread = this
        }
    }

    private val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + confinementExecutor.asCoroutineDispatcher())

    private val capabilities = CompositionCapabilities(context)
    private val store = CompositionStore(context)

    private val sources = CompositionSources(context.applicationContext as android.app.Application)

    private val saved = store.load()

    /**
     * What each layer is meant to show, by layer id: persisted, and applied live when it changes.
     * A layer showing the test image because its source is missing keeps its choice here, with
     * the reason in [placeholders].
     */
    private val _layerChoices = MutableStateFlow(
        saved?.sources.orEmpty()
            .mapNotNull { (id, key) -> SourceChoice.parse(key)?.let { id to it } }
            .toMap()
    )
    val layerChoices: StateFlow<Map<String, SourceChoice>> = _layerChoices.asStateFlow()

    /** Schema 2 saved only the second layer's source, as a kind: read when there is nothing newer. */
    private val legacyPipKind: String? = saved?.pipSourceName?.takeIf { saved.sources.isEmpty() }

    private val _placeholders = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The layers showing the test image in place of their source, and why: it could not be built
     * (a permission to accept on the phone, no URL), or it died (an RTMP feed that stopped, a USB
     * camera unplugged). Stops a failure from replacing a placeholder with another placeholder.
     */
    val placeholders: StateFlow<Map<String, String>> = _placeholders.asStateFlow()

    fun placeholderReason(layerId: String): String? = _placeholders.value[layerId]

    /** The layer meant to show [choice], if any. */
    fun layerWith(choice: SourceChoice): String? =
        _layerChoices.value.entries.firstOrNull { it.value == choice }?.key

    /** The feeds of the RTMP layers, which own their players. */
    private val feeds = java.util.concurrent.ConcurrentHashMap<String, RtmpLayerFeed>()

    /** Camera layers opened at the size two cameras can share. */
    private val cappedLayers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Registered by the app's screen while it is alive; builds the sources only it can build. */
    @Volatile
    var externalSources: ExternalLayerSources? = null

    /** Structural changes (on/off, source swaps) are serialised: two at once would fight over cameras. */
    private val structuralMutex = kotlinx.coroutines.sync.Mutex()

    private var failureJob: kotlinx.coroutines.Job? = null
    private var observedComposite: ICompositeVideoSource? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Things the operator needs to be told. The ViewModel turns these into toasts; the server
     * pushes them to the page, so a remote tap that was refused says why instead of doing
     * nothing.
     */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Tells the operator [message], on the phone and the page. */
    fun tell(message: String) {
        _messages.tryEmit(message)
    }

    private val _layersInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /**
     * Fires when something a layer *shows* changed without the layout itself changing.
     *
     * Swapping a camera deliberately reuses the same VideoLayer so the rectangle survives, which
     * means the new layout compares equal and the StateFlow never emits. Labels and zoom are
     * derived from the live source, so without this signal both the on-device bar and the remote
     * page keep the previous camera's name indefinitely.
     */
    val layersInvalidated: SharedFlow<Unit> = _layersInvalidated.asSharedFlow()

    private val _cameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val cameras: StateFlow<List<CameraInfo>> = _cameras.asStateFlow()

    private val _sourcesVersion = MutableStateFlow(0L)

    /**
     * Moves on whenever a layer's source is replaced. The composite does that silently (the layout
     * is unchanged by design), and the camera controls need to know a new camera is in a layer.
     */
    val sourcesVersion: StateFlow<Long> = _sourcesVersion.asStateFlow()

    private fun sourcesChanged() {
        _sourcesVersion.value++
    }

    val presets: List<LayoutPreset> get() = CompositionPresets.ALL

    val composite: ICompositeVideoSource?
        get() = videoSourceProvider() as? ICompositeVideoSource

    val layout: CompositionLayout? get() = composite?.layoutFlow?.value

    val isCompositionActive: Boolean get() = composite != null

    init {
        scope.launch { loadCameras() }
    }

    // region thread confinement

    /**
     * Confines every mutation to one thread.
     *
     * [ICompositeVideoSource.updateLayer] is an unguarded read-modify-write and `childSource`
     * reads the children map outside the mutex that otherwise guards it. That was safe while the
     * UI thread was the only writer; the remote control is the second one.
     *
     * That thread used to be the **main** thread, which is the worst possible choice here: while
     * streaming the main thread carries the notification updater, the thermal ticks and the whole
     * UI, and it is raised to priority -19 when a stream starts. Every remote command was acked
     * instantly by the HTTP handler and then queued behind all of that, which is exactly what
     * "the commands do not arrive" felt like. It is now a private single thread, so a command
     * takes effect whether or not the UI is busy.
     */
    private fun confined(block: () -> Unit) {
        if (Thread.currentThread() === confinementThread) {
            block()
        } else {
            scope.launch { block() }
        }
    }

    // endregion

    // region geometry — safe at gesture rate, never touches a source

    fun setLayerRect(layerId: String, rect: LayerRect) = confined {
        composite?.updateLayer(layerId) { it.copy(rect = rect) }
    }

    /**
     * Ends a drag: snaps to the canvas edges and centre, then persists.
     *
     * Snapping lives here rather than in either editor so the on-device drag and the remote drag
     * cannot disagree about where a layer ends up.
     */
    fun commitLayerGeometry(layerId: String) = confined {
        composite?.updateLayer(layerId) { layer -> layer.copy(rect = snapped(layer.rect)) }
        scheduleSave()
    }

    fun applyPreset(presetId: String) = confined {
        val target = composite ?: return@confined
        val preset = CompositionPresets.ALL.firstOrNull { it.id == presetId } ?: return@confined
        target.updateLayout(target.layoutFlow.value.applyPreset(preset))
        scheduleSave()
        Log.i(TAG, "Applied layout ${preset.name}")
    }

    fun swapLayers() = confined {
        val target = composite ?: return@confined
        val layers = target.layoutFlow.value.layers.sortedBy { it.z }
        if (layers.size < 2) {
            _messages.tryEmit("Nothing to swap yet")
            return@confined
        }
        target.updateLayout(
            target.layoutFlow.value.swapLayerOrder(layers.first().id, layers.last().id)
        )
        scheduleSave()
    }

    fun setLayerVisible(layerId: String, visible: Boolean) = confined {
        composite?.updateLayer(layerId) { it.copy(visible = visible) }
        scheduleSave()
    }

    fun setPrimaryLayer(layerId: String) = confined {
        composite?.setPrimaryLayer(layerId)
        applyFeedAudio()
        scheduleSave()
    }

    private fun snapped(rect: LayerRect): LayerRect {
        val width = rect.width
        val height = rect.height
        var left = rect.left
        var top = rect.top

        if (left < SNAP_THRESHOLD) left = 0f
        if (top < SNAP_THRESHOLD) top = 0f
        if (left + width > 1f - SNAP_THRESHOLD) left = 1f - width
        if (top + height > 1f - SNAP_THRESHOLD) top = 1f - height
        if (abs(left + width / 2f - 0.5f) < SNAP_THRESHOLD) left = 0.5f - width / 2f
        if (abs(top + height / 2f - 0.5f) < SNAP_THRESHOLD) top = 0.5f - height / 2f

        return LayerRect(left, top, left + width, top + height)
    }

    // endregion

    // region structural — opens devices, can fail

    /** Points one layer at a different camera, keeping its rectangle and depth. */
    suspend fun setLayerCamera(layerId: String, cameraId: String): Result<Unit> =
        setLayerSource(layerId, SourceChoice.Camera(cameraId))

    /**
     * Makes [layerId] show [choice], keeping where it is, its size and its style. A source that
     * cannot be built right now (a USB camera or the screen waiting for a permission, an RTMP
     * source without a URL) leaves the test image in its place, saying why.
     *
     * Refuses what the hardware cannot do *before* trying -- two cameras that cannot run
     * together, one source in two layers -- because letting camera2 fail to open leaves the
     * composition half-built in the middle of a live stream.
     */
    suspend fun setLayerSource(layerId: String, choice: SourceChoice): Result<Unit> = structural {
        val target = composite ?: error("No composition")
        val layer = target.layoutFlow.value[layerId]
            ?: throw IllegalArgumentException("No layer $layerId")
        refusal(layerId, choice)?.let { message ->
            _messages.tryEmit(message)
            Log.w(TAG, "Refused $choice for layer $layerId: $message")
            throw IllegalStateException(message)
        }
        if (_layerChoices.value[layerId] == choice && layerId !in _placeholders.value) return@structural
        apply(target, layerId, layer, choice)
        _messages.tryEmit("${choiceLabel(choice)} -> ${positionName(layerId)}")
        Log.i(TAG, "Layer $layerId now shows $choice" +
                (placeholderReason(layerId)?.let { " (placeholder: $it)" } ?: ""))
    }

    /** Why [choice] cannot go in [layerId] next to what the other layers show, or null. */
    private fun refusal(layerId: String, choice: SourceChoice): String? {
        if (choice == SourceChoice.TestImage) return null
        val others = _layerChoices.value.filterKeys { it != layerId && composite?.layoutFlow?.value?.get(it) != null }
        if (choice in others.values) return "${choiceLabel(choice)} is already in another layer"
        if (choice is SourceChoice.Camera) {
            others.values.filterIsInstance<SourceChoice.Camera>()
                .firstOrNull { !capabilities.canRunTogether(choice.id, it.id) }
                ?.let {
                    return "${displayName(choice.id)} cannot run at the same time as " +
                            "${displayName(it.id)} on this device"
                }
        }
        return null
    }

    /** Builds [choice] into [layer] and puts it in. Under the structural lock. */
    private suspend fun apply(
        target: ICompositeVideoSource,
        layerId: String,
        layer: VideoLayer,
        choice: SourceChoice
    ) {
        val cap = if (choice is SourceChoice.Camera) capForCameraIn(target, layerId) else null
        val feed = (choice as? SourceChoice.Rtmp)?.let { newFeed(layerId, it.index) }
        val build = sources.layerSpec(choice, layer, externalSources, cap) { feed?.open() }
        val previousFeed = feeds.remove(layerId)
        target.replaceLayerSource(layerId, build.spec)
        // Only now: the old source let go of its player when it was replaced
        previousFeed?.release()
        if (feed != null) {
            if (build.placeholderReason == null) feeds[layerId] = feed else feed.release()
        }
        if (cap != null) cappedLayers.add(layerId) else cappedLayers.remove(layerId)
        _layerChoices.value = _layerChoices.value + (layerId to choice)
        setPlaceholder(layerId, build.placeholderReason)
        build.placeholderReason?.let { _messages.tryEmit(it) }
        applyFeedAudio()
        scheduleSave()
        // The layout is unchanged by design (the rectangle survives a source swap), so the
        // layoutFlow will not emit. Everything derived from the live source -- the label on the
        // bar, the chip on the page, the camera controls -- needs this to be told.
        sourcesChanged()
        _layersInvalidated.tryEmit(Unit)
    }

    /**
     * The capture size for a camera going into [layerId]: capped when another layer shows a
     * camera, and that one is reopened capped too if it was not -- both cameras of a pair must
     * stay inside the guaranteed configuration, or the second one fails to open.
     */
    private suspend fun capForCameraIn(target: ICompositeVideoSource, layerId: String): Size? {
        val otherCameras = _layerChoices.value.filter { (id, choice) ->
            id != layerId && choice is SourceChoice.Camera && id !in _placeholders.value &&
                    target.layoutFlow.value[id] != null
        }
        if (otherCameras.isEmpty()) return null
        val cap = capabilities.report().concurrentCameraMaxSize
        otherCameras.forEach { (id, choice) ->
            if (id in cappedLayers) return@forEach
            val layer = target.layoutFlow.value[id] ?: return@forEach
            target.replaceLayerSource(
                id, LayerSpec(layer, CameraSourceFactory((choice as SourceChoice.Camera).id), cap)
            )
            cappedLayers.add(id)
            Log.i(TAG, "Layer $id reopened at ${cap.width}x${cap.height} to pair with another camera")
        }
        return cap
    }

    private fun newFeed(layerId: String, index: Int): RtmpLayerFeed {
        lateinit var feed: RtmpLayerFeed
        feed = RtmpLayerFeed(
            application = context.applicationContext as android.app.Application,
            scope = scope,
            index = index,
            config = rtmpConfig,
            onDown = { reason -> degradeLayerToPlaceholder(layerId, reason) },
            onUp = { player -> restoreFeed(layerId, feed, player) },
        )
        return feed
    }

    /** An RTMP layer's feed plays again: its player goes back in, unless the layer moved on. */
    private suspend fun restoreFeed(layerId: String, feed: RtmpLayerFeed, player: ExoPlayer) {
        structural {
            val target = composite
            val layer = target?.layoutFlow?.value?.get(layerId)
            if (target == null || layer == null || feeds[layerId] !== feed) {
                feed.release()
                return@structural
            }
            target.replaceLayerSource(layerId, LayerSpec(layer, RTMPVideoSource.Factory(player)))
            setPlaceholder(layerId, null)
            applyFeedAudio()
            _messages.tryEmit("RTMP ${feed.index} is back")
            sourcesChanged()
            _layersInvalidated.tryEmit(Unit)
        }
    }

    /**
     * Puts a source that arrives later -- a USB camera done opening, a screen-capture grant --
     * in the layer meant to show it.
     */
    suspend fun attachDeferredSource(
        choice: SourceChoice,
        factory: IVideoSourceInternal.Factory
    ): Result<Unit> = structural {
        val target = composite ?: return@structural
        val layerId = layerWith(choice) ?: return@structural
        val layer = target.layoutFlow.value[layerId] ?: return@structural
        target.replaceLayerSource(layerId, LayerSpec(layer, factory))
        setPlaceholder(layerId, null)
        sourcesChanged()
        _layersInvalidated.tryEmit(Unit)
        Log.i(TAG, "Layer $layerId now shows $choice")
    }

    /**
     * Falls back to the test image in [layerId], keeping the stream going. [reason] is told to
     * the operator, and shown until the source is back.
     */
    suspend fun degradeLayerToPlaceholder(layerId: String, reason: String): Result<Unit> = structural {
        val target = composite ?: return@structural
        val layer = target.layoutFlow.value[layerId] ?: return@structural
        if (layerId in _placeholders.value) return@structural
        if (_layerChoices.value[layerId] == SourceChoice.TestImage) return@structural
        target.replaceLayerSource(layerId, sources.placeholderSpec(layer))
        cappedLayers.remove(layerId)
        setPlaceholder(layerId, reason)
        _messages.tryEmit(reason)
        sourcesChanged()
        _layersInvalidated.tryEmit(Unit)
        Log.i(TAG, "Layer $layerId degraded to the placeholder: $reason")
    }

    /** The same for the layer showing [choice], from a place that cannot wait. */
    fun degradeLater(choice: SourceChoice, reason: String) {
        val layerId = layerWith(choice) ?: return
        scope.launch { degradeLayerToPlaceholder(layerId, reason) }
    }

    private fun setPlaceholder(layerId: String, reason: String?) {
        _placeholders.value =
            if (reason == null) _placeholders.value - layerId
            else _placeholders.value + (layerId to reason)
    }

    /** The player of the 🔊 layer when it is an RTMP source: what the audio monitor plays. */
    fun primaryFeedPlayer(): ExoPlayer? = layout?.primaryLayer?.id?.let { feeds[it]?.player }

    /** Only the 🔊 layer's RTMP feed keeps its sound; see [RtmpLayerFeed.setAudioEnabled]. */
    private fun applyFeedAudio() {
        val primary = layout?.primaryLayer?.id
        feeds.forEach { (id, feed) -> feed.setAudioEnabled(id == primary) }
    }

    // endregion

    // region composition lifecycle — replaces the whole video source

    /**
     * The camera the main layer should use: what is on screen now if it is a camera, else a hint
     * the app leaves (the last camera the operator picked), else the first camera.
     */
    @Volatile
    var primaryCameraHint: String? = null

    private fun primaryCameraId(): String =
        (videoSourceProvider() as? ICameraSource)?.cameraId
            ?: (composite?.childSource(CompositionLayers.MAIN) as? ICameraSource)?.cameraId
            ?: primaryCameraHint
            ?: _cameras.value.firstOrNull()?.id
            ?: "0"

    /**
     * Turns the composition on. The bottom layer shows what is on screen now ([onScreen], else
     * the camera); the other one what it showed last time, unless that cannot run next to it --
     * then another camera, or the test image.
     *
     * Moved here from PreviewViewModel.toggleCompositeSource so the service -- and so the remote
     * control -- can do it with the app's screen gone. Switching the video source while streaming
     * does not reconnect: the encoder is kept and the picture freezes briefly.
     */
    suspend fun enableComposition(onScreen: SourceChoice? = null): Result<Unit> = structural {
        val switcher = videoSourceSwitcher ?: error("Cannot switch the video source from here")
        if (composite != null) return@structural
        val bottom = onScreen ?: SourceChoice.Camera(primaryCameraId())
        val (bottomId, otherId) = upcomingLayers()
        val choices = mapOf(bottomId to bottom, otherId to compatible(remembered(otherId, bottom), bottom))
        val cap = if (choices.values.all { it is SourceChoice.Camera }) {
            capabilities.report().concurrentCameraMaxSize
        } else {
            null
        }

        val newFeeds = mutableMapOf<String, RtmpLayerFeed>()
        val builds = choices.mapValues { (id, choice) ->
            val feed = (choice as? SourceChoice.Rtmp)?.let { newFeed(id, it.index) }
            sources.layerSpec(
                choice, sources.defaultLayer(id), externalSources,
                cap.takeIf { choice is SourceChoice.Camera }
            ) { feed?.open() }.also { build ->
                if (feed != null) {
                    if (build.placeholderReason == null) newFeeds[id] = feed else feed.release()
                }
            }
        }
        try {
            switcher(CompositeVideoSourceFactory(builds.values.map { it.spec }))
        } catch (t: Throwable) {
            newFeeds.values.forEach { it.release() }
            throw t
        }
        feeds.values.forEach { it.release() }
        feeds.clear()
        feeds.putAll(newFeeds)
        cappedLayers.clear()
        if (cap != null) cappedLayers.addAll(choices.keys)
        _layerChoices.value = choices
        _placeholders.value = builds.mapNotNull { (id, build) -> build.placeholderReason?.let { id to it } }.toMap()
        composite?.let { onCompositionAppeared(it) }
        restoreSaved()
        applyFeedAudio()
        scheduleSave()
        sourcesChanged()
        _placeholders.value.values.distinct().forEach { _messages.tryEmit(it) }
        Log.i(TAG, "Composition on: $choices")
    }

    /**
     * The layer at the bottom and the other one, the next time the composition is turned on:
     * they come back where the operator left them, a swap included.
     */
    fun upcomingLayers(): Pair<String, String> {
        val depths = store.load()?.depths.orEmpty()
        return if ((depths[CompositionLayers.PIP] ?: 1) < (depths[CompositionLayers.MAIN] ?: 0)) {
            CompositionLayers.PIP to CompositionLayers.MAIN
        } else {
            CompositionLayers.MAIN to CompositionLayers.PIP
        }
    }

    /** What [layerId] will show when the composition is turned on; checked by the caller. */
    fun presetLayerSource(layerId: String, choice: SourceChoice) {
        _layerChoices.value = _layerChoices.value + (layerId to choice)
        val choices = _layerChoices.value
        store.saveSources(choices.mapValues { it.value.key }, SourceChoice.legacyKind(choices[CompositionLayers.PIP]))
        _messages.tryEmit("${choiceLabel(choice)} in the ${if (layerId == upcomingLayers().first) "main" else "inset"} layer when the composition is on")
    }

    /** What [layerId] showed last time; an old "second camera" is the one that pairs with [onScreen]. */
    private fun remembered(layerId: String, onScreen: SourceChoice): SourceChoice =
        _layerChoices.value[layerId]
            ?: legacyPipKind?.takeIf { layerId == CompositionLayers.PIP }?.let { kind ->
                SourceChoice.fromLegacyKind(kind) {
                    (onScreen as? SourceChoice.Camera)?.let { capabilities.secondCameraFor(it.id) }
                }
            }
            ?: SourceChoice.TestImage

    /** [wanted], or what can stand next to [onScreen] instead: another camera, or the test image. */
    private fun compatible(wanted: SourceChoice, onScreen: SourceChoice): SourceChoice {
        val bothCameras = wanted is SourceChoice.Camera && onScreen is SourceChoice.Camera
        val clash = wanted == onScreen ||
                (bothCameras && !capabilities.canRunTogether((wanted as SourceChoice.Camera).id, (onScreen as SourceChoice.Camera).id))
        if (!clash) return wanted
        if (onScreen is SourceChoice.Camera && wanted is SourceChoice.Camera) {
            return capabilities.secondCameraFor(onScreen.id)?.let { SourceChoice.Camera(it) }
                ?: SourceChoice.TestImage
        }
        return SourceChoice.TestImage
    }

    /**
     * Turns the composition off, back to a camera: the bottom layer's when it shows one, else the
     * last camera picked. Returns what the bottom layer showed when it was something else, for
     * the caller to carry on with: an RTMP source, USB or the screen as the whole picture need
     * the app's screen.
     */
    suspend fun disableComposition(): Result<SourceChoice?> = structural {
        val switcher = videoSourceSwitcher ?: error("Cannot switch the video source from here")
        val target = composite ?: return@structural null
        val bottomId = target.layoutFlow.value.layers.minByOrNull { it.z }?.id
        val bottom = bottomId?.let { _layerChoices.value[it] }
        val cameraId = (bottom as? SourceChoice.Camera)?.id
            ?: primaryCameraHint
            ?: _layerChoices.value.values.filterIsInstance<SourceChoice.Camera>().firstOrNull()?.id
            ?: primaryCameraId()
        saveJob?.cancel()
        saveNow()
        stopObservingFailures()
        switcher(CameraSourceFactory(cameraId))
        feeds.values.forEach { it.release() }
        feeds.clear()
        cappedLayers.clear()
        _placeholders.value = emptyMap()
        sourcesChanged()
        Log.i(TAG, "Composition off, back to camera $cameraId")
        bottom?.takeUnless { it is SourceChoice.Camera || it == SourceChoice.TestImage }
    }

    /** Whether two cameras can run at the same time on this phone. */
    fun canRunTogether(a: String, b: String): Boolean = capabilities.canRunTogether(a, b)

    /**
     * Wires failure handling to a composition, whoever created it -- the app, the page, or a
     * restored session. Idempotent per instance.
     *
     * This used to live in the ViewModel and died with it, so with the app's screen gone a layer
     * whose source failed (an RTMP feed that stopped, say) simply went black.
     */
    fun onCompositionAppeared(target: ICompositeVideoSource) {
        if (observedComposite === target && failureJob?.isActive == true) return
        stopObservingFailures()
        observedComposite = target
        failureJob = scope.launch {
            target.layerFailureFlow.collect { failure ->
                Log.w(TAG, "Layer ${failure.layerId} failed: ${failure.reason}")
                // An RTMP layer is tried again by its feed; the rest wait for the operator
                val feed = feeds[failure.layerId]
                if (feed != null) {
                    feed.reportTrouble(failure.reason)
                } else {
                    val name = _layerChoices.value[failure.layerId]?.let(::choiceLabel) ?: "The layer's source"
                    degradeLayerToPlaceholder(failure.layerId, "$name lost - showing placeholder")
                }
            }
        }
    }

    private fun stopObservingFailures() {
        failureJob?.cancel()
        failureJob = null
        observedComposite = null
    }

    private suspend fun <T> structural(block: suspend () -> T): Result<T> =
        structuralMutex.withLock { runCatching { block() } }
            .onFailure { Log.w(TAG, "Composition change failed: ${it.message}", it) }

    // endregion

    // region style — geometry only, safe while streaming

    /**
     * Changes how a layer is drawn. Null leaves that property as it is.
     *
     * Only what the compositor reads each frame: no source restarts and no encoder changes, so it
     * is safe on air. Changing the scale mode stops matching any preset, which is correct -- the
     * layout is no longer the preset's.
     */
    fun setLayerStyle(
        layerId: String,
        scaleMode: LayerScaleMode? = null,
        alpha: Float? = null,
        mirror: Boolean? = null,
        rotationDegrees: Int? = null
    ) = confined {
        composite?.updateLayer(layerId) {
            it.copy(
                scaleMode = scaleMode ?: it.scaleMode,
                // Zero removes the layer from drawing, which hiding already does; keep it visible.
                alpha = (alpha ?: it.alpha).coerceIn(MIN_ALPHA, 1f),
                mirror = mirror ?: it.mirror,
                rotationDegrees = rotationDegrees?.let { r -> ((r % 360) + 360) % 360 / 90 * 90 }
                    ?: it.rotationDegrees
            )
        }
        scheduleSave()
    }

    /** The colour behind the layers, shown wherever they do not cover the canvas. */
    fun setBackgroundColor(argb: Int) = confined {
        val target = composite ?: return@confined
        target.updateLayout(target.layoutFlow.value.copy(backgroundColor = argb))
        scheduleSave()
    }

    // endregion

    // region naming and cameras

    fun displayName(cameraId: String): String =
        _cameras.value.firstOrNull { it.id == cameraId }?.displayName ?: cameraId

    fun choiceLabel(choice: SourceChoice): String = when (choice) {
        is SourceChoice.Camera -> displayName(choice.id)
        is SourceChoice.Rtmp -> "RTMP ${choice.index}"
        SourceChoice.Usb -> "USB camera"
        SourceChoice.Screen -> "Screen"
        SourceChoice.TestImage -> "Test image"
    }

    /**
     * What a layer is called: after what it shows, because with two cameras on screen "Camera"
     * and "Second camera" say nothing about which is which.
     */
    fun layerLabel(layerId: String): String {
        val choice = _layerChoices.value[layerId]
            ?: (composite?.childSource(layerId) as? ICameraSource)?.cameraId?.let { SourceChoice.Camera(it) }
            ?: return if (layerId == CompositionLayers.MAIN) "Camera" else layerId
        // Says what is really on screen: the test image stands in for a source that is not there
        return if (layerId in _placeholders.value) "${choiceLabel(choice)} (waiting)" else choiceLabel(choice)
    }

    /**
     * Where a layer sits, independent of what feeds it, so it still reads right after a swap.
     */
    fun positionName(layerId: String): String {
        val current = layout ?: return layerId
        val layer = current[layerId] ?: return layerId
        return when {
            layer.rect == LayerRect.FULL -> "main"
            current.layers.size <= 1 -> "main"
            else -> "inset"
        }
    }

    fun cameraIdsInUse(): Set<String> {
        val target = composite ?: return emptySet()
        return target.layoutFlow.value.layers
            .mapNotNull { (target.childSource(it.id) as? ICameraSource)?.cameraId }
            .toSet()
    }

    suspend fun loadCameras() = withContext(Dispatchers.IO) {
        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.mapNotNull { id ->
                runCatching {
                    val characteristics = manager.getCameraCharacteristics(id)
                    val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                        CameraCharacteristics.LENS_FACING_BACK -> "Back"
                        else -> "External"
                    }
                    CameraInfo(id, "$facing ${fieldOfView(characteristics)}".trim(), facing)
                }.getOrNull()
            }
        }.onSuccess { _cameras.value = it }
            .onFailure { Log.e(TAG, "Could not list cameras: ${it.message}") }
        Unit
    }

    /**
     * A rough diagonal field of view, which is how an operator tells two back cameras apart far
     * better than by id.
     */
    private fun fieldOfView(characteristics: CameraCharacteristics): String {
        val focal = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return ""
        val sensor = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) as? SizeF
            ?: return "f=%.1fmm".format(focal)

        val diagonal = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
        val degrees = (2.0 * atan((diagonal / (2.0 * focal)).toDouble()) * 180.0 / Math.PI).toInt()
        return "$degrees°"
    }

    // endregion

    // region persistence

    private var saveJob: kotlinx.coroutines.Job? = null

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(SAVE_DEBOUNCE_MS)
            saveNow()
        }
    }

    private fun saveNow() {
        val current = layout ?: return
        val choices = _layerChoices.value
        store.save(
            layout = current,
            sources = choices.mapValues { it.value.key },
            pipSourceName = SourceChoice.legacyKind(choices[CompositionLayers.PIP])
        )
    }

    /**
     * Puts the saved arrangement back: rectangles, visibility, each layer's style and the
     * background. Only for layers that still exist; the rest keep their defaults.
     */
    fun restoreSaved() {
        val target = composite ?: return
        val saved = store.load() ?: return
        var current = target.layoutFlow.value
        var changed = false

        current.layers.forEach { layer ->
            saved.depths[layer.id]?.let { z ->
                current = current.mapLayer(layer.id) { it.copy(z = z) }
                changed = true
            }
            saved.rects[layer.id]?.let { rect ->
                current = current.mapLayer(layer.id) {
                    it.copy(rect = rect, visible = !saved.hidden.contains(layer.id))
                }
                changed = true
            }
            saved.styles[layer.id]?.let { style ->
                current = current.mapLayer(layer.id) {
                    it.copy(
                        scaleMode = style.scaleMode ?: it.scaleMode,
                        alpha = style.alpha ?: it.alpha,
                        mirror = style.mirror ?: it.mirror,
                        rotationDegrees = style.rotationDegrees ?: it.rotationDegrees
                    )
                }
                changed = true
            }
        }
        saved.backgroundColor?.let {
            current = current.copy(backgroundColor = it)
            changed = true
        }
        // The 🔊 layer: it sets the pace of the frames, and the live's sound comes from it
        saved.primary?.takeIf { current[it] != null && it != current.primaryLayerId }?.let {
            current = current.copy(primaryLayerId = it)
            changed = true
        }

        if (changed) {
            target.updateLayout(current)
            Log.i(TAG, "Restored the saved layout")
        }
    }

    // endregion

    companion object {
        private const val TAG = "CompositionController"

        /** How close to an edge or the centre a dragged layer snaps, in canvas fractions. */
        private const val SNAP_THRESHOLD = 0.02f
        private const val SAVE_DEBOUNCE_MS = 500L
        private const val MIN_ALPHA = 0.1f
    }
}
