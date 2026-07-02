package com.example.data.media

import java.io.File

/**
 * Creates AI-ready JPEG derivatives from validated master images.
 *
 * Implementations must enforce the contract documented in [DerivativeSpec]
 * without modifying the source master file.
 */
interface AiImageDerivativeProcessor {
    /**
     * Writes an AI derivative of [sourceFile] into [destFile].
     *
     * @return metadata about the produced derivative
     * @throws OutOfMemoryException when image decoding exceeds available memory
     * @throws IllegalArgumentException for unsupported or invalid input
     * @throws java.io.IOException when encoding or file writing fails
     */
    fun createAiDerivative(
        sourceFile: File,
        destFile: File
    ): ProcessedImageMetadata
}

/**
 * Specification for AI derivative files.
 */
object DerivativeSpec {
    const val MIME_TYPE = "image/jpeg"
    const val MAX_SINGLE_FILE_BYTES = 640L * 1024L
    const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L
    const val DEFAULT_LONGEST_SIDE = 1280
    const val DEFAULT_QUALITY = 80

    /**
     * Ordered list of (longestSide, quality) attempts. The first entry that produces
     * a file within [MAX_SINGLE_FILE_BYTES] is selected.
     */
    val ENCODING_STEPS: List<Pair<Int, Int>> = listOf(
        1280 to 80,
        1280 to 72,
        1152 to 72,
        1024 to 68,
        896 to 64
    )
}
