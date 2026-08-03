package com.goings.dayzero.data.sync

import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.FoodEntry
import com.goings.dayzero.domain.model.MealEntry
import org.json.JSONObject
import org.json.JSONArray

class SyncPayloadBuilder {
    fun dailyRecordPayload(record: DailyRecord, identity: AppIdentity): JSONObject {
        return JSONObject()
            .put("clientId", record.id)
            .put("ownerLocalId", identity.localOwnerId)
            .put("remoteUserId", identity.remoteUserId)
            .put("authProvider", identity.authProvider)
            .put("canRemoteSync", identity.canRemoteSync)
            .put("date", record.date.toString())
            .put("status", record.status.name)
            .put("totalCalories", record.totalCalories)
            .put("weightKg", record.weightKg)
            .put("aiSummary", record.aiSummary)
            .put("schemaVersion", 1)
    }

    fun mealPayload(recordId: String, meal: MealEntry, identity: AppIdentity): JSONObject {
        return JSONObject()
            .put("clientId", meal.id)
            .put("ownerLocalId", identity.localOwnerId)
            .put("dailyRecordClientId", recordId)
            .put("mealType", meal.mealType.name)
            .put("mediaIds", JSONArray(meal.mediaIds))
            .put("subtotalCalories", meal.mealCalories)
            .put("schemaVersion", 2)
    }

    fun foodPayload(recordId: String, mealId: String, food: FoodEntry, identity: AppIdentity): JSONObject {
        return JSONObject()
            .put("clientId", food.id)
            .put("ownerLocalId", identity.localOwnerId)
            .put("dailyRecordClientId", recordId)
            .put("mealClientId", mealId)
            .put("name", food.name)
            .put("quantity", food.quantity)
            .put("estimatedCalories", food.estimatedCalories)
            .put("confidence", food.confidence)
            .put("carbsG", food.carbohydratesG?.toDouble() ?: JSONObject.NULL)
            .put("proteinG", food.proteinG?.toDouble() ?: JSONObject.NULL)
            .put("fatG", food.fatG?.toDouble() ?: JSONObject.NULL)
            .put("fiberG", food.fiberG?.toDouble() ?: JSONObject.NULL)
            .put("schemaVersion", 1)
    }

    fun weightPayload(recordId: String, date: String, weightKg: Float, identity: AppIdentity): JSONObject {
        return JSONObject()
            .put("clientId", "$recordId:weight")
            .put("ownerLocalId", identity.localOwnerId)
            .put("dailyRecordClientId", recordId)
            .put("measuredDate", date)
            .put("weightKg", weightKg)
            .put("source", "confirm_card")
            .put("schemaVersion", 1)
    }

    fun softDeletePayload(recordId: String, identity: AppIdentity, deletedAt: Long): JSONObject {
        return JSONObject()
            .put("clientId", recordId)
            .put("ownerLocalId", identity.localOwnerId)
            .put("authProvider", identity.authProvider)
            .put("deletedAt", deletedAt)
            .put("schemaVersion", 1)
    }
}
