package com.mebit.app

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
import androidx.compose.ui.viewinterop.UIKitView
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberCameraAvailable(): Boolean = remember { AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null }

@Composable
actual fun rememberAppSettingsOpener(): () -> Unit = remember {
    val open: () -> Unit = {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    }
    open
}

@Composable
actual fun QrCameraView(onText: (String) -> Unit, onDenied: () -> Unit) {
    var granted by remember { mutableStateOf(false) }
    val currentOnDenied by rememberUpdatedState(onDenied)
    LaunchedEffect(Unit) {
        val allowed = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> true
            AVAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { answer ->
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { ok -> answer.resume(ok) }
            }
            else -> false
        }
        if (allowed) granted = true else currentOnDenied()
    }
    if (!granted) return
    val currentOnText by rememberUpdatedState(onText)
    val camera = remember { QrCaptureView { currentOnText(it) } }
    UIKitView(factory = { camera }, modifier = Modifier.fillMaxSize())
    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.stop() }
    }
}

/** A view whose layer shows the back camera, reporting the first QR the session reads on the main queue. */
@OptIn(ExperimentalForeignApi::class)
private class QrCaptureView(private val onText: (String) -> Unit) :
    UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private val session = AVCaptureSession()
    private val preview = AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }
    private var done = false

    init {
        layer.addSublayer(preview)
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, error = null) }
        val output = AVCaptureMetadataOutput()
        if (input != null && session.canAddInput(input) && session.canAddOutput(output)) {
            session.addInput(input)
            session.addOutput(output)
            output.setMetadataObjectsDelegate(this, queue = dispatch_get_main_queue())
            // Only after addOutput does the output know which types the session can read.
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }

    // startRunning blocks for a while, so Apple says to call it off the main queue.
    fun start() = dispatch_async(dispatch_get_global_queue(0L, 0u)) { session.startRunning() }

    fun stop() = dispatch_async(dispatch_get_global_queue(0L, 0u)) { session.stopRunning() }

    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        if (done) return
        val text = didOutputMetadataObjects.filterIsInstance<AVMetadataMachineReadableCodeObject>().firstNotNullOfOrNull { it.stringValue } ?: return
        done = true
        onText(text)
    }
}
