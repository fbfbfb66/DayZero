package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.mapper.DailyRecordMapper
import com.example.data.sync.SyncQueueWriter
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.repository.ConfirmFoodCardResult
import com.example.domain.repository.FoodCardConfirmationRepository
import com.example.domain.usecase.ConfirmFoodRecordMerger
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class RoomFoodCardConfirmationRepository(
    private val database: DayZeroDatabase,
    private val identityProvider: CurrentIdentityProvider,
    private val syncQueueWriter: SyncQueueWriter = SyncQueueWriter(database.syncQueueDao()),
    private val chatSyncQueueWriter: ChatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao()),
    private val failureInjector: ConfirmFoodCardFailureInjector? = null
) : FoodCardConfirmationRepository {
    private val dailyRecordDao = database.dailyRecordDao()
    private val chatDao = database.aiChatMessageDao()
    private val conversationDao = database.conversationDao()
    private val dailyRecordMapper = DailyRecordMapper()

    override suspend fun confirmFoodCard(
        cardId: String,
        payloadSummary: PayloadSummary?
    ): ConfirmFoodCardResult {
        if (cardId.isBlank()) return ConfirmFoodCardResult.CardNotFound
        return database.withTransaction {
            val target = findTarget(cardId) ?: return@withTransaction ConfirmFoodCardResult.CardNotFound
            val cardState = target.confirmState()
            when (cardState) {
                ConfirmableState.AlreadyConfirmed -> return@withTransaction ConfirmFoodCardResult.AlreadyConfirmed
                ConfirmableState.CancelledOrBlocked -> return@withTransaction ConfirmFoodCardResult.Cancelled
                ConfirmableState.Pending -> Unit
            }

            val conversation = conversationDao.getConversationById(target.message.conversationId)
                ?: return@withTransaction ConfirmFoodCardResult.CardNotFound
            val recordDate = LocalDate.parse(conversation.conversationDate)
            val existingRecord = dailyRecordDao
                .getRecordByDateAndStatus(recordDate.toString(), RecordStatus.Confirmed.name)
                ?.let { dailyRecordMapper.toDomain(it) }
            val updatedRecord = ConfirmFoodRecordMerger.merge(
                currentRecord = existingRecord,
                recordDate = recordDate,
                payloadSummary = payloadSummary
            )
            val identity = identityProvider.currentIdentity()
            dailyRecordDao.upsertRecord(dailyRecordMapper.toEntity(updatedRecord, identity.localOwnerId))
            failureInjector?.afterDailyRecordUpsert()

            val updatedCardsJson = target.updatedCardsJson(
                newState = STATE_CONFIRMED,
                resolved = true,
                weightKg = payloadSummary?.weightKg,
                hasUpdatedWeightKg = payloadSummary != null,
                meals = payloadSummary?.meals
            )
            failureInjector?.afterCardJsonBuilt()

            syncQueueWriter.enqueueRecordUpsert(updatedRecord, identity)
            failureInjector?.afterBusinessQueueEnqueued()

            val now = System.currentTimeMillis()
            val updatedMessage = target.message.copy(
                assistantCardsJson = updatedCardsJson,
                updatedAt = now
            )
            val rows = chatDao.updateMessageContentIfActive(
                id = updatedMessage.id,
                text = updatedMessage.text,
                messageType = updatedMessage.messageType,
                contentJson = updatedMessage.contentJson,
                assistantCardsJson = updatedMessage.assistantCardsJson,
                suggestedRepliesJson = updatedMessage.suggestedRepliesJson,
                updatedAt = now
            )
            check(rows == 1) { "Card message disappeared during confirm card transaction" }

            conversationDao.updateConversationSummary(
                id = conversation.id,
                title = conversation.title,
                lastMessagePreview = conversation.lastMessagePreview,
                lastActivityAt = conversation.lastActivityAt,
                updatedAt = now
            )
            val updatedConversation = conversation.copy(updatedAt = now)
            chatSyncQueueWriter.enqueueConversationUpsert(updatedConversation, identity)
            chatSyncQueueWriter.enqueueMessageUpsert(updatedMessage, identity)

            ConfirmFoodCardResult.Confirmed(
                record = updatedRecord,
                conversationId = target.message.conversationId,
                messageId = target.message.id,
                cardId = cardId
            )
        }
    }

    override suspend fun cancelFoodCard(cardId: String): ConfirmFoodCardResult {
        if (cardId.isBlank()) return ConfirmFoodCardResult.CardNotFound
        return database.withTransaction {
            val target = findTarget(cardId) ?: return@withTransaction ConfirmFoodCardResult.CardNotFound
            val cardState = target.confirmState()
            when (cardState) {
                ConfirmableState.AlreadyConfirmed -> return@withTransaction ConfirmFoodCardResult.AlreadyConfirmed
                ConfirmableState.CancelledOrBlocked -> return@withTransaction ConfirmFoodCardResult.Cancelled
                ConfirmableState.Pending -> Unit
            }

            val conversation = conversationDao.getConversationById(target.message.conversationId)
                ?: return@withTransaction ConfirmFoodCardResult.CardNotFound
            val updatedCardsJson = target.updatedCardsJson(
                newState = STATE_CANCELLED,
                resolved = true,
                weightKg = null,
                hasUpdatedWeightKg = false,
                meals = null
            )
            val identity = identityProvider.currentIdentity()
            val now = System.currentTimeMillis()
            val updatedMessage = target.message.copy(
                assistantCardsJson = updatedCardsJson,
                updatedAt = now
            )
            val rows = chatDao.updateMessageContentIfActive(
                id = updatedMessage.id,
                text = updatedMessage.text,
                messageType = updatedMessage.messageType,
                contentJson = updatedMessage.contentJson,
                assistantCardsJson = updatedMessage.assistantCardsJson,
                suggestedRepliesJson = updatedMessage.suggestedRepliesJson,
                updatedAt = now
            )
            check(rows == 1) { "Card message disappeared during cancel card transaction" }
            conversationDao.updateConversationSummary(
                id = conversation.id,
                title = conversation.title,
                lastMessagePreview = conversation.lastMessagePreview,
                lastActivityAt = conversation.lastActivityAt,
                updatedAt = now
            )
            chatSyncQueueWriter.enqueueConversationUpsert(conversation.copy(updatedAt = now), identity)
            chatSyncQueueWriter.enqueueMessageUpsert(updatedMessage, identity)
            ConfirmFoodCardResult.Cancelled
        }
    }

    private suspend fun findTarget(cardId: String): CardTarget? {
        return chatDao.getMessagesWithCards().firstNotNullOfOrNull { message ->
            val raw = message.assistantCardsJson ?: return@firstNotNullOfOrNull null
            val cards = runCatching { JSONArray(raw) }.getOrNull() ?: return@firstNotNullOfOrNull null
            for (index in 0 until cards.length()) {
                val card = cards.optJSONObject(index) ?: continue
                if (card.optString(KEY_ID) == cardId && card.optString(KEY_TYPE) == TYPE_SHOW_CONFIRM_CARD) {
                    return@firstNotNullOfOrNull CardTarget(message, cards, index, card, null)
                }
                if (card.optString(KEY_TYPE) == TYPE_DATE_MISMATCH_GUARD_CARD) {
                    val original = card.optJSONObject(KEY_PENDING_ORIGINAL_CARD) ?: continue
                    if (original.optString(KEY_ID) == cardId && original.optString(KEY_TYPE) == TYPE_SHOW_CONFIRM_CARD) {
                        return@firstNotNullOfOrNull CardTarget(message, cards, index, original, card)
                    }
                }
            }
            null
        }
    }

    private data class CardTarget(
        val message: AiChatMessageEntity,
        val cards: JSONArray,
        val cardIndex: Int,
        val card: JSONObject,
        val guard: JSONObject?
    ) {
        fun confirmState(): ConfirmableState {
            val guardState = guard?.optString(KEY_STATE, STATE_PENDING)
            if (guardState != null && guardState != STATE_APPROVED) {
                return ConfirmableState.CancelledOrBlocked
            }
            return when (card.optString(KEY_STATE, STATE_PENDING)) {
                STATE_CONFIRMED -> ConfirmableState.AlreadyConfirmed
                STATE_CANCELLED -> ConfirmableState.CancelledOrBlocked
                else -> ConfirmableState.Pending
            }
        }

        fun updatedCardsJson(
            newState: String,
            resolved: Boolean,
            weightKg: Double?,
            hasUpdatedWeightKg: Boolean,
            meals: List<ConfirmCardMeal>?
        ): String {
            card.put(KEY_STATE, newState)
            card.put(KEY_RESOLVED, resolved)
            if (hasUpdatedWeightKg) {
                card.put(KEY_WEIGHT_KG, weightKg ?: JSONObject.NULL)
            }
            if (meals != null) {
                card.put(KEY_MEALS, meals.toMealsJsonArray())
            }
            guard?.put(KEY_PENDING_ORIGINAL_CARD, card)
            if (guard == null) {
                cards.put(cardIndex, card)
            } else {
                cards.put(cardIndex, guard)
            }
            return cards.toString()
        }
    }

    private enum class ConfirmableState {
        Pending,
        AlreadyConfirmed,
        CancelledOrBlocked
    }

    private companion object {
        private const val TYPE_SHOW_CONFIRM_CARD = "show_confirm_card"
        private const val TYPE_DATE_MISMATCH_GUARD_CARD = "date_mismatch_guard_card"
        private const val KEY_TYPE = "type"
        private const val KEY_ID = "id"
        private const val KEY_STATE = "state"
        private const val KEY_RESOLVED = "resolved"
        private const val KEY_WEIGHT_KG = "weightKg"
        private const val KEY_MEALS = "meals"
        private const val KEY_PENDING_ORIGINAL_CARD = "pendingOriginalCard"
        private const val STATE_PENDING = "pending"
        private const val STATE_APPROVED = "approved"
        private const val STATE_CONFIRMED = "confirmed"
        private const val STATE_CANCELLED = "cancelled"
    }
}

interface ConfirmFoodCardFailureInjector {
    fun afterDailyRecordUpsert() = Unit
    fun afterCardJsonBuilt() = Unit
    fun afterBusinessQueueEnqueued() = Unit
}

private fun List<ConfirmCardMeal>.toMealsJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { meal -> array.put(meal.toJson()) }
    return array
}

private fun ConfirmCardMeal.toJson(): JSONObject {
    return JSONObject()
        .put("mealType", mealType)
        .put("mealLabel", mealLabel ?: JSONObject.NULL)
        .put("subtotalCalories", subtotalCalories ?: JSONObject.NULL)
        .put("items", items.toItemsJsonArray())
}

private fun List<ConfirmCardItem>.toItemsJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { item -> array.put(item.toJson()) }
    return array
}

private fun ConfirmCardItem.toJson(): JSONObject {
    return JSONObject()
        .put("id", id ?: JSONObject.NULL)
        .put("name", name)
        .put("amountText", amountText ?: JSONObject.NULL)
        .put("calories", calories)
        .put("calorieConfidence", calorieConfidence)
        .put("carbohydratesG", carbohydratesG?.toDouble() ?: JSONObject.NULL)
        .put("proteinG", proteinG?.toDouble() ?: JSONObject.NULL)
        .put("fatG", fatG?.toDouble() ?: JSONObject.NULL)
        .put("fiberG", fiberG?.toDouble() ?: JSONObject.NULL)
}
