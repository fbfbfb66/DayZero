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
    @Query("SELECT * FROM ai_chat_messages ORDER BY createdAt ASC")
    fun observeAllMessages(): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessagesByConversationId(conversationId: String): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessagesByConversationId(conversationId: String): List<AiChatMessageEntity>

    @Query("SELECT * FROM ai_chat_messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessagesByConversationId(conversationId: String, limit: Int): List<AiChatMessageEntity>

    @Query("SELECT * FROM ai_chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): AiChatMessageEntity?

    @Query("SELECT * FROM ai_chat_messages WHERE assistantCardsJson IS NOT NULL ORDER BY createdAt DESC, id DESC")
    suspend fun getMessagesWithCards(): List<AiChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AiChatMessageEntity)

    @Update
    suspend fun updateMessage(message: AiChatMessageEntity)

    @Query("DELETE FROM ai_chat_messages")
    suspend fun deleteAllMessages()
}
