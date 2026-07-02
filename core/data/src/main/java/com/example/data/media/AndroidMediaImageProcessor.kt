package com.example.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.example.domain.usecase.ImportLocalMediaUseCase
import java.io.File
import java.io.IOException

class OutOfMemoryException(message: String, cause: Throwable) : IOException(message, cause)

class AndroidMediaImageProcessor : MediaImageProcessor {

    override fun processImage(
        sourceFile: File,
        masterDestFile: File,
        thumbnailDestFile: File
    ): ProcessedImageMetadata {
        // 1. Read bounds and type info
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(sourceFile.absolutePath, options)
        val mimeType = options.outMimeType ?: ""

        // 2. Format validation
        if (mimeType.isBlank() || !mimeType.startsWith("image/")) {
            throw IllegalArgumentException("Unsupported or unrecognized format: $mimeType")
        }
        if (mimeType == "image/gif") {
            throw IllegalArgumentException("GIF format is not supported")
        }
        if (mimeType == "image/webp" && isAnimatedWebP(sourceFile)) {
            throw IllegalArgumentException("Animated WebP is not supported")
        }

        val rawWidth = options.outWidth.toLong()
        val rawHeight = options.outHeight.toLong()
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw IllegalArgumentException("Invalid dimensions: ${rawWidth}x${rawHeight}")
        }

        // Check single dimension limits first
        if (rawWidth > ImportLocalMediaUseCase.MAX_DIMENSION_PX || rawHeight > ImportLocalMediaUseCase.MAX_DIMENSION_PX) {
            throw IllegalArgumentException("Image dimension exceeds maximum limit of ${ImportLocalMediaUseCase.MAX_DIMENSION_PX}px")
        }

        // Check area limit with Long multiplication
        val area = rawWidth * rawHeight
        if (area > ImportLocalMediaUseCase.MAX_PIXEL_AREA) {
            throw IllegalArgumentException("Image pixel area $area exceeds limit of ${ImportLocalMediaUseCase.MAX_PIXEL_AREA} pixels")
        }

        // 3. Read EXIF orientation
        val exifInterface = try {
            ExifInterface(sourceFile.absolutePath)
        } catch (e: Exception) {
            null
        }
        val orientation = exifInterface?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        // Determine if dimensions are swapped after rotation
        val isSwapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE

        val rotatedWidth = if (isSwapped) options.outHeight else options.outWidth
        val rotatedHeight = if (isSwapped) options.outWidth else options.outHeight

        // Define matrix for rotation/flip
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postScale(1f, -1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
            }
        }

        // 4. Safe sample decoding to prevent OOM
        // We base sample size calculations on raw width and height
        val inSampleSizeVal = calculateInSampleSize(options.outWidth, options.outHeight, 2048)

        var decodedBitmap: Bitmap? = null
        var transformedBitmap: Bitmap? = null
        var flattenedBitmap: Bitmap? = null
        var masterBitmap: Bitmap? = null
        var thumbnailBitmap: Bitmap? = null

        try {
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSampleSizeVal
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decodedBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions)
                ?: throw IOException("Failed to decode bitmap from source file")

            // Apply orientation transformation matrix
            transformedBitmap = if (matrix.isIdentity) {
                decodedBitmap
            } else {
                Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
            }

            // Flatten transparent background to solid white
            flattenedBitmap = if (transformedBitmap.hasAlpha()) {
                val flat = Bitmap.createBitmap(transformedBitmap.width, transformedBitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(flat)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(transformedBitmap, 0f, 0f, null)
                flat
            } else {
                transformedBitmap
            }

            // Target dimensions
            val currentMaxSide = maxOf(flattenedBitmap.width, flattenedBitmap.height)
            
            // 5. Generate Master Image (max side 2048px, quality 85, no upscale)
            masterBitmap = if (currentMaxSide > 2048) {
                val scale = 2048f / currentMaxSide
                val w = (flattenedBitmap.width * scale).toInt()
                val h = (flattenedBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(flattenedBitmap, w, h, true)
            } else {
                flattenedBitmap
            }

            // 6. Generate Thumbnail Image (max side 320px, quality 80, no upscale)
            thumbnailBitmap = if (currentMaxSide > 320) {
                val scale = 320f / currentMaxSide
                val w = (flattenedBitmap.width * scale).toInt()
                val h = (flattenedBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(flattenedBitmap, w, h, true)
            } else {
                if (masterBitmap == flattenedBitmap) {
                    Bitmap.createScaledBitmap(flattenedBitmap, flattenedBitmap.width, flattenedBitmap.height, true)
                } else {
                    flattenedBitmap
                }
            }

            // 7. Write files to temporary locations
            masterDestFile.outputStream().use { out ->
                masterBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbnailDestFile.outputStream().use { out ->
                thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }

            return ProcessedImageMetadata(
                width = masterBitmap.width,
                height = masterBitmap.height,
                mimeType = "image/jpeg"
            )

        } catch (oom: OutOfMemoryError) {
            System.gc()
            throw OutOfMemoryException("OOM occurred during image processing", oom)
        } finally {
            // Clean up resources reliably
            if (decodedBitmap != transformedBitmap) {
                decodedBitmap?.recycle()
            }
            if (transformedBitmap != flattenedBitmap) {
                transformedBitmap?.recycle()
            }
            if (flattenedBitmap != masterBitmap) {
                flattenedBitmap?.recycle()
            }
            if (masterBitmap != thumbnailBitmap) {
                masterBitmap?.recycle()
            }
            thumbnailBitmap?.recycle()
        }
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, targetMaxSide: Int): Int {
        var inSampleSize = 1
        val maxSide = maxOf(srcWidth, srcHeight)
        if (maxSide > targetMaxSide) {
            val halfMaxSide = maxSide / 2
            while (halfMaxSide / inSampleSize >= targetMaxSide) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun isAnimatedWebP(file: File): Boolean {
        try {
            val bytes = file.readBytes()
            if (bytes.size < 30) return false
            val riff = String(bytes, 0, 4, Charsets.US_ASCII)
            val webp = String(bytes, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || webp != "WEBP") return false

            var offset = 12
            while (offset + 8 <= bytes.size) {
                val chunkTag = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = readLittleEndianInt(bytes, offset + 4)
                if (chunkTag == "VP8X") {
                    if (offset + 8 < bytes.size) {
                        val flags = bytes[offset + 8].toInt()
                        if ((flags and 0x10) != 0) {
                            return true
                        }
                    }
                }
                if (chunkTag == "ANIM") {
                    return true
                }
                offset += 8 + chunkSize + (chunkSize % 2)
                if (chunkSize <= 0) break
            }
        } catch (e: Exception) {
            // Treat parse failures as non-animated or let decoder handle it
        }
        return false
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
