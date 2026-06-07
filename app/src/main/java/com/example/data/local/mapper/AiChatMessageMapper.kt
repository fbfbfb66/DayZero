package com.example.data.local.mapper

import com.example.data.local.entity.AiChatMessageEntity
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatActionType
import com.example.domain.model.ai.ChatRole

class AiChatMessageMapper {
    fun toDomain(entity: AiChatMessageEntity): AiChatMessage {
        return AiChatMessage(
            id = entity.id,
            role = try { ChatRole.valueOf(entity.role) } catch (e: Exception) { ChatRole.Assistant },
            text = entity.text,
            createdAt = entity.createdAt,
            relatedDraftId = entity.relatedDraftId,
            actionType = entity.actionType?.let { 
                try { ChatActionType.valueOf(it) } catch (e: Exception) { null }
            }
        )
    }

    fun toEntity(domain: AiChatMessage): AiChatMessageEntity {
        return AiChatMessageEntity(
            id = domain.id,
            role = domain.role.name,
            text = domain.text,
            createdAt = domain.createdAt,
            relatedDraftId = domain.relatedDraftId,
            actionType = domain.actionType?.name
        )
    }
}
