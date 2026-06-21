package com.example.data.local.mapper

import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.remote.dto.assistant.AiChatCardDto
import com.example.data.remote.mapper.AiAssistantRemoteMapper
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatMessageType
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.ChoiceCard
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class AiChatMessageMapper {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val choiceCardAdapter = moshi.adapter(ChoiceCard::class.java)
    
    private val assistantCardListType = Types.newParameterizedType(List::class.java, AiChatCardDto::class.java)
    private val assistantCardsAdapter = moshi.adapter<List<AiChatCardDto>>(assistantCardListType)
    
    private val stringListType = Types.newParameterizedType(List::class.java, String::class.java)
    private val stringListAdapter = moshi.adapter<List<String>>(stringListType)

    private val assistantMapper = AiAssistantRemoteMapper()

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

        val assistantCards = entity.assistantCardsJson?.let { 
            assistantCardsAdapter.fromJson(it)?.mapNotNull { dto -> assistantMapper.toCardDomain(dto) }
        } ?: emptyList()

        val suggestedReplies = entity.suggestedRepliesJson?.let {
            stringListAdapter.fromJson(it)
        } ?: emptyList()

        return AiChatMessage(
            id = entity.id,
            conversationId = entity.conversationId,
            role = try { ChatRole.valueOf(entity.role) } catch (e: Exception) { ChatRole.Assistant },
            text = entity.text,
            createdAt = entity.createdAt,
            relatedDraftId = entity.relatedDraftId,
            messageType = type,
            choiceCard = choiceCard,
            assistantCards = assistantCards,
            suggestedReplies = suggestedReplies,
            updatedAt = entity.updatedAt,
            deletedAt = entity.deletedAt
        )
    }

    fun toEntity(domain: AiChatMessage, conversationId: String = requireNotNull(domain.conversationId)): AiChatMessageEntity {
        val contentJson = domain.choiceCard?.let { choiceCardAdapter.toJson(it) }
        
        val assistantCardsJson = if (domain.assistantCards.isNotEmpty()) {
            val dtos = domain.assistantCards.mapNotNull { assistantMapper.toDto(it) }
            assistantCardsAdapter.toJson(dtos)
        } else null
        
        val suggestedRepliesJson = if (domain.suggestedReplies.isNotEmpty()) {
            stringListAdapter.toJson(domain.suggestedReplies)
        } else null
        
        return AiChatMessageEntity(
            id = domain.id,
            conversationId = conversationId,
            role = domain.role.name,
            text = domain.text,
            createdAt = domain.createdAt,
            relatedDraftId = domain.relatedDraftId,
            messageType = domain.messageType.name,
            contentJson = contentJson,
            assistantCardsJson = assistantCardsJson,
            suggestedRepliesJson = suggestedRepliesJson,
            updatedAt = domain.updatedAt,
            deletedAt = domain.deletedAt
        )
    }
}
