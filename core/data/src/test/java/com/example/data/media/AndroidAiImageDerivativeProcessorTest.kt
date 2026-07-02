package com.example.data.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAiImageDerivativeProcessorTest {

    private lateinit var processor: AndroidAiImageDerivativeProcessor
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        processor = AndroidAiImageDerivativeProcessor()
        tempDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "ai-derivative-test")
        tempDir.mkdirs()
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedFormat_throws() {
        val source = File(tempDir, "not-an-image.txt")
        source.writeText("not an image")
        val dest = File(tempDir, "out.jpg")

        processor.createAiDerivative(source, dest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingSource_throws() {
        val source = File(tempDir, "missing.jpg")
        val dest = File(tempDir, "out.jpg")

        processor.createAiDerivative(source, dest)
    }

    @Test
    fun derivativeSpecDefinesBoundedEncodingSteps() {
        assertEquals(5, DerivativeSpec.ENCODING_STEPS.size)
        assertTrue(DerivativeSpec.ENCODING_STEPS.first().first >= DerivativeSpec.ENCODING_STEPS.last().first)
        assertTrue(DerivativeSpec.MAX_SINGLE_FILE_BYTES > 0)
        assertTrue(DerivativeSpec.MAX_TOTAL_BYTES > DerivativeSpec.MAX_SINGLE_FILE_BYTES)
    }

    @Test
    fun canCreateJpegFileViaBitmapCompress() {
        // Sanity check: Robolectric can create a JPEG file, even if decodeFile is unreliable.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        val file = File(tempDir, "created.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }
}
