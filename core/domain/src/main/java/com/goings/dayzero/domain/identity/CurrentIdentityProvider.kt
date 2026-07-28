package com.goings.dayzero.domain.identity

interface CurrentIdentityProvider {
    suspend fun currentIdentity(): AppIdentity
}
