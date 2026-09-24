package com.dimadesu.lifestreamer.recording

import android.os.SystemClock
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ITsPacketTap
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes an MPEG-TS stream to a series of segment files, a new one at the first video key frame
 * after every [segmentDurationMs], so each segment starts with its own PAT and PMT and plays on
 * its own. A phone that dies mid-recording loses at most the last few seconds of one segment.
 *
 * It is fed on the muxing thread ([ITsPacketTap]), which must never wait on storage: bytes are
 * copied into chunks and a thread of its own writes them. If storage falls behind by more than
 * [maxQueuedBytes], it drops data until the next key frame and starts a new segment there,
 * rather than holding up the live. Any storage error ends the recording once, with a reason
 * ([Listener.onFailed]); nothing it does can throw into the muxer.
 */
class SegmentedTsWriter(
    private val store: SegmentStore,
    /** Name shared by the session's segments, e.g. "LifeStreamer_2026-09-24_14-03-05". */
    private val sessionName: String,
    private val segmentDurationMs: Long,
    private val listener: Listener,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val maxQueuedBytes: Long = 32L shl 20,
    private val minFreeBytes: Long = 200L shl 20,
    private val syncIntervalMs: Long = 10_000,
    private val chunkSize: Int = 64 * 1024,
) : ITsPacketTap {

    interface Listener {
        /** A new segment file was created, on the writer thread. */
        fun onSegmentOpened(name: String, index: Int)

        /** Something was lost but the recording goes on (storage fell behind). */
        fun onWarning(message: String)

        /** The recording ended by itself. Called once, on the writer thread. */
        fun onFailed(failure: RecordingFailure)
    }

    data class Stats(
        val bytesWritten: Long,
        val segmentIndex: Int,
        val segmentName: String?,
        val freeBytes: Long?,
        val droppedBytes: Long,
    )

    private enum class State { WAITING_FIRST_RAP, RECORDING, DROPPING_UNTIL_RAP, FAILED, STOPPED }

    private sealed interface Command {
        class Data(val bytes: ByteArray, val length: Int) : Command
        object Open : Command
        object Rotate : Command
        object Stop : Command
    }

    // Muxer side, guarded by lock
    private val lock = Any()
    private var state = State.WAITING_FIRST_RAP
    private var chunk = ByteArray(chunkSize)
    private var chunkFill = 0
    private var segmentStartMs = 0L
    private var forceRotate = false
    private var lastDropWarningMs = Long.MIN_VALUE

    // Shared
    private val queue = LinkedBlockingQueue<Command>()
    private val queuedBytes = AtomicLong(0)
    private val bytesWritten = AtomicLong(0)
    private val droppedBytes = AtomicLong(0)

    @Volatile
    private var failed = false

    @Volatile
    private var currentFile: SegmentFile? = null

    @Volatile
    private var segmentIndex = 0

    @Volatile
    private var lastFreeBytes: Long? = null

    private val thread = Thread(::writerLoop, "TsRecorder").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun stats() = Stats(
        bytesWritten = bytesWritten.get(),
        segmentIndex = segmentIndex,
        segmentName = currentFile?.name,
        freeBytes = lastFreeBytes,
        droppedBytes = droppedBytes.get(),
    )

    // region ITsPacketTap, on the muxing thread

    override fun onTsPackets(buffer: ByteBuffer) {
        if (failed) return
        synchronized(lock) {
            if (state != State.RECORDING) {
                if (state == State.DROPPING_UNTIL_RAP) droppedBytes.addAndGet(buffer.remaining().toLong())
                return
            }
            if (queuedBytes.get() + chunkFill > maxQueuedBytes) {
                // Storage cannot keep up. Whatever is queued still lands; the rest waits for a
                // key frame, where a new segment makes the gap a clean cut.
                chunkFill = 0
                state = State.DROPPING_UNTIL_RAP
                droppedBytes.addAndGet(buffer.remaining().toLong())
                warnDropping()
                return
            }
            val source = buffer.duplicate()
            while (source.hasRemaining()) {
                val count = minOf(source.remaining(), chunk.size - chunkFill)
                source.get(chunk, chunkFill, count)
                chunkFill += count
                if (chunkFill == chunk.size) flushChunkLocked()
            }
        }
    }

    override fun onRandomAccessPoint(ptsInUs: Long) {
        if (failed) return
        synchronized(lock) {
            when (state) {
                State.WAITING_FIRST_RAP -> {
                    queue.put(Command.Open)
                    segmentStartMs = clock()
                    state = State.RECORDING
                }

                State.DROPPING_UNTIL_RAP -> {
                    if (queuedBytes.get() < maxQueuedBytes / 2) {
                        queue.put(Command.Rotate)
                        segmentStartMs = clock()
                        forceRotate = false
                        state = State.RECORDING
                    }
                }

                State.RECORDING -> {
                    if (forceRotate || clock() - segmentStartMs >= segmentDurationMs) {
                        flushChunkLocked()
                        queue.put(Command.Rotate)
                        segmentStartMs = clock()
                        forceRotate = false
                    }
                }

                State.FAILED, State.STOPPED -> Unit
            }
        }
    }

    override fun onDiscontinuity() {
        synchronized(lock) { forceRotate = true }
    }

    // endregion

    /**
     * Ends the recording: what is buffered is written and the last segment synced and closed.
     * Returns false if storage did not finish within [timeoutMs]; the file is then closed from
     * here and the writer thread abandoned, so a hung card can never hold the caller.
     */
    fun stop(timeoutMs: Long = 5_000): Boolean {
        synchronized(lock) {
            if (state == State.STOPPED) return true
            if (state == State.RECORDING) flushChunkLocked()
            state = State.STOPPED
        }
        queue.put(Command.Stop)
        if (!thread.isAlive) return true
        thread.join(timeoutMs)
        if (thread.isAlive) {
            Log.w(TAG, "Storage did not finish within $timeoutMs ms; closing from outside")
            runCatching { currentFile?.close() }
            return false
        }
        return true
    }

    /** Hands the current chunk to the writer thread. Caller holds [lock]. */
    private fun flushChunkLocked() {
        if (chunkFill == 0) return
        queue.put(Command.Data(chunk, chunkFill))
        queuedBytes.addAndGet(chunkFill.toLong())
        chunk = ByteArray(chunkSize)
        chunkFill = 0
    }

    private fun warnDropping() {
        val now = clock()
        if (lastDropWarningMs == Long.MIN_VALUE || now - lastDropWarningMs > 10_000) {
            lastDropWarningMs = now
            listener.onWarning("Storage is too slow: part of the recording was dropped")
        }
    }

    // region writer thread

    private fun writerLoop() {
        var lastSyncMs = clock()
        try {
            while (true) {
                val command = queue.poll(1, TimeUnit.SECONDS)
                if (command == null) {
                    // Idle: pick up a part-filled chunk so a quiet stream still reaches storage.
                    synchronized(lock) {
                        if (state == State.RECORDING) flushChunkLocked()
                    }
                } else if (!execute(command)) {
                    return
                }
                val now = clock()
                if (now - lastSyncMs >= syncIntervalMs) {
                    lastSyncMs = now
                    currentFile?.let { file ->
                        file.syncData()
                        val free = file.freeBytes()
                        lastFreeBytes = free
                        if (free < minFreeBytes) throw java.io.IOException("ENOSPC: $free bytes left")
                    }
                }
            }
        } catch (t: Throwable) {
            fail(RecordingFailure.from(t), t)
        }
    }

    /** Returns false when the loop must end. */
    private fun execute(command: Command): Boolean {
        when (command) {
            is Command.Data -> {
                currentFile?.write(ByteBuffer.wrap(command.bytes, 0, command.length))
                queuedBytes.addAndGet(-command.length.toLong())
                bytesWritten.addAndGet(command.length.toLong())
            }

            Command.Open -> openNextSegment()
            Command.Rotate -> {
                closeCurrentSegment()
                openNextSegment()
            }

            Command.Stop -> {
                closeCurrentSegment()
                return false
            }
        }
        return true
    }

    private fun openNextSegment() {
        val free = currentFile?.freeBytes() ?: lastFreeBytes
        if (free != null && free < minFreeBytes) throw java.io.IOException("ENOSPC: $free bytes left")
        val index = segmentIndex + 1
        val name = "%s_%03d.ts".format(sessionName, index)
        val file = store.create(name)
        currentFile = file
        segmentIndex = index
        // Checked right away too, not only at the next periodic sync: a card that is already
        // nearly full refuses at once.
        val freeNow = runCatching { file.freeBytes() }.getOrNull()
        lastFreeBytes = freeNow
        if (freeNow != null && freeNow < minFreeBytes) throw java.io.IOException("ENOSPC: $freeNow bytes left")
        Log.i(TAG, "Recording to $name")
        listener.onSegmentOpened(name, index)
    }

    private fun closeCurrentSegment() {
        val file = currentFile ?: return
        currentFile = null
        try {
            file.syncAll()
        } finally {
            file.close()
        }
    }

    private fun fail(failure: RecordingFailure, cause: Throwable) {
        if (failed) return
        failed = true
        synchronized(lock) { state = State.FAILED }
        Log.w(TAG, "Recording stopped: $failure", cause)
        runCatching { currentFile?.close() }
        currentFile = null
        queue.clear()
        queuedBytes.set(0)
        listener.onFailed(failure)
    }

    // endregion

    private companion object {
        const val TAG = "SegmentedTsWriter"
    }
}
