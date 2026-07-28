package com.goings.dayzero.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goings.dayzero.data.local.dao.AiChatMessageDao
import com.goings.dayzero.data.local.dao.MediaAssetDao
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.AiChatMessageEntity
import com.goings.dayzero.data.local.entity.ConversationEntity
import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.data.media.AiImageDerivativeProcessor
import com.goings.dayzero.data.media.AndroidMediaFileStore
import com.goings.dayzero.data.media.MediaFileStore
import com.goings.dayzero.data.media.ProcessedImageMetadata
import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.identity.CurrentIdentityProvider
import com.goings.dayzero.domain.model.ai.ChatMessageType
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaRequest
import com.goings.dayzero.domain.model.ai.assistant.PrepareVisionAttachmentsRequest
import com.goings.dayzero.domain.model.ai.assistant.PreparedVisionAttachment
import com.goings.dayzero.domain.model.ai.assistant.PreparedVisionRequest
import com.goings.dayzero.domain.model.ai.assistant.VisionPreparationFailure
import com.goings.dayzero.domain.model.media.MediaLifecycleState
import com.goings.dayzero.domain.model.media.MediaSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidVisionAttachmentPreparationRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private lateinit var messageDao: AiChatMessageDao
    private lateinit var mediaDao: MediaAssetDao
    private lateinit var fileStore: MediaFileStore
    private lateinit var derivativeProcessor: AiImageDerivativeProcessor
    private lateinit var repository: AndroidVisionAttachmentPreparationRepository
    private lateinit var chatTransactionRepository: RoomChatMediaTransactionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        messageDao = database.aiChatMessageDao()
        mediaDao = database.mediaAssetDao()
        fileStore = AndroidMediaFileStore(context)
        derivativeProcessor = FakeAiImageDerivativeProcessor()
        repository = AndroidVisionAttachmentPreparationRepository(
            context = context,
            messageDao = messageDao,
            mediaDao = mediaDao,
            fileStore = fileStore,
            derivativeProcessor = derivativeProcessor
        )
        chatTransactionRepository = RoomChatMediaTransactionRepository(
            database = database,
            identityProvider = StaticIdentityProvider(),
            chatSyncQueueWriter = com.goings.dayzero.data.sync.chat.ChatSyncQueueWriter(database.syncQueueDao())
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun textAndSingleImage_preparesOneAttachment() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "look at this", mediaIds)
        val requestId = UUID.randomUUID().toString()

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = requestId,
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isSuccess)
        val prepared = result.getOrThrow()
        assertEquals("look at this", prepared.effectiveAiText)
        assertEquals(1, prepared.attachments.size)
        assertEquals(mediaIds[0], prepared.attachments[0].mediaId)
        assertEquals(PreparedVisionRequest.MIME_TYPE_JPEG, prepared.attachments[0].mimeType)
        assertTrue(prepared.attachments[0].base64.isNotBlank())
        assertTrue(prepared.attachments[0].byteSize > 0)

        // Verify derivative file was created.
        val derivativeDir = File(context.cacheDir, "media/ai/$requestId")
        assertTrue(derivativeDir.exists())
        assertEquals(1, derivativeDir.listFiles()?.size ?: 0)
    }

    @Test
    fun imageOnly_usesDefaultPrompt() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "", mediaIds)

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isSuccess)
        val prepared = result.getOrThrow()
        assertEquals(PreparedVisionRequest.IMAGE_ONLY_PROMPT, prepared.effectiveAiText)
    }

    @Test
    fun sixImages_preparesSixAttachmentsInOrder() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 6)
        val userMessageId = commitUserMessage(conversationId, "six images", mediaIds)

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isSuccess)
        val prepared = result.getOrThrow()
        assertEquals(6, prepared.attachments.size)
        assertEquals(mediaIds, prepared.attachments.map { it.mediaId })
        assertTrue(prepared.totalByteSize <= PreparedVisionRequest.MAX_TOTAL_ATTACHMENT_BYTES)
    }

    @Test
    fun sourceMediaIdsOrderIsPreserved() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 3)
        val reversedIds = mediaIds.reversed()
        val userMessageId = commitUserMessage(conversationId, "ordered", reversedIds)

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isSuccess)
        val prepared = result.getOrThrow()
        assertEquals(reversedIds, prepared.attachments.map { it.mediaId })
    }

    @Test
    fun missingMessage_returnsMessageNotFound() = runBlocking {
        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = "conv-1",
                userMessageId = "missing-msg"
            )
        )

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as VisionPreparationFailure.MessageNotFound
        assertEquals("MESSAGE_NOT_FOUND", failure.errorCode)
    }

    @Test
    fun wrongConversation_returnsMessageNotFound() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = "other-conv",
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.MessageNotFound)
    }

    @Test
    fun assistantMessage_returnsInvalidMessage() = runBlocking {
        val conversationId = insertConversation()
        val messageId = UUID.randomUUID().toString()
        messageDao.insertMessage(
            AiChatMessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = ChatRole.Assistant.name,
                text = "assistant text",
                createdAt = 1000L,
                relatedDraftId = null,
                messageType = ChatMessageType.Text.name,
                updatedAt = 1000L
            )
        )

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = messageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.InvalidMessage)
    }

    @Test
    fun softDeletedMessage_returnsInvalidMessage() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)
        messageDao.applyRemoteMutableFields(
            id = userMessageId,
            text = "text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 2000L,
            deletedAt = 2000L
        )

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.InvalidMessage)
    }

    @Test
    fun invalidSchemaVersion_returnsInvalidMediaContract() = runBlocking {
        val conversationId = insertConversation()
        val messageId = UUID.randomUUID().toString()
        messageDao.insertMessage(
            AiChatMessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = ChatRole.User.name,
                text = "text",
                createdAt = 1000L,
                relatedDraftId = null,
                messageType = ChatMessageType.Text.name,
                contentJson = """{"media":{"schemaVersion":2,"sourceMediaIds":["m1"]}}""",
                updatedAt = 1000L
            )
        )

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = messageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.InvalidMediaContract)
    }

    @Test
    fun emptyMediaId_returnsInvalidMediaContract() = runBlocking {
        val conversationId = insertConversation()
        val messageId = UUID.randomUUID().toString()
        messageDao.insertMessage(
            AiChatMessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = ChatRole.User.name,
                text = "text",
                createdAt = 1000L,
                relatedDraftId = null,
                messageType = ChatMessageType.Text.name,
                contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":[""]}}""",
                updatedAt = 1000L
            )
        )

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = messageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.InvalidMediaContract)
    }

    @Test
    fun duplicateMediaIds_returnsInvalidMediaContract() = runBlocking {
        val conversationId = insertConversation()
        val messageId = UUID.randomUUID().toString()
        messageDao.insertMessage(
            AiChatMessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = ChatRole.User.name,
                text = "text",
                createdAt = 1000L,
                relatedDraftId = null,
                messageType = ChatMessageType.Text.name,
                contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":["m1","m1"]}}""",
                updatedAt = 1000L
            )
        )

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = messageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.InvalidMediaContract)
    }

    @Test
    fun missingMediaAsset_returnsMediaNotFound() = runBlocking {
        val conversationId = insertConversation()
        val messageId = UUID.randomUUID().toString()
        messageDao.insertMessage(
            AiChatMessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = ChatRole.User.name,
                text = "text",
                createdAt = 1000L,
                relatedDraftId = null,
                messageType = ChatMessageType.Text.name,
                contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":["missing"]}}""",
                updatedAt = 1000L
            )
        )

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = messageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.MediaNotFound)
    }

    @Test
    fun stagedMedia_returnsMediaNotReady() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertStagedMedia(conversationId, count = 1)
        val userMessageId = insertUserMessageWithMedia(conversationId, "text", mediaIds)

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.MediaNotReady)
    }

    @Test
    fun failedMedia_returnsMediaNotReady() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertStagedMedia(conversationId, count = 1)
        mediaDao.markFailed(mediaIds[0], "test-failed", System.currentTimeMillis())
        val userMessageId = insertUserMessageWithMedia(conversationId, "text", mediaIds)

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.MediaNotReady)
    }

    @Test
    fun sourceMessageIdMismatch_returnsMediaBindingMismatch() = runBlocking {
        val conversationId = insertConversation()
        val mediaId = UUID.randomUUID().toString()
        // Insert media already bound to a different message.
        mediaDao.insertAll(
            listOf(
                MediaAssetEntity(
                    id = mediaId,
                    ownerLocalId = "owner-1",
                    conversationId = conversationId,
                    sourceMessageId = "other-message",
                    conversationOrder = 1L,
                    masterRelativePath = "media/master/$mediaId.jpg",
                    thumbnailRelativePath = "media/thumbnail/$mediaId.jpg",
                    mimeType = "image/jpeg",
                    width = 100,
                    height = 100,
                    byteSize = 1024L,
                    sha256 = "sha256-$mediaId",
                    source = MediaSource.PHOTO_PICKER.name,
                    lifecycleState = MediaLifecycleState.READY.name,
                    failureCode = null,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    deletedAt = null
                )
            )
        )
        // Create master file so path resolution succeeds; binding check happens before derivative.
        File(context.filesDir, "media/master/$mediaId.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        }
        val userMessageId = UUID.randomUUID().toString()
        messageDao.insertMessage(
            AiChatMessageEntity(
                id = userMessageId,
                conversationId = conversationId,
                role = ChatRole.User.name,
                text = "text",
                createdAt = System.currentTimeMillis(),
                relatedDraftId = null,
                messageType = ChatMessageType.Text.name,
                contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":["$mediaId"]}}""",
                updatedAt = System.currentTimeMillis()
            )
        )

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.MediaBindingMismatch)
    }

    @Test
    fun missingMasterFile_returnsMasterFileMissing() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)

        // Delete the master file.
        File(context.filesDir, "media/master/${mediaIds[0]}.jpg").delete()

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VisionPreparationFailure.MasterFileMissing)
    }

    @Test
    fun extraUnrelatedMedia_notIncludedInRequest() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 3)
        val userMessageId = commitUserMessage(conversationId, "two images", mediaIds.take(2))

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertTrue(result.isSuccess)
        val prepared = result.getOrThrow()
        assertEquals(2, prepared.attachments.size)
        assertFalse(prepared.attachments.any { it.mediaId == mediaIds[2] })
    }

    @Test
    fun base64DecodesBackToDerivativeBytes() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)
        val requestId = UUID.randomUUID().toString()

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = requestId,
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        val prepared = result.getOrThrow()
        val attachment = prepared.attachments[0]
        val decoded = Base64.decode(attachment.base64, Base64.NO_WRAP)
        assertEquals(attachment.byteSize, decoded.size.toLong())

        val derivativeFile = File(context.cacheDir, "media/ai/$requestId/${attachment.mediaId}.jpg")
        assertTrue(derivativeFile.exists())
        assertEquals(derivativeFile.readBytes().toList(), decoded.toList())
    }

    @Test
    fun base64HasNoNewlinesOrDataUrlPrefix() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)

        val result = repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        val attachment = result.getOrThrow().attachments[0]
        assertFalse(attachment.base64.contains("\n"))
        assertFalse(attachment.base64.contains("data:"))
    }

    @Test
    fun release_deletesRequestDirectory() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)
        val requestId = UUID.randomUUID().toString()

        repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = requestId,
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        val releaseResult = repository.release(requestId)
        assertTrue(releaseResult.isSuccess)
        assertFalse(File(context.cacheDir, "media/ai/$requestId").exists())
    }

    @Test
    fun release_isIdempotent() = runBlocking {
        val requestId = UUID.randomUUID().toString()

        val first = repository.release(requestId)
        val second = repository.release(requestId)

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
    }

    @Test
    fun prepare_cleansUpPartFiles() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)
        val requestId = UUID.randomUUID().toString()

        repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = requestId,
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        val derivativeDir = File(context.cacheDir, "media/ai/$requestId")
        val partFiles = derivativeDir.listFiles()?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue(partFiles.isEmpty())
    }

    @Test
    fun prepare_doesNotDeleteMasterOrThumbnail() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)
        val requestId = UUID.randomUUID().toString()

        repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = requestId,
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )
        repository.release(requestId)

        assertTrue(File(context.filesDir, "media/master/${mediaIds[0]}.jpg").exists())
        assertTrue(File(context.filesDir, "media/thumbnail/${mediaIds[0]}.jpg").exists())
    }

    @Test
    fun prepare_cleansStaleAiDirectories() = runBlocking {
        val staleDir = File(context.cacheDir, "media/ai/stale-request")
        staleDir.mkdirs()
        staleDir.setLastModified(System.currentTimeMillis() - 25L * 60L * 60L * 1000L)

        val conversationId = insertConversation()
        val mediaIds = createReadyMasterImages(conversationId, count = 1)
        val userMessageId = commitUserMessage(conversationId, "text", mediaIds)

        repository.prepare(
            PrepareVisionAttachmentsRequest(
                requestId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )

        assertFalse(staleDir.exists())
    }

    private suspend fun insertConversation(): String {
        val id = UUID.randomUUID().toString()
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = java.time.LocalDate.now().toString(),
                title = "Test",
                lastMessagePreview = "",
                lastActivityAt = 1000L,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        return id
    }

    private suspend fun createReadyMasterImages(conversationId: String, count: Int): List<String> {
        val ids = (1..count).map { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        val entities = ids.mapIndexed { index, id ->
            MediaAssetEntity(
                id = id,
                ownerLocalId = "owner-1",
                conversationId = conversationId,
                sourceMessageId = null,
                conversationOrder = index.toLong() + 1L,
                masterRelativePath = null,
                thumbnailRelativePath = null,
                mimeType = null,
                width = null,
                height = null,
                byteSize = null,
                sha256 = null,
                source = MediaSource.PHOTO_PICKER.name,
                lifecycleState = MediaLifecycleState.STAGED.name,
                failureCode = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            )
        }
        mediaDao.insertAll(entities)

        ids.forEachIndexed { index, id ->
            val masterFile = File(context.filesDir, "media/master/$id.jpg")
            masterFile.parentFile?.mkdirs()
            val thumbnailFile = File(context.filesDir, "media/thumbnail/$id.jpg")
            thumbnailFile.parentFile?.mkdirs()

            val bitmap = createTestBitmap(100 + index * 50, 100 + index * 50, Color.rgb(index * 40, 100, 100))
            masterFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            thumbnailFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            mediaDao.markReady(
                id = id,
                masterRelativePath = "media/master/$id.jpg",
                thumbnailRelativePath = "media/thumbnail/$id.jpg",
                mimeType = "image/jpeg",
                width = 100 + index * 50,
                height = 100 + index * 50,
                byteSize = masterFile.length(),
                sha256 = "sha256-$id",
                updatedAt = now
            )
        }
        return ids
    }

    private fun createTestBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        return bitmap
    }

    private suspend fun insertStagedMedia(conversationId: String, count: Int): List<String> {
        val ids = (1..count).map { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        val entities = ids.mapIndexed { index, id ->
            MediaAssetEntity(
                id = id,
                ownerLocalId = "owner-1",
                conversationId = conversationId,
                sourceMessageId = null,
                conversationOrder = index.toLong() + 1L,
                masterRelativePath = null,
                thumbnailRelativePath = null,
                mimeType = null,
                width = null,
                height = null,
                byteSize = null,
                sha256 = null,
                source = MediaSource.PHOTO_PICKER.name,
                lifecycleState = MediaLifecycleState.STAGED.name,
                failureCode = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            )
        }
        mediaDao.insertAll(entities)
        return ids
    }

    private suspend fun insertUserMessageWithMedia(
        conversationId: String,
        text: String,
        mediaIds: List<String>
    ): String {
        val userMessageId = UUID.randomUUID().toString()
        val contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":${mediaIds.toJsonArray()}}}"""
        messageDao.insertMessage(
            AiChatMessageEntity(
                id = userMessageId,
                conversationId = conversationId,
                role = ChatRole.User.name,
                text = text,
                createdAt = System.currentTimeMillis(),
                relatedDraftId = null,
                messageType = ChatMessageType.Text.name,
                contentJson = contentJson,
                updatedAt = System.currentTimeMillis()
            )
        )
        val now = System.currentTimeMillis()
        mediaIds.forEach { mediaId ->
            mediaDao.attachToMessage(mediaId, userMessageId, now)
        }
        return userMessageId
    }

    private fun List<String>.toJsonArray(): String {
        return joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    private suspend fun commitUserMessage(
        conversationId: String,
        text: String,
        mediaIds: List<String>
    ): String {
        val userMessageId = UUID.randomUUID().toString()
        val result = chatTransactionRepository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = text,
                orderedMediaIds = mediaIds,
                createdAt = System.currentTimeMillis()
            )
        )
        if (result !is com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult.Committed) {
            fail("Failed to commit user message: $result")
        }
        return userMessageId
    }

    private class StaticIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = "owner-1",
                remoteUserId = null,
                authProvider = "local",
                canRemoteSync = false
            )
        }
    }

    /**
     * Fake derivative processor that writes deterministic JPEG-like bytes without using
     * Android's image decoder, which is unreliable in the Robolectric test environment.
     */
    private class FakeAiImageDerivativeProcessor(
        private val derivativeByteSize: Long = 1024L
    ) : AiImageDerivativeProcessor {
        override fun createAiDerivative(sourceFile: File, destFile: File): ProcessedImageMetadata {
            // Write a minimal JFIF header so the output is recognizably JPEG.
            val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
            val payload = ByteArray(derivativeByteSize.toInt()) { 0x00 }
            destFile.outputStream().use { out ->
                out.write(header)
                out.write(payload)
            }
            return ProcessedImageMetadata(
                width = 100,
                height = 100,
                mimeType = PreparedVisionRequest.MIME_TYPE_JPEG
            )
        }
    }
}
