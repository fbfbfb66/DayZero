package com.example.data.sync

import android.util.Log
import com.example.data.sync.chat.ChatBackfillStateStore
import com.example.data.sync.chat.ChatConversationPullStateStore
import com.example.data.sync.chat.ChatMessagePullStateStore
import com.example.data.sync.chat.ChatPullHealthStateStore
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteIdentityBindingCoordinator(
    private val identityProvider: CurrentIdentityProvider,
    private val bindingStateStore: RemoteIdentityBindingStateStore,
    private val backfillStateStore: BackfillStateStore,
    private val pullStateStore: PullStateStore,
    private val chatBackfillStateStore: ChatBackfillStateStore,
    private val chatConversationPullStateStore: ChatConversationPullStateStore,
    private val chatMessagePullStateStore: ChatMessagePullStateStore,
    private val chatPullHealthStateStore: ChatPullHealthStateStore
) {
    private val mutex = Mutex()

    suspend fun ensureRemoteUserBound(): Boolean {
        return mutex.withLock {
            val identity = identityProvider.currentIdentity()
            val remoteUserId = identity.remoteUserId
            if (!identity.canRemoteSync || remoteUserId.isNullOrBlank()) {
                return@withLock false
            }

            val previous = bindingStateStore.lastBoundRemoteUserId()
            if (previous == remoteUserId) {
                return@withLock false
            }

            Log.w(
                LOG_PREFIX,
                "remote user binding changed previous=${previous.maskedUserIdOrNone()} next=${remoteUserId.maskedUserIdOrNone()}"
            )
            backfillStateStore.resetForRemoteUserChange()
            pullStateStore.resetForRemoteUserChange()
            chatBackfillStateStore.resetForRemoteUserChange()
            chatConversationPullStateStore.clearCursor(remoteUserId)
            chatMessagePullStateStore.clearCursor(remoteUserId)
            chatPullHealthStateStore.resetForRemoteUserChange()
            bindingStateStore.saveBoundRemoteUserId(remoteUserId)
            true
        }
    }

    private fun String?.maskedUserIdOrNone(): String {
        val value = this
        if (value.isNullOrBlank()) return "none"
        return if (value.length <= 8) "***" else "${value.take(8)}..."
    }

    private companion object {
        private const val LOG_PREFIX = "DayZeroIdentityBind"
    }
}
