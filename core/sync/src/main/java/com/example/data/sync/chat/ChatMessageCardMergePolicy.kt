package com.example.data.sync.chat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

class CardMergeConflictException(
    val messageId: String,
    val cardId: String,
    val reason: String
) : RuntimeException("Card merge conflict messageId=$messageId cardId=$cardId reason=$reason")

class ChatMessageCardMergePolicy {

    fun mergeAssistantCards(
        messageId: String,
        localRaw: String?,
        remoteRaw: String?,
        preferRemoteSnapshot: Boolean
    ): String? {
        if (localRaw == null && remoteRaw == null) return null
        if (localRaw == null) return validateCardsArray(messageId, remoteRaw)
        if (remoteRaw == null) return validateCardsArray(messageId, localRaw)

        val local = parseArray(messageId, localRaw)
        val remote = parseArray(messageId, remoteRaw)
        val merged = JSONArray()
        val remoteByKey = linkedMapOf<String, JSONObject>()
        val remoteWithoutKey = mutableListOf<JSONObject>()

        for (index in 0 until remote.length()) {
            val card = remote.optJSONObject(index)
                ?: throw CardMergeConflictException(messageId, "index-$index", "card_not_object")
            val key = cardKey(card, index)
            if (key == null) {
                remoteWithoutKey += card
            } else {
                remoteByKey[key] = card
            }
        }

        val consumedRemoteKeys = mutableSetOf<String>()
        for (index in 0 until local.length()) {
            val localCard = local.optJSONObject(index)
                ?: throw CardMergeConflictException(messageId, "index-$index", "card_not_object")
            val key = cardKey(localCard, index)
            val remoteCard = key?.let { remoteByKey[it] }
            if (remoteCard == null) {
                merged.put(cloneJsonObject(localCard))
                continue
            }
            consumedRemoteKeys += key
            merged.put(mergeCard(messageId, localCard, remoteCard, preferRemoteSnapshot))
        }

        for ((key, remoteCard) in remoteByKey) {
            if (key !in consumedRemoteKeys) {
                merged.put(cloneJsonObject(remoteCard))
            }
        }
        remoteWithoutKey.forEach { merged.put(cloneJsonObject(it)) }

        return merged.toString()
    }

    fun semanticallyEqual(leftRaw: String?, rightRaw: String?): Boolean {
        if (leftRaw == null || rightRaw == null) return leftRaw == rightRaw
        return try {
            val left = parseJson(leftRaw)
            val right = parseJson(rightRaw)
            jsonSimilar(left, right)
        } catch (_: JSONException) {
            leftRaw == rightRaw
        }
    }

    private fun validateCardsArray(messageId: String, raw: String?): String? {
        if (raw == null) return null
        parseArray(messageId, raw)
        return raw
    }

    private fun parseArray(messageId: String, raw: String): JSONArray {
        return try {
            val value = parseJson(raw)
            value as? JSONArray ?: throw CardMergeConflictException(messageId, "unknown", "cards_not_array")
        } catch (e: JSONException) {
            throw CardMergeConflictException(messageId, "unknown", "cards_invalid_json:${e::class.java.simpleName}")
        }
    }

    private fun parseJson(raw: String): Any {
        val value = JSONTokener(raw).nextValue()
        return when (value) {
            is JSONObject, is JSONArray -> value
            JSONObject.NULL -> JSONObject.NULL
            else -> value
        }
    }

    private fun cardKey(card: JSONObject, index: Int): String? {
        val id = card.optString("id").takeIf { it.isNotBlank() } ?: return null
        return id
    }

    private fun mergeCard(
        messageId: String,
        local: JSONObject,
        remote: JSONObject,
        preferRemoteSnapshot: Boolean
    ): JSONObject {
        val id = local.optString("id").takeIf { it.isNotBlank() }
            ?: remote.optString("id").takeIf { it.isNotBlank() }
            ?: "unknown"
        val localType = local.optString("type").takeIf { it.isNotBlank() }
        val remoteType = remote.optString("type").takeIf { it.isNotBlank() }
        if (localType != null && remoteType != null && localType != remoteType) {
            throw CardMergeConflictException(messageId, id, "type_mismatch")
        }
        val type = remoteType ?: localType
        return when (type) {
            TYPE_SHOW_CONFIRM -> mergeShowConfirmCard(local, remote, preferRemoteSnapshot)
            TYPE_DATE_MISMATCH_GUARD -> mergeDateMismatchGuardCard(local, remote, preferRemoteSnapshot)
            else -> mergeGenericCard(local, remote, preferRemoteSnapshot)
        }
    }

    private fun mergeShowConfirmCard(
        local: JSONObject,
        remote: JSONObject,
        preferRemoteSnapshot: Boolean
    ): JSONObject {
        val localState = normalizeShowConfirmState(local.optString("state", STATE_PENDING))
        val remoteState = normalizeShowConfirmState(remote.optString("state", STATE_PENDING))
        val winningState = if (showConfirmRank(remoteState) >= showConfirmRank(localState)) remoteState else localState
        val base = when {
            remoteState == winningState && localState != winningState -> remote
            localState == winningState && remoteState != winningState -> local
            preferRemoteSnapshot -> remote
            else -> local
        }
        val merged = deepMergeObjects(base, if (base === remote) local else remote)
        merged.put("state", winningState)
        if (winningState != STATE_PENDING && merged.has("resolved")) {
            merged.put("resolved", true)
        }
        return merged
    }

    private fun mergeDateMismatchGuardCard(
        local: JSONObject,
        remote: JSONObject,
        preferRemoteSnapshot: Boolean
    ): JSONObject {
        val base = if (preferRemoteSnapshot) remote else local
        val other = if (preferRemoteSnapshot) local else remote
        val merged = deepMergeObjects(base, other)

        val localOriginal = local.optJSONObject("pendingOriginalCard")
        val remoteOriginal = remote.optJSONObject("pendingOriginalCard")
        val mergedOriginal = when {
            localOriginal != null && remoteOriginal != null ->
                mergeShowConfirmCard(localOriginal, remoteOriginal, preferRemoteSnapshot)
            remoteOriginal != null -> cloneJsonObject(remoteOriginal)
            localOriginal != null -> cloneJsonObject(localOriginal)
            else -> null
        }
        if (mergedOriginal != null) {
            merged.put("pendingOriginalCard", mergedOriginal)
        }

        val localState = normalizeGuardState(local.optString("state", STATE_PENDING))
        val remoteState = normalizeGuardState(remote.optString("state", STATE_PENDING))
        val originalConfirmed = mergedOriginal?.optString("state") == STATE_CONFIRMED
        val winningState = mergeGuardState(localState, remoteState, originalConfirmed)
        merged.put("state", winningState)
        return merged
    }

    private fun mergeGenericCard(
        local: JSONObject,
        remote: JSONObject,
        preferRemoteSnapshot: Boolean
    ): JSONObject {
        val base = if (preferRemoteSnapshot) remote else local
        val other = if (preferRemoteSnapshot) local else remote
        return deepMergeObjects(base, other)
    }

    private fun mergeGuardState(local: String, remote: String, originalConfirmed: Boolean): String {
        if (local == STATE_PENDING) return remote
        if (remote == STATE_PENDING) return local
        if (local == remote) return local
        return if (originalConfirmed) STATE_APPROVED else STATE_CANCELLED
    }

    private fun normalizeShowConfirmState(state: String): String {
        return when (state) {
            STATE_CONFIRMED -> STATE_CONFIRMED
            STATE_CANCELLED -> STATE_CANCELLED
            else -> STATE_PENDING
        }
    }

    private fun normalizeGuardState(state: String): String {
        return when (state) {
            STATE_APPROVED -> STATE_APPROVED
            STATE_CANCELLED -> STATE_CANCELLED
            else -> STATE_PENDING
        }
    }

    private fun showConfirmRank(state: String): Int {
        return when (state) {
            STATE_CONFIRMED -> 2
            STATE_CANCELLED -> 1
            else -> 0
        }
    }

    private fun deepMergeObjects(primary: JSONObject, fallback: JSONObject): JSONObject {
        val result = cloneJsonObject(primary)
        val keys = fallback.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!result.has(key) || result.isNull(key)) {
                result.put(key, cloneJsonValue(fallback.get(key)))
            } else {
                val primaryValue = result.get(key)
                val fallbackValue = fallback.get(key)
                if (primaryValue is JSONObject && fallbackValue is JSONObject) {
                    result.put(key, deepMergeObjects(primaryValue, fallbackValue))
                }
            }
        }
        return result
    }

    private fun cloneJsonObject(source: JSONObject): JSONObject {
        return JSONObject(source.toString())
    }

    private fun cloneJsonValue(value: Any?): Any? {
        return when (value) {
            is JSONObject -> JSONObject(value.toString())
            is JSONArray -> JSONArray(value.toString())
            else -> value
        }
    }

    private fun jsonSimilar(left: Any?, right: Any?): Boolean {
        return when {
            left is JSONObject && right is JSONObject -> jsonObjectSimilar(left, right)
            left is JSONArray && right is JSONArray -> jsonArraySimilar(left, right)
            left == JSONObject.NULL && right == null -> false
            left == null && right == JSONObject.NULL -> false
            else -> left == right
        }
    }

    private fun jsonObjectSimilar(left: JSONObject, right: JSONObject): Boolean {
        val leftKeys = left.keys().asSequence().toSet()
        val rightKeys = right.keys().asSequence().toSet()
        if (leftKeys != rightKeys) return false
        return leftKeys.all { key -> jsonSimilar(left.opt(key), right.opt(key)) }
    }

    private fun jsonArraySimilar(left: JSONArray, right: JSONArray): Boolean {
        if (left.length() != right.length()) return false
        for (index in 0 until left.length()) {
            if (!jsonSimilar(left.opt(index), right.opt(index))) return false
        }
        return true
    }

    companion object {
        private const val TYPE_SHOW_CONFIRM = "show_confirm_card"
        private const val TYPE_DATE_MISMATCH_GUARD = "date_mismatch_guard_card"
        private const val STATE_PENDING = "pending"
        private const val STATE_CANCELLED = "cancelled"
        private const val STATE_CONFIRMED = "confirmed"
        private const val STATE_APPROVED = "approved"
    }
}
