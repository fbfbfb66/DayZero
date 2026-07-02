package com.example.domain.usecase

import com.example.domain.model.media.ImportLocalMediaRequest
import com.example.domain.model.media.LocalMediaImportItemResult
import com.example.domain.model.media.MediaImportFailureCode
import com.example.domain.model.media.NewMediaAssetRequest
import com.example.domain.repository.LocalMediaImportRepository
import com.example.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException

fun interface MediaIdGenerator {
    fun generate(): String
}

class ImportLocalMediaUseCase(
    private val mediaRepository: MediaRepository,
    private val importRepository: LocalMediaImportRepository,
    private val idGenerator: MediaIdGenerator
) {
    suspend operator fun invoke(
        requests: List<ImportLocalMediaRequest>,
        now: Long
    ): List<LocalMediaImportItemResult> {
        // Validate batch size
        require(requests.isNotEmpty() && requests.size <= MAX_BATCH_SIZE) {
            "Batch size must be between 1 and $MAX_BATCH_SIZE"
        }
        
        // Validate IDs and conversations
        val conversationId = requests.first().conversationId
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank" }
        require(requests.all { it.ownerLocalId.isNotBlank() }) { "Owner Local ID must not be blank" }
        require(requests.all { it.conversationId == conversationId }) {
            "All requests in a batch must belong to the same conversation"
        }

        // Generate stable mediaIds
        val mediaIds = requests.map { idGenerator.generate() }
        
        // Create STAGED database entries in one batch
        val stagedRequests = requests.mapIndexed { index, request ->
            NewMediaAssetRequest(
                id = mediaIds[index],
                ownerLocalId = request.ownerLocalId,
                conversationId = request.conversationId,
                source = request.source
            )
        }
        mediaRepository.createStagedMedia(stagedRequests, now)

        val results = ArrayList<LocalMediaImportItemResult>(requests.size)
        for (index in requests.indices) {
            val mediaId = mediaIds[index]
            val request = requests[index]
            try {
                val result = importRepository.importStagedMedia(mediaId, request)
                results.add(result)
            } catch (c: CancellationException) {
                // Already READY items are preserved in results.
                // Current item's partial files should be cleaned up by importRepository,
                // and the current item remains STAGED without marking FAILED.
                // Rethrow immediately.
                throw c
            } catch (e: Exception) {
                results.add(
                    LocalMediaImportItemResult.Failed(
                        mediaId = mediaId,
                        failureCode = MediaImportFailureCode.UNKNOWN
                    )
                )
            }
        }
        return results
    }

    companion object {
        const val MAX_BATCH_SIZE = 6
        const val MAX_SOURCE_FILE_SIZE_BYTES = 31457280L // 30 MiB
        const val MAX_DIMENSION_PX = 15000L
        const val MAX_PIXEL_AREA = 100000000L // 100 Megapixels
    }
}
