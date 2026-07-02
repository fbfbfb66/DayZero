package com.example.data.media

import com.example.domain.model.media.LocalMediaInput
import java.io.File
import java.io.InputStream

interface MediaFileStore {
    suspend fun copyToStaging(
        mediaId: String,
        input: LocalMediaInput,
        maxSizeBytes: Long
    ): File

    fun getStagingSourceFile(mediaId: String): File

    fun openStagingSource(mediaId: String): InputStream

    fun deleteStagingSource(mediaId: String): Boolean

    suspend fun writeReadyFilesAtomically(
        mediaId: String,
        writeAction: (File, File) -> Unit
    ): ReadyFilesPaths

    fun deleteAllFiles(mediaId: String): Boolean

    fun deletePartFiles(mediaId: String)

    /**
     * Returns the isolated directory for AI derivative files of a single request.
     * The directory is created if it does not exist.
     */
    fun getAiRequestDirectory(requestId: String): File

    /**
     * Deletes the entire AI derivative directory for [requestId].
     * Returns true if anything was deleted.
     */
    fun deleteAiRequestDirectory(requestId: String): Boolean

    /**
     * Cleans up AI request directories under [cache/media/ai] older than [olderThanMillis].
     * Returns the list of request ids that were removed.
     */
    fun cleanupStaleAiRequestDirectories(olderThanMillis: Long): List<String>
}

data class ReadyFilesPaths(
    val masterRelativePath: String,
    val thumbnailRelativePath: String,
    val masterFile: File,
    val thumbnailFile: File
)
