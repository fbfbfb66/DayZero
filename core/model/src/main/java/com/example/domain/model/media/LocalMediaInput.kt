package com.example.domain.model.media

sealed interface LocalMediaInput {
    data class ContentReference(
        val opaqueValue: String
    ) : LocalMediaInput

    data class AppCacheFile(
        val relativePath: String
    ) : LocalMediaInput
}

data class ImportLocalMediaRequest(
    val conversationId: String,
    val ownerLocalId: String,
    val source: MediaSource,
    val input: LocalMediaInput
)

sealed interface LocalMediaImportItemResult {
    val mediaId: String

    data class Ready(
        override val mediaId: String,
        val asset: MediaAsset
    ) : LocalMediaImportItemResult

    data class Failed(
        override val mediaId: String,
        val failureCode: MediaImportFailureCode
    ) : LocalMediaImportItemResult
}

enum class MediaImportFailureCode {
    SOURCE_OPEN_FAILED,
    SOURCE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    INVALID_DIMENSIONS,
    DECODE_FAILED,
    OUT_OF_MEMORY,
    WRITE_FAILED,
    HASH_FAILED,
    DATABASE_UPDATE_FAILED,
    SOURCE_MISSING,
    UNKNOWN
}
