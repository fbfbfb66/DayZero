package com.example.domain.model.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ChatSyncContractTest {
    @Test
    fun conversationSnapshotUsesStableSchemaVersionAndNaturalDate() {
        val snapshot = ChatSyncConversationSnapshot(
            id = "11111111-1111-1111-1111-111111111111",
            conversationDate = LocalDate.parse("2026-06-21"),
            title = "Title",
            lastMessagePreview = "Preview",
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            lastActivityAtMillis = 3L
        )

        assertEquals(CHAT_SYNC_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals("2026-06-21", snapshot.conversationDate.toString())
    }

    @Test
    fun serverCursorCarriesStableSecondaryId() {
        val cursor = ChatSyncServerCursor(
            serverUpdatedAtMillis = 1_782_000_000_123L,
            id = "22222222-2222-2222-2222-222222222222"
        )

        assertEquals(1_782_000_000_123L, cursor.serverUpdatedAtMillis)
        assertEquals("22222222-2222-2222-2222-222222222222", cursor.id)
    }
}

