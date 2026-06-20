package com.example.data.sync.chat

data class ChatBackfillStats(
    val scannedConversationCount: Int = 0,
    val scannedMessageCount: Int = 0,
    val enqueuedConversationCount: Int = 0,
    val enqueuedMessageCount: Int = 0,
    val skippedDuplicateCount: Int = 0,
    val skippedPlaceholderCount: Int = 0,
    val errorCount: Int = 0
) {
    val enqueuedCount: Int
        get() = enqueuedConversationCount + enqueuedMessageCount
}
