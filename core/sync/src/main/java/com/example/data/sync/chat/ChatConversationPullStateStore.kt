package com.example.data.sync.chat

import android.content.Context
import android.util.Log

class ChatConversationPullStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCursor(ownerId: String): Pair<String, String>? {
        val serverUpdatedAt = preferences.getString(KEY_SERVER_UPDATED_AT + "_" + ownerId, null)
        val id = preferences.getString(KEY_ID + "_" + ownerId, null)
        if (serverUpdatedAt.isNullOrBlank() || id.isNullOrBlank()) {
            return null
        }
        return Pair(serverUpdatedAt, id)
    }

    fun saveCursor(ownerId: String, serverUpdatedAt: String, id: String) {
        Log.d("DayZeroChatPull", "saveCursor: $serverUpdatedAt, $id for $ownerId")
        val saved = preferences.edit()
            .putString(KEY_SERVER_UPDATED_AT + "_" + ownerId, serverUpdatedAt)
            .putString(KEY_ID + "_" + ownerId, id)
            .commit()
        check(saved) { "Failed to save chat conversation pull cursor" }
    }

    fun clearCursor(ownerId: String) {
        Log.d("DayZeroChatPull", "clearCursor for $ownerId")
        preferences.edit()
            .remove(KEY_SERVER_UPDATED_AT + "_" + ownerId)
            .remove(KEY_ID + "_" + ownerId)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dayzero_chat_conversation_pull"
        private const val KEY_SERVER_UPDATED_AT = "cursor_server_updated_at"
        private const val KEY_ID = "cursor_id"
    }
}
