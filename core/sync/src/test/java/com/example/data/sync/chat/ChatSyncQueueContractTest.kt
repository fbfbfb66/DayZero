package com.example.data.sync.chat

import com.example.data.sync.DayZeroSyncConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSyncQueueContractTest {
    @Test
    fun chatOperationsAreDefinedButNotAddedToProductionQueueDispatchConstants() {
        assertTrue(ChatSyncQueueContract.ENTITY_CONVERSATION in ChatSyncQueueContract.allEntityTypes)
        assertTrue(ChatSyncQueueContract.ENTITY_MESSAGE in ChatSyncQueueContract.allEntityTypes)
        assertTrue(ChatSyncQueueContract.OP_UPSERT_MESSAGE in ChatSyncQueueContract.allOperations)

        val productionRecordOperations = setOf(
            DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
            DayZeroSyncConstants.OP_UPSERT_MEAL,
            DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY,
            DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD,
            DayZeroSyncConstants.OP_SOFT_DELETE_RECORD
        )

        assertFalse(ChatSyncQueueContract.allOperations.any { it in productionRecordOperations })
    }
}

