package com.goings.dayzero.data.repository

import androidx.room.withTransaction
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.AiChatMessageEntity
import com.goings.dayzero.data.sync.chat.ChatSyncQueueWriter
import com.goings.dayzero.domain.identity.CurrentIdentityProvider
import com.goings.dayzero.domain.model.ai.assistant.assistantPlaceholderId
import com.goings.dayzero.domain.repository.FoodCardPhotoAssignmentRepository
import com.goings.dayzero.domain.repository.MealPhotoAssignment
import com.goings.dayzero.domain.repository.UpdateFoodCardPhotoAssignmentsResult
import org.json.JSONArray
import org.json.JSONObject

class RoomFoodCardPhotoAssignmentRepository(
    private val database: DayZeroDatabase,
    private val identityProvider: CurrentIdentityProvider,
    private val chatSyncQueueWriter: ChatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao()),
    private val failureInjector: FoodCardPhotoAssignmentFailureInjector? = null
) : FoodCardPhotoAssignmentRepository {
    private val chatDao = database.aiChatMessageDao()

    override suspend fun updatePhotoAssignments(
        cardId: String,
        assignments: List<MealPhotoAssignment>
    ): UpdateFoodCardPhotoAssignmentsResult {
        if (cardId.isBlank()) return UpdateFoodCardPhotoAssignmentsResult.CardNotFound
        val identity = identityProvider.currentIdentity()
        return database.withTransaction {
            val target = findTarget(cardId) ?: return@withTransaction UpdateFoodCardPhotoAssignmentsResult.CardNotFound
            if (!target.isEditable()) return@withTransaction UpdateFoodCardPhotoAssignmentsResult.NotEditable
            val allowedIds = findSourceMediaIds(target.message)
                ?: return@withTransaction UpdateFoodCardPhotoAssignmentsResult.InvalidAssignments
            if (!assignments.areValid(target.meals.length(), allowedIds)) {
                return@withTransaction UpdateFoodCardPhotoAssignmentsResult.InvalidAssignments
            }

            var changed = false
            assignments.forEach { assignment ->
                val meal = target.meals.getJSONObject(assignment.mealIndex)
                val next = JSONArray(assignment.orderedMediaIds)
                val current = meal.optJSONArray(KEY_SOURCE_MEDIA_IDS)
                if (current == null || !jsonArraysEqual(current, next)) {
                    meal.put(KEY_SOURCE_MEDIA_IDS, next)
                    changed = true
                }
            }
            if (!changed) return@withTransaction UpdateFoodCardPhotoAssignmentsResult.Unchanged

            target.card.put(KEY_MEALS, target.meals)
            target.guard?.put(KEY_PENDING_ORIGINAL_CARD, target.card)
            target.cards.put(target.cardIndex, target.guard ?: target.card)
            val now = System.currentTimeMillis()
            val updated = target.message.copy(assistantCardsJson = target.cards.toString(), updatedAt = now)
            failureInjector?.beforeMessageUpdate()
            check(chatDao.updateMessageContentIfActive(
                id = updated.id,
                text = updated.text,
                messageType = updated.messageType,
                contentJson = updated.contentJson,
                assistantCardsJson = updated.assistantCardsJson,
                suggestedRepliesJson = updated.suggestedRepliesJson,
                updatedAt = now
            ) == 1) { "Card message disappeared during photo assignment update" }
            failureInjector?.beforeQueueEnqueue()
            chatSyncQueueWriter.enqueueMessageUpsert(updated, identity)
            UpdateFoodCardPhotoAssignmentsResult.Updated
        }
    }

    private suspend fun findTarget(cardId: String): Target? =
        chatDao.getMessagesWithCards().firstNotNullOfOrNull { message ->
            val cards = runCatching { JSONArray(message.assistantCardsJson) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            for (index in 0 until cards.length()) {
                val outer = cards.optJSONObject(index) ?: continue
                val guard = outer.takeIf { it.optString(KEY_TYPE) == TYPE_GUARD }
                val card = guard?.optJSONObject(KEY_PENDING_ORIGINAL_CARD) ?: outer
                if (card.optString(KEY_TYPE) == TYPE_CONFIRM && card.optString(KEY_ID) == cardId) {
                    val meals = card.optJSONArray(KEY_MEALS) ?: return@firstNotNullOfOrNull null
                    return@firstNotNullOfOrNull Target(message, cards, index, card, guard, meals)
                }
            }
            null
        }

    private suspend fun findSourceMediaIds(assistantMessage: AiChatMessageEntity): List<String>? {
        val user = chatDao.getMessagesByConversationId(assistantMessage.conversationId)
            .firstOrNull { it.role.equals("User", true) && assistantPlaceholderId(it.id) == assistantMessage.id }
            ?: return null
        val content = runCatching { JSONObject(user.contentJson ?: return null) }.getOrNull() ?: return null
        val ids = content.optJSONObject("media")?.optJSONArray(KEY_SOURCE_MEDIA_IDS) ?: return null
        val result = (0 until ids.length()).mapNotNull { ids.optString(it).trim().takeIf(String::isNotEmpty) }
        return result.takeIf { it.size in 1..6 && it.distinct().size == it.size }
    }

    private fun List<MealPhotoAssignment>.areValid(mealCount: Int, allowedIds: List<String>): Boolean {
        val indices = map { it.mealIndex }
        if (indices.distinct().size != size || indices.any { it !in 0 until mealCount }) return false
        val allIds = flatMap { it.orderedMediaIds }
        return allIds.none(String::isBlank) && allIds.distinct().size == allIds.size && allIds.all { it in allowedIds }
    }

    private data class Target(
        val message: AiChatMessageEntity,
        val cards: JSONArray,
        val cardIndex: Int,
        val card: JSONObject,
        val guard: JSONObject?,
        val meals: JSONArray
    ) {
        fun isEditable(): Boolean =
            card.optString(KEY_STATE, STATE_PENDING) == STATE_PENDING &&
                (guard == null || guard.optString(KEY_STATE, STATE_PENDING) == STATE_APPROVED)
    }

    private fun jsonArraysEqual(a: JSONArray, b: JSONArray): Boolean =
        a.length() == b.length() && (0 until a.length()).all { a.optString(it) == b.optString(it) }

    private companion object {
        const val TYPE_CONFIRM = "show_confirm_card"
        const val TYPE_GUARD = "date_mismatch_guard_card"
        const val KEY_TYPE = "type"
        const val KEY_ID = "id"
        const val KEY_STATE = "state"
        const val KEY_MEALS = "meals"
        const val KEY_SOURCE_MEDIA_IDS = "sourceMediaIds"
        const val KEY_PENDING_ORIGINAL_CARD = "pendingOriginalCard"
        const val STATE_PENDING = "pending"
        const val STATE_APPROVED = "approved"
    }
}

interface FoodCardPhotoAssignmentFailureInjector {
    fun beforeMessageUpdate() = Unit
    fun beforeQueueEnqueue() = Unit
}
