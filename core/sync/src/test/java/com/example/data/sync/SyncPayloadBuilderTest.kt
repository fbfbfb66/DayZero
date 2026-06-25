package com.example.data.sync

import com.example.domain.identity.AppIdentity
import com.example.domain.model.FoodEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPayloadBuilderTest {

    private val builder = SyncPayloadBuilder()
    private val identity = AppIdentity(
        localOwnerId = "local-user-1",
        remoteUserId = "remote-user-1",
        authProvider = "google",
        canRemoteSync = true
    )

    @Test
    fun foodPayload_withNullNutrition_mapsToExplicitJsonNull() {
        val food = FoodEntry(
            id = "food-1",
            name = "苹果",
            quantity = "1个",
            estimatedCalories = 80,
            confidence = "high",
            carbohydratesG = null,
            proteinG = null,
            fatG = null,
            fiberG = null
        )

        val json = builder.foodPayload("record-1", "meal-1", food, identity)

        assertEquals("food-1", json.getString("clientId"))
        assertEquals("local-user-1", json.getString("ownerLocalId"))
        assertTrue(json.isNull("carbsG"))
        assertTrue(json.isNull("proteinG"))
        assertTrue(json.isNull("fatG"))
        assertTrue(json.isNull("fiberG"))

        // Verify key existence is explicit
        assertTrue(json.has("carbsG"))
        assertTrue(json.has("proteinG"))
        assertTrue(json.has("fatG"))
        assertTrue(json.has("fiberG"))
    }

    @Test
    fun foodPayload_withZeroNutrition_mapsToNumericZero() {
        val food = FoodEntry(
            id = "food-1",
            name = "纯净水",
            quantity = "500ml",
            estimatedCalories = 0,
            confidence = "high",
            carbohydratesG = 0.0f,
            proteinG = 0.0f,
            fatG = 0.0f,
            fiberG = 0.0f
        )

        val json = builder.foodPayload("record-1", "meal-1", food, identity)

        assertEquals(0.0, json.getDouble("carbsG"), 0.001)
        assertEquals(0.0, json.getDouble("proteinG"), 0.001)
        assertEquals(0.0, json.getDouble("fatG"), 0.001)
        assertEquals(0.0, json.getDouble("fiberG"), 0.001)
    }

    @Test
    fun foodPayload_withPositiveNutrition_mapsToNumbers() {
        val food = FoodEntry(
            id = "food-1",
            name = "鸡胸肉",
            quantity = "100g",
            estimatedCalories = 165,
            confidence = "high",
            carbohydratesG = 0.0f,
            proteinG = 31.0f,
            fatG = 3.6f,
            fiberG = 1.5f
        )

        val json = builder.foodPayload("record-1", "meal-1", food, identity)

        assertEquals(0.0, json.getDouble("carbsG"), 0.001)
        assertEquals(31.0, json.getDouble("proteinG"), 0.001)
        assertEquals(3.6, json.getDouble("fatG"), 0.001)
        assertEquals(1.5, json.getDouble("fiberG"), 0.001)
    }
}
