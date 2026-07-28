package com.goings.dayzero.data.repository

import com.goings.dayzero.data.media.MediaFileStore
import com.goings.dayzero.data.media.MediaImageProcessor
import com.goings.dayzero.data.media.OutOfMemoryException
import com.goings.dayzero.data.media.ProcessedImageMetadata
import com.goings.dayzero.data.media.SourceTooLargeException
import com.goings.dayzero.domain.model.media.ImportLocalMediaRequest
import com.goings.dayzero.domain.model.media.LocalMediaImportItemResult
import com.goings.dayzero.domain.model.media.MediaImportFailureCode
import com.goings.dayzero.domain.repository.LocalMediaImportRepository
import com.goings.dayzero.domain.repository.MediaRepository
import com.goings.dayzero.domain.usecase.ImportLocalMediaUseCase
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.MessageDigest
class AndroidLocalMediaImportRepository(
    private val mediaRepository: MediaRepository,
    private val fileStore: MediaFileStore,
    private val imageProcessor: MediaImageProcessor
) : LocalMediaImportRepository {

    override suspend fun importStagedMedia(
        mediaId: String,
        request: ImportLocalMediaRequest
    ): LocalMediaImportItemResult {
        val now = System.currentTimeMillis()

        // 1. Copy source to staging
        val stagingFile = try {
            fileStore.copyToStaging(
                mediaId = mediaId,
                input = request.input,
                maxSizeBytes = ImportLocalMediaUseCase.MAX_SOURCE_FILE_SIZE_BYTES
            )
        } catch (c: CancellationException) {
            fileStore.deletePartFiles(mediaId)
            throw c
        } catch (e: SourceTooLargeException) {
            return markFailedAndReturn(mediaId, request.conversationId, MediaImportFailureCode.SOURCE_TOO_LARGE, now)
        } catch (e: IllegalArgumentException) {
            return markFailedAndReturn(mediaId, request.conversationId, MediaImportFailureCode.SOURCE_OPEN_FAILED, now)
        } catch (e: Exception) {
            return markFailedAndReturn(mediaId, request.conversationId, MediaImportFailureCode.SOURCE_OPEN_FAILED, now)
        }

        // 2. Process image and write outputs atomically
        var metadata: ProcessedImageMetadata? = null
        val paths = try {
            fileStore.writeReadyFilesAtomically(mediaId) { masterFile, thumbFile ->
                metadata = imageProcessor.processImage(stagingFile, masterFile, thumbFile)
            }
        } catch (c: CancellationException) {
            fileStore.deletePartFiles(mediaId)
            throw c
        } catch (oom: OutOfMemoryException) {
            fileStore.deletePartFiles(mediaId)
            return markFailedAndReturn(mediaId, request.conversationId, MediaImportFailureCode.OUT_OF_MEMORY, now)
        } catch (e: IllegalArgumentException) {
            fileStore.deletePartFiles(mediaId)
            val code = when {
                e.message?.contains("dimensions", ignoreCase = true) == true -> MediaImportFailureCode.INVALID_DIMENSIONS
                e.message?.contains("area", ignoreCase = true) == true -> MediaImportFailureCode.INVALID_DIMENSIONS
                e.message?.contains("format", ignoreCase = true) == true -> MediaImportFailureCode.UNSUPPORTED_FORMAT
                e.message?.contains("GIF", ignoreCase = true) == true -> MediaImportFailureCode.UNSUPPORTED_FORMAT
                else -> MediaImportFailureCode.DECODE_FAILED
            }
            return markFailedAndReturn(mediaId, request.conversationId, code, now)
        } catch (e: Exception) {
            fileStore.deletePartFiles(mediaId)
            return markFailedAndReturn(mediaId, request.conversationId, MediaImportFailureCode.WRITE_FAILED, now)
        }

        val meta = metadata ?: return markFailedAndReturn(
            mediaId = mediaId,
            conversationId = request.conversationId,
            failureCode = MediaImportFailureCode.DECODE_FAILED,
            now = now
        )

        // 3. Compute final master image stats (SHA-256 and size)
        val (sha256, byteSize) = try {
            val bytes = paths.masterFile.readBytes()
            val hash = calculateSha256(bytes)
            Pair(hash, bytes.size.toLong())
        } catch (c: CancellationException) {
            fileStore.deletePartFiles(mediaId)
            throw c
        } catch (e: Exception) {
            fileStore.deletePartFiles(mediaId)
            return markFailedAndReturn(mediaId, request.conversationId, MediaImportFailureCode.HASH_FAILED, now)
        }

        // 4. Update database status to READY
        val asset = try {
            mediaRepository.markMediaReady(
                id = mediaId,
                conversationId = request.conversationId,
                masterRelativePath = paths.masterRelativePath,
                thumbnailRelativePath = paths.thumbnailRelativePath,
                mimeType = meta.mimeType,
                width = meta.width,
                height = meta.height,
                byteSize = byteSize,
                sha256 = sha256,
                now = now
            )
        } catch (c: CancellationException) {
            fileStore.deletePartFiles(mediaId)
            throw c
        } catch (e: Exception) {
            fileStore.deletePartFiles(mediaId)
            paths.masterFile.delete()
            paths.thumbnailFile.delete()
            try {
                mediaRepository.markMediaFailed(mediaId, request.conversationId, MediaImportFailureCode.DATABASE_UPDATE_FAILED.name, now)
            } catch (ignored: Exception) {}
            return LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.DATABASE_UPDATE_FAILED)
        }

        // 5. Delete staging source after successful READY database update
        try {
            fileStore.deleteStagingSource(mediaId)
        } catch (e: Exception) {
            System.err.println("Warning: Failed to delete staging source for media $mediaId after successful import: ${e.message}")
        }

        return LocalMediaImportItemResult.Ready(mediaId, asset)
    }

    override suspend fun retryImport(mediaId: String): LocalMediaImportItemResult {
        val assets = mediaRepository.getMediaByIds(listOf(mediaId))
        if (assets.isEmpty()) {
            return LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.SOURCE_MISSING)
        }
        val asset = assets.first()
        if (asset.deletedAt != null) {
            throw IllegalArgumentException("Cannot retry a soft-deleted media asset: $mediaId")
        }

        val now = System.currentTimeMillis()

        // 1. Verify that the staging source file exists on disk
        val stagingFile = fileStore.getStagingSourceFile(mediaId)
        if (!stagingFile.exists()) {
            return markFailedAndReturn(mediaId, asset.conversationId, MediaImportFailureCode.SOURCE_MISSING, now)
        }

        // 2. Process image and write outputs atomically
        var metadata: ProcessedImageMetadata? = null
        val paths = try {
            fileStore.writeReadyFilesAtomically(mediaId) { masterFile, thumbFile ->
                metadata = imageProcessor.processImage(stagingFile, masterFile, thumbFile)
            }
        } catch (c: CancellationException) {
            fileStore.deletePartFiles(mediaId)
            throw c
        } catch (oom: OutOfMemoryException) {
            fileStore.deletePartFiles(mediaId)
            return markFailedAndReturn(mediaId, asset.conversationId, MediaImportFailureCode.OUT_OF_MEMORY, now)
        } catch (e: IllegalArgumentException) {
            fileStore.deletePartFiles(mediaId)
            val code = when {
                e.message?.contains("dimensions", ignoreCase = true) == true -> MediaImportFailureCode.INVALID_DIMENSIONS
                e.message?.contains("area", ignoreCase = true) == true -> MediaImportFailureCode.INVALID_DIMENSIONS
                e.message?.contains("format", ignoreCase = true) == true -> MediaImportFailureCode.UNSUPPORTED_FORMAT
                e.message?.contains("GIF", ignoreCase = true) == true -> MediaImportFailureCode.UNSUPPORTED_FORMAT
                else -> MediaImportFailureCode.DECODE_FAILED
            }
            return markFailedAndReturn(mediaId, asset.conversationId, code, now)
        } catch (e: Exception) {
            fileStore.deletePartFiles(mediaId)
            return markFailedAndReturn(mediaId, asset.conversationId, MediaImportFailureCode.WRITE_FAILED, now)
        }

        val meta = metadata ?: return markFailedAndReturn(
            mediaId = mediaId,
            conversationId = asset.conversationId,
            failureCode = MediaImportFailureCode.DECODE_FAILED,
            now = now
        )

        // 3. Compute final master stats
        val (sha256, byteSize) = try {
            val bytes = paths.masterFile.readBytes()
            val hash = calculateSha256(bytes)
            Pair(hash, bytes.size.toLong())
        } catch (c: CancellationException) {
            fileStore.deletePartFiles(mediaId)
            throw c
        } catch (e: Exception) {
            fileStore.deletePartFiles(mediaId)
            return markFailedAndReturn(mediaId, asset.conversationId, MediaImportFailureCode.HASH_FAILED, now)
        }

        // 4. Update database status to READY
        val updatedAsset = try {
            mediaRepository.markMediaReady(
                id = mediaId,
                conversationId = asset.conversationId,
                masterRelativePath = paths.masterRelativePath,
                thumbnailRelativePath = paths.thumbnailRelativePath,
                mimeType = meta.mimeType,
                width = meta.width,
                height = meta.height,
                byteSize = byteSize,
                sha256 = sha256,
                now = now
            )
        } catch (c: CancellationException) {
            fileStore.deletePartFiles(mediaId)
            throw c
        } catch (e: Exception) {
            fileStore.deletePartFiles(mediaId)
            paths.masterFile.delete()
            paths.thumbnailFile.delete()
            try {
                mediaRepository.markMediaFailed(mediaId, asset.conversationId, MediaImportFailureCode.DATABASE_UPDATE_FAILED.name, now)
            } catch (ignored: Exception) {}
            return LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.DATABASE_UPDATE_FAILED)
        }

        // 5. Delete staging source after successful READY database update
        try {
            fileStore.deleteStagingSource(mediaId)
        } catch (e: Exception) {
            System.err.println("Warning: Failed to delete staging source for media $mediaId after successful retry: ${e.message}")
        }

        return LocalMediaImportItemResult.Ready(mediaId, updatedAsset)
    }

    override suspend fun discardStagedMedia(mediaId: String): Boolean {
        return fileStore.deleteAllFiles(mediaId)
    }

    override suspend fun cleanupStaleMedia(updatedBefore: Long): List<String> {
        val candidates = mediaRepository.findStaleStagedMedia(updatedBefore)
        val cleaned = mutableListOf<String>()
        for (asset in candidates) {
            if (asset.deletedAt == null && asset.sourceMessageId == null) {
                if (fileStore.deleteAllFiles(asset.id)) {
                    cleaned.add(asset.id)
                }
            }
        }
        return cleaned
    }

    private suspend fun markFailedAndReturn(
        mediaId: String,
        conversationId: String,
        failureCode: MediaImportFailureCode,
        now: Long
    ): LocalMediaImportItemResult {
        try {
            mediaRepository.markMediaFailed(mediaId, conversationId, failureCode.name, now)
        } catch (dbEx: Exception) {
            throw dbEx
        }
        return LocalMediaImportItemResult.Failed(mediaId, failureCode)
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { String.format("%02x", it) }
    }
}
