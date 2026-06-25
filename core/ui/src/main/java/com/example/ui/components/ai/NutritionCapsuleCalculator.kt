package com.example.ui.components.ai

import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import kotlin.math.max

data class NutritionCapsuleSummary(
    val netCarbohydratesG: Float,
    val proteinG: Float,
    val fatG: Float,
    val fiberG: Float,
    val components: List<NutritionCapsuleComponent>
)

data class NutritionCapsuleComponent(
    val kind: NutritionCapsuleComponentKind,
    val label: String,
    val grams: Float,
    val ratio: Float
)

enum class NutritionCapsuleComponentKind {
    NetCarbs,
    Protein,
    Fat,
    Fiber
}

object NutritionCapsuleCalculator {
    fun calculate(meals: List<ConfirmCardMeal>): NutritionCapsuleSummary? {
        val items = meals.flatMap { it.items }
        if (items.isEmpty()) return null
        if (items.any { !it.hasCompleteFiniteNutrition() }) return null

        val totalCarbohydrates = items.sumOfFloat { it.carbohydratesG ?: 0f }
        val totalProtein = items.sumOfFloat { it.proteinG ?: 0f }
        val totalFat = items.sumOfFloat { it.fatG ?: 0f }
        val totalFiber = items.sumOfFloat { it.fiberG ?: 0f }
        val netCarbohydrates = max(totalCarbohydrates - totalFiber, 0f)
        val denominator = netCarbohydrates + totalProtein + totalFat + totalFiber
        if (!denominator.isFinite() || denominator <= 0f) return null

        val components = listOf(
            NutritionCapsuleComponent(
                kind = NutritionCapsuleComponentKind.NetCarbs,
                label = "净碳水",
                grams = netCarbohydrates,
                ratio = netCarbohydrates / denominator
            ),
            NutritionCapsuleComponent(
                kind = NutritionCapsuleComponentKind.Protein,
                label = "蛋白质",
                grams = totalProtein,
                ratio = totalProtein / denominator
            ),
            NutritionCapsuleComponent(
                kind = NutritionCapsuleComponentKind.Fat,
                label = "脂肪",
                grams = totalFat,
                ratio = totalFat / denominator
            ),
            NutritionCapsuleComponent(
                kind = NutritionCapsuleComponentKind.Fiber,
                label = "膳食纤维",
                grams = totalFiber,
                ratio = totalFiber / denominator
            )
        )

        return NutritionCapsuleSummary(
            netCarbohydratesG = netCarbohydrates,
            proteinG = totalProtein,
            fatG = totalFat,
            fiberG = totalFiber,
            components = components
        )
    }

    fun applyFoodEdit(
        item: ConfirmCardItem,
        name: String,
        amountText: String?,
        calories: Int
    ): ConfirmCardItem {
        val changed = item.name != name ||
            item.amountText != amountText ||
            item.calories != calories

        return item.copy(
            name = name,
            amountText = amountText,
            calories = calories,
            calorieConfidence = if (changed) "user_edited" else item.calorieConfidence,
            carbohydratesG = if (changed) null else item.carbohydratesG,
            proteinG = if (changed) null else item.proteinG,
            fatG = if (changed) null else item.fatG,
            fiberG = if (changed) null else item.fiberG
        )
    }

    fun newItem(
        name: String,
        amountText: String?,
        calories: Int
    ): ConfirmCardItem {
        return ConfirmCardItem(
            name = name,
            amountText = amountText,
            calories = calories,
            calorieConfidence = "user_edited",
            carbohydratesG = null,
            proteinG = null,
            fatG = null,
            fiberG = null
        )
    }
}

private fun ConfirmCardItem.hasCompleteFiniteNutrition(): Boolean {
    return isValidNutritionNumber(carbohydratesG) &&
        isValidNutritionNumber(proteinG) &&
        isValidNutritionNumber(fatG) &&
        (fiberG == null || isValidNutritionNumber(fiberG))
}

private fun isValidNutritionNumber(value: Float?): Boolean {
    return value != null && value.isFinite() && value >= 0f
}

private inline fun Iterable<ConfirmCardItem>.sumOfFloat(selector: (ConfirmCardItem) -> Float): Float {
    var sum = 0f
    for (item in this) {
        sum += selector(item)
    }
    return sum
}
