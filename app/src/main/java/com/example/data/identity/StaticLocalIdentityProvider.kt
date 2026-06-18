package com.example.data.identity

import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider

class StaticLocalIdentityProvider(
    private val localOwnerId: String = "local_uninitialized"
) : CurrentIdentityProvider {
    override suspend fun currentIdentity(): AppIdentity {
        return AppIdentity(
            localOwnerId = localOwnerId,
            remoteUserId = null,
            authProvider = "local",
            canRemoteSync = false
        )
    }
}
