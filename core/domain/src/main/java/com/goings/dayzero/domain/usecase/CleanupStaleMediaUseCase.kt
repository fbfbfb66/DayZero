package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.repository.LocalMediaImportRepository
import com.goings.dayzero.domain.repository.MediaRepository

data class CleanupStats(
    val successCount: Int,
    val failureCount: Int,
    val failedMediaIds: List<String>
)

class CleanupStaleMediaUseCase(
    private val mediaRepository: MediaRepository,
    private val importRepository: LocalMediaImportRepository
) {
    suspend operator fun invoke(
        updatedBefore: Long,
        now: Long
    ): CleanupStats {
        val candidates = mediaRepository.findStaleStagedMedia(updatedBefore)
        var successCount = 0
        var failureCount = 0
        val failedMediaIds = mutableListOf<String>()

        for (asset in candidates) {
            if (asset.deletedAt != null || asset.sourceMessageId != null) {
                continue
            }
            try {
                importRepository.discardStagedMedia(asset.id)
                mediaRepository.softDeleteMedia(asset.id, asset.conversationId, now)
                successCount++
            } catch (e: Exception) {
                failureCount++
                failedMediaIds.add(asset.id)
            }
        }

        return CleanupStats(
            successCount = successCount,
            failureCount = failureCount,
            failedMediaIds = failedMediaIds
        )
    }
}
