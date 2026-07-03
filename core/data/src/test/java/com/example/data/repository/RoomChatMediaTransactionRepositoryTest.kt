package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.dao.MediaAssetDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MediaAssetEntity
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.sync.DayZeroSyncConstants
import com.example.data.sync.chat.ChatSyncQueueContract
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatMessageType
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.SendUserMessageWithMediaRequest
import com.example.domain.model.ai.SendUserMessageWithMediaResult
import com.example.domain.model.media.MediaLifecycleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RoomChatMediaTransactionRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private lateinit var repository: RoomChatMediaTransactionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomChatMediaTransactionRepository(
            database = database,
            identityProvider = StaticIdentityProvider(),
            chatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao())
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun commitsTextAndSingleImageAtomically() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 1)
        val userMessageId = UUID.randomUUID().toString()
        val now = 9999L

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "look at this",
                orderedMediaIds = mediaIds,
                createdAt = now
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Committed)
        val committed = result as SendUserMessageWithMediaResult.Committed
        assertEquals(userMessageId, committed.userMessageId)
        assertEquals(
            RoomChatMediaTransactionRepository.assistantPlaceholderId(userMessageId),
            committed.assistantPlaceholderId
        )

        val userMessage = database.aiChatMessageDao().getMessageById(userMessageId)
        assertNotNull(userMessage)
        assertEquals("look at this", userMessage!!.text)
        assertEquals(ChatRole.User.name, userMessage.role)
        assertTrue(userMessage.contentJson!!.contains("\"sourceMediaIds\""))

        val media = database.mediaAssetDao().getById(mediaIds.single())
        assertEquals(userMessageId, media!!.sourceMessageId)

        val placeholder = database.aiChatMessageDao().getMessageById(committed.assistantPlaceholderId)
        assertNotNull(placeholder)
        assertEquals(ChatRole.Assistant.name, placeholder!!.role)
        assertEquals("", placeholder.text)

        val conversation = database.conversationDao().getConversationById(conversationId)
        assertEquals("look at this", conversation!!.lastMessagePreview)

        val tasks = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING)
        assertEquals(2, tasks.size)
        assertTrue(tasks.any { it.entityType == ChatSyncQueueContract.ENTITY_CONVERSATION })
        assertTrue(tasks.any { it.entityType == ChatSyncQueueContract.ENTITY_MESSAGE && it.entityLocalId == userMessageId })
        assertTrue(tasks.none { it.entityLocalId == committed.assistantPlaceholderId })
    }

    @Test
    fun commitsImageOnlyMessage() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 3)
        val userMessageId = UUID.randomUUID().toString()

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Committed)
        val userMessage = database.aiChatMessageDao().getMessageById(userMessageId)!!
        assertEquals("", userMessage.text)
        assertTrue(userMessage.contentJson!!.contains("\"sourceMediaIds\""))

        val conversation = database.conversationDao().getConversationById(conversationId)!!
        assertEquals("发送了 3 张图片", conversation.lastMessagePreview)
    }

    @Test
    fun sourceMediaIdsOrderIsPreservedInContentJson() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 4)
        val userMessageId = UUID.randomUUID().toString()

        repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "",
                orderedMediaIds = mediaIds.reversed(),
                createdAt = 9999L
            )
        )

        val message = database.aiChatMessageDao().getMessageById(userMessageId)!!
        val domain = com.example.data.local.mapper.AiChatMessageMapper().toDomain(message)
        assertEquals(mediaIds.reversed(), domain.sourceMediaIds)
    }

    @Test
    fun duplicateRequestReturnsAlreadyCommitted() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()
        val request = SendUserMessageWithMediaRequest(
            conversationId = conversationId,
            userMessageId = userMessageId,
            text = "hi",
            orderedMediaIds = mediaIds,
            createdAt = 9999L
        )

        val first = repository.sendUserMessageWithMedia(request)
        val second = repository.sendUserMessageWithMedia(request)

        assertTrue(first is SendUserMessageWithMediaResult.Committed)
        assertTrue(second is SendUserMessageWithMediaResult.AlreadyCommitted)

        val messages = database.aiChatMessageDao().getMessagesByConversationId(conversationId)
        assertEquals(2, messages.size)
        val tasks = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING)
        assertEquals(2, tasks.size)
    }

    @Test
    fun sameUserMessageIdWithDifferentContentReturnsConflict() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 1)
        val userMessageId = UUID.randomUUID().toString()

        repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "first",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        val second = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "second",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(second is SendUserMessageWithMediaResult.Conflict)
    }

    @Test
    fun missingConversationReturnsInvalidConversation() = runBlocking {
        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = "missing",
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = listOf("media-1"),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidConversation)
    }

    @Test
    fun deletedConversationReturnsInvalidConversation() = runBlocking {
        val conversationId = insertConversation(deletedAt = 5000L)
        val mediaIds = insertReadyMedia(conversationId, count = 1)

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidConversation)
    }

    @Test
    fun emptyMediaListReturnsInvalidMedia() = runBlocking {
        val conversationId = insertConversation()

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = emptyList(),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidMedia)
    }

    @Test
    fun tooManyMediaReturnsInvalidMedia() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 7)

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidMedia)
    }

    @Test
    fun duplicateMediaIdsInRequestReturnsInvalidMedia() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 1)

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = listOf(mediaIds.first(), mediaIds.first()),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidMedia)
    }

    @Test
    fun mediaFromOtherConversationReturnsInvalidMedia() = runBlocking {
        val conversationA = insertConversation("conv-a")
        val conversationB = insertConversation("conv-b")
        val mediaIds = insertReadyMedia(conversationA, count = 1)

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationB,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidMedia)
    }

    @Test
    fun stagedMediaReturnsInvalidMedia() = runBlocking {
        val conversationId = insertConversation()
        val mediaId = UUID.randomUUID().toString()
        insertMedia(
            conversationId = conversationId,
            id = mediaId,
            lifecycleState = MediaLifecycleState.STAGED
        )

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = listOf(mediaId),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidMedia)
    }

    @Test
    fun alreadyAttachedMediaReturnsMediaAlreadyAttached() = runBlocking {
        val conversationId = insertConversation()
        val mediaId = UUID.randomUUID().toString()
        insertMedia(
            conversationId = conversationId,
            id = mediaId,
            lifecycleState = MediaLifecycleState.READY,
            sourceMessageId = "other-message"
        )

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = listOf(mediaId),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.MediaAlreadyAttached)
        val attached = result as SendUserMessageWithMediaResult.MediaAlreadyAttached
        assertEquals(listOf(mediaId), attached.mediaIds)
    }

    @Test
    fun concurrentCompetingMediaBindingReturnsConflictOrAlreadyCommitted() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val messageA = UUID.randomUUID().toString()
        val messageB = UUID.randomUUID().toString()

        val resultA = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = messageA,
                text = "a",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        val resultB = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = messageB,
                text = "b",
                orderedMediaIds = mediaIds,
                createdAt = 10000L
            )
        )

        // One must commit and the other must fail because media are exclusively bound.
        assertTrue(
            (resultA is SendUserMessageWithMediaResult.Committed && resultB !is SendUserMessageWithMediaResult.Committed) ||
                (resultB is SendUserMessageWithMediaResult.Committed && resultA !is SendUserMessageWithMediaResult.Committed)
        )
    }

    @Test
    fun placeholderIdIsDeterministic() {
        val userMessageId = UUID.randomUUID().toString()
        val first = RoomChatMediaTransactionRepository.assistantPlaceholderId(userMessageId)
        val second = RoomChatMediaTransactionRepository.assistantPlaceholderId(userMessageId)
        assertEquals(first, second)
        assertTrue(first.isNotBlank())
    }

    @Test
    fun commitsSixImagesAtLimit() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 6)
        val userMessageId = UUID.randomUUID().toString()

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Committed)
        val bound = database.mediaAssetDao().getActiveByConversation(conversationId)
        assertEquals(6, bound.size)
        assertTrue(bound.all { it.sourceMessageId == userMessageId })
    }

    @Test
    fun rejectsFailedMedia() = runBlocking {
        val conversationId = insertConversation()
        val mediaId = UUID.randomUUID().toString()
        insertMedia(
            conversationId = conversationId,
            id = mediaId,
            lifecycleState = MediaLifecycleState.FAILED,
            failureCode = "DIMENSION_LIMIT"
        )

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = listOf(mediaId),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidMedia)
    }

    @Test
    fun rejectsSoftDeletedMedia() = runBlocking {
        val conversationId = insertConversation()
        val mediaId = UUID.randomUUID().toString()
        insertMedia(
            conversationId = conversationId,
            id = mediaId,
            lifecycleState = MediaLifecycleState.READY,
            deletedAt = 5000L
        )

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = UUID.randomUUID().toString(),
                text = "hi",
                orderedMediaIds = listOf(mediaId),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.InvalidMedia)
    }

    @Test
    fun casAffectedRowsMismatchRollsBackEverything() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()

        val mismatchRepository = RoomChatMediaTransactionRepository(
            database = database,
            identityProvider = StaticIdentityProvider(),
            chatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao()),
            mediaAssetDao = MismatchMediaDao(database.mediaAssetDao())
        )

        val result = mismatchRepository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "hi",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Conflict)
        assertUnchangedAfterRollback(conversationId, userMessageId, mediaIds, originalPreview = "preview")
    }

    @Test
    fun conversationQueueFailureRollsBackEverything() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()

        val failingRepository = RoomChatMediaTransactionRepository(
            database = database,
            identityProvider = StaticIdentityProvider(),
            chatSyncQueueWriter = ChatSyncQueueWriter(
                FailingSyncQueueDao(failForEntityType = ChatSyncQueueContract.ENTITY_CONVERSATION)
            )
        )

        val result = failingRepository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "hi",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Failed)
        assertUnchangedAfterRollback(conversationId, userMessageId, mediaIds, originalPreview = "preview")
    }

    @Test
    fun messageQueueFailureRollsBackEverything() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()

        val failingRepository = RoomChatMediaTransactionRepository(
            database = database,
            identityProvider = StaticIdentityProvider(),
            chatSyncQueueWriter = ChatSyncQueueWriter(
                FailingSyncQueueDao(failForEntityType = ChatSyncQueueContract.ENTITY_MESSAGE)
            )
        )

        val result = failingRepository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "hi",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Failed)
        assertUnchangedAfterRollback(conversationId, userMessageId, mediaIds, originalPreview = "preview")
    }

    @Test
    fun cancellationRollsBackEverythingAndRethrows() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()

        val cancellingRepository = RoomChatMediaTransactionRepository(
            database = database,
            identityProvider = DelayingIdentityProvider(),
            chatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao())
        )

        try {
            withTimeout(100) {
                cancellingRepository.sendUserMessageWithMedia(
                    SendUserMessageWithMediaRequest(
                        conversationId = conversationId,
                        userMessageId = userMessageId,
                        text = "hi",
                        orderedMediaIds = mediaIds,
                        createdAt = 9999L
                    )
                )
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }

        assertUnchangedAfterRollback(conversationId, userMessageId, mediaIds, originalPreview = "preview")
    }

    @Test
    fun existingFinalAssistantMessageIsNeverOverwritten() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()
        val request = SendUserMessageWithMediaRequest(
            conversationId = conversationId,
            userMessageId = userMessageId,
            text = "final test",
            orderedMediaIds = mediaIds,
            createdAt = 9999L
        )

        repository.sendUserMessageWithMedia(request)

        val placeholderId = RoomChatMediaTransactionRepository.assistantPlaceholderId(userMessageId)
        database.aiChatMessageDao().updateMessageContentIfActive(
            id = placeholderId,
            text = "Final answer",
            messageType = ChatMessageType.Text.name,
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 10000L
        )

        val result = repository.sendUserMessageWithMedia(request)
        assertTrue(result is SendUserMessageWithMediaResult.AlreadyCommitted)

        val placeholder = database.aiChatMessageDao().getMessageById(placeholderId)!!
        assertEquals("Final answer", placeholder.text)
    }

    @Test
    fun alreadyCommittedRequiresAllMediaBoundToUserMessage() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()
        val request = SendUserMessageWithMediaRequest(
            conversationId = conversationId,
            userMessageId = userMessageId,
            text = "hi",
            orderedMediaIds = mediaIds,
            createdAt = 9999L
        )

        repository.sendUserMessageWithMedia(request)

        // Unbind one media to simulate partial commit from a previous crash.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE media_assets SET sourceMessageId = NULL WHERE id = ?",
            arrayOf(mediaIds.last())
        )

        val result = repository.sendUserMessageWithMedia(request)
        assertTrue(result is SendUserMessageWithMediaResult.Conflict)
    }

    @Test
    fun partiallyBoundExistingMessageReturnsConflict() = runBlocking {
        val conversationId = insertConversation()
        val mediaA = UUID.randomUUID().toString()
        val mediaB = UUID.randomUUID().toString()
        insertMedia(conversationId, mediaA, MediaLifecycleState.READY, conversationOrder = 1L)
        insertMedia(conversationId, mediaB, MediaLifecycleState.READY, conversationOrder = 2L)
        val userMessageId = UUID.randomUUID().toString()

        // Pre-create user message with contentJson for two media, but bind only one.
        database.aiChatMessageDao().insertMessage(
            com.example.data.local.mapper.AiChatMessageMapper().toEntity(
                AiChatMessage(
                    id = userMessageId,
                    conversationId = conversationId,
                    role = ChatRole.User,
                    text = "partial",
                    createdAt = 9999L,
                    sourceMediaIds = listOf(mediaA, mediaB),
                    updatedAt = 9999L
                )
            )
        )
        database.mediaAssetDao().attachToMessage(
            id = mediaA,
            sourceMessageId = userMessageId,
            updatedAt = 9999L
        )

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "partial",
                orderedMediaIds = listOf(mediaA, mediaB),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Conflict)
    }

    @Test
    fun strictInsertDoesNotReplaceExistingUserMessage() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 1)
        val userMessageId = UUID.randomUUID().toString()

        database.aiChatMessageDao().insertMessage(
            com.example.data.local.mapper.AiChatMessageMapper().toEntity(
                AiChatMessage(
                    id = userMessageId,
                    conversationId = conversationId,
                    role = ChatRole.User,
                    text = "original",
                    createdAt = 9999L,
                    sourceMediaIds = emptyList(),
                    updatedAt = 9999L
                )
            )
        )

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "different",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Conflict)
        val userMessage = database.aiChatMessageDao().getMessageById(userMessageId)!!
        assertEquals("original", userMessage.text)
    }

    @Test
    fun preservesUnknownTopLevelAndNestedMediaJsonFields() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 1)
        val userMessageId = UUID.randomUUID().toString()

        val contentJson = """
            {
                "futureTopLevel": {"keep": true},
                "media": {
                    "schemaVersion": 99,
                    "sourceMediaIds": ["old"],
                    "futureNested": {"a": 1}
                }
            }
        """.trimIndent()

        database.aiChatMessageDao().insertMessage(
            com.example.data.local.mapper.AiChatMessageMapper().toEntity(
                AiChatMessage(
                    id = userMessageId,
                    conversationId = conversationId,
                    role = ChatRole.User,
                    text = "fields",
                    createdAt = 9999L,
                    sourceMediaIds = listOf(mediaIds.first()),
                    contentJson = contentJson,
                    updatedAt = 9999L
                )
            )
        )
        database.mediaAssetDao().attachToMessage(
            id = mediaIds.first(),
            sourceMessageId = userMessageId,
            updatedAt = 9999L
        )
        insertPlaceholder(userMessageId, conversationId, 9999L)

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "fields",
                orderedMediaIds = listOf(mediaIds.first()),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.AlreadyCommitted)
        val userMessage = database.aiChatMessageDao().getMessageById(userMessageId)!!
        val json = org.json.JSONObject(userMessage.contentJson!!)
        assertTrue(json.getJSONObject("futureTopLevel").getBoolean("keep"))
        val media = json.getJSONObject("media")
        assertEquals(1, media.getInt("schemaVersion"))
        assertEquals(mediaIds.first(), media.getJSONArray("sourceMediaIds").getString(0))
        assertEquals(1, media.getJSONObject("futureNested").getInt("a"))
    }

    @Test
    fun sameIdsInDifferentOrderReturnsConflict() = runBlocking {
        val conversationId = insertConversation()
        val mediaIds = insertReadyMedia(conversationId, count = 2)
        val userMessageId = UUID.randomUUID().toString()

        repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "order",
                orderedMediaIds = mediaIds,
                createdAt = 9999L
            )
        )

        val result = repository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "order",
                orderedMediaIds = mediaIds.reversed(),
                createdAt = 9999L
            )
        )

        assertTrue(result is SendUserMessageWithMediaResult.Conflict)
    }

    private suspend fun assertUnchangedAfterRollback(
        conversationId: String,
        userMessageId: String,
        mediaIds: List<String>,
        originalPreview: String
    ) {
        val userMessage = database.aiChatMessageDao().getMessageById(userMessageId)
        assertNull("User message should not exist after rollback", userMessage)

        val placeholder = database.aiChatMessageDao().getMessageById(
            RoomChatMediaTransactionRepository.assistantPlaceholderId(userMessageId)
        )
        assertNull("Placeholder should not exist after rollback", placeholder)

        mediaIds.forEach { mediaId ->
            val media = database.mediaAssetDao().getById(mediaId)
            assertNull("Media should remain unbound after rollback", media!!.sourceMessageId)
        }

        val conversation = database.conversationDao().getConversationById(conversationId)!!
        assertEquals(originalPreview, conversation.lastMessagePreview)

        val tasks = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING)
        assertTrue("No sync tasks should be queued after rollback", tasks.isEmpty())
    }

    private suspend fun insertPlaceholder(userMessageId: String, conversationId: String, createdAt: Long) {
        database.aiChatMessageDao().insertMessage(
            AiChatMessageEntity(
                id = RoomChatMediaTransactionRepository.assistantPlaceholderId(userMessageId),
                conversationId = conversationId,
                role = ChatRole.Assistant.name,
                text = "",
                createdAt = createdAt + 1,
                relatedDraftId = null,
                messageType = com.example.domain.model.ai.ChatMessageType.Text.name,
                contentJson = null,
                assistantCardsJson = null,
                suggestedRepliesJson = null,
                updatedAt = createdAt,
                deletedAt = null
            )
        )
    }

    private suspend fun insertConversation(id: String = UUID.randomUUID().toString(), deletedAt: Long? = null): String {
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = "2026-06-18",
                title = "title",
                lastMessagePreview = "preview",
                createdAt = 1000L,
                updatedAt = 1000L,
                lastActivityAt = 1000L,
                deletedAt = deletedAt
            )
        )
        return id
    }

    private suspend fun insertReadyMedia(conversationId: String, count: Int): List<String> {
        return (1..count).map { index ->
            val id = UUID.randomUUID().toString()
            insertMedia(conversationId, id, MediaLifecycleState.READY, conversationOrder = index.toLong())
            id
        }
    }

    private suspend fun insertMedia(
        conversationId: String,
        id: String,
        lifecycleState: MediaLifecycleState,
        sourceMessageId: String? = null,
        conversationOrder: Long = 1L,
        deletedAt: Long? = null,
        failureCode: String? = null
    ) {
        database.mediaAssetDao().insertAll(
            listOf(
                MediaAssetEntity(
                    id = id,
                    ownerLocalId = "owner-1",
                    conversationId = conversationId,
                    sourceMessageId = sourceMessageId,
                    conversationOrder = conversationOrder,
                    masterRelativePath = if (lifecycleState == MediaLifecycleState.READY) "master/$id.jpg" else null,
                    thumbnailRelativePath = if (lifecycleState == MediaLifecycleState.READY) "thumb/$id.jpg" else null,
                    mimeType = if (lifecycleState == MediaLifecycleState.READY) "image/jpeg" else null,
                    width = if (lifecycleState == MediaLifecycleState.READY) 100 else null,
                    height = if (lifecycleState == MediaLifecycleState.READY) 100 else null,
                    byteSize = if (lifecycleState == MediaLifecycleState.READY) 1000L else null,
                    sha256 = if (lifecycleState == MediaLifecycleState.READY) "hash" else null,
                    source = "PHOTO_PICKER",
                    lifecycleState = lifecycleState.name,
                    failureCode = failureCode,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    deletedAt = deletedAt
                )
            )
        )
    }

    private class StaticIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = "test-owner",
                remoteUserId = "00000000-0000-0000-0000-000000000001",
                authProvider = "supabase_anonymous",
                canRemoteSync = true
            )
        }
    }

    private class DelayingIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            delay(Long.MAX_VALUE)
            return AppIdentity(
                localOwnerId = "test-owner",
                remoteUserId = "00000000-0000-0000-0000-000000000001",
                authProvider = "supabase_anonymous",
                canRemoteSync = true
            )
        }
    }

    private class MismatchMediaDao(private val delegate: MediaAssetDao) : MediaAssetDao {
        override suspend fun attachReadyMediaToMessage(
            orderedMediaIds: List<String>,
            conversationId: String,
            sourceMessageId: String,
            updatedAt: Long
        ): Int = 0

        override suspend fun getByIds(ids: List<String>): List<MediaAssetEntity> =
            delegate.getByIds(ids)

        override suspend fun getActiveByConversation(conversationId: String): List<MediaAssetEntity> =
            delegate.getActiveByConversation(conversationId)

        override suspend fun insertAll(entities: List<MediaAssetEntity>): Unit =
            delegate.insertAll(entities)

        override suspend fun getMaxConversationOrder(conversationId: String): Long? =
            delegate.getMaxConversationOrder(conversationId)

        override fun observeActiveByConversation(conversationId: String): kotlinx.coroutines.flow.Flow<List<MediaAssetEntity>> =
            delegate.observeActiveByConversation(conversationId)

        override suspend fun getById(id: String): MediaAssetEntity? = delegate.getById(id)

        override suspend fun attachToMessage(
            id: String,
            sourceMessageId: String,
            updatedAt: Long
        ): Int = delegate.attachToMessage(id, sourceMessageId, updatedAt)

        override suspend fun markReady(
            id: String,
            masterRelativePath: String,
            thumbnailRelativePath: String,
            mimeType: String,
            width: Int,
            height: Int,
            byteSize: Long,
            sha256: String,
            updatedAt: Long
        ): Int = delegate.markReady(
            id,
            masterRelativePath,
            thumbnailRelativePath,
            mimeType,
            width,
            height,
            byteSize,
            sha256,
            updatedAt
        )

        override suspend fun markFailed(
            id: String,
            failureCode: String?,
            updatedAt: Long
        ): Int = delegate.markFailed(id, failureCode, updatedAt)

        override suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long): Int =
            delegate.softDelete(id, deletedAt, updatedAt)

        override suspend fun findStaleStaged(updatedBefore: Long): List<MediaAssetEntity> =
            delegate.findStaleStaged(updatedBefore)
    }

    private class FailingSyncQueueDao(private val failForEntityType: String) : SyncQueueDao {
        override suspend fun insert(item: SyncQueueEntity) {
            if (item.entityType == failForEntityType) {
                throw RuntimeException("Injected queue failure for $failForEntityType")
            }
        }

        override suspend fun coalescePendingTask(
            ownerLocalId: String,
            entityType: String,
            entityLocalId: String,
            operation: String,
            payloadJson: String,
            updatedAt: Long,
            reason: String?
        ): Int = 0

        override suspend fun getRunnableTasks(now: Long, limit: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun markProcessing(id: String, now: Long, reason: String?): Int = 0
        override suspend fun markDone(id: String, updatedAt: Long): Unit = Unit
        override suspend fun markRetryableFailure(
            id: String,
            error: String?,
            retryCount: Int,
            updatedAt: Long,
            nextAttemptAt: Long,
            reason: String?
        ): Unit = Unit

        override suspend fun markFatalFailure(
            id: String,
            error: String?,
            updatedAt: Long,
            reason: String?
        ): Unit = Unit

        override suspend fun markWaitingForAuth(
            id: String,
            reason: String?,
            updatedAt: Long,
            nextAttemptAt: Long
        ): Unit = Unit

        override fun observePendingCount(): kotlinx.coroutines.flow.Flow<Int> =
            kotlinx.coroutines.flow.flowOf(0)

        override suspend fun getPendingCount(): Int = 0
        override suspend fun countPending(): Int = 0
        override suspend fun countRetryable(): Int = 0
        override suspend fun countFatal(): Int = 0
        override suspend fun countWaitingForAuth(): Int = 0
        override suspend fun countBlockingDuplicate(
            ownerLocalId: String,
            entityType: String,
            entityLocalId: String,
            operation: String
        ): Int = 0

        override suspend fun countActiveTasksForEntity(
            ownerLocalId: String,
            entityType: String,
            entityLocalId: String
        ): Int = 0

        override suspend fun countActiveTasksForEntityAndOperation(
            ownerLocalId: String,
            entityType: String,
            entityLocalId: String,
            operation: String
        ): Int = 0

        override suspend fun countByStatus(status: String): Int = 0
        override suspend fun getTasksByStatus(status: String): List<SyncQueueEntity> = emptyList()
        override suspend fun resetStuckProcessingTasks(beforeTimestamp: Long, now: Long): Int = 0
        override suspend fun deleteDoneOlderThan(beforeTimestamp: Long): Int = 0
        override suspend fun deleteBusinessRecordTasks(): Int = 0
        override suspend fun getLastSyncAttemptAt(): Long? = null
        override suspend fun getLastSuccessfulSyncAt(): Long? = null
        override suspend fun getLastSyncFailureAt(): Long? = null
        override suspend fun getOldestPendingAt(): Long? = null
        override suspend fun getLastSyncError(): String? = null
    }
}
