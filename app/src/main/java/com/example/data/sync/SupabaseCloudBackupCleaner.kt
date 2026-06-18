package com.example.data.sync

import android.util.Log
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.remote.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SupabaseCloudBackupCleaner(
    private val okHttpClient: OkHttpClient,
    private val sessionProvider: SupabaseAuthSessionProvider,
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val isConfigured: Boolean = SupabaseConfig.isConfigured()
) {
    suspend fun clearCurrentUserCloudBackup(): Boolean {
        if (!isConfigured) {
            Log.d(LOG_PREFIX, "debug cloud clear skipped reason=remote_disabled")
            return false
        }

        val session = sessionProvider.currentSessionOrNull()
        if (session == null) {
            Log.d(LOG_PREFIX, "debug cloud clear skipped reason=waiting_for_auth")
            return false
        }

        Log.w(LOG_PREFIX, "debug cloud clear start userId=${session.userId.take(8)}...")
        return withContext(Dispatchers.IO) {
            try {
                TABLES_IN_DELETE_ORDER.all { tableName ->
                    val request = Request.Builder()
                        .url("${restUrl()}$tableName?user_id=eq.${session.userId}")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer ${session.accessToken}")
                        .header("Prefer", "return=minimal")
                        .delete()
                        .build()

                    Log.w(LOG_PREFIX, "debug cloud clear table=$tableName")
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            true
                        } else {
                            Log.e(LOG_PREFIX, "debug cloud clear failed table=$tableName reason=http_${response.code}")
                            false
                        }
                    }
                }.also { success ->
                    if (success) {
                        Log.w(LOG_PREFIX, "debug cloud clear success")
                    }
                }
            } catch (error: Exception) {
                Log.e(LOG_PREFIX, "debug cloud clear error reason=${error::class.java.simpleName}", error)
                false
            }
        }
    }

    private fun restUrl(): String = "${normalizedUrl()}rest/v1/"

    private fun normalizedUrl(): String {
        return if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
    }

    private companion object {
        private const val LOG_PREFIX = "DayZeroRemote"
        private val TABLES_IN_DELETE_ORDER = listOf(
            "food_entries",
            "meals",
            "weight_records",
            "daily_records"
        )
    }
}
