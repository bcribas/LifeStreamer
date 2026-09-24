package com.dimadesu.lifestreamer.audio

import android.os.Build
import android.util.Log
import com.dimadesu.lifestreamer.composition.CompositionController
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionAudioSource
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionAudioSourceFactory
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionService
import com.dimadesu.lifestreamer.sources.SourceChoice
import com.dimadesu.lifestreamer.sources.SourceController
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.ICompositeVideoSource
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Makes the live's sound follow the 🔊 layer of a composition: that RTMP source's sound for an
 * RTMP layer, the microphone (or Bluetooth) for the rest, the phone's sound with SYS AUDIO. Lives
 * in the service, so it follows with the app closed too.
 *
 * Only a change of route switches the sound: the audio input swaps mid-live with a short gap, and
 * the layout (which carries the 🔊 layer) changes at every step of a drag. Outside a composition
 * the app's screen picks the sound when it switches the source, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AudioFollower(
    private val scope: CoroutineScope,
    private val composition: CompositionController,
    private val sources: SourceController,
    private val videoSourceFlow: () -> Flow<Any?>?,
    private val streamer: () -> IWithAudioSource?,
    /** The microphone, then Bluetooth when that is on. */
    private val useMic: suspend () -> Unit,
) {
    /** What the composition's sound comes from now, or null outside a composition. */
    private val _route = MutableStateFlow<AudioRoute?>(null)
    val route: StateFlow<AudioRoute?> = _route.asStateFlow()

    private val _systemAudio = MutableStateFlow(false)

    /** SYS AUDIO, as the app's screen last set it: the phone's sound instead of the microphone. */
    var systemAudio: Boolean
        get() = _systemAudio.value
        set(value) {
            _systemAudio.value = value
        }

    /** The 🔊 layer's source while a composition runs; [Idle] outside one. */
    private sealed interface Target {
        data object Idle : Target
        data class Layer(val choice: SourceChoice?) : Target
    }

    private var composing = false
    private var askedFor: SourceChoice? = null

    fun start() {
        val videoFlow = videoSourceFlow() ?: return
        val target = videoFlow.flatMapLatest { source ->
            val composite = source as? ICompositeVideoSource
            if (composite == null) {
                flowOf<Target>(Target.Idle)
            } else {
                combine(
                    composite.layoutFlow.map { it.primaryLayer?.id }.distinctUntilChanged(),
                    composition.layerChoices
                ) { primary, choices -> Target.Layer(primary?.let { choices[it] }) }
            }
        }
        scope.launch {
            combine(target, _systemAudio, MediaProjectionService.projection) { t, sys, grant -> Triple(t, sys, grant) }
                .debounce(DEBOUNCE_MS)
                .collect { (t, sys, grant) ->
                    runCatching { follow(t, sys, grant) }
                        .onFailure { Log.w(TAG, "Could not follow the sound: ${it.message}", it) }
                }
        }
    }

    private suspend fun follow(target: Target, systemAudio: Boolean, grant: android.media.projection.MediaProjection?) {
        val live = streamer() ?: return
        if (target !is Target.Layer) {
            if (composing) leaveComposition(live, systemAudio, grant)
            return
        }
        composing = true
        val wanted = AudioRoute.of(target.choice, systemAudio, Build.VERSION.SDK_INT)
        val now = routeOf(live)
        // A new grant ends the last one, and the capture made with it goes silent
        val capturing = live.audioInput?.sourceFlow?.value as? MediaProjectionAudioSource
        val staleCapture = capturing != null && grant != null && capturing.mediaProjection !== grant
        if (wanted == now && !staleCapture) {
            _route.value = now
            return
        }
        apply(live, wanted, grant, target.choice)
    }

    private suspend fun apply(
        live: IWithAudioSource,
        wanted: AudioRoute,
        grant: android.media.projection.MediaProjection?,
        choice: SourceChoice?,
    ) {
        if (wanted == AudioRoute.MIC || grant == null || Build.VERSION.SDK_INT < AudioRoute.API_CAPTURE) {
            if (routeOf(live) != AudioRoute.MIC) useMic()
            _route.value = AudioRoute.MIC
            if (wanted.captures && askedFor != choice) {
                askedFor = choice
                missingGrant(wanted, choice)
            }
            Log.i(TAG, "The composition's sound: microphone" + if (wanted.captures) " (no capture grant)" else "")
            return
        }
        live.setAudioSource(MediaProjectionAudioSourceFactory(grant, captureFullPhone = wanted == AudioRoute.PHONE_AUDIO))
        askedFor = null
        _route.value = wanted
        Log.i(TAG, "The composition's sound: $wanted")
    }

    /** The sound wants a capture grant there is none of: asked for on the phone when it can be. */
    private fun missingGrant(wanted: AudioRoute, choice: SourceChoice?) {
        val what = if (wanted == AudioRoute.APP_AUDIO && choice != null) "${sources.label(choice)}'s sound" else "The phone's sound"
        val app = sources.host
        if (app != null && app.canAskOnPhone) {
            app.askCaptureForSound()
            composition.tell("$what needs screen capture: accept it on the phone (microphone until then)")
        } else if (app != null) {
            composition.tell("$what needs screen capture: bring the app to the front to accept it (microphone until then)")
        } else {
            composition.tell("$what needs screen capture, accepted with the app open (microphone until then)")
        }
    }

    /**
     * The composition is gone. A captured sound goes back to what the whole picture's source
     * takes; an RTMP source's own is set by the app's screen when it switches to one.
     */
    private suspend fun leaveComposition(
        live: IWithAudioSource,
        systemAudio: Boolean,
        grant: android.media.projection.MediaProjection?,
    ) {
        composing = false
        askedFor = null
        _route.value = null
        val top = sources.currentTopLevel()
        if (top is SourceChoice.Rtmp) return
        val wanted = AudioRoute.of(top, systemAudio, Build.VERSION.SDK_INT)
        if (routeOf(live) != wanted) {
            Log.i(TAG, "The composition is off: the sound goes back to $wanted")
            apply(live, wanted, grant, top)
        }
    }

    private fun routeOf(live: IWithAudioSource): AudioRoute? =
        when (val source = live.audioInput?.sourceFlow?.value) {
            null -> null
            is MediaProjectionAudioSource -> if (source.captureFullPhone) AudioRoute.PHONE_AUDIO else AudioRoute.APP_AUDIO
            else -> AudioRoute.MIC
        }

    private companion object {
        const val TAG = "AudioFollower"
        const val DEBOUNCE_MS = 250L
    }
}
