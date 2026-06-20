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
    @Query("SELECT * FROM ai_chat_messages ORDER BY createdAt ASC")
    fun observeAllMessages(): Flow<List<AiChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AiChatMessageEntity)

    @Update
    suspend fun updateMessage(message: AiChatMessageEntity)

    @Query("DELETE FROM ai_chat_messages")
    suspend fun deleteAllMessages()
}
