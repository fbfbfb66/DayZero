package com.goings.dayzero.data.media

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
    // Processor acceptance target. The transport ceiling remains 640KB downstream.
    const val MAX_SINGLE_FILE_BYTES = 384L * 1024L
    const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L
    const val DEFAULT_LONGEST_SIDE = 1024
    const val DEFAULT_QUALITY = 74

    /**
     * Ordered list of (longestSide, quality) attempts. The first entry that produces
     * a file within [MAX_SINGLE_FILE_BYTES] is selected.
     */
    val ENCODING_STEPS: List<Pair<Int, Int>> = listOf(
        1024 to 74,
        1024 to 66,
        896 to 64,
        832 to 60,
        768 to 56
    )
}
