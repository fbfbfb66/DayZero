package com.goings.dayzero.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Android implementation of [AiImageDerivativeProcessor].
 *
 * Produces JPEG derivatives with the following guarantees:
 * - EXIF orientation is applied, and the output contains no meaningful EXIF metadata.
 * - Transparent backgrounds are flattened to white.
 * - Images are never upscaled.
 * - A bounded sequence of (longestSide, quality) attempts is tried; if none fits
     *   within [DerivativeSpec.MAX_SINGLE_FILE_BYTES], an [IllegalArgumentException] is thrown.
 * - Decoding is sampled to keep memory bounded, and transient bitmaps are recycled eagerly.
 */
class AndroidAiImageDerivativeProcessor : AiImageDerivativeProcessor {

    override fun createAiDerivative(sourceFile: File, destFile: File): ProcessedImageMetadata {
        // 1. Read bounds
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)
        val mimeType = boundsOptions.outMimeType ?: ""

        if (mimeType.isBlank() || !mimeType.startsWith("image/")) {
            throw IllegalArgumentException("Unsupported or unrecognized format: $mimeType")
        }

        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw IllegalArgumentException("Invalid dimensions: ${rawWidth}x${rawHeight}")
        }

        // 2. Read EXIF orientation
        val exifInterface = try {
            ExifInterface(sourceFile.absolutePath)
        } catch (_: Exception) {
            null
        }
        val orientation = exifInterface?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val isSwapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

        val rotatedWidth = if (isSwapped) rawHeight else rawWidth
        val rotatedHeight = if (isSwapped) rawWidth else rawHeight

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        // 3. Decode with a sample size that keeps memory bounded for the largest target.
        val maxTargetSide = DerivativeSpec.ENCODING_STEPS.maxOf { it.first }
        val inSampleSize = calculateInSampleSize(rawWidth, rawHeight, maxTargetSide)

        var decodedBitmap: Bitmap? = null
        var transformedBitmap: Bitmap? = null
        var flattenedBitmap: Bitmap? = null

        try {
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decodedBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions)
                ?: throw IOException("Failed to decode bitmap from source file")

            transformedBitmap = if (matrix.isIdentity) {
                decodedBitmap
            } else {
                Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
            }

            flattenedBitmap = if (transformedBitmap.hasAlpha()) {
                val flat = Bitmap.createBitmap(
                    transformedBitmap.width,
                    transformedBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(flat)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(transformedBitmap, 0f, 0f, null)
                flat
            } else {
                transformedBitmap
            }

            // 4. Try encoding steps.
            var lastException: Exception? = null
            for ((targetSide, quality) in DerivativeSpec.ENCODING_STEPS) {
                val scaled = scaleToLongestSide(flattenedBitmap, targetSide)
                try {
                    destFile.outputStream().use { out ->
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                            throw IOException("JPEG compression failed")
                        }
                    }
                    if (destFile.length() <= DerivativeSpec.MAX_SINGLE_FILE_BYTES) {
                        return ProcessedImageMetadata(
                            width = scaled.width,
                            height = scaled.height,
                            mimeType = DerivativeSpec.MIME_TYPE
                        )
                    }
                    // Too large: remove attempt and continue to next step.
                    destFile.delete()
                } catch (e: IOException) {
                    lastException = e
                    destFile.delete()
                } finally {
                    if (scaled != flattenedBitmap) {
                        scaled.recycle()
                    }
                }
            }

            throw lastException ?: IllegalArgumentException(
                "Could not produce AI derivative within ${DerivativeSpec.MAX_SINGLE_FILE_BYTES} bytes"
            )
        } catch (oom: OutOfMemoryError) {
            System.gc()
            throw OutOfMemoryException("OOM occurred during AI derivative creation", oom)
        } finally {
            if (decodedBitmap != transformedBitmap) {
                decodedBitmap?.recycle()
            }
            if (transformedBitmap != flattenedBitmap) {
                transformedBitmap?.recycle()
            }
            flattenedBitmap?.recycle()
        }
    }

    private fun scaleToLongestSide(bitmap: Bitmap, targetLongestSide: Int): Bitmap {
        val currentLongest = maxOf(bitmap.width, bitmap.height)
        if (currentLongest <= targetLongestSide) {
            // No upscale; create a new bitmap to strip EXIF/metadata.
            return Bitmap.createScaledBitmap(bitmap, bitmap.width, bitmap.height, true)
        }
        val scale = targetLongestSide.toFloat() / currentLongest
        val width = (bitmap.width * scale).toInt()
        val height = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, targetMaxSide: Int): Int {
        var inSampleSize = 1
        val maxSide = maxOf(srcWidth, srcHeight)
        if (maxSide > targetMaxSide) {
            var halfMaxSide = maxSide / 2
            while (halfMaxSide / inSampleSize >= targetMaxSide) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
