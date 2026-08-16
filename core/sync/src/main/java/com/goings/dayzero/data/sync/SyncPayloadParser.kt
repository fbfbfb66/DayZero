package com.goings.dayzero.data.sync

import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.data.sync.chat.ChatSyncQueueContract
import com.goings.dayzero.data.sync.media.MediaSyncQueueContract
import com.goings.dayzero.data.sync.title.ConversationTitleSyncContract
import org.json.JSONObject

class SyncPayloadParser {
    fun parse(item: SyncQueueEntity): Result<SyncPayload> {
        return runCatching {
            require(item.entityType.isNotBlank()) { "entityType is blank" }
            require(item.entityLocalId.isNotBlank()) { "entityLocalId is blank" }
            require(item.operation in SUPPORTED_OPERATIONS) { "unsupported operation ${item.operation}" }

            val body = JSONObject(item.payloadJson)
            // Title-job payloads identify their target via conversationId/firstUserMessageId
            // and legitimately omit clientId; SyncPayload.clientId() falls back to entityLocalId.
            if (item.operation != ConversationTitleSyncContract.OP_SUBMIT_TITLE_JOB) {
                require(body.optString("clientId").isNotBlank()) { "payload clientId is blank" }
            }

            SyncPayload(
                queueId = item.id,
                entityType = item.entityType,
                entityLocalId = item.entityLocalId,
                operation = item.operation,
                ownerLocalId = item.ownerLocalId,
                body = body
            )
        }
    }

    private companion object {
        private val SUPPORTED_OPERATIONS = setOf(
            DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
            DayZeroSyncConstants.OP_UPSERT_MEAL,
            DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY,
            DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD,
            DayZeroSyncConstants.OP_SOFT_DELETE_RECORD,
            ChatSyncQueueContract.OP_UPSERT_CONVERSATION,
            ChatSyncQueueContract.OP_UPSERT_MESSAGE,
            ConversationTitleSyncContract.OP_SUBMIT_TITLE_JOB,
            MediaSyncQueueContract.OP_UPSERT_MEDIA_ASSET,
            MediaSyncQueueContract.OP_DOWNLOAD_MEDIA_ASSET,
            MediaSyncQueueContract.OP_SOFT_DELETE_MEDIA_ASSET
        )
    }
}
