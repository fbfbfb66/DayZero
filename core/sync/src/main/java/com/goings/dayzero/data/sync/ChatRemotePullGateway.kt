package com.goings.dayzero.data.sync

import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.model.sync.ChatRemoteConversationPage
import com.goings.dayzero.domain.model.sync.ChatRemoteMessagePage
import com.goings.dayzero.domain.model.sync.ChatSyncServerCursor

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
