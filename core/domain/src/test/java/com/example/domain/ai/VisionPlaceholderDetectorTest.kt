package com.example.domain.ai

import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.assistantPlaceholderId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPlaceholderDetectorTest {

    @Test
    fun `detects vision placeholder for user message with media`() {
        val userMessageId = "user-1"
        val placeholderId = assistantPlaceholderId(userMessageId)
        val userMessage = AiChatMessage(
            id = userMessageId,
            conversationId = "conv-1",
            role = ChatRole.User,
            text = "look",
            sourceMediaIds = listOf("media-1")
        )
        val placeholder = AiChatMessage(
            id = placeholderId,
            conversationId = "conv-1",
            role = ChatRole.Assistant,
            text = ""
        )

        assertTrue(isVisionAssistantPlaceholder(placeholder, listOf(userMessage, placeholder)))
    }

    @Test
    fun `text placeholder is not vision placeholder`() {
        val userMessage = AiChatMessage(
            id = "user-text",
            conversationId = "conv-1",
            role = ChatRole.User,
            text = "hello",
            sourceMediaIds = emptyList()
        )
        val placeholder = AiChatMessage(
            id = assistantPlaceholderId("user-text"),
            conversationId = "conv-1",
            role = ChatRole.Assistant,
            text = ""
        )

        assertFalse(isVisionAssistantPlaceholder(placeholder, listOf(userMessage, placeholder)))
    }

    @Test
    fun `user message is not vision placeholder`() {
        val userMessage = AiChatMessage(
            id = "user-1",
            conversationId = "conv-1",
            role = ChatRole.User,
            text = "look",
            sourceMediaIds = listOf("media-1")
        )

        assertFalse(isVisionAssistantPlaceholder(userMessage, listOf(userMessage)))
    }
}
