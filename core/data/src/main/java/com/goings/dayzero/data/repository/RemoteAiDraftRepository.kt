package com.goings.dayzero.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.ConversationEntity
import com.goings.dayzero.data.local.mapper.AiChatMessageMapper
import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.identity.StaticLocalIdentityProvider
import com.goings.dayzero.data.remote.api.AiDraftApiService
import com.goings.dayzero.data.remote.mapper.AiDraftRemoteMapper
import com.goings.dayzero.data.sync.chat.ChatSyncQueueWriter
import com.goings.dayzero.domain.identity.CurrentIdentityProvider
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.AiDraftRequest
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.CheckinDraft
import com.goings.dayzero.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.goings.dayzero.domain.repository.AiDraftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class RemoteAiDraftRepository(
    private val apiService: AiDraftApiService,
    private val database: DayZeroDatabase,
    syncQueueDao: SyncQueueDao? = null,
    private val identityProvider: CurrentIdentityProvider = StaticLocalIdentityProvider()
) : AiDraftRepository {

    private val mapper = AiDraftRemoteMapper()
    private val chatMapper = AiChatMessageMapper()
    private val chatDao = database.aiChatMessageDao()
    private val conversationDao = database.conversationDao()
    private val chatSyncQueueWriter = syncQueueDao?.let { ChatSyncQueueWriter(it) }

    override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft {
        val requestDto = mapper.toRequestDto(request)
        val responseDto = apiService.generateDraft(requestDto)
        return mapper.toDomain(responseDto)
    }

    private val streamingStates = kotlinx.coroutines.flow.MutableStateFlow<Map<String, StreamingState>>(emptyMap())
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

    data class StreamingState(
        val conversationId: String,
        val messageId: String,
        val text: String,
        val isStreaming: Boolean
    )

    override fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean) {
        streamingStates.value = streamingStates.value + (conversationId to StreamingState(conversationId, messageId, text, isStreaming))
    }

    override fun clearStreamingState(conversationId: String) {
        val current = streamingStates.value[conversationId]
        if (current != null) {
            streamingStates.value = streamingStates.value + (conversationId to current.copy(isStreaming = false))
        }
    }

    private fun clearStreamingStateActual(conversationId: String) {
        streamingStates.value = streamingStates.value - conversationId
    }

    override fun observeChatMessages(): Flow<List<AiChatMessage>> {
        return kotlinx.coroutines.flow.combine(chatDao.observeAllMessages(), streamingStates) { entities, states ->
            entities.map { chatMapper.toDomain(it) }.map { msg ->
                val convId = msg.conversationId
                val state = if (convId != null) states[convId] else null
                if (state != null && msg.id == state.messageId && convId != null) {
                    if (msg.text.isNotBlank() || msg.assistantCards.isNotEmpty()) {
                        if (!state.isStreaming) {
                            repositoryScope.launch {
                                clearStreamingStateActual(convId)
                            }
                        }
                        msg
                    } else {
                        msg.copy(text = state.text)
                    }
                } else {
                    msg
                }
            }
        }
    }

    override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> {
        return kotlinx.coroutines.flow.combine(chatDao.observeMessagesByConversationId(conversationId), streamingStates) { entities, states ->
            val state = states[conversationId]
            entities.map { chatMapper.toDomain(it) }.map { msg ->
                if (state != null && msg.id == state.messageId) {
                    if (msg.text.isNotBlank() || msg.assistantCards.isNotEmpty()) {
                        if (!state.isStreaming) {
                            repositoryScope.launch {
                                clearStreamingStateActual(conversationId)
                            }
                        }
                        msg
                    } else {
                        msg.copy(text = state.text)
                    }
                } else {
                    msg
                }
            }
        }
    }

    override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val conversationId = UUID.randomUUID().toString()
        val date = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val firstMessage = AiChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = ChatRole.User,
            text = trimmed,
            createdAt = now
        )
        val conversation = ConversationEntity(
            id = conversationId,
            conversationDate = date,
            title = trimmed.normalizedPreviewText().limitPreview().ifBlank { neutralTitle(date) },
            lastMessagePreview = firstMessage.previewText(),
            createdAt = now,
            updatedAt = now,
            lastActivityAt = now,
            deletedAt = null
        )

        val identity = identityProvider.currentIdentity()
        database.withTransaction {
            conversationDao.insertConversation(conversation)
            val firstMessageEntity = chatMapper.toEntity(firstMessage, conversationId)
            chatDao.insertMessage(firstMessageEntity)
            chatSyncQueueWriter?.enqueueConversationUpsert(conversation, identity)
            chatSyncQueueWriter?.enqueueMessageUpsert(firstMessageEntity, identity)
        }
        return conversationId
    }

    override suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage> {
        return chatDao.getRecentMessagesByConversationId(conversationId, limit)
            .asReversed()
            .map { chatMapper.toDomain(it) }
    }

    override suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage? {
        return chatDao.getMessagesWithCards()
            .firstNotNullOfOrNull { entity ->
                val message = chatMapper.toDomain(entity)
                message.takeIf {
                    it.assistantCards.any { card ->
                        card.id == cardId ||
                            (card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == cardId)
                    }
                }
            }
    }

    override suspend fun getChatMessageById(messageId: String): AiChatMessage? {
        return chatDao.getMessageById(messageId)?.let { chatMapper.toDomain(it) }
    }

    override suspend fun insertChatMessage(message: AiChatMessage) {
        val conversationId = message.conversationId ?: ensureCurrentConversation(message).id
        insertChatMessage(conversationId, message)
    }

    override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {
        val messageWithConversation = message.copy(conversationId = conversationId)
        val messageEntity = chatMapper.toEntity(messageWithConversation, conversationId)
        val identity = identityProvider.currentIdentity()
        val updatedAt = System.currentTimeMillis()
        database.withTransaction {
            chatDao.insertMessage(messageEntity)
            refreshConversationSummaryInTransaction(conversationId, messageWithConversation)
            conversationDao.getConversationById(conversationId)?.let { conversation ->
                chatSyncQueueWriter?.enqueueConversationUpsert(conversation, identity)
            }
            chatSyncQueueWriter?.enqueueMessageUpsert(messageEntity, identity)
        }
    }

    override suspend fun updateChatMessage(message: AiChatMessage) {
        val conversationId = message.conversationId
            ?: chatDao.getMessageById(message.id)?.conversationId
            ?: ensureCurrentConversation(message).id
        val messageWithConversation = message.copy(conversationId = conversationId)
        var messageEntity = chatMapper.toEntity(messageWithConversation, conversationId)
        val persistedBeforeUpdate = chatDao.getMessageById(message.id)
        val mergedCardsJson = mergeGeneratedCardsWithPersistedUnknowns(
            generatedRaw = messageEntity.assistantCardsJson,
            persistedRaw = persistedBeforeUpdate?.assistantCardsJson
        )
        logCardMerge(
            messageId = message.id,
            generatedRaw = messageEntity.assistantCardsJson,
            persistedRaw = persistedBeforeUpdate?.assistantCardsJson,
            mergedRaw = mergedCardsJson
        )
        messageEntity = messageEntity.copy(assistantCardsJson = mergedCardsJson)
        val identity = identityProvider.currentIdentity()
        val updatedAt = System.currentTimeMillis()
        database.withTransaction {
            val rowsAffected = chatDao.updateMessageContentIfActive(
                id = messageEntity.id,
                text = messageEntity.text,
                messageType = messageEntity.messageType,
                contentJson = messageEntity.contentJson,
                assistantCardsJson = messageEntity.assistantCardsJson,
                suggestedRepliesJson = messageEntity.suggestedRepliesJson,
                updatedAt = updatedAt
            )
            if (rowsAffected > 0) {
                val updatedEntity = messageEntity.copy(updatedAt = updatedAt)
                refreshConversationSummaryInTransaction(conversationId, messageWithConversation)
                conversationDao.getConversationById(conversationId)?.let { conversation ->
                    chatSyncQueueWriter?.enqueueConversationUpsert(conversation, identity)
                }
                chatSyncQueueWriter?.enqueueMessageUpsert(updatedEntity, identity)
            }
        }
    }

    private fun mergeGeneratedCardsWithPersistedUnknowns(
        generatedRaw: String?,
        persistedRaw: String?
    ): String? {
        if (generatedRaw == null) return persistedRaw
        if (persistedRaw == null) return generatedRaw
        return runCatching {
            val generated = JSONArray(generatedRaw)
            val persisted = JSONArray(persistedRaw)
            val persistedById = (0 until persisted.length()).mapNotNull { index ->
                persisted.optJSONObject(index)?.let { card ->
                    card.optString("id").takeIf(String::isNotBlank)?.let { it to card }
                }
            }.toMap()
            for (index in 0 until generated.length()) {
                val card = generated.optJSONObject(index) ?: continue
                val id = card.optString("id")
                persistedById[id]?.let { mergeMissingJson(card, it) }
            }
            generated.toString()
        }.getOrElse { generatedRaw }
    }

    private fun logCardMerge(
        messageId: String,
        generatedRaw: String?,
        persistedRaw: String?,
        mergedRaw: String?
    ) {
        Log.i(
            "RemoteAiDraftRepository",
            "CARD_PERSIST_MERGE | messageId=${messageId.take(8)} " +
                "persistedTypes=${cardTypeOrder(persistedRaw)} " +
                "generatedTypes=${cardTypeOrder(generatedRaw)} " +
                "mergedTypes=${cardTypeOrder(mergedRaw)}"
        )
    }

    private fun cardTypeOrder(raw: String?): String {
        if (raw.isNullOrBlank()) return "none"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index -> array.optJSONObject(index)?.optString("type")?.takeIf(String::isNotBlank) }
                .joinToString(">")
                .ifBlank { "none" }
        }.getOrDefault("invalid")
    }

    private fun mergeMissingJson(primary: JSONObject, fallback: JSONObject) {
        fallback.keys().forEach { key ->
            if (!primary.has(key)) {
                primary.put(key, cloneJsonValue(fallback.get(key)))
            } else {
                mergeNestedJson(primary.get(key), fallback.get(key))
            }
        }
    }

    private fun mergeNestedJson(primary: Any, fallback: Any) {
        when {
            primary is JSONObject && fallback is JSONObject -> mergeMissingJson(primary, fallback)
            primary is JSONArray && fallback is JSONArray -> {
                for (index in 0 until minOf(primary.length(), fallback.length())) {
                    mergeNestedJson(primary.get(index), fallback.get(index))
                }
            }
        }
    }

    private fun cloneJsonValue(value: Any): Any = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is JSONArray -> JSONArray(value.toString())
        else -> value
    }

    override suspend fun clearChatMessages() {
        database.withTransaction {
            chatDao.deleteAllMessages()
            conversationDao.deleteAllConversations()
        }
    }

    private suspend fun ensureCurrentConversation(message: AiChatMessage): ConversationEntity {
        val latest = conversationDao.getLatestActiveConversation()
        if (latest != null) return latest

        val date = Instant.ofEpochMilli(message.createdAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val title = if (message.role == ChatRole.User) {
            message.text.normalizedPreviewText().limitPreview()
        } else {
            neutralTitle(date)
        }
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            conversationDate = date,
            title = title.ifBlank { neutralTitle(date) },
            lastMessagePreview = message.previewText(),
            createdAt = message.createdAt,
            updatedAt = message.createdAt,
            lastActivityAt = message.createdAt,
            deletedAt = null
        )
        conversationDao.insertConversation(conversation)
        return conversation
    }

    private suspend fun refreshConversationSummaryInTransaction(conversationId: String, message: AiChatMessage) {
        val conversation = conversationDao.getConversationById(conversationId) ?: return
        val title = conversation.title.ifBlank {
            if (message.role == ChatRole.User) {
                message.text.normalizedPreviewText().limitPreview().ifBlank { neutralTitle(conversation.conversationDate) }
            } else {
                neutralTitle(conversation.conversationDate)
            }
        }
        val preview = message.previewText().ifBlank { conversation.lastMessagePreview }
        conversationDao.updateConversationSummary(
            id = conversationId,
            title = title,
            lastMessagePreview = preview,
            lastActivityAt = message.createdAt,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun AiChatMessage.previewText(): String {
        return text.normalizedPreviewText().limitPreview().ifBlank {
            if (assistantCards.isNotEmpty() || choiceCard != null) {
                "Card message"
            } else {
                ""
            }
        }
    }

    private fun String.normalizedPreviewText(): String {
        return trim().replace(Regex("\\s+"), " ")
    }

    private fun String.limitPreview(maxLength: Int = 32): String {
        return if (length <= maxLength) this else take(maxLength).trimEnd() + "..."
    }

    private fun neutralTitle(date: String): String {
        val localDate = java.time.LocalDate.parse(date)
        return DateTimeFormatter.ofPattern("M月d日的对话").format(localDate)
    }
}
