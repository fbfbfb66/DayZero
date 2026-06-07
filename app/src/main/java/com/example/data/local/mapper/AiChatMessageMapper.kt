package com.example.data.local.mapper

import com.example.data.local.entity.AiChatMessageEntity
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatMessageType
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.ChoiceCard
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class AiChatMessageMapper {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val choiceCardAdapter = moshi.adapter(ChoiceCard::class.java)

    fun toDomain(entity: AiChatMessageEntity): AiChatMessage {
        val type = try { 
            ChatMessageType.valueOf(entity.messageType) 
        } catch (e: Exception) { 
            ChatMessageType.Text 
        }
        
        val choiceCard = if (type == ChatMessageType.ChoiceCard && entity.contentJson != null) {
            choiceCardAdapter.fromJson(entity.contentJson)
        } else {
            null
        }

        return AiChatMessage(
            id = entity.id,
            role = try { ChatRole.valueOf(entity.role) } catch (e: Exception) { ChatRole.Assistant },
            text = entity.text,
            createdAt = entity.createdAt,
            relatedDraftId = entity.relatedDraftId,
            messageType = type,
            choiceCard = choiceCard
        )
    }

    fun toEntity(domain: AiChatMessage): AiChatMessageEntity {
        val contentJson = domain.choiceCard?.let { choiceCardAdapter.toJson(it) }
        
        return AiChatMessageEntity(
            id = domain.id,
            role = domain.role.name,
            text = domain.text,
            createdAt = domain.createdAt,
            relatedDraftId = domain.relatedDraftId,
            messageType = domain.messageType.name,
            contentJson = contentJson
        )
    }
}
