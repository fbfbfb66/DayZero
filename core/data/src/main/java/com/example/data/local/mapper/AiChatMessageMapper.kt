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
import org.json.JSONArray
import org.json.JSONObject

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

        val contentJsonRaw = entity.contentJson
        val contentJson = contentJsonRaw?.let { safeParseContentJson(it) }

        val choiceCard = if (type == ChatMessageType.ChoiceCard && contentJsonRaw != null) {
            choiceCardAdapter.fromJson(contentJsonRaw)
        } else {
            null
        }

        val sourceMediaIds = contentJson?.optJSONObject(MEDIA_KEY)?.let { media ->
            val array = media.optJSONArray(SOURCE_MEDIA_IDS_KEY)
            if (array == null) {
                emptyList()
            } else {
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }
        } ?: emptyList()

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
            sourceMediaIds = sourceMediaIds,
            contentJson = contentJsonRaw,
            updatedAt = entity.updatedAt,
            deletedAt = entity.deletedAt
        )
    }

    fun toEntity(domain: AiChatMessage, conversationId: String = requireNotNull(domain.conversationId)): AiChatMessageEntity {
        val contentJson = buildContentJson(domain)
        
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

    /**
     * Safely parses contentJson into a JSONObject. Returns an empty object for
     * non-object values (null literal, array, scalar) so that callers can
     * continue to overlay media/choiceCard fields without crashing.
     */
    private fun safeParseContentJson(raw: String): JSONObject? {
        return try {
            when (val value = org.json.JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Builds the contentJson string for persistence.
     *
     * Preserves unknown fields from the domain's raw [contentJson], then overlays
     * the structured fields we own (choiceCard and media). This keeps the media
     * contract compatible with any future fields added by other features.
     */
    private fun buildContentJson(domain: AiChatMessage): String? {
        val base = domain.contentJson?.let { safeParseContentJson(it) } ?: JSONObject()

        domain.choiceCard?.let { card ->
            val cardJson = choiceCardAdapter.toJson(card)?.let { safeParseContentJson(it) }
            cardJson?.let { overlayJSONObject(base, it) }
        }

        if (domain.sourceMediaIds.isNotEmpty()) {
            val media = base.optJSONObject(MEDIA_KEY) ?: JSONObject()
            media.put(MEDIA_SCHEMA_VERSION_KEY, MEDIA_SCHEMA_VERSION)
            media.put(SOURCE_MEDIA_IDS_KEY, JSONArray(domain.sourceMediaIds))
            base.put(MEDIA_KEY, media)
        }

        return if (base.length() == 0) null else base.toString()
    }

    /**
     * Overlays [overlay] onto [base] without removing existing keys.
     * Existing keys are overwritten only if the overlay value is non-null.
     */
    private fun overlayJSONObject(base: JSONObject, overlay: JSONObject) {
        val keys = overlay.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!overlay.isNull(key)) {
                base.put(key, overlay.get(key))
            }
        }
    }

    companion object {
        private const val MEDIA_KEY = "media"
        private const val MEDIA_SCHEMA_VERSION_KEY = "schemaVersion"
        private const val SOURCE_MEDIA_IDS_KEY = "sourceMediaIds"
        private const val MEDIA_SCHEMA_VERSION = 1
    }
}
