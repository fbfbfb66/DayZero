package com.goings.dayzero.ui.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.goings.dayzero.ui.theme.BrandGreen
import com.goings.dayzero.ui.theme.TextSecondary
import com.goings.dayzero.ui.theme.TextTertiary
import kotlinx.coroutines.launch
import kotlin.math.abs

data class PhotoViewerItem(
    val mediaId: String,
    val masterRelativePath: String?,
    val thumbnailRelativePath: String?,
    val width: Int?,
    val height: Int?,
    val accessibilityLabel: String? = null
)

object PhotoViewerGeometry {
    const val BASE_SCALE = 1f
    const val MAX_SCALE = 4f
    const val SCALE_EPSILON = 0.01f

    fun clampScale(scale: Float): Float = when {
        !scale.isFinite() || scale <= BASE_SCALE + SCALE_EPSILON -> BASE_SCALE
        else -> scale.coerceAtMost(MAX_SCALE)
    }

    fun clampInitialIndex(initialIndex: Int, size: Int): Int {
        if (size <= 0) return 0
        return initialIndex.coerceIn(0, size - 1)
    }

    fun calculateDoubleTapScale(currentScale: Float): Float {
        return if (currentScale > 1.05f) 1f else 2.5f
    }

    fun calculateDisplayedSize(
        containerWidth: Float,
        containerHeight: Float,
        imageWidth: Float,
        imageHeight: Float
    ): Pair<Float, Float> {
        val imageAspectRatio = if (imageWidth > 0 && imageHeight > 0) imageWidth / imageHeight else 1f
        return if (containerWidth > 0 && containerHeight > 0) {
            if (containerWidth / containerHeight > imageAspectRatio) {
                Pair(containerHeight * imageAspectRatio, containerHeight)
            } else {
                Pair(containerWidth, containerWidth / imageAspectRatio)
            }
        } else {
            Pair(0f, 0f)
        }
    }

    fun calculateMaxOffset(
        scale: Float,
        containerWidth: Float,
        containerHeight: Float,
        displayedWidth: Float,
        displayedHeight: Float
    ): Offset {
        if (!scale.isFinite() || !containerWidth.isFinite() || !containerHeight.isFinite() ||
            !displayedWidth.isFinite() || !displayedHeight.isFinite()
        ) return Offset.Zero
        val maxOffsetX = ((displayedWidth * scale - containerWidth) / 2f).coerceAtLeast(0f)
        val maxOffsetY = ((displayedHeight * scale - containerHeight) / 2f).coerceAtLeast(0f)
        return Offset(maxOffsetX, maxOffsetY)
    }

    fun clampOffset(
        offset: Offset,
        scale: Float,
        containerWidth: Float,
        containerHeight: Float,
        displayedWidth: Float,
        displayedHeight: Float
    ): Offset {
        if (scale <= BASE_SCALE + SCALE_EPSILON || !offset.x.isFinite() || !offset.y.isFinite()) {
            return Offset.Zero
        }
        val max = calculateMaxOffset(scale, containerWidth, containerHeight, displayedWidth, displayedHeight)
        return Offset(offset.x.coerceIn(-max.x, max.x), offset.y.coerceIn(-max.y, max.y))
    }

    fun shouldDismiss(swipeOffsetY: Float, thresholdPx: Float = 300f): Boolean =
        abs(swipeOffsetY) >= thresholdPx
}

internal enum class PhotoViewerGestureMode { IDLE, PENDING, HORIZONTAL, DISMISS, PAN, PINCH, PINCH_END_WAIT }

internal class PhotoViewerGestureState {
    var mode: PhotoViewerGestureMode = PhotoViewerGestureMode.IDLE
        private set
    var dismissOffset: Float = 0f
        private set

    fun start() { mode = PhotoViewerGestureMode.PENDING }

    fun updatePointerCount(pointerCount: Int, scale: Float): PhotoViewerGestureMode {
        if (pointerCount >= 2) dismissOffset = 0f
        mode = when {
            pointerCount >= 2 -> PhotoViewerGestureMode.PINCH
            mode == PhotoViewerGestureMode.PINCH && scale > PhotoViewerGeometry.BASE_SCALE + PhotoViewerGeometry.SCALE_EPSILON -> PhotoViewerGestureMode.PAN
            mode == PhotoViewerGestureMode.PINCH -> PhotoViewerGestureMode.PINCH_END_WAIT
            else -> mode
        }
        return mode
    }

    fun resolveSingleDrag(
        total: Offset,
        touchSlop: Float,
        scale: Float,
        pagerIsScrolling: Boolean,
        verticalDominanceRatio: Float = 1.2f
    ): PhotoViewerGestureMode {
        if (mode != PhotoViewerGestureMode.PENDING) return mode
        if (total.getDistance() <= touchSlop) return mode
        mode = when {
            scale > PhotoViewerGeometry.BASE_SCALE + PhotoViewerGeometry.SCALE_EPSILON -> PhotoViewerGestureMode.PAN
            pagerIsScrolling -> PhotoViewerGestureMode.HORIZONTAL
            abs(total.y) > abs(total.x) * verticalDominanceRatio -> PhotoViewerGestureMode.DISMISS
            else -> PhotoViewerGestureMode.HORIZONTAL
        }
        return mode
    }

    fun canDismiss(scale: Float, pagerIsScrolling: Boolean): Boolean =
        mode == PhotoViewerGestureMode.DISMISS &&
            scale <= PhotoViewerGeometry.BASE_SCALE + PhotoViewerGeometry.SCALE_EPSILON &&
            !pagerIsScrolling

    fun updateDismissOffset(value: Float) {
        if (mode == PhotoViewerGestureMode.DISMISS && value.isFinite()) dismissOffset = value
    }

    fun reset() {
        mode = PhotoViewerGestureMode.IDLE
        dismissOffset = 0f
    }
}

internal class PhotoViewerDismissGate {
    private var started = false
    fun tryStart(): Boolean {
        if (started) return false
        started = true
        return true
    }
}

@Composable
fun PhotoViewerOverlay(
    items: List<PhotoViewerItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val clampedInitialIndex = PhotoViewerGeometry.clampInitialIndex(initialIndex, items.size)
    val pagerState = rememberPagerState(
        initialPage = clampedInitialIndex,
        pageCount = { items.size }
    )

    var pagerScrollable by remember { mutableStateOf(true) }
    var swipeOffsetY by remember { mutableStateOf(0f) }
    var isDismissing by remember { mutableStateOf(false) }
    var dismissProgress by remember { mutableStateOf(0f) }
    val dismissGate = remember { PhotoViewerDismissGate() }
    val coroutineScope = rememberCoroutineScope()

    val handleDismiss: () -> Unit = {
        if (dismissGate.tryStart()) {
            isDismissing = true
            coroutineScope.launch {
                animate(0f, 1f) { value, _ ->
                    dismissProgress = value
                }
                onDismiss()
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        swipeOffsetY = 0f
    }

    BackHandler(enabled = true) {
        handleDismiss()
    }

    val baseBackdropAlpha = (1f - (abs(swipeOffsetY) / 600f).coerceIn(0f, 1f)) * 0.95f
    val backdropAlpha = if (isDismissing) {
        baseBackdropAlpha * (1f - dismissProgress)
    } else {
        baseBackdropAlpha
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha))
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pagerScrollable,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            PhotoViewerPage(
                item = items[pageIndex],
                isSelected = pagerState.currentPage == pageIndex,
                isPagerScrollable = { scrollable ->
                    if (pagerState.currentPage == pageIndex) {
                        pagerScrollable = scrollable
                    }
                },
                isPagerScrolling = { pagerState.isScrollInProgress },
                onDismiss = handleDismiss,
                swipeOffsetY = if (pagerState.currentPage == pageIndex) swipeOffsetY else 0f,
                onSwipeOffsetYChanged = { newOffset ->
                    if (pagerState.currentPage == pageIndex) {
                        swipeOffsetY = newOffset
                    }
                },
                isDismissing = isDismissing && (pagerState.currentPage == pageIndex),
                dismissProgress = dismissProgress
            )
        }

        // Top Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = handleDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = "关闭大图查看器"
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White
                )
            }

            if (items.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "当前处于第 ${pagerState.currentPage + 1} 张图片，共 ${items.size} 张"
                    }
                )
            }

            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

@Composable
fun PhotoViewerPage(
    item: PhotoViewerItem,
    isSelected: Boolean,
    isPagerScrollable: (Boolean) -> Unit,
    isPagerScrolling: () -> Boolean,
    onDismiss: () -> Unit,
    swipeOffsetY: Float,
    onSwipeOffsetYChanged: (Float) -> Unit,
    isDismissing: Boolean = false,
    dismissProgress: Float = 0f,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    var containerWidth by remember { mutableStateOf(0) }
    var containerHeight by remember { mutableStateOf(0) }
    var imageWidth by remember { mutableStateOf(0f) }
    var imageHeight by remember { mutableStateOf(0f) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val masterFile = remember(item.masterRelativePath) {
        getSafeMasterMediaFile(context, item.masterRelativePath)
    }
    LaunchedEffect(isSelected) {
        if (!isSelected) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            onSwipeOffsetYChanged(0f)
        }
    }

    LaunchedEffect(scale) {
        isPagerScrollable(scale <= 1.01f)
        if (scale > 1.01f) {
            onSwipeOffsetYChanged(0f)
        }
    }

    val displayedSize = PhotoViewerGeometry.calculateDisplayedSize(
        containerWidth = containerWidth.toFloat(),
        containerHeight = containerHeight.toFloat(),
        imageWidth = imageWidth,
        imageHeight = imageHeight
    )
    val displayedWidth = displayedSize.first
    val displayedHeight = displayedSize.second

    LaunchedEffect(containerWidth, containerHeight, displayedWidth, displayedHeight, scale, isSelected) {
        if (!isSelected) return@LaunchedEffect
        scale = PhotoViewerGeometry.clampScale(scale)
        val clamped = PhotoViewerGeometry.clampOffset(
            Offset(offsetX, offsetY), scale,
            containerWidth.toFloat(), containerHeight.toFloat(), displayedWidth, displayedHeight
        )
        offsetX = clamped.x
        offsetY = clamped.y
    }

    // Use rememberUpdatedState to prevent long-lived closures from capturing stale values
    val currentOnSwipeOffsetYChanged by rememberUpdatedState(onSwipeOffsetYChanged)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentSwipeOffsetY by rememberUpdatedState(swipeOffsetY)
    val currentDisplayedWidth by rememberUpdatedState(displayedWidth)
    val currentDisplayedHeight by rememberUpdatedState(displayedHeight)
    val currentScale by rememberUpdatedState(scale)

    val pointerInputModifier = Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { pressOffset ->
                    val targetScale = PhotoViewerGeometry.calculateDoubleTapScale(currentScale)
                    if (targetScale == 1f) {
                        coroutineScope.launch {
                            animate(scale, 1f) { value, _ -> scale = value }
                        }
                        coroutineScope.launch {
                            animate(offsetX, 0f) { value, _ -> offsetX = value }
                        }
                        coroutineScope.launch {
                            animate(offsetY, 0f) { value, _ -> offsetY = value }
                        }
                    } else {
                        val centerX = containerWidth / 2f
                        val centerY = containerHeight / 2f
                        val targetX = -(pressOffset.x - centerX) * (targetScale - 1f)
                        val targetY = -(pressOffset.y - centerY) * (targetScale - 1f)

                        val maxOffset = PhotoViewerGeometry.calculateMaxOffset(
                            scale = targetScale,
                            containerWidth = containerWidth.toFloat(),
                            containerHeight = containerHeight.toFloat(),
                            displayedWidth = currentDisplayedWidth,
                            displayedHeight = currentDisplayedHeight
                        )
                        val clampedX = targetX.coerceIn(-maxOffset.x, maxOffset.x)
                        val clampedY = targetY.coerceIn(-maxOffset.y, maxOffset.y)

                        coroutineScope.launch {
                            animate(scale, targetScale) { value, _ -> scale = value }
                        }
                        coroutineScope.launch {
                            animate(offsetX, clampedX) { value, _ -> offsetX = value }
                        }
                        coroutineScope.launch {
                            animate(offsetY, clampedY) { value, _ -> offsetY = value }
                        }
                    }
                }
            )
        }
        .pointerInput(isSelected) {
            awaitPointerEventScope {
                val gesture = PhotoViewerGestureState()
                var startPosition = Offset.Zero
                var previousPosition = Offset.Zero
                var lastCentroid: Offset? = null
                var lastDistance: Float? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val activePointers = event.changes.filter { it.pressed }
                    if (activePointers.isEmpty()) {
                        val releasedDismissOffset = gesture.dismissOffset
                        if (gesture.canDismiss(currentScale, isPagerScrolling()) &&
                            PhotoViewerGeometry.shouldDismiss(releasedDismissOffset)
                        ) currentOnDismiss()
                        else if (releasedDismissOffset != 0f) coroutineScope.launch {
                            animate(releasedDismissOffset, 0f) { value, _ -> currentOnSwipeOffsetYChanged(value) }
                        }
                        gesture.reset()
                        lastCentroid = null
                        lastDistance = null
                        isPagerScrollable(currentScale <= 1.01f)
                        continue
                    }

                    if (gesture.mode == PhotoViewerGestureMode.IDLE) {
                        gesture.start()
                        startPosition = activePointers.first().position
                        previousPosition = startPosition
                    }

                    val previousMode = gesture.mode
                    val mode = gesture.updatePointerCount(activePointers.size, currentScale)
                    if (mode == PhotoViewerGestureMode.PINCH) {
                        if (previousMode != PhotoViewerGestureMode.PINCH) {
                            currentOnSwipeOffsetYChanged(0f)
                            lastCentroid = null
                            lastDistance = null
                        }
                        isPagerScrollable(false)
                        val p1 = activePointers[0].position
                        val p2 = activePointers[1].position
                        val centroid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                        val distance = (p1 - p2).getDistance()
                        if (lastCentroid != null && lastDistance != null && lastDistance!! > 0f) {
                            val oldScale = scale
                            scale = PhotoViewerGeometry.clampScale(scale * (distance / lastDistance!!))
                            val scaleChange = scale / oldScale
                            val pan = centroid - lastCentroid!!
                            val proposed = Offset(
                                centroid.x - (centroid.x - offsetX) * scaleChange + pan.x,
                                centroid.y - (centroid.y - offsetY) * scaleChange + pan.y
                            )
                            val clamped = PhotoViewerGeometry.clampOffset(
                                proposed, scale, containerWidth.toFloat(), containerHeight.toFloat(),
                                currentDisplayedWidth, currentDisplayedHeight
                            )
                            offsetX = clamped.x
                            offsetY = clamped.y
                        }
                        lastCentroid = centroid
                        lastDistance = distance
                        event.changes.forEach { it.consume() }
                        continue
                    }

                    val position = activePointers.first().position
                    if (previousMode == PhotoViewerGestureMode.PINCH) {
                        startPosition = position
                        previousPosition = position
                        currentOnSwipeOffsetYChanged(0f)
                    }
                    val resolvedMode = gesture.resolveSingleDrag(
                        position - startPosition, viewConfiguration.touchSlop,
                        currentScale, isPagerScrolling()
                    )
                    when (resolvedMode) {
                        PhotoViewerGestureMode.PAN -> {
                            val clamped = PhotoViewerGeometry.clampOffset(
                                Offset(offsetX, offsetY) + (position - previousPosition), currentScale,
                                containerWidth.toFloat(), containerHeight.toFloat(),
                                currentDisplayedWidth, currentDisplayedHeight
                            )
                            offsetX = clamped.x
                            offsetY = clamped.y
                            isPagerScrollable(false)
                            event.changes.forEach { it.consume() }
                        }
                        PhotoViewerGestureMode.DISMISS -> {
                            if (!isPagerScrolling()) {
                                gesture.updateDismissOffset(position.y - startPosition.y)
                                currentOnSwipeOffsetYChanged(gesture.dismissOffset)
                                isPagerScrollable(false)
                                event.changes.forEach { it.consume() }
                            }
                        }
                        PhotoViewerGestureMode.PINCH_END_WAIT -> event.changes.forEach { it.consume() }
                        else -> Unit
                    }
                    previousPosition = position
                }
            }
        }

    val dragScale = (1f - (abs(swipeOffsetY) / 1000f)).coerceIn(0.8f, 1f)
    val animatedScale = if (isDismissing) {
        val currentScale = scale * dragScale
        // 动画缩小至0，模拟吸入效果
        val targetScale = 0f
        currentScale * (1f - dismissProgress) + targetScale * dismissProgress
    } else {
        scale * dragScale
    }

    val animatedTransX = if (isDismissing) {
        // 吸入右上角：X 轴向右偏移 containerWidth / 2f
        val targetX = containerWidth / 2f
        offsetX * (1f - dismissProgress) + targetX * dismissProgress
    } else {
        offsetX
    }

    val animatedTransY = if (isDismissing) {
        // 吸入右上角：Y 轴向上偏移 -containerHeight / 2f
        val targetY = -containerHeight / 2f
        val currentY = offsetY + swipeOffsetY
        currentY * (1f - dismissProgress) + targetY * dismissProgress
    } else {
        offsetY + swipeOffsetY
    }

    val animatedRotationZ = if (isDismissing) 25f * dismissProgress else 0f
    val animatedRotationY = if (isDismissing) 35f * dismissProgress else 0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                containerWidth = it.width
                containerHeight = it.height
            }
            .then(pointerInputModifier),
        contentAlignment = Alignment.Center
    ) {
        var isError by remember { mutableStateOf(false) }
        val imageModel = masterFile

        if (imageModel != null && !isError) {
            SubcomposeAsyncImage(
                model = imageModel,
                contentDescription = item.accessibilityLabel ?: "大图查看",
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        translationX = animatedTransX
                        translationY = animatedTransY
                        rotationZ = animatedRotationZ
                        rotationY = animatedRotationY
                        cameraDistance = 8f
                        alpha = if (isDismissing) 1f - dismissProgress else 1f
                    },
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0 && size.height > 0) {
                        imageWidth = size.width
                        imageHeight = size.height
                    }
                },
                onError = {
                    isError = true
                },
                loading = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = BrandGreen)
                    }
                },
                error = {
                    PhotoPlaceholder(accessibilityLabel = item.accessibilityLabel)
                }
            )
        } else {
            PhotoPlaceholder(accessibilityLabel = item.accessibilityLabel)
        }
    }
}

@Composable
fun PhotoPlaceholder(
    accessibilityLabel: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFAF9F6)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.ImageNotSupported,
                contentDescription = accessibilityLabel ?: "图片未找到",
                tint = TextTertiary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "图片未找到",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}
