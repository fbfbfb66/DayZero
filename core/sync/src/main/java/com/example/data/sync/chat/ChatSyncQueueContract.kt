package com.example.data.sync.chat

object ChatSyncQueueContract {
    const val ENTITY_CONVERSATION = "ai_conversation"
    const val ENTITY_MESSAGE = "ai_chat_message"

    const val OP_UPSERT_CONVERSATION = "UPSERT_AI_CONVERSATION"
    const val OP_UPSERT_MESSAGE = "UPSERT_AI_CHAT_MESSAGE"
    const val OP_SOFT_DELETE_CONVERSATION = "SOFT_DELETE_AI_CONVERSATION"
    const val OP_SOFT_DELETE_MESSAGE = "SOFT_DELETE_AI_CHAT_MESSAGE"

    val allEntityTypes = setOf(ENTITY_CONVERSATION, ENTITY_MESSAGE)
    val allOperations = setOf(
        OP_UPSERT_CONVERSATION,
        OP_UPSERT_MESSAGE,
        OP_SOFT_DELETE_CONVERSATION,
        OP_SOFT_DELETE_MESSAGE
    )
}

