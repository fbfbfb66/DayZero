package com.example.domain.model.ai

import com.example.domain.model.ai.assistant.AiChatCard
import java.util.UUID

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val relatedDraftId: String? = null,
    val messageType: ChatMessageType = ChatMessageType.Text,
    val choiceCard: ChoiceCard? = null,
    val assistantCards: List<AiChatCard> = emptyList(),
    val suggestedReplies: List<String> = emptyList()
)

enum class ChatMessageType {
    Text, DraftCard, ChoiceCard
}

data class ChoiceCard(
    val title: String,
    val options: List<ChatOption>,
    val resolved: Boolean = false
)

data class ChatOption(
    val id: String,
    val label: String,
    val action: ChatAction
)

enum class ChatAction {
    Cancel, 
    AddNonConflictingMeals, 
    OverrideConflictingMeals, 
    AppendToExistingMeals,
    SetMealTypeBreakfast, 
    SetMealTypeLunch, 
    SetMealTypeDinner, 
    SetMealTypeSnack,
    ConfirmWeight,
    ConfirmEdit,
    ConfirmDelete
}
