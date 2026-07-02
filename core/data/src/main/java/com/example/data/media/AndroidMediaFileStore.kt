package com.example.data.media

import android.content.Context
import android.net.Uri
import com.example.domain.model.media.LocalMediaInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

class AndroidMediaFileStore(
    private val context: Context
) : MediaFileStore {

    private val importDir = File(context.cacheDir, "media/import")
    private val masterDir = File(context.filesDir, "media/master")
    private val thumbnailDir = File(context.filesDir, "media/thumbnail")

    init {
        importDir.mkdirs()
        masterDir.mkdirs()
        thumbnailDir.mkdirs()
        File(context.cacheDir, "media/camera").mkdirs()
        File(context.cacheDir, "media/ai").mkdirs()
    }

    override suspend fun copyToStaging(
        mediaId: String,
        input: LocalMediaInput,
        maxSizeBytes: Long
    ): File = withContext(Dispatchers.IO) {
        val stagingPartFile = File(importDir, "$mediaId.source.part")
        val stagingFinalFile = File(importDir, "$mediaId.source")

        // Clean up any stale files from previous runs
        stagingPartFile.delete()
        stagingFinalFile.delete()

        val inputStream: InputStream = when (input) {
            is LocalMediaInput.ContentReference -> {
                val uri = Uri.parse(input.opaqueValue)
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Failed to open input stream for Uri: ${input.opaqueValue}")
            }
            is LocalMediaInput.AppCacheFile -> {
                val validatedFile = validateAppCacheFile(input.relativePath)
                if (!validatedFile.exists()) {
                    throw IOException("App cache file does not exist: ${input.relativePath}")
                }
                validatedFile.inputStream()
            }
        }

        try {
            val buffer = ByteArray(8192)
            var totalBytes = 0L
            stagingPartFile.outputStream().use { output ->
                inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        if (totalBytes > maxSizeBytes) {
                            throw SourceTooLargeException("Source file exceeds maximum size limit of $maxSizeBytes bytes")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            stagingPartFile.delete()
            throw e
        }

        // Perform atomic rename in same directory
        val renamed = stagingPartFile.renameTo(stagingFinalFile)
        if (!renamed) {
            stagingPartFile.delete()
            stagingFinalFile.delete()
            throw IOException("Failed to rename staging part file to final file for $mediaId")
        }

        stagingFinalFile
    }

    override fun getStagingSourceFile(mediaId: String): File {
        return File(importDir, "$mediaId.source")
    }

    override fun openStagingSource(mediaId: String): InputStream {
        val file = getStagingSourceFile(mediaId)
        if (!file.exists()) {
            throw IOException("Staging source file does not exist for $mediaId")
        }
        return file.inputStream()
    }

    override fun deleteStagingSource(mediaId: String): Boolean {
        return getStagingSourceFile(mediaId).delete()
    }

    override suspend fun writeReadyFilesAtomically(
        mediaId: String,
        writeAction: (File, File) -> Unit
    ): ReadyFilesPaths = withContext(Dispatchers.IO) {
        val masterPart = File(masterDir, "$mediaId.jpg.part")
        val masterFinal = File(masterDir, "$mediaId.jpg")
        val thumbPart = File(thumbnailDir, "$mediaId.jpg.part")
        val thumbFinal = File(thumbnailDir, "$mediaId.jpg")

        // Clean up stale files
        masterPart.delete()
        masterFinal.delete()
        thumbPart.delete()
        thumbFinal.delete()

        try {
            writeAction(masterPart, thumbPart)
        } catch (e: Exception) {
            masterPart.delete()
            thumbPart.delete()
            throw e
        }

        // Rename master
        val masterRenamed = masterPart.renameTo(masterFinal)
        if (!masterRenamed) {
            masterPart.delete()
            masterFinal.delete()
            thumbPart.delete()
            throw IOException("Failed to atomically rename master image for $mediaId")
        }

        // Rename thumbnail
        val thumbRenamed = thumbPart.renameTo(thumbFinal)
        if (!thumbRenamed) {
            masterFinal.delete()
            thumbPart.delete()
            thumbFinal.delete()
            throw IOException("Failed to atomically rename thumbnail image for $mediaId")
        }

        ReadyFilesPaths(
            masterRelativePath = "media/master/$mediaId.jpg",
            thumbnailRelativePath = "media/thumbnail/$mediaId.jpg",
            masterFile = masterFinal,
            thumbnailFile = thumbFinal
        )
    }

    override fun deleteAllFiles(mediaId: String): Boolean {
        val files = listOf(
            File(importDir, "$mediaId.source"),
            File(importDir, "$mediaId.source.part"),
            File(masterDir, "$mediaId.jpg"),
            File(masterDir, "$mediaId.jpg.part"),
            File(thumbnailDir, "$mediaId.jpg"),
            File(thumbnailDir, "$mediaId.jpg.part")
        )
        var deletedAny = false
        for (file in files) {
            if (file.exists() && file.delete()) {
                deletedAny = true
            }
        }
        return deletedAny
    }

    override fun deletePartFiles(mediaId: String) {
        File(importDir, "$mediaId.source.part").delete()
        File(masterDir, "$mediaId.jpg.part").delete()
        File(thumbnailDir, "$mediaId.jpg.part").delete()
    }

    override fun getAiRequestDirectory(requestId: String): File {
        val safeRequestId = validateSafeFileName(requestId)
        val dir = File(File(context.cacheDir, "media/ai"), safeRequestId)
        dir.mkdirs()
        return dir
    }

    override fun deleteAiRequestDirectory(requestId: String): Boolean {
        val safeRequestId = validateSafeFileName(requestId)
        val dir = File(File(context.cacheDir, "media/ai"), safeRequestId)
        return deleteRecursivelyUnderAllowedRoot(dir)
    }

    override fun cleanupStaleAiRequestDirectories(olderThanMillis: Long): List<String> {
        val aiRoot = File(context.cacheDir, "media/ai")
        aiRoot.mkdirs()
        val cutoff = System.currentTimeMillis() - olderThanMillis
        val removed = mutableListOf<String>()
        aiRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.lastModified() < cutoff) {
                val requestId = dir.name
                if (deleteRecursivelyUnderAllowedRoot(dir)) {
                    removed.add(requestId)
                }
            }
        }
        return removed
    }

    private fun validateAppCacheFile(relativePath: String): File {
        require(!relativePath.contains("..")) { "Path traversal not allowed" }
        val cacheRoot = context.cacheDir.canonicalFile
        val targetFile = File(cacheRoot, relativePath).canonicalFile

        val allowedRoots = listOf(
            File(cacheRoot, "media/camera").canonicalFile,
            File(cacheRoot, "media/import").canonicalFile,
            File(cacheRoot, "media/ai").canonicalFile
        )

        val isAllowed = allowedRoots.any { root ->
            targetFile.path.startsWith(root.path + File.separator) || targetFile.path == root.path
        }
        require(isAllowed) { "Access to cache path is not allowed: $relativePath" }

        return targetFile
    }

    private fun validateSafeFileName(name: String): String {
        require(name.isNotBlank()) { "File name must not be blank" }
        require(!name.contains("..")) { "Path traversal not allowed in file name: $name" }
        require(!name.contains('/') && !name.contains('\\')) { "File name must not contain path separators: $name" }
        require(name != "." && name != "..") { "Invalid file name: $name" }
        return name
    }

    private fun deleteRecursivelyUnderAllowedRoot(target: File): Boolean {
        val aiRoot = File(context.cacheDir, "media/ai").canonicalFile
        val canonicalTarget = target.canonicalFile
        val isAllowed = canonicalTarget.path == aiRoot.path ||
            canonicalTarget.path.startsWith(aiRoot.path + File.separator)
        require(isAllowed) { "Can only delete files under AI cache root: ${target.path}" }

        if (!canonicalTarget.exists()) return false
        return canonicalTarget.deleteRecursively()
    }
}

class SourceTooLargeException(message: String) : IllegalArgumentException(message)
