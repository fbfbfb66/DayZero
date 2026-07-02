package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.ConversationEntity
import com.example.data.media.AndroidMediaFileStore
import com.example.data.media.AndroidMediaImageProcessor
import com.example.data.media.ProcessedImageMetadata
import com.example.data.media.OutOfMemoryException
import com.example.domain.model.media.ImportLocalMediaRequest
import com.example.domain.model.media.LocalMediaImportItemResult
import com.example.domain.model.media.LocalMediaInput
import com.example.domain.model.media.MediaImportFailureCode
import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.model.media.MediaSource
import com.example.domain.model.media.NewMediaAssetRequest
import com.example.domain.repository.LocalMediaImportRepository
import com.example.domain.repository.MediaRepository
import com.example.domain.usecase.CleanupStaleMediaUseCase
import com.example.domain.usecase.DiscardStagedMediaUseCase
import com.example.domain.usecase.ImportLocalMediaUseCase
import com.example.domain.usecase.MediaIdGenerator
import com.example.domain.usecase.RetryLocalMediaImportUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AndroidLocalMediaImportRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private lateinit var mediaRepository: RoomMediaRepository
    private lateinit var fileStore: AndroidMediaFileStore
    private lateinit var imageProcessor: AndroidMediaImageProcessor
    private lateinit var importRepository: AndroidLocalMediaImportRepository

    private lateinit var importUseCase: ImportLocalMediaUseCase
    private lateinit var retryUseCase: RetryLocalMediaImportUseCase
    private lateinit var discardUseCase: DiscardStagedMediaUseCase
    private lateinit var cleanupUseCase: CleanupStaleMediaUseCase

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaRepository = RoomMediaRepository(database)
        fileStore = AndroidMediaFileStore(context)
        imageProcessor = AndroidMediaImageProcessor()
        importRepository = AndroidLocalMediaImportRepository(
            mediaRepository = mediaRepository,
            fileStore = fileStore,
            imageProcessor = imageProcessor
        )

        importUseCase = ImportLocalMediaUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository,
            idGenerator = object : MediaIdGenerator {
                private var counter = 0
                override fun generate(): String = "media-id-${++counter}"
            }
        )

        retryUseCase = RetryLocalMediaImportUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository
        )

        discardUseCase = DiscardStagedMediaUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository
        )

        cleanupUseCase = CleanupStaleMediaUseCase(
            mediaRepository = mediaRepository,
            importRepository = importRepository
        )
    }

    @After
    fun tearDown() {
        database.close()
        // Clean up test directories
        File(context.cacheDir, "media").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
    }

    @Test
    fun testSuccessfulImportFromContentUri() = runTest {
        insertConversation("conv-1")
        val imageFile = createTestImageFile("source.jpg", 100, 200)

        val contentUri = Uri.parse("content://com.example.provider/photo.jpg")
        val shadowResolver = shadowOf(context.contentResolver)
        shadowResolver.registerInputStream(contentUri, imageFile.inputStream())

        val request = ImportLocalMediaRequest(
            conversationId = "conv-1",
            ownerLocalId = "owner-1",
            source = MediaSource.PHOTO_PICKER,
            input = LocalMediaInput.ContentReference(contentUri.toString())
        )

        val results = importUseCase(listOf(request), now = 1000L)
        assertEquals(1, results.size)
        val firstResult = results.first()
        assertTrue(firstResult is LocalMediaImportItemResult.Ready)

        val ready = firstResult as LocalMediaImportItemResult.Ready
        assertEquals("media-id-1", ready.mediaId)
        val asset = ready.asset
        assertEquals(MediaLifecycleState.READY, asset.lifecycleState)
        assertEquals(100, asset.width)
        assertEquals(200, asset.height)
        assertEquals("image/jpeg", asset.mimeType)
        assertNotNull(asset.sha256)
        assertNotNull(asset.byteSize)
        assertNull(asset.failureCode)

        // Verify staging source was deleted
        val stagingFile = fileStore.getStagingSourceFile("media-id-1")
        assertFalse(stagingFile.exists())

        // Verify master and thumbnail are written
        val masterFile = File(context.filesDir, asset.masterRelativePath!!)
        val thumbFile = File(context.filesDir, asset.thumbnailRelativePath!!)
        assertTrue(masterFile.exists())
        assertTrue(thumbFile.exists())
    }

    @Test
    fun testSuccessfulImportFromAppCacheFile() = runTest {
        insertConversation("conv-1")
        createTestImageFile("media/camera/source.jpg", 120, 160)

        val request = ImportLocalMediaRequest(
            conversationId = "conv-1",
            ownerLocalId = "owner-1",
            source = MediaSource.CAMERA,
            input = LocalMediaInput.AppCacheFile("media/camera/source.jpg")
        )

        val results = importUseCase(listOf(request), now = 1000L)
        assertTrue(results.first() is LocalMediaImportItemResult.Ready)
        val ready = results.first() as LocalMediaImportItemResult.Ready
        assertEquals(120, ready.asset.width)
        assertEquals(160, ready.asset.height)
    }

    @Test
    fun testContentUriSizeLimitEnforced() = runTest {
        insertConversation("conv-1")
        // Create an input stream that returns dummy bytes exceeding 30 MiB limit
        val contentUri = Uri.parse("content://com.example.provider/too_large.jpg")
        val shadowResolver = shadowOf(context.contentResolver)
        val fakeBytes = ByteArray(31457280 + 1) // 30 MiB + 1 byte
        shadowResolver.registerInputStream(contentUri, ByteArrayInputStream(fakeBytes))

        val request = ImportLocalMediaRequest(
            conversationId = "conv-1",
            ownerLocalId = "owner-1",
            source = MediaSource.PHOTO_PICKER,
            input = LocalMediaInput.ContentReference(contentUri.toString())
        )

        val results = importUseCase(listOf(request), now = 1000L)
        assertTrue(results.first() is LocalMediaImportItemResult.Failed)
        val failed = results.first() as LocalMediaImportItemResult.Failed
        assertEquals(MediaImportFailureCode.SOURCE_TOO_LARGE, failed.failureCode)
    }

    @Test
    fun testAppCacheFilePathValidationJailbreak() = runTest {
        insertConversation("conv-1")
        // Absolute paths must be rejected
        val absoluteRequest = ImportLocalMediaRequest(
            conversationId = "conv-1",
            ownerLocalId = "owner-1",
            source = MediaSource.CAMERA,
            input = LocalMediaInput.AppCacheFile("/data/data/com.example/camera/photo.jpg")
        )
        val traversalRequest = ImportLocalMediaRequest(
            conversationId = "conv-1",
            ownerLocalId = "owner-1",
            source = MediaSource.CAMERA,
            input = LocalMediaInput.AppCacheFile("media/camera/../../jailbreak.jpg")
        )

        val results1 = importUseCase(listOf(absoluteRequest), now = 1000L)
        val results2 = importUseCase(listOf(traversalRequest), now = 1000L)

        val failed1 = results1.first() as LocalMediaImportItemResult.Failed
        val failed2 = results2.first() as LocalMediaImportItemResult.Failed

        assertEquals(MediaImportFailureCode.SOURCE_OPEN_FAILED, failed1.failureCode)
        assertEquals(MediaImportFailureCode.SOURCE_OPEN_FAILED, failed2.failureCode)
    }

    @Test
    fun testGifAndAnimatedWebPRejected() = runTest {
        insertConversation("conv-1")
        val gifFile = createFakeGifFile("source.gif")
        val web = shadowOf(context.contentResolver)
        val contentUri = Uri.parse("content://com.example/animated.gif")
        web.registerInputStream(contentUri, gifFile.inputStream())

        // WebP animations can be checked by writing WebP headers
        val animatedWebPBytes = ByteArray(40).apply {
            System.arraycopy("RIFF".toByteArray(), 0, this, 0, 4)
            System.arraycopy("WEBP".toByteArray(), 0, this, 8, 4)
            System.arraycopy("VP8X".toByteArray(), 0, this, 12, 4)
            this[20] = 0x10.toByte() // ANIMATION bit flag set
        }
        val animWebPUri = Uri.parse("content://com.example/animated.webp")
        web.registerInputStream(animWebPUri, ByteArrayInputStream(animatedWebPBytes))

        val reqGif = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.PHOTO_PICKER, LocalMediaInput.ContentReference(contentUri.toString()))
        val reqWebP = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.PHOTO_PICKER, LocalMediaInput.ContentReference(animWebPUri.toString()))

        val resGif = importUseCase(listOf(reqGif), now = 1000L)
        val resWebP = importUseCase(listOf(reqWebP), now = 1000L)

        assertEquals(MediaImportFailureCode.UNSUPPORTED_FORMAT, (resGif.first() as LocalMediaImportItemResult.Failed).failureCode)
        assertEquals(MediaImportFailureCode.UNSUPPORTED_FORMAT, (resWebP.first() as LocalMediaImportItemResult.Failed).failureCode)
    }

    @Test
    fun testExifOrientationAppliedCorrectlyAndMetadataStripped() = runTest {
        insertConversation("conv-1")
        // Image size 100x200 rotated 90 degrees
        val imageFile = createTestImageFile("source.jpg", 100, 200, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val contentUri = Uri.parse("content://com.example/photo_rotated.jpg")
        shadowOf(context.contentResolver).registerInputStream(contentUri, imageFile.inputStream())

        val request = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.PHOTO_PICKER, LocalMediaInput.ContentReference(contentUri.toString()))
        val results = importUseCase(listOf(request), now = 1000L)

        val firstResult = results.first()
        if (firstResult is LocalMediaImportItemResult.Failed) {
            System.out.println("DEBUG: EXIF test failed with code: ${firstResult.failureCode}")
        }
        val ready = firstResult as LocalMediaImportItemResult.Ready
        System.out.println("DEBUG: Rotated asset dimensions: ${ready.asset.width}x${ready.asset.height}")
        // Post rotation, dimensions should be swapped: 200x100
        assertEquals(200, ready.asset.width)
        assertEquals(100, ready.asset.height)

        // Read output EXIF to verify that GPS is stripped and orientation is normal (or stripped)
        val masterFile = File(context.filesDir, ready.asset.masterRelativePath!!)
        val outputExif = ExifInterface(masterFile.absolutePath)
        assertNull(outputExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val outputOrientation = outputExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        assertTrue(outputOrientation == ExifInterface.ORIENTATION_NORMAL || outputOrientation == ExifInterface.ORIENTATION_UNDEFINED)
    }

    @Test
    fun testOomDecodingReturnsOomFailureCode() = runTest {
        insertConversation("conv-1")
        createTestImageFile("media/camera/source.jpg", 100, 100)

        // Stub/mock processor by injecting a failing implementation in test
        val badImportRepo = AndroidLocalMediaImportRepository(
            mediaRepository = mediaRepository,
            fileStore = fileStore,
            imageProcessor = object : com.example.data.media.MediaImageProcessor {
                override fun processImage(sourceFile: File, masterDestFile: File, thumbnailDestFile: File): ProcessedImageMetadata {
                    throw OutOfMemoryException("Forced OOM test", OutOfMemoryError("OOM"))
                }
            }
        )
        val badUseCase = ImportLocalMediaUseCase(mediaRepository, badImportRepo) { "m-id" }

        val request = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.CAMERA, LocalMediaInput.AppCacheFile("media/camera/source.jpg"))
        val results = badUseCase(listOf(request), now = 1000L)

        val failed = results.first() as LocalMediaImportItemResult.Failed
        assertEquals(MediaImportFailureCode.OUT_OF_MEMORY, failed.failureCode)
    }

    @Test
    fun testDbUpdateReadyFailureRollsBackFiles() = runTest {
        insertConversation("conv-1")
        val imageFile = createTestImageFile("source.jpg", 100, 100)
        val contentUri = Uri.parse("content://com.example/photo.jpg")
        shadowOf(context.contentResolver).registerInputStream(contentUri, imageFile.inputStream())

        // Let's use a mock-like or custom Repository subclass that fails markMediaReady:
        val realRepo = RoomMediaRepository(database)
        val badRepository = object : MediaRepository by realRepo {
            override suspend fun markMediaReady(
                id: String,
                conversationId: String,
                masterRelativePath: String,
                thumbnailRelativePath: String,
                mimeType: String,
                width: Int,
                height: Int,
                byteSize: Long,
                sha256: String,
                now: Long
            ): com.example.domain.model.media.MediaAsset {
                throw IOException("Simulated DB write failure")
            }
        }
        val customImportRepository = AndroidLocalMediaImportRepository(badRepository, fileStore, imageProcessor)
        val customUseCase = ImportLocalMediaUseCase(badRepository, customImportRepository) { "m-id" }

        val request = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.PHOTO_PICKER, LocalMediaInput.ContentReference(contentUri.toString()))
        val results = customUseCase(listOf(request), now = 1000L)

        val failed = results.first() as LocalMediaImportItemResult.Failed
        assertEquals(MediaImportFailureCode.DATABASE_UPDATE_FAILED, failed.failureCode)

        // Staging source should be preserved for retry
        val stagingFile = fileStore.getStagingSourceFile("m-id")
        assertTrue(stagingFile.exists())

        // Formally output files should be deleted (rolled back)
        assertFalse(File(context.filesDir, "media/master/m-id.jpg").exists())
        assertFalse(File(context.filesDir, "media/thumbnail/m-id.jpg").exists())
    }

    @Test
    fun testRetryLogicOnStagedOrFailedAsset() = runTest {
        insertConversation("conv-1")
        val imageFile = createTestImageFile("source.jpg", 100, 150)
        val contentUri = Uri.parse("content://com.example/photo.jpg")
        shadowOf(context.contentResolver).registerInputStream(contentUri, imageFile.inputStream())

        // First attempt: simulate a failure (e.g. hash computation fails)
        val badImportRepository = object : LocalMediaImportRepository by importRepository {
            override suspend fun importStagedMedia(mediaId: String, request: ImportLocalMediaRequest): LocalMediaImportItemResult {
                // Perform only staging copy
                fileStore.copyToStaging(mediaId, request.input, ImportLocalMediaUseCase.MAX_SOURCE_FILE_SIZE_BYTES)
                // Mark failed
                mediaRepository.markMediaFailed(mediaId, request.conversationId, "HASH_FAILED", 1000L)
                return LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.HASH_FAILED)
            }
        }
        val customUseCase = ImportLocalMediaUseCase(mediaRepository, badImportRepository) { "m-id-retry" }
        val req = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.PHOTO_PICKER, LocalMediaInput.ContentReference(contentUri.toString()))
        customUseCase(listOf(req), now = 1000L)

        // Asset is now FAILED in DB and staging source is preserved
        val assetBeforeRetry = mediaRepository.getMediaByIds(listOf("m-id-retry")).first()
        assertEquals(MediaLifecycleState.FAILED, assetBeforeRetry.lifecycleState)
        assertTrue(fileStore.getStagingSourceFile("m-id-retry").exists())

        // Run retry (uses real repo which succeeds)
        val retryResult = retryUseCase("m-id-retry")
        assertTrue(retryResult is LocalMediaImportItemResult.Ready)
        val ready = retryResult as LocalMediaImportItemResult.Ready
        assertEquals(MediaLifecycleState.READY, ready.asset.lifecycleState)
        assertEquals(100, ready.asset.width)
        assertEquals(150, ready.asset.height)

        // Source file is cleaned up after successful retry
        assertFalse(fileStore.getStagingSourceFile("m-id-retry").exists())
    }

    @Test
    fun testDiscardStagedMediaDeletesFilesAndSoftDeletesDatabase() = runTest {
        insertConversation("conv-1")
        val imageFile = createTestImageFile("source.jpg", 100, 100)
        val contentUri = Uri.parse("content://com.example/photo.jpg")
        shadowOf(context.contentResolver).registerInputStream(contentUri, imageFile.inputStream())

        // Create staged asset and save staging file
        val request = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.PHOTO_PICKER, LocalMediaInput.ContentReference(contentUri.toString()))
        val importResults = importUseCase(listOf(request), now = 1000L)
        val mediaId = importResults.first().mediaId

        // But we want it to be FAILED or STAGED to discard (READY assets reject discard)
        mediaRepository.markMediaFailed(mediaId, "conv-1", "temp_failed", 2000L)
        // Also put a staging source back there (re-register stream)
        shadowOf(context.contentResolver).registerInputStream(contentUri, imageFile.inputStream())
        val staging = fileStore.copyToStaging(mediaId, LocalMediaInput.ContentReference(contentUri.toString()), ImportLocalMediaUseCase.MAX_SOURCE_FILE_SIZE_BYTES)
        assertTrue(staging.exists())

        // Run discard
        val discarded = discardUseCase(mediaId, now = 3000L)
        assertTrue(discarded)

        // Verify files are deleted
        assertFalse(staging.exists())
        assertFalse(File(context.filesDir, "media/master/$mediaId.jpg").exists())

        // Verify database soft deleted
        val asset = database.mediaAssetDao().getById(mediaId)
        assertNotNull(asset)
        assertEquals(3000L, asset!!.deletedAt)
    }

    @Test
    fun testCleanupStaleMediaCollectsCandidatesAndSoftDeletesCorrectly() = runTest {
        insertConversation("conv-1")
        
        // Staged asset 1: updated 25 hours ago (eligible)
        mediaRepository.createStagedMedia(listOf(NewMediaAssetRequest("m-1", "owner-1", "conv-1", MediaSource.CAMERA)), now = 1000L)
        val file1 = fileStore.getStagingSourceFile("m-1")
        file1.createNewFile()

        // Staged asset 2: updated 5 hours ago (not eligible)
        mediaRepository.createStagedMedia(listOf(NewMediaAssetRequest("m-2", "owner-1", "conv-1", MediaSource.CAMERA)), now = System.currentTimeMillis())
        val file2 = fileStore.getStagingSourceFile("m-2")
        file2.createNewFile()

        // Failed asset 3: updated 25 hours ago (eligible)
        mediaRepository.createStagedMedia(listOf(NewMediaAssetRequest("m-3", "owner-1", "conv-1", MediaSource.CAMERA)), now = 1000L)
        mediaRepository.markMediaFailed("m-3", "conv-1", "reason", 2000L)
        val file3 = fileStore.getStagingSourceFile("m-3")
        file3.createNewFile()

        // Ready asset 4: updated 25 hours ago (not eligible)
        mediaRepository.createStagedMedia(listOf(NewMediaAssetRequest("m-4", "owner-1", "conv-1", MediaSource.CAMERA)), now = 1000L)
        mediaRepository.markMediaReady("m-4", "conv-1", "media/master/m-4.jpg", "media/thumb/m-4.jpg", "image/jpeg", 10, 10, 10, "sha", 2000L)
        val file4 = fileStore.getStagingSourceFile("m-4")
        file4.createNewFile()

        // Staged asset 5: updated 25 hours ago but attached to message (not eligible)
        mediaRepository.createStagedMedia(listOf(NewMediaAssetRequest("m-5", "owner-1", "conv-1", MediaSource.CAMERA)), now = 1000L)
        // Add fake message and attach
        database.aiChatMessageDao().insertMessage(
            com.example.data.local.entity.AiChatMessageEntity(
                id = "msg-1", conversationId = "conv-1", role = "User", text = "hi", createdAt = 1000L, relatedDraftId = null, messageType = "Text", updatedAt = 1000L
            )
        )
        mediaRepository.attachMediaToMessage(listOf("m-5"), "conv-1", "msg-1", 2000L)
        val file5 = fileStore.getStagingSourceFile("m-5")
        file5.createNewFile()

        // Run cleanup
        val stats = cleanupUseCase(updatedBefore = System.currentTimeMillis() - 24 * 3600 * 1000, now = System.currentTimeMillis())
        assertEquals(2, stats.successCount)
        assertEquals(0, stats.failureCount)

        // Verify file1 (m-1) and file3 (m-3) deleted
        assertFalse(file1.exists())
        assertFalse(file3.exists())
        // Verify DB soft deleted
        assertNotNull(database.mediaAssetDao().getById("m-1")?.deletedAt)
        assertNotNull(database.mediaAssetDao().getById("m-3")?.deletedAt)

        // Verify other files not deleted
        assertTrue(file2.exists())
        assertNull(database.mediaAssetDao().getById("m-2")?.deletedAt)
        assertTrue(file4.exists())
        assertNull(database.mediaAssetDao().getById("m-4")?.deletedAt)
        assertTrue(file5.exists())
        assertNull(database.mediaAssetDao().getById("m-5")?.deletedAt)
    }

    @Test
    fun testCoroutineCancellationCleansUpCurrentItemAndPreservesStagingSource() = runTest {
        insertConversation("conv-1")
        val imageFile = createTestImageFile("source.jpg", 100, 100)
        val contentUri = Uri.parse("content://com.example/photo.jpg")
        shadowOf(context.contentResolver).registerInputStream(contentUri, imageFile.inputStream())

        // In order to simulate a cancel in the middle of processing:
        // We throw CancellationException inside writeReadyFilesAtomically
        val cancelImportRepository = object : LocalMediaImportRepository by importRepository {
            override suspend fun importStagedMedia(mediaId: String, request: ImportLocalMediaRequest): LocalMediaImportItemResult {
                // Copy to staging
                fileStore.copyToStaging(mediaId, request.input, ImportLocalMediaUseCase.MAX_SOURCE_FILE_SIZE_BYTES)
                // Write ready files starts, but is cancelled
                fileStore.deletePartFiles(mediaId)
                throw CancellationException("Simulated coroutine cancellation")
            }
        }
        val cancelUseCase = ImportLocalMediaUseCase(mediaRepository, cancelImportRepository) { "m-cancel" }

        val request = ImportLocalMediaRequest("conv-1", "owner-1", MediaSource.PHOTO_PICKER, LocalMediaInput.ContentReference(contentUri.toString()))

        var exceptionThrown: CancellationException? = null
        try {
            cancelUseCase(listOf(request), now = 1000L)
        } catch (c: CancellationException) {
            exceptionThrown = c
        }

        assertNotNull(exceptionThrown)

        // DB state remains STAGED (does NOT become FAILED)
        val asset = database.mediaAssetDao().getById("m-cancel")
        assertNotNull(asset)
        assertEquals(MediaLifecycleState.STAGED.name, asset!!.lifecycleState)

        // Staging source is preserved
        assertTrue(fileStore.getStagingSourceFile("m-cancel").exists())

        // .part files cleaned up
        assertFalse(File(context.cacheDir, "media/import/m-cancel.source.part").exists())
        assertFalse(File(context.filesDir, "media/master/m-cancel.jpg.part").exists())
    }

    private suspend fun insertConversation(id: String) {
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = "2026-06-18",
                title = id,
                lastMessagePreview = "preview",
                createdAt = 1L,
                updatedAt = 1L,
                lastActivityAt = 1L
            )
        )
    }

    private fun createFakeGifFile(fileName: String): File {
        val file = File(context.cacheDir, fileName)
        file.parentFile?.mkdirs()
        val gifBytes = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
            0x01, 0x00, 0x01, 0x00, // 1x1 width, height
            0x80.toByte(), 0x00, 0x00,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), // Global color table
            0x00, 0x00, 0x00,
            0x21, 0xf9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, // Graphic control extension
            0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // Image descriptor
            0x02, 0x02, 0x4c, 0x01, 0x00, 0x3b // Image data, Trailer
        )
        file.writeBytes(gifBytes)
        return file
    }

    private fun createTestImageFile(
        fileName: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): File {
        val file = File(context.cacheDir, fileName)
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.RED)
        file.outputStream().use { out ->
            bitmap.compress(format, 90, out)
        }
        bitmap.recycle()

        if (format == Bitmap.CompressFormat.JPEG) {
            val exifInterface = ExifInterface(file.absolutePath)
            exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39.9042")
            exifInterface.saveAttributes()
            
            val doubleCheck = ExifInterface(file.absolutePath)
            val readOrient = doubleCheck.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            System.out.println("DEBUG: createTestImageFile saved orientation=$orientation, readOrient=$readOrient")
        }
        return file
    }
}
