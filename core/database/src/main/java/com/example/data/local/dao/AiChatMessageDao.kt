package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.AiChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiChatMessageDao {
    // Compatibility path for the current single-stream chat UI. New history UI should query by conversationId.
    @Query("SELECT * FROM ai_chat_messages WHERE deletedAt IS NULL ORDER BY createdAt ASC")
    fun observeAllMessages(): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_messages WHERE conversationId = :conversationId AND deletedAt IS NULL ORDER BY createdAt ASC, id ASC")
    fun observeMessagesByConversationId(conversationId: String): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_messages WHERE conversationId = :conversationId AND deletedAt IS NULL ORDER BY createdAt ASC, id ASC")
    suspend fun getMessagesByConversationId(conversationId: String): List<AiChatMessageEntity>

    @Query("SELECT * FROM ai_chat_messages WHERE conversationId = :conversationId AND deletedAt IS NULL ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessagesByConversationId(conversationId: String, limit: Int): List<AiChatMessageEntity>

    @Query("SELECT * FROM ai_chat_messages WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getMessageById(id: String): AiChatMessageEntity?

    @Query("SELECT * FROM ai_chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageByIdIncludingDeleted(id: String): AiChatMessageEntity?

    @Query("SELECT * FROM ai_chat_messages WHERE assistantCardsJson IS NOT NULL AND deletedAt IS NULL ORDER BY createdAt DESC, id DESC")
    suspend fun getMessagesWithCards(): List<AiChatMessageEntity>

    @Query(
        """
        SELECT * FROM ai_chat_messages
        WHERE createdAt > :afterCreatedAt
           OR (createdAt = :afterCreatedAt AND id > :afterId)
        ORDER BY createdAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getMessagesForChatBackfill(
        afterCreatedAt: Long,
        afterId: String,
        limit: Int
    ): List<AiChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AiChatMessageEntity)

    @Query(
        """
        UPDATE ai_chat_messages
        SET text = :text,
            messageType = :messageType,
            contentJson = :contentJson,
            assistantCardsJson = :assistantCardsJson,
            suggestedRepliesJson = :suggestedRepliesJson,
            updatedAt = :updatedAt
        WHERE id = :id
          AND deletedAt IS NULL
        """
    )
    suspend fun updateMessageContentIfActive(
        id: String,
        text: String,
        messageType: String,
        contentJson: String?,
        assistantCardsJson: String?,
        suggestedRepliesJson: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE ai_chat_messages
        SET text = :text,
            contentJson = :contentJson,
            assistantCardsJson = :assistantCardsJson,
            suggestedRepliesJson = :suggestedRepliesJson,
            updatedAt = :updatedAt,
            deletedAt = :deletedAt
        WHERE id = :id
        """
    )
    suspend fun applyRemoteMutableFields(
        id: String,
        text: String,
        contentJson: String?,
        assistantCardsJson: String?,
        suggestedRepliesJson: String?,
        updatedAt: Long,
        deletedAt: Long?
    ): Int

    @Query("DELETE FROM ai_chat_messages")
    suspend fun deleteAllMessages()
}
