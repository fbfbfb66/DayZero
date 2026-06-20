package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE deletedAt IS NULL ORDER BY createdAt ASC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE deletedAt IS NULL ORDER BY lastActivityAt DESC, createdAt DESC")
    fun observeConversationsByLastActivity(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE deletedAt IS NULL ORDER BY lastActivityAt DESC, createdAt DESC LIMIT 1")
    suspend fun getLatestActiveConversation(): ConversationEntity?

    @Query(
        """
        UPDATE conversations
        SET title = :title,
            lastMessagePreview = :lastMessagePreview,
            lastActivityAt = :lastActivityAt,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateConversationSummary(
        id: String,
        title: String,
        lastMessagePreview: String,
        lastActivityAt: Long,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE conversations
        SET deletedAt = :deletedAt,
            updatedAt = :deletedAt
        WHERE id = :id
        """
    )
    suspend fun softDeleteConversation(id: String, deletedAt: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}
