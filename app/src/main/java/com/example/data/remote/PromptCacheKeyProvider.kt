package com.example.data.remote

import android.content.Context
import java.util.UUID

class PromptCacheKeyProvider(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dayzero_ai_prompt_cache",
        Context.MODE_PRIVATE
    )

    fun getPromptCacheKey(): String {
        val existing = preferences.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = "dayzero_app_${UUID.randomUUID()}"
        preferences.edit().putString(KEY, generated).apply()
        return generated
    }

    companion object {
        private const val KEY = "prompt_cache_key"
    }
}
