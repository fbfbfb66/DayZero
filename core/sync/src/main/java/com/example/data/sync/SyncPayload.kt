package com.example.data.sync

import org.json.JSONObject

data class SyncPayload(
    val queueId: String,
    val entityType: String,
    val entityLocalId: String,
    val operation: String,
    val ownerLocalId: String,
    val body: JSONObject
)
