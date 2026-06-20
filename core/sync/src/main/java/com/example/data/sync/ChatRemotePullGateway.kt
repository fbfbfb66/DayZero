package com.example.data.sync

import com.example.domain.identity.AppIdentity
import com.example.domain.model.sync.ChatRemoteConversationPage
import com.example.domain.model.sync.ChatRemoteMessagePage
import com.example.domain.model.sync.ChatSyncServerCursor

interface ChatRemotePullGateway {
    suspend fun fetchConversationPage(
        identity: AppIdentity,
        cursor: ChatSyncServerCursor?,
        limit: Int
    ): ChatRemotePullResult<ChatRemoteConversationPage>

    suspend fun fetchMessagePage(
        identity: AppIdentity,
        cursor: ChatSyncServerCursor?,
        limit: Int
    ): ChatRemotePullResult<ChatRemoteMessagePage>
}

sealed class ChatRemotePullResult<out T> {
    data class Success<T>(val data: T) : ChatRemotePullResult<T>()
    data class RetryableFailure(val message: String) : ChatRemotePullResult<Nothing>()
    data class FatalFailure(val message: String) : ChatRemotePullResult<Nothing>()
    data class Skipped(val reason: String) : ChatRemotePullResult<Nothing>()
}
