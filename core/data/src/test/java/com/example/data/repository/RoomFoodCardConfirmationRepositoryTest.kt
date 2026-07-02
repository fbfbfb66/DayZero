package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.sync.DayZeroSyncConstants
import com.example.data.sync.SyncQueueWriter
import com.example.data.sync.chat.ChatSyncQueueContract
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.repository.ConfirmFoodCardResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RoomFoodCardConfirmationRepositoryTest {
    private lateinit var database: DayZeroDatabase
    private lateinit var repository: RoomFoodCardConfirmationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = createRepository()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun confirmPendingCardWritesRecordUpdatesCardAndEnqueuesBothQueues() = runTest {
        insertConversation()
        insertCardMessage(showConfirmCardJson("card-1", state = "pending"))

        val result = repository.confirmFoodCard("card-1", payload())

        assertTrue(result is ConfirmFoodCardResult.Confirmed)
        val record = database.dailyRecordDao()
            .getRecordByDateAndStatus("2026-06-18", RecordStatus.Confirmed.name)
            ?.let { com.example.data.local.mapper.DailyRecordMapper().toDomain(it) }
        require(record != null)
        assertEquals(LocalDate.of(2026, 6, 18), record.date)
        assertEquals(72.5f, record.weightKg)
        assertEquals(2, record.meals.size)
        assertEquals(MealType.Lunch, record.meals.first().mealType)
        val rice = record.meals.first().foods.single()
        assertEquals("rice", rice.name)
        assertEquals(85f, rice.carbohydratesG)
        assertEquals(15f, rice.proteinG)
        assertEquals(22f, rice.fatG)
        assertEquals(6f, rice.fiberG)

        val storedCard = storedCard()
        assertEquals("confirmed", storedCard.getString("state"))
        assertEquals("keep-me", storedCard.getString("unknownField"))
        assertEquals(72.5, storedCard.getDouble("weightKg"), 0.001)

        val tasks = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING)
        assertEquals(8, tasks.size)
        assertTrue(tasks.any { it.operation == DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD })
        assertEquals(2, tasks.count { it.operation == DayZeroSyncConstants.OP_UPSERT_MEAL })
        assertEquals(2, tasks.count { it.operation == DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY })
        assertTrue(tasks.any { it.operation == DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD })
        assertTrue(tasks.any { it.operation == ChatSyncQueueContract.OP_UPSERT_CONVERSATION })
        assertTrue(tasks.any { it.operation == ChatSyncQueueContract.OP_UPSERT_MESSAGE })
    }

    @Test
    fun confirmingSameCardTwiceIsNoOpSecondTime() = runTest {
        insertConversation()
        insertCardMessage(showConfirmCardJson("card-1", state = "pending"))

        val first = repository.confirmFoodCard("card-1", payload())
        val taskCountAfterFirst = database.syncQueueDao().getPendingCount()
        val second = repository.confirmFoodCard("card-1", payload())

        assertTrue(first is ConfirmFoodCardResult.Confirmed)
        assertEquals(ConfirmFoodCardResult.AlreadyConfirmed, second)
        assertEquals(taskCountAfterFirst, database.syncQueueDao().getPendingCount())
        val record = savedRecord()
        assertEquals(1, record.meals.first { it.mealType == MealType.Lunch }.foods.size)
        assertEquals(1, record.meals.first { it.mealType == MealType.Snack }.foods.size)
    }

    @Test
    fun concurrentDoubleConfirmOnlyWritesOnce() = runTest {
        insertConversation()
        insertCardMessage(showConfirmCardJson("card-1", state = "pending"))

        val first = async { withContext(Dispatchers.IO) { repository.confirmFoodCard("card-1", payload()) } }
        val second = async { withContext(Dispatchers.IO) { repository.confirmFoodCard("card-1", payload()) } }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it is ConfirmFoodCardResult.Confirmed })
        assertEquals(1, results.count { it == ConfirmFoodCardResult.AlreadyConfirmed })
        val record = savedRecord()
        assertEquals(1, record.meals.first { it.mealType == MealType.Lunch }.foods.size)
        assertEquals("confirmed", storedCard().getString("state"))
    }

    @Test
    fun rollbackAfterDailyRecordUpsertLeavesNoPartialWrites() = runTest {
        assertRollback(
            object : ConfirmFoodCardFailureInjector {
                override fun afterDailyRecordUpsert() {
                    error("after_daily_record")
                }
            }
        )
    }

    @Test
    fun rollbackAfterCardJsonBuiltLeavesNoPartialWrites() = runTest {
        assertRollback(
            object : ConfirmFoodCardFailureInjector {
                override fun afterCardJsonBuilt() {
                    error("after_card_json")
                }
            }
        )
    }

    @Test
    fun rollbackAfterBusinessQueueLeavesNoPartialWrites() = runTest {
        assertRollback(
            object : ConfirmFoodCardFailureInjector {
                override fun afterBusinessQueueEnqueued() {
                    error("after_business_queue")
                }
            }
        )
    }

    @Test
    fun cardTerminalAndGuardStatesGateConfirmation() = runTest {
        insertConversation()
        insertCardMessage(showConfirmCardJson("confirmed-card", state = "confirmed"), messageId = "confirmed-message")
        insertCardMessage(showConfirmCardJson("cancelled-card", state = "cancelled"), messageId = "cancelled-message")
        insertCardMessage(guardCardJson("guard-pending", "guard-pending-card", guardState = "pending"), messageId = "guard-pending-message")
        insertCardMessage(guardCardJson("guard-cancelled", "guard-cancelled-card", guardState = "cancelled"), messageId = "guard-cancelled-message")
        insertCardMessage(guardCardJson("guard-approved", "guard-approved-card", guardState = "approved"), messageId = "guard-approved-message")

        assertEquals(ConfirmFoodCardResult.AlreadyConfirmed, repository.confirmFoodCard("confirmed-card", payload()))
        assertEquals(ConfirmFoodCardResult.Cancelled, repository.confirmFoodCard("cancelled-card", payload()))
        assertEquals(ConfirmFoodCardResult.Cancelled, repository.confirmFoodCard("guard-pending-card", payload()))
        assertEquals(ConfirmFoodCardResult.Cancelled, repository.confirmFoodCard("guard-cancelled-card", payload()))
        assertTrue(repository.confirmFoodCard("guard-approved-card", payload()) is ConfirmFoodCardResult.Confirmed)
        assertEquals(ConfirmFoodCardResult.CardNotFound, repository.confirmFoodCard("missing-card", payload()))

        val record = savedRecord()
        assertEquals(1, record.meals.first { it.mealType == MealType.Lunch }.foods.size)
        val guard = storedCard(messageId = "guard-approved-message")
        assertEquals("approved", guard.getString("state"))
        assertEquals("confirmed", guard.getJSONObject("pendingOriginalCard").getString("state"))
    }

    @Test
    fun differentCardsWithSameMealTypeEachAppendOnce() = runTest {
        insertConversation()
        insertCardMessage(showConfirmCardJson("card-1", state = "pending"), messageId = "message-1")
        insertCardMessage(showConfirmCardJson("card-2", state = "pending"), messageId = "message-2")

        repository.confirmFoodCard("card-1", payload(foodName = "rice"))
        repository.confirmFoodCard("card-2", payload(foodName = "noodles"))

        val lunchFoods = savedRecord().meals.first { it.mealType == MealType.Lunch }.foods
        assertEquals(listOf("rice", "noodles"), lunchFoods.map { it.name })
    }

    private suspend fun assertRollback(injector: ConfirmFoodCardFailureInjector) {
        insertConversation()
        insertCardMessage(showConfirmCardJson("card-1", state = "pending"))
        val failingRepository = createRepository(injector)

        val result = runCatching { failingRepository.confirmFoodCard("card-1", payload()) }

        assertTrue(result.isFailure)
        assertEquals(null, database.dailyRecordDao().getRecordByDateAndStatus("2026-06-18", RecordStatus.Confirmed.name))
        assertEquals("pending", storedCard().getString("state"))
        assertEquals(0, database.syncQueueDao().getPendingCount())
    }

    private fun createRepository(
        injector: ConfirmFoodCardFailureInjector? = null
    ): RoomFoodCardConfirmationRepository {
        return RoomFoodCardConfirmationRepository(
            database = database,
            identityProvider = StaticIdentityProvider(),
            syncQueueWriter = SyncQueueWriter(database.syncQueueDao()),
            chatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao()),
            failureInjector = injector
        )
    }

    private suspend fun insertConversation() {
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = CONVERSATION_ID,
                conversationDate = "2026-06-18",
                title = "test",
                lastMessagePreview = "preview",
                createdAt = 1L,
                updatedAt = 1L,
                lastActivityAt = 1L
            )
        )
    }

    private suspend fun insertCardMessage(cardsJson: String, messageId: String = MESSAGE_ID) {
        database.aiChatMessageDao().insertMessage(
            AiChatMessageEntity(
                id = messageId,
                conversationId = CONVERSATION_ID,
                role = "Assistant",
                text = "",
                createdAt = 2L,
                relatedDraftId = null,
                messageType = "Text",
                assistantCardsJson = cardsJson,
                updatedAt = 2L
            )
        )
    }

    private suspend fun storedCard(messageId: String = MESSAGE_ID): org.json.JSONObject {
        val raw = database.aiChatMessageDao().getMessageById(messageId)!!.assistantCardsJson!!
        return JSONArray(raw).getJSONObject(0)
    }

    private suspend fun savedRecord(): com.example.domain.model.DailyRecord {
        return database.dailyRecordDao()
            .getRecordByDateAndStatus("2026-06-18", RecordStatus.Confirmed.name)
            ?.let { com.example.data.local.mapper.DailyRecordMapper().toDomain(it) }
            ?: error("record missing")
    }

    private class StaticIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = "local-owner",
                remoteUserId = "remote-user",
                authProvider = "test",
                canRemoteSync = true
            )
        }
    }

    private companion object {
        private const val CONVERSATION_ID = "conversation-1"
        private const val MESSAGE_ID = "message-1"

        fun payload(foodName: String = "rice"): PayloadSummary {
            return PayloadSummary(
                originalText = foodName,
                weightKg = 72.5,
                meals = listOf(
                    ConfirmCardMeal(
                        mealType = "lunch",
                        mealLabel = "Lunch",
                        subtotalCalories = 300,
                        items = listOf(
                            ConfirmCardItem(
                                id = "item-$foodName",
                                name = foodName,
                                amountText = "1 bowl",
                                calories = 300,
                                calorieConfidence = "medium",
                                carbohydratesG = 85f,
                                proteinG = 15f,
                                fatG = 22f,
                                fiberG = 6f
                            )
                        )
                    ),
                    ConfirmCardMeal(
                        mealType = "snack",
                        mealLabel = "Snack",
                        subtotalCalories = 80,
                        items = listOf(
                            ConfirmCardItem(
                                id = "snack-$foodName",
                                name = "apple",
                                amountText = "1",
                                calories = 80,
                                calorieConfidence = "high",
                                carbohydratesG = null,
                                proteinG = null,
                                fatG = null,
                                fiberG = null
                            )
                        )
                    )
                )
            )
        }

        fun showConfirmCardJson(cardId: String, state: String): String {
            return """
                [{
                  "type":"show_confirm_card",
                  "id":"$cardId",
                  "confirmType":"food_record",
                  "title":"Confirm",
                  "message":"Confirm",
                  "state":"$state",
                  "resolved":false,
                  "unknownField":"keep-me",
                  "buttons":[{"id":"confirm","label":"Confirm"}]
                }]
            """.trimIndent()
        }

        fun guardCardJson(guardId: String, originalCardId: String, guardState: String): String {
            return """
                [{
                  "type":"date_mismatch_guard_card",
                  "id":"$guardId",
                  "conversationId":"$CONVERSATION_ID",
                  "conversationDate":"2026-06-18",
                  "detectedCurrentDate":"2026-06-20",
                  "state":"$guardState",
                  "pendingOriginalCard":{
                    "type":"show_confirm_card",
                    "id":"$originalCardId",
                    "confirmType":"food_record",
                    "title":"Confirm",
                    "message":"Confirm",
                    "state":"pending",
                    "resolved":false,
                    "unknownNested":"keep"
                  }
                }]
            """.trimIndent()
        }
    }
}
