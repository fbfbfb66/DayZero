package com.example.domain.model.ai.assistant

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Deterministic assistant placeholder message id for a given user message.
 *
 * This algorithm must stay in sync with the local transaction layer so that
 * retries and UI lookups can identify the placeholder belonging to a persisted
 * user message without scanning the database.
 */
fun assistantPlaceholderId(userMessageId: String): String {
    return UUID.nameUUIDFromBytes(
        "dayzero-assistant-reply:$userMessageId".toByteArray(StandardCharsets.UTF_8)
    ).toString()
}
