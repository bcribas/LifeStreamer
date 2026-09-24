package com.dimadesu.lifestreamer.sources

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dimadesu.lifestreamer.composition.CompositionController
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.uvc.UvcVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.IMediaProjectionSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The video source, as the phone and the page choose it: what can be chosen now and why not, and
 * the switch itself. Lives in the service, so the page can switch cameras with the app closed; the
 * rest goes through the app's screen ([host]) when it is there.
 */
class SourceController(
    private val scope: CoroutineScope,
    private val storage: DataStoreRepository,
    private val composition: CompositionController,
    private val videoSource: () -> IVideoSource?,
    /** Switches the whole picture to a camera, from the service. */
    private val switchCamera: suspend (String) -> Unit,
    /** Puts the microphone (or Bluetooth) back as the sound, after an RTMP source. */
    private val restoreMicAudio: suspend () -> Unit,
) {
    /** An RTMP source of the settings, as the page may show it: never the URL, which has the key. */
    data class RtmpEntry(val index: Int, val urlSet: Boolean, val host: String?)

    data class Option(
        val choice: SourceChoice,
        val label: String,
        val detail: String?,
        val verdict: SourceRules.Verdict,
        val active: Boolean,
    )

    @Volatile
    var host: AppSourceHost? = null
        set(value) {
            field = value
            // The same screen builds the layers only it can build (USB, screen capture)
            composition.externalSources = value
            // A USB layer left waiting while the app was closed opens again with it (the screen
            // is not asked for again on its own: that is a dialog every time the app opens)
            if (value != null) prepareWaitingLayers(usbOnly = true)
            changed()
        }

    private val _rtmp = MutableStateFlow<List<RtmpEntry>>(emptyList())
    val rtmpEntries: StateFlow<List<RtmpEntry>> = _rtmp.asStateFlow()

    private val _pending = MutableStateFlow<String?>(null)

    private val _version = MutableStateFlow(0L)

    /** Moves on whenever something the page shows about sources changed. */
    val changes: StateFlow<Long> = _version.asStateFlow()

    /** What the phone is waiting for (a permission), said on the page; null when nothing. */
    val pending: String? get() = _pending.value

    fun changed() {
        _version.value++
    }

    fun setPending(text: String?) {
        if (_pending.value != text) {
            _pending.value = text
            changed()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            storage.rtmpSourceCountFlow.flatMapLatest { count ->
                if (count <= 0) flowOf(emptyList())
                else combine((1..count).map { index -> storage.rtmpSourceUrlFlow(index).map { index to it } }) { it.toList() }
            }.collect { urls ->
                _rtmp.value = urls.map { (index, url) -> RtmpEntry(index, url.isNotBlank(), hostOf(url)) }
                changed()
            }
        }
    }

    // region what can be chosen

    fun context(layers: Map<String, SourceChoice> = emptyMap()): SourceRules.Context {
        val app = host
        return SourceRules.Context(
            appOpen = app != null,
            canAskOnPhone = app?.canAskOnPhone == true,
            cameraIds = composition.cameras.value.map { it.id },
            rtmp = _rtmp.value.associate { it.index to it.urlSet },
            captureGranted = app?.captureGranted == true,
            apiLevel = Build.VERSION.SDK_INT,
            usbConnected = app?.usbConnected == true,
            usbPermitted = app?.usbPermitted == true,
            layers = layers,
            canRunTogether = composition::canRunTogether,
        )
    }

    /** What feeds the whole picture; null while a composition is on (each layer has its own). */
    fun currentTopLevel(): SourceChoice? {
        if (composition.isCompositionActive) return null
        host?.topLevelChoice?.let { return it }
        return when (val source = videoSource()) {
            is ICameraSource -> SourceChoice.Camera(source.cameraId)
            is RTMPVideoSource -> lastRtmp?.let { SourceChoice.Rtmp(it) }
            is UvcVideoSource -> SourceChoice.Usb
            is IMediaProjectionSource -> SourceChoice.Screen
            else -> null
        }
    }

    /** The last RTMP index put on the whole picture, to name it with the app closed. */
    @Volatile
    var lastRtmp: Int? = null
        private set

    fun label(choice: SourceChoice): String = composition.choiceLabel(choice)

    private fun detail(choice: SourceChoice): String? = when (choice) {
        is SourceChoice.Rtmp -> _rtmp.value.firstOrNull { it.index == choice.index }?.host
        else -> null
    }

    fun topLevelOptions(): List<Option> {
        val context = context()
        val current = currentTopLevel()
        return SourceRules.options(context, null).map { choice ->
            Option(choice, label(choice), detail(choice), SourceRules.verdict(choice, context), choice == current)
        }
    }

    /** What [layerId] of the running composition can show, next to what the others show. */
    fun layerOptions(layerId: String): List<Option> {
        val layers = composition.layerChoices.value
        val context = context(layers)
        val current = layers[layerId]
        return SourceRules.options(context, layerId).map { choice ->
            Option(choice, label(choice), detail(choice), SourceRules.verdict(choice, context, layerId), choice == current)
        }
    }

    // endregion

    /**
     * Switches the whole picture to [choice]. Refused (with the reason) during a composition, or
     * when it cannot be chosen now; otherwise done by the app, or by the service for a camera.
     */
    suspend fun switchTopLevel(choice: SourceChoice): Result<Unit> {
        if (composition.isCompositionActive) {
            return Result.failure(IllegalStateException("A composition is on: choose each layer's source"))
        }
        val verdict = SourceRules.verdict(choice, context())
        if (!verdict.available) return Result.failure(IllegalArgumentException(verdict.reason))
        if (choice is SourceChoice.Rtmp) lastRtmp = choice.index
        host?.let {
            it.switchTopLevel(choice)
            return Result.success(Unit)
        }
        if (choice !is SourceChoice.Camera) return Result.failure(IllegalArgumentException(SourceRules.NEEDS_APP))
        return runCatching {
            val previous = videoSource()
            switchCamera(choice.id)
            composition.primaryCameraHint = choice.id
            // An RTMP source left running when the app closed: its player and its sound go too
            (previous as? RTMPVideoSource)?.let { rtmp ->
                restoreMicAudio()
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        rtmp.player.stop()
                        rtmp.player.release()
                    }
                }
            }
            Log.i(TAG, "Whole picture on camera ${choice.id}, from the service")
            changed()
        }
    }

    // region the composition's layers

    /**
     * With no composition: the layer that will be the inset when it is turned on, and what it can
     * show next to what is on screen now.
     */
    fun presetOptions(): Pair<String, List<Option>> {
        val (bottomId, otherId) = composition.upcomingLayers()
        val onScreen = currentTopLevel()
        val context = context(listOfNotNull(onScreen?.let { bottomId to it }).toMap())
        val current = composition.layerChoices.value[otherId]
        return otherId to SourceRules.options(context, otherId).map { choice ->
            Option(choice, label(choice), detail(choice), SourceRules.verdict(choice, context, otherId), choice == current)
        }
    }

    /** Chooses what [layerId] shows when the composition is turned on. */
    fun presetLayerSource(layerId: String, choice: SourceChoice): Result<Unit> {
        val option = presetOptions().second.firstOrNull { it.choice == choice }
        if (option != null && !option.verdict.available) return Result.failure(IllegalArgumentException(option.verdict.reason))
        composition.presetLayerSource(layerId, choice)
        changed()
        return Result.success(Unit)
    }

    /**
     * Makes [layerId] show [choice]. What needs the app's screen to get ready (a USB camera's
     * permission, a screen-capture grant) is asked for there, and the layer shows the test image
     * until it arrives.
     */
    suspend fun setLayerSource(layerId: String, choice: SourceChoice): Result<Unit> {
        if (!composition.isCompositionActive) return Result.failure(IllegalStateException("No composition"))
        val verdict = SourceRules.verdict(choice, context(composition.layerChoices.value), layerId)
        if (!verdict.available) return Result.failure(IllegalArgumentException(verdict.reason))
        return composition.setLayerSource(layerId, choice).onSuccess { prepareWaitingLayers() }
    }

    /**
     * Turns the composition on, its bottom layer showing what is on screen now: the RTMP source,
     * USB camera or screen too, not only a camera.
     */
    suspend fun enableComposition(): Result<Unit> {
        // A screen capture ends with the source that used it: the layer asks for a new grant
        val onScreen = currentTopLevel()
        return composition.enableComposition(onScreen).onSuccess { prepareWaitingLayers() }
    }

    /**
     * Turns the composition off. What the bottom layer showed becomes the whole picture: at once
     * for a camera; through the app for the rest, or the camera when it cannot be done now.
     */
    suspend fun disableComposition(): Result<Unit> {
        val bottom = composition.disableComposition().getOrElse { return Result.failure(it) }
            ?: return Result.success(Unit)
        val verdict = SourceRules.verdict(bottom, context())
        val app = host
        if (verdict.available && app != null) {
            if (bottom is SourceChoice.Rtmp) lastRtmp = bottom.index
            app.switchTopLevel(bottom)
        } else {
            composition.tell("${label(bottom)} stays off: ${verdict.reason ?: SourceRules.NEEDS_APP}")
        }
        return Result.success(Unit)
    }

    /** USB and screen layers waiting for the app: asked for there, when the app is there. */
    private fun prepareWaitingLayers(usbOnly: Boolean = false) {
        val app = host ?: return
        if (!composition.isCompositionActive) return
        composition.layerChoices.value.forEach { (layerId, choice) ->
            if ((choice == SourceChoice.Usb || (choice == SourceChoice.Screen && !usbOnly)) &&
                composition.placeholderReason(layerId) != null
            ) {
                app.prepareLayerSource(layerId, choice)
            }
        }
    }

    // endregion

    companion object {
        private const val TAG = "SourceController"

        /** The host of a stream URL, for the page: the rest (path, stream key) stays on the phone. */
        fun hostOf(url: String): String? = runCatching { android.net.Uri.parse(url.trim()).host }.getOrNull()
    }
}
