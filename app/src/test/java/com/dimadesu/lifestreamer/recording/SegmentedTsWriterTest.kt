package com.dimadesu.lifestreamer.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SegmentedTsWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FileStore(
        private val dir: File,
        var failOnWrite: Throwable? = null,
        var freeBytes: Long = Long.MAX_VALUE,
        var writeDelayMs: Long = 0,
    ) : SegmentStore {
        override fun create(displayName: String): SegmentFile {
            val file = File(dir, displayName)
            val stream = file.outputStream()
            return object : SegmentFile {
                override val name = displayName
                override fun write(buffer: ByteBuffer) {
                    failOnWrite?.let { throw it }
                    if (writeDelayMs > 0) Thread.sleep(writeDelayMs)
                    stream.channel.write(buffer)
                }

                override fun syncData() = Unit
                override fun syncAll() = Unit
                override fun freeBytes() = freeBytes
                override fun close() = stream.close()
            }
        }
    }

    private class RecordingListener : SegmentedTsWriter.Listener {
        val opened = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var failure: RecordingFailure? = null
        val failed = CountDownLatch(1)

        override fun onSegmentOpened(name: String, index: Int) {
            synchronized(opened) { opened += name }
        }

        override fun onWarning(message: String) {
            synchronized(warnings) { warnings += message }
        }

        override fun onFailed(failure: RecordingFailure) {
            this.failure = failure
            failed.countDown()
        }
    }

    private var now = 0L

    private fun packet(fill: Int) = ByteBuffer.wrap(ByteArray(188) { fill.toByte() })

    private fun writer(
        store: SegmentStore,
        listener: SegmentedTsWriter.Listener,
        segmentMs: Long = 60_000,
        maxQueued: Long = 32L shl 20,
    ) = SegmentedTsWriter(
        store, "LifeStreamer_test", segmentMs, listener,
        clock = { now }, maxQueuedBytes = maxQueued, chunkSize = 1024
    ).also { it.start() }

    @Test
    fun `nothing is written before the first key frame`() {
        val listener = RecordingListener()
        val writer = writer(FileStore(folder.root), listener)

        writer.onTsPackets(packet(1))
        writer.onRandomAccessPoint(0)
        writer.onTsPackets(packet(2))
        assertTrue(writer.stop())

        val files = folder.root.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("LifeStreamer_test_001.ts"), files.map { it.name })
        assertArrayEquals(ByteArray(188) { 2 }, files[0].readBytes())
    }

    @Test
    fun `a new segment starts at the first key frame after the duration`() {
        val listener = RecordingListener()
        val writer = writer(FileStore(folder.root), listener, segmentMs = 60_000)

        writer.onRandomAccessPoint(0)
        writer.onTsPackets(packet(1))
        now = 59_000
        writer.onRandomAccessPoint(0) // too early: same segment
        writer.onTsPackets(packet(2))
        now = 61_000
        writer.onTsPackets(packet(3)) // past the duration but not a key frame: same segment
        writer.onRandomAccessPoint(0)
        writer.onTsPackets(packet(4))
        assertTrue(writer.stop())

        val files = folder.root.listFiles()!!.sortedBy { it.name }
        assertEquals(
            listOf("LifeStreamer_test_001.ts", "LifeStreamer_test_002.ts"),
            files.map { it.name })
        assertArrayEquals(
            ByteArray(188) { 1 } + ByteArray(188) { 2 } + ByteArray(188) { 3 },
            files[0].readBytes()
        )
        assertArrayEquals(ByteArray(188) { 4 }, files[1].readBytes())
    }

    @Test
    fun `a discontinuity forces a new segment at the next key frame`() {
        val writer = writer(FileStore(folder.root), RecordingListener())

        writer.onRandomAccessPoint(0)
        writer.onTsPackets(packet(1))
        writer.onDiscontinuity()
        writer.onRandomAccessPoint(0)
        writer.onTsPackets(packet(2))
        assertTrue(writer.stop())

        assertEquals(2, folder.root.listFiles()!!.size)
    }

    @Test
    fun `a full card ends the recording once, with the reason`() {
        val listener = RecordingListener()
        val store = FileStore(folder.root, failOnWrite = IOException("write failed: ENOSPC (No space left on device)"))
        val writer = writer(store, listener)

        writer.onRandomAccessPoint(0)
        repeat(20) { writer.onTsPackets(packet(it)) } // more than a chunk: reaches storage

        assertTrue(listener.failed.await(5, TimeUnit.SECONDS))
        assertEquals(RecordingFailure.StorageFull, listener.failure)
        // Later calls are harmless no-ops
        writer.onTsPackets(packet(1))
        writer.onRandomAccessPoint(0)
        assertTrue(writer.stop())
    }

    @Test
    fun `running low on space ends the recording as full`() {
        val listener = RecordingListener()
        val writer = writer(FileStore(folder.root, freeBytes = 1024), listener)

        writer.onRandomAccessPoint(0) // free space is checked as soon as the segment opens
        writer.onTsPackets(packet(1))

        assertTrue(listener.failed.await(5, TimeUnit.SECONDS))
        assertEquals(RecordingFailure.StorageFull, listener.failure)
        assertTrue(writer.stop())
    }

    @Test
    fun `slow storage drops until the next key frame instead of blocking the muxer`() {
        val listener = RecordingListener()
        val store = FileStore(folder.root, writeDelayMs = 200)
        val writer = writer(store, listener, maxQueued = 4 * 1024)

        writer.onRandomAccessPoint(0)
        val start = System.nanoTime()
        repeat(200) { writer.onTsPackets(packet(it)) } // ~37 KB against a 4 KB queue
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("the muxer thread was held for $elapsedMs ms", elapsedMs < 150)
        assertTrue(listener.warnings.isNotEmpty())
        writer.stop(timeoutMs = 10_000)
    }

    @Test
    fun `failures are classified by meaning`() {
        assertEquals(RecordingFailure.AccessLost, RecordingFailure.from(SecurityException("revoked")))
        assertEquals(
            RecordingFailure.StorageGone,
            RecordingFailure.from(IOException("write failed: EIO (I/O error)"))
        )
        assertEquals(
            RecordingFailure.StorageFull,
            RecordingFailure.from(IOException("x", IOException("ENOSPC")))
        )
        assertTrue(RecordingFailure.from(IOException("odd")) is RecordingFailure.Other)
    }
}
