package com.example.data.remote.mapper

import com.example.data.remote.dto.chat.RemoteAiChatMessageDto
import com.example.data.remote.dto.chat.RemoteConversationDto
import com.example.domain.model.sync.ChatSyncConversationSnapshot
import com.example.domain.model.sync.ChatSyncMessageSnapshot
import java.time.Instant
import java.time.LocalDate

class RemoteChatSyncMapper {
    fun toRemoteConversation(snapshot: ChatSyncConversationSnapshot): RemoteConversationDto {
        return RemoteConversationDto(
            id = snapshot.id,
            conversationDate = snapshot.conversationDate.toString(),
            title = snapshot.title,
            lastMessagePreview = snapshot.lastMessagePreview,
            createdAt = snapshot.createdAtMillis.toIsoInstant(),
            updatedAt = snapshot.updatedAtMillis.toIsoInstant(),
            lastActivityAt = snapshot.lastActivityAtMillis.toIsoInstant(),
            deletedAt = snapshot.deletedAtMillis?.toIsoInstant(),
            schemaVersion = snapshot.schemaVersion
        )
    }

    fun toConversationSnapshot(dto: RemoteConversationDto): ChatSyncConversationSnapshot {
        return ChatSyncConversationSnapshot(
            id = dto.id,
            conversationDate = LocalDate.parse(dto.conversationDate),
            title = dto.title,
            lastMessagePreview = dto.lastMessagePreview,
            createdAtMillis = dto.createdAt.toEpochMillis(),
            updatedAtMillis = dto.updatedAt.toEpochMillis(),
            lastActivityAtMillis = dto.lastActivityAt.toEpochMillis(),
            deletedAtMillis = dto.deletedAt?.toEpochMillis(),
            schemaVersion = dto.schemaVersion
        )
    }

    fun toRemoteMessage(snapshot: ChatSyncMessageSnapshot): RemoteAiChatMessageDto {
        return RemoteAiChatMessageDto(
            id = snapshot.id,
            conversationId = snapshot.conversationId,
            role = snapshot.role,
            messageType = snapshot.messageType,
            text = snapshot.text,
            contentJson = snapshot.contentJson,
            assistantCardsJson = snapshot.assistantCardsJson,
            suggestedRepliesJson = snapshot.suggestedRepliesJson,
            createdAt = snapshot.createdAtMillis.toIsoInstant(),
            updatedAt = snapshot.updatedAtMillis.toIsoInstant(),
            deletedAt = snapshot.deletedAtMillis?.toIsoInstant(),
            schemaVersion = snapshot.schemaVersion
        )
    }

    fun toMessageSnapshot(dto: RemoteAiChatMessageDto): ChatSyncMessageSnapshot {
        return ChatSyncMessageSnapshot(
            id = dto.id,
            conversationId = dto.conversationId,
            role = dto.role,
            text = dto.text,
            createdAtMillis = dto.createdAt.toEpochMillis(),
            updatedAtMillis = dto.updatedAt.toEpochMillis(),
            deletedAtMillis = dto.deletedAt?.toEpochMillis(),
            messageType = dto.messageType,
            contentJson = dto.contentJson,
            assistantCardsJson = dto.assistantCardsJson,
            suggestedRepliesJson = dto.suggestedRepliesJson,
            schemaVersion = dto.schemaVersion
        )
    }

    private fun Long.toIsoInstant(): String = Instant.ofEpochMilli(this).toString()

    private fun String.toEpochMillis(): Long = Instant.parse(this).toEpochMilli()
}

