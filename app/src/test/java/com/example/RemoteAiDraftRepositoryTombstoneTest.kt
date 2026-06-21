package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.dto.AiDraftRequestDto
import com.example.data.remote.dto.AiDraftResponseDto
import com.example.data.remote.dto.AiSummaryRequestDto
import com.example.data.remote.dto.AiSummaryResponseDto
import com.example.data.remote.dto.IntentClassificationResultDto
import com.example.data.remote.dto.IntentClassifierRequestDto
import com.example.data.repository.RemoteAiDraftRepository
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class RemoteAiDraftRepositoryTombstoneTest {

    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private lateinit var repository: RemoteAiDraftRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RemoteAiDraftRepository(
            apiService = FakeAiDraftApiService(),
            database = database,
            syncQueueDao = database.syncQueueDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private class FakeAiDraftApiService : AiDraftApiService {
        override suspend fun generateDraft(request: AiDraftRequestDto): AiDraftResponseDto = error("unused")
        override suspend fun generateDailySummary(request: AiSummaryRequestDto): AiSummaryResponseDto = error("unused")
        override suspend fun classifyUserIntent(request: IntentClassifierRequestDto): IntentClassificationResultDto = error("unused")
        override suspend fun sendAssistantTurnV2WithResponse(
            request: com.example.data.remote.dto.assistant.AiAssistantRequestDto
        ): Response<com.example.data.remote.dto.assistant.AssistantTurnV2ResponseDto> = error("unused")
    }

    @Test
    fun testStreamingFinalDoesNotResurrectTombstone() = runBlocking {
        // 1. Setup conversation and active assistant placeholder
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = "conv_1",
            conversationDate = "2026-06-21",
            title = "Test Conversation",
            lastMessagePreview = "Initial user prompt",
            createdAt = now - 1000,
            updatedAt = now - 1000,
            lastActivityAt = now - 1000,
            deletedAt = null
        )
        database.conversationDao().insertConversation(conversation)

        val placeholder = AiChatMessageEntity(
            id = "msg_1",
            conversationId = "conv_1",
            role = "Assistant",
            text = "",
            createdAt = now - 500,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = now - 500,
            deletedAt = null
        )
        database.aiChatMessageDao().insertMessage(placeholder)

        // Verify active placeholder exists
        val initialMessage = database.aiChatMessageDao().getMessageById("msg_1")
        assertNotNull(initialMessage)
        assertNull(initialMessage?.deletedAt)

        // 2. Simulate remote tombstone via applyRemoteMutableFields / remote merge DAO
        val deletedAtTime = now - 300
        val applyRows = database.aiChatMessageDao().applyRemoteMutableFields(
            id = "msg_1",
            text = "",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = now - 300,
            deletedAt = deletedAtTime
        )
        assertEquals(1, applyRows)

        // Verify deletedAt is set
        val tombstonedMessage = database.aiChatMessageDao().getMessageByIdIncludingDeleted("msg_1")
        assertNotNull(tombstonedMessage)
        assertEquals(deletedAtTime, tombstonedMessage?.deletedAt)

        // Clear existing sync queue tasks from setup to isolate assertions
        assertEquals(0, database.syncQueueDao().countPending())

        // 3. Submit final streaming update via repository updateChatMessage
        val finalMessageDomain = AiChatMessage(
            id = "msg_1",
            conversationId = "conv_1",
            role = ChatRole.Assistant,
            text = "Final Streaming Reply",
            createdAt = now - 500
        )
        repository.updateChatMessage(finalMessageDomain)

        // 4. Assertions
        val finalDbMessage = database.aiChatMessageDao().getMessageByIdIncludingDeleted("msg_1")
        assertNotNull(finalDbMessage)
        assertEquals(deletedAtTime, finalDbMessage?.deletedAt)
        
        // - text not updated (remains "")
        assertEquals("", finalDbMessage?.text)

        // - active queries cannot retrieve this message
        val activeMessage = database.aiChatMessageDao().getMessageById("msg_1")
        assertNull(activeMessage)

        val activeList = database.aiChatMessageDao().observeMessagesByConversationId("conv_1").first()
        assertTrue(activeList.none { it.id == "msg_1" })

        // - no active sync queue tasks generated for message msg_1
        val pendingTasks = database.syncQueueDao().getTasksByStatus("PENDING")
        assertTrue("Queue must not contain updates for the tombstoned message", 
            pendingTasks.none { it.entityLocalId == "msg_1" })

        // - conversation summary not updated
        val finalConversation = database.conversationDao().getConversationById("conv_1")
        assertEquals("Initial user prompt", finalConversation?.lastMessagePreview)
    }

    @Test
    fun testFallbackFinalDoesNotResurrectTombstone() = runBlocking {
        // 1. Setup conversation and active assistant placeholder
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = "conv_1",
            conversationDate = "2026-06-21",
            title = "Test Conversation",
            lastMessagePreview = "Initial user prompt",
            createdAt = now - 1000,
            updatedAt = now - 1000,
            lastActivityAt = now - 1000,
            deletedAt = null
        )
        database.conversationDao().insertConversation(conversation)

        val placeholder = AiChatMessageEntity(
            id = "msg_1",
            conversationId = "conv_1",
            role = "Assistant",
            text = "",
            createdAt = now - 500,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = now - 500,
            deletedAt = null
        )
        database.aiChatMessageDao().insertMessage(placeholder)

        // 2. Remote tombstone
        val deletedAtTime = now - 300
        database.aiChatMessageDao().applyRemoteMutableFields(
            id = "msg_1",
            text = "",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = now - 300,
            deletedAt = deletedAtTime
        )

        // 3. Fallback mutation
        val fallbackMessageDomain = AiChatMessage(
            id = "msg_1",
            conversationId = "conv_1",
            role = ChatRole.Assistant,
            text = "Fallback assistant response",
            createdAt = now - 500
        )
        repository.updateChatMessage(fallbackMessageDomain)

        // 4. Assertions
        val finalDbMessage = database.aiChatMessageDao().getMessageByIdIncludingDeleted("msg_1")
        assertNotNull(finalDbMessage)
        assertEquals(deletedAtTime, finalDbMessage?.deletedAt)
        assertEquals("", finalDbMessage?.text)

        val activeMessage = database.aiChatMessageDao().getMessageById("msg_1")
        assertNull(activeMessage)

        val pendingTasks = database.syncQueueDao().getTasksByStatus("PENDING")
        assertTrue(pendingTasks.none { it.entityLocalId == "msg_1" })

        val finalConversation = database.conversationDao().getConversationById("conv_1")
        assertEquals("Initial user prompt", finalConversation?.lastMessagePreview)
    }

    @Test
    fun testCardUpdateDoesNotResurrectTombstone() = runBlocking {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = "conv_1",
            conversationDate = "2026-06-21",
            title = "Test Conversation",
            lastMessagePreview = "Initial user prompt",
            createdAt = now - 1000,
            updatedAt = now - 1000,
            lastActivityAt = now - 1000,
            deletedAt = null
        )
        database.conversationDao().insertConversation(conversation)

        val originalCard = ShowConfirmCardPayload(
            id = "card_1",
            confirmType = "food",
            title = "Confirm Title",
            message = "Confirm Msg",
            originalText = "lunch egg",
            mealType = null,
            items = emptyList(),
            date = null,
            weightKg = null,
            totalCalories = null,
            meals = emptyList(),
            buttons = emptyList(),
            resolved = false,
            state = "pending"
        )
        val activeMsgDomain = AiChatMessage(
            id = "msg_1",
            conversationId = "conv_1",
            role = ChatRole.Assistant,
            text = "",
            createdAt = now - 500,
            assistantCards = listOf(originalCard)
        )
        
        val mapper = com.example.data.local.mapper.AiChatMessageMapper()
        database.aiChatMessageDao().insertMessage(mapper.toEntity(activeMsgDomain))

        // Verify card can be found initially
        val initialFound = repository.findMessageByAssistantCardId("card_1")
        assertNotNull(initialFound)
        assertEquals("msg_1", initialFound?.id)

        // Remote tombstone
        val deletedAtTime = now - 300
        database.aiChatMessageDao().applyRemoteMutableFields(
            id = "msg_1",
            text = "",
            contentJson = null,
            assistantCardsJson = mapper.toEntity(activeMsgDomain).assistantCardsJson,
            suggestedRepliesJson = null,
            updatedAt = now - 300,
            deletedAt = deletedAtTime
        )

        // Verify finding card returns null now
        val tombstonedFound = repository.findMessageByAssistantCardId("card_1")
        assertNull("Tombstoned message cards must not be searchable", tombstonedFound)

        // Clear existing sync queue tasks
        assertEquals(0, database.syncQueueDao().countPending())

        // Try updating card on the previously retrieved message instance
        val updatedCard = originalCard.copy(
            state = "confirmed",
            resolved = true,
            weightKg = 75.0
        )
        val updatedMsgDomain = initialFound!!.copy(assistantCards = listOf(updatedCard))
        repository.updateChatMessage(updatedMsgDomain)

        // Assertions
        val finalDbMessage = database.aiChatMessageDao().getMessageByIdIncludingDeleted("msg_1")
        assertNotNull(finalDbMessage)
        assertEquals(deletedAtTime, finalDbMessage?.deletedAt)
        
        // Assert card state did NOT change in database (must stay 'pending', resolved = false)
        val finalDomain = mapper.toDomain(finalDbMessage!!)
        val finalCard = finalDomain.assistantCards.single() as ShowConfirmCardPayload
        assertEquals("pending", finalCard.state)
        assertEquals(false, finalCard.resolved)
        assertNull(finalCard.weightKg)

        // Assert not enqueued
        val pendingTasks = database.syncQueueDao().getTasksByStatus("PENDING")
        assertTrue(pendingTasks.none { it.entityLocalId == "msg_1" })

        // Assert conversation summary not updated
        val finalConversation = database.conversationDao().getConversationById("conv_1")
        assertEquals("Initial user prompt", finalConversation?.lastMessagePreview)
    }

    @Test
    fun testActiveMessageNormalUpdate() = runBlocking {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = "conv_1",
            conversationDate = "2026-06-21",
            title = "Test Conversation",
            lastMessagePreview = "Initial user prompt",
            createdAt = now - 1000,
            updatedAt = now - 1000,
            lastActivityAt = now - 1000,
            deletedAt = null
        )
        database.conversationDao().insertConversation(conversation)

        val activeMsgDomain = AiChatMessage(
            id = "msg_1",
            conversationId = "conv_1",
            role = ChatRole.Assistant,
            text = "Initial streaming text",
            createdAt = now - 500
        )
        val mapper = com.example.data.local.mapper.AiChatMessageMapper()
        database.aiChatMessageDao().insertMessage(mapper.toEntity(activeMsgDomain))

        // Clear sync queue
        assertEquals(0, database.syncQueueDao().countPending())

        // Update via repository
        val updatedMsgDomain = activeMsgDomain.copy(text = "Final active reply")
        repository.updateChatMessage(updatedMsgDomain)

        // Assertions
        val finalDbMessage = database.aiChatMessageDao().getMessageById("msg_1")
        assertNotNull(finalDbMessage)
        assertEquals("Final active reply", finalDbMessage?.text)
        assertTrue("updatedAt must be updated to current time", finalDbMessage!!.updatedAt > now - 500)
        assertNull(finalDbMessage.deletedAt)

        // Queue contains the message upsert task
        val pendingTasks = database.syncQueueDao().getTasksByStatus("PENDING")
        val messageTask = pendingTasks.find { it.entityLocalId == "msg_1" && it.entityType == "ai_chat_message" }
        assertNotNull("Should enqueue message upsert for active message update", messageTask)
        assertTrue(messageTask!!.payloadJson.contains("Final active reply"))

        // Conversation summary updated
        val finalConversation = database.conversationDao().getConversationById("conv_1")
        assertEquals("Final active reply", finalConversation?.lastMessagePreview)
    }
}
