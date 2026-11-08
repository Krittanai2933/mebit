package com.mebit.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberCameraAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
}

@Composable
actual fun rememberTorchAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }
}

@Composable
actual fun rememberAppSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        val open: () -> Unit = {
            val page = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(page) }
        }
        open
    }
}

@Composable
actual fun QrCameraView(torch: Boolean, onText: (String) -> Unit, onDenied: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val currentOnDenied by rememberUpdatedState(onDenied)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) granted = true else currentOnDenied() }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (!granted) return
    val owner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    // A TextureView composites like any view, so the aiming frame and the buttons draw over it on every device.
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    var camera by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(owner) {
        val decoder = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(decoder) { frame ->
            val text = try { decodeQr(frame) } finally { frame.close() }
            if (text != null) view.post { currentOnText(text) }
        }
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val pending = ProcessCameraProvider.getInstance(context)
        pending.addListener({
            if (disposed) return@addListener
            val cameraProvider = pending.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            // No back camera that CameraX can open leaves the sheet black with its close button, not a crash.
            camera = runCatching { cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }.getOrNull()
        }, { view.post(it) })
        onDispose {
            disposed = true
            camera = null
            provider?.unbindAll()
            decoder.shutdown()
        }
    }
}

private val reader = QRCodeReader()

/** The QR text in this frame's Y plane, or null when ZXing finds none. The reader runs on the one decoder thread. */
private fun decodeQr(frame: ImageProxy): String? {
    val plane = frame.planes[0]
    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, frame.height, 0, 0, frame.width, frame.height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (missed: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

@Composable
actual fun rememberQrPhotoPicker(onResult: (PhotoQr) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            currentOnResult(PhotoQr.Cancelled)
        } else {
            scope.launch {
                val text = withContext(Dispatchers.Default) { decodeQrFromImage(context, uri) }
                currentOnResult(if (text == null) PhotoQr.None else PhotoQr.Text(text))
            }
        }
    }
    return remember(launcher) {
        val open: () -> Unit = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        open
    }
}

private const val MAX_PHOTO_SIDE = 2_048

/** The QR text in the image at [uri], or null when it holds none ZXing can read or cannot be loaded. */
private fun decodeQrFromImage(context: Context, uri: Uri): String? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PHOTO_SIDE) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    if (bitmap == null) {
        null
    } else {
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    }
} catch (unreadable: Exception) {
    null
}
