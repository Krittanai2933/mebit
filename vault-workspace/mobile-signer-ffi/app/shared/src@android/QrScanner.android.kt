package com.mebit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

@Composable
actual fun rememberCameraAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
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
actual fun QrCameraView(onText: (String) -> Unit, onDenied: () -> Unit) {
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
    DisposableEffect(owner) {
        val decoder = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var found = false
        analysis.setAnalyzer(decoder) { frame ->
            val text = try { decodeQr(frame) } finally { frame.close() }
            if (text != null && !found) {
                found = true
                analysis.clearAnalyzer()
                view.post { currentOnText(text) }
            }
        }
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val pending = ProcessCameraProvider.getInstance(context)
        pending.addListener({
            if (disposed) return@addListener
            val camera = pending.get()
            provider = camera
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            // No back camera that CameraX can open leaves the sheet black with its close button, not a crash.
            runCatching { camera.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, { view.post(it) })
        onDispose {
            disposed = true
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
