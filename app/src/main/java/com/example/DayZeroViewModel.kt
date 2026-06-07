package com.example

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.UUID

enum class RecordStatus {
    Draft, Confirmed
}

enum class MealType(val displayName: String) {
    Breakfast("早餐"),
    Lunch("午餐"),
    Dinner("晚餐"),
    Snack("加餐")
}

data class FoodEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val quantity: String,
    val estimatedCalories: Int,
    val confidence: String = "high"
)

data class MealEntry(
    val mealType: MealType,
    val hasPhoto: Boolean = false,
    val foods: List<FoodEntry> = emptyList()
) {
    val mealCalories: Int
        get() = foods.sumOf { it.estimatedCalories }
}

data class DailyRecord(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val status: RecordStatus,
    val meals: List<MealEntry>,
    val weightKg: Float? = null,
    val aiSummary: String = ""
) {
    val totalCalories: Int
        get() = meals.sumOf { it.mealCalories }
}

data class AppState(
    val currentDate: LocalDate = LocalDate.of(2026, 6, 7), // Using mock date requested
    val records: List<DailyRecord> = emptyList(),
    val isAnalyzing: Boolean = false,
    val aiMessage: String? = null
)

class DayZeroViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppState())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    init {
        loadMockData()
    }

    private fun loadMockData() {
        // Prepare some confirmed past records for trends
        val pastRecords = listOf(
            DailyRecord(
                date = LocalDate.of(2026, 6, 1),
                status = RecordStatus.Confirmed,
                meals = emptyList(),
                weightKg = 65.5f,
                aiSummary = "好的开端！"
            ),
            DailyRecord(
                date = LocalDate.of(2026, 6, 2),
                status = RecordStatus.Confirmed,
                meals = emptyList(),
                weightKg = 65.2f,
                aiSummary = "继续保持！"
            ),
            DailyRecord(
                date = LocalDate.of(2026, 6, 3),
                status = RecordStatus.Confirmed,
                meals = emptyList(),
                weightKg = 65.0f,
                aiSummary = "非常棒的记录！"
            ),
            DailyRecord(
                date = LocalDate.of(2026, 6, 4),
                status = RecordStatus.Confirmed,
                meals = emptyList(),
                weightKg = 64.9f,
                aiSummary = "今天表现不错！"
            ),
            DailyRecord(
                date = LocalDate.of(2026, 6, 5),
                status = RecordStatus.Confirmed,
                meals = emptyList(),
                weightKg = 64.8f,
                aiSummary = "周末也不要放松哦！"
            ),
            DailyRecord(
                date = LocalDate.of(2026, 6, 6),
                status = RecordStatus.Confirmed,
                meals = listOf(
                    MealEntry(
                        mealType = MealType.Breakfast,
                        foods = listOf(
                            FoodEntry(name = "燕麦片", quantity = "1碗", estimatedCalories = 150),
                            FoodEntry(name = "牛奶", quantity = "1杯", estimatedCalories = 120)
                        )
                    ),
                    MealEntry(
                        mealType = MealType.Lunch,
                        foods = listOf(
                            FoodEntry(name = "鸡胸肉沙拉", quantity = "1份", estimatedCalories = 350)
                        )
                    )
                ),
                weightKg = 64.6f,
                aiSummary = "继续保持！"
            )
        )

        // The specific mock AI draft for today
        val mockDraft = DailyRecord(
            date = LocalDate.of(2026, 6, 7),
            status = RecordStatus.Draft,
            meals = listOf(
                MealEntry(
                    mealType = MealType.Breakfast,
                    hasPhoto = false,
                    foods = listOf(
                        FoodEntry(name = "肉包子", quantity = "2个", estimatedCalories = 374, confidence = "medium"),
                        FoodEntry(name = "香蕉", quantity = "1根", estimatedCalories = 105, confidence = "high")
                    )
                ),
                MealEntry(
                    mealType = MealType.Lunch,
                    hasPhoto = false,
                    foods = listOf(
                        FoodEntry(name = "猪肉肠粉", quantity = "1碗", estimatedCalories = 450, confidence = "medium")
                    )
                )
            ),
            weightKg = null,
            aiSummary = "今天目前记录的热量不算高，但蛋白质可能略少，晚餐可以补充一些优质蛋白。"
        )

        _uiState.update { 
            it.copy(
                records = pastRecords + mockDraft
            )
        }
    }

    // Add this to make the total calories from mock data exact if needed
    // In our model we recalculate it dynamically. Let's ensure property names don't clash.

    fun confirmDraft(recordId: String, newWeight: Float?) {
        _uiState.update { state ->
            val updatedRecords = state.records.map {
                if (it.id == recordId) {
                    it.copy(status = RecordStatus.Confirmed, weightKg = newWeight ?: it.weightKg)
                } else {
                    it
                }
            }
            state.copy(
                records = updatedRecords,
                aiMessage = "成功确认！今日记录已更新。"
            )
        }
    }

    fun removeFood(recordId: String, mealType: MealType, foodId: String) {
        _uiState.update { state ->
            val updatedRecords = state.records.map { record ->
                if (record.id == recordId && record.status == RecordStatus.Draft) {
                    val updatedMeals = record.meals.map { meal ->
                        if (meal.mealType == mealType) {
                            meal.copy(foods = meal.foods.filter { it.id != foodId })
                        } else {
                            meal
                        }
                    }
                    record.copy(meals = updatedMeals)
                } else {
                    record
                }
            }
            state.copy(records = updatedRecords)
        }
    }
}
