package com.goings.dayzero.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goings.dayzero.ui.theme.TextTertiary
import kotlin.math.abs

object PinnedPhotoStripTestTags {
    const val Strip = "pinned_photo_strip"
    const val EditEntry = "pinned_photo_strip_edit_entry"
    fun photo(index: Int) = "pinned_photo_$index"
}

/**
 * Quiet journal-style photo strip for one meal on a confirmation card.
 *
 * Photos are the visual subject: a thin warm mat, restrained stable rotation,
 * a single weak tape accent and a soft small shadow. No entry animation is
 * replayed on recomposition. When [onEditClick] is provided, a weak trailing
 * text entry combining the count and the edit affordance is shown; otherwise
 * only a de-emphasized count appears for multi-photo strips.
 */
@Composable
fun PinnedPhotoStrip(
    items: List<PhotoViewerItem>,
    onPhotoClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    showCount: Boolean = true,
    onEditClick: (() -> Unit)? = null,
    editLabel: String = "整理照片"
) {
    if (items.isEmpty()) return

    val totalCount = items.size
    val semanticsLabel = "餐次照片，共 $totalCount 张"

    Column(
        modifier = modifier
            .testTag(PinnedPhotoStripTestTags.Strip)
            .semantics(mergeDescendants = false) {
                contentDescription = semanticsLabel
            }
    ) {
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier.horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(2.dp))
            items.forEachIndexed { index, item ->
                JournalPhoto(
                    item = item,
                    index = index,
                    totalCount = totalCount,
                    rotation = rememberStableRotation(item.mediaId, index, totalCount),
                    verticalOffsetDp = PinnedPhotoStripLogic.verticalOffsetDp(index, totalCount),
                    onClick = { onPhotoClick(index) }
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
        }

        val metaText = when {
            onEditClick != null -> if (totalCount > 1) "$editLabel · $totalCount 张" else editLabel
            showCount && totalCount > 1 -> "$totalCount 张"
            else -> null
        }
        if (metaText != null) {
            Row(
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                if (onEditClick != null) {
                    Text(
                        text = metaText,
                        color = TextTertiary,
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onEditClick
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag(PinnedPhotoStripTestTags.EditEntry)
                            .semantics {
                                role = Role.Button
                                contentDescription = "整理餐次照片，共 $totalCount 张"
                            }
                    )
                } else {
                    Text(
                        text = metaText,
                        color = TextTertiary,
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalPhoto(
    item: PhotoViewerItem,
    index: Int,
    totalCount: Int,
    rotation: Float,
    verticalOffsetDp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemLabel = item.accessibilityLabel ?: "照片 ${index + 1}，共 $totalCount 张"
    Box(
        modifier = modifier
            .padding(top = 16.dp, bottom = 12.dp)
            .offset(y = verticalOffsetDp.dp)
            .graphicsLayer { rotationZ = rotation }
    ) {
        PinnedTornPaperPhoto(
            item = item,
            photoSize = 84.dp,
            onClick = onClick,
            contentDescription = itemLabel,
            modifier = Modifier.testTag(PinnedPhotoStripTestTags.photo(index))
        )
    }
}

/**
 * The new photo presentation using a torn paper frame and a push pin.
 */
@Composable
fun PinnedTornPaperPhoto(
    item: PhotoViewerItem,
    photoSize: Dp = 210.dp,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    // Determine responsive size
    // In a horizontal scroll strip, we can give it a fixed but reasonably large size
    // The user requested ~200dp - 230dp for single photo.
    // We'll use 210.dp default width. The original frame is 1068x1065, nearly 1:1.
    val containerSize = photoSize

    // The pin is usually 16-20% of the frame width.
    val pinSize = (containerSize * 0.18f).coerceAtLeast(16.dp)
    val pinOverlapOffset = -(pinSize * 0.35f) // Pin overlaps top by about 35% of its height

    // The inner padding for the photo to fit inside the torn paper frame
    val photoPaddingLeft = containerSize * 0.08f
    val photoPaddingTop = containerSize * 0.08f
    val photoPaddingRight = containerSize * 0.08f
    val photoPaddingBottom = containerSize * 0.08f

    val isDark = isSystemInDarkTheme()
    val shadowAmbient = Color.Black.copy(alpha = if (isDark) 0.15f else 0.04f)
    val shadowSpot = Color.Black.copy(alpha = if (isDark) 0.20f else 0.08f)

    Box(
        modifier = modifier
            .width(containerSize)
            // No strict height, let the frame image dictate aspect ratio, or force 1:1
            .height(containerSize)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.contentDescription = contentDescription
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        // 1. Shadow and Frame + Photo combination box
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pinSize * 0.25f) // Leave room at top for pin sticking out
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(2.dp),
                    ambientColor = shadowAmbient,
                    spotColor = shadowSpot
                )
        ) {
            // Layer 1: Photo
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = photoPaddingLeft,
                        top = photoPaddingTop,
                        end = photoPaddingRight,
                        bottom = photoPaddingBottom
                    )
                    .clip(RoundedCornerShape(1.dp))
            ) {
                // Background color in case photo is missing or transparent
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE0E0E0).copy(alpha = if (isDark) 0.1f else 0.3f)))
                
                LocalMediaThumbnail(
                    thumbnailRelativePath = item.thumbnailRelativePath,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null
                )
            }

            // Layer 2: Torn Paper Frame
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.goings.dayzero.core.ui.R.drawable.dayzero_torn_paper_frame),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds
            )
        }

        // Layer 3: Push Pin
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = com.goings.dayzero.core.ui.R.drawable.dayzero_push_pin_top),
            contentDescription = null,
            modifier = Modifier
                .size(pinSize)
                .offset(y = pinOverlapOffset),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    }
}

@Composable
private fun rememberStableRotation(mediaId: String, index: Int, totalCount: Int): Float {
    return remember(mediaId, index, totalCount) {
        PinnedPhotoStripLogic.calculateStableRotation(mediaId, index, totalCount)
    }
}

internal object PinnedPhotoStripLogic {
    /** Max rotation magnitude in degrees for multi-photo strips. */
    const val MAX_ROTATION_DEGREES = 1.6f

    /**
     * Stable, restrained rotation per photo. A lone photo lies flat; in a
     * multi-photo strip the angle is derived only from the mediaId so it never
     * changes across recompositions, and both ends are damped.
     */
    fun calculateStableRotation(mediaId: String, index: Int, totalCount: Int): Float {
        if (totalCount <= 1) return 0f
        val hash = mediaId.hashCode()
        val sign = if (hash % 2 == 0) 1f else -1f
        val magnitude = (abs(hash) % 17) / 10f // 0.0 .. 1.6
        val damping = if (index == 0 || index == totalCount - 1) 0.75f else 1f
        return sign * magnitude * damping
    }

    /** Gentle stair-step layering so multiple photos read as placed, not machine-aligned. */
    fun verticalOffsetDp(index: Int, totalCount: Int): Float {
        if (totalCount <= 1) return 0f
        return if (index % 2 == 1) 3f else 0f
    }

    /** Tape angle offset, stable per mediaId and always small. */
    fun tapeRotation(mediaId: String): Float {
        val hash = mediaId.hashCode()
        val sign = if ((hash / 7) % 2 == 0) 1f else -1f
        return sign * ((abs(hash / 3) % 5) + 2f) // 2 .. 6 degrees
    }
}

// region Previews

private fun previewItems(count: Int, missingIndices: Set<Int> = emptySet()): List<PhotoViewerItem> =
    (1..count).map { i ->
        PhotoViewerItem(
            mediaId = "preview-$i",
            masterRelativePath = null,
            thumbnailRelativePath = null,
            width = null,
            height = null,
            accessibilityLabel = if ((i - 1) in missingIndices) "图片未找到 $i，共 $count 张" else "图片 $i，共 $count 张"
        )
    }

@Preview(name = "1 photo", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewPinnedPhotoStrip1() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(1), onPhotoClick = {}, onEditClick = {})
    }
}

@Preview(name = "2 photos", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewPinnedPhotoStrip2() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(2), onPhotoClick = {}, onEditClick = {})
    }
}

@Preview(name = "4 photos", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewPinnedPhotoStrip4() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(4), onPhotoClick = {}, onEditClick = {})
    }
}

@Preview(name = "6 photos", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewPinnedPhotoStrip6() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(6), onPhotoClick = {}, onEditClick = {})
    }
}

@Preview(name = "missing photo", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PreviewPinnedPhotoStripMissing() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(3, missingIndices = setOf(1)), onPhotoClick = {})
    }
}

@Preview(name = "narrow screen", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 280)
@Composable
private fun PreviewPinnedPhotoStripNarrow() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(5), onPhotoClick = {}, onEditClick = {})
    }
}

@Preview(name = "large font", showBackground = true, backgroundColor = 0xFFFFFFFF, fontScale = 1.6f)
@Composable
private fun PreviewPinnedPhotoStripLargeFont() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(3), onPhotoClick = {}, onEditClick = {})
    }
}

@Preview(
    name = "dark",
    showBackground = true,
    backgroundColor = 0xFF2A2925,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PreviewPinnedPhotoStripDark() {
    MaterialTheme {
        PinnedPhotoStrip(items = previewItems(4), onPhotoClick = {}, onEditClick = {})
    }
}

// endregion
