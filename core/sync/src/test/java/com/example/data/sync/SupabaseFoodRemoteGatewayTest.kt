package com.example.data.sync

import com.example.data.identity.SupabaseAuthSession
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.identity.SupabaseAuthSessionStatus
import com.example.domain.identity.AppIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SupabaseFoodRemoteGatewayTest {

    private val identity = AppIdentity("user123", "remote123", "supabase", true)
    private val session = SupabaseAuthSession("remote123", "token", "refresh", Long.MAX_VALUE)

    private val sessionProvider = object : SupabaseAuthSessionProvider {
        override suspend fun currentSessionOrNull(): SupabaseAuthSession = session
        override suspend fun forceRefreshSession(): SupabaseAuthSession = session
        override fun currentSessionStatus(): SupabaseAuthSessionStatus =
            SupabaseAuthSessionStatus.AccessTokenUsable("remote123")
    }

    private fun mockHttpClient(responseCode: Int, responseBody: String, onRequest: ((Request) -> Unit)? = null): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onRequest?.invoke(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun push_foodEntry_withPositiveNutrition_mapsCorrectly() = runBlocking {
        var capturedBody: JSONObject? = null
        val client = mockHttpClient(200, "") { request ->
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            capturedBody = JSONObject(buffer.readUtf8())
        }

        val gateway = SupabaseRemoteSyncGateway(client, sessionProvider, "https://example.supabase.co", "key", true)

        val payloadBody = JSONObject()
            .put("clientId", "food-123")
            .put("mealClientId", "meal-123")
            .put("name", "鸡胸肉")
            .put("quantity", "100g")
            .put("estimatedCalories", 165)
            .put("carbsG", 1.5)
            .put("proteinG", 31.0)
            .put("fatG", 3.6)
            .put("fiberG", 2.2)
            .put("confidence", 1.0)
            .put("schemaVersion", 1)

        val payload = SyncPayload(
            queueId = "queue-123",
            ownerLocalId = "user123",
            entityType = "food_entry",
            entityLocalId = "food-123",
            operation = "OP_UPSERT_FOOD_ENTRY",
            body = payloadBody
        )

        gateway.upsertFoodEntry(payload)

        val json = capturedBody
        assertNotNull(json)
        assertEquals(1.5, json!!.getDouble("carbs_g"), 0.001)
        assertEquals(31.0, json.getDouble("protein_g"), 0.001)
        assertEquals(3.6, json.getDouble("fat_g"), 0.001)
        assertEquals(2.2, json.getDouble("fiber_g"), 0.001)
    }

    @Test
    fun push_foodEntry_withZeroNutrition_mapsCorrectly() = runBlocking {
        var capturedBody: JSONObject? = null
        val client = mockHttpClient(200, "") { request ->
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            capturedBody = JSONObject(buffer.readUtf8())
        }

        val gateway = SupabaseRemoteSyncGateway(client, sessionProvider, "https://example.supabase.co", "key", true)

        val payloadBody = JSONObject()
            .put("clientId", "food-123")
            .put("mealClientId", "meal-123")
            .put("name", "水")
            .put("quantity", "500ml")
            .put("estimatedCalories", 0)
            .put("carbsG", 0.0)
            .put("proteinG", 0.0)
            .put("fatG", 0.0)
            .put("fiberG", 0.0)
            .put("confidence", 1.0)
            .put("schemaVersion", 1)

        val payload = SyncPayload(
            queueId = "queue-123",
            ownerLocalId = "user123",
            entityType = "food_entry",
            entityLocalId = "food-123",
            operation = "OP_UPSERT_FOOD_ENTRY",
            body = payloadBody
        )

        gateway.upsertFoodEntry(payload)

        val json = capturedBody
        assertNotNull(json)
        assertEquals(0.0, json!!.getDouble("carbs_g"), 0.001)
        assertEquals(0.0, json.getDouble("protein_g"), 0.001)
        assertEquals(0.0, json.getDouble("fat_g"), 0.001)
        assertEquals(0.0, json.getDouble("fiber_g"), 0.001)
    }

    @Test
    fun push_foodEntry_withNullNutrition_mapsCorrectly() = runBlocking {
        var capturedBody: JSONObject? = null
        val client = mockHttpClient(200, "") { request ->
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            capturedBody = JSONObject(buffer.readUtf8())
        }

        val gateway = SupabaseRemoteSyncGateway(client, sessionProvider, "https://example.supabase.co", "key", true)

        val payloadBody = JSONObject()
            .put("clientId", "food-123")
            .put("mealClientId", "meal-123")
            .put("name", "苹果")
            .put("quantity", "1个")
            .put("estimatedCalories", 80)
            .put("carbsG", JSONObject.NULL)
            .put("proteinG", JSONObject.NULL)
            .put("fatG", JSONObject.NULL)
            .put("fiberG", JSONObject.NULL)
            .put("confidence", 1.0)
            .put("schemaVersion", 1)

        val payload = SyncPayload(
            queueId = "queue-123",
            ownerLocalId = "user123",
            entityType = "food_entry",
            entityLocalId = "food-123",
            operation = "OP_UPSERT_FOOD_ENTRY",
            body = payloadBody
        )

        gateway.upsertFoodEntry(payload)

        val json = capturedBody
        assertNotNull(json)
        assertTrue(json!!.has("carbs_g"))
        assertTrue(json.has("protein_g"))
        assertTrue(json.has("fat_g"))
        assertTrue(json.has("fiber_g"))

        assertTrue(json.isNull("carbs_g"))
        assertTrue(json.isNull("protein_g"))
        assertTrue(json.isNull("fat_g"))
        assertTrue(json.isNull("fiber_g"))
    }

    @Test
    fun pull_foodEntry_parsesNutritionFieldsCorrectly() = runBlocking {
        val responseBody = """
            [
              {
                "user_id": "remote123",
                "client_id": "food-123",
                "meal_client_id": "meal-123",
                "name": "西兰花",
                "amount_text": "100g",
                "grams": 100.0,
                "calories": 34.0,
                "protein_g": 2.8,
                "carbs_g": 7.0,
                "fat_g": 0.4,
                "fiber_g": 2.6,
                "confidence": 1.0,
                "source": "confirm_card",
                "created_at": "2026-06-26T00:10:00Z",
                "updated_at": "2026-06-26T00:10:00Z",
                "deleted_at": null,
                "schema_version": 1
              }
            ]
        """.trimIndent()

        val client = mockHttpClient(200, responseBody)
        val gateway = SupabaseRemotePullGateway(client, sessionProvider, "https://example.supabase.co", "key", true)

        val result = gateway.pullFoodEntries(null, 10)
        assertTrue(result is RemotePullResult.Success)
        val items = (result as RemotePullResult.Success).items
        assertEquals(1, items.size)
        val food = items[0]
        assertEquals("food-123", food.clientId)
        assertEquals("西兰花", food.name)
        assertEquals(7.0f, food.carbsG)
        assertEquals(2.8f, food.proteinG)
        assertEquals(0.4f, food.fatG)
        assertEquals(2.6f, food.fiberG)
    }

    @Test
    fun pull_foodEntry_handlesMissingFiberGColumn() = runBlocking {
        // Test case where fiber_g column is not returned at all by supabase (for migration compatibility)
        val responseBody = """
            [
              {
                "user_id": "remote123",
                "client_id": "food-123",
                "meal_client_id": "meal-123",
                "name": "西兰花",
                "amount_text": "100g",
                "grams": 100.0,
                "calories": 34.0,
                "protein_g": 2.8,
                "carbs_g": 7.0,
                "fat_g": 0.4,
                "confidence": 1.0,
                "source": "confirm_card",
                "created_at": "2026-06-26T00:10:00Z",
                "updated_at": "2026-06-26T00:10:00Z",
                "deleted_at": null,
                "schema_version": 1
              }
            ]
        """.trimIndent()

        val client = mockHttpClient(200, responseBody)
        val gateway = SupabaseRemotePullGateway(client, sessionProvider, "https://example.supabase.co", "key", true)

        val result = gateway.pullFoodEntries(null, 10)
        assertTrue(result is RemotePullResult.Success)
        val items = (result as RemotePullResult.Success).items
        assertEquals(1, items.size)
        val food = items[0]
        assertEquals("food-123", food.clientId)
        assertNull(food.fiberG)
        assertEquals(7.0f, food.carbsG)
        assertEquals(2.8f, food.proteinG)
        assertEquals(0.4f, food.fatG)
    }

    private fun assertNotNull(actual: Any?) {
        assertTrue(actual != null)
    }
}
