package com.goings.dayzero.data.local.mapper

import com.goings.dayzero.data.local.entity.AiChatMessageEntity
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiChatMessageMapperTest {

    private val mapper = AiChatMessageMapper()

    @Test
    fun toDomainExtractsSourceMediaIdsFromContentJson() {
        val entity = AiChatMessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "User",
            text = "",
            createdAt = 1000L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":["a","b","c"]}}""",
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 1000L,
            deletedAt = null
        )

        val domain = mapper.toDomain(entity)

        assertEquals(listOf("a", "b", "c"), domain.sourceMediaIds)
        assertEquals("msg-1", domain.id)
    }

    @Test
    fun toDomainIgnoresInvalidMediaEntries() {
        val entity = AiChatMessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "User",
            text = "",
            createdAt = 1000L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":["a","","c"]}}""",
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 1000L,
            deletedAt = null
        )

        val domain = mapper.toDomain(entity)

        assertEquals(listOf("a", "c"), domain.sourceMediaIds)
    }

    @Test
    fun toDomainHandlesEmptyMediaObject() {
        val entity = AiChatMessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "User",
            text = "hello",
            createdAt = 1000L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = "{}",
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 1000L,
            deletedAt = null
        )

        val domain = mapper.toDomain(entity)

        assertTrue(domain.sourceMediaIds.isEmpty())
        assertEquals("hello", domain.text)
    }

    @Test
    fun toDomainHandlesNullContentJson() {
        val entity = AiChatMessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "User",
            text = "hello",
            createdAt = 1000L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 1000L,
            deletedAt = null
        )

        val domain = mapper.toDomain(entity)

        assertTrue(domain.sourceMediaIds.isEmpty())
        assertNull(domain.contentJson)
    }

    @Test
    fun toEntityWritesMediaContentJson() {
        val domain = AiChatMessage(
            id = "msg-1",
            conversationId = "conv-1",
            role = ChatRole.User,
            text = "",
            createdAt = 1000L,
            sourceMediaIds = listOf("a", "b")
        )

        val entity = mapper.toEntity(domain)

        val json = JSONObject(entity.contentJson!!)
        val media = json.getJSONObject("media")
        assertEquals(1, media.getInt("schemaVersion"))
        val ids = media.getJSONArray("sourceMediaIds")
        assertEquals(2, ids.length())
        assertEquals("a", ids.getString(0))
        assertEquals("b", ids.getString(1))
    }

    @Test
    fun toEntityPreservesUnknownContentJsonFields() {
        val domain = AiChatMessage(
            id = "msg-1",
            conversationId = "conv-1",
            role = ChatRole.User,
            text = "",
            createdAt = 1000L,
            sourceMediaIds = listOf("a"),
            contentJson = """{"futureField":{"keep":true},"media":{"schemaVersion":99}}"""
        )

        val entity = mapper.toEntity(domain)

        val json = JSONObject(entity.contentJson!!)
        assertNotNull(json.getJSONObject("futureField"))
        assertTrue(json.getJSONObject("futureField").getBoolean("keep"))
        assertEquals(1, json.getJSONObject("media").getInt("schemaVersion"))
        assertEquals("a", json.getJSONObject("media").getJSONArray("sourceMediaIds").getString(0))
    }

    @Test
    fun toEntityOmitsMediaFieldWhenSourceMediaIdsEmpty() {
        val domain = AiChatMessage(
            id = "msg-1",
            conversationId = "conv-1",
            role = ChatRole.User,
            text = "hello",
            createdAt = 1000L,
            sourceMediaIds = emptyList()
        )

        val entity = mapper.toEntity(domain)

        assertNull(entity.contentJson)
    }

    @Test
    fun toEntityPreservesUnknownNestedMediaFields() {
        val domain = AiChatMessage(
            id = "msg-1",
            conversationId = "conv-1",
            role = ChatRole.User,
            text = "",
            createdAt = 1000L,
            sourceMediaIds = listOf("new-1", "new-2"),
            contentJson = """
                {
                    "existingTopLevel": {"x": 1},
                    "media": {
                        "schemaVersion": 99,
                        "sourceMediaIds": ["old"],
                        "futureUnknownField": "keep",
                        "futureNestedObject": {"a": 1}
                    }
                }
            """.trimIndent()
        )

        val entity = mapper.toEntity(domain)

        val json = JSONObject(entity.contentJson!!)
        assertEquals(1, json.getJSONObject("existingTopLevel").getInt("x"))
        val media = json.getJSONObject("media")
        assertEquals(1, media.getInt("schemaVersion"))
        assertEquals("new-1", media.getJSONArray("sourceMediaIds").getString(0))
        assertEquals("new-2", media.getJSONArray("sourceMediaIds").getString(1))
        assertEquals("keep", media.getString("futureUnknownField"))
        assertEquals(1, media.getJSONObject("futureNestedObject").getInt("a"))
    }

    @Test
    fun roundTripPreservesSourceMediaIdsAndUnknownFields() {
        val original = AiChatMessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "User",
            text = "",
            createdAt = 1000L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":["x","y"]},"extra":123}""",
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 1000L,
            deletedAt = null
        )

        val domain = mapper.toDomain(original)
        val roundTrip = mapper.toEntity(domain)

        val json = JSONObject(roundTrip.contentJson!!)
        assertEquals(123, json.getInt("extra"))
        val ids = json.getJSONObject("media").getJSONArray("sourceMediaIds")
        assertEquals(2, ids.length())
        assertEquals("x", ids.getString(0))
        assertEquals("y", ids.getString(1))
    }
}
