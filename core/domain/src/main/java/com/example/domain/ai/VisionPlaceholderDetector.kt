package com.example.domain.ai

import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.assistantPlaceholderId

/**
 * Determines whether an assistant placeholder message corresponds to a persisted
 * user message that contains media attachments.
 *
 * The decision is based solely on persisted message state, not on current draft
 * or screen-level attachments.
 */
fun isVisionAssistantPlaceholder(
    message: AiChatMessage,
    allMessages: List<AiChatMessage>
): Boolean {
    if (message.role != ChatRole.Assistant) return false
    return allMessages.any { userMsg ->
        userMsg.role == ChatRole.User &&
            userMsg.sourceMediaIds.isNotEmpty() &&
            assistantPlaceholderId(userMsg.id) == message.id
    }
}
