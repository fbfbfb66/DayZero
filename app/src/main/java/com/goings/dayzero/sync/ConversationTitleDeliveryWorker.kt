package com.goings.dayzero.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.sync.DayZeroSyncConstants
import com.goings.dayzero.data.sync.SyncCoordinator
import com.goings.dayzero.data.sync.title.ConversationTitleSyncContract
import com.goings.dayzero.domain.sync.ConversationTitleDeliveryScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class ConversationTitleDeliveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncCoordinator: SyncCoordinator,
    private val syncQueueDao: SyncQueueDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        syncCoordinator.runOnce()
        val conversationId = inputData.getString(KEY_CONVERSATION_ID)
        if (conversationId != null) {
            return when (
                syncQueueDao.getStatusById(
                    ConversationTitleSyncContract.queueId(conversationId)
                )
            ) {
                null,
                DayZeroSyncConstants.STATUS_DONE,
                DayZeroSyncConstants.STATUS_FAILED_FATAL -> Result.success()
                else -> Result.retry()
            }
        }

        return if (
            syncQueueDao.countActiveTasksForOperation(
                ConversationTitleSyncContract.OP_SUBMIT_TITLE_JOB
            ) == 0
        ) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val RECONCILE_WORK_NAME = "conversation-title:outbox-reconcile"

        private fun request(conversationId: String? = null) =
            OneTimeWorkRequestBuilder<ConversationTitleDeliveryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .apply {
                    if (conversationId != null) {
                        setInputData(workDataOf(KEY_CONVERSATION_ID to conversationId))
                    }
                }
                .build()

        fun schedule(context: Context, conversationId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "conversation-title:$conversationId",
                ExistingWorkPolicy.KEEP,
                request(conversationId)
            )
        }

        fun reconcile(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                RECONCILE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request()
            )
        }
    }
}

class WorkManagerConversationTitleDeliveryScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : ConversationTitleDeliveryScheduler {
    override fun schedule(conversationId: String) {
        ConversationTitleDeliveryWorker.schedule(context, conversationId)
    }
}
