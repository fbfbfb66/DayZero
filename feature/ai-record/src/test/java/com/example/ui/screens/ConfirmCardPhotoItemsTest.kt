package com.example.ui.screens

import com.example.domain.model.media.MediaAsset
import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.model.media.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmCardPhotoItemsTest {
    @Test fun resolvesOnlyCurrentMealInOrderAndKeepsMissingPlaceholderIndex() {
        val asset = MediaAsset("m2", "owner", "conv", "user", 2, "master/m2", "thumb/m2", "image/jpeg",
            10, 20, 30, "hash", MediaSource.CAMERA, MediaLifecycleState.READY, null, 1, 1, null)
        val items = listOf("m1", "m2").toPhotoViewerItems(mapOf("m2" to asset, "other" to asset.copy(id = "other")))
        assertEquals(listOf("m1", "m2"), items.map { it.mediaId })
        assertNull(items[0].thumbnailRelativePath)
        assertEquals("thumb/m2", items[1].thumbnailRelativePath)
    }

    @Test fun nullAndEmptyAssignmentsProduceNoStripItems() {
        assertEquals(emptyList<Any>(), (null as List<String>?).toPhotoViewerItems(emptyMap()))
        assertEquals(emptyList<Any>(), emptyList<String>().toPhotoViewerItems(emptyMap()))
    }
}
