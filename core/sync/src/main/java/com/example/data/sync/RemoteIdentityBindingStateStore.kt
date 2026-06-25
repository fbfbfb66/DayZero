package com.example.data.sync

import android.content.Context

class RemoteIdentityBindingStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dayzero_remote_identity_binding",
        Context.MODE_PRIVATE
    )

    fun lastBoundRemoteUserId(): String? {
        return preferences.getString(KEY_LAST_BOUND_REMOTE_USER_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun saveBoundRemoteUserId(remoteUserId: String) {
        val saved = preferences.edit()
            .putString(KEY_LAST_BOUND_REMOTE_USER_ID, remoteUserId)
            .commit()
        check(saved) { "Failed to save remote identity binding" }
    }

    companion object {
        private const val KEY_LAST_BOUND_REMOTE_USER_ID = "last_bound_remote_user_id"
    }
}
