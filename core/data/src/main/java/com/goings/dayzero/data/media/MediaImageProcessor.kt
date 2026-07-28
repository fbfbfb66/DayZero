package com.goings.dayzero.data.media

import java.io.File

interface MediaImageProcessor {
    fun processImage(
        sourceFile: File,
        masterDestFile: File,
        thumbnailDestFile: File
    ): ProcessedImageMetadata
}

data class ProcessedImageMetadata(
    val width: Int,
    val height: Int,
    val mimeType: String
)
