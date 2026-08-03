package com.goings.dayzero.data.sync.title

object ConversationTitleSyncContract {
    const val ENTITY_TITLE_JOB = "ai_conversation_title_job"
    const val OP_SUBMIT_TITLE_JOB = "SUBMIT_AI_CONVERSATION_TITLE_JOB"

    fun queueId(conversationId: String): String =
        java.util.UUID.nameUUIDFromBytes(
            "dayzero-conversation-title:$conversationId".toByteArray(Charsets.UTF_8)
        ).toString()

    fun requestId(conversationId: String, firstUserMessageId: String): String =
        java.util.UUID.nameUUIDFromBytes(
            "dayzero-conversation-title-request:$conversationId:$firstUserMessageId"
                .toByteArray(Charsets.UTF_8)
        ).toString()
}
