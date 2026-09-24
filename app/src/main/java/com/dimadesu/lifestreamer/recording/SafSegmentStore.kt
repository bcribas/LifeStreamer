package com.dimadesu.lifestreamer.recording

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.Os
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Segments as documents in a folder the operator picked through the Storage Access Framework,
 * typically on the SD card.
 */
class SafSegmentStore(private val context: Context, private val treeUri: Uri) : SegmentStore {
    private val parentDocument: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )

    override fun create(displayName: String): SegmentFile {
        val resolver = context.contentResolver
        // octet-stream so the provider keeps the name as given: with a video MIME it appends the
        // extension it maps that MIME to whenever it disagrees with ".ts".
        val document = DocumentsContract.createDocument(
            resolver, parentDocument, "application/octet-stream", displayName
        ) ?: throw IOException("Could not create $displayName (ENOENT: folder unavailable)")
        val pfd = resolver.openFileDescriptor(document, "w")
            ?: throw IOException("Could not open $displayName (ENOENT)")
        return SafSegmentFile(displayName, pfd)
    }

    /** Whether the app still holds write access to the folder, which survives reboots. */
    fun hasPermission(): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isWritePermission
    }

    /** Free bytes on the folder's volume, or null if it cannot be read (e.g. card removed). */
    fun freeBytes(): Long? = runCatching {
        context.contentResolver.openFileDescriptor(parentDocument, "r")?.use {
            val stat = Os.fstatvfs(it.fileDescriptor)
            stat.f_bavail * stat.f_frsize
        }
    }.getOrNull()

    /** "SD card" or "Internal storage", then the folder path, for the settings summary. */
    fun label(): String = describe(context, treeUri)

    private class SafSegmentFile(
        override val name: String,
        private val pfd: ParcelFileDescriptor
    ) : SegmentFile {
        private val channel: FileChannel = FileOutputStream(pfd.fileDescriptor).channel

        override fun write(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }

        override fun syncData() = Os.fdatasync(pfd.fileDescriptor)

        override fun syncAll() = Os.fsync(pfd.fileDescriptor)

        override fun freeBytes(): Long {
            val stat = Os.fstatvfs(pfd.fileDescriptor)
            return stat.f_bavail * stat.f_frsize
        }

        override fun close() {
            runCatching { channel.close() }
            pfd.close()
        }
    }

    companion object {
        /** The volume id in a tree document id: "primary" or the SD card's, e.g. "1A2B-3C4D". */
        fun volumeId(treeUri: Uri): String? =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':') }
                .getOrNull()

        fun describe(context: Context, treeUri: Uri): String {
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
                .getOrNull() ?: return treeUri.toString()
            val volume = documentId.substringBefore(':')
            val path = documentId.substringAfter(':', "")
            val where = if (volume == "primary") {
                "Internal storage"
            } else {
                context.getSystemService(StorageManager::class.java)?.storageVolumes
                    ?.firstOrNull { it.uuid == volume }
                    ?.getDescription(context) ?: "SD card"
            }
            return if (path.isEmpty()) where else "$where / $path"
        }
    }
}
