package com.example.ui.screens.photoeditor

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.domain.model.media.MediaAsset
import com.example.ui.components.JournalPhotoTile
import com.example.ui.components.PhotoViewerItem
import com.example.ui.theme.BorderNormal
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.BrandRed
import com.example.ui.theme.CardBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.WarmBackground
import kotlinx.coroutines.launch

object PhotoEditorTestTags {
    const val Root = "photo_editor_root"
    const val Cancel = "photo_editor_cancel"
    const val Save = "photo_editor_save"
    const val Deck = "photo_editor_deck"
    const val UnassignedCaption = "photo_editor_unassigned_caption"
    const val CompletionState = "photo_editor_completion"
    const val DiscardDialog = "photo_editor_discard_dialog"
    const val ErrorBanner = "photo_editor_error_banner"
    fun mealChip(index: Int) = "photo_editor_meal_chip_$index"
    fun deckCard(mediaId: String) = "photo_editor_deck_card_$mediaId"
    fun wallTile(mediaId: String) = "photo_editor_wall_tile_$mediaId"
    fun wallRemove(mediaId: String) = "photo_editor_wall_remove_$mediaId"
    fun wallMove(mediaId: String) = "photo_editor_wall_move_$mediaId"
    fun wallMoveTarget(mediaId: String, targetMealIndex: Int) = "photo_editor_wall_move_${mediaId}_to_$targetMealIndex"
}

/** Action surface of the editor; every callback only touches the local edit session. */
class PhotoAssignmentEditorActions(
    val onSelectMeal: (Int) -> Unit,
    val onAssignToSelectedMeal: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onMove: (String, Int) -> Unit,
    val onRequestClose: () -> Unit,
    val onDismissDiscard: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onSave: () -> Unit
)

/**
 * Immersive full-screen photo assignment editor. Hosted as an overlay inside
 * the conversation screen (same pattern as PhotoViewerOverlay) so the session
 * state lives in the ViewModel and the existing viewer host stays reusable
 * above it.
 */
@Composable
fun PhotoAssignmentEditorScreen(
    state: PhotoAssignmentEditorUiState,
    mediaById: Map<String, MediaAsset>,
    actions: PhotoAssignmentEditorActions,
    onOpenViewer: (List<PhotoViewerItem>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = true) { actions.onRequestClose() }

    val unassignedIds = state.draft.unassignedIds
    val wallIds = state.draft.assignedTo(state.selectedMealIndex)
    val deckItems = remember(unassignedIds, mediaById) {
        unassignedIds.mapIndexed { index, id ->
            editorViewerItem(id, index, unassignedIds.size, mediaById, ownership = "未分配")
        }
    }
    val wallItems = remember(wallIds, mediaById, state.selectedMealIndex, state.mealLabels) {
        val label = state.mealLabels.getOrNull(state.selectedMealIndex).orEmpty()
        wallIds.mapIndexed { index, id ->
            editorViewerItem(id, index, wallIds.size, mediaById, ownership = "已分配到$label")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBackground)
            .testTag(PhotoEditorTestTags.Root)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        EditorTopBar(
            isSaving = state.isSaving,
            saveEnabled = !state.isSaving && !state.cardNoLongerEditable,
            onCancel = actions.onRequestClose,
            onSave = actions.onSave
        )

        MealSwitcherRow(
            mealLabels = state.mealLabels,
            selectedIndex = state.selectedMealIndex,
            countFor = { index -> state.draft.assignedTo(index).size },
            onSelect = actions.onSelectMeal
        )

        Box(modifier = Modifier.weight(1f)) {
            if (deckItems.isEmpty()) {
                CompletionState(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "未分配 ${deckItems.size} 张 · 点按中间的照片分到当前餐次",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, start = 24.dp, end = 24.dp)
                            .testTag(PhotoEditorTestTags.UnassignedCaption)
                            .semantics { contentDescription = "未分配照片 ${deckItems.size} 张" }
                    )
                    FanPhotoDeck(
                        items = deckItems,
                        onCenterCardTap = { index ->
                            val id = unassignedIds.getOrNull(index) ?: return@FanPhotoDeck
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onAssignToSelectedMeal(id)
                        },
                        currentMealLabel = state.mealLabels.getOrNull(state.selectedMealIndex).orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        if (state.saveError != null) {
            SaveErrorBanner(
                message = state.saveError,
                showRetry = !state.cardNoLongerEditable,
                onRetry = actions.onSave,
                onExit = actions.onConfirmDiscard
            )
        }

        CurrentMealWall(
            mealLabel = state.mealLabels.getOrNull(state.selectedMealIndex).orEmpty(),
            items = wallItems,
            wallIds = wallIds,
            mealLabels = state.mealLabels,
            selectedMealIndex = state.selectedMealIndex,
            onTileClick = { index -> onOpenViewer(wallItems, index) },
            onRemove = { id ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.onRemove(id)
            },
            onMove = { id, target ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.onMove(id, target)
            }
        )
    }

    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = actions.onDismissDiscard,
            modifier = Modifier.testTag(PhotoEditorTestTags.DiscardDialog),
            title = { Text("放弃修改？") },
            text = { Text("照片整理还没有保存，退出后将保持原样。") },
            confirmButton = {
                TextButton(onClick = actions.onConfirmDiscard) {
                    Text("放弃", color = BrandRed)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissDiscard) {
                    Text("继续整理")
                }
            }
        )
    }
}

@Composable
private fun EditorTopBar(
    isSaving: Boolean,
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag(PhotoEditorTestTags.Cancel)
        ) {
            Text("取消", color = TextSecondary, fontSize = 15.sp)
        }
        Text(
            text = "整理餐次照片",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.testTag(PhotoEditorTestTags.Save)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = BrandGreen
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = "保存",
                color = if (saveEnabled) BrandGreen else TextTertiary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun MealSwitcherRow(
    mealLabels: List<String>,
    selectedIndex: Int,
    countFor: (Int) -> Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        mealLabels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val count = countFor(index)
            val text = if (count > 0) "$label · $count" else label
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isSelected) BrandGreen else CardBackground,
                border = if (isSelected) null else BorderStroke(1.dp, BorderNormal.copy(alpha = 0.6f)),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(index) }
                    .testTag(PhotoEditorTestTags.mealChip(index))
                    .semantics {
                        role = Role.Tab
                        selected = isSelected
                        contentDescription = "$label，已分配 $count 张照片" + if (isSelected) "，当前餐次" else ""
                    }
            ) {
                Text(
                    text = text,
                    color = if (isSelected) Color.White else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun CompletionState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .testTag(PhotoEditorTestTags.CompletionState)
            .semantics(mergeDescendants = true) {
                contentDescription = "所有照片都已分配"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "所有照片都已整理好",
            color = TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "可在下方查看或调整当前餐次的照片",
            color = TextTertiary,
            fontSize = 12.sp
        )
    }
}

/**
 * Central fan deck: horizontal drag pages through unassigned photos; releasing
 * snaps to the nearest slot with a light haptic tick; tapping the centered card
 * assigns it, tapping a side card brings it to the center.
 */
@Composable
fun FanPhotoDeck(
    items: List<PhotoViewerItem>,
    onCenterCardTap: (index: Int) -> Unit,
    currentMealLabel: String,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var centerIndex by rememberSaveable { mutableIntStateOf(0) }
    val safeCenter = centerIndex.coerceIn(0, items.lastIndex)

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val position = remember { Animatable(safeCenter.toFloat()) }

    // Keep the continuous position in range when the deck shrinks after an assignment.
    LaunchedEffect(items.size, safeCenter) {
        if (centerIndex != safeCenter) centerIndex = safeCenter
        if (position.value > items.lastIndex.toFloat() || position.value < 0f) {
            position.snapTo(safeCenter.toFloat())
        }
    }

    BoxWithConstraints(
        modifier = modifier.testTag(PhotoEditorTestTags.Deck),
        contentAlignment = Alignment.Center
    ) {
        val cardPhotoSize = (maxWidth * 0.52f).coerceAtMost(240.dp)
        val cardWidthPx = with(LocalDensity.current) { cardPhotoSize.toPx() }

        val draggableState = rememberDraggableState { delta ->
            val next = FanDeckMath.clampContinuousCenter(
                position.value + FanDeckMath.dragToSlotDelta(delta, cardWidthPx),
                items.size
            )
            scope.launch { position.snapTo(next) }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val target = FanDeckMath.snapTargetIndex(position.value, velocity, items.size)
                        if (target != centerIndex) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        centerIndex = target
                        position.animateTo(
                            target.toFloat(),
                            tween(durationMillis = 240, easing = FastOutSlowInEasing)
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            items.forEachIndexed { index, item ->
                val slotOffset = index - position.value
                val sinkDp = FanDeckMath.verticalSinkDpFor(slotOffset)
                val isCentered = index == safeCenter
                val a11y = (item.accessibilityLabel ?: "照片 ${index + 1}") +
                    if (isCentered) "，点按分配到$currentMealLabel" else "，点按移到中间"

                Box(
                    modifier = Modifier
                        .zIndex(FanDeckMath.zIndexFor(slotOffset))
                        .graphicsLayer {
                            translationX = FanDeckMath.translationXFor(slotOffset, cardWidthPx)
                            translationY = sinkDp.dp.toPx()
                            rotationZ = FanDeckMath.rotationFor(slotOffset)
                            scaleX = FanDeckMath.scaleFor(slotOffset)
                            scaleY = FanDeckMath.scaleFor(slotOffset)
                            alpha = FanDeckMath.alphaFor(slotOffset)
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (index == safeCenter) {
                                onCenterCardTap(index)
                            } else {
                                centerIndex = index
                                scope.launch {
                                    position.animateTo(
                                        index.toFloat(),
                                        tween(durationMillis = 240, easing = FastOutSlowInEasing)
                                    )
                                }
                            }
                        }
                        .testTag(PhotoEditorTestTags.deckCard(item.mediaId))
                        .semantics {
                            role = Role.Button
                            contentDescription = a11y
                        }
                ) {
                    JournalPhotoTile(
                        item = item,
                        photoSize = cardPhotoSize,
                        onClick = null
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentMealWall(
    mealLabel: String,
    items: List<PhotoViewerItem>,
    wallIds: List<String>,
    mealLabels: List<String>,
    selectedMealIndex: Int,
    onTileClick: (index: Int) -> Unit,
    onRemove: (mediaId: String) -> Unit,
    onMove: (mediaId: String, targetMealIndex: Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Text(
            text = if (mealLabel.isBlank()) "当前餐次照片" else "$mealLabel 的照片",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp)
        )
        if (items.isEmpty()) {
            Text(
                text = "还没有照片 · 点按上方中间的照片进行分配",
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(start = 20.dp, top = 10.dp, bottom = 18.dp)
                    .semantics { contentDescription = "当前餐次还没有分配照片" }
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(items = items, key = { _, item -> item.mediaId }) { index, item ->
                    WallTile(
                        item = item,
                        onClick = { onTileClick(index) },
                        onRemove = { onRemove(item.mediaId) },
                        moveTargets = mealLabels.mapIndexedNotNull { i, label ->
                            if (i == selectedMealIndex) null else i to label
                        },
                        onMove = { target -> onMove(item.mediaId, target) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WallTile(
    item: PhotoViewerItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    moveTargets: List<Pair<Int, String>>,
    onMove: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.padding(top = 6.dp)) {
            JournalPhotoTile(
                item = item,
                photoSize = 64.dp,
                onClick = onClick,
                contentDescription = item.accessibilityLabel,
                modifier = Modifier.testTag(PhotoEditorTestTags.wallTile(item.mediaId))
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "移除",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRemove
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag(PhotoEditorTestTags.wallRemove(item.mediaId))
                    .semantics {
                        role = Role.Button
                        contentDescription = "把这张照片移出当前餐次"
                    }
            )
            if (moveTargets.isNotEmpty()) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Text(
                        text = "移至",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { menuOpen = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag(PhotoEditorTestTags.wallMove(item.mediaId))
                            .semantics {
                                role = Role.Button
                                contentDescription = "把这张照片移到其他餐次"
                            }
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        moveTargets.forEach { (targetIndex, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                modifier = Modifier.testTag(PhotoEditorTestTags.wallMoveTarget(item.mediaId, targetIndex)),
                                onClick = {
                                    menuOpen = false
                                    onMove(targetIndex)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveErrorBanner(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(PhotoEditorTestTags.ErrorBanner),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFDE8E8),
        border = BorderStroke(1.dp, Color(0xFFE53E3E).copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = Color(0xFFB03030),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            if (showRetry) {
                TextButton(onClick = onRetry) {
                    Text("重试", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                TextButton(onClick = onExit) {
                    Text("退出", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun editorViewerItem(
    mediaId: String,
    index: Int,
    total: Int,
    mediaById: Map<String, MediaAsset>,
    ownership: String
): PhotoViewerItem {
    val asset = mediaById[mediaId]
    val base = if (asset == null) "图片未找到 ${index + 1}，共 $total 张" else "图片 ${index + 1}，共 $total 张"
    return PhotoViewerItem(
        mediaId = mediaId,
        masterRelativePath = asset?.masterRelativePath,
        thumbnailRelativePath = asset?.thumbnailRelativePath,
        width = asset?.width,
        height = asset?.height,
        accessibilityLabel = "$base，$ownership"
    )
}

// region Previews (no Room, no network, no real files — assets resolve to placeholders)

private fun previewState(
    mealLabels: List<String>,
    assignments: Map<Int, List<String>>,
    originCount: Int,
    selectedMealIndex: Int = 0,
    isSaving: Boolean = false,
    saveError: String? = null
): PhotoAssignmentEditorUiState {
    val origin = (1..originCount).map { "m$it" }
    val draft = PhotoAssignmentDraft(
        cardId = "card-preview",
        mealCount = mealLabels.size,
        originMediaIds = origin,
        assignments = assignments
    )
    return PhotoAssignmentEditorUiState(
        conversationId = "conv-preview",
        cardId = "card-preview",
        mealLabels = mealLabels,
        selectedMealIndex = selectedMealIndex,
        initialDraft = draft,
        draft = draft,
        isSaving = isSaving,
        saveError = saveError
    )
}

private val previewActions = PhotoAssignmentEditorActions(
    onSelectMeal = {},
    onAssignToSelectedMeal = {},
    onRemove = {},
    onMove = { _, _ -> },
    onRequestClose = {},
    onDismissDiscard = {},
    onConfirmDiscard = {},
    onSave = {}
)

@Preview(name = "single meal, 1 photo", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEditorSingleMealOnePhoto() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(listOf("午餐"), assignments = emptyMap(), originCount = 1),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(name = "single meal, 6 photos", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEditorSingleMealSixPhotos() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(listOf("晚餐"), assignments = mapOf(0 to listOf("m1", "m2")), originCount = 6),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(name = "multi meal, unassigned", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEditorMultiMealUnassigned() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(
                listOf("早餐", "午餐", "晚餐"),
                assignments = mapOf(0 to listOf("m1"), 1 to listOf("m2")),
                originCount = 5,
                selectedMealIndex = 1
            ),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(name = "multi meal, all assigned", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEditorMultiMealAllAssigned() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(
                listOf("早餐", "午餐"),
                assignments = mapOf(0 to listOf("m1", "m2"), 1 to listOf("m3", "m4")),
                originCount = 4,
                selectedMealIndex = 0
            ),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(name = "saving", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEditorSaving() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(listOf("午餐"), assignments = mapOf(0 to listOf("m1")), originCount = 2, isSaving = true),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(name = "save failed", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEditorSaveFailed() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(
                listOf("午餐"),
                assignments = mapOf(0 to listOf("m1")),
                originCount = 2,
                saveError = "保存失败，请重试"
            ),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(name = "large font", showBackground = true, heightDp = 720, fontScale = 1.6f)
@Composable
private fun PreviewEditorLargeFont() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(
                listOf("早餐", "午餐", "晚餐", "加餐"),
                assignments = mapOf(0 to listOf("m1")),
                originCount = 3
            ),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(name = "narrow", showBackground = true, widthDp = 300, heightDp = 620)
@Composable
private fun PreviewEditorNarrow() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(
                listOf("早餐", "午餐", "晚餐"),
                assignments = mapOf(0 to listOf("m1")),
                originCount = 4
            ),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

@Preview(
    name = "dark",
    showBackground = true,
    backgroundColor = 0xFF2A2925,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PreviewEditorDark() {
    MaterialTheme {
        PhotoAssignmentEditorScreen(
            state = previewState(
                listOf("早餐", "午餐"),
                assignments = mapOf(0 to listOf("m1")),
                originCount = 3
            ),
            mediaById = emptyMap(),
            actions = previewActions,
            onOpenViewer = { _, _ -> }
        )
    }
}

// endregion
