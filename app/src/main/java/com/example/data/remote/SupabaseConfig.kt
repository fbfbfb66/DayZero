package com.example.data.remote

import com.example.BuildConfig

object SupabaseConfig {
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL.trim()
    val SUPABASE_PUBLISHABLE_KEY: String = BuildConfig.SUPABASE_ANON_KEY.trim()
    val SAFE_BASE_URL: String = normalizeBaseUrl(SUPABASE_URL).ifBlank { "https://example.invalid/" }

    fun edgeFunctionUrl(functionName: String): String {
        return "${SAFE_BASE_URL}functions/v1/$functionName"
    }

    fun isConfigured(): Boolean {
        return normalizeBaseUrl(SUPABASE_URL).isNotBlank() &&
            isUsableValue(SUPABASE_PUBLISHABLE_KEY) &&
            !SUPABASE_PUBLISHABLE_KEY.contains("service_role", ignoreCase = true)
    }

    private fun normalizeBaseUrl(value: String): String {
        if (!isUsableValue(value)) return ""
        return if (value.endsWith("/")) value else "$value/"
    }

    private fun isUsableValue(value: String): Boolean {
        return value.isNotBlank() &&
            !value.startsWith("TODO_", ignoreCase = true) &&
            !value.startsWith("MY_", ignoreCase = true)
    }
}
