package com.goings.dayzero.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.goings.dayzero.data.local.dao.AiChatMessageDao
import com.goings.dayzero.data.local.dao.MediaAssetDao
import com.goings.dayzero.data.local.entity.AiChatMessageEntity
import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.data.media.AiImageDerivativeProcessor
import com.goings.dayzero.data.media.MediaFileStore
import com.goings.dayzero.data.media.OutOfMemoryException
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.assistant.PrepareVisionAttachmentsRequest
import com.goings.dayzero.domain.model.ai.assistant.PreparedVisionAttachment
import com.goings.dayzero.domain.model.ai.assistant.PreparedVisionRequest
import com.goings.dayzero.domain.model.ai.assistant.VisionPreparationFailure
import com.goings.dayzero.domain.model.media.MediaLifecycleState
import com.goings.dayzero.domain.repository.VisionAttachmentPreparationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Android implementation of [VisionAttachmentPreparationRepository].
 *
 * Reads the persisted user message as the authoritative input, validates every media
 * binding, generates AI-specific JPEG derivatives, encodes them as Base64, and keeps
 * all transient files isolated under [cache/media/ai/{requestId}].
 */
class AndroidVisionAttachmentPreparationRepository(
    context: Context,
    private val messageDao: AiChatMessageDao,
    private val mediaDao: MediaAssetDao,
    private val fileStore: MediaFileStore,
    private val derivativeProcessor: AiImageDerivativeProcessor
) : VisionAttachmentPreparationRepository {

    private val filesDir = context.filesDir
    private val tag = "VisionPrep"

    override suspend fun prepare(
        request: PrepareVisionAttachmentsRequest
    ): Result<PreparedVisionRequest> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // Opportunity stale cleanup: ignore failures, do not let it block prepare.
            try {
                val removed = fileStore.cleanupStaleAiRequestDirectories(STALE_DIRECTORY_TTL_MILLIS)
                if (removed.isNotEmpty()) {
                    Log.d(tag, "cleaned up stale AI request directories: $removed")
                }
            } catch (_: Exception) {
                // Non-fatal: stale cleanup failure is logged but not reported.
            }

            val message = loadAndValidateMessage(request)
                ?: return@withContext Result.failure(
                    VisionPreparationFailure.MessageNotFound(
                        "User message ${request.userMessageId} not found"
                    )
                )

            if (message.role != ChatRole.User.name) {
                return@withContext Result.failure(
                    VisionPreparationFailure.InvalidMessage(
                        "Message ${request.userMessageId} is not a user message"
                    )
                )
            }
            if (message.deletedAt != null) {
                return@withContext Result.failure(
                    VisionPreparationFailure.InvalidMessage(
                        "Message ${request.userMessageId} is soft-deleted"
                    )
                )
            }

            val sourceMediaIds = parseSourceMediaIds(message)
                ?: return@withContext Result.failure(
                    VisionPreparationFailure.InvalidMediaContract(
                        "Message ${request.userMessageId} has no valid media contract"
                    )
                )

            validateSourceMediaIds(sourceMediaIds)?.let { failure ->
                return@withContext Result.failure(failure)
            }

            val mediaAssets = try {
                loadAndValidateMedia(
                    sourceMediaIds = sourceMediaIds,
                    request = request
                ) ?: return@withContext Result.failure(
                    VisionPreparationFailure.MediaNotFound(
                        "Could not load all media for message ${request.userMessageId}"
                    )
                )
            } catch (e: VisionPreparationFailureException) {
                return@withContext Result.failure(e.failure)
            }

            // Clean any leftovers from a previous prepare attempt for the same requestId.
            fileStore.deleteAiRequestDirectory(request.requestId)

            val attachments = mutableListOf<PreparedVisionAttachment>()
            var totalByteSize = 0L

            for ((index, media) in mediaAssets.withIndex()) {
                ensureActive()

                val derivativeFile = createDerivativeFile(request.requestId, media.id)
                val partFile = File(derivativeFile.parentFile, derivativeFile.name + ".part")

                try {
                    derivativeProcessor.createAiDerivative(
                        sourceFile = media.masterFile,
                        destFile = partFile
                    )

                    if (!partFile.renameTo(derivativeFile)) {
                        throw IOException("Failed to rename derivative file for ${media.id}")
                    }

                    val byteSize = derivativeFile.length()
                    if (byteSize > PreparedVisionRequest.MAX_SINGLE_ATTACHMENT_BYTES) {
                        return@withContext Result.failure(
                            VisionPreparationFailure.ImageTooLarge(
                                "Derivative for ${media.id} exceeds single file limit"
                            )
                        )
                    }

                    val bytes = derivativeFile.readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (base64.isBlank()) {
                        return@withContext Result.failure(
                            VisionPreparationFailure.Base64Failed(
                                "Base64 encoding produced empty output for ${media.id}"
                            )
                        )
                    }

                    totalByteSize += byteSize
                    if (totalByteSize > PreparedVisionRequest.MAX_TOTAL_ATTACHMENT_BYTES) {
                        return@withContext Result.failure(
                            VisionPreparationFailure.TotalPayloadTooLarge(
                                "Total attachment size exceeds ${PreparedVisionRequest.MAX_TOTAL_ATTACHMENT_BYTES} bytes"
                            )
                        )
                    }

                    attachments.add(
                        PreparedVisionAttachment(
                            mediaId = media.id,
                            mimeType = PreparedVisionRequest.MIME_TYPE_JPEG,
                            base64 = base64,
                            byteSize = byteSize
                        )
                    )
                } catch (oom: OutOfMemoryError) {
                    System.gc()
                    return@withContext Result.failure(
                        VisionPreparationFailure.OutOfMemory(
                            "Out of memory while processing ${media.id}"
                        )
                    )
                } catch (e: CancellationException) {
                    cleanupRequestDirectory(request.requestId)
                    throw e
                } catch (e: Exception) {
                    return@withContext Result.failure(mapProcessingError(e, media.id))
                } finally {
                    partFile.delete()
                }
            }

            val effectiveAiText = message.text.trim().ifBlank {
                PreparedVisionRequest.IMAGE_ONLY_PROMPT
            }

            val durationMs = System.currentTimeMillis() - startTime
            Log.d(
                tag,
                "prepared vision request " +
                    "requestId=${request.requestId} " +
                    "userMessageId=${request.userMessageId} " +
                    "attachmentCount=${attachments.size} " +
                    "totalByteSize=$totalByteSize " +
                    "durationMs=$durationMs"
            )

            Result.success(
                PreparedVisionRequest(
                    requestId = request.requestId,
                    conversationId = request.conversationId,
                    userMessageId = request.userMessageId,
                    effectiveAiText = effectiveAiText,
                    attachments = attachments
                )
            )
        } catch (e: CancellationException) {
            cleanupRequestDirectory(request.requestId)
            throw e
        } catch (e: Exception) {
            Result.failure(
                VisionPreparationFailure.Unknown(
                    "Unexpected error preparing vision request: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun release(requestId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cleanupRequestDirectory(requestId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                VisionPreparationFailure.Unknown(
                    "Failed to release vision attachments for $requestId: ${e.message}",
                    e
                )
            )
        }
    }

    private suspend fun loadAndValidateMessage(request: PrepareVisionAttachmentsRequest): AiChatMessageEntity? {
        val message = messageDao.getMessageByIdIncludingDeleted(request.userMessageId) ?: return null
        if (message.conversationId != request.conversationId) {
            return null
        }
        return message
    }

    private fun parseSourceMediaIds(message: AiChatMessageEntity): List<String>? {
        val contentJson = message.contentJson ?: return null
        return try {
            val json = JSONObject(contentJson)
            val media = json.optJSONObject(MEDIA_KEY) ?: return null
            val schemaVersion = media.optInt(SCHEMA_VERSION_KEY, -1)
            if (schemaVersion != MEDIA_SCHEMA_VERSION) {
                return null
            }
            val array = media.optJSONArray(SOURCE_MEDIA_IDS_KEY) ?: return null
            val ids = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val id = array.optString(i).takeIf { it.isNotBlank() } ?: return null
                ids.add(id)
            }
            if (ids.isEmpty()) return null
            ids
        } catch (_: Exception) {
            null
        }
    }

    private fun validateSourceMediaIds(sourceMediaIds: List<String>): VisionPreparationFailure? {
        if (sourceMediaIds.size !in PreparedVisionRequest.ALLOWED_ATTACHMENT_COUNT_RANGE) {
            return VisionPreparationFailure.InvalidMediaContract(
                "Media count must be 1..6, got ${sourceMediaIds.size}"
            )
        }
        if (sourceMediaIds.any { it.isBlank() }) {
            return VisionPreparationFailure.InvalidMediaContract(
                "sourceMediaIds must not contain empty ids"
            )
        }
        if (sourceMediaIds.toSet().size != sourceMediaIds.size) {
            return VisionPreparationFailure.InvalidMediaContract(
                "sourceMediaIds must not contain duplicates"
            )
        }
        return null
    }

    private suspend fun loadAndValidateMedia(
        sourceMediaIds: List<String>,
        request: PrepareVisionAttachmentsRequest
    ): List<ValidatedMedia>? {
        val entities = mediaDao.getByIds(sourceMediaIds)
        val byId = entities.associateBy { it.id }

        // Ensure exact count and order.
        if (byId.size != sourceMediaIds.size) {
            return null
        }

        val validated = mutableListOf<ValidatedMedia>()
        for (mediaId in sourceMediaIds) {
            val entity = byId[mediaId] ?: return null
            validateMediaEntity(entity, request)?.let { failure ->
                throw VisionPreparationFailureException(failure)
            }
            val masterFile = resolveMasterFile(
                mediaId = entity.id,
                masterRelativePath = entity.masterRelativePath!!
            )
            validated.add(ValidatedMedia(id = entity.id, masterFile = masterFile))
        }
        return validated
    }

    private fun validateMediaEntity(
        entity: MediaAssetEntity,
        request: PrepareVisionAttachmentsRequest
    ): VisionPreparationFailure? {
        if (entity.conversationId != request.conversationId) {
            return VisionPreparationFailure.MediaBindingMismatch(
                "Media ${entity.id} does not belong to conversation ${request.conversationId}"
            )
        }
        if (entity.deletedAt != null) {
            return VisionPreparationFailure.MediaNotReady(
                "Media ${entity.id} is soft-deleted"
            )
        }
        if (entity.lifecycleState != MediaLifecycleState.READY.name) {
            return VisionPreparationFailure.MediaNotReady(
                "Media ${entity.id} is not READY (current=${entity.lifecycleState})"
            )
        }
        if (entity.sourceMessageId != request.userMessageId) {
            return VisionPreparationFailure.MediaBindingMismatch(
                "Media ${entity.id} is not bound to message ${request.userMessageId}"
            )
        }
        if (entity.masterRelativePath.isNullOrBlank()) {
            return VisionPreparationFailure.MasterFileMissing(
                "Media ${entity.id} has no master relative path"
            )
        }
        return null
    }

    private fun resolveMasterFile(mediaId: String, masterRelativePath: String): File {
        return try {
            val file = File(filesDir, masterRelativePath)
            val canonicalFile = file.canonicalFile
            val canonicalMasterRoot = File(filesDir, "media/master").canonicalFile
            val isAllowed = canonicalFile.path.startsWith(canonicalMasterRoot.path + File.separator) ||
                canonicalFile.path == canonicalMasterRoot.path
            if (!isAllowed) {
                throw VisionPreparationFailureException(
                    VisionPreparationFailure.UnsafePath(
                        "Master path for $mediaId is outside allowed root"
                    )
                )
            }
            if (!canonicalFile.exists()) {
                throw VisionPreparationFailureException(
                    VisionPreparationFailure.MasterFileMissing(
                        "Master file for $mediaId does not exist"
                    )
                )
            }
            canonicalFile
        } catch (e: VisionPreparationFailureException) {
            throw e
        } catch (e: Exception) {
            throw VisionPreparationFailureException(
                VisionPreparationFailure.MasterFileMissing(
                    "Failed to resolve master file for $mediaId: ${e.message}"
                )
            )
        }
    }

    private fun createDerivativeFile(requestId: String, mediaId: String): File {
        val requestDir = fileStore.getAiRequestDirectory(requestId)
        return File(requestDir, "$mediaId.jpg")
    }

    private fun cleanupRequestDirectory(requestId: String) {
        fileStore.deleteAiRequestDirectory(requestId)
    }

    private fun mapProcessingError(error: Throwable, mediaId: String): VisionPreparationFailure {
        return when (error) {
            is OutOfMemoryException -> VisionPreparationFailure.OutOfMemory(
                "Out of memory while processing $mediaId"
            )
            is IOException -> VisionPreparationFailure.WriteFailed(
                "Write failed for $mediaId: ${error.message}"
            )
            is IllegalArgumentException -> VisionPreparationFailure.ImageTooLarge(
                "Could not fit $mediaId within size limit: ${error.message}"
            )
            is VisionPreparationFailureException -> error.failure
            else -> VisionPreparationFailure.Unknown(
                "Unexpected error processing $mediaId: ${error.message}",
                error
            )
        }
    }

    private class VisionPreparationFailureException(
        val failure: VisionPreparationFailure
    ) : RuntimeException(failure.message)

    private data class ValidatedMedia(
        val id: String,
        val masterFile: File
    )

    companion object {
        private const val MEDIA_KEY = "media"
        private const val SCHEMA_VERSION_KEY = "schemaVersion"
        private const val SOURCE_MEDIA_IDS_KEY = "sourceMediaIds"
        private const val MEDIA_SCHEMA_VERSION = 1
        private const val STALE_DIRECTORY_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
