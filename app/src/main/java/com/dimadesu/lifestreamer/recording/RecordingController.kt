package com.dimadesu.lifestreamer.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository.RecordingMode
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.createDefaultTsServiceInfo
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.TsMuxer
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ITsPacketTap
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.TsTapSink
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioVideoEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.ISecondaryOutputStreamer
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the live to local storage, for as long as the live session lasts.
 *
 * A session starts with the live ([onLiveStarting], before the live opens) and ends only when
 * the operator stops it ([onManualStop]): network drops, reconnections and even a live that
 * failed without the app's screen leave the recording running, which is the point of it. The
 * toggle ([setEnabled], also from the remote page) applies at once during a session.
 *
 * Nothing here may affect the live: every failure ends the recording with a reason in
 * [statusFlow] and is never thrown to the caller.
 */
class RecordingController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: DataStoreRepository,
    private val streamerProvider: () -> ISingleStreamer?,
    private val liveCopyHost: LiveCopyHost? = null,
) {
    /**
     * What the copy-of-the-live mode needs from the live: a TS stream it can tap, which only the
     * resilient SRT endpoint offers.
     */
    interface LiveCopyHost {
        /**
         * Why the live cannot be copied now, or null.
         *
         * @param liveOpen whether the live is already open: its endpoint is then fixed until the
         * next live
         */
        suspend fun liveCopyBlockedReason(liveOpen: Boolean): String?

        /** Feeds the live's TS to [tap], or stops feeding it with null. */
        fun attachLiveTap(tap: ITsPacketTap?)
    }

    enum class State { OFF, ARMED, RECORDING, ERROR }

    data class Status(
        val enabled: Boolean = false,
        val state: State = State.OFF,
        val mode: RecordingMode = RecordingMode.SEPARATE,
        val segmentIndex: Int = 0,
        val segmentName: String? = null,
        val startedAtMs: Long? = null,
        val resolution: String? = null,
        val error: String? = null,
        val warning: String? = null,
    )

    private val _statusFlow = MutableStateFlow(Status())
    val statusFlow: StateFlow<Status> = _statusFlow.asStateFlow()

    val isActive: Boolean
        get() = _statusFlow.value.state == State.RECORDING

    private val mutex = Mutex()
    private var sessionActive = false
    private var writer: SegmentedTsWriter? = null
    private var output: IConfigurableAudioVideoEncodingPipelineOutput? = null
    private var outputWatch: Job? = null
    private var stoppingOutput = false
    private var ejectReceiver: BroadcastReceiver? = null

    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LifeStreamer::Recording")
            .apply { setReferenceCounted(false) }

    init {
        scope.launch {
            repository.recordingConfigFlow.first().let { config ->
                updateStatus { it.copy(enabled = config.enabled, mode = config.mode) }
            }
            mutex.withLock { refreshIdleStateLocked() }
            // The toggle, from the settings or the remote page, applies at once in a session
            repository.recordingConfigFlow.map { it.enabled }.distinctUntilChanged().drop(1)
                .collect { enabled ->
                    mutex.withLock {
                        if (enabled && sessionActive && writer == null) startLocked()
                        if (!enabled && writer != null) stopLocked(error = null)
                        refreshIdleStateLocked()
                    }
                }
        }
    }

    /** Bytes, segment and free space, for the 2 s stats; null when not recording. */
    fun stats(): SegmentedTsWriter.Stats? = writer?.stats()

    /**
     * The live is about to open. Starts the recording if it is enabled and not already running;
     * idempotent, so the reconnection path can call it again.
     */
    suspend fun onLiveStarting() {
        mutex.withLock {
            sessionActive = true
            if (writer == null && repository.recordingConfigFlow.first().enabled) startLocked()
            refreshIdleStateLocked()
        }
    }

    /** The operator stopped the live: the session, and the recording with it, ends. */
    fun onManualStop() {
        scope.launch {
            mutex.withLock {
                sessionActive = false
                stopLocked(error = null)
                refreshIdleStateLocked()
            }
        }
    }

    /**
     * Turns the function on or off, persisted like the settings switch. Returns why it cannot be
     * turned on, if it cannot.
     */
    suspend fun setEnabled(enabled: Boolean): String? {
        if (enabled) blockedReason()?.let { return it }
        repository.setRecordingEnabled(enabled)
        return null
    }

    /** Why the recording could not start now, or null. */
    suspend fun blockedReason(): String? = withContext(Dispatchers.IO) {
        val config = repository.recordingConfigFlow.first()
        val folder = config.folderUri ?: return@withContext "Pick a recording folder in the app settings first"
        val store = SafSegmentStore(context, Uri.parse(folder))
        when {
            !store.hasPermission() -> RecordingFailure.AccessLost.reason
            (store.freeBytes() ?: 0L) < MIN_FREE_BYTES -> RecordingFailure.StorageFull.reason
            config.mode == RecordingMode.LIVE_COPY -> liveCopyHost
                ?.liveCopyBlockedReason(streamerProvider()?.isOpenFlow?.value == true)
                ?: if (liveCopyHost == null) "Copy of the live is not available" else null
            else -> null
        }
    }

    suspend fun shutdown() {
        mutex.withLock {
            sessionActive = false
            stopLocked(error = null)
        }
    }

    // region internals, all under mutex

    private suspend fun startLocked() {
        val config = repository.recordingConfigFlow.first()
        updateStatus { it.copy(mode = config.mode, error = null, warning = null) }
        blockedReason()?.let { reason ->
            updateStatus { it.copy(state = State.ERROR, error = reason) }
            return
        }
        if (config.mode == RecordingMode.LIVE_COPY) {
            startLiveCopyLocked(config)
            return
        }
        val streamer = streamerProvider()
        val secondary = streamer as? ISecondaryOutputStreamer
        val videoStreamer = streamer as? IVideoSingleStreamer
        if (secondary == null || videoStreamer == null) {
            updateStatus { it.copy(state = State.ERROR, error = "The streamer is not ready") }
            return
        }
        val live = videoStreamer.videoConfigFlow.value
        if (live == null) {
            updateStatus { it.copy(state = State.ERROR, error = "The live's video is not configured yet") }
            return
        }
        val audio = (streamer as? IAudioSingleStreamer)?.audioConfigFlow?.value

        var warning: String? = null
        var resolution = config.resolution ?: live.resolution
        if (!sameShape(resolution, live.resolution)) {
            warning = "Recording at ${live.resolution}: ${config.resolution} has another shape than the live"
            resolution = live.resolution
        }
        if (secondary.isPipelineStreamingFlow.value) {
            // The sources are running, so they cannot grow for the recording now
            videoStreamer.videoInput.sourceConfigFlow.value?.resolution?.let { source ->
                if (resolution.width > source.width || resolution.height > source.height) {
                    warning = "Recording at $source: the live was already running. " +
                            "$resolution applies from the next live"
                    resolution = source
                }
            }
        }

        val sessionName = sessionName()
        val newWriter = SegmentedTsWriter(
            SafSegmentStore(context, Uri.parse(config.folderUri)),
            sessionName,
            config.segmentDurationMs,
            writerListener,
            minFreeBytes = MIN_FREE_BYTES,
        )
        var newOutput: IConfigurableAudioVideoEncodingPipelineOutput? = null
        try {
            newWriter.start()
            newOutput = secondary.addSecondaryOutput(
                endpointFactory = TapEndpointFactory(newWriter),
                withAudio = audio != null,
            )
            audio?.let { newOutput.setAudioCodecConfig(it) }
            newOutput.setVideoCodecConfig(
                live.copy(resolution = resolution, startBitrate = config.videoBitrateBps)
            )
            newOutput.open(UriMediaDescriptor(DESCRIPTOR_URI))
            newOutput.startStream()
        } catch (t: Throwable) {
            Log.e(TAG, "Recording could not start", t)
            newOutput?.let { runCatching { secondary.removeSecondaryOutput(it) } }
            withContext(Dispatchers.IO) { newWriter.stop(timeoutMs = 2_000) }
            updateStatus {
                it.copy(state = State.ERROR, error = "Recording could not start: ${t.message ?: t.javaClass.simpleName}")
            }
            return
        }

        writer = newWriter
        output = newOutput
        stoppingOutput = false
        watchOutput(newOutput)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        registerEjectReceiver(config.folderUri)
        Log.i(TAG, "Recording $resolution at ${config.videoBitrateBps / 1000} kb/s as $sessionName")
        updateStatus {
            it.copy(
                state = State.RECORDING,
                startedAtMs = System.currentTimeMillis(),
                resolution = resolution.toString(),
                warning = warning,
                error = null,
            )
        }
    }

    /**
     * The live's own TS, byte for byte: no encoder of its own, the live's resolution and bitrate,
     * and no gap on a network drop since the resilient endpoint keeps muxing through it.
     */
    private suspend fun startLiveCopyLocked(config: DataStoreRepository.RecordingConfig) {
        val host = liveCopyHost ?: return
        val live = (streamerProvider() as? IVideoSingleStreamer)?.videoConfigFlow?.value
        val newWriter = SegmentedTsWriter(
            SafSegmentStore(context, Uri.parse(config.folderUri)),
            sessionName(),
            config.segmentDurationMs,
            writerListener,
            minFreeBytes = MIN_FREE_BYTES,
        )
        newWriter.start()
        host.attachLiveTap(newWriter)
        writer = newWriter
        output = null
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        registerEjectReceiver(config.folderUri)
        Log.i(TAG, "Recording a copy of the live")
        updateStatus {
            it.copy(
                state = State.RECORDING,
                startedAtMs = System.currentTimeMillis(),
                resolution = live?.resolution?.toString(),
                warning = null,
                error = null,
            )
        }
    }

    private suspend fun stopLocked(error: String?) {
        val currentWriter = writer ?: run {
            if (error != null) updateStatus { it.copy(state = State.ERROR, error = error) }
            return
        }
        val currentOutput = output
        writer = null
        output = null
        stoppingOutput = true
        outputWatch?.cancel()
        outputWatch = null
        unregisterEjectReceiver()

        // The output first, so its last frames still go through the muxer into the writer. A
        // copy of the live has no output: the tap is just detached.
        currentOutput?.let { runCatching { it.stopStream() } }
        if (currentOutput == null) liveCopyHost?.attachLiveTap(null)
        val clean = withContext(Dispatchers.IO) { currentWriter.stop() }
        if (!clean) Log.w(TAG, "The last segment may be incomplete: storage did not finish in time")
        val secondary = streamerProvider() as? ISecondaryOutputStreamer
        currentOutput?.let { out -> secondary?.let { runCatching { it.removeSecondaryOutput(out) } } }
        if (wakeLock.isHeld) runCatching { wakeLock.release() }

        updateStatus {
            it.copy(
                state = if (error != null) State.ERROR else State.OFF,
                error = error,
                startedAtMs = null,
                segmentName = null,
            )
        }
    }

    /** OFF or ARMED when nothing is recording and nothing went wrong. */
    private suspend fun refreshIdleStateLocked() {
        val enabled = repository.recordingConfigFlow.first().enabled
        updateStatus { status ->
            val state = when {
                writer != null -> State.RECORDING
                status.state == State.ERROR && enabled && sessionActive -> State.ERROR
                enabled -> State.ARMED
                else -> State.OFF
            }
            status.copy(
                enabled = enabled,
                state = state,
                error = if (state == State.ERROR) status.error else null,
            )
        }
    }

    /** An output that stops by itself (encoder error) ends the recording with the reason. */
    private fun watchOutput(target: IConfigurableAudioVideoEncodingPipelineOutput) {
        outputWatch = scope.launch {
            launch {
                target.throwableFlow.filterNotNull().collect { t ->
                    onRecordingFailed("Recording encoder error: ${t.message ?: t.javaClass.simpleName}")
                }
            }
            launch {
                target.isStreamingFlow.drop(1).collect { streaming ->
                    if (!streaming && !stoppingOutput) onRecordingFailed("The recording stopped by itself")
                }
            }
        }
    }

    private fun onRecordingFailed(reason: String) {
        scope.launch {
            mutex.withLock {
                if (writer != null) stopLocked(error = reason)
            }
        }
    }

    private val writerListener = object : SegmentedTsWriter.Listener {
        override fun onSegmentOpened(name: String, index: Int) {
            updateStatus { it.copy(segmentName = name, segmentIndex = index) }
            // Renew the wake lock's timeout with every segment
            if (wakeLock.isHeld) runCatching { wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) }
        }

        override fun onWarning(message: String) {
            updateStatus { it.copy(warning = message) }
        }

        override fun onFailed(failure: RecordingFailure) {
            onRecordingFailed(failure.reason)
        }
    }

    /**
     * Ejecting the card from the system settings kills processes that hold files open on it, so
     * the segment is closed as soon as the eject is announced.
     */
    private fun registerEjectReceiver(folderUri: String?) {
        val volumeId = folderUri?.let { SafSegmentStore.volumeId(Uri.parse(it)) }
        if (volumeId == null || volumeId == "primary") return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val path = intent.data?.path ?: return
                if (!path.contains(volumeId)) return
                Log.w(TAG, "Recording volume going away (${intent.action}): stopping")
                onRecordingFailed("SD card ejected")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ejectReceiver = receiver
    }

    private fun unregisterEjectReceiver() {
        ejectReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        ejectReceiver = null
    }

    // endregion

    private fun updateStatus(transform: (Status) -> Status) {
        while (true) {
            val current = _statusFlow.value
            if (_statusFlow.compareAndSet(current, transform(current))) return
        }
    }

    private fun sessionName() =
        "LifeStreamer_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    private fun sameShape(a: Size, b: Size): Boolean =
        kotlin.math.abs(a.width.toDouble() / a.height - b.width.toDouble() / b.height) < 0.02

    /** The recording's endpoint: its own TS muxer, feeding the writer. */
    private class TapEndpointFactory(private val writer: SegmentedTsWriter) : IEndpointInternal.Factory {
        override fun create(context: Context, dispatcherProvider: IDispatcherProvider): IEndpointInternal =
            CompositeEndpoint(
                TsMuxer().apply { addService(createDefaultTsServiceInfo()) },
                TsTapSink(writer)
            )
    }

    private companion object {
        const val TAG = "RecordingController"
        const val MIN_FREE_BYTES = 200L shl 20
        const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** Never touched: the tap sink ignores it. It only has to describe a TS output. */
        const val DESCRIPTOR_URI = "file:///lifestreamer-recording.ts"
    }
}
