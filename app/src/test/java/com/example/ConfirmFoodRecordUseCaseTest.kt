package com.example

import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.repository.RecordRepository
import com.example.domain.usecase.ConfirmFoodRecordUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ConfirmFoodRecordUseCaseTest {

    private val recordRepository = InMemoryRecordRepository()
    private val useCase = ConfirmFoodRecordUseCase(recordRepository)

    @Test
    fun invoke_mapsNutritionFieldsFromPayloadToFoodEntries() = runTest {
        val currentDate = LocalDate.of(2026, 6, 26)
        val payload = PayloadSummary(
            originalText = "吃了鸡胸肉",
            weightKg = 70.0,
            meals = listOf(
                ConfirmCardMeal(
                    mealType = "lunch",
                    mealLabel = "午餐",
                    subtotalCalories = 165,
                    items = listOf(
                        ConfirmCardItem(
                            id = "item-1",
                            name = "鸡胸肉",
                            amountText = "100g",
                            calories = 165,
                            calorieConfidence = "high",
                            carbohydratesG = 0.0f,
                            proteinG = 31.0f,
                            fatG = 3.6f,
                            fiberG = null
                        )
                    )
                )
            )
        )

        val updatedRecord = useCase(currentDate, payload)

        // Verify the record in DB contains the food with nutrition
        val saved = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
        assertEquals(updatedRecord, saved)
        assertEquals(1, saved?.meals?.size)
        val meal = saved!!.meals[0]
        assertEquals(MealType.Lunch, meal.mealType)
        assertEquals(1, meal.foods.size)
        val food = meal.foods[0]
        assertEquals("鸡胸肉", food.name)
        assertEquals(0.0f, food.carbohydratesG)
        assertEquals(31.0f, food.proteinG)
        assertEquals(3.6f, food.fatG)
        assertNull(food.fiberG)
    }

    @Test
    fun invoke_usesEditedNullNutritionFieldsFromPayload() = runTest {
        val currentDate = LocalDate.of(2026, 6, 26)
        val payload = PayloadSummary(
            originalText = "edited rice",
            weightKg = null,
            meals = listOf(
                ConfirmCardMeal(
                    mealType = "lunch",
                    mealLabel = "Lunch",
                    subtotalCalories = 320,
                    items = listOf(
                        ConfirmCardItem(
                            id = "item-1",
                            name = "brown rice",
                            amountText = "2 bowls",
                            calories = 320,
                            calorieConfidence = "user_edited",
                            carbohydratesG = null,
                            proteinG = null,
                            fatG = null,
                            fiberG = null
                        )
                    )
                )
            )
        )

        val updatedRecord = useCase(currentDate, payload)
        val food = updatedRecord.meals.single().foods.single()

        assertEquals("brown rice", food.name)
        assertEquals("2 bowls", food.quantity)
        assertNull(food.carbohydratesG)
        assertNull(food.proteinG)
        assertNull(food.fatG)
        assertNull(food.fiberG)
    }

    private class InMemoryRecordRepository : RecordRepository {
        private val _records = MutableStateFlow<List<DailyRecord>>(emptyList())
        val records = _records.asStateFlow()

        override fun observeRecords(): Flow<List<DailyRecord>> = records

        override suspend fun upsertRecord(record: DailyRecord) {
            _records.update { current -> current.filterNot { it.id == record.id } + record }
        }

        override suspend fun deleteRecordById(recordId: String) = Unit

        override suspend fun getRecordById(recordId: String): DailyRecord? = records.value.find { it.id == recordId }

        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? {
            return records.value.find { it.date == date && it.status == status }
        }

        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) = Unit

        override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) = Unit

        override suspend fun clearAllRecords() {
            _records.value = emptyList()
        }
    }
}
