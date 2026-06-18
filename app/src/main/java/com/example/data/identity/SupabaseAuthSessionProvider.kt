package com.example.data.identity

interface SupabaseAuthSessionProvider {
    suspend fun currentSessionOrNull(): SupabaseAuthSession?
}
