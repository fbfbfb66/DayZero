package com.example.data.local.mapper

import com.example.data.local.entity.DailyRecordEntity
import com.example.domain.model.DailyRecord
import com.example.domain.model.FoodEntry
import com.example.domain.model.MealEntry
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DailyRecordMapperTest {

    private val mapper = DailyRecordMapper()

    @Test
    fun toDomain_withOldJson_hasNullNutritionFields() {
        val oldJson = """
            [
              {
                "id": "meal-1",
                "mealType": "Breakfast",
                "hasPhoto": false,
                "foods": [
                  {
                    "id": "food-1",
                    "name": "苹果",
                    "quantity": "1个",
                    "estimatedCalories": 80,
                    "confidence": "high"
                  }
                ]
              }
            ]
        """.trimIndent()

        val entity = DailyRecordEntity(
            id = "record-1",
            date = "2026-06-26",
            status = "Confirmed",
            mealsJson = oldJson,
            weightKg = 70.0f,
            aiSummary = "Summary",
            createdAt = 123456L,
            updatedAt = 123456L,
            clientId = "record-1",
            syncStatus = "PENDING",
            syncVersion = 123456L,
            ownerLocalId = "user-1"
        )

        val domain = mapper.toDomain(entity)

        assertEquals("record-1", domain.id)
        assertEquals(1, domain.meals.size)
        val meal = domain.meals[0]
        assertEquals(MealType.Breakfast, meal.mealType)
        assertEquals(1, meal.foods.size)
        val food = meal.foods[0]
        assertEquals("food-1", food.id)
        assertEquals("苹果", food.name)
        assertNull(food.carbohydratesG)
        assertNull(food.proteinG)
        assertNull(food.fatG)
        assertNull(food.fiberG)
    }

    @Test
    fun roundTrip_withNutritionFields_preservesValues() {
        val foods = listOf(
            FoodEntry(
                id = "food-1",
                name = "鸡胸肉",
                quantity = "100g",
                estimatedCalories = 165,
                confidence = "high",
                carbohydratesG = 0.0f, // zero
                proteinG = 31.0f, // positive
                fatG = 3.6f,      // positive
                fiberG = null     // null
            ),
            FoodEntry(
                id = "food-2",
                name = "西兰花",
                quantity = "100g",
                estimatedCalories = 34,
                confidence = "high",
                carbohydratesG = 7.0f,
                proteinG = 2.8f,
                fatG = 0.4f,
                fiberG = 2.6f
            )
        )
        val meals = listOf(
            MealEntry(
                id = "meal-1",
                mealType = MealType.Lunch,
                foods = foods
            )
        )
        val domain = DailyRecord(
            id = "record-1",
            date = LocalDate.parse("2026-06-26"),
            status = RecordStatus.Confirmed,
            meals = meals,
            weightKg = 70.0f,
            aiSummary = "Summary"
        )

        val entity = mapper.toEntity(domain, "user-1")
        val parsedDomain = mapper.toDomain(entity)

        assertEquals(1, parsedDomain.meals.size)
        val parsedMeal = parsedDomain.meals[0]
        assertEquals(MealType.Lunch, parsedMeal.mealType)
        assertEquals(2, parsedMeal.foods.size)

        val chicken = parsedMeal.foods[0]
        assertEquals("鸡胸肉", chicken.name)
        assertEquals(0.0f, chicken.carbohydratesG)
        assertEquals(31.0f, chicken.proteinG)
        assertEquals(3.6f, chicken.fatG)
        assertNull(chicken.fiberG)

        val broccoli = parsedMeal.foods[1]
        assertEquals("西兰花", broccoli.name)
        assertEquals(7.0f, broccoli.carbohydratesG)
        assertEquals(2.8f, broccoli.proteinG)
        assertEquals(0.4f, broccoli.fatG)
        assertEquals(2.6f, broccoli.fiberG)
    }
}
