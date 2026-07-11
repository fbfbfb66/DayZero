package com.example.data.remote.mapper

import com.example.data.remote.dto.assistant.AiChatCardDto
import com.example.data.remote.dto.assistant.AiAssistantRequestDto
import com.example.data.remote.dto.assistant.AssistantActionDto
import com.example.data.remote.dto.assistant.AssistantActionItemDto
import com.example.data.remote.dto.assistant.AssistantActionPayloadDto
import com.example.data.remote.dto.assistant.AssistantTurnV2ResponseDto
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.example.domain.model.ai.assistant.AskMissingInfoOption
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.model.ai.assistant.PreparedVisionAttachment
import com.example.domain.model.ai.assistant.PreparedVisionRequest
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiAssistantRemoteMapperTest {

    private val mapper = AiAssistantRemoteMapper()
    private val actionMapper = AssistantTurnV2ResponseMapper()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val chatCardAdapter = moshi.adapter(AiChatCardDto::class.java)

    @Test
    fun confirmMealSourceMediaIds_preserveMissingNullEmptyAndOrderedArrays() {
        fun parseMeal(sourceField: String): ConfirmCardMeal {
            val json = """{"type":"show_confirm_card","id":"c","confirmType":"food_record","title":"t","message":"m","meals":[{"mealType":"lunch","items":[]$sourceField}],"buttons":[]}"""
            return (mapper.toCardDomain(chatCardAdapter.fromJson(json)!!) as ShowConfirmCardPayload).meals!!.single()
        }
        assertNull(parseMeal("").sourceMediaIds)
        assertNull(parseMeal(",\"sourceMediaIds\":null").sourceMediaIds)
        assertEquals(emptyList<String>(), parseMeal(",\"sourceMediaIds\":[]").sourceMediaIds)
        assertEquals(listOf("m2", "m1"), parseMeal(",\"sourceMediaIds\":[\"m2\",\"m1\"]").sourceMediaIds)

        val card = ShowConfirmCardPayload("c", "food_record", "t", "m", null, null, emptyList(),
            meals = listOf(ConfirmCardMeal("lunch", null, 0, emptyList(), listOf("m2", "m1"))), buttons = emptyList())
        val json = chatCardAdapter.toJson(mapper.toDto(card))
        assertTrue(json.contains("\"sourceMediaIds\":[\"m2\",\"m1\"]"))
        assertTrue(!json.contains("\"sourceMediaIds\":\"["))
    }

    @Test
    fun dateGuardPendingOriginalCard_preservesNullEmptyAndOrderedPhotoAssignments() {
        val variants = listOf<List<String>?>(null, emptyList(), listOf("m2", "m1"))
        variants.forEachIndexed { index, ids ->
            val original = ShowConfirmCardPayload(
                "c$index", "food_record", "t", "m", null, null, emptyList(),
                meals = listOf(ConfirmCardMeal("lunch", null, 0, emptyList(), ids)), buttons = emptyList()
            )
            val guard = DateMismatchGuardCardPayload(
                "g$index", "conv", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                pendingOriginalCard = original
            )
            val back = mapper.toCardDomain(mapper.toDto(guard)!!) as DateMismatchGuardCardPayload
            assertEquals(ids, back.pendingOriginalCard.meals!!.single().sourceMediaIds)
        }
    }

    @Test
    fun askCardContinuationContext_roundTripsUnknownSafeFields_andLegacyCardRemainsCompatible() {
        val card = AskMissingInfoCardPayload(
            id = "meal-card",
            title = "Meal",
            message = "Which meal?",
            field = "mealType",
            originalText = "",
            options = listOf(AskMissingInfoOption("lunch", "Lunch")),
            continuationContext = mapOf(
                "schemaVersion" to 1,
                "mediaIds" to listOf("11111111-1111-4111-8111-111111111111"),
                "recognizedFoods" to listOf(mapOf("name" to "apple", "calories" to 95)),
                "futureCompatibleField" to mapOf("nested" to true)
            )
        )

        val roundTripped = mapper.toCardDomain(mapper.toDto(card)!!) as AskMissingInfoCardPayload
        assertEquals(true, (roundTripped.continuationContext?.get("futureCompatibleField") as Map<*, *>)["nested"])
        assertEquals("apple", ((roundTripped.continuationContext?.get("recognizedFoods") as List<*>).single() as Map<*, *>)["name"])

        val legacy = chatCardAdapter.fromJson(
            """{"type":"ask_missing_info_card","id":"legacy","title":"Meal","message":"Which?","field":"mealType","originalText":"food","options":[]}"""
        )!!
        assertNull((mapper.toCardDomain(legacy) as AskMissingInfoCardPayload).continuationContext)
    }

    @Test
    fun toCardDomain_withOldJson_hasNullNutritionFields() {
        val oldJson = """
            {
              "type": "show_confirm_card",
              "id": "card-123",
              "confirmType": "food_record",
              "title": "确认早餐",
              "message": "已为您添加早餐记录",
              "originalText": "我吃了苹果",
              "items": [
                {
                  "name": "苹果",
                  "amountText": "1个",
                  "calories": 80,
                  "calorieConfidence": "high"
                }
              ],
              "buttons": [
                {"id": "confirm", "label": "确认"},
                {"id": "cancel", "label": "取消"}
              ]
            }
        """.trimIndent()

        val dto = chatCardAdapter.fromJson(oldJson)
        assertNotNull(dto)

        val domain = mapper.toCardDomain(dto!!) as ShowConfirmCardPayload
        assertNotNull(domain)
        assertEquals(1, domain.items.size)
        val item = domain.items[0]
        assertEquals("苹果", item.name)
        assertNull(item.carbohydratesG)
        assertNull(item.proteinG)
        assertNull(item.fatG)
        assertNull(item.fiberG)
    }

    @Test
    fun roundTrip_withNutritionFields_preservesValues() {
        val oldJson = """
            {
              "type": "show_confirm_card",
              "id": "card-123",
              "confirmType": "food_record",
              "title": "确认早餐",
              "message": "已为您添加早餐记录",
              "originalText": "我吃了鸡胸肉",
              "items": [
                {
                  "name": "鸡胸肉",
                  "amountText": "100g",
                  "calories": 165,
                  "calorieConfidence": "high",
                  "carbohydratesG": 0.0,
                  "proteinG": 31.0,
                  "fatG": 3.6,
                  "fiberG": null
                }
              ],
              "buttons": [
                {"id": "confirm", "label": "确认"},
                {"id": "cancel", "label": "取消"}
              ]
            }
        """.trimIndent()

        val dto = chatCardAdapter.fromJson(oldJson)
        assertNotNull(dto)

        val domain = mapper.toCardDomain(dto!!) as ShowConfirmCardPayload
        assertNotNull(domain)
        assertEquals(1, domain.items.size)
        val item = domain.items[0]
        assertEquals("鸡胸肉", item.name)
        assertEquals(0.0f, item.carbohydratesG)
        assertEquals(31.0f, item.proteinG)
        assertEquals(3.6f, item.fatG)
        assertNull(item.fiberG)

        // Convert back to DTO
        val backDto = mapper.toDto(domain)
        assertNotNull(backDto)
        assertEquals(1, backDto!!.items!!.size)
        val backItem = backDto.items!![0]
        assertEquals("鸡胸肉", backItem.name)
        assertEquals(0.0f, backItem.carbohydratesG)
        assertEquals(31.0f, backItem.proteinG)
        assertEquals(3.6f, backItem.fatG)
        assertNull(backItem.fiberG)

        // Re-serialize back to JSON
        val backJson = chatCardAdapter.toJson(backDto)
        assertTrue(backJson.contains("\"carbohydratesG\":0.0"))
        assertTrue(backJson.contains("\"proteinG\":31.0"))
        assertTrue(backJson.contains("\"fatG\":3.6"))
        // Wait, Moshi serialization omits null values or serializes them as null?
        // Since they are defined as Nullable without @Json(serializeNulls=true), they might be omitted, which is also fine,
        // but let's check: when deserializing missing keys or explicit nulls, we get null. That is correct.
    }

    @Test
    fun dateMismatchGuardCard_preservesNutritionFieldsInPendingOriginalCard() {
        val originalCard = ShowConfirmCardPayload(
            id = "original-123",
            confirmType = "food_record",
            title = "Title",
            message = "Msg",
            originalText = "Text",
            mealType = "Breakfast",
            items = listOf(
                ConfirmCardItem(
                    name = "鸡胸肉",
                    amountText = "100g",
                    calories = 165,
                    calorieConfidence = "high",
                    carbohydratesG = 0.0f,
                    proteinG = 31.0f,
                    fatG = 3.6f,
                    fiberG = 1.0f
                )
            ),
            buttons = emptyList()
        )

        val guardCard = DateMismatchGuardCardPayload(
            id = "guard-123",
            conversationId = "conv-123",
            conversationDate = LocalDate.now(),
            detectedCurrentDate = LocalDate.now(),
            pendingOriginalCard = originalCard
        )

        val dto = mapper.toDto(guardCard)
        assertNotNull(dto)

        val backDomain = mapper.toCardDomain(dto!!) as DateMismatchGuardCardPayload
        assertNotNull(backDomain)
        val backOriginal = backDomain.pendingOriginalCard
        assertEquals(1, backOriginal.items.size)
        val backItem = backOriginal.items[0]
        assertEquals("鸡胸肉", backItem.name)
        assertEquals(0.0f, backItem.carbohydratesG)
        assertEquals(31.0f, backItem.proteinG)
        assertEquals(3.6f, backItem.fatG)
        assertEquals(1.0f, backItem.fiberG)
    }

    @Test
    fun roundTrip_preservesZeroAndNullNutritionSeparately() {
        val card = ShowConfirmCardPayload(
            id = "card-zero-null",
            confirmType = "food_record",
            title = "Title",
            message = "Msg",
            originalText = "Text",
            mealType = null,
            items = emptyList(),
            meals = listOf(
                com.example.domain.model.ai.assistant.ConfirmCardMeal(
                    mealType = "lunch",
                    mealLabel = "Lunch",
                    subtotalCalories = 100,
                    items = listOf(
                        ConfirmCardItem(
                            id = "item-1",
                            name = "tofu",
                            amountText = "100g",
                            calories = 100,
                            calorieConfidence = "estimated",
                            carbohydratesG = 0.0f,
                            proteinG = null,
                            fatG = 3.0f,
                            fiberG = null
                        )
                    )
                )
            ),
            buttons = emptyList()
        )

        val dto = mapper.toDto(card)
        val back = mapper.toCardDomain(dto!!) as ShowConfirmCardPayload
        val item = back.meals!!.single().items.single()

        assertEquals(0.0f, item.carbohydratesG)
        assertNull(item.proteinG)
        assertEquals(3.0f, item.fatG)
        assertNull(item.fiberG)
    }

    @Test
    fun editedNullNutritionInPendingOriginalCard_survivesRoundTrip() {
        val originalCard = ShowConfirmCardPayload(
            id = "original-edited",
            confirmType = "food_record",
            title = "Title",
            message = "Msg",
            originalText = "Text",
            mealType = null,
            items = emptyList(),
            meals = listOf(
                com.example.domain.model.ai.assistant.ConfirmCardMeal(
                    mealType = "lunch",
                    mealLabel = "Lunch",
                    subtotalCalories = 300,
                    items = listOf(
                        ConfirmCardItem(
                            id = "item-1",
                            name = "brown rice",
                            amountText = "1 bowl",
                            calories = 300,
                            calorieConfidence = "user_edited",
                            carbohydratesG = null,
                            proteinG = null,
                            fatG = null,
                            fiberG = null
                        )
                    )
                )
            ),
            buttons = emptyList()
        )
        val guardCard = DateMismatchGuardCardPayload(
            id = "guard-edited",
            conversationId = "conv-123",
            conversationDate = LocalDate.of(2026, 6, 25),
            detectedCurrentDate = LocalDate.of(2026, 6, 26),
            state = "approved",
            pendingOriginalCard = originalCard
        )

        val back = mapper.toCardDomain(mapper.toDto(guardCard)!!) as DateMismatchGuardCardPayload
        val item = back.pendingOriginalCard.meals!!.single().items.single()

        assertEquals("brown rice", item.name)
        assertNull(item.carbohydratesG)
        assertNull(item.proteinG)
        assertNull(item.fatG)
        assertNull(item.fiberG)
    }

    @Test
    fun historicalCardWithoutNutritionDoesNotCrashAndHasNulls() {
        val oldJson = """
            {
              "type": "show_confirm_card",
              "id": "legacy-card",
              "confirmType": "food_record",
              "title": "Confirm",
              "message": "Confirm food",
              "meals": [
                {
                  "mealType": "lunch",
                  "mealLabel": "Lunch",
                  "subtotalCalories": 300,
                  "items": [
                    {
                      "name": "rice",
                      "amountText": "1 bowl",
                      "calories": 300,
                      "calorieConfidence": "estimated"
                    }
                  ]
                }
              ],
              "buttons": []
            }
        """.trimIndent()

        val card = mapper.toCardDomain(chatCardAdapter.fromJson(oldJson)!!) as ShowConfirmCardPayload
        val item = card.meals!!.single().items.single()

        assertNull(item.carbohydratesG)
        assertNull(item.proteinG)
        assertNull(item.fatG)
        assertNull(item.fiberG)
    }

    @Test
    fun actionResponseMapper_parsesNutritionFieldsCorrectly() {
        val actionDto = AssistantActionDto(
            type = "show_confirm_card",
            id = "card-123",
            payload = AssistantActionPayloadDto(
                confirmType = "food_record",
                title = "确认",
                message = "已添加",
                originalText = "吃了香蕉",
                buttons = listOf(
                    com.example.data.remote.dto.assistant.AssistantActionOptionDto("confirm", "确认"),
                    com.example.data.remote.dto.assistant.AssistantActionOptionDto("cancel", "取消")
                ),
                items = listOf(
                    AssistantActionItemDto(
                        name = "香蕉",
                        amountText = "1根",
                        calories = 90,
                        calorieConfidence = "high",
                        carbohydratesG = 23.0f,
                        proteinG = 1.1f,
                        fatG = 0.3f,
                        fiberG = 2.6f
                    )
                )
            )
        )

        val turnResponse = AssistantTurnV2ResponseDto(
            reply = "回复内容",
            actions = listOf(actionDto)
        )

        val domainTurn = actionMapper.toDomain(turnResponse)
        assertEquals(1, domainTurn.cards.size)
        val card = domainTurn.cards[0] as ShowConfirmCardPayload
        assertEquals(1, card.items.size)
        val item = card.items[0]
        assertEquals("香蕉", item.name)
        assertEquals(23.0f, item.carbohydratesG)
        assertEquals(1.1f, item.proteinG)
        assertEquals(0.3f, item.fatG)
        assertEquals(2.6f, item.fiberG)
    }

    @Test
    fun textOnlyRequest_omitsAttachmentsField() {
        val request = AiAssistantRequest(
            date = LocalDate.of(2026, 6, 26),
            userText = "hello"
        )

        val dto = mapper.toRequestDto(request)
        assertNull(dto.attachments)
    }

    @Test
    fun requestWithAttachments_mapsToDtoArray() {
        val request = AiAssistantRequest(
            date = LocalDate.of(2026, 6, 26),
            userText = "look at this",
            attachments = listOf(
                PreparedVisionAttachment(
                    mediaId = "media-1",
                    mimeType = PreparedVisionRequest.MIME_TYPE_JPEG,
                    base64 = "dGVzdA==",
                    byteSize = 1234
                )
            )
        )

        val dto = mapper.toRequestDto(request)
        assertNotNull(dto.attachments)
        assertEquals(1, dto.attachments!!.size)
        val attachment = dto.attachments[0]
        assertEquals("media-1", attachment.mediaId)
        assertEquals(PreparedVisionRequest.MIME_TYPE_JPEG, attachment.mimeType)
        assertEquals("dGVzdA==", attachment.base64)
    }

    @Test
    fun requestWithEmptyAttachments_omitsAttachmentsField() {
        val request = AiAssistantRequest(
            date = LocalDate.of(2026, 6, 26),
            userText = "hello",
            attachments = emptyList()
        )

        val dto = mapper.toRequestDto(request)
        assertNull(dto.attachments)
    }

    @Test
    fun attachmentsDto_serializesAsJsonArrayNotString() {
        val request = AiAssistantRequest(
            date = LocalDate.of(2026, 6, 26),
            userText = "image only",
            attachments = listOf(
                PreparedVisionAttachment(
                    mediaId = "m1",
                    mimeType = PreparedVisionRequest.MIME_TYPE_JPEG,
                    base64 = "Yg==",
                    byteSize = 1
                ),
                PreparedVisionAttachment(
                    mediaId = "m2",
                    mimeType = PreparedVisionRequest.MIME_TYPE_JPEG,
                    base64 = "Yw==",
                    byteSize = 1
                )
            )
        )

        val dto = mapper.toRequestDto(request)
        val adapter = moshi.adapter(AiAssistantRequestDto::class.java)
        val json = adapter.toJson(dto)

        assertTrue(json.contains("\"attachments\":["))
        assertTrue(json.contains("\"mediaId\":\"m1\""))
        assertTrue(json.contains("\"mediaId\":\"m2\""))
        assertTrue(json.contains("\"mimeType\":\"image/jpeg\""))
        // Base64 must not be stringified again or contain data URL prefix
        assertTrue(json.contains("\"base64\":\"Yg==\""))
        assertTrue(json.contains("\"base64\":\"Yw==\""))
    }

    @Test
    fun interactionResultRequest_omitsAttachmentsField() {
        val request = AiAssistantRequest(
            date = LocalDate.of(2026, 6, 26),
            userText = "已选择：确认",
            turnType = "interaction_result",
            interactionResult = com.example.domain.model.ai.assistant.InteractionResult(
                interactionId = "card-1",
                actionType = "show_confirm_card",
                selectedOptionId = "confirm",
                continuationContext = mapOf(
                    "schemaVersion" to 1,
                    "recognizedFoods" to listOf(
                        mapOf("name" to "apple", "amountText" to "1 item", "calories" to 95)
                    ),
                    "futureCompatibleField" to "kept"
                ),
                selectedOptionLabel = "确认"
            ),
            attachments = listOf(
                PreparedVisionAttachment(
                    mediaId = "media-1",
                    mimeType = PreparedVisionRequest.MIME_TYPE_JPEG,
                    base64 = "dGVzdA==",
                    byteSize = 1234
                )
            )
        )

        val dto = mapper.toRequestDto(request)
        assertNotNull(dto.interactionResult)
        assertEquals("card-1", dto.interactionResult!!.interactionId)
        assertEquals("kept", dto.interactionResult!!.continuationContext?.get("futureCompatibleField"))
        assertNull(dto.attachments)
    }
}
