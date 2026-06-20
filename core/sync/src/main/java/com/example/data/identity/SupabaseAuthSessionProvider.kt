package com.example.data.identity

interface SupabaseAuthSessionProvider {
    suspend fun currentSessionOrNull(): SupabaseAuthSession?

    fun currentSessionStatus(): SupabaseAuthSessionStatus = SupabaseAuthSessionStatus.NoStoredSession
}

sealed class SupabaseAuthSessionStatus {
    data object NoStoredSession : SupabaseAuthSessionStatus()
    data class AccessTokenUsable(val userId: String) : SupabaseAuthSessionStatus()
    data class RefreshSucceeded(val userId: String) : SupabaseAuthSessionStatus()
    data class RefreshTemporaryFailure(val reason: String) : SupabaseAuthSessionStatus()
    data class RefreshPermanentlyRejected(val reason: String) : SupabaseAuthSessionStatus()
    data class AnonymousSignUpSucceeded(val userId: String) : SupabaseAuthSessionStatus()
}
