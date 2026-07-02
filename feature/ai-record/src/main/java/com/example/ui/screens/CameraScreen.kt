package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
import com.example.ui.theme.BorderNormal
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmBackground
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class CameraUiState {
    Preview,
    Capturing,
    CapturedPreview,
    Importing,
    Error
}

@Composable
fun CameraScreen(
    conversationId: String,
    viewModel: AiRecordViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraUiState by remember { mutableStateOf(CameraUiState.Preview) }
    var isPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasRequestedPermissionBefore by remember {
        mutableStateOf(
            context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
                .getBoolean("has_requested_camera", false)
        )
    }

    var shouldShowSettingsButton by remember { mutableStateOf(false) }
    var currentCaptureId by remember { mutableStateOf<String?>(null) }
    var currentCaptureFile by remember { mutableStateOf<File?>(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isPermissionGranted = granted
        hasRequestedPermissionBefore = true
        context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("has_requested_camera", true)
            .apply()
        
        if (!granted) {
            // Check if we should direct the user to Settings
            // We'll let the user decide by clicking on a rationale layout
            shouldShowSettingsButton = true
        }
    }

    // ON_RESUME listener to re-verify permissions
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val currentGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                isPermissionGranted = currentGranted
                if (currentGranted) {
                    shouldShowSettingsButton = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Cleanup all unsaved captures on exit
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            cleanupCameraDirectory(context)
        }
    }

    // Clean up temporary capture file when retaking
    val deleteCurrentCapture = {
        currentCaptureFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        currentCaptureFile = null
        currentCaptureId = null
    }

    // Back action intercept logic
    val handleBackPress = {
        if (cameraUiState == CameraUiState.CapturedPreview || cameraUiState == CameraUiState.Error) {
            deleteCurrentCapture()
            cameraUiState = CameraUiState.Preview
        } else {
            onBack()
        }
    }

    // Request permissions automatically on first enter if not granted
    LaunchedEffect(isPermissionGranted) {
        if (!isPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Handle camera selector changes
    LaunchedEffect(lensFacing) {
        cameraController.cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    }

    // Handle camera flash mode
    LaunchedEffect(flashMode) {
        cameraController.imageCaptureFlashMode = flashMode
    }

    // Album launcher setup (does not require camera permission)
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importPhotos(conversationId, uris.map { it.toString() })
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!isPermissionGranted) {
            PermissionDeniedLayout(
                shouldShowSettingsButton = shouldShowSettingsButton,
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                onBack = onBack
            )
        } else {
            // Render Camera preview/capture flow
            when (cameraUiState) {
                CameraUiState.Preview, CameraUiState.Capturing -> {
                    CameraPreviewLayout(
                        cameraController = cameraController,
                        lensFacing = lensFacing,
                        flashMode = flashMode,
                        isCapturing = cameraUiState == CameraUiState.Capturing,
                        onSwitchLens = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        onToggleFlash = {
                            flashMode = if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                                ImageCapture.FLASH_MODE_ON
                            } else {
                                ImageCapture.FLASH_MODE_OFF
                            }
                        },
                        onCapture = {
                            if (cameraUiState != CameraUiState.Preview) return@CameraPreviewLayout
                            cameraUiState = CameraUiState.Capturing
                            
                            val captureId = UUID.randomUUID().toString()
                            val cameraDir = File(context.cacheDir, "media/camera")
                            cameraDir.mkdirs()
                            val file = File(cameraDir, "capture-$captureId.jpg")

                            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                            cameraController.takePicture(
                                outputOptions,
                                cameraExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        mainExecutor.execute {
                                            currentCaptureId = captureId
                                            currentCaptureFile = file
                                            cameraUiState = CameraUiState.CapturedPreview
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        mainExecutor.execute {
                                            cameraUiState = CameraUiState.Error
                                            Toast.makeText(context, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        },
                        onOpenAlbum = {
                            pickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onBack = handleBackPress
                    )
                }

                CameraUiState.CapturedPreview -> {
                    CapturedPreviewLayout(
                        imageFile = currentCaptureFile,
                        onRetake = {
                            deleteCurrentCapture()
                            cameraUiState = CameraUiState.Preview
                        },
                        onUsePhoto = {
                            val file = currentCaptureFile
                            if (file != null && file.exists()) {
                                cameraUiState = CameraUiState.Importing
                                // Relative path from context.cacheDir: "media/camera/capture-captureId.jpg"
                                val relativePath = "media/camera/capture-${currentCaptureId}.jpg"
                                
                                viewModel.importCameraCapture(
                                    conversationId = conversationId,
                                    relativePath = relativePath
                                ) { success, mediaId ->
                                    if (success) {
                                        // Delete temp file after copying staging source successfully
                                        deleteCurrentCapture()
                                        onBack()
                                    } else {
                                        cameraUiState = CameraUiState.Error
                                    }
                                }
                            } else {
                                cameraUiState = CameraUiState.Error
                            }
                        }
                    )
                }

                CameraUiState.Importing -> {
                    ImportingLayout()
                }

                CameraUiState.Error -> {
                    ErrorLayout(
                        onRetry = {
                            cameraUiState = CameraUiState.Preview
                            deleteCurrentCapture()
                        },
                        onCancel = {
                            deleteCurrentCapture()
                            onBack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedLayout(
    shouldShowSettingsButton: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WarmBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Permission Denied",
                tint = Color(0xFFD32F2F),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "需要相机权限",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "DayZero 需要使用您的相机来拍摄食物照片，并为您进行多模态识别与分析。",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(36.dp))

            if (shouldShowSettingsButton) {
                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(26.dp),
                    color = BrandGreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("前往系统设置", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Surface(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(26.dp),
                    color = BrandGreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("授予相机权限", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                onClick = onBack,
                shape = RoundedCornerShape(26.dp),
                color = Color.Transparent,
                border = BorderNormal.copy(alpha = 0.5f).let { androidx.compose.foundation.BorderStroke(1.dp, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("返回", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewLayout(
    cameraController: LifecycleCameraController,
    lensFacing: Int,
    flashMode: Int,
    isCapturing: Boolean,
    onSwitchLens: () -> Unit,
    onToggleFlash: () -> Unit,
    onCapture: () -> Unit,
    onOpenAlbum: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        cameraController.bindToLifecycle(lifecycleOwner)
        onDispose {
            cameraController.unbind()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview View
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller = cameraController
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Light visual framing/guiding box overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 120.dp)
                .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(28.dp))
        )

        // Top controls bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Text(
                text = "AI 食物识别",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            IconButton(
                onClick = onToggleFlash,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                val icon = if (flashMode == ImageCapture.FLASH_MODE_ON) Icons.Default.FlashOn else Icons.Default.FlashOff
                Icon(icon, contentDescription = "Toggle Flash", tint = Color.White)
            }
        }

        // Bottom controls bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album Button
            IconButton(
                onClick = onOpenAlbum,
                modifier = Modifier
                    .size(54.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = "Photo Album",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Shutter Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f))
                    .clickable(enabled = !isCapturing) { onCapture() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(if (isCapturing) Color.Gray else Color.White)
                )
            }

            // Switch Camera Selector Button
            IconButton(
                onClick = onSwitchLens,
                modifier = Modifier
                    .size(54.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Cached,
                    contentDescription = "Switch Lens",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun CapturedPreviewLayout(
    imageFile: File?,
    onRetake: () -> Unit,
    onUsePhoto: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (imageFile != null && imageFile.exists()) {
            val painter = rememberAsyncImagePainter(model = imageFile)
            Image(
                painter = painter,
                contentDescription = "Captured Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // Overlay actions at the bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp, start = 32.dp, end = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Retake Button
            Row(
                modifier = Modifier
                    .height(54.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(27.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(27.dp))
                    .clickable { onRetake() }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Retake", tint = Color.White)
                Spacer(modifier = Modifier.size(8.dp))
                Text("重新拍摄", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            // Use Photo Button
            Row(
                modifier = Modifier
                    .height(54.dp)
                    .background(BrandGreen, RoundedCornerShape(27.dp))
                    .clickable { onUsePhoto() }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Done, contentDescription = "Use Photo", tint = Color.White)
                Spacer(modifier = Modifier.size(8.dp))
                Text("使用照片", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ImportingLayout() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.7f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = BrandGreen)
            Spacer(modifier = Modifier.height(16.dp))
            Text("正在导入...", color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ErrorLayout(
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WarmBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error",
                tint = Color(0xFFD32F2F),
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("导入失败", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("无法处理您所拍摄的照片，请重试或返回。", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(32.dp))

            Surface(
                onClick = onRetry,
                shape = RoundedCornerShape(26.dp),
                color = BrandGreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("重试", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                onClick = onCancel,
                shape = RoundedCornerShape(26.dp),
                color = Color.Transparent,
                border = BorderNormal.copy(alpha = 0.5f).let { androidx.compose.foundation.BorderStroke(1.dp, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("返回", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun cleanupCameraDirectory(context: Context) {
    try {
        val cameraDir = File(context.cacheDir, "media/camera")
        if (cameraDir.exists() && cameraDir.isDirectory) {
            cameraDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("capture-")) {
                    file.delete()
                }
            }
        }
    } catch (e: Exception) {
        // Suppress cleanup exception
    }
}
