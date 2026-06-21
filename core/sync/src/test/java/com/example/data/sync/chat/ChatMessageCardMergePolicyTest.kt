package com.example.data.sync.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatMessageCardMergePolicyTest {
    private val policy = ChatMessageCardMergePolicy()

    @Test
    fun preservesNullAndEmptyArrayAsDistinctValues() {
        assertNull(policy.mergeAssistantCards("m1", null, null, preferRemoteSnapshot = true))
        assertEquals("[]", policy.mergeAssistantCards("m1", null, "[]", preferRemoteSnapshot = true))
        assertFalse(policy.semanticallyEqual(null, "[]"))
    }

    @Test
    fun showConfirmStateIsMonotonicAndPreservesUnknownFieldsMealsAndWeight() {
        val local = """
            [{"type":"show_confirm_card","id":"card-1","state":"confirmed","resolved":true,"weightKg":70.5,
              "meals":[{"mealType":"Dinner","items":[{"name":"rice","calories":300}]}],
              "localOnly":{"keep":true}}]
        """.trimIndent()
        val remote = """
            [{"type":"show_confirm_card","id":"card-1","state":"cancelled","resolved":true,"remoteOnly":{"keep":true}}]
        """.trimIndent()

        val merged = JSONArray(policy.mergeAssistantCards("m1", local, remote, preferRemoteSnapshot = true))
            .getJSONObject(0)

        assertEquals("confirmed", merged.getString("state"))
        assertEquals(70.5, merged.getDouble("weightKg"), 0.001)
        assertTrue(merged.getJSONObject("localOnly").getBoolean("keep"))
        assertTrue(merged.getJSONObject("remoteOnly").getBoolean("keep"))
        assertEquals("rice", merged.getJSONArray("meals").getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("name"))
    }

    @Test
    fun showConfirmAllowsPendingToCancelledOrConfirmedButNeverTerminalToPending() {
        assertEquals("cancelled", mergeShowState("pending", "cancelled"))
        assertEquals("confirmed", mergeShowState("pending", "confirmed"))
        assertEquals("confirmed", mergeShowState("confirmed", "pending"))
        assertEquals("cancelled", mergeShowState("cancelled", "pending"))
        assertEquals("confirmed", mergeShowState("cancelled", "confirmed"))
    }

    @Test
    fun dateMismatchGuardStateMergesWithNestedOriginalCard() {
        val approved = """
            [{"type":"date_mismatch_guard_card","id":"guard-1","state":"approved",
              "pendingOriginalCard":{"type":"show_confirm_card","id":"card-1","state":"confirmed","unknownNested":"keep"}}]
        """.trimIndent()
        val cancelled = """
            [{"type":"date_mismatch_guard_card","id":"guard-1","state":"cancelled",
              "pendingOriginalCard":{"type":"show_confirm_card","id":"card-1","state":"cancelled","meals":[{"mealType":"Lunch"}]}}]
        """.trimIndent()

        val merged = JSONArray(policy.mergeAssistantCards("m1", approved, cancelled, preferRemoteSnapshot = true))
            .getJSONObject(0)

        assertEquals("approved", merged.getString("state"))
        val original = merged.getJSONObject("pendingOriginalCard")
        assertEquals("confirmed", original.getString("state"))
        assertEquals("keep", original.getString("unknownNested"))
        assertEquals("Lunch", original.getJSONArray("meals").getJSONObject(0).getString("mealType"))
    }

    @Test
    fun dateMismatchGuardCancelledWinsWhenOriginalIsNotConfirmedAndTerminalNeverReturnsToPending() {
        val approved = guard("approved", originalState = "pending")
        val cancelled = guard("cancelled", originalState = "cancelled")
        val pending = guard("pending", originalState = "pending")

        val mergedConflict = JSONArray(policy.mergeAssistantCards("m1", approved, cancelled, preferRemoteSnapshot = true))
            .getJSONObject(0)
        assertEquals("cancelled", mergedConflict.getString("state"))
        assertEquals("cancelled", mergedConflict.getJSONObject("pendingOriginalCard").getString("state"))

        val terminalVsPending = JSONArray(policy.mergeAssistantCards("m1", cancelled, pending, preferRemoteSnapshot = true))
            .getJSONObject(0)
        assertEquals("cancelled", terminalVsPending.getString("state"))
    }

    @Test(expected = CardMergeConflictException::class)
    fun sameIdDifferentTypeFailsWholePage() {
        policy.mergeAssistantCards(
            "m1",
            """[{"type":"show_confirm_card","id":"same","state":"pending"}]""",
            """[{"type":"date_mismatch_guard_card","id":"same","state":"pending"}]""",
            preferRemoteSnapshot = true
        )
    }

    @Test
    fun multiCardUnknownCardsAndKeyOrderArePreserved() {
        val local = """[{"type":"unknown_card","id":"u1","nested":{"a":1}},{"type":"show_confirm_card","id":"c1","state":"pending"}]"""
        val remote = """[{"id":"c1","type":"show_confirm_card","state":"confirmed","remote":true},{"type":"another_unknown","id":"u2","payload":{"x":[1,2]}}]"""

        val merged = JSONArray(policy.mergeAssistantCards("m1", local, remote, preferRemoteSnapshot = true))

        assertEquals(3, merged.length())
        assertEquals("unknown_card", merged.getJSONObject(0).getString("type"))
        assertEquals("confirmed", merged.getJSONObject(1).getString("state"))
        assertEquals(2, merged.getJSONObject(2).getJSONObject("payload").getJSONArray("x").length())
        assertTrue(policy.semanticallyEqual("""{"b":2,"a":1}""", """{"a":1,"b":2}"""))
    }

    private fun mergeShowState(local: String, remote: String): String {
        val merged = policy.mergeAssistantCards(
            "m1",
            """[{"type":"show_confirm_card","id":"card","state":"$local"}]""",
            """[{"type":"show_confirm_card","id":"card","state":"$remote"}]""",
            preferRemoteSnapshot = true
        )
        return JSONArray(merged).getJSONObject(0).getString("state")
    }

    private fun guard(state: String, originalState: String): String {
        return JSONObject()
            .put("type", "date_mismatch_guard_card")
            .put("id", "guard-1")
            .put("state", state)
            .put(
                "pendingOriginalCard",
                JSONObject()
                    .put("type", "show_confirm_card")
                    .put("id", "card-1")
                    .put("state", originalState)
            )
            .let { JSONArray().put(it).toString() }
    }
}
