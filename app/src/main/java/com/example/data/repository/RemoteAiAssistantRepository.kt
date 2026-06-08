package com.example.data.repository

import android.util.Log
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.mapper.AiAssistantRemoteMapper
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.ProtocolException
import com.example.domain.repository.AiAssistantRepository
import java.util.UUID

class RemoteAiAssistantRepository(
    private val apiService: AiDraftApiService
) : AiAssistantRepository {

    private val mapper = AiAssistantRemoteMapper()

    override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
        Log.d("AssistantTurnV2", "sendMessage start: userText='${request.userText.take(50)}', date=${request.date}, recentMessagesCount=${request.recentMessages.size}")
        
        val requestDto = mapper.toRequestDto(request)
        
        Log.d("DayZeroAiV2", "parse AssistantTurnResponse start")
        
        return try {
            val response = apiService.sendAssistantTurnV2WithResponse(requestDto)
            Log.d("AssistantTurnV2", "assistant-turn-v2 response received: status=${response.code()}")
            
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                Log.e("AssistantTurnV2", "assistant-turn-v2 failed: status=${response.code()}, error=$errorBody")
                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: HTTP status=${response.code()}")
                throw ProtocolException("协议错误")
            }

            val responseBody = response.body()
            if (responseBody == null) {
                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: body is null")
                throw ProtocolException("协议错误")
            }

            val reply = responseBody.reply
            val actions = responseBody.actions

            if (reply == null || reply.trim().isEmpty()) {
                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: reply is null or empty")
                throw ProtocolException("协议错误")
            }

            if (actions == null) {
                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: actions is null")
                throw ProtocolException("协议错误")
            }

            Log.d("DayZeroAiV2", "action parse start")
            val mappedCards = mutableListOf<com.example.domain.model.ai.assistant.AiChatCard>()

            if (actions.isNotEmpty()) {
                actions.forEach { action ->
                    if (action.type == "debug_show_choice_card") {
                        Log.d("DayZeroAiV2", "action type = debug_show_choice_card")

                        val interactionId = action.interactionId
                        val payload = action.payload

                        if (interactionId.isNullOrBlank() ||
                            payload == null ||
                            payload.title.isNullOrBlank() ||
                            payload.message.isNullOrBlank() ||
                            payload.options.isNullOrEmpty()
                        ) {
                            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: debug_show_choice_card missing required fields. interactionId=$interactionId, title=${payload?.title}, message=${payload?.message}, optionsSize=${payload?.options?.size}")
                            throw ProtocolException("协议错误")
                        }

                        payload.options.forEach { opt ->
                            if (opt.id.isNullOrBlank() || opt.label.isNullOrBlank()) {
                                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: debug_show_choice_card option missing id or label")
                                throw ProtocolException("协议错误")
                            }
                        }

                        val options = payload.options.map { opt ->
                            com.example.domain.model.ai.assistant.DebugChoiceOption(
                                id = opt.id!!,
                                label = opt.label!!
                            )
                        }

                        mappedCards.add(
                            com.example.domain.model.ai.assistant.DebugChoiceCardPayload(
                                id = interactionId,
                                title = payload.title,
                                message = payload.message,
                                options = options
                            )
                        )
                    } else if (action.type == "ask_record_intent_card") {
                        Log.d("DayZeroAiV2", "action type = ask_record_intent_card")

                        val interactionId = action.interactionId
                        val payload = action.payload

                        if (interactionId.isNullOrBlank() ||
                            payload == null ||
                            payload.title.isNullOrBlank() ||
                            payload.message.isNullOrBlank() ||
                            payload.originalText.isNullOrBlank() ||
                            payload.options.isNullOrEmpty()
                        ) {
                            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: ask_record_intent_card missing required fields.")
                            throw ProtocolException("协议错误")
                        }

                        val optionIds = payload.options.map { it.id }
                        if (!optionIds.containsAll(listOf("record", "chat_only", "not_now"))) {
                            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: ask_record_intent_card options missing required keys.")
                            throw ProtocolException("协议错误")
                        }

                        payload.options.forEach { opt ->
                            if (opt.id.isNullOrBlank() || opt.label.isNullOrBlank()) {
                                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: ask_record_intent_card option missing id or label")
                                throw ProtocolException("协议错误")
                            }
                        }

                        val options = payload.options.map { opt ->
                            com.example.domain.model.ai.assistant.AskRecordIntentOption(
                                id = opt.id!!,
                                label = opt.label!!
                            )
                        }

                        mappedCards.add(
                            com.example.domain.model.ai.assistant.AskRecordIntentCardPayload(
                                id = interactionId,
                                title = payload.title,
                                message = payload.message,
                                originalText = payload.originalText,
                                options = options
                            )
                        )
                    } else if (action.type == "ask_missing_info_card") {
                        Log.d("DayZeroAiV2", "action type = ask_missing_info_card")

                        val interactionId = action.interactionId
                        val payload = action.payload

                        if (interactionId.isNullOrBlank() ||
                            payload == null ||
                            payload.title.isNullOrBlank() ||
                            payload.message.isNullOrBlank() ||
                            payload.field.isNullOrBlank() ||
                            payload.originalText.isNullOrBlank() ||
                            payload.options.isNullOrEmpty()
                        ) {
                            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: ask_missing_info_card missing required fields.")
                            throw ProtocolException("协议错误")
                        }

                        if (payload.field == "mealType") {
                            val optionIds = payload.options.map { it.id }
                            if (!optionIds.containsAll(listOf("breakfast", "lunch", "dinner", "snack"))) {
                                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: ask_missing_info_card mealType options missing required keys.")
                                throw ProtocolException("协议错误")
                            }
                        }

                        payload.options.forEach { opt ->
                            if (opt.id.isNullOrBlank() || opt.label.isNullOrBlank()) {
                                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: ask_missing_info_card option missing id or label")
                                throw ProtocolException("协议错误")
                            }
                        }

                        val options = payload.options.map { opt ->
                            com.example.domain.model.ai.assistant.AskMissingInfoOption(
                                id = opt.id!!,
                                label = opt.label!!
                            )
                        }

                        mappedCards.add(
                            com.example.domain.model.ai.assistant.AskMissingInfoCardPayload(
                                id = interactionId,
                                title = payload.title,
                                message = payload.message,
                                field = payload.field,
                                originalText = payload.originalText,
                                options = options
                            )
                        )
                    } else if (action.type == "show_confirm_card") {
                        Log.d("DayZeroAiV2", "action type = show_confirm_card")
                        val interactionId = action.interactionId
                        val payload = action.payload

                        if (interactionId.isNullOrBlank() ||
                            payload == null ||
                            payload.confirmType != "food_record" ||
                            payload.title.isNullOrBlank() ||
                            payload.message.isNullOrBlank() ||
                            payload.originalText.isNullOrBlank() ||
                            payload.mealType.isNullOrBlank() ||
                            payload.items.isNullOrEmpty() ||
                            payload.buttons.isNullOrEmpty()
                        ) {
                            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: show_confirm_card missing required fields.")
                            throw ProtocolException("协议错误")
                        }

                        val items = payload.items.map { item ->
                            if (item.name.isNullOrBlank() || item.calories == null || item.calorieConfidence.isNullOrBlank()) {
                                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: show_confirm_card item missing required fields.")
                                throw ProtocolException("协议错误")
                            }
                            com.example.domain.model.ai.assistant.ConfirmCardItem(
                                name = item.name!!,
                                amountText = item.amountText,
                                calories = item.calories.toInt(),
                                calorieConfidence = item.calorieConfidence!!
                            )
                        }

                        val buttons = payload.buttons.map { btn ->
                            if (btn.id.isNullOrBlank() || btn.label.isNullOrBlank()) {
                                Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: show_confirm_card button missing id or label")
                                throw ProtocolException("协议错误")
                            }
                            com.example.domain.model.ai.assistant.ConfirmCardOption(
                                id = btn.id!!,
                                label = btn.label!!
                            )
                        }
                        
                        val buttonIds = buttons.map { it.id }
                        if (!buttonIds.containsAll(listOf("confirm", "cancel"))) {
                            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: show_confirm_card missing confirm/cancel buttons")
                            throw ProtocolException("协议错误")
                        }

                        mappedCards.add(
                            com.example.domain.model.ai.assistant.ShowConfirmCardPayload(
                                id = interactionId,
                                confirmType = payload.confirmType,
                                title = payload.title,
                                message = payload.message,
                                originalText = payload.originalText,
                                mealType = payload.mealType,
                                items = items,
                                buttons = buttons
                            )
                        )
                    } else {
                        Log.e("DayZeroAiV2", "parse AssistantTurnResponse error: invalid action type = ${action.type}")
                        throw ProtocolException("协议错误")
                    }
                }
            }

            Log.d("DayZeroAiV2", "action parse success")
            Log.d("DayZeroAiV2", "actions count = ${actions.size}")

            AiAssistantTurn(
                id = UUID.randomUUID().toString(),
                intent = AiIntent.GeneralChat,
                replyText = reply.trim(),
                cards = mappedCards,
                suggestedReplies = emptyList()
            )
        } catch (e: Exception) {
            if (e is ProtocolException) {
                throw e
            }
            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error", e)
            throw ProtocolException("协议错误", e)
        }
    }
}
