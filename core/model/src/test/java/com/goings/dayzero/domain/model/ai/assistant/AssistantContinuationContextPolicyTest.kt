package com.goings.dayzero.domain.model.ai.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistantContinuationContextPolicyTest {

    @Test
    fun `safe structured context preserves unknown JSON fields`() {
        val context = mapOf(
            "schemaVersion" to 1,
            "mediaIds" to listOf("11111111-1111-4111-8111-111111111111"),
            "recognizedFoods" to listOf(mapOf("name" to "apple", "calories" to 95)),
            "futureCompatibleField" to mapOf("nested" to true)
        )

        assertEquals(context, AssistantContinuationContextPolicy.sanitize(context))
    }

    @Test
    fun `binary URLs and filesystem paths are rejected`() {
        listOf(
            mapOf("base64" to "abc"),
            mapOf("unknown" to "data:image/jpeg;base64,abc"),
            mapOf("unknown" to "https://example.test/photo.jpg"),
            mapOf("unknown" to "C:\\private\\photo.jpg"),
            mapOf("unknown" to "/private/photo.jpg")
        ).forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) {
                AssistantContinuationContextPolicy.sanitize(unsafe)
            }
        }
    }
}
