package com.example.ui.components

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

private fun getSafeThumbnailFile(context: Context, relativePath: String): File? {
    if (relativePath.isBlank() || relativePath.contains("..")) return null
    return try {
        val filesDir = context.filesDir.canonicalFile
        val targetFile = File(filesDir, relativePath).canonicalFile
        val thumbnailDir = File(filesDir, "media/thumbnail").canonicalFile
        
        if (targetFile.path.startsWith(thumbnailDir.path + File.separator) || targetFile.path == thumbnailDir.path) {
            if (targetFile.exists() && targetFile.isFile) {
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
