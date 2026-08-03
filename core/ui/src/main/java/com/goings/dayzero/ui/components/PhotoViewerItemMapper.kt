package com.goings.dayzero.ui.components

import com.goings.dayzero.domain.model.media.MediaAsset

/** Preserves the caller's ordered ids, including references not currently available locally. */
fun List<String>?.toPhotoViewerItems(mediaById: Map<String, MediaAsset>): List<PhotoViewerItem> =
    this?.mapIndexed { index, id ->
        val asset = mediaById[id]
        PhotoViewerItem(
            mediaId = id,
            masterRelativePath = asset?.masterRelativePath,
            thumbnailRelativePath = asset?.thumbnailRelativePath,
            width = asset?.width,
            height = asset?.height,
            accessibilityLabel = "图片 ${index + 1}，共 $size 张"
        )
    }.orEmpty()
