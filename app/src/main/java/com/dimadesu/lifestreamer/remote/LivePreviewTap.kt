package com.dimadesu.lifestreamer.remote

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Size
import io.github.thibaultbee.streampack.core.pipelines.inputs.IVideoInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * A small JPEG of the picture that goes out, for the remote page: the frame the live's encoder
 * gets (the finished composition, or the one camera), before compression.
 *
 * It costs nothing while nobody asks. On the first request a small reader is added to the video
 * pipeline, drawn only on the frame a request is waiting for, and it is taken off again after a
 * while without requests. It never starts the video: without a live (or a recording) there are
 * no frames, and a request just gets nothing.
 */
class LivePreviewTap(
    private val scope: CoroutineScope,
    private val videoInput: () -> IVideoInput?,
    /** How hot the phone is: null to go on, or why to wait between frames / not answer. */
    private val heat: () -> Heat,
) {
    enum class Heat { NORMAL, SEVERE, CRITICAL }

    /** A frame, or why there is none. */
    data class Frame(val jpeg: ByteArray?, val reason: String?, val takenAtMs: Long)

    private val thread = HandlerThread("LivePreview", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    /** Drawn by the pipeline only while a frame is wanted. */
    private val wanted = AtomicBoolean(false)
    private val mutex = Mutex()

    private var reader: ImageReader? = null
    private var attachedTo: IVideoInput? = null
    private var bitmap: Bitmap? = null
    private var idleJob: Job? = null

    @Volatile
    private var latest: ByteArray? = null

    @Volatile
    private var latestAtMs = 0L

    @Volatile
    private var waiting: CompletableDeferred<Unit>? = null

    /**
     * The newest frame not older than the rate allows, taking one if needed. Several pages share
     * a frame: a second request within the interval gets the same one.
     */
    suspend fun frame(): Frame {
        val heatNow = heat()
        if (heatNow == Heat.CRITICAL) return Frame(null, "Paused: the phone is too hot", 0)
        val minIntervalMs = if (heatNow == Heat.SEVERE) SEVERE_INTERVAL_MS else MIN_INTERVAL_MS
        return mutex.withLock {
            keepAlive()
            val now = System.currentTimeMillis()
            latest?.takeIf { now - latestAtMs < minIntervalMs }?.let { return@withLock Frame(it, null, latestAtMs) }
            if (!attach()) return@withLock Frame(null, "The picture shows during a live", 0)
            val arrived = CompletableDeferred<Unit>()
            waiting = arrived
            wanted.set(true)
            val got = withTimeoutOrNull(WAIT_MS) { arrived.await() } != null
            wanted.set(false)
            if (got) Frame(latest, null, latestAtMs)
            else Frame(null, "No picture yet: it shows during a live", 0)
        }
    }

    /** Takes the reader off the pipeline after a while without requests. */
    private fun keepAlive() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            mutex.withLock { detach() }
        }
    }

    /**
     * Adds the reader to the live's pipeline, shaped like what goes out (a portrait live is
     * portrait), and again when that shape changed; false when there is no video.
     */
    private suspend fun attach(): Boolean {
        val input = videoInput() ?: return false
        val live = runCatching { input.encoderOutputSize() }.getOrNull() ?: return false
        val size = scaledSize(live)
        val current = reader
        if (current != null && attachedTo === input && current.width == size.width && current.height == size.height) {
            return true
        }
        detach()
        val next = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        next.setOnImageAvailableListener({ onImage(it) }, handler)
        return runCatching {
            input.addExtraOutputSurface(next.surface, size) { wanted.get() }
            reader = next
            attachedTo = input
            Log.i(TAG, "Preview reader on, ${size.width}x${size.height}")
            true
        }.getOrElse {
            Log.w(TAG, "Could not add the preview reader: ${it.message}")
            next.close()
            false
        }
    }

    private suspend fun detach() {
        val current = reader ?: return
        runCatching { attachedTo?.removeExtraOutputSurface(current.surface) }
        reader = null
        attachedTo = null
        latest = null
        handler.post {
            current.close()
            bitmap?.recycle()
            bitmap = null
        }
        Log.i(TAG, "Preview reader off")
    }

    /** On the reader's thread: to JPEG, and the buffer back at once so the pipeline never waits. */
    private fun onImage(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            // The buffer's rows may be padded: take a bitmap as wide as a row, then crop
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded = bitmap?.takeIf { it.width == paddedWidth && it.height == image.height }
                ?: Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888).also {
                    bitmap?.recycle()
                    bitmap = it
                }
            padded.copyPixelsFromBuffer(plane.buffer)
            val frame = if (paddedWidth == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            val out = ByteArrayOutputStream(32 * 1024)
            frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (frame !== padded) frame.recycle()
            latest = out.toByteArray()
            latestAtMs = System.currentTimeMillis()
            wanted.set(false)
            waiting?.complete(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Preview frame failed: ${t.message}")
        } finally {
            image.close()
        }
    }

    fun release() {
        idleJob?.cancel()
        scope.launch {
            mutex.withLock { detach() }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "LivePreviewTap"

        /** The longer side of the picture sent to the page. */
        const val MAX_SIDE = 480

        private const val JPEG_QUALITY = 70
        private const val MIN_INTERVAL_MS = 400L
        private const val SEVERE_INTERVAL_MS = 2_000L
        private const val WAIT_MS = 1_500L
        private const val IDLE_MS = 10_000L

        /** [live] scaled so its longer side is [MAX_SIDE], even sizes (what readers expect). */
        fun scaledSize(live: Size): Size {
            val scale = MAX_SIDE.toFloat() / maxOf(live.width, live.height)
            if (scale >= 1f) return Size(live.width / 2 * 2, live.height / 2 * 2)
            return Size(
                ((live.width * scale).roundToInt() / 2 * 2).coerceAtLeast(2),
                ((live.height * scale).roundToInt() / 2 * 2).coerceAtLeast(2),
            )
        }
    }
}
