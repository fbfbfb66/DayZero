package com.goings.dayzero.data.local.mapper

import com.goings.dayzero.data.local.entity.ConversationEntity
import com.goings.dayzero.domain.model.ai.Conversation
import java.time.LocalDate

class ConversationEntityMapper {
    fun toDomain(entity: ConversationEntity): Conversation {
        return Conversation(
            id = entity.id,
            conversationDate = LocalDate.parse(entity.conversationDate),
            title = entity.title,
            lastMessagePreview = entity.lastMessagePreview,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            lastActivityAt = entity.lastActivityAt,
            deletedAt = entity.deletedAt
        )
    }

    fun toEntity(domain: Conversation): ConversationEntity {
        return ConversationEntity(
            id = domain.id,
            conversationDate = domain.conversationDate.toString(),
            title = domain.title,
            lastMessagePreview = domain.lastMessagePreview,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
            lastActivityAt = domain.lastActivityAt,
            deletedAt = domain.deletedAt
        )
    }
}
