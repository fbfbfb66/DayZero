package com.example.domain.repository

import com.example.domain.model.media.LocalMediaImportItemResult
import com.example.domain.model.media.ImportLocalMediaRequest

interface LocalMediaImportRepository {
    suspend fun importStagedMedia(
        mediaId: String,
        request: ImportLocalMediaRequest
    ): LocalMediaImportItemResult

    suspend fun retryImport(
        mediaId: String
    ): LocalMediaImportItemResult

    suspend fun discardStagedMedia(
        mediaId: String
    ): Boolean

    suspend fun cleanupStaleMedia(
        updatedBefore: Long
    ): List<String>
}
