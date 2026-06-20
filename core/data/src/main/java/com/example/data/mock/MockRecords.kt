package com.example.data.mock

import com.example.domain.model.DailyRecord
import com.example.domain.model.FoodEntry
import com.example.domain.model.MealEntry
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import java.time.LocalDate

fun createMockRecords(): List<DailyRecord> {
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

    return pastRecords + mockDraft
}
