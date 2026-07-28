package com.goings.dayzero.data.identity

import com.goings.dayzero.data.remote.SupabaseConfig

data class FixedDevelopmentAccountCredentials(
    val email: String,
    val password: String,
    val expectedUserId: String
) {
    fun isConfigured(): Boolean {
        return email.isNotBlank() && password.isNotBlank() && expectedUserId.isNotBlank()
    }
}

class FixedDevelopmentAccountCredentialsProvider(
    private val email: String = SupabaseConfig.DAYZERO_FIXED_AUTH_EMAIL,
    private val password: String = SupabaseConfig.DAYZERO_FIXED_AUTH_PASSWORD,
    private val expectedUserId: String = SupabaseConfig.DAYZERO_FIXED_AUTH_USER_ID
) {
    fun credentials(): FixedDevelopmentAccountCredentials {
        return FixedDevelopmentAccountCredentials(
            email = email.trim(),
            password = password,
            expectedUserId = expectedUserId.trim()
        )
    }
}
