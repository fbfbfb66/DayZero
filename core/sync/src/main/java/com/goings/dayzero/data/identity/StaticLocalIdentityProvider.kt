package com.goings.dayzero.data.identity

import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.identity.CurrentIdentityProvider

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
