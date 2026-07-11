package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.identity.StaticLocalIdentityProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.domain.model.ai.assistant.assistantPlaceholderId
import com.example.domain.repository.MealPhotoAssignment
import com.example.domain.repository.UpdateFoodCardPhotoAssignmentsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomFoodCardPhotoAssignmentRepositoryTest {
    private lateinit var db: DayZeroDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DayZeroDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    @Test fun updatesRawCardPreservesUnknownFieldsEnqueuesChatSyncAndIsIdempotent() = runTest {
        seed(guardState = null)
        val repo = repository()
        assertEquals(UpdateFoodCardPhotoAssignmentsResult.Updated, repo.updatePhotoAssignments("card", listOf(
            MealPhotoAssignment(0, listOf("m2")), MealPhotoAssignment(1, listOf("m1"))
        )))
        val stored = JSONArray(db.aiChatMessageDao().getMessageById(assistantPlaceholderId(USER_ID))!!.assistantCardsJson).getJSONObject(0)
        assertEquals("card-unknown", stored.getString("unknownCard"))
        assertEquals("meal-unknown", stored.getJSONArray("meals").getJSONObject(0).getString("unknownMeal"))
        assertEquals("m2", stored.getJSONArray("meals").getJSONObject(0).getJSONArray("sourceMediaIds").getString(0))
        assertEquals(1, db.syncQueueDao().getPendingCount())
        val updatedAt = db.aiChatMessageDao().getMessageById(assistantPlaceholderId(USER_ID))!!.updatedAt
        assertEquals(UpdateFoodCardPhotoAssignmentsResult.Unchanged, repo.updatePhotoAssignments("card", listOf(
            MealPhotoAssignment(0, listOf("m2")), MealPhotoAssignment(1, listOf("m1"))
        )))
        assertEquals(updatedAt, db.aiChatMessageDao().getMessageById(assistantPlaceholderId(USER_ID))!!.updatedAt)
    }

    @Test fun rejectsInventedDuplicateAndTerminalOrPendingGuardAssignments() = runTest {
        seed(null)
        val repo = repository()
        assertEquals(UpdateFoodCardPhotoAssignmentsResult.InvalidAssignments,
            repo.updatePhotoAssignments("card", listOf(MealPhotoAssignment(0, listOf("fake")))))
        assertEquals(UpdateFoodCardPhotoAssignmentsResult.InvalidAssignments,
            repo.updatePhotoAssignments("card", listOf(MealPhotoAssignment(0, listOf("m1")), MealPhotoAssignment(1, listOf("m1")))))

        db.aiChatMessageDao().deleteAllMessages()
        seed("pending")
        assertEquals(UpdateFoodCardPhotoAssignmentsResult.NotEditable,
            repo.updatePhotoAssignments("card", listOf(MealPhotoAssignment(0, emptyList()))))
    }

    @Test fun queueFailureAndCancellationRollbackMessageAndRethrow() = runTest {
        seed(null)
        val original = db.aiChatMessageDao().getMessageById(assistantPlaceholderId(USER_ID))!!.assistantCardsJson
        val repo = repository(object : FoodCardPhotoAssignmentFailureInjector {
            override fun beforeQueueEnqueue() { throw CancellationException("stop") }
        })
        var cancellation: CancellationException? = null
        try {
            repo.updatePhotoAssignments("card", listOf(MealPhotoAssignment(0, listOf("m1"))))
        } catch (error: CancellationException) {
            cancellation = error
        }
        check(cancellation != null)
        assertEquals(original, db.aiChatMessageDao().getMessageById(assistantPlaceholderId(USER_ID))!!.assistantCardsJson)
        assertEquals(0, db.syncQueueDao().getPendingCount())
    }

    private fun repository(injector: FoodCardPhotoAssignmentFailureInjector? = null) =
        RoomFoodCardPhotoAssignmentRepository(db, StaticLocalIdentityProvider("owner"), failureInjector = injector)

    private suspend fun seed(guardState: String?) {
        db.conversationDao().insertConversation(ConversationEntity(CONV, "2026-07-07", "t", "p", 1, 1, 1))
        db.aiChatMessageDao().insertMessage(AiChatMessageEntity(USER_ID, CONV, "User", "food", 1, null, "Text",
            """{"media":{"schemaVersion":1,"sourceMediaIds":["m1","m2"]}}""", null, null, 1))
        val card = """{"type":"show_confirm_card","id":"card","state":"pending","unknownCard":"card-unknown","meals":[{"mealType":"lunch","unknownMeal":"meal-unknown","items":[{"name":"rice","calories":1}]},{"mealType":"dinner","items":[]}]}"""
        val outer = if (guardState == null) card else """{"type":"date_mismatch_guard_card","id":"guard","state":"$guardState","pendingOriginalCard":$card}"""
        db.aiChatMessageDao().insertMessage(AiChatMessageEntity(assistantPlaceholderId(USER_ID), CONV, "Assistant", "ok", 2, null, "Text", null, "[$outer]", null, 2))
    }

    private companion object { const val CONV = "conv"; const val USER_ID = "user" }
}
