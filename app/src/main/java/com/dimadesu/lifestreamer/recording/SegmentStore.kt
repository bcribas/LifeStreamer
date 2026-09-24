package com.dimadesu.lifestreamer.recording

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Where recording segments are created. Behind an interface so the writer can be tested on the
 * JVM against plain files; on the device it is [SafSegmentStore].
 */
interface SegmentStore {
    /** Creates a new, empty segment named [displayName] and opens it for writing. */
    fun create(displayName: String): SegmentFile
}

/** One open segment. Used from a single thread, except [close], which may come from another. */
interface SegmentFile : Closeable {
    val name: String

    /** Writes all of [buffer]. */
    fun write(buffer: ByteBuffer)

    /** Flushes the data to the storage (fdatasync). */
    fun syncData()

    /** Flushes data and metadata to the storage (fsync), before closing a finished segment. */
    fun syncAll()

    /** Free bytes on the volume this segment is on. */
    fun freeBytes(): Long
}

/** Why a recording stopped by itself. The live is never affected by any of these. */
sealed class RecordingFailure(val reason: String) {
    object StorageFull : RecordingFailure("SD card full")
    object StorageGone : RecordingFailure("SD card removed or unreadable")
    object AccessLost : RecordingFailure("Folder access lost: pick the folder again")
    class Other(message: String) : RecordingFailure(message)

    override fun toString() = reason

    companion object {
        private val GONE_ERRNOS = setOf("EIO", "ENOENT", "ENODEV", "ENXIO", "EBADF", "EROFS")

        /** Classifies an I/O failure by what it means to the operator. */
        fun from(t: Throwable): RecordingFailure {
            if (t is SecurityException) return AccessLost
            val text = generateSequence(t) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            return when {
                "ENOSPC" in text || "No space left" in text -> StorageFull
                GONE_ERRNOS.any { it in text } -> StorageGone
                else -> Other(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
