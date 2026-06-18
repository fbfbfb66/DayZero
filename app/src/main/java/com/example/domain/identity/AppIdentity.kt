package com.example.domain.identity

data class AppIdentity(
    val localOwnerId: String,
    val remoteUserId: String?,
    val authProvider: String,
    val canRemoteSync: Boolean
)
