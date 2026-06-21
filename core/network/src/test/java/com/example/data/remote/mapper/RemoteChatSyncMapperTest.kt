package com.example.data.remote.mapper

import com.example.data.remote.dto.chat.RemoteAiChatMessageDto
import com.example.domain.model.sync.ChatSyncConversationSnapshot
import com.example.domain.model.sync.ChatSyncMessageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RemoteChatSyncMapperTest {
    private val mapper = RemoteChatSyncMapper()

    @Test
    fun conversationSnapshotMapsEveryRemoteField() {
        val snapshot = ChatSyncConversationSnapshot(
            id = "11111111-1111-1111-1111-111111111111",
            conversationDate = LocalDate.parse("2026-06-21"),
            title = "Breakfast",
            lastMessagePreview = "Ate eggs",
            createdAtMillis = 1_782_000_000_123L,
            updatedAtMillis = 1_782_000_001_234L,
            lastActivityAtMillis = 1_782_000_002_345L,
            deletedAtMillis = null,
            schemaVersion = 1
        )

        val dto = mapper.toRemoteConversation(snapshot)
        val roundTrip = mapper.toConversationSnapshot(dto)

        assertEquals(snapshot.id, dto.id)
        assertNull(dto.userId)
        assertEquals("2026-06-21", dto.conversationDate)
        assertEquals("2026-06-21T00:00:00.123Z", dto.createdAt)
        assertEquals("2026-06-21T00:00:01.234Z", dto.updatedAt)
        assertEquals("2026-06-21T00:00:02.345Z", dto.lastActivityAt)
        assertNull(dto.deletedAt)
        assertEquals(snapshot, roundTrip)
    }

    @Test
    fun conversationDateRoundTripsWithoutTimezoneShift() {
        val snapshot = ChatSyncConversationSnapshot(
            id = "22222222-2222-2222-2222-222222222222",
            conversationDate = LocalDate.parse("2026-01-01"),
            title = "New year",
            lastMessagePreview = "",
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            lastActivityAtMillis = 3L
        )

        val roundTrip = mapper.toConversationSnapshot(mapper.toRemoteConversation(snapshot))

        assertEquals(LocalDate.parse("2026-01-01"), roundTrip.conversationDate)
    }

    @Test
    fun messageSnapshotMapsEveryRemoteFieldAndSoftDelete() {
        val snapshot = ChatSyncMessageSnapshot(
            id = "33333333-3333-3333-3333-333333333333",
            conversationId = "22222222-2222-2222-2222-222222222222",
            role = "Assistant",
            text = "Final answer",
            createdAtMillis = 1_782_000_000_123L,
            updatedAtMillis = 1_782_000_010_123L,
            deletedAtMillis = 1_782_000_020_123L,
            messageType = "Text",
            contentJson = """{"debug":true}""",
            assistantCardsJson = """[]""",
            suggestedRepliesJson = """["ok"]""",
            schemaVersion = 1
        )

        val dto = mapper.toRemoteMessage(snapshot)
        val roundTrip = mapper.toMessageSnapshot(dto)

        assertEquals(snapshot.id, dto.id)
        assertNull(dto.userId)
        assertEquals(snapshot.conversationId, dto.conversationId)
        assertEquals("Assistant", dto.role)
        assertEquals("Text", dto.messageType)
        assertEquals("2026-06-21T00:00:20.123Z", dto.deletedAt)
        assertEquals(snapshot, roundTrip)
    }

    @Test
    fun nullDeletedAtAndNullCardsStayNull() {
        val snapshot = ChatSyncMessageSnapshot(
            id = "44444444-4444-4444-4444-444444444444",
            conversationId = "22222222-2222-2222-2222-222222222222",
            role = "User",
            text = "hello",
            createdAtMillis = 10L,
            updatedAtMillis = 10L,
            assistantCardsJson = null
        )

        val dto = mapper.toRemoteMessage(snapshot)
        val roundTrip = mapper.toMessageSnapshot(dto)

        assertNull(dto.deletedAt)
        assertNull(dto.assistantCardsJson)
        assertNull(roundTrip.assistantCardsJson)
    }

    @Test
    fun emptyCardsArrayIsDistinctFromNull() {
        val snapshot = ChatSyncMessageSnapshot(
            id = "55555555-5555-5555-5555-555555555555",
            conversationId = "22222222-2222-2222-2222-222222222222",
            role = "Assistant",
            text = "",
            createdAtMillis = 10L,
            updatedAtMillis = 10L,
            assistantCardsJson = "[]"
        )

        val dto = mapper.toRemoteMessage(snapshot)

        assertEquals("[]", dto.assistantCardsJson)
        assertEquals("[]", mapper.toMessageSnapshot(dto).assistantCardsJson)
    }

    @Test
    fun showConfirmCardJsonRoundTripsWithoutChangingPayload() {
        val cardsJson = """
            [{"type":"show_confirm_card","id":"card-1","confirmType":"food_record","title":"Confirm","message":"Check","originalText":"rice","date":"2026-06-21","weightKg":72.5,"meals":[{"mealType":"Lunch","mealLabel":"Lunch","subtotalCalories":330,"items":[{"id":"item-1","name":"rice","amountText":"1 bowl","calories":330,"calorieConfidence":"high"}]}],"buttons":[{"id":"confirm","label":"Confirm"},{"id":"cancel","label":"Cancel"}],"state":"confirmed","futureField":{"keep":true}}]
        """.trimIndent()

        val dto = messageDto(assistantCardsJson = cardsJson)
        val roundTrip = mapper.toRemoteMessage(mapper.toMessageSnapshot(dto))

        assertEquals(cardsJson, roundTrip.assistantCardsJson)
    }

    @Test
    fun dateMismatchGuardJsonRoundTripsWithoutChangingPendingOriginalCard() {
        val cardsJson = """
            [{"type":"date_mismatch_guard_card","id":"guard-1","conversationId":"c1","conversationDate":"2026-06-20","detectedCurrentDate":"2026-06-21","state":"approved","createdAt":1782000000123,"pendingOriginalCard":{"type":"show_confirm_card","id":"card-1","confirmType":"food_record","meals":[{"mealType":"Dinner","items":[{"name":"noodle","calories":500,"calorieConfidence":"medium"}]}],"buttons":[{"id":"confirm","label":"Confirm"},{"id":"cancel","label":"Cancel"}],"state":"pending","unknownNested":"keep-me"}}]
        """.trimIndent()

        val dto = messageDto(assistantCardsJson = cardsJson)
        val roundTrip = mapper.toRemoteMessage(mapper.toMessageSnapshot(dto))

        assertEquals(cardsJson, roundTrip.assistantCardsJson)
    }

    @Test
    fun unknownCardJsonFieldsArePreservedAsRawJson() {
        val cardsJson = """[{"type":"future_card","id":"f1","newObject":{"x":1},"newArray":[true,"yes"]}]"""

        val dto = messageDto(assistantCardsJson = cardsJson)
        val roundTrip = mapper.toRemoteMessage(mapper.toMessageSnapshot(dto))

        assertEquals(cardsJson, roundTrip.assistantCardsJson)
    }

    @Test
    fun streamingStateAndDraftTextAreNotPartOfRemoteMessageDto() {
        val propertyNames = RemoteAiChatMessageDto::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(false, "streamingState" in propertyNames)
        assertEquals(false, "isAnalyzing" in propertyNames)
        assertEquals(false, "inputDraft" in propertyNames)
        assertEquals(false, "activeConversationId" in propertyNames)
    }

    @Test
    fun testDateTimeParsingFormats() {
        val formats = listOf(
            "2026-06-21T13:39:20.154Z",
            "2026-06-21T13:39:20.154+00:00",
            "2026-06-21T13:39:20.154000+00:00",
            "2026-06-21T13:39:20Z",
            "2026-06-21T13:39:20+08:00",
            "2026-06-21T13:39:20.154-05:00"
        )
        for (f in formats) {
            val instantResult = runCatching { java.time.Instant.parse(f).toEpochMilli() }
            val offsetResult = runCatching { java.time.OffsetDateTime.parse(f).toInstant().toEpochMilli() }
            println("Format: $f")
            println("  Instant.parse: success=${instantResult.isSuccess}, error=${instantResult.exceptionOrNull()?.javaClass?.simpleName}, value=${instantResult.getOrNull()}")
            println("  OffsetDateTime.parse: success=${offsetResult.isSuccess}, error=${offsetResult.exceptionOrNull()?.javaClass?.simpleName}, value=${offsetResult.getOrNull()}")
        }
    }

    private fun messageDto(assistantCardsJson: String?): RemoteAiChatMessageDto {
        return RemoteAiChatMessageDto(
            id = "66666666-6666-6666-6666-666666666666",
            conversationId = "22222222-2222-2222-2222-222222222222",
            role = "Assistant",
            messageType = "Text",
            text = "",
            assistantCardsJson = assistantCardsJson,
            createdAt = "2026-06-16T09:20:00.123Z",
            updatedAt = "2026-06-16T09:20:00.123Z"
        )
    }
}

