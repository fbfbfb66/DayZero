package com.example.data.sync.chat

import android.content.Context
import android.util.Log

class ChatMessagePullStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCursor(remoteUserId: String): Pair<String, String>? {
        val serverUpdatedAt = preferences.getString(KEY_SERVER_UPDATED_AT + "_" + remoteUserId, null)
        val id = preferences.getString(KEY_ID + "_" + remoteUserId, null)
        if (serverUpdatedAt.isNullOrBlank() || id.isNullOrBlank()) {
            return null
        }
        return Pair(serverUpdatedAt, id)
    }

    fun saveCursor(remoteUserId: String, serverUpdatedAt: String, id: String) {
        Log.d("DayZeroChatMsgPull", "saveCursor user=${remoteUserId.take(8)} id=${id.take(8)}")
        val saved = preferences.edit()
            .putString(KEY_SERVER_UPDATED_AT + "_" + remoteUserId, serverUpdatedAt)
            .putString(KEY_ID + "_" + remoteUserId, id)
            .commit()
        check(saved) { "Failed to save chat message pull cursor" }
    }

    fun clearCursor(remoteUserId: String) {
        preferences.edit()
            .remove(KEY_SERVER_UPDATED_AT + "_" + remoteUserId)
            .remove(KEY_ID + "_" + remoteUserId)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dayzero_chat_message_pull"
        private const val KEY_SERVER_UPDATED_AT = "cursor_server_updated_at"
        private const val KEY_ID = "cursor_id"
    }
}
