package com.lotuswallet.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lotuswallet.app.R
import com.lotuswallet.app.util.QrCode
import java.util.concurrent.Executors

/**
 * 扫码对话框（相机 + 相册兜底）。
 *
 * ## 为什么不用 ML Kit
 * ML Kit 的条码能力依赖 Google Play 服务与在线模型下载，在无 GMS 的国产 ROM 上
 * 直接不可用。ZXing 是纯 Java 实现，解码全在本机完成 —— 这也符合本产品
 * 「扫码内容（otpauth 密钥、地址）绝不出设备」的要求。
 *
 * ## 为什么保留「从相册选择」
 * 用户常常已经截图了二维码；相机权限被拒时它也是唯一的退路。
 * 相册选择走系统照片选择器，不需要读存储权限。
 */
@Composable
fun QrScannerDialog(
    onDecoded: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.scan_title),
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.let { QrCode.decode(it) }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            error = context.getString(R.string.scan_failed)
        } else if (!handled) {
            handled = true
            onDecoded(text)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (hasPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.Black, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CameraPreview(
                            onDecoded = { text ->
                                if (!handled) {
                                    handled = true
                                    onDecoded(text)
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.scan_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.scan_permission_required),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.scan_grant))
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.scan_from_gallery))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * CameraX 预览 + ImageAnalysis 解码。
 *
 * 只保留最新帧（`STRATEGY_KEEP_ONLY_LATEST`）：二维码解码是同步的，
 * 堆积帧只会让预览变卡。解码失败是常态（对焦中、角度不对），因此**不打日志、不报错**。
 */
@Composable
private fun CameraPreview(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analyzer ->
                analyzer.setAnalyzer(
                    executor,
                    ImageAnalysis.Analyzer { image -> analyzeFrame(image) { text -> currentOnDecoded(text) } },
                )
            }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxWidth().aspectRatio(1f))
}

/** 仅对 QR 感兴趣，其余格式直接跳过以省电。 */
private fun analyzeFrame(
    image: ImageProxy,
    onDecoded: (String) -> Unit,
) {
    try {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val rowStride = plane.rowStride
        if (rowStride <= 0) return
        val dataHeight = data.size / rowStride
        if (dataHeight <= 0) return

        val text = QrCode.decodeYuv(
            data = data,
            dataWidth = rowStride,
            dataHeight = dataHeight,
            width = image.width,
            height = image.height,
        )
        if (!text.isNullOrBlank()) onDecoded(text)
    } catch (_: Throwable) {
        // 解码失败是高频事件，静默即可
    } finally {
        image.close()
    }
}
