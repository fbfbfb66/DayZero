package com.example.ui.components.ai

import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionCapsuleCalculatorTest {
    @Test
    fun singleItemPositiveValues_summarizesAndCalculatesRatios() {
        val summary = NutritionCapsuleCalculator.calculate(
            meals = listOf(meal(item(carbs = 30f, protein = 20f, fat = 10f, fiber = 5f)))
        )

        assertNotNull(summary)
        summary!!
        assertEquals(25f, summary.netCarbohydratesG, 0.001f)
        assertEquals(20f, summary.proteinG, 0.001f)
        assertEquals(10f, summary.fatG, 0.001f)
        assertEquals(5f, summary.fiberG, 0.001f)
        assertEquals(1f, summary.components.sumOf { it.ratio.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun multipleMealsAndItems_summarizesAllItems() {
        val summary = NutritionCapsuleCalculator.calculate(
            meals = listOf(
                meal(item(carbs = 50f, protein = 10f, fat = 5f, fiber = 8f)),
                meal(
                    item(carbs = 20f, protein = 6f, fat = 2f, fiber = 3f),
                    item(carbs = 10f, protein = 4f, fat = 1f, fiber = 1f)
                )
            )
        )

        assertNotNull(summary)
        summary!!
        assertEquals(68f, summary.netCarbohydratesG, 0.001f)
        assertEquals(20f, summary.proteinG, 0.001f)
        assertEquals(8f, summary.fatG, 0.001f)
        assertEquals(12f, summary.fiberG, 0.001f)
    }

    @Test
    fun totalCarbohydratesIncludeFiber_netCarbsSubtractFiber() {
        val summary = NutritionCapsuleCalculator.calculate(
            listOf(meal(item(carbs = 85f, protein = 15f, fat = 22f, fiber = 6f)))
        )

        assertEquals(79f, summary!!.netCarbohydratesG, 0.001f)
    }

    @Test
    fun fiberGreaterThanCarbohydrates_clampsNetCarbsToZero() {
        val summary = NutritionCapsuleCalculator.calculate(
            listOf(meal(item(carbs = 2f, protein = 4f, fat = 1f, fiber = 6f)))
        )

        assertEquals(0f, summary!!.netCarbohydratesG, 0.001f)
        assertEquals(1f, summary.components.sumOf { it.ratio.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun anyNullNutritionField_hidesCapsule() {
        assertNull(NutritionCapsuleCalculator.calculate(listOf(meal(item(carbs = null)))))
    }

    @Test
    fun explicitZeroIsValidWhenTotalIsPositive() {
        val summary = NutritionCapsuleCalculator.calculate(
            listOf(meal(item(carbs = 0f, protein = 10f, fat = 0f, fiber = 0f)))
        )

        assertNotNull(summary)
        assertEquals(0f, summary!!.netCarbohydratesG, 0.001f)
        assertEquals(10f, summary.proteinG, 0.001f)
    }

    @Test
    fun allDisplayedComponentsZero_hidesCapsule() {
        assertNull(
            NutritionCapsuleCalculator.calculate(
                listOf(meal(item(carbs = 0f, protein = 0f, fat = 0f, fiber = 0f)))
            )
        )
    }

    @Test
    fun negativeNaNAndInfinity_hideCapsule() {
        assertNull(NutritionCapsuleCalculator.calculate(listOf(meal(item(carbs = -1f)))))
        assertNull(NutritionCapsuleCalculator.calculate(listOf(meal(item(protein = Float.NaN)))))
        assertNull(NutritionCapsuleCalculator.calculate(listOf(meal(item(fat = Float.POSITIVE_INFINITY)))))
        assertNull(NutritionCapsuleCalculator.calculate(listOf(meal(item(fiber = Float.NEGATIVE_INFINITY)))))
    }

    @Test
    fun emptyMealsOrItems_hideCapsule() {
        assertNull(NutritionCapsuleCalculator.calculate(emptyList()))
        assertNull(NutritionCapsuleCalculator.calculate(listOf(meal())))
    }

    @Test
    fun editingNameAmountOrCalories_invalidatesOnlyEditedItemNutrition() {
        val original = item(id = "target", name = "rice", amount = "1 bowl", calories = 300)
        val unchanged = item(id = "other", name = "egg", amount = "1", calories = 70)

        val changedName = NutritionCapsuleCalculator.applyFoodEdit(original, "brown rice", "1 bowl", 300)
        val changedAmount = NutritionCapsuleCalculator.applyFoodEdit(original, "rice", "2 bowls", 300)
        val changedCalories = NutritionCapsuleCalculator.applyFoodEdit(original, "rice", "1 bowl", 320)

        listOf(changedName, changedAmount, changedCalories).forEach { edited ->
            assertNull(edited.carbohydratesG)
            assertNull(edited.proteinG)
            assertNull(edited.fatG)
            assertNull(edited.fiberG)
        }
        assertEquals(30f, unchanged.carbohydratesG)
        assertEquals(20f, unchanged.proteinG)
    }

    @Test
    fun savingSameNameAmountAndCalories_preservesNutritionAndConfidence() {
        val original = item(name = "rice", amount = "1 bowl", calories = 300)

        val edited = NutritionCapsuleCalculator.applyFoodEdit(original, "rice", "1 bowl", 300)

        assertEquals(original.carbohydratesG, edited.carbohydratesG)
        assertEquals(original.proteinG, edited.proteinG)
        assertEquals(original.fatG, edited.fatG)
        assertEquals(original.fiberG, edited.fiberG)
        assertEquals(original.calorieConfidence, edited.calorieConfidence)
    }

    @Test
    fun mealTypeAndWeightChangesDoNotInvalidateNutrition() {
        val sourceMeal = meal(item())
        val changedMealType = sourceMeal.copy(mealType = "dinner", mealLabel = "Dinner")

        assertNotNull(NutritionCapsuleCalculator.calculate(listOf(changedMealType)))
        assertNotNull(NutritionCapsuleCalculator.calculate(listOf(sourceMeal)))
    }

    @Test
    fun newItemDefaultsNutritionToNull() {
        val item = NutritionCapsuleCalculator.newItem("tofu", "100g", 90)

        assertNull(item.carbohydratesG)
        assertNull(item.proteinG)
        assertNull(item.fatG)
        assertNull(item.fiberG)
    }

    @Test
    fun deletingIncompleteItemAllowsRemainingCompleteItemsToShow() {
        val complete = item(id = "complete")
        val incomplete = item(id = "incomplete", carbs = null)
        assertNull(NutritionCapsuleCalculator.calculate(listOf(meal(complete, incomplete))))

        val summary = NutritionCapsuleCalculator.calculate(listOf(meal(complete)))

        assertNotNull(summary)
    }

    private fun meal(vararg items: ConfirmCardItem): ConfirmCardMeal {
        return ConfirmCardMeal(
            mealType = "lunch",
            mealLabel = "Lunch",
            subtotalCalories = items.sumOf { it.calories },
            items = items.toList()
        )
    }

    private fun item(
        id: String? = "item",
        name: String = "rice",
        amount: String? = "1 bowl",
        calories: Int = 300,
        carbs: Float? = 30f,
        protein: Float? = 20f,
        fat: Float? = 10f,
        fiber: Float? = 5f
    ): ConfirmCardItem {
        return ConfirmCardItem(
            id = id,
            name = name,
            amountText = amount,
            calories = calories,
            calorieConfidence = "estimated",
            carbohydratesG = carbs,
            proteinG = protein,
            fatG = fat,
            fiberG = fiber
        )
    }
}
