package com.goings.dayzero.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import java.io.File

@Composable
fun LocalMediaThumbnail(
    thumbnailRelativePath: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val safeFile = remember(thumbnailRelativePath) {
        thumbnailRelativePath?.let { getSafeThumbnailFile(context, it) }
    }

    Box(
        modifier = modifier
            .background(Color(0xFFF5F0EA), RoundedCornerShape(12.dp)), // Clean warm DayZero beige
        contentAlignment = Alignment.Center
    ) {
        if (safeFile != null) {
            SubcomposeAsyncImage(
                model = safeFile,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = {
                    PlaceholderIcon()
                }
            )
        } else {
            PlaceholderIcon()
        }
    }
}

@Composable
private fun PlaceholderIcon() {
    Icon(
        imageVector = Icons.Default.ImageNotSupported,
        contentDescription = "No Image",
        tint = Color.Gray.copy(alpha = 0.4f)
    )
}

internal enum class SafeMediaRoot(val relativeDirectory: String) {
    MASTER("media/master"),
    THUMBNAIL("media/thumbnail")
}

internal fun resolveSafeMediaFile(
    filesDir: File,
    relativePath: String?,
    root: SafeMediaRoot
): File? {
    if (relativePath.isNullOrBlank()) return null
    val normalized = relativePath.replace('\\', '/')
    if (File(relativePath).isAbsolute) return null
    if (normalized.substringBefore('/').contains(':')) return null
    if (normalized.split('/').any { it == ".." }) return null
    return try {
        val canonicalFilesDir = filesDir.canonicalFile
        val targetFile = File(canonicalFilesDir, relativePath).canonicalFile
        val allowedRoot = File(canonicalFilesDir, root.relativeDirectory).canonicalFile

        if (targetFile.path.startsWith(allowedRoot.path + File.separator)) {
            if (targetFile.exists() && targetFile.isFile && targetFile.canRead()) {
                targetFile
            } else {
                null
            }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

internal fun getSafeMasterMediaFile(context: Context, relativePath: String?): File? =
    resolveSafeMediaFile(context.filesDir, relativePath, SafeMediaRoot.MASTER)

internal fun getSafeThumbnailMediaFile(context: Context, relativePath: String?): File? =
    resolveSafeMediaFile(context.filesDir, relativePath, SafeMediaRoot.THUMBNAIL)

private fun getSafeThumbnailFile(context: Context, relativePath: String): File? =
    getSafeThumbnailMediaFile(context, relativePath)
