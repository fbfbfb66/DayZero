package com.example.data.sync.chat

import android.util.Log
import androidx.room.withTransaction
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.ConversationEntity
import com.example.domain.model.sync.ChatSyncConversationSnapshot
import java.time.Instant

class ImmutableConflictException(
    val fieldName: String,
    val localValue: String,
    val remoteValue: String
) : RuntimeException("Immutable conflict on $fieldName: local=$localValue, remote=$remoteValue")

class ChatConversationRemoteMerger(
    private val database: DayZeroDatabase,
    private val conversationDao: ConversationDao,
    private val syncQueueDao: SyncQueueDao
) {

    suspend fun mergeConversationPage(
        identityLocalId: String,
        remoteSnapshots: List<ChatSyncConversationSnapshot>
    ): ChatConversationMergeStats {
        if (remoteSnapshots.isEmpty()) return ChatConversationMergeStats()

        var stats = ChatConversationMergeStats()

        database.withTransaction {
            for (remote in remoteSnapshots) {
                val itemStats = applyRemoteConversation(identityLocalId, remote)
                stats = stats + itemStats
            }
        }

        return stats
    }

    private suspend fun applyRemoteConversation(
        identityLocalId: String,
        remote: ChatSyncConversationSnapshot
    ): ChatConversationMergeStats {
        val local = conversationDao.getConversationById(remote.id)
        val isDirty = isLocalDirty(identityLocalId, remote.id)

        if (local == null) {
            if (remote.deletedAtMillis != null) {
                // Remote is tombstone, local doesn't exist.
                // We choose to skip it, to not clutter the local DB with invisible tombstones
                // of conversations we never had. We may need to revisit this if messages
                // get pulled and need a parent.
                Log.d("DayZeroChatPull", "skipped tombstone for non-existent local id=${remote.id.take(8)}")
                return ChatConversationMergeStats(skippedCount = 1)
            } else {
                // Local doesn't exist, remote is active -> insert
                val newEntity = ConversationEntity(
                    id = remote.id,
                    conversationDate = remote.conversationDate.toString(),
                    title = remote.title,
                    lastMessagePreview = remote.lastMessagePreview,
                    createdAt = remote.createdAtMillis,
                    updatedAt = remote.updatedAtMillis,
                    lastActivityAt = remote.lastActivityAtMillis,
                    deletedAt = null
                )
                conversationDao.insertConversation(newEntity)
                Log.d("DayZeroChatPull", "inserted remote active id=${remote.id.take(8)}")
                return ChatConversationMergeStats(insertedCount = 1)
            }
        }

        // Local exists. Check immutable fields first.
        val localDate = local.conversationDate
        if (localDate != remote.conversationDate.toString()) {
            Log.e("DayZeroChatPull", "immutable conflict: conversationDate local=$localDate remote=${remote.conversationDate}")
            throw ImmutableConflictException("conversationDate", localDate, remote.conversationDate.toString())
        }
        if (local.createdAt != remote.createdAtMillis) {
            Log.e("DayZeroChatPull", "immutable conflict: createdAt local=${local.createdAt} remote=${remote.createdAtMillis}")
            throw ImmutableConflictException("createdAt", local.createdAt.toString(), remote.createdAtMillis.toString())
        }

        // Check if local is dirty
        if (isDirty) {
            Log.d("DayZeroChatPull", "deferred to dirty local id=${remote.id.take(8)}")
            return ChatConversationMergeStats(deferredCount = 1)
        }

        // Local is clean.

        // Handle tombstone
        val remoteDeletedAtMillis = remote.deletedAtMillis
        if (remoteDeletedAtMillis != null) {
            if (local.deletedAt == null) {
                // Remote says deleted, local is active -> delete local
                if (remote.updatedAtMillis >= local.updatedAt) {
                    conversationDao.softDeleteConversation(remote.id, remoteDeletedAtMillis)
                    Log.d("DayZeroChatPull", "applied remote tombstone id=${remote.id.take(8)}")
                    return ChatConversationMergeStats(deletedCount = 1)
                } else {
                    Log.d("DayZeroChatPull", "ignored old remote tombstone id=${remote.id.take(8)}")
                    return ChatConversationMergeStats(skippedCount = 1)
                }
            } else {
                // Both are deleted
                return ChatConversationMergeStats(skippedCount = 1)
            }
        } else {
            // Remote is active
            if (local.deletedAt != null) {
                // Remote says active, local is deleted.
                // Rule: As long as local deletedAt != null, remote active must NOT clear deletedAt through normal Pull.
                Log.d("DayZeroChatPull", "refused to resurrect deleted local id=${remote.id.take(8)} with remote active")
                return ChatConversationMergeStats(skippedCount = 1)
            }

            // Both are active. Compare updatedAt.
            if (remote.updatedAtMillis > local.updatedAt) {
                // Remote is newer
                conversationDao.updateConversationSummary(
                    id = remote.id,
                    title = remote.title,
                    lastMessagePreview = remote.lastMessagePreview,
                    lastActivityAt = remote.lastActivityAtMillis,
                    updatedAt = remote.updatedAtMillis
                )
                Log.d("DayZeroChatPull", "updated to newer remote id=${remote.id.take(8)}")
                return ChatConversationMergeStats(updatedCount = 1)
            } else if (remote.updatedAtMillis < local.updatedAt) {
                // Local is newer
                Log.d("DayZeroChatPull", "retained newer clean local id=${remote.id.take(8)}")
                return ChatConversationMergeStats(skippedCount = 1)
            } else {
                // Same timestamp
                if (remote.title != local.title ||
                    remote.lastMessagePreview != local.lastMessagePreview ||
                    remote.lastActivityAtMillis != local.lastActivityAt
                ) {
                    // Conflict at same timestamp. Resolve deterministically.
                    // Rule: remote wins on same timestamp to ensure eventual consistency if tie.
                    conversationDao.updateConversationSummary(
                        id = remote.id,
                        title = remote.title,
                        lastMessagePreview = remote.lastMessagePreview,
                        lastActivityAt = remote.lastActivityAtMillis,
                        updatedAt = remote.updatedAtMillis
                    )
                    Log.d("DayZeroChatPull", "resolved same-timestamp conflict with remote id=${remote.id.take(8)}")
                    return ChatConversationMergeStats(updatedCount = 1, conflictCount = 1)
                } else {
                    // Exact match
                    return ChatConversationMergeStats(skippedCount = 1)
                }
            }
        }
    }

    private suspend fun isLocalDirty(identityLocalId: String, conversationId: String): Boolean {
        val activeTasksCount = syncQueueDao.countActiveTasksForEntityAndOperation(
            ownerLocalId = identityLocalId,
            entityType = ChatSyncQueueContract.ENTITY_CONVERSATION,
            entityLocalId = conversationId,
            operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION
        )
        return activeTasksCount > 0
    }
}

data class ChatConversationMergeStats(
    val insertedCount: Int = 0,
    val updatedCount: Int = 0,
    val deletedCount: Int = 0,
    val deferredCount: Int = 0,
    val skippedCount: Int = 0,
    val conflictCount: Int = 0,
    val immutableConflictCount: Int = 0
) {
    operator fun plus(other: ChatConversationMergeStats): ChatConversationMergeStats {
        return ChatConversationMergeStats(
            insertedCount = this.insertedCount + other.insertedCount,
            updatedCount = this.updatedCount + other.updatedCount,
            deletedCount = this.deletedCount + other.deletedCount,
            deferredCount = this.deferredCount + other.deferredCount,
            skippedCount = this.skippedCount + other.skippedCount,
            conflictCount = this.conflictCount + other.conflictCount,
            immutableConflictCount = this.immutableConflictCount + other.immutableConflictCount
        )
    }
}
