package com.example.domain.identity

interface CurrentIdentityProvider {
    suspend fun currentIdentity(): AppIdentity
}
