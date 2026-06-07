package com.example.data.repository

import com.example.domain.model.MealType
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.DraftFood
import com.example.domain.model.ai.DraftMeal
import com.example.domain.repository.AiDraftRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeAiDraftRepository : AiDraftRepository {
    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())

    override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft {
        delay(1500)

        val text = request.text
        val foods = mutableListOf<DraftFood>()

        if (text.contains("包子")) {
            foods.add(DraftFood(name = "肉包子", quantity = "2个", estimatedCalories = 374, confidence = "medium"))
        }
        if (text.contains("香蕉")) {
            foods.add(DraftFood(name = "香蕉", quantity = "1根", estimatedCalories = 105, confidence = "high"))
        }
        if (text.contains("肠粉")) {
            foods.add(DraftFood(name = "猪肉肠粉", quantity = "1碗", estimatedCalories = 450, confidence = "medium"))
        }
        if (text.contains("米粉")) {
            foods.add(DraftFood(name = "炒米粉", quantity = "1份", estimatedCalories = 550, confidence = "medium"))
        }
        if (text.contains("鸡腿")) {
            foods.add(DraftFood(name = "鸡腿饭", quantity = "1份", estimatedCalories = 650, confidence = "medium"))
        }

        if (foods.isEmpty()) {
            foods.add(DraftFood(name = "未识别食物", quantity = "1份", estimatedCalories = 500, confidence = "low"))
        }

        val mealType = if (text.contains("早")) MealType.Breakfast else MealType.Lunch
        
        val draftMeal = DraftMeal(
            mealType = mealType,
            displayName = mealType.displayName,
            foods = foods,
            mealCalories = foods.sumOf { it.estimatedCalories }
        )

        return CheckinDraft(
            date = request.date,
            meals = listOf(draftMeal),
            totalCalories = draftMeal.mealCalories,
            weightKg = request.weightKg,
            aiSummary = "这是根据你的描述生成的本地演示估算，你可以修改后再确认。",
            sourceText = text
        )
    }

    override fun observeChatMessages(): Flow<List<AiChatMessage>> = _messages.asStateFlow()

    override suspend fun insertChatMessage(message: AiChatMessage) {
        _messages.update { it + message }
    }

    override suspend fun clearChatMessages() {
        _messages.update { emptyList() }
    }
}
