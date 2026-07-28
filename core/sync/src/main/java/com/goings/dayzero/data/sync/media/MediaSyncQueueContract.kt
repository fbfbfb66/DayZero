package com.goings.dayzero.data.sync.media

object MediaSyncQueueContract {
    const val ENTITY_MEDIA_ASSET = "media_asset"

    const val OP_UPSERT_MEDIA_ASSET = "UPSERT_MEDIA_ASSET"
    const val OP_DOWNLOAD_MEDIA_ASSET = "DOWNLOAD_MEDIA_ASSET"
    const val OP_SOFT_DELETE_MEDIA_ASSET = "SOFT_DELETE_MEDIA_ASSET"

    const val MEDIA_SYNC_SCHEMA_VERSION = 1

    val allEntityTypes = setOf(ENTITY_MEDIA_ASSET)
    val allOperations = setOf(
        OP_UPSERT_MEDIA_ASSET,
        OP_DOWNLOAD_MEDIA_ASSET,
        OP_SOFT_DELETE_MEDIA_ASSET
    )
}
