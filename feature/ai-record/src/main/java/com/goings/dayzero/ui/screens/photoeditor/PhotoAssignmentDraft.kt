package com.goings.dayzero.ui.screens.photoeditor

import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal
import com.goings.dayzero.domain.repository.MealPhotoAssignment

/**
 * Local edit snapshot for one confirmation card's meal photo assignments.
 *
 * All editor interactions mutate only this immutable value; nothing is written
 * to Room until the user explicitly saves. Invariants:
 * - every assigned id belongs to [originMediaIds];
 * - one photo belongs to at most one meal;
 * - per-meal order is preserved;
 * - unassigned photos are allowed and keep their origin order.
 */
data class PhotoAssignmentDraft(
    val cardId: String,
    val mealCount: Int,
    val originMediaIds: List<String>,
    val assignments: Map<Int, List<String>>
) {
    val assignedIds: Set<String> get() = assignments.values.flatten().toSet()

    val unassignedIds: List<String> get() = originMediaIds.filterNot { it in assignedIds }

    fun mealOf(mediaId: String): Int? =
        assignments.entries.firstOrNull { mediaId in it.value }?.key

    fun assignedTo(mealIndex: Int): List<String> = assignments[mealIndex].orEmpty()

    /**
     * Assigns [mediaId] to [mealIndex], appending at the end of that meal and
     * removing it from any other meal. No-op for unknown ids, invalid meal
     * indices, or when the photo is already in the target meal.
     */
    fun assignToMeal(mediaId: String, mealIndex: Int): PhotoAssignmentDraft {
        if (mediaId !in originMediaIds) return this
        if (mealIndex !in 0 until mealCount) return this
        if (mediaId in assignedTo(mealIndex)) return this
        val cleared = assignments.mapValues { (_, ids) -> ids.filterNot { it == mediaId } }
        val updated = cleared + (mealIndex to (cleared[mealIndex].orEmpty() + mediaId))
        return copy(assignments = updated)
    }

    /** Moves [mediaId] back to the unassigned pool. No-op if not assigned. */
    fun removeFromMeal(mediaId: String): PhotoAssignmentDraft {
        if (mealOf(mediaId) == null) return this
        return copy(assignments = assignments.mapValues { (_, ids) -> ids.filterNot { it == mediaId } })
    }

    fun isValid(): Boolean {
        val all = assignments.values.flatten()
        return assignments.keys.all { it in 0 until mealCount } &&
            all.all { it in originMediaIds } &&
            all.distinct().size == all.size
    }

    /** Full per-meal assignment list covering every meal index of the card. */
    fun toMealPhotoAssignments(): List<MealPhotoAssignment> =
        (0 until mealCount).map { index ->
            MealPhotoAssignment(mealIndex = index, orderedMediaIds = assignedTo(index))
        }

    companion object {
        const val MAX_ORIGIN_PHOTOS = 6

        /**
         * True when a card's origin image user message carries a legal photo set
         * (1..6 distinct non-blank ids) — the precondition for showing the edit
         * entry and opening the editor.
         */
        fun isLegalOriginSet(originMediaIds: List<String>): Boolean {
            val cleaned = originMediaIds.map(String::trim)
            return cleaned.size in 1..MAX_ORIGIN_PHOTOS &&
                cleaned.none(String::isEmpty) &&
                cleaned.distinct().size == cleaned.size
        }

        /**
         * Builds the initial snapshot from the card's current meals. Ids outside
         * the legal origin set or already claimed by an earlier meal are dropped
         * defensively (persisted cards are already normalized to these rules).
         */
        fun fromMeals(
            cardId: String,
            meals: List<ConfirmCardMeal>,
            originMediaIds: List<String>
        ): PhotoAssignmentDraft {
            val origin = originMediaIds.map(String::trim).filter(String::isNotEmpty).distinct()
            val claimed = mutableSetOf<String>()
            val assignments = meals.mapIndexed { index, meal ->
                index to meal.sourceMediaIds.orEmpty().filter { it in origin && claimed.add(it) }
            }.toMap()
            return PhotoAssignmentDraft(
                cardId = cardId,
                mealCount = meals.size,
                originMediaIds = origin,
                assignments = assignments
            )
        }
    }
}
