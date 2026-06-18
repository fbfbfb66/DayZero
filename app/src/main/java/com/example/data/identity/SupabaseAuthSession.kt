package com.example.data.identity

data class SupabaseAuthSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long
) {
    fun isUsable(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        return userId.isNotBlank() && accessToken.isNotBlank() && expiresAtEpochSeconds > nowEpochSeconds + 60
    }
}
