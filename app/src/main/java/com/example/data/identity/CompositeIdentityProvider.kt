package com.example.data.identity

import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider

class CompositeIdentityProvider(
    private val localIdentityProvider: CurrentIdentityProvider,
    private val remoteIdentityProvider: CurrentIdentityProvider?
) : CurrentIdentityProvider {
    override suspend fun currentIdentity(): AppIdentity {
        val localIdentity = localIdentityProvider.currentIdentity()
        val remoteIdentity = remoteIdentityProvider?.currentIdentity()

        return if (remoteIdentity?.canRemoteSync == true && !remoteIdentity.remoteUserId.isNullOrBlank()) {
            localIdentity.copy(
                remoteUserId = remoteIdentity.remoteUserId,
                authProvider = remoteIdentity.authProvider,
                canRemoteSync = true
            )
        } else {
            localIdentity.copy(
                remoteUserId = null,
                authProvider = "local",
                canRemoteSync = false
            )
        }
    }
}
