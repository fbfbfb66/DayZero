package com.goings.dayzero.data.sync.media

/**
 * Abstraction over local media file IO for the sync layer. core:sync does not depend
 * on core:data, so the concrete implementation (over MediaFileStore) is injected from
 * the app DI graph.
 */
interface MediaBinaryStore {
    /** Reads bytes for a stored media file by its filesDir-relative path; null if missing. */
    suspend fun readBytes(relativePath: String): ByteArray?

    /**
     * Atomically writes downloaded master/thumbnail bytes into local storage for [mediaId]
     * and returns the resulting filesDir-relative paths.
     */
    suspend fun writeDownloaded(
        mediaId: String,
        masterBytes: ByteArray,
        thumbnailBytes: ByteArray?
    ): MediaDownloadedPaths
}

data class MediaDownloadedPaths(
    val masterRelativePath: String,
    val thumbnailRelativePath: String?
)
