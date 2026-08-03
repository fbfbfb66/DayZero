package com.goings.dayzero.domain.sync

/**
 * Schedules durable delivery of an already-persisted conversation-title outbox item.
 */
fun interface ConversationTitleDeliveryScheduler {
    fun schedule(conversationId: String)

    companion object {
        val Noop = ConversationTitleDeliveryScheduler { }
    }
}
