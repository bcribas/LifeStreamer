package com.dimadesu.lifestreamer.composition

import android.app.Application
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dimadesu.lifestreamer.ui.main.RtmpSourceSwitchHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player behind an RTMP (or SRT) layer, and what keeps it going: a feed that fails, ends or
 * stalls is reported down (the layer shows the test image) and tried again every few seconds,
 * then handed back to the layer once it plays.
 *
 * A layer's source never releases its player; this does, when the layer takes another source or
 * the composition ends. It lives in the service, so it keeps trying with the app closed.
 *
 * The player is touched on the main thread only, as ExoPlayer requires.
 */
class RtmpLayerFeed(
    private val application: Application,
    private val scope: CoroutineScope,
    val index: Int,
    /** URL and playback buffer of RTMP source [index], or null when it has no URL. */
    private val config: suspend (Int) -> Pair<String, Int>?,
    /** The feed died: the layer should show the test image, saying [reason]. */
    private val onDown: suspend (reason: String) -> Unit,
    /** The feed plays again: this player goes back in the layer. */
    private val onUp: suspend (ExoPlayer) -> Unit,
) {
    @Volatile
    var player: ExoPlayer? = null
        private set

    @Volatile
    private var released = false

    @Volatile
    private var down = false

    @Volatile
    private var audioEnabled = false

    private var watcher: Player.Listener? = null
    private var stallJob: Job? = null
    private var retryJob: Job? = null

    /** A player for the layer, not started (the layer's source starts it); null without a URL. */
    suspend fun open(): ExoPlayer? {
        val next = create() ?: return null
        withContext(Dispatchers.Main) { adopt(next) }
        return next
    }

    /**
     * Whether this feed's sound plays. Only the layer the live's sound comes from keeps it: the
     * capture that carries it takes every sound the app plays, and a muted track is not decoded.
     */
    fun setAudioEnabled(enabled: Boolean) {
        if (audioEnabled == enabled) return
        audioEnabled = enabled
        val current = player ?: return
        scope.launch(Dispatchers.Main) { applyAudio(current) }
    }

    /** Something else found the feed broken (its layer failed to start): the same as a failure. */
    fun reportTrouble(reason: String) = trouble(reason)

    fun release() {
        released = true
        retryJob?.cancel()
        val last = player
        player = null
        scope.launch(Dispatchers.Main) {
            stallJob?.cancel()
            last?.let { dispose(it) }
            Log.i(TAG, "RTMP $index feed released")
        }
    }

    private suspend fun create(): ExoPlayer? {
        val (url, bufferMs) = config(index)?.takeIf { it.first.isNotBlank() } ?: return null
        return RtmpSourceSwitchHelper.createExoPlayer(application, url, bufferMs)
    }

    /** Main thread: watches [next] and makes it the feed's player. */
    private fun adopt(next: ExoPlayer) {
        if (released) {
            dispose(next)
            return
        }
        applyAudio(next)
        val listener = object : Player.Listener {
            private var everReady = false

            override fun onPlayerError(error: PlaybackException) {
                trouble("RTMP $index failed: ${error.message}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                stallJob?.cancel()
                when (playbackState) {
                    Player.STATE_READY -> everReady = true
                    Player.STATE_ENDED -> trouble("RTMP $index ended")
                    // A feed that never starts, or stops arriving, is as good as gone. The limit
                    // is longer the first time: connecting and filling the buffer take a while.
                    Player.STATE_BUFFERING -> {
                        val limit = if (everReady) STALL_MS else FIRST_READY_MS
                        stallJob = scope.launch(Dispatchers.Main) {
                            delay(limit)
                            if (next.playbackState == Player.STATE_BUFFERING) trouble("RTMP $index stalled")
                        }
                    }
                    // Idle is the layer's source stopping it (no live, no preview), not a failure
                    else -> Unit
                }
            }
        }
        next.addListener(listener)
        watcher = listener
        player = next
    }

    private fun applyAudio(target: ExoPlayer) {
        runCatching {
            target.trackSelectionParameters = target.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audioEnabled)
                .build()
        }.onFailure { Log.w(TAG, "Could not set the RTMP $index sound: ${it.message}") }
    }

    private fun trouble(reason: String) {
        if (down || released) return
        down = true
        Log.w(TAG, "$reason - retrying every ${RETRY_MS / 1000} s")
        val failed = player
        player = null
        retryJob = scope.launch {
            onDown("RTMP $index lost - showing placeholder")
            // Released once the placeholder has taken its place in the layer
            withContext(Dispatchers.Main) {
                stallJob?.cancel()
                failed?.let { dispose(it) }
            }
            retry()
        }
    }

    private suspend fun retry() {
        while (scope.isActive && !released) {
            delay(RETRY_MS)
            val next = runCatching { create() }.getOrNull() ?: continue
            val playing = withContext(Dispatchers.Main) {
                runCatching {
                    applyAudio(next)
                    next.prepare()
                    next.playWhenReady = true
                    RtmpSourceSwitchHelper.awaitReady(next, READY_TIMEOUT_MS)
                }.getOrDefault(false)
            }
            if (!playing || released) {
                withContext(Dispatchers.Main) { dispose(next) }
                continue
            }
            withContext(Dispatchers.Main) { adopt(next) }
            down = false
            Log.i(TAG, "RTMP $index is back")
            onUp(next)
            return
        }
    }

    /** Main thread. */
    private fun dispose(target: ExoPlayer) {
        runCatching {
            watcher?.let { target.removeListener(it) }
            target.stop()
            target.release()
        }.onFailure { Log.w(TAG, "Could not release an RTMP layer player: ${it.message}") }
    }

    private companion object {
        const val TAG = "RtmpLayerFeed"
        const val RETRY_MS = 5_000L
        const val READY_TIMEOUT_MS = 10_000L
        const val STALL_MS = 4_000L
        const val FIRST_READY_MS = 15_000L
    }
}
