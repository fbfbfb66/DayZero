package com.example.data.identity

import android.content.Context
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import java.util.UUID

class LocalIdentityProvider(context: Context) : CurrentIdentityProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dayzero_identity",
        Context.MODE_PRIVATE
    )

    override suspend fun currentIdentity(): AppIdentity {
        val localOwnerId = getOrCreateLocalOwnerId()
        return AppIdentity(
            localOwnerId = localOwnerId,
            remoteUserId = null,
            authProvider = "local",
            canRemoteSync = false
        )
    }

    private fun getOrCreateLocalOwnerId(): String {
        val existing = preferences.getString(KEY_LOCAL_OWNER_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val created = "local_${UUID.randomUUID()}"
        preferences.edit().putString(KEY_LOCAL_OWNER_ID, created).apply()
        return created
    }

    companion object {
        private const val KEY_LOCAL_OWNER_ID = "local_owner_id"
    }
}
