package com.goings.dayzero.data.media

import android.content.Context
import com.goings.dayzero.data.sync.media.MediaBinaryStore
import com.goings.dayzero.data.sync.media.MediaDownloadedPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Concrete [MediaBinaryStore] over the local filesystem. Reads are resolved under
 * `filesDir/media` with a path-traversal guard; writes reuse the atomic
 * `.part` -> rename path in [MediaFileStore.writeReadyFilesAtomically].
 */
class AndroidMediaBinaryStore(
    private val context: Context,
    private val fileStore: MediaFileStore
) : MediaBinaryStore {

    override suspend fun readBytes(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = resolveUnderMediaRoot(relativePath) ?: return@withContext null
        if (!file.exists()) null else file.readBytes()
    }

    override suspend fun writeDownloaded(
        mediaId: String,
        masterBytes: ByteArray,
        thumbnailBytes: ByteArray?
    ): MediaDownloadedPaths {
        val paths = fileStore.writeReadyFilesAtomically(mediaId) { masterPart, thumbPart ->
            masterPart.writeBytes(masterBytes)
            // We always upload a thumbnail, so thumbnailBytes is normally present; fall back
            // to the master bytes so the thumbnail slot is never left empty.
            thumbPart.writeBytes(thumbnailBytes ?: masterBytes)
        }
        return MediaDownloadedPaths(
            masterRelativePath = paths.masterRelativePath,
            thumbnailRelativePath = paths.thumbnailRelativePath
        )
    }

    private fun resolveUnderMediaRoot(relativePath: String): File? {
        if (relativePath.contains("..")) return null
        val mediaRoot = File(context.filesDir, "media").canonicalFile
        val target = File(context.filesDir, relativePath).canonicalFile
        val isUnderRoot = target.path == mediaRoot.path ||
            target.path.startsWith(mediaRoot.path + File.separator)
        return if (isUnderRoot) target else null
    }
}
